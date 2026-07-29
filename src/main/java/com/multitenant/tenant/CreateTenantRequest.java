package com.multitenant.tenant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateTenantRequest(
		@Size(max = 63) String tenantId,
		@NotBlank @Size(min = 2, max = 255) String tenantName,
		@Size(max = 63) String schemaName) {
}
