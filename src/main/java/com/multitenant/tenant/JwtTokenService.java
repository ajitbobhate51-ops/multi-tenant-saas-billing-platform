package com.multitenant.tenant;

import java.time.Instant;
import java.util.List;

import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

@Service
public class JwtTokenService {

	private final JwtEncoder jwtEncoder;

	private final SecurityProperties properties;

	public JwtTokenService(JwtEncoder jwtEncoder, SecurityProperties properties) {
		this.jwtEncoder = jwtEncoder;
		this.properties = properties;
	}

	public LoginResponse issueToken(String tenantId, TenantUser user) {
		Instant issuedAt = Instant.now();
		Instant expiresAt = issuedAt.plus(properties.jwtExpiration());
		JwtClaimsSet claims = JwtClaimsSet.builder()
				.issuer(properties.getJwtIssuer())
				.issuedAt(issuedAt)
				.expiresAt(expiresAt)
				.subject(user.getEmail())
				.claim("tenant_id", tenantId)
				.claim("role", user.getRole())
				.claim("authorities", List.of(user.getRole()))
				.build();
		JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
		String token = jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
		return new LoginResponse("Bearer", token, expiresAt, tenantId, user.getEmail(), user.getRole());
	}
}
