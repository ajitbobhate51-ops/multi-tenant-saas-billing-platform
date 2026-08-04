package com.multitenant.tenant;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PricingService {

	private final SubscriptionRepository subscriptionRepository;

	public PricingService(SubscriptionRepository subscriptionRepository) {
		this.subscriptionRepository = subscriptionRepository;
	}

	@Transactional(readOnly = true)
	public PricingResponse calculate(Long subscriptionId) {
		Subscription subscription = subscriptionRepository.findById(subscriptionId)
			.orElseThrow(() -> new SubscriptionNotFoundException("Subscription does not exist"));
		return PricingResponse.from(subscription);
	}
}