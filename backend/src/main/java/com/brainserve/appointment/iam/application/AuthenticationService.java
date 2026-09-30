package com.brainserve.appointment.iam.application;

import com.brainserve.appointment.iam.domain.RefreshTokenSession;
import com.brainserve.appointment.iam.domain.SystemRole;
import com.brainserve.appointment.iam.domain.UserAccount;
import com.brainserve.appointment.iam.api.EmailService;
import com.brainserve.appointment.iam.infrastructure.RefreshTokenSessionRepository;
import com.brainserve.appointment.iam.infrastructure.UserAccountRepository;
import com.brainserve.appointment.shared.application.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

@Service
public class AuthenticationService {
    private static final Logger log = LoggerFactory.getLogger(AuthenticationService.class);
    private final UserAccountRepository users;
    private final RefreshTokenSessionRepository sessions;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final CompanyEmailPolicy emailPolicy;
    private final EmailService emailService;
    private final StringRedisTemplate redis;
    private final AuthenticationSecurityStateWriter securityState;
    private final long refreshTokenDays;
    private final long passwordChangeOtpMinutes;
    private final SecureRandom secureRandom = new SecureRandom();

    public AuthenticationService(UserAccountRepository users, RefreshTokenSessionRepository sessions,
                                 PasswordEncoder passwordEncoder, JwtService jwtService,
                                 CompanyEmailPolicy emailPolicy, EmailService emailService, StringRedisTemplate redis,
                                 AuthenticationSecurityStateWriter securityState,
                                 @Value("${brainserve.security.refresh-token-days}") long refreshTokenDays,
                                 @Value("${brainserve.security.password-change-otp-minutes:10}") long passwordChangeOtpMinutes) {
        this.users = users; this.sessions = sessions; this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService; this.emailPolicy = emailPolicy; this.emailService = emailService;
        this.redis = redis; this.securityState = securityState; this.refreshTokenDays = refreshTokenDays;
        this.passwordChangeOtpMinutes = passwordChangeOtpMinutes;
    }

