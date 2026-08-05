package com.multitenant.tenant;

import java.util.Optional;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TenantRepository extends JpaRepository<Tenant, String> {

	boolean existsByTenantNameIgnoreCase(String tenantName);

	boolean existsBySchemaName(String schemaName);

	Optional<Tenant> findBySchemaName(String schemaName);

	List<Tenant> findByStatusAndProvisioningStatus(TenantStatus status, ProvisioningStatus provisioningStatus);
}
