package com.multitenant.tenant;

import java.io.IOException;
import java.util.List;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

public class PlatformAdminTokenFilter extends OncePerRequestFilter {

	public static final String PLATFORM_ADMIN = "PLATFORM_ADMIN";

	private final String platformAdminToken;

	private final SecurityErrorWriter securityErrorWriter;

	public PlatformAdminTokenFilter(SecurityProperties properties, SecurityErrorWriter securityErrorWriter) {
		this.platformAdminToken = properties.requiredPlatformAdminToken();
		this.securityErrorWriter = securityErrorWriter;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
			throws ServletException, IOException {
		if (request.getRequestURI().startsWith("/api/tenants")) {
			String providedToken = request.getHeader("X-Platform-Admin-Token");
			if (providedToken != null && providedToken.equals(platformAdminToken)) {
				UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
						"platform-admin", null, List.of(new SimpleGrantedAuthority(PLATFORM_ADMIN)));
				SecurityContextHolder.getContext().setAuthentication(authentication);
			}
		}
		filterChain.doFilter(request, response);
	}
}
