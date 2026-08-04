package com.multitenant.tenant;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/pricing")
public class PricingController {

	private final PricingService pricingService;

	public PricingController(PricingService pricingService) {
		this.pricingService = pricingService;
	}

	@GetMapping("/{subscriptionId}")
	public PricingResponse calculate(@PathVariable Long subscriptionId) {
		return pricingService.calculate(subscriptionId);
	}
}