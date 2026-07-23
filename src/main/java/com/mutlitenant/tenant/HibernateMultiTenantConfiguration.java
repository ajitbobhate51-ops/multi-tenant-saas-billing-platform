package com.mutlitenant.tenant;

import java.util.Map;

import org.hibernate.cfg.MultiTenancySettings;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(TenantProperties.class)
public class HibernateMultiTenantConfiguration implements HibernatePropertiesCustomizer {

	private final SchemaPerTenantConnectionProvider connectionProvider;

	private final HibernateTenantIdentifierResolver tenantIdentifierResolver;

	private final TenantProperties properties;

	public HibernateMultiTenantConfiguration(SchemaPerTenantConnectionProvider connectionProvider,
			HibernateTenantIdentifierResolver tenantIdentifierResolver, TenantProperties properties) {
		this.connectionProvider = connectionProvider;
		this.tenantIdentifierResolver = tenantIdentifierResolver;
		this.properties = properties;
	}

	@Override
	public void customize(Map<String, Object> hibernateProperties) {
		hibernateProperties.put(MultiTenancySettings.MULTI_TENANT_CONNECTION_PROVIDER, connectionProvider);
		hibernateProperties.put(MultiTenancySettings.MULTI_TENANT_IDENTIFIER_RESOLVER, tenantIdentifierResolver);
		hibernateProperties.put(MultiTenancySettings.TENANT_IDENTIFIER_TO_USE_FOR_ANY_KEY,
				TenantIdentifier.normalize(properties.getDefaultTenant()));
	}
}
