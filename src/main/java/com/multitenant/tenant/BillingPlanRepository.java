package com.multitenant.tenant;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface BillingPlanRepository extends JpaRepository<BillingPlan, Long> {

	boolean existsByCodeIgnoreCase(String code);

	Optional<BillingPlan> findByCodeIgnoreCase(String code);
}
