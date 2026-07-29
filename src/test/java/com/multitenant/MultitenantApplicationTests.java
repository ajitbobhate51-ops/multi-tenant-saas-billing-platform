package com.multitenant;

import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;

import javax.sql.DataSource;

import com.multitenant.tenant.ProvisioningStatus;
import com.multitenant.tenant.SchemaPerTenantConnectionProvider;
import com.multitenant.tenant.Tenant;
import com.multitenant.tenant.TenantContext;
import com.multitenant.tenant.TenantIdentifier;
import com.multitenant.tenant.TenantRepository;
import com.multitenant.tenant.TenantStatus;
import com.multitenant.tenant.TenantUser;
import com.multitenant.tenant.TenantUserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MultitenantApplicationTests {

	private static final String PLATFORM_ADMIN_TOKEN = "test-platform-admin-token";

	private static final String BOOTSTRAP_TOKEN = "test-bootstrap-token";

	private static final String PASSWORD = "change-me-123";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private EntityManagerFactory entityManagerFactory;

	@Autowired
	private SchemaPerTenantConnectionProvider connectionProvider;

	@Autowired
	private DataSource dataSource;

	@Autowired
	private TenantRepository tenantRepository;

	@Autowired
	private TenantUserRepository tenantUserRepository;

	@Autowired
	private JwtEncoder jwtEncoder;

	@BeforeEach
	void clearTenantContext() {
		TenantContext.clear();
	}

	@Test
	void contextLoads() {
	}

	@Test
	void protectedTenantEndpointRequiresToken() throws Exception {
		mockMvc.perform(get("/tenant")).andExpect(status().isUnauthorized());
		assertThat(TenantContext.getTenant()).isNull();
	}

	@Test
	void validJwtUsesAuthenticatedTenantWhenHeaderIsMissing() throws Exception {
		String token = registerBootstrapAndLogin("tenant_one", "Tenant One", "tenant_one", "admin@tenant-one.test");

		mockMvc.perform(get("/tenant").header("Authorization", bearer(token)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.tenant").value("tenant_one"));
		assertThat(TenantContext.getTenant()).isNull();
	}

	@Test
	void jpaUsesTenantSchemaFromJwt() throws Exception {
		String token = registerBootstrapAndLogin("tenant_jpa", "Tenant Jpa", "tenant_jpa", "admin@tenant-jpa.test");

		mockMvc.perform(get("/tenant/jpa").header("Authorization", bearer(token)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.tenant").value("tenant_jpa"))
				.andExpect(jsonPath("$.schema").value("tenant_jpa"));
	}

	@Test
	void rejectsUnsafeTenantHeader() throws Exception {
		String token = registerBootstrapAndLogin("tenant_safe", "Tenant Safe", "tenant_safe", "admin@tenant-safe.test");

		mockMvc.perform(get("/tenant").header("Authorization", bearer(token)).header("X-Tenant-ID", "tenant-one"))
				.andExpect(status().isBadRequest());
		assertThat(TenantContext.getTenant()).isNull();
	}

	@Test
	void rejectsReservedTenantIdentifiers() {
		assertThatThrownBy(() -> TenantIdentifier.normalize("pg_catalog"))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("reserved");
		assertThatThrownBy(() -> TenantIdentifier.normalize("information_schema"))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("reserved");
	}

	@Test
	void normalizesSafeTenantIdentifiers() {
		assertThat(TenantIdentifier.normalize(" Tenant_A1 ")).isEqualTo("tenant_a1");
		assertThat(TenantIdentifier.normalize(null)).isNull();
		assertThat(TenantIdentifier.normalize(" ")).isNull();
	}

	@Test
	void isolatesDataBetweenTenantSchemas() {
		registerTenant("tenant_a", "Tenant A", "tenant_a");
		registerTenant("tenant_b", "Tenant B", "tenant_b");
		insertUsageRecord("tenant_a", 1L, "alpha");
		insertUsageRecord("tenant_b", 1L, "bravo");

		assertThat(findUsageDescription("tenant_a", 1L)).isEqualTo("alpha");
		assertThat(findUsageDescription("tenant_b", 1L)).isEqualTo("bravo");
		assertThat(countUsageRecordsWithDescription("tenant_a", "bravo")).isZero();
		assertThat(countUsageRecordsWithDescription("tenant_b", "alpha")).isZero();
		assertThat(TenantContext.getTenant()).isNull();
	}

	@Test
	void connectionProviderResetsSchemaBeforeReturningConnectionToPool() throws Exception {
		registerTenant("tenant_reset", "Tenant Reset", "tenant_reset");
		Connection tenantConnection = connectionProvider.getConnection("tenant_reset");
		assertThat(tenantConnection.getSchema()).isEqualTo("tenant_reset");

		connectionProvider.releaseConnection("tenant_reset", tenantConnection);

		try (Connection connection = dataSource.getConnection()) {
			assertThat(connection.getSchema()).isEqualTo("public");
		}
	}

	@Test
	void registersAndProvisionsTenant() throws Exception {
		mockMvc.perform(post("/api/tenants")
				.header("X-Platform-Admin-Token", PLATFORM_ADMIN_TOKEN)
				.contentType("application/json")
				.content("""
						{"tenantId":"tenant_api","tenantName":"Tenant Api","schemaName":"tenant_api"}
						"""))
				.andExpect(status().isCreated())
				.andExpect(header().string("Location", "/api/tenants/tenant_api"))
				.andExpect(jsonPath("$.tenantId").value("tenant_api"))
				.andExpect(jsonPath("$.schemaName").value("tenant_api"))
				.andExpect(jsonPath("$.status").value("ACTIVE"))
				.andExpect(jsonPath("$.provisioningStatus").value("ACTIVE"));
	}

	@Test
	void tenantManagementRequiresPlatformAdminToken() throws Exception {
		mockMvc.perform(get("/api/tenants")).andExpect(status().isUnauthorized());
	}

	@Test
	void listsAndReadsTenantsWithPlatformAdminToken() throws Exception {
		registerTenant("tenant_read", "Tenant Read", "tenant_read");

		mockMvc.perform(get("/api/tenants/tenant_read")
				.header("X-Platform-Admin-Token", PLATFORM_ADMIN_TOKEN)
				.header("X-Tenant-ID", "missing_tenant"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.tenantId").value("tenant_read"));
		mockMvc.perform(get("/api/tenants").header("X-Platform-Admin-Token", PLATFORM_ADMIN_TOKEN))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[?(@.tenantId == 'tenant_read')]").exists());
	}

	@Test
	void preventsDuplicateTenantRegistration() throws Exception {
		registerTenant("tenant_dup", "Tenant Duplicate", "tenant_dup");

		mockMvc.perform(post("/api/tenants")
				.header("X-Platform-Admin-Token", PLATFORM_ADMIN_TOKEN)
				.contentType("application/json")
				.content("""
						{"tenantId":"tenant_dup","tenantName":"Other Duplicate","schemaName":"other_duplicate"}
						"""))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("TENANT_CONFLICT"));
	}

	@Test
	void preventsDuplicateSchemaRegistration() throws Exception {
		registerTenant("tenant_schema_one", "Tenant Schema One", "shared_schema");

		mockMvc.perform(post("/api/tenants")
				.header("X-Platform-Admin-Token", PLATFORM_ADMIN_TOKEN)
				.contentType("application/json")
				.content("""
						{"tenantId":"tenant_schema_two","tenantName":"Tenant Schema Two","schemaName":"shared_schema"}
						"""))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("TENANT_CONFLICT"));
	}

	@Test
	void rejectsInvalidTenantRegistrationInput() throws Exception {
		mockMvc.perform(post("/api/tenants")
				.header("X-Platform-Admin-Token", PLATFORM_ADMIN_TOKEN)
				.contentType("application/json")
				.content("""
						{"tenantId":"tenant-bad","tenantName":"Bad Tenant","schemaName":"tenant_bad"}
						"""))
				.andExpect(status().isBadRequest());

		mockMvc.perform(post("/api/tenants")
				.header("X-Platform-Admin-Token", PLATFORM_ADMIN_TOKEN)
				.contentType("application/json")
				.content("""
						{"tenantId":"public","tenantName":"Platform Tenant","schemaName":"public"}
						"""))
				.andExpect(status().isBadRequest());
	}

	@Test
	void unknownTenantLoginIsRejected() throws Exception {
		mockMvc.perform(post("/api/auth/login")
				.contentType("application/json")
				.content("""
						{"tenantId":"unknown_tenant","email":"admin@unknown.test","password":"change-me-123"}
						"""))
				.andExpect(status().isNotFound());
	}

	@Test
	void inactiveTenantLoginIsRejected() throws Exception {
		saveRegistryRecord("inactive_tenant", "Inactive Tenant", "inactive_tenant", TenantStatus.PENDING,
				ProvisioningStatus.PENDING);

		mockMvc.perform(post("/api/auth/login")
				.contentType("application/json")
				.content("""
						{"tenantId":"inactive_tenant","email":"admin@inactive.test","password":"change-me-123"}
						"""))
				.andExpect(status().isForbidden());
		assertThat(TenantContext.getTenant()).isNull();
	}

	@Test
	void provisioningFailureMarksTenantFailed() throws Exception {
		createBrokenTenantSchema("tenant_fail");

		mockMvc.perform(post("/api/tenants")
				.header("X-Platform-Admin-Token", PLATFORM_ADMIN_TOKEN)
				.contentType("application/json")
				.content("""
						{"tenantId":"tenant_fail","tenantName":"Tenant Fail","schemaName":"tenant_fail"}
						"""))
				.andExpect(status().isInternalServerError())
				.andExpect(jsonPath("$.code").value("TENANT_PROVISIONING_FAILED"));

		Tenant tenant = tenantRepository.findById("tenant_fail").orElseThrow();
		assertThat(tenant.getStatus()).isEqualTo(TenantStatus.FAILED);
		assertThat(tenant.getProvisioningStatus()).isEqualTo(ProvisioningStatus.FAILED);
		assertThat(tenant.getFailureMessage()).isNotBlank();
	}

	@Test
	void provisioningRetryIsIdempotent() throws Exception {
		createBrokenTenantSchema("tenant_retry");
		mockMvc.perform(post("/api/tenants")
				.header("X-Platform-Admin-Token", PLATFORM_ADMIN_TOKEN)
				.contentType("application/json")
				.content("""
						{"tenantId":"tenant_retry","tenantName":"Tenant Retry","schemaName":"tenant_retry"}
						"""))
				.andExpect(status().isInternalServerError());

		dropBrokenTenantTable("tenant_retry");
		mockMvc.perform(post("/api/tenants/tenant_retry/provision")
				.header("X-Platform-Admin-Token", PLATFORM_ADMIN_TOKEN))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.tenantId").value("tenant_retry"))
				.andExpect(jsonPath("$.status").value("ACTIVE"));

		mockMvc.perform(post("/api/tenants/tenant_retry/provision")
				.header("X-Platform-Admin-Token", PLATFORM_ADMIN_TOKEN))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.tenantId").value("tenant_retry"))
				.andExpect(jsonPath("$.status").value("ACTIVE"));
	}

	@Test
	void bootstrapEncodesPasswordAndDoesNotExposeHash() throws Exception {
		registerTenant("tenant_bootstrap", "Tenant Bootstrap", "tenant_bootstrap");

		mockMvc.perform(post("/api/tenants/tenant_bootstrap/users/bootstrap")
				.header("X-Bootstrap-Token", BOOTSTRAP_TOKEN)
				.contentType("application/json")
				.content("""
						{"email":"admin@bootstrap.test","password":"change-me-123"}
						"""))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.email").value("admin@bootstrap.test"))
				.andExpect(jsonPath("$.passwordHash").doesNotExist());

		TenantUser user = executeInTenant("tenant_bootstrap",
				entityManager -> tenantUserRepository.findByEmailIgnoreCase("admin@bootstrap.test").orElseThrow());
		assertThat(user.getPasswordHash()).isNotEqualTo(PASSWORD);
		assertThat(user.getPasswordHash()).startsWith("$2");
	}

	@Test
	void bootstrapRejectsInvalidBootstrapToken() throws Exception {
		registerTenant("tenant_no_bootstrap", "Tenant No Bootstrap", "tenant_no_bootstrap");

		mockMvc.perform(post("/api/tenants/tenant_no_bootstrap/users/bootstrap")
				.header("X-Bootstrap-Token", "wrong")
				.contentType("application/json")
				.content("""
						{"email":"admin@no-bootstrap.test","password":"change-me-123"}
						"""))
				.andExpect(status().isForbidden());
	}

	@Test
	void loginReturnsJwtForTenantUser() throws Exception {
		registerBootstrapAndLogin("tenant_login", "Tenant Login", "tenant_login", "admin@login.test");
	}

	@Test
	void wrongPasswordReturnsUnauthorized() throws Exception {
		registerTenant("tenant_wrong_password", "Tenant Wrong Password", "tenant_wrong_password");
		bootstrapUser("tenant_wrong_password", "admin@wrong-password.test");

		mockMvc.perform(post("/api/auth/login")
				.contentType("application/json")
				.content("""
						{"tenantId":"tenant_wrong_password","email":"admin@wrong-password.test","password":"wrong-password"}
						"""))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
	}

	@Test
	void invalidOrExpiredTokenReturnsUnauthorized() throws Exception {
		String token = registerBootstrapAndLogin("tenant_expired", "Tenant Expired", "tenant_expired", "admin@expired.test");

		mockMvc.perform(get("/customers").header("Authorization", "Bearer not-a-jwt"))
				.andExpect(status().isUnauthorized());
		mockMvc.perform(get("/customers").header("Authorization", bearer(expiredToken("tenant_expired", "admin@expired.test"))))
				.andExpect(status().isUnauthorized());
		mockMvc.perform(get("/customers").header("Authorization", bearer(token)))
				.andExpect(status().isOk());
	}

	@Test
	void tenantJwtCannotAccessAnotherTenantByChangingHeader() throws Exception {
		String tenantAToken = registerBootstrapAndLogin("tenant_cross_a", "Tenant Cross A", "tenant_cross_a",
				"admin@cross-a.test");
		registerBootstrapAndLogin("tenant_cross_b", "Tenant Cross B", "tenant_cross_b", "admin@cross-b.test");

		mockMvc.perform(get("/customers")
				.header("Authorization", bearer(tenantAToken))
				.header("X-Tenant-ID", "tenant_cross_b"))
				.andExpect(status().isForbidden());
	}

	@Test
	void customerDataRemainsIsolatedWithJwtAuthentication() throws Exception {
		String tenantAToken = registerBootstrapAndLogin("tenant_customer_a", "Tenant Customer A", "tenant_customer_a",
				"admin@customer-a.test");
		String tenantBToken = registerBootstrapAndLogin("tenant_customer_b", "Tenant Customer B", "tenant_customer_b",
				"admin@customer-b.test");

		mockMvc.perform(post("/customers")
				.header("Authorization", bearer(tenantAToken))
				.contentType("application/json")
				.content("""
						{"name":"Alice A"}
						"""))
				.andExpect(status().isCreated());
		mockMvc.perform(post("/customers")
				.header("Authorization", bearer(tenantBToken))
				.contentType("application/json")
				.content("""
						{"name":"Bob B"}
						"""))
				.andExpect(status().isCreated());

		mockMvc.perform(get("/customers").header("Authorization", bearer(tenantAToken)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].name").value("Alice A"))
				.andExpect(jsonPath("$[?(@.name == 'Bob B')]").doesNotExist());
		mockMvc.perform(get("/customers").header("Authorization", bearer(tenantBToken)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].name").value("Bob B"))
				.andExpect(jsonPath("$[?(@.name == 'Alice A')]").doesNotExist());
	}

	private void insertUsageRecord(String tenant, Long id, String description) {
		executeInTenant(tenant, entityManager -> {
			entityManager.createNativeQuery("create table if not exists phase1_usage_record "
					+ "(id bigint primary key, description varchar(255))").executeUpdate();
			entityManager.createNativeQuery("merge into phase1_usage_record (id, description) key(id) values (:id, :description)")
					.setParameter("id", id)
					.setParameter("description", description)
					.executeUpdate();
			return null;
		});
	}

	private String findUsageDescription(String tenant, Long id) {
		return executeInTenant(tenant, entityManager -> (String) entityManager
				.createNativeQuery("select description from phase1_usage_record where id = :id", String.class)
				.setParameter("id", id)
				.getSingleResult());
	}

	private long countUsageRecordsWithDescription(String tenant, String description) {
		Number count = executeInTenant(tenant, entityManager -> (Number) entityManager
				.createNativeQuery("select count(*) from phase1_usage_record where description = :description", Long.class)
				.setParameter("description", description)
				.getSingleResult());
		return count.longValue();
	}

	private void registerTenant(String tenantId, String tenantName, String schemaName) {
		try {
			mockMvc.perform(post("/api/tenants")
					.header("X-Platform-Admin-Token", PLATFORM_ADMIN_TOKEN)
					.contentType("application/json")
					.content("""
							{"tenantId":"%s","tenantName":"%s","schemaName":"%s"}
							""".formatted(tenantId, tenantName, schemaName)))
					.andExpect(status().isCreated());
		}
		catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

	private String registerBootstrapAndLogin(String tenantId, String tenantName, String schemaName, String email)
			throws Exception {
		registerTenant(tenantId, tenantName, schemaName);
		bootstrapUser(tenantId, email);
		return login(tenantId, email, PASSWORD);
	}

	private void bootstrapUser(String tenantId, String email) throws Exception {
		mockMvc.perform(post("/api/tenants/{tenantId}/users/bootstrap", tenantId)
				.header("X-Bootstrap-Token", BOOTSTRAP_TOKEN)
				.contentType("application/json")
				.content("""
						{"email":"%s","password":"%s"}
						""".formatted(email, PASSWORD)))
				.andExpect(status().isCreated());
	}

	private String login(String tenantId, String email, String password) throws Exception {
		String response = mockMvc.perform(post("/api/auth/login")
				.contentType("application/json")
				.content("""
						{"tenantId":"%s","email":"%s","password":"%s"}
						""".formatted(tenantId, email, password)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.tokenType").value("Bearer"))
				.andExpect(jsonPath("$.tenantId").value(tenantId))
				.andReturn()
				.getResponse()
				.getContentAsString();
		return response.replaceAll(".*\\\"accessToken\\\":\\\"([^\\\"]+)\\\".*", "$1");
	}

	private String bearer(String token) {
		return "Bearer " + token;
	}

	private String expiredToken(String tenantId, String email) {
		Instant now = Instant.now();
		JwtClaimsSet claims = JwtClaimsSet.builder()
				.issuer("multitenant-test")
				.issuedAt(now.minusSeconds(120))
				.expiresAt(now.minusSeconds(60))
				.subject(email)
				.claim("tenant_id", tenantId)
				.claim("role", "TENANT_ADMIN")
				.build();
		return jwtEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
				.getTokenValue();
	}

	private void saveRegistryRecord(String tenantId, String tenantName, String schemaName, TenantStatus status,
			ProvisioningStatus provisioningStatus) {
		Tenant tenant = new Tenant();
		tenant.setTenantId(tenantId);
		tenant.setTenantName(tenantName);
		tenant.setSchemaName(schemaName);
		tenant.setStatus(status);
		tenant.setProvisioningStatus(provisioningStatus);
		tenantRepository.saveAndFlush(tenant);
	}

	private void createBrokenTenantSchema(String schemaName) throws Exception {
		try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
			connection.setSchema("public");
			statement.execute("create schema if not exists " + schemaName);
			statement.execute("create table " + schemaName + ".tenant_schema_info (id bigint primary key)");
		}
	}

	private void dropBrokenTenantTable(String schemaName) throws Exception {
		try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
			connection.setSchema("public");
			statement.execute("drop table if exists " + schemaName + ".tenant_schema_info");
		}
	}

	private <T> T executeInTenant(String tenant, TenantEntityManagerCallback<T> callback) {
		TenantContext.setTenant(tenant);
		EntityManager entityManager = entityManagerFactory.createEntityManager();
		try {
			entityManager.getTransaction().begin();
			T result = callback.doInEntityManager(entityManager);
			entityManager.getTransaction().commit();
			return result;
		}
		catch (RuntimeException ex) {
			if (entityManager.getTransaction().isActive()) {
				entityManager.getTransaction().rollback();
			}
			throw ex;
		}
		finally {
			entityManager.close();
			TenantContext.clear();
		}
	}

	private interface TenantEntityManagerCallback<T> {

		T doInEntityManager(EntityManager entityManager);
	}
}
