package com.multitenant.tenant;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class TenantMigrationStartupRunner implements ApplicationRunner {

	private final TenantProvisioningService tenantProvisioningService;

	public TenantMigrationStartupRunner(TenantProvisioningService tenantProvisioningService) {
		this.tenantProvisioningService = tenantProvisioningService;
	}

	@Override
	public void run(ApplicationArguments args) {
		tenantProvisioningService.migrateActiveTenants();
	}
}
