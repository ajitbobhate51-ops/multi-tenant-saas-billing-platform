package com.multitenant.tenant;

import java.net.URI;
import java.util.List;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/tenant/users")
public class TenantUserManagementController {

	private final TenantUserManagementService tenantUserManagementService;

	public TenantUserManagementController(TenantUserManagementService tenantUserManagementService) {
		this.tenantUserManagementService = tenantUserManagementService;
	}

	@PostMapping
	public ResponseEntity<TenantUserResponse> createUser(@Valid @RequestBody CreateTenantUserRequest request) {
		TenantUserResponse user = tenantUserManagementService.createUser(request);
		return ResponseEntity.created(URI.create("/api/tenant/users/" + user.id())).body(user);
	}

	@GetMapping
	public List<TenantUserResponse> listUsers() {
		return tenantUserManagementService.listUsers();
	}

	@PatchMapping("/{userId}/enabled")
	public TenantUserResponse updateEnabled(@PathVariable Long userId,
			@Valid @RequestBody UpdateTenantUserEnabledRequest request) {
		return tenantUserManagementService.updateEnabled(userId, request);
	}
}
