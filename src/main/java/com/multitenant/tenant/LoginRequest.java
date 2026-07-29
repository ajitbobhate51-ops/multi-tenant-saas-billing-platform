package com.multitenant.tenant;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LoginRequest(
		@NotBlank @Size(max = 63) String tenantId,
		@NotBlank @Email @Size(max = 320) String email,
		@NotBlank @Size(max = 128) String password) {
}
