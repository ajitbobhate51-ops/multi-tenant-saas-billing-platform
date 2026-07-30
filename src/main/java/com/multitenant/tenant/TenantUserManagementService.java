package com.multitenant.tenant;

import java.util.List;
import java.util.Locale;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TenantUserManagementService {

	private final TenantUserRepository tenantUserRepository;

	private final PasswordEncoder passwordEncoder;

	public TenantUserManagementService(TenantUserRepository tenantUserRepository, PasswordEncoder passwordEncoder) {
		this.tenantUserRepository = tenantUserRepository;
		this.passwordEncoder = passwordEncoder;
	}

	@Transactional
	public TenantUserResponse createUser(CreateTenantUserRequest request) {
		String email = normalizeEmail(request.email());
		if (tenantUserRepository.findByEmailIgnoreCase(email).isPresent()) {
			throw new TenantConflictException("Tenant user email already exists");
		}

		TenantUser user = new TenantUser();
		user.setEmail(email);
		user.setPasswordHash(passwordEncoder.encode(request.password()));
		user.setEnabled(true);
		user.setRole(request.role().name());
		try {
			return TenantUserResponse.from(tenantUserRepository.saveAndFlush(user));
		}
		catch (DataIntegrityViolationException ex) {
			throw new TenantConflictException("Tenant user email already exists");
		}
	}

	@Transactional(readOnly = true)
	public List<TenantUserResponse> listUsers() {
		return tenantUserRepository.findAll()
			.stream()
			.map(TenantUserResponse::from)
			.toList();
	}

	@Transactional
	public TenantUserResponse updateEnabled(Long userId, UpdateTenantUserEnabledRequest request) {
		TenantUser user = tenantUserRepository.findById(userId)
			.orElseThrow(() -> new TenantUserNotFoundException("Tenant user does not exist"));
		user.setEnabled(request.enabled());
		return TenantUserResponse.from(tenantUserRepository.saveAndFlush(user));
	}

	private String normalizeEmail(String email) {
		return email.trim().toLowerCase(Locale.ROOT);
	}
}
