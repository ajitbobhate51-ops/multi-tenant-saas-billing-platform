package com.mutlitenant.tenant;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Locale;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class TenantProvisioningService {

	private final TenantRepository tenantRepository;

	private final DataSource dataSource;

	private final TenantProperties properties;

	private final TransactionTemplate transactionTemplate;

	public TenantProvisioningService(TenantRepository tenantRepository, DataSource dataSource, TenantProperties properties,
			TransactionTemplate transactionTemplate) {
		this.tenantRepository = tenantRepository;
		this.dataSource = dataSource;
		this.properties = properties;
		this.transactionTemplate = transactionTemplate;
	}

	public TenantResponse registerAndProvision(CreateTenantRequest request) {
		Tenant tenant = registerTenant(request);
		return provisionTenant(tenant.getTenantId());
	}

	public List<TenantResponse> listTenants() {
		return tenantRepository.findAll().stream().map(TenantResponse::from).toList();
	}

	public TenantResponse getTenant(String tenantId) {
		return TenantResponse.from(findTenant(tenantId));
	}

	public TenantResponse provisionTenant(String tenantId) {
		Tenant tenant = markProvisioning(tenantId);
		if (tenant.getStatus() == TenantStatus.ACTIVE && tenant.getProvisioningStatus() == ProvisioningStatus.ACTIVE) {
			return TenantResponse.from(tenant);
		}
		try {
			createSchema(tenant.getSchemaName());
			runTenantMigrations(tenant.getSchemaName());
			return TenantResponse.from(markActive(tenant.getTenantId()));
		}
		catch (RuntimeException ex) {
			markFailed(tenant.getTenantId(), ex.getMessage());
			throw new TenantProvisioningException("Provisioning failed for tenant: " + tenant.getTenantId(), ex);
		}
	}

	private Tenant registerTenant(CreateTenantRequest request) {
		String tenantName = normalizeTenantName(request.tenantName());
		String tenantId = request.tenantId() == null || request.tenantId().isBlank()
				? generateTenantId(tenantName) : TenantIdentifier.normalize(request.tenantId());
		String schemaName = request.schemaName() == null || request.schemaName().isBlank()
				? tenantId : TenantIdentifier.normalize(request.schemaName());

		if (tenantId.equals(defaultTenant()) || schemaName.equals(defaultTenant())) {
			throw new IllegalArgumentException("Tenant id and schema name cannot use the platform schema");
		}

		return transactionTemplate.execute(status -> {
			if (tenantRepository.existsById(tenantId)) {
				throw new TenantConflictException("Tenant already exists: " + tenantId);
			}
			if (tenantRepository.existsByTenantNameIgnoreCase(tenantName)) {
				throw new TenantConflictException("Tenant name already exists: " + tenantName);
			}
			if (tenantRepository.existsBySchemaName(schemaName)) {
				throw new TenantConflictException("Tenant schema already exists: " + schemaName);
			}

			Tenant tenant = new Tenant();
			tenant.setTenantId(tenantId);
			tenant.setTenantName(tenantName);
			tenant.setSchemaName(schemaName);
			tenant.setStatus(TenantStatus.PENDING);
			tenant.setProvisioningStatus(ProvisioningStatus.PENDING);
			try {
				return tenantRepository.saveAndFlush(tenant);
			}
			catch (DataIntegrityViolationException ex) {
				throw new TenantConflictException("Tenant, tenant name, or schema already exists");
			}
		});
	}

	private Tenant markProvisioning(String tenantId) {
		return transactionTemplate.execute(status -> {
			Tenant tenant = findTenant(tenantId);
			if (tenant.getStatus() == TenantStatus.ACTIVE && tenant.getProvisioningStatus() == ProvisioningStatus.ACTIVE) {
				return tenant;
			}
			if (tenant.getStatus() == TenantStatus.SUSPENDED) {
				throw new TenantAccessException("Suspended tenants cannot be provisioned: " + tenant.getTenantId());
			}
			tenant.setStatus(TenantStatus.PROVISIONING);
			tenant.setProvisioningStatus(ProvisioningStatus.PROVISIONING);
			tenant.setFailureMessage(null);
			return tenantRepository.saveAndFlush(tenant);
		});
	}

	private Tenant markActive(String tenantId) {
		return transactionTemplate.execute(status -> {
			Tenant tenant = findTenant(tenantId);
			tenant.setStatus(TenantStatus.ACTIVE);
			tenant.setProvisioningStatus(ProvisioningStatus.ACTIVE);
			tenant.setFailureMessage(null);
			return tenantRepository.saveAndFlush(tenant);
		});
	}

	private void markFailed(String tenantId, String message) {
		transactionTemplate.executeWithoutResult(status -> {
			Tenant tenant = findTenant(tenantId);
			tenant.setStatus(TenantStatus.FAILED);
			tenant.setProvisioningStatus(ProvisioningStatus.FAILED);
			tenant.setFailureMessage(trimFailureMessage(message));
			tenantRepository.saveAndFlush(tenant);
		});
	}

	private Tenant findTenant(String tenantId) {
		String normalizedTenantId = TenantIdentifier.normalize(tenantId);
		return tenantRepository.findById(normalizedTenantId)
			.orElseThrow(() -> new TenantNotFoundException("Tenant does not exist: " + normalizedTenantId));
	}

	private void createSchema(String schemaName) {
		try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
			connection.setSchema(defaultTenant());
			statement.execute("create schema if not exists " + schemaName);
		}
		catch (SQLException ex) {
			throw new IllegalStateException("Unable to create tenant schema: " + schemaName, ex);
		}
	}

	private void runTenantMigrations(String schemaName) {
		Flyway.configure()
			.dataSource(dataSource)
			.schemas(schemaName)
			.defaultSchema(schemaName)
			.createSchemas(false)
			.locations(properties.getTenantMigrationLocation())
			.placeholders(java.util.Map.of("schemaName", schemaName))
			.load()
			.migrate();
	}

	private String normalizeTenantName(String tenantName) {
		String normalized = tenantName == null ? "" : tenantName.trim().replaceAll("\\s+", " ");
		if (normalized.length() < 2) {
			throw new IllegalArgumentException("Tenant name must contain at least 2 characters");
		}
		return normalized;
	}

	private String generateTenantId(String tenantName) {
		String generated = tenantName.toLowerCase(Locale.ROOT)
			.replaceAll("[^a-z0-9]+", "_")
			.replaceAll("_+", "_")
			.replaceAll("^_|_$", "");
		return TenantIdentifier.normalize(generated);
	}

	private String defaultTenant() {
		return TenantIdentifier.normalize(properties.getDefaultTenant());
	}

	private String trimFailureMessage(String message) {
		if (message == null) {
			return "Unknown provisioning failure";
		}
		return message.length() <= 1000 ? message : message.substring(0, 1000);
	}
}
