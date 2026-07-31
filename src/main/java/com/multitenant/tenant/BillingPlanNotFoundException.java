package com.multitenant.tenant;

public class BillingPlanNotFoundException extends RuntimeException {

	public BillingPlanNotFoundException(String message) {
		super(message);
	}
}
