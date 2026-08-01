package com.multitenant.tenant;

import jakarta.validation.constraints.NotNull;

public record CreateSubscriptionRequest(
		@NotNull Long customerId,
		@NotNull Long planId) {
}
