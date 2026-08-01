package com.multitenant.tenant;

import java.net.URI;
import java.util.List;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/subscriptions")
public class SubscriptionController {

	private final SubscriptionService subscriptionService;

	public SubscriptionController(SubscriptionService subscriptionService) {
		this.subscriptionService = subscriptionService;
	}

	@PostMapping
	public ResponseEntity<SubscriptionResponse> createSubscription(@Valid @RequestBody CreateSubscriptionRequest request) {
		SubscriptionResponse subscription = subscriptionService.createSubscription(request);
		return ResponseEntity.created(URI.create("/api/subscriptions/" + subscription.id())).body(subscription);
	}

	@GetMapping
	public List<SubscriptionResponse> listSubscriptions() {
		return subscriptionService.listSubscriptions();
	}

	@GetMapping("/{subscriptionId}")
	public SubscriptionResponse getSubscription(@PathVariable Long subscriptionId) {
		return subscriptionService.getSubscription(subscriptionId);
	}

	@PatchMapping("/{subscriptionId}/cancel")
	public SubscriptionResponse cancelSubscription(@PathVariable Long subscriptionId,
			@RequestBody(required = false) CancelSubscriptionRequest request) {
		return subscriptionService.cancelSubscription(subscriptionId);
	}
}
