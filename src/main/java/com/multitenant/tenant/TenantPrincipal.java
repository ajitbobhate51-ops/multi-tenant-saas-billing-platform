package com.multitenant.tenant;

public record TenantPrincipal(String email, String tenantId, String role) {
}
