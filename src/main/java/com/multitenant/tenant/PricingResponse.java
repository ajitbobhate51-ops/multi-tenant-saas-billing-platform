package com.multitenant.tenant;

import java.math.BigDecimal;

public record PricingResponse(Long subscriptionId, Long customerId, Long planId, String planName, String currency,
		BillingInterval billingInterval, BigDecimal subtotal, BigDecimal discount, BigDecimal tax, BigDecimal finalAmount,
		SubscriptionStatus subscriptionStatus) {

	public static PricingResponse from(Subscription subscription) {
		BillingPlan plan = subscription.getPlan();
		BigDecimal subtotal = plan.getAmount();
		BigDecimal discount = BigDecimal.ZERO;
		BigDecimal tax = BigDecimal.ZERO;
		return new PricingResponse(subscription.getId(), subscription.getCustomer().getId(), plan.getId(), plan.getName(),
				plan.getCurrency(), plan.getBillingInterval(), subtotal, discount, tax, subtotal, subscription.getStatus());
	}
}