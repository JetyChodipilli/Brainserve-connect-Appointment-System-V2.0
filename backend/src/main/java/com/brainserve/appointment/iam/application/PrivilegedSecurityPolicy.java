package com.brainserve.appointment.iam.application;

import com.brainserve.appointment.iam.domain.SystemRole;
import com.brainserve.appointment.iam.domain.UserAccount;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class PrivilegedSecurityPolicy {
    private final Set<SystemRole> requiredRoles;
    private final Duration stepUpAge;
    public static final Duration ENROLLMENT_LIFETIME = Duration.ofMinutes(10);
    public static final Duration CHALLENGE_LIFETIME = Duration.ofMinutes(10);

    public PrivilegedSecurityPolicy(
            @Value("${brainserve.security.mfa-required-roles:ROLE_SYSTEM_ADMIN,ROLE_CEO,ROLE_HR_ADMIN,ROLE_MANAGER,ROLE_TEAM_LEAD}") String roles,
            @Value("${brainserve.security.mfa-step-up-seconds:300}") long stepUpSeconds) {
        requiredRoles = Arrays.stream(roles.split(",")).map(String::trim).filter(value -> !value.isEmpty())
                .map(SystemRole::valueOf).collect(Collectors.toUnmodifiableSet());
        if (requiredRoles.isEmpty()) throw new IllegalArgumentException("At least one MFA-required role is required");
        if (stepUpSeconds < 60 || stepUpSeconds > 900) {
            throw new IllegalArgumentException("MFA step-up must be between 60 and 900 seconds");
        }
        stepUpAge = Duration.ofSeconds(stepUpSeconds);
    }

    public boolean required(UserAccount account, boolean enrolled) {
        return enrolled || account.getRoles().stream().anyMatch(requiredRoles::contains);
    }
    public Duration stepUpAge() { return stepUpAge; }
    public Set<String> privilegedRoles() {
        return requiredRoles.stream().map(Enum::name).collect(Collectors.toUnmodifiableSet());
    }
}
