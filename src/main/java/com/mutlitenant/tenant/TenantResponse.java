package com.mutlitenant.tenant;

import java.time.OffsetDateTime;

public record TenantResponse(String tenantId, String tenantName, String schemaName, TenantStatus status,
		ProvisioningStatus provisioningStatus, String failureMessage, OffsetDateTime createdAt,
		OffsetDateTime updatedAt) {

	public static TenantResponse from(Tenant tenant) {
		return new TenantResponse(tenant.getTenantId(), tenant.getTenantName(), tenant.getSchemaName(),
				tenant.getStatus(), tenant.getProvisioningStatus(), tenant.getFailureMessage(), tenant.getCreatedAt(),
				tenant.getUpdatedAt());
	}
}
