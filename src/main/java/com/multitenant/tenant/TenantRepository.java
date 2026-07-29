package com.multitenant.tenant;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TenantRepository extends JpaRepository<Tenant, String> {

	boolean existsByTenantNameIgnoreCase(String tenantName);

	boolean existsBySchemaName(String schemaName);

	Optional<Tenant> findBySchemaName(String schemaName);
}
