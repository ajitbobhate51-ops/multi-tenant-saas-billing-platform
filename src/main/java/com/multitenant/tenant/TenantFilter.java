package com.multitenant.tenant;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

public class TenantFilter extends OncePerRequestFilter {

	private final TenantProperties properties;
	private final TenantResolver tenantResolver;
	private final SecurityErrorWriter securityErrorWriter;

	public TenantFilter(TenantProperties properties, TenantResolver tenantResolver, SecurityErrorWriter securityErrorWriter) {
		this.properties = properties;
		this.tenantResolver = tenantResolver;
		this.securityErrorWriter = securityErrorWriter;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
			throws ServletException, IOException {

		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication == null || !(authentication.getPrincipal() instanceof TenantPrincipal)) {
			filterChain.doFilter(request, response);
			return;
		}

		try {
			String tenant = tenantResolver.resolveTenant(request);
			TenantContext.setTenant(tenant == null ? TenantIdentifier.normalize(properties.getDefaultTenant()) : tenant);
			filterChain.doFilter(request, response);
		}
		catch (IllegalArgumentException ex) {
			securityErrorWriter.write(response, HttpStatus.BAD_REQUEST.value(), "BAD_REQUEST", ex.getMessage());
		}
		catch (TenantNotFoundException ex) {
			securityErrorWriter.write(response, HttpStatus.NOT_FOUND.value(), "TENANT_NOT_FOUND", ex.getMessage());
		}
		catch (TenantAccessException ex) {
			securityErrorWriter.write(response, HttpStatus.FORBIDDEN.value(), "TENANT_ACCESS_DENIED", ex.getMessage());
		}
		finally {
			TenantContext.clear();
		}
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		return request.getRequestURI().startsWith("/api/tenants") || request.getRequestURI().startsWith("/api/auth");
	}
}
