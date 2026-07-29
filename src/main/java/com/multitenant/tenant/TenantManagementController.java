package com.multitenant.tenant;

import java.net.URI;
import java.util.List;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/tenants")
public class TenantManagementController {

	private final TenantProvisioningService tenantProvisioningService;

	public TenantManagementController(TenantProvisioningService tenantProvisioningService) {
		this.tenantProvisioningService = tenantProvisioningService;
	}

	@PostMapping
	public ResponseEntity<TenantResponse> registerTenant(@Valid @RequestBody CreateTenantRequest request) {
		TenantResponse tenant = tenantProvisioningService.registerAndProvision(request);
		return ResponseEntity.created(URI.create("/api/tenants/" + tenant.tenantId())).body(tenant);
	}

	@GetMapping("/{tenantId}")
	public TenantResponse getTenant(@PathVariable String tenantId) {
		return tenantProvisioningService.getTenant(tenantId);
	}

	@GetMapping
	public List<TenantResponse> listTenants() {
		return tenantProvisioningService.listTenants();
	}

	@PostMapping("/{tenantId}/provision")
	public TenantResponse provisionTenant(@PathVariable String tenantId) {
		return tenantProvisioningService.provisionTenant(tenantId);
	}
}
