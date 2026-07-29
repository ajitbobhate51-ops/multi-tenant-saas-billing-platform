package com.multitenant.tenant;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
public class HeaderTenantResolver implements TenantResolver {

	private final TenantProperties properties;
	private final TenantRegistryLookup tenantRegistryLookup;

	public HeaderTenantResolver(TenantProperties properties, TenantRegistryLookup tenantRegistryLookup) {
		this.properties = properties;
		this.tenantRegistryLookup = tenantRegistryLookup;
	}

	@Override
	public String resolveTenant(HttpServletRequest request) {
		String requestedTenant = TenantIdentifier.normalize(request.getHeader(properties.getHeaderName()));
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

		if (authentication != null && authentication.getPrincipal() instanceof TenantPrincipal principal) {
			String authenticatedTenant = TenantIdentifier.normalize(principal.tenantId());
			String resolvedTenant = requestedTenant == null ? authenticatedTenant : requestedTenant;
			if (!authenticatedTenant.equals(resolvedTenant)) {
				throw new TenantAccessException("Authenticated tenant does not match requested tenant");
			}
			tenantRegistryLookup.requireActiveSchema(resolvedTenant);
			return resolvedTenant;
		}

		if (requestedTenant == null || requestedTenant.equals(TenantIdentifier.normalize(properties.getDefaultTenant()))) {
			return requestedTenant;
		}

		tenantRegistryLookup.requireActiveSchema(requestedTenant);
		return requestedTenant;
	}
}
