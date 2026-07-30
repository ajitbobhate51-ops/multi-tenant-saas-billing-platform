package com.multitenant.tenant;

public class TenantUserNotFoundException extends RuntimeException {

	public TenantUserNotFoundException(String message) {
		super(message);
	}
}
