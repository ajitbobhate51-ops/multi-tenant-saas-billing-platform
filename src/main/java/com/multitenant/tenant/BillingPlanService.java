package com.multitenant.tenant;

import java.util.List;
import java.util.Locale;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BillingPlanService {

	private final BillingPlanRepository billingPlanRepository;

	public BillingPlanService(BillingPlanRepository billingPlanRepository) {
		this.billingPlanRepository = billingPlanRepository;
	}

	@Transactional
	public BillingPlanResponse createPlan(CreateBillingPlanRequest request) {
		String code = normalizeCode(request.code());
		if (billingPlanRepository.existsByCodeIgnoreCase(code)) {
			throw new TenantConflictException("Billing plan code already exists");
		}

		BillingPlan plan = new BillingPlan();
		plan.setCode(code);
		plan.setName(normalizeRequiredText(request.name()));
		plan.setDescription(normalizeOptionalText(request.description()));
		plan.setAmount(request.amount());
		plan.setCurrency(normalizeCurrency(request.currency()));
		plan.setBillingInterval(request.billingInterval());
		plan.setActive(true);
		try {
			return BillingPlanResponse.from(billingPlanRepository.saveAndFlush(plan));
		}
		catch (DataIntegrityViolationException ex) {
			throw new TenantConflictException("Billing plan code already exists");
		}
	}

	@Transactional(readOnly = true)
	public List<BillingPlanResponse> listPlans() {
		return billingPlanRepository.findAll()
			.stream()
			.map(BillingPlanResponse::from)
			.toList();
	}

	@Transactional(readOnly = true)
	public BillingPlanResponse getPlan(Long planId) {
		return BillingPlanResponse.from(findPlan(planId));
	}

	@Transactional
	public BillingPlanResponse updateEnabled(Long planId, UpdateBillingPlanEnabledRequest request) {
		BillingPlan plan = findPlan(planId);
		plan.setActive(request.enabled());
		return BillingPlanResponse.from(billingPlanRepository.saveAndFlush(plan));
	}

	private BillingPlan findPlan(Long planId) {
		return billingPlanRepository.findById(planId)
			.orElseThrow(() -> new BillingPlanNotFoundException("Billing plan does not exist"));
	}

	private String normalizeCode(String code) {
		return code.trim().toUpperCase(Locale.ROOT);
	}

	private String normalizeCurrency(String currency) {
		return currency.trim().toUpperCase(Locale.ROOT);
	}

	private String normalizeRequiredText(String value) {
		return value.trim();
	}

	private String normalizeOptionalText(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		return value.trim();
	}
}
