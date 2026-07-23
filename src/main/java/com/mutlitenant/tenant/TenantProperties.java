package com.mutlitenant.tenant;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.tenancy")
public class TenantProperties {

	private String headerName = "X-Tenant-ID";

	private String defaultTenant = "public";

	private boolean createSchema = true;

	public String getHeaderName() {
		return headerName;
	}

	public void setHeaderName(String headerName) {
		this.headerName = headerName;
	}

	public String getDefaultTenant() {
		return defaultTenant;
	}

	public void setDefaultTenant(String defaultTenant) {
		this.defaultTenant = defaultTenant;
	}

	public boolean isCreateSchema() {
		return createSchema;
	}

	public void setCreateSchema(boolean createSchema) {
		this.createSchema = createSchema;
	}
}
