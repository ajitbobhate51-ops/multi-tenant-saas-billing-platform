package com.multitenant.tenant;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

@Component
public class HeaderTenantResolver implements TenantResolver {

	private final TenantProperties properties;
	private final TenantRegistryLookup tenantRegistryLookup;

	public HeaderTenantResolver(
			TenantProperties properties,
			TenantRegistryLookup tenantRegistryLookup) {

		this.properties = properties;
		this.tenantRegistryLookup = tenantRegistryLookup;
	}

	@Override
	public String resolveTenant(HttpServletRequest request) {

		String tenant =
				TenantIdentifier.normalize(
						request.getHeader(properties.getHeaderName())
				);

		if (tenant == null ||
				tenant.equals(
						TenantIdentifier.normalize(properties.getDefaultTenant())
				)) {

			return tenant;
		}

		tenantRegistryLookup.requireActiveSchema(tenant);

		return tenant;
	}
}