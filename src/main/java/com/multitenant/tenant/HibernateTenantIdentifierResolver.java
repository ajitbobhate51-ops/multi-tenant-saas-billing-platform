package com.multitenant.tenant;

import org.hibernate.context.spi.CurrentTenantIdentifierResolver;
import org.springframework.stereotype.Component;

@Component
public class HibernateTenantIdentifierResolver implements CurrentTenantIdentifierResolver<String> {

	private final TenantProperties properties;

	public HibernateTenantIdentifierResolver(TenantProperties properties) {
		this.properties = properties;
	}

	@Override
	public String resolveCurrentTenantIdentifier() {
		String tenant = TenantIdentifier.normalize(TenantContext.getTenant());
		return tenant == null ? TenantIdentifier.normalize(properties.getDefaultTenant()) : tenant;
	}

	@Override
	public boolean validateExistingCurrentSessions() {
		return true;
	}

	@Override
	public boolean isRoot(String tenantId) {
		return TenantIdentifier.normalize(properties.getDefaultTenant()).equals(TenantIdentifier.normalize(tenantId));
	}
}