    @Transactional
    public TokenPair login(String email, String password) {
        UserAccount user = users.findByEmailIgnoreCase(email)
                .orElseThrow(this::invalidCredentials);
        if (!user.isEnabled()) {
            log.info("Blocked login for non-active account userId={} status={}", user.getId(), user.getStatus());
            throw invalidCredentials();
        }
        if (user.isLocked()) throw invalidCredentials();
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            securityState.recordFailedLogin(user.getId());
            throw invalidCredentials();
        }
        requireOperationalProfile(user);
        user.recordSuccessfulLogin();
        return createPair(user, UUID.randomUUID());
    }

    @Transactional(readOnly = true)
    public TokenPair refresh(String refreshToken) {
        String currentHash = hash(refreshToken);
        RefreshTokenSession current = sessions.findByTokenHash(currentHash)
                .orElseThrow(this::invalidRefreshToken);
        if (current.isRevoked()) {
            securityState.revokeRefreshTokenFamily(current.getFamilyId(), Instant.now());
            throw invalidRefreshToken();
        }
        if (!current.isUsable()) throw invalidRefreshToken();
        UserAccount user = users.findById(current.getUserId())
                .filter(UserAccount::isEnabled)
                .filter(account -> !account.isLocked())
                .orElseThrow(this::invalidRefreshToken);
        requireOperationalProfile(user);
        String nextToken = randomToken();
        String nextHash = hash(nextToken);
        var rotation = securityState.rotateRefreshToken(currentHash, nextHash, user.getId(),
                Instant.now().plus(refreshTokenDays, ChronoUnit.DAYS));
        if (rotation != AuthenticationSecurityStateWriter.RefreshRotation.ROTATED) {
            throw invalidRefreshToken();
        }
        JwtService.AccessToken access = jwtService.issue(user);
        return new TokenPair(access.value(), access.expiresAt(), nextToken, user.isForcePasswordChange());
    }

    public void logout(String refreshToken) {
        securityState.revokePresentedRefreshToken(hash(refreshToken), Instant.now());
    }

    @Transactional
    public void logoutAll(UUID userId) { sessions.revokeAllForUser(userId, Instant.now()); }

    public void requestPasswordChangeOtp(UUID userId, String currentPassword) {
        UserAccount user = users.findById(userId)
                .orElseThrow(() -> new BusinessException("USER_NOT_FOUND", "User account was not found", HttpStatus.NOT_FOUND));
        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) throw invalidCredentials();
        String otp = String.format("%06d", secureRandom.nextInt(1_000_000));
        Instant expiresAt = Instant.now().plus(passwordChangeOtpMinutes, ChronoUnit.MINUTES);
        redis.opsForValue().set(passwordChangeOtpKey(userId), hash(otp), Duration.ofMinutes(passwordChangeOtpMinutes));
        emailService.sendPasswordChangeOtp(user.getEmail(), user.getFullName(), otp, expiresAt);
    }

    @Transactional
    public void confirmPasswordChange(UUID userId, String otp, String newPassword) {
        UserAccount user = users.findById(userId)
                .orElseThrow(() -> new BusinessException("USER_NOT_FOUND", "User account was not found", HttpStatus.NOT_FOUND));
        String key = passwordChangeOtpKey(userId);
        String expectedOtpHash = redis.opsForValue().get(key);
        if (expectedOtpHash == null || !MessageDigest.isEqual(expectedOtpHash.getBytes(StandardCharsets.UTF_8),
                hash(otp).getBytes(StandardCharsets.UTF_8))) {
            throw new BusinessException("INVALID_OTP", "OTP is invalid or expired", HttpStatus.UNAUTHORIZED);
        }
        validatePasswordPolicy(newPassword);
        if (passwordEncoder.matches(newPassword, user.getPasswordHash())) {
            throw new BusinessException("PASSWORD_REUSE", "New password must differ from the current password",
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }
        Instant changedAt = Instant.now();
        user.changePassword(passwordEncoder.encode(newPassword));
        redis.delete(key);
        sessions.revokeAllForUser(userId, changedAt);
        emailService.sendPasswordChangedConfirmation(user.getEmail(), user.getFullName(), changedAt);
    }

    @Transactional
    public void changeEmail(UUID userId, String currentPassword, String newEmail) {
        UserAccount user = users.findById(userId)
                .orElseThrow(() -> new BusinessException("USER_NOT_FOUND", "User account was not found", HttpStatus.NOT_FOUND));
        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) throw invalidCredentials();
        String companyEmail = emailPolicy.requireCompanyEmail(newEmail);
        if (users.existsByEmailIgnoreCaseAndIdNot(companyEmail, userId)) {
            throw new BusinessException("ACCOUNT_EMAIL_EXISTS", "A login account already uses this email", HttpStatus.CONFLICT);
        }
        user.changeEmail(companyEmail);
        sessions.revokeAllForUser(userId, Instant.now());
    }

    private TokenPair createPair(UserAccount user, UUID familyId) {
        JwtService.AccessToken access = jwtService.issue(user);
        String refresh = randomToken();
        sessions.save(new RefreshTokenSession(user.getId(), hash(refresh), familyId,
                Instant.now().plus(refreshTokenDays, ChronoUnit.DAYS)));
        return new TokenPair(access.value(), access.expiresAt(), refresh, user.isForcePasswordChange());
    }

    private String randomToken() {
        byte[] bytes = new byte[48];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private BusinessException invalidCredentials() {
        return new BusinessException("INVALID_CREDENTIALS", "Invalid email or password", HttpStatus.UNAUTHORIZED);
    }
    private BusinessException invalidRefreshToken() {
        return new BusinessException("INVALID_REFRESH_TOKEN", "Refresh token is invalid or expired", HttpStatus.UNAUTHORIZED);
    }

    private void requireOperationalProfile(UserAccount user) {
        if (user.getRoles().contains(SystemRole.ROLE_EMPLOYEE) && user.getEmployeeId() == null) {
            throw new BusinessException("EMPLOYEE_PROFILE_ASSIGNMENT_REQUIRED",
                    "HR must assign your department and employee ID before you can sign in",
                    HttpStatus.FORBIDDEN);
        }
    }

    private String passwordChangeOtpKey(UUID userId) {
        return "iam:password-change-otp:" + userId;
    }

    private void validatePasswordPolicy(String password) {
        boolean hasUpper = password.chars().anyMatch(Character::isUpperCase);
        boolean hasLower = password.chars().anyMatch(Character::isLowerCase);
        boolean hasDigit = password.chars().anyMatch(Character::isDigit);
        boolean hasSpecial = password.chars().anyMatch(character -> !Character.isLetterOrDigit(character));
        boolean hasWhitespace = password.chars().anyMatch(Character::isWhitespace);
        if (password.length() < 12 || password.length() > 64 || !hasUpper || !hasLower || !hasDigit
                || !hasSpecial || hasWhitespace) {
            throw new BusinessException("WEAK_PASSWORD",
                    "Password must be 12-64 characters and include uppercase, lowercase, number and special characters without spaces",
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }
    }

    public record TokenPair(String accessToken, Instant accessTokenExpiresAt, String refreshToken, boolean forcePasswordChange) {}
}
