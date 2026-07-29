package com.multitenant.tenant;

public class TenantNotFoundException extends RuntimeException {

	public TenantNotFoundException(String message) {
		super(message);
	}
}
