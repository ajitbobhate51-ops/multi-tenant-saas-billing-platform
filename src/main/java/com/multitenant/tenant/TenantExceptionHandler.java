package com.multitenant.tenant;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class TenantExceptionHandler {

	@ExceptionHandler(IllegalArgumentException.class)
	public ResponseEntity<ApiErrorResponse> badRequest(IllegalArgumentException ex) {
		return ResponseEntity.badRequest().body(ApiErrorResponse.of("BAD_REQUEST", ex.getMessage()));
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<ApiErrorResponse> validation(MethodArgumentNotValidException ex) {
		String message = ex.getBindingResult().getFieldErrors().stream()
			.findFirst()
			.map(error -> error.getField() + " " + error.getDefaultMessage())
			.orElse("Request validation failed");
		return ResponseEntity.badRequest().body(ApiErrorResponse.of("VALIDATION_FAILED", message));
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	public ResponseEntity<ApiErrorResponse> unreadable(HttpMessageNotReadableException ex) {
		return ResponseEntity.badRequest().body(ApiErrorResponse.of("BAD_REQUEST", "Request body is invalid"));
	}

	@ExceptionHandler(BadCredentialsException.class)
	public ResponseEntity<ApiErrorResponse> badCredentials(BadCredentialsException ex) {
		return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiErrorResponse.of("UNAUTHENTICATED", "Invalid credentials"));
	}

	@ExceptionHandler(TenantConflictException.class)
	public ResponseEntity<ApiErrorResponse> conflict(TenantConflictException ex) {
		return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiErrorResponse.of("TENANT_CONFLICT", ex.getMessage()));
	}

	@ExceptionHandler(TenantNotFoundException.class)
	public ResponseEntity<ApiErrorResponse> notFound(TenantNotFoundException ex) {
		return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiErrorResponse.of("TENANT_NOT_FOUND", ex.getMessage()));
	}

	@ExceptionHandler(TenantUserNotFoundException.class)
	public ResponseEntity<ApiErrorResponse> userNotFound(TenantUserNotFoundException ex) {
		return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiErrorResponse.of("TENANT_USER_NOT_FOUND", ex.getMessage()));
	}

	@ExceptionHandler(BillingPlanNotFoundException.class)
	public ResponseEntity<ApiErrorResponse> billingPlanNotFound(BillingPlanNotFoundException ex) {
		return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiErrorResponse.of("BILLING_PLAN_NOT_FOUND", ex.getMessage()));
	}
	@ExceptionHandler(CustomerNotFoundException.class)
	public ResponseEntity<ApiErrorResponse> customerNotFound(CustomerNotFoundException ex) {
		return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiErrorResponse.of("CUSTOMER_NOT_FOUND", ex.getMessage()));
	}

	@ExceptionHandler(SubscriptionNotFoundException.class)
	public ResponseEntity<ApiErrorResponse> subscriptionNotFound(SubscriptionNotFoundException ex) {
		return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiErrorResponse.of("SUBSCRIPTION_NOT_FOUND", ex.getMessage()));
	}
	@ExceptionHandler(TenantAccessException.class)
	public ResponseEntity<ApiErrorResponse> forbidden(TenantAccessException ex) {
		return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiErrorResponse.of("TENANT_ACCESS_DENIED", ex.getMessage()));
	}

	@ExceptionHandler(TenantProvisioningException.class)
	public ResponseEntity<ApiErrorResponse> provisioningFailed(TenantProvisioningException ex) {
		return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
			.body(ApiErrorResponse.of("TENANT_PROVISIONING_FAILED", ex.getMessage()));
	}
}
