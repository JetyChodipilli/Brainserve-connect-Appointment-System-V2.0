package com.brainserve.appointment.iam.application;

import com.brainserve.appointment.iam.domain.SystemRole;
import com.brainserve.appointment.iam.domain.UserAccount;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashSet;
import java.util.Set;

@Service
public class JwtService {
    private final JwtEncoder encoder;
    private final long accessTokenMinutes;

    public JwtService(JwtEncoder encoder, @Value("${brainserve.security.access-token-minutes}") long accessTokenMinutes) {
        this.encoder = encoder;
        this.accessTokenMinutes = accessTokenMinutes;
    }

    public AccessToken issue(UserAccount user) {
        Instant issuedAt = Instant.now();
        Instant expiresAt = issuedAt.plus(accessTokenMinutes, ChronoUnit.MINUTES);
        Set<String> authorities = new LinkedHashSet<>();
        user.getRoles().forEach(role -> {
            authorities.add(role.name());
        });
        user.effectivePermissions().forEach(permission -> authorities.add(permission.name()));
        JwtClaimsSet.Builder builder = JwtClaimsSet.builder()
                .issuer("brainserve-appointment-service")
                .subject(user.getId().toString())
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .claim("email", user.getEmail())
                .claim("authorities", authorities)
                .claim("forcePasswordChange", user.isForcePasswordChange());
        if (user.getEmployeeId() != null) builder.claim("employeeId", user.getEmployeeId().toString());
        JwtClaimsSet claims = builder.build();
        String token = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
        return new AccessToken(token, expiresAt);
    }

    public record AccessToken(String value, Instant expiresAt) {}
}
