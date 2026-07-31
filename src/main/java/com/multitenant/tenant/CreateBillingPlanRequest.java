package com.multitenant.tenant;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateBillingPlanRequest(
		@NotBlank @Size(max = 64) String code,
		@NotBlank @Size(max = 255) String name,
		@Size(max = 1000) String description,
		@NotNull @DecimalMin(value = "0.00") @Digits(integer = 17, fraction = 2) BigDecimal amount,
		@NotBlank @Pattern(regexp = "[A-Za-z]{3}") String currency,
		@NotNull BillingInterval billingInterval) {
}
