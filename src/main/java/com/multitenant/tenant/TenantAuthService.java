package com.multitenant.tenant;

import java.security.MessageDigest;
import java.util.Locale;

import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class TenantAuthService {

	public static final String TENANT_ADMIN = TenantRole.TENANT_ADMIN.name();

	public static final String TENANT_USER = TenantRole.TENANT_USER.name();

	private final TenantRegistryLookup tenantRegistryLookup;

	private final TenantUserRepository tenantUserRepository;

	private final PasswordEncoder passwordEncoder;

	private final JwtTokenService jwtTokenService;

	private final SecurityProperties securityProperties;

	private final TransactionTemplate transactionTemplate;

	public TenantAuthService(TenantRegistryLookup tenantRegistryLookup, TenantUserRepository tenantUserRepository,
			PasswordEncoder passwordEncoder, JwtTokenService jwtTokenService, SecurityProperties securityProperties,
			TransactionTemplate transactionTemplate) {
		this.tenantRegistryLookup = tenantRegistryLookup;
		this.tenantUserRepository = tenantUserRepository;
		this.passwordEncoder = passwordEncoder;
		this.jwtTokenService = jwtTokenService;
		this.securityProperties = securityProperties;
		this.transactionTemplate = transactionTemplate;
	}

	public TenantUserResponse bootstrapFirstUser(String tenantId, String bootstrapToken,
			BootstrapTenantUserRequest request) {
		if (!constantTimeEquals(securityProperties.requiredBootstrapToken(), bootstrapToken)) {
			throw new TenantAccessException("Invalid tenant bootstrap token");
		}
		String normalizedTenantId = TenantIdentifier.normalize(tenantId);
		tenantRegistryLookup.requireActiveSchema(normalizedTenantId);
		return executeInTenant(normalizedTenantId, () -> transactionTemplate.execute(status -> {
			if (tenantUserRepository.count() > 0) {
				throw new TenantConflictException("Tenant already has a bootstrapped user");
			}
			TenantUser user = new TenantUser();
			user.setEmail(normalizeEmail(request.email()));
			user.setPasswordHash(passwordEncoder.encode(request.password()));
			user.setEnabled(true);
			user.setRole(TenantRole.TENANT_ADMIN.name());
			return TenantUserResponse.from(tenantUserRepository.saveAndFlush(user));
		}));
	}

	public LoginResponse login(LoginRequest request) {
		String tenantId = TenantIdentifier.normalize(request.tenantId());
		tenantRegistryLookup.requireActiveSchema(tenantId);
		return executeInTenant(tenantId, () -> {
			TenantUser user = tenantUserRepository.findByEmailIgnoreCase(normalizeEmail(request.email()))
					.orElseThrow(() -> new BadCredentialsException("Invalid credentials"));
			if (!user.isEnabled() || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
				throw new BadCredentialsException("Invalid credentials");
			}
			TenantRole.from(user.getRole());
			return jwtTokenService.issueToken(tenantId, user);
		});
	}

	private <T> T executeInTenant(String tenantId, TenantOperation<T> operation) {
		TenantContext.setTenant(tenantId);
		try {
			return operation.execute();
		}
		finally {
			TenantContext.clear();
		}
	}

	private String normalizeEmail(String email) {
		return email.trim().toLowerCase(Locale.ROOT);
	}

	private boolean constantTimeEquals(String expected, String actual) {
		if (actual == null) {
			return false;
		}
		return MessageDigest.isEqual(expected.getBytes(java.nio.charset.StandardCharsets.UTF_8),
				actual.getBytes(java.nio.charset.StandardCharsets.UTF_8));
	}

	private interface TenantOperation<T> {

		T execute();
	}
}
