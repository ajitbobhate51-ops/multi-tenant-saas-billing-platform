package com.multitenant.tenant;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.security")
public class SecurityProperties {

	private String jwtSecret;

	private String jwtIssuer = "multitenant";

	private long jwtExpirationMinutes = 60;

	private String platformAdminToken;

	private String bootstrapToken;

	public String getJwtSecret() {
		return jwtSecret;
	}

	public void setJwtSecret(String jwtSecret) {
		this.jwtSecret = jwtSecret;
	}

	public String getJwtIssuer() {
		return jwtIssuer;
	}

	public void setJwtIssuer(String jwtIssuer) {
		this.jwtIssuer = jwtIssuer;
	}

	public long getJwtExpirationMinutes() {
		return jwtExpirationMinutes;
	}

	public void setJwtExpirationMinutes(long jwtExpirationMinutes) {
		this.jwtExpirationMinutes = jwtExpirationMinutes;
	}

	public String getPlatformAdminToken() {
		return platformAdminToken;
	}

	public void setPlatformAdminToken(String platformAdminToken) {
		this.platformAdminToken = platformAdminToken;
	}

	public String getBootstrapToken() {
		return bootstrapToken;
	}

	public void setBootstrapToken(String bootstrapToken) {
		this.bootstrapToken = bootstrapToken;
	}

	public Duration jwtExpiration() {
		return Duration.ofMinutes(jwtExpirationMinutes);
	}

	public SecretKey jwtSigningKey() {
		String secret = requireConfigured(jwtSecret, "JWT_SECRET");
		if (secret.getBytes(StandardCharsets.UTF_8).length < 32) {
			throw new IllegalStateException("JWT_SECRET must be at least 32 bytes for HS256 signing");
		}
		return new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
	}

	public String requiredPlatformAdminToken() {
		return requireConfigured(platformAdminToken, "PLATFORM_ADMIN_TOKEN");
	}

	public String requiredBootstrapToken() {
		return requireConfigured(bootstrapToken, "TENANT_BOOTSTRAP_TOKEN");
	}

	private String requireConfigured(String value, String name) {
		if (value == null || value.isBlank()) {
			throw new IllegalStateException(name + " must be configured");
		}
		return value;
	}
}
