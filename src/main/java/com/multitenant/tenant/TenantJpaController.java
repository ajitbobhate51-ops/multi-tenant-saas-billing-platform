package com.multitenant.tenant;

import java.util.Map;

import jakarta.persistence.EntityManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TenantJpaController {

	private final EntityManager entityManager;

	public TenantJpaController(EntityManager entityManager) {
		this.entityManager = entityManager;
	}

	@GetMapping("/tenant/jpa")
	@Transactional(readOnly = true)
	public Map<String, String> tenantFromJpaConnection() {
		String schema = (String) entityManager.createNativeQuery("select current_schema()", String.class)
			.getSingleResult();
		return Map.of("tenant", TenantContext.getTenant(), "schema", schema);
	}
}
