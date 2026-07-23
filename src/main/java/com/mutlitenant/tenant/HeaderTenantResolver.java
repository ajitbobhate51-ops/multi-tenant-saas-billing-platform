package com.mutlitenant.tenant;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

@Component
public class HeaderTenantResolver implements TenantResolver {

	private final TenantProperties properties;

	public HeaderTenantResolver(TenantProperties properties) {
		this.properties = properties;
	}

	@Override
	public String resolveTenant(HttpServletRequest request) {
		return TenantIdentifier.normalize(request.getHeader(properties.getHeaderName()));
	}
}
