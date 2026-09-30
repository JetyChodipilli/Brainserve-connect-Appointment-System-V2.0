package com.brainserve.appointment.iam.application;

import com.brainserve.appointment.audit.api.AuditService;
import com.brainserve.appointment.departmenthr.api.DepartmentHrDirectory;
import com.brainserve.appointment.employee.api.EmployeeDirectory;
import com.brainserve.appointment.iam.api.EmailService;
import com.brainserve.appointment.iam.domain.AccountStatus;
import com.brainserve.appointment.iam.domain.SystemRole;
import com.brainserve.appointment.iam.domain.UserAccount;
import com.brainserve.appointment.iam.infrastructure.UserAccountRepository;
import com.brainserve.appointment.shared.application.BusinessException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
public class StaffAccountAdministrationService {

    private static final Set<SystemRole> HR_MANAGED_ROLES = EnumSet.of(
            SystemRole.ROLE_EMPLOYEE,
            SystemRole.ROLE_TEAM_LEAD,
            SystemRole.ROLE_RECEPTIONIST,
            SystemRole.ROLE_SECURITY
    );

    private final UserAccountRepository users;
    private final PasswordEncoder encoder;
    private final CompanyEmailPolicy emailPolicy;
    private final EmailService emailService;
    private final AuditService audit;
    private final DepartmentHrDirectory departmentHrs;
    private final EmployeeDirectory employees;

    public StaffAccountAdministrationService(
            UserAccountRepository users,
            PasswordEncoder encoder,
            CompanyEmailPolicy emailPolicy,
            EmailService emailService,
            AuditService audit,
            DepartmentHrDirectory departmentHrs,
            EmployeeDirectory employees
    ) {
        this.users = users;
        this.encoder = encoder;
        this.emailPolicy = emailPolicy;
        this.emailService = emailService;
        this.audit = audit;
        this.departmentHrs = departmentHrs;
        this.employees = employees;
    }

    @Transactional(readOnly = true)
    public Page<UserAccount> list(
            UUID actorId,
            String query,
            Pageable pageable
    ) {
        UserAccount actor = require(actorId);
        manageableRoles(actor);

        UUID departmentId = departmentHrs
                .requireForUser(actorId)
                .departmentId();

        String normalizedQuery = query == null || query.isBlank()
                ? ""
                : query.trim().toLowerCase(Locale.ROOT);

        return users.findHrManagedAccounts(
                actorId,
                departmentId,
                normalizedQuery,
                pageable
        );
    }

    @Transactional
    public UserAccount create(
            UUID actorId,
            String email,
            String temporaryPassword,
            SystemRole role
    ) {
        UserAccount actor = require(actorId);
        requireRoleAllowed(actor, role);
        validatePassword(temporaryPassword);

        String companyEmail = emailPolicy.requireCompanyEmail(email);

        if (users.existsByEmailIgnoreCase(companyEmail)) {
            throw new BusinessException(
                    "ACCOUNT_EMAIL_EXISTS",
                    "A login account already uses this email",
                    HttpStatus.CONFLICT
            );
        }

        String fullName = companyEmail
                .substring(0, companyEmail.indexOf('@'))
                .replace('.', ' ');

        UserAccount created = users.save(
                new UserAccount(
                        companyEmail,
                        fullName,
                        null,
                        encoder.encode(temporaryPassword),
                        true,
                        AccountStatus.PENDING_HR_APPROVAL,
                        Set.of(role),
                        actor
                )
        );

        audit.record(
                "STAFF_ACCOUNT_CREATE",
                "USER_ACCOUNT",
                created.getId().toString(),
                "{\"role\":\"" + role.name() + "\"}"
        );

        emailService.sendPendingAccountCreated(
                created.getEmail(),
                created.getFullName(),
                role.name(),
                temporaryPassword,
                "an HR Admin"
        );

        return created;
    }

    @Transactional
    public UserAccount changeEmail(
            UUID actorId,
            UUID targetId,
            String email
    ) {
        UserAccount actor = require(actorId);
        UserAccount target = requireManageable(actor, targetId);
        String companyEmail = emailPolicy.requireCompanyEmail(email);

        if (users.existsByEmailIgnoreCaseAndIdNot(companyEmail, targetId)) {
            throw new BusinessException(
                    "ACCOUNT_EMAIL_EXISTS",
                    "A login account already uses this email",
                    HttpStatus.CONFLICT
            );
        }

        target.changeEmail(companyEmail);

        audit.record(
                "STAFF_ACCOUNT_EMAIL_CHANGE",
                "USER_ACCOUNT",
                targetId.toString(),
                "{}"
        );

        return target;
    }

    @Transactional
    public UserAccount resetPassword(
            UUID actorId,
            UUID targetId,
            String temporaryPassword
    ) {
        UserAccount actor = require(actorId);
        UserAccount target = requireManageable(actor, targetId);

        validatePassword(temporaryPassword);
        target.resetPassword(encoder.encode(temporaryPassword));

        audit.record(
                "STAFF_ACCOUNT_PASSWORD_RESET",
                "USER_ACCOUNT",
                targetId.toString(),
                "{}"
        );

        return target;
    }

