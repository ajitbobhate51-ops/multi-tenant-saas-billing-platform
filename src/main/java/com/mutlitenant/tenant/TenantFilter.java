package com.mutlitenant.tenant;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class TenantFilter extends OncePerRequestFilter {

	private final TenantProperties properties;
	private final TenantResolver tenantResolver;

	public TenantFilter(TenantProperties properties, TenantResolver tenantResolver) {
		this.properties = properties;
		this.tenantResolver = tenantResolver;
	}

	@Override
	protected void doFilterInternal(
			HttpServletRequest request,
			HttpServletResponse response,
			FilterChain filterChain)
			throws ServletException, IOException {

		try {
			String tenant = tenantResolver.resolveTenant(request);

			TenantContext.setTenant(
					tenant == null
							? TenantIdentifier.normalize(properties.getDefaultTenant())
							: tenant
			);

			filterChain.doFilter(request, response);
		}
		catch (IllegalArgumentException ex) {
			response.sendError(HttpStatus.BAD_REQUEST.value(), ex.getMessage());
		}
		catch (TenantNotFoundException ex) {
			response.sendError(HttpStatus.NOT_FOUND.value(), ex.getMessage());
		}
		catch (TenantAccessException ex) {
			response.sendError(HttpStatus.FORBIDDEN.value(), ex.getMessage());
		}
		finally {
			TenantContext.clear();
		}
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		return request.getRequestURI().startsWith("/api/tenants");
	}
}