package com.multitenant.tenant;

public class TenantProvisioningException extends RuntimeException {

	public TenantProvisioningException(String message, Throwable cause) {
		super(message, cause);
	}
}
