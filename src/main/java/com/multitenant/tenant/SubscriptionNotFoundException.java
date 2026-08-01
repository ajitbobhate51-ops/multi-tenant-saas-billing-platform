package com.multitenant.tenant;

public class SubscriptionNotFoundException extends RuntimeException {

	public SubscriptionNotFoundException(String message) {
		super(message);
	}
}
