package com.multitenant.tenant;

import java.time.Instant;

public record LoginResponse(String tokenType, String accessToken, Instant expiresAt, String tenantId, String email,
		String role) {
}
