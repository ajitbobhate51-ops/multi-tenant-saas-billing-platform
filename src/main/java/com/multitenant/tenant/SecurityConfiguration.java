package com.multitenant.tenant;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import jakarta.servlet.DispatcherType;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(SecurityProperties.class)
public class SecurityConfiguration {

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http, PlatformAdminTokenFilter platformAdminTokenFilter,
			JwtAuthenticationFilter jwtAuthenticationFilter, TenantFilter tenantFilter, SecurityErrorWriter securityErrorWriter)
			throws Exception {
		return http
				.csrf(csrf -> csrf.disable())
				.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.exceptionHandling(exceptions -> exceptions
						.authenticationEntryPoint((request, response, authException) -> securityErrorWriter.write(response,
								HttpStatus.UNAUTHORIZED.value(), "UNAUTHENTICATED", "Authentication is required"))
						.accessDeniedHandler((request, response, accessDeniedException) -> securityErrorWriter.write(response,
								HttpStatus.FORBIDDEN.value(), "FORBIDDEN", "Access denied")))
				.authorizeHttpRequests(authorize -> authorize
						.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
						.requestMatchers(HttpMethod.POST, "/api/auth/login").permitAll()
						.requestMatchers(HttpMethod.POST, "/api/tenants/*/users/bootstrap").permitAll()
						.requestMatchers(HttpMethod.POST, "/api/tenant/users").hasAuthority(TenantAuthService.TENANT_ADMIN)
						.requestMatchers(HttpMethod.GET, "/api/tenant/users").hasAuthority(TenantAuthService.TENANT_ADMIN)
						.requestMatchers(HttpMethod.PATCH, "/api/tenant/users/*/enabled").hasAuthority(TenantAuthService.TENANT_ADMIN)
						.requestMatchers(HttpMethod.POST, "/api/plans").hasAuthority(TenantAuthService.TENANT_ADMIN)
						.requestMatchers(HttpMethod.PATCH, "/api/plans/*/enabled").hasAuthority(TenantAuthService.TENANT_ADMIN)
						.requestMatchers(HttpMethod.GET, "/api/plans", "/api/plans/*").hasAnyAuthority(TenantAuthService.TENANT_ADMIN, TenantAuthService.TENANT_USER)
						.requestMatchers("/api/tenants/**").hasAuthority(PlatformAdminTokenFilter.PLATFORM_ADMIN)
						.anyRequest().authenticated())
				.addFilterBefore(platformAdminTokenFilter, UsernamePasswordAuthenticationFilter.class)
				.addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
				.addFilterAfter(tenantFilter, JwtAuthenticationFilter.class)
				.build();
	}

	@Bean
	PlatformAdminTokenFilter platformAdminTokenFilter(SecurityProperties properties, SecurityErrorWriter securityErrorWriter) {
		return new PlatformAdminTokenFilter(properties, securityErrorWriter);
	}

	@Bean
	FilterRegistrationBean<PlatformAdminTokenFilter> platformAdminTokenFilterRegistration(
			PlatformAdminTokenFilter platformAdminTokenFilter) {
		return disabledFilterRegistration(platformAdminTokenFilter);
	}

	@Bean
	JwtAuthenticationFilter jwtAuthenticationFilter(JwtDecoder jwtDecoder, TenantRegistryLookup tenantRegistryLookup,
			SecurityErrorWriter securityErrorWriter) {
		return new JwtAuthenticationFilter(jwtDecoder, tenantRegistryLookup, securityErrorWriter);
	}

	@Bean
	FilterRegistrationBean<JwtAuthenticationFilter> jwtAuthenticationFilterRegistration(
			JwtAuthenticationFilter jwtAuthenticationFilter) {
		return disabledFilterRegistration(jwtAuthenticationFilter);
	}

	@Bean
	TenantFilter tenantFilter(TenantProperties tenantProperties, TenantResolver tenantResolver,
			SecurityErrorWriter securityErrorWriter) {
		return new TenantFilter(tenantProperties, tenantResolver, securityErrorWriter);
	}

	@Bean
	FilterRegistrationBean<TenantFilter> tenantFilterRegistration(TenantFilter tenantFilter) {
		return disabledFilterRegistration(tenantFilter);
	}

	private <T extends jakarta.servlet.Filter> FilterRegistrationBean<T> disabledFilterRegistration(T filter) {
		FilterRegistrationBean<T> registration = new FilterRegistrationBean<>(filter);
		registration.setEnabled(false);
		return registration;
	}

	@Bean
	PasswordEncoder passwordEncoder() {
		return new BCryptPasswordEncoder();
	}

	@Bean
	JwtEncoder jwtEncoder(SecurityProperties properties) {
		return new NimbusJwtEncoder(new ImmutableSecret<>(properties.jwtSigningKey()));
	}

	@Bean
	JwtDecoder jwtDecoder(SecurityProperties properties) {
		return NimbusJwtDecoder.withSecretKey(properties.jwtSigningKey()).build();
	}
}
