package com.multitenant.tenant;

import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SubscriptionService {

	private final SubscriptionRepository subscriptionRepository;

	private final CustomerRepository customerRepository;

	private final BillingPlanRepository billingPlanRepository;

	public SubscriptionService(SubscriptionRepository subscriptionRepository, CustomerRepository customerRepository,
			BillingPlanRepository billingPlanRepository) {
		this.subscriptionRepository = subscriptionRepository;
		this.customerRepository = customerRepository;
		this.billingPlanRepository = billingPlanRepository;
	}

	@Transactional
	public SubscriptionResponse createSubscription(CreateSubscriptionRequest request) {
		Customer customer = customerRepository.findById(request.customerId())
			.orElseThrow(() -> new CustomerNotFoundException("Customer does not exist"));
		BillingPlan plan = billingPlanRepository.findById(request.planId())
			.orElseThrow(() -> new BillingPlanNotFoundException("Billing plan does not exist"));
		if (!plan.isActive()) {
			throw new TenantConflictException("Billing plan is inactive");
		}
		if (subscriptionRepository.existsByCustomerIdAndPlanIdAndStatus(customer.getId(), plan.getId(), SubscriptionStatus.ACTIVE)) {
			throw new TenantConflictException("Customer already has an active subscription for this billing plan");
		}

		Subscription subscription = new Subscription();
		subscription.setCustomer(customer);
		subscription.setPlan(plan);
		subscription.setStatus(SubscriptionStatus.ACTIVE);
		subscription.setStartedAt(OffsetDateTime.now());
		try {
			return SubscriptionResponse.from(subscriptionRepository.saveAndFlush(subscription));
		}
		catch (DataIntegrityViolationException ex) {
			throw new TenantConflictException("Customer already has an active subscription for this billing plan");
		}
	}

	@Transactional(readOnly = true)
	public List<SubscriptionResponse> listSubscriptions() {
		return subscriptionRepository.findAll()
			.stream()
			.map(SubscriptionResponse::from)
			.toList();
	}

	@Transactional(readOnly = true)
	public SubscriptionResponse getSubscription(Long subscriptionId) {
		return SubscriptionResponse.from(findSubscription(subscriptionId));
	}

	@Transactional
	public SubscriptionResponse cancelSubscription(Long subscriptionId) {
		Subscription subscription = findSubscription(subscriptionId);
		if (subscription.getStatus() == SubscriptionStatus.CANCELLED) {
			return SubscriptionResponse.from(subscription);
		}
		subscription.setStatus(SubscriptionStatus.CANCELLED);
		subscription.setCancelledAt(OffsetDateTime.now());
		return SubscriptionResponse.from(subscriptionRepository.saveAndFlush(subscription));
	}

	private Subscription findSubscription(Long subscriptionId) {
		return subscriptionRepository.findById(subscriptionId)
			.orElseThrow(() -> new SubscriptionNotFoundException("Subscription does not exist"));
	}
}
