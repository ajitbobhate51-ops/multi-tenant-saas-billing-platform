package com.mutlitenant.tenant;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

import javax.sql.DataSource;

import org.springframework.stereotype.Component;

@Component
public class TenantRegistryLookup {

	private final DataSource dataSource;

	private final TenantProperties properties;

	public TenantRegistryLookup(DataSource dataSource, TenantProperties properties) {
		this.dataSource = dataSource;
		this.properties = properties;
	}

	public Optional<TenantRegistryRecord> findByTenantId(String tenantId) {
		String normalizedTenantId = TenantIdentifier.normalize(tenantId);
		if (normalizedTenantId == null || normalizedTenantId.equals(defaultTenant())) {
			return Optional.empty();
		}

		try (Connection connection = dataSource.getConnection()) {
			connection.setSchema(defaultTenant());
			try (PreparedStatement statement = connection.prepareStatement(
					"select tenant_id, schema_name, status, provisioning_status from tenants where tenant_id = ?")) {
				statement.setString(1, normalizedTenantId);
				try (ResultSet resultSet = statement.executeQuery()) {
					if (!resultSet.next()) {
						return Optional.empty();
					}
					return Optional.of(new TenantRegistryRecord(resultSet.getString("tenant_id"),
							resultSet.getString("schema_name"), TenantStatus.valueOf(resultSet.getString("status")),
							ProvisioningStatus.valueOf(resultSet.getString("provisioning_status"))));
				}
			}
		}
		catch (SQLException ex) {
			throw new TenantAccessException("Unable to read tenant registry");
		}
	}

	public String requireActiveSchema(String tenantId) {
		String normalizedTenantId = TenantIdentifier.normalize(tenantId);
		if (normalizedTenantId == null || normalizedTenantId.equals(defaultTenant())) {
			return defaultTenant();
		}

		TenantRegistryRecord tenant = findByTenantId(normalizedTenantId)
			.orElseThrow(() -> new TenantNotFoundException("Tenant does not exist: " + normalizedTenantId));
		if (tenant.status() != TenantStatus.ACTIVE || tenant.provisioningStatus() != ProvisioningStatus.ACTIVE) {
			throw new TenantAccessException("Tenant is not active: " + normalizedTenantId);
		}
		return tenant.schemaName();
	}

	private String defaultTenant() {
		return TenantIdentifier.normalize(properties.getDefaultTenant());
	}

	public record TenantRegistryRecord(String tenantId, String schemaName, TenantStatus status,
			ProvisioningStatus provisioningStatus) {
	}
}
