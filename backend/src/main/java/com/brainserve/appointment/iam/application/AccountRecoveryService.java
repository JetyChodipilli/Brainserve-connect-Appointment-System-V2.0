package com.brainserve.appointment.iam.application;

import com.brainserve.appointment.audit.api.AuditService;
import com.brainserve.appointment.iam.api.EmailService;
import com.brainserve.appointment.iam.domain.AccountRecoveryRequest;
import com.brainserve.appointment.iam.domain.AccountRecoveryStatus;
import com.brainserve.appointment.iam.domain.AccountRecoveryType;
import com.brainserve.appointment.iam.domain.SystemRole;
import com.brainserve.appointment.iam.domain.UserAccount;
import com.brainserve.appointment.iam.infrastructure.AccountRecoveryRequestRepository;
import com.brainserve.appointment.iam.infrastructure.RefreshTokenSessionRepository;
import com.brainserve.appointment.iam.infrastructure.UserAccountRepository;
import com.brainserve.appointment.shared.application.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
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
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class AccountRecoveryService {
    private static final String CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final Logger log = LoggerFactory.getLogger(AccountRecoveryService.class);

    private final AccountRecoveryRequestRepository requests;
    private final UserAccountRepository users;
    private final RefreshTokenSessionRepository sessions;
    private final PasswordEncoder passwordEncoder;
    private final CompanyEmailPolicy emailPolicy;
    private final EmailService emailService;
    private final AuditService audit;
    private final AccountRecoveryRequestWriter requestWriter;
    private final long codeMinutes;
    private final SecureRandom random = new SecureRandom();

    public AccountRecoveryService(AccountRecoveryRequestRepository requests, UserAccountRepository users,
                                  RefreshTokenSessionRepository sessions, PasswordEncoder passwordEncoder,
                                  CompanyEmailPolicy emailPolicy, EmailService emailService, AuditService audit,
                                  AccountRecoveryRequestWriter requestWriter,
                                  @Value("${brainserve.security.account-recovery-code-minutes:30}") long codeMinutes) {
        this.requests = requests;
        this.users = users;
        this.sessions = sessions;
        this.passwordEncoder = passwordEncoder;
        this.emailPolicy = emailPolicy;
        this.emailService = emailService;
        this.audit = audit;
        this.requestWriter = requestWriter;
        this.codeMinutes = codeMinutes;
    }

    public void request(String identifier, SystemRole role, AccountRecoveryType type) {
        resolveTarget(identifier, role).ifPresent(user -> {
            requestWriter.createIfAbsent(user.getId(), type).ifPresent(requestId -> {
                try {
                    audit.record("ACCOUNT_RECOVERY_REQUESTED", "ACCOUNT_RECOVERY", requestId.toString(),
                            "{\"type\":\"" + type.name() + "\"}");
                } catch (RuntimeException auditFailure) {
                    // The request is already committed in an independent transaction. Keep the
                    // public response privacy-safe and surface the secondary failure in server logs.
                    log.error("Recovery request {} was persisted but its audit event failed", requestId, auditFailure);
                }
            });
        });
    }

    @Transactional(readOnly = true)
    public List<AccountRecoveryRequest> pending() {
        return requests.findByStatusOrderByCreatedAtAsc(AccountRecoveryStatus.PENDING);
    }

    @Transactional
    public Approval approve(UUID actorId, UUID requestId) {
        UserAccount actor = requireSystemAdmin(actorId);
        AccountRecoveryRequest request = require(requestId);
        String code = newCode();
        Instant expiresAt = Instant.now().plus(codeMinutes, ChronoUnit.MINUTES);
        try {
            request.approve(actor, hash(normalizeCode(code)), expiresAt);
        } catch (IllegalStateException ex) {
            throw wrongStatus();
        }
        audit.record("ACCOUNT_RECOVERY_APPROVED", "ACCOUNT_RECOVERY", requestId.toString(),
                "{\"type\":\"" + request.getType().name() + "\"}");
        return new Approval(request, code);
    }

    @Transactional
    public AccountRecoveryRequest reject(UUID actorId, UUID requestId, String reason) {
        UserAccount actor = requireSystemAdmin(actorId);
        AccountRecoveryRequest request = require(requestId);
        try {
            request.reject(actor, reason);
        } catch (IllegalStateException ex) {
            throw wrongStatus();
        }
        audit.record("ACCOUNT_RECOVERY_REJECTED", "ACCOUNT_RECOVERY", requestId.toString(),
                "{\"type\":\"" + request.getType().name() + "\"}");
        return request;
    }

    @Transactional
    public void recoverPassword(String code, String newPassword, String confirmation) {
        AccountRecoveryRequest request = requireUsable(code, AccountRecoveryType.PASSWORD);
        if (!newPassword.equals(confirmation)) {
            throw new BusinessException("PASSWORD_CONFIRMATION_MISMATCH", "Password and confirmation do not match",
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }
        validatePassword(newPassword);
        UserAccount user = request.getUser();
        if (passwordEncoder.matches(newPassword, user.getPasswordHash())) {
            throw new BusinessException("PASSWORD_REUSE", "New password must differ from the current password",
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }
        Instant changedAt = Instant.now();
        user.recoverPassword(passwordEncoder.encode(newPassword));
        request.use();
        sessions.revokeAllForUser(user.getId(), changedAt);
        audit.record("ACCOUNT_PASSWORD_RECOVERED", "USER_ACCOUNT", user.getId().toString(),
                "{\"requestId\":\"" + request.getId() + "\"}");
        emailService.sendPasswordChangedConfirmation(user.getEmail(), user.getFullName(), changedAt);
    }

    @Transactional
    public void recoverEmail(String code, String newEmail, String confirmation) {
        AccountRecoveryRequest request = requireUsable(code, AccountRecoveryType.EMAIL);
        String normalized = emailPolicy.requireCompanyEmail(newEmail);
        String normalizedConfirmation = emailPolicy.requireCompanyEmail(confirmation);
        if (!normalized.equals(normalizedConfirmation)) {
            throw new BusinessException("EMAIL_CONFIRMATION_MISMATCH", "Email and confirmation do not match",
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }
        UserAccount user = request.getUser();
        if (users.existsByEmailIgnoreCaseAndIdNot(normalized, user.getId())) {
            throw new BusinessException("ACCOUNT_EMAIL_EXISTS", "A login account already uses this email",
                    HttpStatus.CONFLICT);
        }
        Instant changedAt = Instant.now();
        user.changeEmail(normalized);
        request.use();
        sessions.revokeAllForUser(user.getId(), changedAt);
        audit.record("ACCOUNT_EMAIL_RECOVERED", "USER_ACCOUNT", user.getId().toString(),
                "{\"requestId\":\"" + request.getId() + "\"}");
        emailService.sendEmailRecoveryConfirmation(normalized, user.getFullName(), changedAt);
    }

    private java.util.Optional<UserAccount> resolveTarget(String identifier, SystemRole role) {
        if (identifier == null || role == null || role == SystemRole.ROLE_SYSTEM_ADMIN) return java.util.Optional.empty();
        String normalized = identifier.trim();
        if (normalized.contains("@")) {
            // Email is the unique account identifier. Do not discard a valid request when the
            // requester selects a stale role after promotion/demotion; the System Admin still
            // verifies the account before issuing a one-time code.
            return users.findByEmailIgnoreCase(normalized)
                    .filter(UserAccount::isEnabled)
                    .filter(user -> !user.getRoles().contains(SystemRole.ROLE_SYSTEM_ADMIN));
        }
        List<UserAccount> matches = users.findAllByFullNameIgnoreCase(normalized).stream()
                .filter(UserAccount::isEnabled)
                // A name is not unique, so use the selected role to disambiguate safely.
                .filter(user -> user.getRoles().contains(role))
                .toList();
        return matches.size() == 1 ? java.util.Optional.of(matches.get(0)) : java.util.Optional.empty();
    }

    private AccountRecoveryRequest requireUsable(String rawCode, AccountRecoveryType expectedType) {
        String normalized = normalizeCode(rawCode);
        if (!normalized.matches("BSR-[A-Z2-9]{4}-[A-Z2-9]{4}-[A-Z2-9]{4}")) throw invalidCode();
        AccountRecoveryRequest request = requests.findByCodeHash(hash(normalized)).orElseThrow(this::invalidCode);
        if (request.getType() != expectedType || !request.isUsableAt(Instant.now()) || !request.getUser().isEnabled()) {
            throw invalidCode();
        }
        return request;
    }

    private UserAccount requireSystemAdmin(UUID actorId) {
        return users.findById(actorId)
                .filter(UserAccount::isEnabled)
                .filter(user -> user.getRoles().contains(SystemRole.ROLE_SYSTEM_ADMIN))
                .orElseThrow(() -> new BusinessException("SYSTEM_ADMIN_REQUIRED",
                        "Only the active System Admin can decide account recovery requests", HttpStatus.FORBIDDEN));
    }

    private AccountRecoveryRequest require(UUID id) {
        return requests.findDetailedById(id).orElseThrow(() -> new BusinessException("RECOVERY_REQUEST_NOT_FOUND",
                "The account recovery request was not found", HttpStatus.NOT_FOUND));
    }

    private String newCode() {
        return "BSR-" + segment() + "-" + segment() + "-" + segment();
    }

    private String segment() {
        StringBuilder value = new StringBuilder(4);
        while (value.length() < 4) value.append(CODE_ALPHABET.charAt(random.nextInt(CODE_ALPHABET.length())));
        return value.toString();
    }

    private String normalizeCode(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    private String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private void validatePassword(String password) {
        boolean valid = password != null && password.length() >= 12 && password.length() <= 64
                && password.chars().anyMatch(Character::isUpperCase)
                && password.chars().anyMatch(Character::isLowerCase)
                && password.chars().anyMatch(Character::isDigit)
                && password.chars().anyMatch(character -> !Character.isLetterOrDigit(character))
                && password.chars().noneMatch(Character::isWhitespace);
        if (!valid) throw new BusinessException("WEAK_PASSWORD",
                "Password must be 12-64 characters and include uppercase, lowercase, number and special characters without spaces",
                HttpStatus.UNPROCESSABLE_ENTITY);
    }

    private BusinessException invalidCode() {
        return new BusinessException("INVALID_RECOVERY_CODE", "Recovery code is invalid, expired or already used",
                HttpStatus.UNAUTHORIZED);
    }

    private BusinessException wrongStatus() {
        return new BusinessException("RECOVERY_REQUEST_ALREADY_DECIDED",
                "Only a pending account recovery request can be decided", HttpStatus.CONFLICT);
    }

    public record Approval(AccountRecoveryRequest request, String recoveryCode) {}
}
