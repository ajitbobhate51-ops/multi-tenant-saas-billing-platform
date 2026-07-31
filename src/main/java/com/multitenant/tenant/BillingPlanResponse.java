package com.multitenant.tenant;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record BillingPlanResponse(Long id, String code, String name, String description, BigDecimal amount,
		String currency, BillingInterval billingInterval, boolean active, OffsetDateTime createdAt, OffsetDateTime updatedAt) {

	public static BillingPlanResponse from(BillingPlan plan) {
		return new BillingPlanResponse(plan.getId(), plan.getCode(), plan.getName(), plan.getDescription(), plan.getAmount(),
				plan.getCurrency(), plan.getBillingInterval(), plan.isActive(), plan.getCreatedAt(), plan.getUpdatedAt());
	}
}
