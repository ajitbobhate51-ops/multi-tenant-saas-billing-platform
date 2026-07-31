package com.multitenant.tenant;

import jakarta.validation.constraints.NotNull;

public record UpdateBillingPlanEnabledRequest(@NotNull Boolean enabled) {
}
