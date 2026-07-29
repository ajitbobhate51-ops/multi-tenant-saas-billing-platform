package com.multitenant.tenant;

import java.time.OffsetDateTime;

public record TenantUserResponse(Long id, String email, boolean enabled, String role, OffsetDateTime createdAt,
		OffsetDateTime updatedAt) {

	public static TenantUserResponse from(TenantUser user) {
		return new TenantUserResponse(user.getId(), user.getEmail(), user.isEnabled(), user.getRole(), user.getCreatedAt(),
				user.getUpdatedAt());
	}
}
