package com.multitenant;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;

import com.multitenant.tenant.TenantContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class BootstrapRuntimeSecurityTests {

	private static final String PLATFORM_ADMIN_TOKEN = "test-platform-admin-token";

	private static final String BOOTSTRAP_TOKEN = "test-bootstrap-token";

	private final HttpClient httpClient = HttpClient.newHttpClient();

	@LocalServerPort
	private int port;

	@Autowired
	private JwtEncoder jwtEncoder;

	@BeforeEach
	void clearTenantContext() {
		TenantContext.clear();
	}

	@Test
	void bootstrapWithoutJwtOrPlatformAdminTokenReachesBootstrapFlow() throws Exception {
		registerTenant("tenant_runtime_bootstrap");

		HttpResponse<String> response = send(post("/api/tenants/tenant_runtime_bootstrap/users/bootstrap")
				.header("X-Bootstrap-Token", BOOTSTRAP_TOKEN)
				.header(HttpHeaders.CONTENT_TYPE, "application/json")
				.POST(HttpRequest.BodyPublishers.ofString("""
						{"email":"admin@runtime-bootstrap.test","password":"change-me-123"}
						""")));

		assertThat(response.statusCode()).isEqualTo(201);
		assertThat(response.body()).contains("admin@runtime-bootstrap.test");
		assertThat(response.body()).doesNotContain("UNAUTHENTICATED");
	}

	@Test
	void bootstrapWithoutValidBootstrapTokenIsRejectedByBootstrapFlow() throws Exception {
		registerTenant("tenant_runtime_wrong_bootstrap");

		HttpResponse<String> missing = send(post("/api/tenants/tenant_runtime_wrong_bootstrap/users/bootstrap")
				.header(HttpHeaders.CONTENT_TYPE, "application/json")
				.POST(HttpRequest.BodyPublishers.ofString("""
						{"email":"admin@missing-bootstrap.test","password":"change-me-123"}
						""")));
		HttpResponse<String> wrong = send(post("/api/tenants/tenant_runtime_wrong_bootstrap/users/bootstrap")
				.header("X-Bootstrap-Token", "wrong")
				.header(HttpHeaders.CONTENT_TYPE, "application/json")
				.POST(HttpRequest.BodyPublishers.ofString("""
						{"email":"admin@wrong-bootstrap.test","password":"change-me-123"}
						""")));

		assertThat(missing.statusCode()).isEqualTo(403);
		assertThat(missing.body()).contains("TENANT_ACCESS_DENIED");
		assertThat(wrong.statusCode()).isEqualTo(403);
		assertThat(wrong.body()).contains("TENANT_ACCESS_DENIED");
	}

	@Test
	void protectedTenantAdminEndpointsStillRequirePlatformAdminToken() throws Exception {
		HttpResponse<String> response = send(get("/api/tenants"));

		assertThat(response.statusCode()).isEqualTo(401);
		assertThat(response.body()).contains("UNAUTHENTICATED");
	}

	@Test
	void protectedCustomerEndpointsStillRequireJwt() throws Exception {
		HttpResponse<String> response = send(get("/customers"));

		assertThat(response.statusCode()).isEqualTo(401);
		assertThat(response.body()).contains("UNAUTHENTICATED");
	}

	@Test
	void tenantJwtCannotAccessAnotherTenantOverHttp() throws Exception {
		registerTenant("tenant_runtime_cross_a");
		registerTenant("tenant_runtime_cross_b");
		String token = token("tenant_runtime_cross_a", "admin@cross-a.test");

		HttpResponse<String> response = send(get("/customers")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.header("X-Tenant-ID", "tenant_runtime_cross_b"));

		assertThat(response.statusCode()).isEqualTo(403);
		assertThat(response.body()).contains("TENANT_ACCESS_DENIED");
	}

	private void registerTenant(String tenantId) throws Exception {
		HttpResponse<String> response = send(post("/api/tenants")
				.header("X-Platform-Admin-Token", PLATFORM_ADMIN_TOKEN)
				.header(HttpHeaders.CONTENT_TYPE, "application/json")
				.POST(HttpRequest.BodyPublishers.ofString("""
						{"tenantId":"%s","tenantName":"%s","schemaName":"%s"}
						""".formatted(tenantId, tenantId, tenantId))));
		assertThat(response.statusCode()).isEqualTo(201);
	}

	private HttpResponse<String> send(HttpRequest.Builder builder) throws Exception {
		return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
	}

	private HttpRequest.Builder get(String path) {
		return HttpRequest.newBuilder(uri(path)).GET();
	}

	private HttpRequest.Builder post(String path) {
		return HttpRequest.newBuilder(uri(path));
	}

	private URI uri(String path) {
		return URI.create("http://localhost:" + port + path);
	}

	private String token(String tenantId, String email) {
		Instant now = Instant.now();
		JwtClaimsSet claims = JwtClaimsSet.builder()
				.issuer("multitenant-test")
				.issuedAt(now)
				.expiresAt(now.plusSeconds(3600))
				.subject(email)
				.claim("tenant_id", tenantId)
				.claim("role", "TENANT_ADMIN")
				.build();
		return jwtEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
				.getTokenValue();
	}
}