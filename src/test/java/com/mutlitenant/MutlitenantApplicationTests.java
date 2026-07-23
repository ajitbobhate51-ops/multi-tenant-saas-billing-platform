package com.mutlitenant;

import java.sql.Connection;

import javax.sql.DataSource;

import com.mutlitenant.tenant.SchemaPerTenantConnectionProvider;
import com.mutlitenant.tenant.TenantContext;
import com.mutlitenant.tenant.TenantIdentifier;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class MutlitenantApplicationTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private EntityManagerFactory entityManagerFactory;

	@Autowired
	private SchemaPerTenantConnectionProvider connectionProvider;

	@Autowired
	private DataSource dataSource;

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
		mockMvc.perform(get("/tenant").header("X-Tenant-ID", "Tenant_One"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.tenant").value("tenant_one"));
		assertThat(TenantContext.getTenant()).isNull();
	}

	@Test
	void jpaUsesTenantSchemaFromHeader() throws Exception {
		mockMvc.perform(get("/tenant/jpa").header("X-Tenant-ID", "Tenant_One"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.tenant").value("tenant_one"))
				.andExpect(jsonPath("$.schema").value("tenant_one"));
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
		Connection tenantConnection = connectionProvider.getConnection("tenant_a");
		assertThat(tenantConnection.getSchema()).isEqualTo("tenant_a");

		connectionProvider.releaseConnection("tenant_a", tenantConnection);

		try (Connection connection = dataSource.getConnection()) {
			assertThat(connection.getSchema()).isEqualTo("public");
		}
	}

	private void insertUsageRecord(String tenant, Long id, String description) {
		executeInTenant(tenant, entityManager -> {
			entityManager.createNativeQuery("create table if not exists usage_record "
					+ "(id bigint primary key, description varchar(255))").executeUpdate();
			entityManager.createNativeQuery("merge into usage_record (id, description) key(id) values (:id, :description)")
					.setParameter("id", id)
					.setParameter("description", description)
					.executeUpdate();
			return null;
		});
	}

	private String findUsageDescription(String tenant, Long id) {
		return executeInTenant(tenant, entityManager -> (String) entityManager
				.createNativeQuery("select description from usage_record where id = :id", String.class)
				.setParameter("id", id)
				.getSingleResult());
	}

	private long countUsageRecordsWithDescription(String tenant, String description) {
		Number count = executeInTenant(tenant, entityManager -> (Number) entityManager
				.createNativeQuery("select count(*) from usage_record where description = :description", Long.class)
				.setParameter("description", description)
				.getSingleResult());
		return count.longValue();
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