    @Transactional
    public UserAccount setEnabled(
            UUID actorId,
            UUID targetId,
            boolean enabled
    ) {
        UserAccount actor = require(actorId);
        UserAccount target = requireManageable(actor, targetId);

        if (enabled && target.getStatus() != AccountStatus.DISABLED) {
            throw new BusinessException(
                    "INVALID_ACCOUNT_STATUS",
                    "Only a disabled account can be re-enabled; pending accounts require the assigned approval endpoint",
                    HttpStatus.CONFLICT
            );
        }

        if (!enabled && target.getStatus() != AccountStatus.ACTIVE) {
            throw new BusinessException(
                    "INVALID_ACCOUNT_STATUS",
                    "Only an active account can be disabled",
                    HttpStatus.CONFLICT
            );
        }

        if (enabled) {
            target.enable();
        } else {
            target.disable();
        }

        audit.record(
                enabled
                        ? "STAFF_ACCOUNT_ENABLE"
                        : "STAFF_ACCOUNT_DISABLE",
                "USER_ACCOUNT",
                targetId.toString(),
                "{}"
        );

        return target;
    }

    private UserAccount requireManageable(
            UserAccount actor,
            UUID targetId
    ) {
        UserAccount target = require(targetId);
        Set<SystemRole> manageable = manageableRoles(actor);

        if (target.getRoles().isEmpty()
                || !manageable.containsAll(target.getRoles())) {
            throw new BusinessException(
                    "STAFF_ACCOUNT_SCOPE_DENIED",
                    "You cannot manage this staff account",
                    HttpStatus.FORBIDDEN
            );
        }

        requireActorScope(actor, target);
        return target;
    }

    private void requireActorScope(
            UserAccount actor,
            UserAccount target
    ) {
        UUID actorDepartmentId = departmentHrs
                .requireForUser(actor.getId())
                .departmentId();

        UUID targetEmployeeId = target.getEmployeeId();

        if (targetEmployeeId != null) {
            UUID targetDepartmentId =
                    employees.departmentIdForEmployee(targetEmployeeId);

            if (!actorDepartmentId.equals(targetDepartmentId)) {
                throw departmentScopeDenied();
            }

            return;
        }

        /*
         * Before an employee profile exists, only the HR administrator who
         * created the pending account may mutate it. Older unowned accounts
         * fail closed.
         */
        if (!actor.getId().equals(target.getCreatedByUserId())) {
            throw departmentScopeDenied();
        }
    }

    private BusinessException departmentScopeDenied() {
        return new BusinessException(
                "STAFF_ACCOUNT_DEPARTMENT_SCOPE_DENIED",
                "HR may manage only staff accounts assigned to their department",
                HttpStatus.FORBIDDEN
        );
    }

    private void requireRoleAllowed(
            UserAccount actor,
            SystemRole role
    ) {
        if (role == SystemRole.ROLE_TEAM_LEAD) {
            throw new BusinessException(
                    "TEAM_LEAD_PROMOTION_REQUIRED",
                    "Assign an existing active employee from Organization to create a Team Lead",
                    HttpStatus.UNPROCESSABLE_ENTITY
            );
        }

        if (!manageableRoles(actor).contains(role)) {
            throw new BusinessException(
                    "STAFF_ROLE_SCOPE_DENIED",
                    "You cannot create an account with this role",
                    HttpStatus.FORBIDDEN
            );
        }
    }

    private Set<SystemRole> manageableRoles(UserAccount actor) {
        if (actor.getRoles().contains(SystemRole.ROLE_HR_ADMIN)) {
            return HR_MANAGED_ROLES;
        }

        throw new BusinessException(
                "STAFF_ACCOUNT_SCOPE_DENIED",
                "Only an HR administrator may manage Team Lead, Employee, Receptionist or Security accounts",
                HttpStatus.FORBIDDEN
        );
    }

    private UserAccount require(UUID id) {
        return users.findById(id)
                .orElseThrow(() -> new BusinessException(
                        "USER_NOT_FOUND",
                        "User account was not found",
                        HttpStatus.NOT_FOUND
                ));
    }

    private void validatePassword(String password) {
        boolean strong = password != null
                && password.length() >= 12
                && password.length() <= 64
                && password.chars().anyMatch(Character::isUpperCase)
                && password.chars().anyMatch(Character::isLowerCase)
                && password.chars().anyMatch(Character::isDigit)
                && password.chars().anyMatch(
                character -> !Character.isLetterOrDigit(character)
        )
                && password.chars().noneMatch(Character::isWhitespace);

        if (!strong) {
            throw new BusinessException(
                    "WEAK_PASSWORD",
                    "Password must be 12-64 characters and include uppercase, lowercase, number and special characters without spaces",
                    HttpStatus.UNPROCESSABLE_ENTITY
            );
        }
    }
}