package com.mutlitenant;

import java.sql.Connection;
import java.sql.Statement;

import javax.sql.DataSource;

import com.mutlitenant.tenant.ProvisioningStatus;
import com.mutlitenant.tenant.SchemaPerTenantConnectionProvider;
import com.mutlitenant.tenant.Tenant;
import com.mutlitenant.tenant.TenantContext;
import com.mutlitenant.tenant.TenantIdentifier;
import com.mutlitenant.tenant.TenantRepository;
import com.mutlitenant.tenant.TenantStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MutlitenantApplicationTests {

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

	@BeforeEach
	void clearTenantContext() {
		TenantContext.clear();
	}

	@Test
	void contextLoads() {
	}

	@Test
	void usesDefaultTenantWhenHeaderIsMissing() throws Exception {
		mockMvc.perform(get("/tenant")).andExpect(status().isOk()).andExpect(jsonPath("$.tenant").value("public"));
		assertThat(TenantContext.getTenant()).isNull();
	}

	@Test
	void resolvesTenantFromHeader() throws Exception {
		registerTenant("tenant_one", "Tenant One", "tenant_one");

		mockMvc.perform(get("/tenant").header("X-Tenant-ID", "Tenant_One"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.tenant").value("tenant_one"));
		assertThat(TenantContext.getTenant()).isNull();
	}

	@Test
	void jpaUsesTenantSchemaFromHeader() throws Exception {
		registerTenant("tenant_jpa", "Tenant Jpa", "tenant_jpa");

		mockMvc.perform(get("/tenant/jpa").header("X-Tenant-ID", "Tenant_Jpa"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.tenant").value("tenant_jpa"))
				.andExpect(jsonPath("$.schema").value("tenant_jpa"));
	}

	@Test
	void rejectsUnsafeTenantHeader() throws Exception {
		mockMvc.perform(get("/tenant").header("X-Tenant-ID", "tenant-one")).andExpect(status().isBadRequest());
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

		mockMvc.perform(get("/tenant/jpa").header("X-Tenant-ID", "tenant_api"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.schema").value("tenant_api"));
	}

	@Test
	void listsAndReadsTenantsWithoutTenantHeaderInterference() throws Exception {
		registerTenant("tenant_read", "Tenant Read", "tenant_read");

		mockMvc.perform(get("/api/tenants/tenant_read").header("X-Tenant-ID", "missing_tenant"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.tenantId").value("tenant_read"));
		mockMvc.perform(get("/api/tenants"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[?(@.tenantId == 'tenant_read')]").exists());
	}

	@Test
	void preventsDuplicateTenantRegistration() throws Exception {
		registerTenant("tenant_dup", "Tenant Duplicate", "tenant_dup");

		mockMvc.perform(post("/api/tenants")
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
				.contentType("application/json")
				.content("""
						{"tenantId":"tenant-bad","tenantName":"Bad Tenant","schemaName":"tenant_bad"}
						"""))
				.andExpect(status().isBadRequest());

		mockMvc.perform(post("/api/tenants")
				.contentType("application/json")
				.content("""
						{"tenantId":"public","tenantName":"Platform Tenant","schemaName":"public"}
						"""))
				.andExpect(status().isBadRequest());
	}

	@Test
	void rejectsUnknownTenantAccess() throws Exception {
		mockMvc.perform(get("/tenant/jpa").header("X-Tenant-ID", "unknown_tenant"))
				.andExpect(status().isNotFound());
		assertThat(TenantContext.getTenant()).isNull();
	}

	@Test
	void rejectsInactiveTenantAccess() throws Exception {
		saveRegistryRecord("inactive_tenant", "Inactive Tenant", "inactive_tenant", TenantStatus.PENDING,
				ProvisioningStatus.PENDING);

		mockMvc.perform(get("/tenant/jpa").header("X-Tenant-ID", "inactive_tenant"))
				.andExpect(status().isForbidden());
		assertThat(TenantContext.getTenant()).isNull();
	}

	@Test
	void provisioningFailureMarksTenantFailed() throws Exception {
		createBrokenTenantSchema("tenant_fail");

		mockMvc.perform(post("/api/tenants")
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
				.contentType("application/json")
				.content("""
						{"tenantId":"tenant_retry","tenantName":"Tenant Retry","schemaName":"tenant_retry"}
						"""))
				.andExpect(status().isInternalServerError());

		dropBrokenTenantTable("tenant_retry");
		mockMvc.perform(post("/api/tenants/tenant_retry/provision"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.tenantId").value("tenant_retry"))
				.andExpect(jsonPath("$.status").value("ACTIVE"));

		mockMvc.perform(post("/api/tenants/tenant_retry/provision"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.tenantId").value("tenant_retry"))
				.andExpect(jsonPath("$.status").value("ACTIVE"));
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
