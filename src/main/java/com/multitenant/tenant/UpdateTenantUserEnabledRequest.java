package com.multitenant.tenant;

import jakarta.validation.constraints.NotNull;

public record UpdateTenantUserEnabledRequest(@NotNull Boolean enabled) {
}
