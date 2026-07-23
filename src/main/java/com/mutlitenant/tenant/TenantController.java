package com.mutlitenant.tenant;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TenantController {

	@GetMapping("/tenant")
	public Map<String, String> tenant() {
		return Map.of("tenant", TenantContext.getTenant());
	}
}
