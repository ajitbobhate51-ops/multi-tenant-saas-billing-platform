package com.multitenant.tenant;

public class TenantAccessException extends RuntimeException {

	public TenantAccessException(String message) {
		super(message);
	}
}
