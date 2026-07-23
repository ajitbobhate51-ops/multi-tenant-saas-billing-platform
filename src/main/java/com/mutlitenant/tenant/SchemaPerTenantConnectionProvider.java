package com.mutlitenant.tenant;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import javax.sql.DataSource;

import org.hibernate.engine.jdbc.connections.spi.MultiTenantConnectionProvider;
import org.springframework.stereotype.Component;

@Component
public class SchemaPerTenantConnectionProvider implements MultiTenantConnectionProvider<String> {

	private final DataSource dataSource;

	private final TenantProperties properties;

	public SchemaPerTenantConnectionProvider(DataSource dataSource, TenantProperties properties) {
		this.dataSource = dataSource;
		this.properties = properties;
	}

	@Override
	public Connection getAnyConnection() throws SQLException {
		Connection connection = dataSource.getConnection();
		String schema = defaultSchema();
		if (properties.isCreateSchema()) {
			createSchema(connection, schema);
		}
		connection.setSchema(schema);
		return connection;
	}

	@Override
	public void releaseAnyConnection(Connection connection) throws SQLException {
		resetAndClose(connection);
	}

	@Override
	public Connection getConnection(String tenantIdentifier) throws SQLException {
		String schema = resolveSchema(tenantIdentifier);
		Connection connection = getAnyConnection();
		if (properties.isCreateSchema() && !schema.equals(defaultSchema())) {
			createSchema(connection, schema);
		}
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
		return unwrapType.isAssignableFrom(getClass()) || unwrapType.isAssignableFrom(dataSource.getClass());
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

	private String resolveSchema(String tenantIdentifier) {
		String tenant = TenantIdentifier.normalize(tenantIdentifier);
		return tenant == null ? defaultSchema() : tenant;
	}

	private String defaultSchema() {
		return TenantIdentifier.normalize(properties.getDefaultTenant());
	}

	private void createSchema(Connection connection, String schema) throws SQLException {
		try (Statement statement = connection.createStatement()) {
			statement.execute("create schema if not exists " + schema);
		}
	}

	private void resetAndClose(Connection connection) throws SQLException {
		try {
			String schema = defaultSchema();
			if (properties.isCreateSchema()) {
				createSchema(connection, schema);
			}
			connection.setSchema(schema);
		}
		finally {
			connection.close();
		}
	}
}
