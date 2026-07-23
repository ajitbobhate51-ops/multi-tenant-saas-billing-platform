package com.mutlitenant.tenant;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

public final class TenantIdentifier {

	private static final Pattern VALID_SCHEMA = Pattern.compile("[a-z][a-z0-9_]{0,62}");

	private static final Set<String> RESERVED_SCHEMA_NAMES = Set.of("information_schema");

	private TenantIdentifier() {
	}

	public static String normalize(String tenant) {
		if (tenant == null || tenant.isBlank()) {
			return null;
		}

		String normalized = tenant.trim().toLowerCase(Locale.ROOT);
		if (!VALID_SCHEMA.matcher(normalized).matches()) {
			throw new IllegalArgumentException("Tenant id must be a valid PostgreSQL schema name");
		}
		if (normalized.startsWith("pg_") || RESERVED_SCHEMA_NAMES.contains(normalized)) {
			throw new IllegalArgumentException("Tenant id cannot use a reserved database schema name");
		}

		return normalized;
	}
}
