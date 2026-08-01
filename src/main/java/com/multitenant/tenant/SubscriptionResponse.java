package com.multitenant.tenant;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record SubscriptionResponse(Long id, Long customerId, Long planId, String planCode, String planName,
		BigDecimal planAmount, String currency, BillingInterval billingInterval, SubscriptionStatus status,
		OffsetDateTime startedAt, OffsetDateTime cancelledAt, OffsetDateTime createdAt, OffsetDateTime updatedAt) {

	public static SubscriptionResponse from(Subscription subscription) {
		BillingPlan plan = subscription.getPlan();
		return new SubscriptionResponse(subscription.getId(), subscription.getCustomer().getId(), plan.getId(), plan.getCode(),
				plan.getName(), plan.getAmount(), plan.getCurrency(), plan.getBillingInterval(), subscription.getStatus(),
				subscription.getStartedAt(), subscription.getCancelledAt(), subscription.getCreatedAt(), subscription.getUpdatedAt());
	}
}
