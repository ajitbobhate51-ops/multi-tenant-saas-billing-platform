package com.mutlitenant.tenant;

public class TenantAccessException extends RuntimeException {

	public TenantAccessException(String message) {
		super(message);
	}
}
