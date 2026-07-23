package com.mutlitenant.tenant;

public class TenantNotFoundException extends RuntimeException {

	public TenantNotFoundException(String message) {
		super(message);
	}
}
