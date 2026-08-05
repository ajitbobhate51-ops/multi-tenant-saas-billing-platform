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
import com.multitenant.tenant.TenantProvisioningService;
import com.multitenant.tenant.TenantRepository;
import com.multitenant.tenant.TenantStatus;
import com.multitenant.tenant.TenantUser;
import com.multitenant.tenant.TenantUserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.flywaydb.core.Flyway;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
	private TenantProvisioningService tenantProvisioningService;

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
	void migratesActiveTenantSchemasWithPendingBillingAndSubscriptionMigrations() throws Exception {
		createTenantSchemaMigratedThroughV3("tenant_legacy_v3");
		saveRegistryRecord("tenant_legacy_v3", "Tenant Legacy V3", "tenant_legacy_v3", TenantStatus.ACTIVE,
				ProvisioningStatus.ACTIVE);

		assertThat(tableExists("tenant_legacy_v3", "customers")).isTrue();
		assertThat(tableExists("tenant_legacy_v3", "tenant_users")).isTrue();
		assertThat(tableExists("tenant_legacy_v3", "billing_plans")).isFalse();
		assertThat(tableExists("tenant_legacy_v3", "subscriptions")).isFalse();

		tenantProvisioningService.migrateActiveTenants();

		assertThat(tableExists("tenant_legacy_v3", "billing_plans")).isTrue();
		assertThat(tableExists("tenant_legacy_v3", "subscriptions")).isTrue();
		assertThat(successfulTenantMigrationCount("tenant_legacy_v3", "4")).isEqualTo(1);
		assertThat(successfulTenantMigrationCount("tenant_legacy_v3", "5")).isEqualTo(1);
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

	@Test
	void tenantAdminCanCreateListDisableTenantUserAndDisabledUserCannotLogin() throws Exception {
		String adminToken = registerBootstrapAndLogin("tenant_rbac_admin", "Tenant Rbac Admin", "tenant_rbac_admin",
				"admin@rbac-admin.test");

		long userId = createTenantUser(adminToken, "user@rbac-admin.test", "TENANT_USER")
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.email").value("user@rbac-admin.test"))
				.andExpect(jsonPath("$.role").value("TENANT_USER"))
				.andExpect(jsonPath("$.passwordHash").doesNotExist())
				.andReturn()
				.getResponse()
				.getContentAsString()
				.replaceAll(".*\\\"id\\\":([0-9]+).*", "$1")
				.lines()
				.mapToLong(Long::parseLong)
				.findFirst()
				.orElseThrow();

		TenantUser created = executeInTenant("tenant_rbac_admin",
				entityManager -> tenantUserRepository.findByEmailIgnoreCase("user@rbac-admin.test").orElseThrow());
		assertThat(created.getPasswordHash()).isNotEqualTo(PASSWORD);
		assertThat(created.getPasswordHash()).startsWith("$2");

		String userToken = login("tenant_rbac_admin", "user@rbac-admin.test", PASSWORD);
		mockMvc.perform(get("/customers").header("Authorization", bearer(userToken)))
				.andExpect(status().isOk());
		mockMvc.perform(get("/api/tenant/users").header("Authorization", bearer(userToken)))
				.andExpect(status().isForbidden());
		mockMvc.perform(post("/api/tenant/users")
				.header("Authorization", bearer(userToken))
				.contentType("application/json")
				.content("""
						{"email":"blocked@rbac-admin.test","password":"change-me-123","role":"TENANT_USER"}
						"""))
				.andExpect(status().isForbidden());

		mockMvc.perform(get("/api/tenant/users").header("Authorization", bearer(adminToken)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[?(@.email == 'admin@rbac-admin.test')]").exists())
				.andExpect(jsonPath("$[?(@.email == 'user@rbac-admin.test')]").exists());

		mockMvc.perform(patch("/api/tenant/users/{userId}/enabled", userId)
				.header("Authorization", bearer(adminToken))
				.contentType("application/json")
				.content("""
						{"enabled":false}
						"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.enabled").value(false));

		mockMvc.perform(post("/api/auth/login")
				.contentType("application/json")
				.content("""
						{"tenantId":"tenant_rbac_admin","email":"user@rbac-admin.test","password":"change-me-123"}
						"""))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
	}

	@Test
	void tenantUserManagementRejectsDuplicateEmailAndInvalidRole() throws Exception {
		String adminToken = registerBootstrapAndLogin("tenant_rbac_duplicate", "Tenant Rbac Duplicate",
				"tenant_rbac_duplicate", "admin@rbac-duplicate.test");
		createTenantUser(adminToken, "user@rbac-duplicate.test", "TENANT_USER").andExpect(status().isCreated());

		createTenantUser(adminToken, "USER@rbac-duplicate.test", "TENANT_USER")
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("TENANT_CONFLICT"));
		mockMvc.perform(post("/api/tenant/users")
				.header("Authorization", bearer(adminToken))
				.contentType("application/json")
				.content("""
						{"email":"bad-role@rbac-duplicate.test","password":"change-me-123","role":"PLATFORM_ADMIN"}
						"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("BAD_REQUEST"));
	}

	@Test
	void tenantAdminCannotManageAnotherTenantsUsers() throws Exception {
		String tenantAToken = registerBootstrapAndLogin("tenant_rbac_cross_a", "Tenant Rbac Cross A",
				"tenant_rbac_cross_a", "admin@rbac-cross-a.test");
		registerBootstrapAndLogin("tenant_rbac_cross_b", "Tenant Rbac Cross B", "tenant_rbac_cross_b",
				"admin@rbac-cross-b.test");

		mockMvc.perform(get("/api/tenant/users")
				.header("Authorization", bearer(tenantAToken))
				.header("X-Tenant-ID", "tenant_rbac_cross_b"))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("TENANT_ACCESS_DENIED"));
		mockMvc.perform(post("/api/tenant/users")
				.header("Authorization", bearer(tenantAToken))
				.header("X-Tenant-ID", "tenant_rbac_cross_b")
				.contentType("application/json")
				.content("""
						{"email":"user@rbac-cross-b.test","password":"change-me-123","role":"TENANT_USER"}
						"""))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("TENANT_ACCESS_DENIED"));
	}

	@Test
	void platformAdministrationRemainsSeparateFromTenantJwtRoles() throws Exception {
		String tenantAdminToken = registerBootstrapAndLogin("tenant_rbac_platform", "Tenant Rbac Platform",
				"tenant_rbac_platform", "admin@rbac-platform.test");

		mockMvc.perform(get("/api/tenants").header("Authorization", bearer(tenantAdminToken)))
				.andExpect(status().isForbidden());
		mockMvc.perform(get("/api/tenants").header("Authorization",
				bearer(tokenWithRole("tenant_rbac_platform", "admin@rbac-platform.test", "PLATFORM_ADMIN"))))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
		mockMvc.perform(get("/api/tenants").header("X-Platform-Admin-Token", PLATFORM_ADMIN_TOKEN))
				.andExpect(status().isOk());
	}
	@Test
	void tenantAdminCanReEnableTenantUserAndUserCanLoginAgain() throws Exception {
		String adminToken = registerBootstrapAndLogin("tenant_rbac_reenable", "Tenant Rbac Reenable",
				"tenant_rbac_reenable", "admin@rbac-reenable.test");
		long userId = createTenantUserAndReturnId(adminToken, "user@rbac-reenable.test", "TENANT_USER");

		mockMvc.perform(patch("/api/tenant/users/{userId}/enabled", userId)
				.header("Authorization", bearer(adminToken))
				.contentType("application/json")
				.content("""
						{"enabled":false}
						"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.enabled").value(false));

		mockMvc.perform(post("/api/auth/login")
				.contentType("application/json")
				.content("""
						{"tenantId":"tenant_rbac_reenable","email":"user@rbac-reenable.test","password":"change-me-123"}
						"""))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));

		mockMvc.perform(patch("/api/tenant/users/{userId}/enabled", userId)
				.header("Authorization", bearer(adminToken))
				.contentType("application/json")
				.content("""
						{"enabled":true}
						"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.enabled").value(true))
				.andExpect(jsonPath("$.passwordHash").doesNotExist())
				.andExpect(jsonPath("$.password").doesNotExist());

		String userToken = login("tenant_rbac_reenable", "user@rbac-reenable.test", PASSWORD);
		mockMvc.perform(get("/customers").header("Authorization", bearer(userToken)))
				.andExpect(status().isOk());
	}

	@Test
	void tenantAdminPatchMissingUserReturnsNotFound() throws Exception {
		String adminToken = registerBootstrapAndLogin("tenant_rbac_missing_user", "Tenant Rbac Missing User",
				"tenant_rbac_missing_user", "admin@rbac-missing-user.test");

		mockMvc.perform(patch("/api/tenant/users/{userId}/enabled", 999999L)
				.header("Authorization", bearer(adminToken))
				.contentType("application/json")
				.content("""
						{"enabled":false}
						"""))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("TENANT_USER_NOT_FOUND"));
	}

	@Test
	void tenantUserCreateValidationRejectsInvalidInput() throws Exception {
		String adminToken = registerBootstrapAndLogin("tenant_rbac_validation", "Tenant Rbac Validation",
				"tenant_rbac_validation", "admin@rbac-validation.test");

		assertCreateTenantUserValidationFails(adminToken, """
				{"email":"not-an-email","password":"change-me-123","role":"TENANT_USER"}
				""");
		assertCreateTenantUserValidationFails(adminToken, """
				{"email":"","password":"change-me-123","role":"TENANT_USER"}
				""");
		assertCreateTenantUserValidationFails(adminToken, """
				{"email":"short-password@rbac-validation.test","password":"short","role":"TENANT_USER"}
				""");
		assertCreateTenantUserValidationFails(adminToken, """
				{"email":"blank-password@rbac-validation.test","password":"","role":"TENANT_USER"}
				""");
		assertCreateTenantUserValidationFails(adminToken, """
				{"email":"missing-role@rbac-validation.test","password":"change-me-123"}
				""");
	}

	@Test
	void tenantUserEnabledPatchValidationRejectsMissingOrNullEnabled() throws Exception {
		String adminToken = registerBootstrapAndLogin("tenant_rbac_enabled_validation", "Tenant Rbac Enabled Validation",
				"tenant_rbac_enabled_validation", "admin@rbac-enabled-validation.test");
		long userId = createTenantUserAndReturnId(adminToken, "user@rbac-enabled-validation.test", "TENANT_USER");

		assertUpdateEnabledValidationFails(adminToken, userId, "{}");
		assertUpdateEnabledValidationFails(adminToken, userId, """
				{"enabled":null}
				""");
	}

	@Test
	void tenantUserManagementListAndPatchResponsesDoNotExposeSensitivePasswordFields() throws Exception {
		String adminToken = registerBootstrapAndLogin("tenant_rbac_sensitive", "Tenant Rbac Sensitive",
				"tenant_rbac_sensitive", "admin@rbac-sensitive.test");
		long userId = createTenantUserAndReturnId(adminToken, "user@rbac-sensitive.test", "TENANT_USER");

		mockMvc.perform(get("/api/tenant/users").header("Authorization", bearer(adminToken)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[*].passwordHash").doesNotExist())
				.andExpect(jsonPath("$[*].password").doesNotExist());

		mockMvc.perform(patch("/api/tenant/users/{userId}/enabled", userId)
				.header("Authorization", bearer(adminToken))
				.contentType("application/json")
				.content("""
						{"enabled":false}
						"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.passwordHash").doesNotExist())
				.andExpect(jsonPath("$.password").doesNotExist());
	}
	@Test
	void tenantAdminCreatesMonthlyAndYearlyPlansAndTenantUserCanReadThem() throws Exception {
		String adminToken = registerBootstrapAndLogin("tenant_plans_basic", "Tenant Plans Basic", "tenant_plans_basic",
				"admin@plans-basic.test");
		createTenantUser(adminToken, "user@plans-basic.test", "TENANT_USER").andExpect(status().isCreated());
		String userToken = login("tenant_plans_basic", "user@plans-basic.test", PASSWORD);

		long monthlyPlanId = createBillingPlan(adminToken, " basic ", "Basic Plan", "Basic monthly plan", "499.00", "inr",
				"MONTHLY")
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.code").value("BASIC"))
				.andExpect(jsonPath("$.currency").value("INR"))
				.andExpect(jsonPath("$.billingInterval").value("MONTHLY"))
				.andExpect(jsonPath("$.active").value(true))
				.andReturn()
				.getResponse()
				.getContentAsString()
				.replaceAll(".*\\\"id\\\":([0-9]+).*", "$1")
				.lines()
				.mapToLong(Long::parseLong)
				.findFirst()
				.orElseThrow();

		createBillingPlan(adminToken, "PRO", "Pro Plan", "Annual pro plan", "4999.00", "USD", "YEARLY")
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.code").value("PRO"))
				.andExpect(jsonPath("$.billingInterval").value("YEARLY"))
				.andExpect(jsonPath("$.active").value(true));

		Object[] stored = executeInTenant("tenant_plans_basic", entityManager -> (Object[]) entityManager
				.createNativeQuery("select code, currency, amount from billing_plans where code = 'BASIC'")
				.getSingleResult());
		assertThat(stored[0]).isEqualTo("BASIC");
		assertThat(stored[1]).isEqualTo("INR");
		assertThat(stored[2].toString()).isEqualTo("499.00");

		mockMvc.perform(get("/api/plans").header("Authorization", bearer(adminToken)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[?(@.code == 'BASIC')]").exists())
				.andExpect(jsonPath("$[?(@.code == 'PRO')]").exists());
		mockMvc.perform(get("/api/plans/{planId}", monthlyPlanId).header("Authorization", bearer(adminToken)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.code").value("BASIC"));
		mockMvc.perform(get("/api/plans").header("Authorization", bearer(userToken)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[?(@.code == 'BASIC')]").exists());
		mockMvc.perform(get("/api/plans/{planId}", monthlyPlanId).header("Authorization", bearer(userToken)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.code").value("BASIC"));
	}

	@Test
	void tenantUserCannotCreateOrEnableDisablePlans() throws Exception {
		String adminToken = registerBootstrapAndLogin("tenant_plans_user_forbidden", "Tenant Plans User Forbidden",
				"tenant_plans_user_forbidden", "admin@plans-user-forbidden.test");
		createTenantUser(adminToken, "user@plans-user-forbidden.test", "TENANT_USER").andExpect(status().isCreated());
		String userToken = login("tenant_plans_user_forbidden", "user@plans-user-forbidden.test", PASSWORD);
		long planId = createBillingPlanAndReturnId(adminToken, "BASIC", "Basic Plan", "499.00", "INR", "MONTHLY");

		createBillingPlan(userToken, "USERPLAN", "User Plan", "499.00", "INR", "MONTHLY")
				.andExpect(status().isForbidden());
		mockMvc.perform(patch("/api/plans/{planId}/enabled", planId)
				.header("Authorization", bearer(userToken))
				.contentType("application/json")
				.content("""
						{"enabled":false}
						"""))
				.andExpect(status().isForbidden());
	}

	@Test
	void planEndpointsRejectUnauthenticatedAndPlatformAdminOnlyRequests() throws Exception {
		mockMvc.perform(post("/api/plans")
				.contentType("application/json")
				.content(planJson("BASIC", "Basic Plan", null, "499.00", "INR", "MONTHLY")))
				.andExpect(status().isUnauthorized());
		mockMvc.perform(get("/api/plans"))
				.andExpect(status().isUnauthorized());
		mockMvc.perform(get("/api/plans/{planId}", 1L))
				.andExpect(status().isUnauthorized());
		mockMvc.perform(get("/api/plans").header("X-Platform-Admin-Token", PLATFORM_ADMIN_TOKEN))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void billingPlanDuplicateCodeIsRejectedAfterNormalization() throws Exception {
		String adminToken = registerBootstrapAndLogin("tenant_plans_duplicate", "Tenant Plans Duplicate",
				"tenant_plans_duplicate", "admin@plans-duplicate.test");
		createBillingPlan(adminToken, "basic", "Basic Plan", "499.00", "INR", "MONTHLY")
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.code").value("BASIC"));

		createBillingPlan(adminToken, " BASIC ", "Other Basic", "699.00", "INR", "MONTHLY")
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("TENANT_CONFLICT"));
	}

	@Test
	void billingPlanMissingPlanAndInvalidRequestsReturnJsonErrors() throws Exception {
		String adminToken = registerBootstrapAndLogin("tenant_plans_validation", "Tenant Plans Validation",
				"tenant_plans_validation", "admin@plans-validation.test");

		mockMvc.perform(get("/api/plans/{planId}", 999999L).header("Authorization", bearer(adminToken)))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("BILLING_PLAN_NOT_FOUND"));
		mockMvc.perform(patch("/api/plans/{planId}/enabled", 999999L)
				.header("Authorization", bearer(adminToken))
				.contentType("application/json")
				.content("""
						{"enabled":false}
						"""))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("BILLING_PLAN_NOT_FOUND"));

		assertBillingPlanValidationFails(adminToken, planJson("", "Basic Plan", null, "499.00", "INR", "MONTHLY"),
				"VALIDATION_FAILED");
		assertBillingPlanValidationFails(adminToken, planJson("BASIC", "", null, "499.00", "INR", "MONTHLY"),
				"VALIDATION_FAILED");
		assertBillingPlanValidationFails(adminToken, planJson("BASIC", "Basic Plan", null, "-1.00", "INR", "MONTHLY"),
				"VALIDATION_FAILED");
		assertBillingPlanValidationFails(adminToken, """
				{"code":"BASIC","name":"Basic Plan","currency":"INR","billingInterval":"MONTHLY"}
				""", "VALIDATION_FAILED");
		assertBillingPlanValidationFails(adminToken, planJson("BASIC", "Basic Plan", null, "499.00", "IN", "MONTHLY"),
				"VALIDATION_FAILED");
		assertBillingPlanValidationFails(adminToken, """
				{"code":"BASIC","name":"Basic Plan","amount":499.00,"currency":"INR"}
				""", "VALIDATION_FAILED");
		assertBillingPlanValidationFails(adminToken, planJson("BASIC", "Basic Plan", null, "499.00", "INR", "WEEKLY"),
				"BAD_REQUEST");

		long planId = createBillingPlanAndReturnId(adminToken, "VALID", "Valid Plan", "499.00", "INR", "MONTHLY");
		mockMvc.perform(patch("/api/plans/{planId}/enabled", planId)
				.header("Authorization", bearer(adminToken))
				.contentType("application/json")
				.content("{}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
	}

	@Test
	void tenantAdminCanDisableAndReEnableBillingPlan() throws Exception {
		String adminToken = registerBootstrapAndLogin("tenant_plans_toggle", "Tenant Plans Toggle", "tenant_plans_toggle",
				"admin@plans-toggle.test");
		long planId = createBillingPlanAndReturnId(adminToken, "BASIC", "Basic Plan", "499.00", "INR", "MONTHLY");

		mockMvc.perform(patch("/api/plans/{planId}/enabled", planId)
				.header("Authorization", bearer(adminToken))
				.contentType("application/json")
				.content("""
						{"enabled":false}
						"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.active").value(false));
		mockMvc.perform(patch("/api/plans/{planId}/enabled", planId)
				.header("Authorization", bearer(adminToken))
				.contentType("application/json")
				.content("""
						{"enabled":true}
						"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.active").value(true));
	}

	@Test
	void billingPlansRemainTenantIsolatedAndRejectCrossTenantHeader() throws Exception {
		String tenantAToken = registerBootstrapAndLogin("tenant_plans_iso_a", "Tenant Plans Iso A", "tenant_plans_iso_a",
				"admin@plans-iso-a.test");
		String tenantBToken = registerBootstrapAndLogin("tenant_plans_iso_b", "Tenant Plans Iso B", "tenant_plans_iso_b",
				"admin@plans-iso-b.test");
		createBillingPlan(tenantAToken, "ALPHA", "Alpha Plan", "499.00", "INR", "MONTHLY")
				.andExpect(status().isCreated());
		createBillingPlan(tenantBToken, "BRAVO", "Bravo Plan", "599.00", "USD", "YEARLY")
				.andExpect(status().isCreated());

		mockMvc.perform(get("/api/plans").header("Authorization", bearer(tenantAToken)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[?(@.code == 'ALPHA')]").exists())
				.andExpect(jsonPath("$[?(@.code == 'BRAVO')]").doesNotExist());
		mockMvc.perform(get("/api/plans").header("Authorization", bearer(tenantBToken)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[?(@.code == 'BRAVO')]").exists())
				.andExpect(jsonPath("$[?(@.code == 'ALPHA')]").doesNotExist());
		mockMvc.perform(get("/api/plans")
				.header("Authorization", bearer(tenantAToken))
				.header("X-Tenant-ID", "tenant_plans_iso_b"))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("TENANT_ACCESS_DENIED"));
	}
	@Test
	void tenantAdminCreatesListsGetsAndCancelsSubscription() throws Exception {
		String adminToken = registerBootstrapAndLogin("tenant_subscriptions_basic", "Tenant Subscriptions Basic",
				"tenant_subscriptions_basic", "admin@subscriptions-basic.test");
		long customerId = createCustomerAndReturnId(adminToken, "Alice Subscription");
		long planId = createBillingPlanAndReturnId(adminToken, "BASIC", "Basic Plan", "499.00", "INR", "MONTHLY");

		long subscriptionId = createSubscription(adminToken, customerId, planId)
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.customerId").value((int) customerId))
				.andExpect(jsonPath("$.planId").value((int) planId))
				.andExpect(jsonPath("$.planCode").value("BASIC"))
				.andExpect(jsonPath("$.status").value("ACTIVE"))
				.andExpect(jsonPath("$.startedAt").exists())
				.andReturn()
				.getResponse()
				.getContentAsString()
				.replaceAll(".*\\\"id\\\":([0-9]+).*", "$1")
				.lines()
				.mapToLong(Long::parseLong)
				.findFirst()
				.orElseThrow();

		mockMvc.perform(get("/api/subscriptions").header("Authorization", bearer(adminToken)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[?(@.id == %s)]".formatted(subscriptionId)).exists());
		mockMvc.perform(get("/api/subscriptions/{subscriptionId}", subscriptionId).header("Authorization", bearer(adminToken)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("ACTIVE"));
		mockMvc.perform(patch("/api/subscriptions/{subscriptionId}/cancel", subscriptionId)
				.header("Authorization", bearer(adminToken))
				.contentType("application/json")
				.content("{}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("CANCELLED"))
				.andExpect(jsonPath("$.cancelledAt").exists());
		mockMvc.perform(patch("/api/subscriptions/{subscriptionId}/cancel", subscriptionId)
				.header("Authorization", bearer(adminToken))
				.contentType("application/json")
				.content("{}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("CANCELLED"));
	}

	@Test
	void subscriptionCreateRejectsDuplicateInactivePlanAndMissingReferences() throws Exception {
		String adminToken = registerBootstrapAndLogin("tenant_subscriptions_reject", "Tenant Subscriptions Reject",
				"tenant_subscriptions_reject", "admin@subscriptions-reject.test");
		long customerId = createCustomerAndReturnId(adminToken, "Alice Reject");
		long activePlanId = createBillingPlanAndReturnId(adminToken, "ACTIVE", "Active Plan", "499.00", "INR", "MONTHLY");
		long inactivePlanId = createBillingPlanAndReturnId(adminToken, "INACTIVE", "Inactive Plan", "599.00", "INR", "MONTHLY");
		mockMvc.perform(patch("/api/plans/{planId}/enabled", inactivePlanId)
				.header("Authorization", bearer(adminToken))
				.contentType("application/json")
				.content("""
						{"enabled":false}
						"""))
				.andExpect(status().isOk());

		createSubscription(adminToken, customerId, activePlanId).andExpect(status().isCreated());
		createSubscription(adminToken, customerId, activePlanId)
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("TENANT_CONFLICT"));
		createSubscription(adminToken, customerId, inactivePlanId)
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("TENANT_CONFLICT"));
		createSubscription(adminToken, 999999L, activePlanId)
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("CUSTOMER_NOT_FOUND"));
		createSubscription(adminToken, customerId, 999999L)
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("BILLING_PLAN_NOT_FOUND"));
	}

	@Test
	void tenantUserCanReadButCannotCreateOrCancelSubscriptions() throws Exception {
		String adminToken = registerBootstrapAndLogin("tenant_subscriptions_user", "Tenant Subscriptions User",
				"tenant_subscriptions_user", "admin@subscriptions-user.test");
		createTenantUser(adminToken, "user@subscriptions-user.test", "TENANT_USER").andExpect(status().isCreated());
		String userToken = login("tenant_subscriptions_user", "user@subscriptions-user.test", PASSWORD);
		long customerId = createCustomerAndReturnId(adminToken, "Alice User");
		long planId = createBillingPlanAndReturnId(adminToken, "BASIC", "Basic Plan", "499.00", "INR", "MONTHLY");
		long subscriptionId = createSubscriptionAndReturnId(adminToken, customerId, planId);

		mockMvc.perform(get("/api/subscriptions").header("Authorization", bearer(userToken)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[?(@.id == %s)]".formatted(subscriptionId)).exists());
		mockMvc.perform(get("/api/subscriptions/{subscriptionId}", subscriptionId).header("Authorization", bearer(userToken)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value((int) subscriptionId));
		createSubscription(userToken, customerId, planId)
				.andExpect(status().isForbidden());
		mockMvc.perform(patch("/api/subscriptions/{subscriptionId}/cancel", subscriptionId)
				.header("Authorization", bearer(userToken))
				.contentType("application/json")
				.content("{}"))
				.andExpect(status().isForbidden());
	}

	@Test
	void subscriptionsRejectCrossTenantAccessAndRemainTenantIsolated() throws Exception {
		String tenantAToken = registerBootstrapAndLogin("tenant_subscriptions_iso_a", "Tenant Subscriptions Iso A",
				"tenant_subscriptions_iso_a", "admin@subscriptions-iso-a.test");
		String tenantBToken = registerBootstrapAndLogin("tenant_subscriptions_iso_b", "Tenant Subscriptions Iso B",
				"tenant_subscriptions_iso_b", "admin@subscriptions-iso-b.test");
		long customerAId = createCustomerAndReturnId(tenantAToken, "Alice A");
		long planAId = createBillingPlanAndReturnId(tenantAToken, "ALPHA", "Alpha Plan", "499.00", "INR", "MONTHLY");
		long subscriptionAId = createSubscriptionAndReturnId(tenantAToken, customerAId, planAId);
		long customerBId = createCustomerAndReturnId(tenantBToken, "Bob B");
		long planBId = createBillingPlanAndReturnId(tenantBToken, "BRAVO", "Bravo Plan", "599.00", "USD", "YEARLY");
		long subscriptionBId = createSubscriptionAndReturnId(tenantBToken, customerBId, planBId);

		mockMvc.perform(get("/api/subscriptions").header("Authorization", bearer(tenantAToken)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[?(@.planCode == 'ALPHA')]").exists())
				.andExpect(jsonPath("$[?(@.planCode == 'BRAVO')]").doesNotExist());
		mockMvc.perform(get("/api/subscriptions")
				.header("Authorization", bearer(tenantAToken))
				.header("X-Tenant-ID", "tenant_subscriptions_iso_b"))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("TENANT_ACCESS_DENIED"));
	}

	@Test
	void subscriptionEndpointsRejectUnauthenticatedAndPlatformAdminOnlyRequests() throws Exception {
		mockMvc.perform(post("/api/subscriptions")
				.contentType("application/json")
				.content("""
						{"customerId":1,"planId":1}
						"""))
				.andExpect(status().isUnauthorized());
		mockMvc.perform(get("/api/subscriptions"))
				.andExpect(status().isUnauthorized());
		mockMvc.perform(get("/api/subscriptions/{subscriptionId}", 1L))
				.andExpect(status().isUnauthorized());
		mockMvc.perform(get("/api/subscriptions").header("X-Platform-Admin-Token", PLATFORM_ADMIN_TOKEN))
				.andExpect(status().isUnauthorized());
	}
	@Test
	void pricingCalculationReturnsPlanAmountForTenantAdminAndUser() throws Exception {
		String adminToken = registerBootstrapAndLogin("tenant_pricing_basic", "Tenant Pricing Basic",
				"tenant_pricing_basic", "admin@pricing-basic.test");
		createTenantUser(adminToken, "user@pricing-basic.test", "TENANT_USER").andExpect(status().isCreated());
		String userToken = login("tenant_pricing_basic", "user@pricing-basic.test", PASSWORD);
		long customerId = createCustomerAndReturnId(adminToken, "Alice Pricing");
		long planId = createBillingPlanAndReturnId(adminToken, "GROWTH", "Growth Plan", "1234.56", "USD", "YEARLY");
		long subscriptionId = createSubscriptionAndReturnId(adminToken, customerId, planId);

		mockMvc.perform(get("/api/pricing/{subscriptionId}", subscriptionId).header("Authorization", bearer(adminToken)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.subscriptionId").value((int) subscriptionId))
				.andExpect(jsonPath("$.customerId").value((int) customerId))
				.andExpect(jsonPath("$.planId").value((int) planId))
				.andExpect(jsonPath("$.planName").value("Growth Plan"))
				.andExpect(jsonPath("$.currency").value("USD"))
				.andExpect(jsonPath("$.billingInterval").value("YEARLY"))
				.andExpect(jsonPath("$.subtotal").value(1234.56))
				.andExpect(jsonPath("$.discount").value(0))
				.andExpect(jsonPath("$.tax").value(0))
				.andExpect(jsonPath("$.finalAmount").value(1234.56))
				.andExpect(jsonPath("$.subscriptionStatus").value("ACTIVE"));
		mockMvc.perform(get("/api/pricing/{subscriptionId}", subscriptionId).header("Authorization", bearer(userToken)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.finalAmount").value(1234.56));
	}

	@Test
	void pricingRejectsMissingUnauthenticatedPlatformAndCrossTenantRequests() throws Exception {
		String tenantAToken = registerBootstrapAndLogin("tenant_pricing_iso_a", "Tenant Pricing Iso A",
				"tenant_pricing_iso_a", "admin@pricing-iso-a.test");
		String tenantBToken = registerBootstrapAndLogin("tenant_pricing_iso_b", "Tenant Pricing Iso B",
				"tenant_pricing_iso_b", "admin@pricing-iso-b.test");
		long customerAId = createCustomerAndReturnId(tenantAToken, "Alice Pricing A");
		long planAId = createBillingPlanAndReturnId(tenantAToken, "ALPHA", "Alpha Pricing", "499.00", "INR", "MONTHLY");
		long subscriptionAId = createSubscriptionAndReturnId(tenantAToken, customerAId, planAId);
		long customerBId = createCustomerAndReturnId(tenantBToken, "Bob Pricing B");
		long planBId = createBillingPlanAndReturnId(tenantBToken, "BRAVO", "Bravo Pricing", "599.00", "USD", "YEARLY");
		createSubscriptionAndReturnId(tenantBToken, customerBId, planBId);

		mockMvc.perform(get("/api/pricing/{subscriptionId}", 999999L).header("Authorization", bearer(tenantAToken)))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("SUBSCRIPTION_NOT_FOUND"));
		mockMvc.perform(get("/api/pricing/{subscriptionId}", subscriptionAId))
				.andExpect(status().isUnauthorized());
		mockMvc.perform(get("/api/pricing/{subscriptionId}", subscriptionAId)
				.header("X-Platform-Admin-Token", PLATFORM_ADMIN_TOKEN))
				.andExpect(status().isUnauthorized());
		mockMvc.perform(get("/api/pricing/{subscriptionId}", subscriptionAId)
				.header("Authorization", bearer(tenantAToken))
				.header("X-Tenant-ID", "tenant_pricing_iso_b"))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("TENANT_ACCESS_DENIED"));
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


	private long createCustomerAndReturnId(String token, String name) throws Exception {
		String response = mockMvc.perform(post("/customers")
				.header("Authorization", bearer(token))
				.contentType("application/json")
				.content("""
						{"name":"%s"}
						""".formatted(name)))
				.andExpect(status().isCreated())
				.andReturn()
				.getResponse()
				.getContentAsString();
		return Long.parseLong(response.replaceAll(".*\\\"id\\\":([0-9]+).*", "$1"));
	}

	private org.springframework.test.web.servlet.ResultActions createSubscription(String token, long customerId, long planId)
			throws Exception {
		return mockMvc.perform(post("/api/subscriptions")
				.header("Authorization", bearer(token))
				.contentType("application/json")
				.content("""
						{"customerId":%s,"planId":%s}
						""".formatted(customerId, planId)));
	}

	private long createSubscriptionAndReturnId(String adminToken, long customerId, long planId) throws Exception {
		String response = createSubscription(adminToken, customerId, planId)
				.andExpect(status().isCreated())
				.andReturn()
				.getResponse()
				.getContentAsString();
		return Long.parseLong(response.replaceAll(".*\\\"id\\\":([0-9]+).*", "$1"));
	}
	private org.springframework.test.web.servlet.ResultActions createBillingPlan(String token, String code, String name,
			String amount, String currency, String billingInterval) throws Exception {
		return createBillingPlan(token, code, name, null, amount, currency, billingInterval);
	}


	private org.springframework.test.web.servlet.ResultActions createBillingPlan(String token, String code, String name,
			String description, String amount, String currency, String billingInterval) throws Exception {
		return mockMvc.perform(post("/api/plans")
				.header("Authorization", bearer(token))
				.contentType("application/json")
				.content(planJson(code, name, description, amount, currency, billingInterval)));
	}

	private long createBillingPlanAndReturnId(String adminToken, String code, String name, String amount, String currency,
			String billingInterval) throws Exception {
		String response = createBillingPlan(adminToken, code, name, amount, currency, billingInterval)
				.andExpect(status().isCreated())
				.andReturn()
				.getResponse()
				.getContentAsString();
		return Long.parseLong(response.replaceAll(".*\\\"id\\\":([0-9]+).*", "$1"));
	}

	private void assertBillingPlanValidationFails(String adminToken, String body, String code) throws Exception {
		mockMvc.perform(post("/api/plans")
				.header("Authorization", bearer(adminToken))
				.contentType("application/json")
				.content(body))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value(code));
	}

	private String planJson(String code, String name, String description, String amount, String currency,
			String billingInterval) {
		String descriptionJson = description == null ? "" : "\"description\":\"" + description + "\",";
		return """
				{"code":"%s","name":"%s",%s"amount":%s,"currency":"%s","billingInterval":"%s"}
				""".formatted(code, name, descriptionJson, amount, currency, billingInterval);
	}
	private org.springframework.test.web.servlet.ResultActions createTenantUser(String adminToken, String email, String role)
			throws Exception {
		return mockMvc.perform(post("/api/tenant/users")
				.header("Authorization", bearer(adminToken))
				.contentType("application/json")
				.content("""
						{"email":"%s","password":"%s","role":"%s"}
						""".formatted(email, PASSWORD, role)));
	}
	private long createTenantUserAndReturnId(String adminToken, String email, String role) throws Exception {
		String response = createTenantUser(adminToken, email, role)
				.andExpect(status().isCreated())
				.andReturn()
				.getResponse()
				.getContentAsString();
		return Long.parseLong(response.replaceAll(".*\\\"id\\\":([0-9]+).*", "$1"));
	}

	private void assertCreateTenantUserValidationFails(String adminToken, String body) throws Exception {
		mockMvc.perform(post("/api/tenant/users")
				.header("Authorization", bearer(adminToken))
				.contentType("application/json")
				.content(body))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
	}

	private void assertUpdateEnabledValidationFails(String adminToken, long userId, String body) throws Exception {
		mockMvc.perform(patch("/api/tenant/users/{userId}/enabled", userId)
				.header("Authorization", bearer(adminToken))
				.contentType("application/json")
				.content(body))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
	}
	private String bearer(String token) {
		return "Bearer " + token;
	}


	private String tokenWithRole(String tenantId, String email, String role) {
		Instant now = Instant.now();
		JwtClaimsSet claims = JwtClaimsSet.builder()
				.issuer("multitenant-test")
				.issuedAt(now)
				.expiresAt(now.plusSeconds(3600))
				.subject(email)
				.claim("tenant_id", tenantId)
				.claim("role", role)
				.build();
		return jwtEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
				.getTokenValue();
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

	private void createTenantSchemaMigratedThroughV3(String schemaName) {
		Flyway.configure()
				.dataSource(dataSource)
				.schemas(schemaName)
				.defaultSchema(schemaName)
				.createSchemas(true)
				.locations("classpath:db/tenant")
				.placeholders(java.util.Map.of("schemaName", schemaName))
				.target("3")
				.load()
				.migrate();
	}

	private boolean tableExists(String schemaName, String tableName) throws Exception {
		try (Connection connection = dataSource.getConnection();
				var tables = connection.getMetaData().getTables(null, schemaName, tableName, new String[] { "TABLE" })) {
			return tables.next();
		}
	}

	private long successfulTenantMigrationCount(String schemaName, String version) throws Exception {
		try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
			connection.setSchema(schemaName);
			try (var resultSet = statement.executeQuery(
					"select count(*) from flyway_schema_history where version = '" + version + "' and success = true")) {
				resultSet.next();
				return resultSet.getLong(1);
			}
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
