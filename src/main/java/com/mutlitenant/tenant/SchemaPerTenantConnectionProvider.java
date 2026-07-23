package com.mutlitenant.tenant;

import java.sql.Connection;
import java.sql.SQLException;

import javax.sql.DataSource;

import org.hibernate.engine.jdbc.connections.spi.MultiTenantConnectionProvider;
import org.springframework.stereotype.Component;

@Component
public class SchemaPerTenantConnectionProvider implements MultiTenantConnectionProvider<String> {

	private final DataSource dataSource;
	private final TenantProperties properties;
	private final TenantRegistryLookup tenantRegistryLookup;

	public SchemaPerTenantConnectionProvider(
			DataSource dataSource,
			TenantProperties properties,
			TenantRegistryLookup tenantRegistryLookup) {

		this.dataSource = dataSource;
		this.properties = properties;
		this.tenantRegistryLookup = tenantRegistryLookup;
	}

	@Override
	public Connection getAnyConnection() throws SQLException {
		Connection connection = dataSource.getConnection();
		connection.setSchema(defaultSchema());
		return connection;
	}

	@Override
	public void releaseAnyConnection(Connection connection) throws SQLException {
		resetAndClose(connection);
	}

	@Override
	public Connection getConnection(String tenantIdentifier) throws SQLException {
		String schema = resolveActiveSchema(tenantIdentifier);
		Connection connection = getAnyConnection();
		connection.setSchema(schema);
		return connection;
	}

	@Override
	public void releaseConnection(String tenantIdentifier, Connection connection) throws SQLException {
		resetAndClose(connection);
	}

	@Override
	public boolean supportsAggressiveRelease() {
		return false;
	}

	@Override
	public boolean handlesConnectionSchema() {
		return true;
	}

	@Override
	public boolean isUnwrappableAs(Class<?> unwrapType) {
		return unwrapType.isAssignableFrom(getClass())
				|| unwrapType.isAssignableFrom(dataSource.getClass());
	}

	@Override
	public <T> T unwrap(Class<T> unwrapType) {
		if (unwrapType.isAssignableFrom(getClass())) {
			return unwrapType.cast(this);
		}

		if (unwrapType.isAssignableFrom(dataSource.getClass())) {
			return unwrapType.cast(dataSource);
		}

		return null;
	}

	private String resolveActiveSchema(String tenantIdentifier) throws SQLException {
		String tenant = TenantIdentifier.normalize(tenantIdentifier);

		try {
			return tenant == null
					? defaultSchema()
					: tenantRegistryLookup.requireActiveSchema(tenant);
		}
		catch (TenantNotFoundException | TenantAccessException ex) {
			throw new SQLException(ex.getMessage(), ex);
		}
	}

	private String defaultSchema() {
		return TenantIdentifier.normalize(properties.getDefaultTenant());
	}

	private void resetAndClose(Connection connection) throws SQLException {
		try {
			connection.setSchema(defaultSchema());
		}
		finally {
			connection.close();
		}
	}
}