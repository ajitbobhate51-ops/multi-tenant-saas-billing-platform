package com.multitenant.tenant;

public class TenantConflictException extends RuntimeException {

	public TenantConflictException(String message) {
		super(message);
	}
}
