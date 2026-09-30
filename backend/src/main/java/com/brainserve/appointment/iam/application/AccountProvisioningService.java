package com.brainserve.appointment.iam.application;

import com.brainserve.appointment.audit.api.AuditService;
import com.brainserve.appointment.iam.api.EmailService;
import com.brainserve.appointment.iam.domain.AccountStatus;
import com.brainserve.appointment.iam.domain.SystemRole;
import com.brainserve.appointment.iam.domain.UserAccount;
import com.brainserve.appointment.iam.infrastructure.UserAccountRepository;
import com.brainserve.appointment.shared.application.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class AccountProvisioningService {
    private static final Set<SystemRole> SYSTEM_ADMIN_CREATED_ROLES =
            EnumSet.of(SystemRole.ROLE_CEO, SystemRole.ROLE_HR_ADMIN, SystemRole.ROLE_MANAGER);
    private static final Set<SystemRole> LOWER_ROLES =
            EnumSet.of(SystemRole.ROLE_EMPLOYEE, SystemRole.ROLE_RECEPTIONIST, SystemRole.ROLE_SECURITY);
    private static final Set<SystemRole> SYSTEM_ADMIN_APPROVED_ROLES =
            EnumSet.of(SystemRole.ROLE_CEO);
    private static final Set<SystemRole> CEO_APPROVED_ROLES =
            EnumSet.of(SystemRole.ROLE_HR_ADMIN, SystemRole.ROLE_MANAGER);
    private static final Set<AccountStatus> GOVERNING_CEO_STATUSES =
            EnumSet.of(AccountStatus.ACTIVE, AccountStatus.PENDING_APPROVAL);
    private static final String TEMPORARY_PASSWORD_ALPHABET =
            "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";

    private final UserAccountRepository users;
    private final PasswordEncoder encoder;
    private final CompanyEmailPolicy emailPolicy;
    private final EmailService emailService;
    private final AuditService audit;
    private final SecureRandom random = new SecureRandom();

    public AccountProvisioningService(UserAccountRepository users, PasswordEncoder encoder,
                                      CompanyEmailPolicy emailPolicy, EmailService emailService,
                                      AuditService audit) {
        this.users = users;
        this.encoder = encoder;
        this.emailPolicy = emailPolicy;
        this.emailService = emailService;
        this.audit = audit;
    }

    @Transactional
    public UserAccount createPrivileged(UUID actorId, String fullName, String email, SystemRole role) {
        UserAccount actor = requireActiveActor(actorId, SystemRole.ROLE_SYSTEM_ADMIN);
        requireAllowedRole(role, SYSTEM_ADMIN_CREATED_ROLES,
                "Only CEO, HR Admin or Manager accounts can be created here");
        if (role == SystemRole.ROLE_CEO) {
            requireCeoSlotAvailable(null);
        } else {
            requireSingleActiveCeo();
        }
        String normalizedEmail = requireAvailableCompanyEmail(email);
        String temporaryPassword = generateTemporaryPassword();
        UserAccount account = users.save(new UserAccount(normalizedEmail, requireFullName(fullName), null,
                encoder.encode(temporaryPassword), true, AccountStatus.PENDING_APPROVAL, Set.of(role), actor));
        audit.record("PRIVILEGED_ACCOUNT_CREATED", "USER_ACCOUNT", account.getId().toString(), roleDetails(role));
        emailService.sendPendingAccountCreated(account.getEmail(), account.getFullName(), role.name(),
                temporaryPassword, role == SystemRole.ROLE_CEO ? "the System Admin" : "the company CEO");
        return account;
    }

    @Transactional
    public UserAccount register(String fullName, String email, String password, SystemRole role) {
        Set<SystemRole> registrableRoles = EnumSet.copyOf(CEO_APPROVED_ROLES);
        registrableRoles.addAll(LOWER_ROLES);
        requireAllowedRole(role, registrableRoles,
                "CEO is a singleton company role created only by System Admin. Self-registration is available for HR Admin, Manager, Employee, Receptionist or Security roles");
        if (CEO_APPROVED_ROLES.contains(role)) requireSingleActiveCeo();
        validatePassword(password);
        String normalizedEmail = requireAvailableCompanyEmail(email);
        UserAccount account = users.save(new UserAccount(normalizedEmail, requireFullName(fullName), null,
                encoder.encode(password), false, pendingStatusFor(role), Set.of(role), null));
        audit.record("ACCOUNT_SELF_REGISTERED", "USER_ACCOUNT", account.getId().toString(), roleDetails(role));
        return account;
    }

    @Transactional
    public UserAccount createForHrApproval(UUID actorId, String fullName, String email, String password,
                                           SystemRole role, UUID employeeId) {
        UserAccount actor = require(actorId);
        if (!actor.isEnabled() || (!actor.getRoles().contains(SystemRole.ROLE_HR_ADMIN)
                && !actor.getRoles().contains(SystemRole.ROLE_CEO))) {
            throw new BusinessException("ACCOUNT_CREATION_FORBIDDEN",
                    "Only an active CEO or HR Admin can create this account", HttpStatus.FORBIDDEN);
        }
        requireAllowedRole(role, LOWER_ROLES,
                "Only Employee, Receptionist or Security accounts can enter HR Admin approval");
        validatePassword(password);
        String normalizedEmail = requireAvailableCompanyEmail(email);
        UserAccount account = users.save(new UserAccount(normalizedEmail, requireFullName(fullName), employeeId,
                encoder.encode(password), false, AccountStatus.PENDING_HR_APPROVAL, Set.of(role), actor));
        audit.record("ACCOUNT_CREATED_FOR_HR_APPROVAL", "USER_ACCOUNT", account.getId().toString(), roleDetails(role));
        emailService.sendPendingAccountCreated(account.getEmail(), account.getFullName(), role.name(),
                password, "an HR Admin");
        return account;
    }

    @Transactional(readOnly = true)
    public List<UserAccount> pendingSystemAdminApproval(UUID actorId) {
        requireActiveActor(actorId, SystemRole.ROLE_SYSTEM_ADMIN);
        return pending(AccountStatus.PENDING_APPROVAL, SYSTEM_ADMIN_APPROVED_ROLES);
    }

    @Transactional
    public UserAccount approveBySystemAdmin(UUID actorId, UUID targetId) {
        requireCeoSlotAvailable(targetId);
        return approve(actorId, targetId, SystemRole.ROLE_SYSTEM_ADMIN,
                AccountStatus.PENDING_APPROVAL, SYSTEM_ADMIN_APPROVED_ROLES, "SYSTEM_ADMIN_ACCOUNT_APPROVED");
    }

    @Transactional
    public UserAccount rejectBySystemAdmin(UUID actorId, UUID targetId, String reason) {
        return reject(actorId, targetId, SystemRole.ROLE_SYSTEM_ADMIN,
                AccountStatus.PENDING_APPROVAL, SYSTEM_ADMIN_APPROVED_ROLES, reason, "SYSTEM_ADMIN_ACCOUNT_REJECTED");
    }

    @Transactional(readOnly = true)
    public List<UserAccount> pendingCeoApproval(UUID actorId) {
        requireActiveActor(actorId, SystemRole.ROLE_CEO);
        return pending(AccountStatus.PENDING_APPROVAL, CEO_APPROVED_ROLES);
    }

    @Transactional
    public UserAccount approveByCeo(UUID actorId, UUID targetId) {
        return approve(actorId, targetId, SystemRole.ROLE_CEO,
                AccountStatus.PENDING_APPROVAL, CEO_APPROVED_ROLES, "CEO_HR_ACCOUNT_APPROVED");
    }

    @Transactional
    public UserAccount rejectByCeo(UUID actorId, UUID targetId, String reason) {
        return reject(actorId, targetId, SystemRole.ROLE_CEO,
                AccountStatus.PENDING_APPROVAL, CEO_APPROVED_ROLES, reason, "CEO_HR_ACCOUNT_REJECTED");
    }

    @Transactional(readOnly = true)
    public List<UserAccount> pendingHrApproval(UUID actorId) {
        requireActiveActor(actorId, SystemRole.ROLE_HR_ADMIN);
        return pending(AccountStatus.PENDING_HR_APPROVAL, LOWER_ROLES);
    }

    @Transactional
    public UserAccount approveByHr(UUID actorId, UUID targetId) {
        UserAccount target = require(targetId);
        if (target.getRoles().equals(Set.of(SystemRole.ROLE_EMPLOYEE)) && target.getEmployeeId() == null) {
            throw new BusinessException("EMPLOYEE_DEPARTMENT_ASSIGNMENT_REQUIRED",
                    "Select a department, designation and joining date before approving the Employee",
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }
        return approve(actorId, targetId, SystemRole.ROLE_HR_ADMIN,
                AccountStatus.PENDING_HR_APPROVAL, LOWER_ROLES, "HR_STAFF_ACCOUNT_APPROVED");
    }

    @Transactional
    public UserAccount rejectByHr(UUID actorId, UUID targetId, String reason) {
        return reject(actorId, targetId, SystemRole.ROLE_HR_ADMIN,
                AccountStatus.PENDING_HR_APPROVAL, LOWER_ROLES, reason, "HR_STAFF_ACCOUNT_REJECTED");
    }

    private UserAccount approve(UUID actorId, UUID targetId, SystemRole actorRole, AccountStatus expectedStatus,
                                Set<SystemRole> allowedRoles, String eventType) {
        UserAccount actor = requireActiveActor(actorId, actorRole);
        UserAccount target = requireTarget(targetId, expectedStatus, allowedRoles);
        target.approve(actor);
        SystemRole role = onlyRole(target);
        audit.record(eventType, "USER_ACCOUNT", targetId.toString(), roleDetails(role));
        emailService.sendAccountApproved(target.getEmail(), target.getFullName(), role.name(), actor.getEmail());
        return target;
    }
    private UserAccount reject(UUID actorId, UUID targetId, SystemRole actorRole, AccountStatus expectedStatus,
                               Set<SystemRole> allowedRoles, String reason, String eventType) {
        UserAccount actor = requireActiveActor(actorId, actorRole);
        UserAccount target = requireTarget(targetId, expectedStatus, allowedRoles);
        SystemRole role = onlyRole(target);
        String normalizedReason = normalizeReason(reason);
        target.reject(actor);
        audit.record(eventType, "USER_ACCOUNT", targetId.toString(), roleDetails(role));
        emailService.sendAccountRejected(target.getEmail(), target.getFullName(), role.name(), normalizedReason,
                actor.getEmail());
        return target;
    }

    private List<UserAccount> pending(AccountStatus status, Set<SystemRole> roles) {
        return users.findAllByStatusOrderByCreatedAtAsc(status).stream()
                .filter(account -> account.getRoles().size() == 1 && roles.contains(onlyRole(account)))
                .toList();
    }

    private UserAccount requireTarget(UUID targetId, AccountStatus expectedStatus, Set<SystemRole> allowedRoles) {
        UserAccount target = require(targetId);
        if (target.getStatus() != expectedStatus) {
            throw new BusinessException("INVALID_ACCOUNT_STATUS",
                    "Account must be in " + expectedStatus + " status for this action", HttpStatus.CONFLICT);
        }
        SystemRole role = onlyRole(target);
        requireAllowedRole(role, allowedRoles, "Account role is invalid for this approval endpoint");
        return target;
    }

    private UserAccount requireActiveActor(UUID actorId, SystemRole requiredRole) {
        UserAccount actor = require(actorId);
        if (!actor.isEnabled() || !actor.getRoles().contains(requiredRole)) {
            throw new BusinessException("APPROVAL_FORBIDDEN",
                    "An active " + requiredRole.name() + " account is required", HttpStatus.FORBIDDEN);
        }
        if (requiredRole == SystemRole.ROLE_CEO) {
            List<UserAccount> chiefExecutives =
                    users.findDistinctByRolesContainingAndStatusAndEnabledTrueAndArchivedFalse(
                            SystemRole.ROLE_CEO, AccountStatus.ACTIVE);
            if (chiefExecutives.size() != 1 || !chiefExecutives.getFirst().getId().equals(actorId)) {
                throw new BusinessException("CEO_GOVERNANCE_CONFLICT",
                        "BrainServe Connect requires exactly one active company CEO",
                        HttpStatus.CONFLICT);
            }
        }
        return actor;
    }

    @Transactional
    public CeoSlotView ceoSlot(UUID actorId) {
        requireActiveActor(actorId, SystemRole.ROLE_SYSTEM_ADMIN);
        List<UserAccount> accounts = users.findGoverningRoleAccountsForUpdate(
                SystemRole.ROLE_CEO, GOVERNING_CEO_STATUSES);
        if (accounts.size() > 1) {
            throw new BusinessException("CEO_GOVERNANCE_CONFLICT",
                    "Multiple governing CEO accounts require database reconciliation",
                    HttpStatus.CONFLICT);
        }
        UserAccount account = accounts.isEmpty() ? null : accounts.getFirst();
        return new CeoSlotView(account == null, account == null ? null : account.getId(),
                account == null ? null : account.getFullName(),
                account == null ? null : account.getEmail(),
                account == null ? null : account.getStatus());
    }

    private void requireCeoSlotAvailable(UUID allowedPendingCeoId) {
        List<UserAccount> accounts = users.findGoverningRoleAccountsForUpdate(
                SystemRole.ROLE_CEO, GOVERNING_CEO_STATUSES);
        boolean reservedOnlyForTarget = allowedPendingCeoId != null && accounts.size() == 1
                && accounts.getFirst().getId().equals(allowedPendingCeoId)
                && accounts.getFirst().getStatus() == AccountStatus.PENDING_APPROVAL;
        if (!accounts.isEmpty() && !reservedOnlyForTarget) {
            throw new BusinessException("CEO_ACCOUNT_ALREADY_EXISTS",
                    "BrainServe Connect already has a governing CEO account. A second CEO cannot be created or activated",
                    HttpStatus.CONFLICT);
        }
    }

    private UserAccount requireSingleActiveCeo() {
        List<UserAccount> accounts =
                users.findDistinctByRolesContainingAndStatusAndEnabledTrueAndArchivedFalse(
                        SystemRole.ROLE_CEO, AccountStatus.ACTIVE);
        if (accounts.size() != 1) {
            throw new BusinessException("CEO_ACCOUNT_REQUIRED",
                    accounts.isEmpty()
                            ? "Create and activate the company CEO before requesting HR Admin or Manager access"
                            : "Multiple active CEO accounts require database reconciliation",
                    HttpStatus.CONFLICT);
        }
        return accounts.getFirst();
    }

    private UserAccount require(UUID id) {
        return users.findById(id).orElseThrow(() -> new BusinessException("USER_NOT_FOUND",
                "User account was not found", HttpStatus.NOT_FOUND));
    }

    private String requireAvailableCompanyEmail(String email) {
        String companyEmail = emailPolicy.requireCompanyEmail(email);
        if (users.existsByEmailIgnoreCase(companyEmail)) {
            throw new BusinessException("ACCOUNT_EMAIL_EXISTS",
                    "A login account already uses this email", HttpStatus.CONFLICT);
        }
        return companyEmail;
    }

    private AccountStatus pendingStatusFor(SystemRole role) {
        return LOWER_ROLES.contains(role) ? AccountStatus.PENDING_HR_APPROVAL : AccountStatus.PENDING_APPROVAL;
    }

    private String requireFullName(String fullName) {
        String normalized = fullName == null ? "" : fullName.trim().replaceAll("\\s+", " ");
        if (normalized.length() < 2 || normalized.length() > 170) {
            throw new BusinessException("INVALID_FULL_NAME", "Full name must contain 2-170 characters",
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }
        return normalized;
    }

    private void requireAllowedRole(SystemRole role, Set<SystemRole> allowedRoles, String message) {
        if (role == null || !allowedRoles.contains(role)) {
            throw new BusinessException("INVALID_ROLE", message, HttpStatus.UNPROCESSABLE_ENTITY);
        }
    }

    private SystemRole onlyRole(UserAccount account) {
        if (account.getRoles().size() != 1) {
            throw new BusinessException("INVALID_ROLE", "Account must have exactly one provisioning role",
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }
        return account.getRoles().iterator().next();
    }

    private void validatePassword(String password) {
        boolean strong = password != null && password.length() >= 12 && password.length() <= 64
                && password.chars().anyMatch(Character::isUpperCase)
                && password.chars().anyMatch(Character::isLowerCase)
                && password.chars().anyMatch(Character::isDigit)
                && password.chars().anyMatch(character -> !Character.isLetterOrDigit(character))
                && password.chars().noneMatch(Character::isWhitespace);
        if (!strong) {
            throw new BusinessException("WEAK_PASSWORD",
                    "Password must be 12-64 characters and include uppercase, lowercase, number and special characters without spaces",
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }
    }

    private String generateTemporaryPassword() {
        StringBuilder password = new StringBuilder("Bs!").append(random.nextInt(10));
        while (password.length() < 18) {
            password.append(TEMPORARY_PASSWORD_ALPHABET.charAt(random.nextInt(TEMPORARY_PASSWORD_ALPHABET.length())));
        }
        return password.toString();
    }

    private String normalizeReason(String reason) {
        if (reason == null) return "";
        String normalized = reason.trim();
        if (normalized.length() > 500) {
            throw new BusinessException("REJECTION_REASON_TOO_LONG",
                    "Rejection reason cannot exceed 500 characters", HttpStatus.UNPROCESSABLE_ENTITY);
        }
        return normalized;
    }

    private String roleDetails(SystemRole role) {
        return "{\"role\":\"" + role.name() + "\"}";
    }

    public record CeoSlotView(boolean available, UUID userId, String fullName, String email,
                              AccountStatus status) {}

}
