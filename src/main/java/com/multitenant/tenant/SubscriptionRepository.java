package com.multitenant.tenant;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SubscriptionRepository extends JpaRepository<Subscription, Long> {

	boolean existsByCustomerIdAndPlanIdAndStatus(Long customerId, Long planId, SubscriptionStatus status);
}
