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
@RequestMapping("/api/plans")
public class BillingPlanController {

	private final BillingPlanService billingPlanService;

	public BillingPlanController(BillingPlanService billingPlanService) {
		this.billingPlanService = billingPlanService;
	}

	@PostMapping
	public ResponseEntity<BillingPlanResponse> createPlan(@Valid @RequestBody CreateBillingPlanRequest request) {
		BillingPlanResponse plan = billingPlanService.createPlan(request);
		return ResponseEntity.created(URI.create("/api/plans/" + plan.id())).body(plan);
	}

	@GetMapping
	public List<BillingPlanResponse> listPlans() {
		return billingPlanService.listPlans();
	}

	@GetMapping("/{planId}")
	public BillingPlanResponse getPlan(@PathVariable Long planId) {
		return billingPlanService.getPlan(planId);
	}

	@PatchMapping("/{planId}/enabled")
	public BillingPlanResponse updateEnabled(@PathVariable Long planId,
			@Valid @RequestBody UpdateBillingPlanEnabledRequest request) {
		return billingPlanService.updateEnabled(planId, request);
	}
}
