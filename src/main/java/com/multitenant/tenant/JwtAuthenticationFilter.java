package com.multitenant.tenant;

import java.io.IOException;
import java.util.List;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.web.filter.OncePerRequestFilter;

public class JwtAuthenticationFilter extends OncePerRequestFilter {

	private final JwtDecoder jwtDecoder;

	private final TenantRegistryLookup tenantRegistryLookup;

	private final SecurityErrorWriter securityErrorWriter;

	public JwtAuthenticationFilter(JwtDecoder jwtDecoder, TenantRegistryLookup tenantRegistryLookup,
			SecurityErrorWriter securityErrorWriter) {
		this.jwtDecoder = jwtDecoder;
		this.tenantRegistryLookup = tenantRegistryLookup;
		this.securityErrorWriter = securityErrorWriter;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
			throws ServletException, IOException {
		String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
		if (authorization == null || !authorization.startsWith("Bearer ")) {
			filterChain.doFilter(request, response);
			return;
		}

		try {
			Jwt jwt = jwtDecoder.decode(authorization.substring(7));
			String tenantId = TenantIdentifier.normalize(jwt.getClaimAsString("tenant_id"));
			String role = jwt.getClaimAsString("role");
			String email = jwt.getSubject();
			if (tenantId == null || role == null || role.isBlank() || email == null || email.isBlank()) {
				throw new JwtException("JWT is missing required tenant authentication claims");
			}
			TenantRole tenantRole = TenantRole.from(role);
			tenantRegistryLookup.requireActiveSchema(tenantId);
			TenantPrincipal principal = new TenantPrincipal(email, tenantId, tenantRole.name());
			UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(principal, jwt,
					List.of(new SimpleGrantedAuthority(tenantRole.name())));
			SecurityContextHolder.getContext().setAuthentication(authentication);
			filterChain.doFilter(request, response);
		}
		catch (JwtException | IllegalArgumentException | TenantAccessException | TenantNotFoundException ex) {
			SecurityContextHolder.clearContext();
			securityErrorWriter.write(response, HttpStatus.UNAUTHORIZED.value(), "UNAUTHENTICATED", "Invalid or expired token");
		}
	}
}
