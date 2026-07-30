package com.multitenant.tenant;

import java.util.Arrays;

public enum TenantRole {

	TENANT_ADMIN,
	TENANT_USER;

	public static TenantRole from(String value) {
		return Arrays.stream(values())
			.filter(role -> role.name().equals(value))
			.findFirst()
			.orElseThrow(() -> new IllegalArgumentException("Unsupported tenant role"));
	}
}
