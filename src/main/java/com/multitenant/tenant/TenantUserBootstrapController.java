package com.multitenant.tenant;

import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/tenants/{tenantId}/users")
public class TenantUserBootstrapController {

	private final TenantAuthService tenantAuthService;

	public TenantUserBootstrapController(TenantAuthService tenantAuthService) {
		this.tenantAuthService = tenantAuthService;
	}

	@PostMapping("/bootstrap")
	@ResponseStatus(HttpStatus.CREATED)
	public TenantUserResponse bootstrapFirstUser(@PathVariable String tenantId,
			@RequestHeader(name = "X-Bootstrap-Token", required = false) String bootstrapToken,
			@Valid @RequestBody BootstrapTenantUserRequest request) {
		return tenantAuthService.bootstrapFirstUser(tenantId, bootstrapToken, request);
	}
}
