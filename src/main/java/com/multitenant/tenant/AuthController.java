package com.multitenant.tenant;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

	private final TenantAuthService tenantAuthService;

	public AuthController(TenantAuthService tenantAuthService) {
		this.tenantAuthService = tenantAuthService;
	}

	@PostMapping("/login")
	public LoginResponse login(@Valid @RequestBody LoginRequest request) {
		return tenantAuthService.login(request);
	}
}
