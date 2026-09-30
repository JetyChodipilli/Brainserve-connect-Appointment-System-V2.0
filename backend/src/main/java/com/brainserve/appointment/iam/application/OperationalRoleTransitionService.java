package com.brainserve.appointment.iam.application;

import com.brainserve.appointment.audit.api.AuditService;
import com.brainserve.appointment.departmenthr.api.DepartmentHrDirectory;
import com.brainserve.appointment.employee.api.EmployeeDirectory;
import com.brainserve.appointment.iam.domain.SystemRole;
import com.brainserve.appointment.iam.domain.UserAccount;
import com.brainserve.appointment.iam.infrastructure.RefreshTokenSessionRepository;
import com.brainserve.appointment.iam.infrastructure.UserAccountRepository;
import com.brainserve.appointment.manager.api.ManagerDirectory;
import com.brainserve.appointment.organization.api.OrganizationDirectory;
import com.brainserve.appointment.shared.application.BusinessException;
import com.brainserve.appointment.teamlead.api.TeamLeadDirectory;
import org.springframework.http.HttpStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
public class OperationalRoleTransitionService {
    private static final Set<SystemRole> OPERATIONAL_ROLES = EnumSet.of(SystemRole.ROLE_EMPLOYEE,
            SystemRole.ROLE_TEAM_LEAD, SystemRole.ROLE_HR_ADMIN, SystemRole.ROLE_MANAGER);
    private static final Set<SystemRole> RECOVERY_ROLES = EnumSet.of(SystemRole.ROLE_CEO,
            SystemRole.ROLE_MANAGER, SystemRole.ROLE_HR_ADMIN, SystemRole.ROLE_TEAM_LEAD,
            SystemRole.ROLE_EMPLOYEE, SystemRole.ROLE_RECEPTIONIST, SystemRole.ROLE_SECURITY);
    private static final Set<com.brainserve.appointment.iam.domain.AccountStatus> FORMER_CEO_STATUSES =
            EnumSet.of(com.brainserve.appointment.iam.domain.AccountStatus.ACTIVE,
                    com.brainserve.appointment.iam.domain.AccountStatus.REJECTED,
                    com.brainserve.appointment.iam.domain.AccountStatus.DISABLED);

    private final UserAccountRepository users;
    private final RefreshTokenSessionRepository sessions;
    private final EmployeeDirectory employees;
    private final OrganizationDirectory organization;
    private final TeamLeadDirectory teamLeads;
    private final DepartmentHrDirectory departmentHrs;
    private final ManagerDirectory managers;
    private final AuditService audit;

    public OperationalRoleTransitionService(UserAccountRepository users,
                                            RefreshTokenSessionRepository sessions,
                                            EmployeeDirectory employees,
                                            OrganizationDirectory organization,
                                            TeamLeadDirectory teamLeads,
                                            DepartmentHrDirectory departmentHrs,
                                            ManagerDirectory managers,
                                            AuditService audit) {
        this.users = users;
        this.sessions = sessions;
        this.employees = employees;
        this.organization = organization;
        this.teamLeads = teamLeads;
        this.departmentHrs = departmentHrs;
        this.managers = managers;
        this.audit = audit;
    }

    @Transactional
    public Result transition(UUID actorUserId, UUID targetUserId, SystemRole targetRole,
                             UUID departmentId, String reason) {
        if (actorUserId.equals(targetUserId)) {
            throw new BusinessException("SELF_ROLE_TRANSITION_DENIED",
                    "Users cannot change their own operational role", HttpStatus.FORBIDDEN);
        }
        UserAccount actor = users.findById(actorUserId).filter(UserAccount::isEnabled)
                .orElseThrow(() -> forbidden("An active CEO or System Admin account is required"));
        if (!actor.getRoles().equals(Set.of(SystemRole.ROLE_CEO))
                && !actor.getRoles().equals(Set.of(SystemRole.ROLE_SYSTEM_ADMIN))) {
            throw forbidden("Only CEO or System Admin can change operational roles");
        }
        UserAccount target = users.findByIdForUpdate(targetUserId).orElseThrow(() ->
                new BusinessException("USER_NOT_FOUND", "User account was not found", HttpStatus.NOT_FOUND));
        if (target.getRoles().size() != 1 || !OPERATIONAL_ROLES.contains(targetRole)) {
            throw new BusinessException("ROLE_TRANSITION_NOT_SUPPORTED",
                    "The target role must be Employee, Team Lead, HR Admin or Manager",
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }
        SystemRole previousRole = target.getRoles().iterator().next();
        boolean formerChiefExecutive = previousRole == SystemRole.ROLE_CEO;
        if (!formerChiefExecutive && !OPERATIONAL_ROLES.contains(previousRole)) {
            throw new BusinessException("ROLE_TRANSITION_NOT_SUPPORTED",
                    "Only operational accounts or a governed former CEO can use this transition",
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }
        if (formerChiefExecutive) {
            if (!actor.getRoles().equals(Set.of(SystemRole.ROLE_SYSTEM_ADMIN))) {
                throw forbidden("Only System Admin can move a former CEO into the Manager role");
            }
            if (targetRole != SystemRole.ROLE_MANAGER) {
                throw new BusinessException("FORMER_CEO_MANAGER_ROLE_REQUIRED",
                        "A former CEO can transition only to Manager through this governed workflow",
                        HttpStatus.UNPROCESSABLE_ENTITY);
            }
            requireAnotherActiveChiefExecutive(targetUserId);
        }
        if (previousRole == targetRole) {
            throw new BusinessException("ROLE_TRANSITION_SAME_ROLE",
                    "Select a different operational role for this transition",
                    HttpStatus.CONFLICT);
        }
        if (departmentId == null) {
            throw new BusinessException("ROLE_TRANSITION_DEPARTMENT_REQUIRED",
                    "Select the department for this role transition", HttpStatus.UNPROCESSABLE_ENTITY);
        }
        organization.lockActiveDepartment(departmentId);
        UUID employeeId = target.getEmployeeId();
        if (employeeId == null) {
            throw new BusinessException("ROLE_TRANSITION_EMPLOYEE_REQUIRED",
                    "The account must be linked to an employee profile", HttpStatus.UNPROCESSABLE_ENTITY);
        }
        employees.requireActiveEmployee(employeeId);

        endPreviousAssignment(actorUserId, target, previousRole);
        endConflictingAssignments(actorUserId, target, targetRole, departmentId);
        requireTargetAssignmentAvailable(targetUserId, targetRole, departmentId);
        employees.transitionOperationalPosition(employeeId, departmentId, designationFor(targetRole));
        try {
            if (formerChiefExecutive) target.replaceFormerChiefExecutiveWithManager();
            else target.replaceOperationalRole(targetRole);
        } catch (IllegalStateException exception) {
            throw new BusinessException("ROLE_TRANSITION_INVALID", exception.getMessage(),
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }
        users.saveAndFlush(target);
        createTargetAssignment(actorUserId, target, targetRole, departmentId);
        Instant changedAt = Instant.now();
        sessions.revokeAllForUser(targetUserId, changedAt);
        audit.record("OPERATIONAL_ROLE_TRANSITIONED", "USER_ACCOUNT", targetUserId.toString(),
                "{\"fromRole\":\"" + previousRole + "\",\"toRole\":\"" + targetRole
                        + "\",\"departmentId\":\"" + departmentId + "\",\"reason\":\""
                        + json(reason) + "\"}");
        return new Result(targetUserId, previousRole, targetRole, departmentId, changedAt);
    }

    @Transactional
    public SuccessionResult succeedChiefExecutive(UUID actorUserId, UUID currentCeoUserId,
                                                  UUID successorUserId, UUID formerCeoDepartmentId,
                                                  String reason) {
        if (currentCeoUserId == null || successorUserId == null
                || currentCeoUserId.equals(successorUserId)) {
            throw new BusinessException("CEO_SUCCESSOR_MUST_DIFFER",
                    "Select a different account as successor CEO", HttpStatus.UNPROCESSABLE_ENTITY);
        }
        String normalizedReason = reason == null ? "" : reason.trim().replaceAll("\\s+", " ");
        if (normalizedReason.length() < 5 || normalizedReason.length() > 500) {
            throw new BusinessException("CEO_SUCCESSION_REASON_INVALID",
                    "Succession reason must contain 5 to 500 characters",
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }
        UserAccount actor = users.findByIdForUpdate(actorUserId).filter(UserAccount::isEnabled)
                .orElseThrow(() -> forbidden("An active System Admin account is required"));
        if (!actor.getRoles().equals(Set.of(SystemRole.ROLE_SYSTEM_ADMIN))) {
            throw forbidden("Only System Admin can execute CEO succession");
        }
        var governing = users.findGoverningRoleAccountsForUpdate(SystemRole.ROLE_CEO,
                EnumSet.of(com.brainserve.appointment.iam.domain.AccountStatus.ACTIVE,
                        com.brainserve.appointment.iam.domain.AccountStatus.PENDING_APPROVAL));
        if (governing.size() != 1
                || !governing.getFirst().getId().equals(currentCeoUserId)
                || !governing.getFirst().isEnabled()) {
            throw new BusinessException("CEO_GOVERNANCE_CONFLICT",
                    "Exactly one active CEO must match the succession request",
                    HttpStatus.CONFLICT);
        }
        UserAccount current = users.findByIdForUpdate(currentCeoUserId).orElseThrow(() ->
                new BusinessException("CEO_NOT_FOUND", "The current CEO account was not found", HttpStatus.NOT_FOUND));
        UserAccount successor = users.findByIdForUpdate(successorUserId).orElseThrow(() ->
                new BusinessException("CEO_SUCCESSOR_NOT_FOUND", "The successor account was not found", HttpStatus.NOT_FOUND));
        if (!current.isEnabled() || current.isArchived()
                || !current.getRoles().equals(Set.of(SystemRole.ROLE_CEO))) {
            throw new BusinessException("CURRENT_CEO_REQUIRED",
                    "Select the single active CEO account", HttpStatus.CONFLICT);
        }
        organization.lockActiveDepartment(formerCeoDepartmentId);
        if (!successor.isEnabled() || successor.isArchived()
                || current.getEmployeeId() == null || successor.getEmployeeId() == null) {
            throw new BusinessException("CEO_SUCCESSION_EMPLOYEE_REQUIRED",
                    "Both CEO identities must be active and retain an employee profile",
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }
        employees.requireActiveEmployee(current.getEmployeeId());
        employees.requireActiveEmployee(successor.getEmployeeId());

        SystemRole successorPreviousRole = successor.getRoles().size() == 1
                ? successor.getRoles().iterator().next() : null;
        if (successorPreviousRole == null || !OPERATIONAL_ROLES.contains(successorPreviousRole)) {
            throw new BusinessException("CEO_SUCCESSOR_ROLE_INVALID",
                    "The successor must have exactly one active operational role", HttpStatus.UNPROCESSABLE_ENTITY);
        }

        endConflictingAssignments(actorUserId, successor, SystemRole.ROLE_CEO,
                employees.departmentIdForEmployee(successor.getEmployeeId()));
        endConflictingAssignments(actorUserId, current, SystemRole.ROLE_MANAGER, formerCeoDepartmentId);
        requireTargetAssignmentAvailable(currentCeoUserId, SystemRole.ROLE_MANAGER, formerCeoDepartmentId);
        employees.transitionOperationalPosition(current.getEmployeeId(), formerCeoDepartmentId,
                designationFor(SystemRole.ROLE_MANAGER));
        employees.transitionOperationalPosition(successor.getEmployeeId(),
                employees.departmentIdForEmployee(successor.getEmployeeId()), "Chief Executive Officer");
        try {
            current.replaceFormerChiefExecutiveWithManager();
            successor.appointChiefExecutive();
        } catch (IllegalStateException exception) {
            throw new BusinessException("CEO_SUCCESSION_INVALID", exception.getMessage(),
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }
        users.saveAndFlush(current);
        users.saveAndFlush(successor);
        createTargetAssignment(actorUserId, current, SystemRole.ROLE_MANAGER, formerCeoDepartmentId);
        Instant changedAt = Instant.now();
        sessions.revokeAllForUser(currentCeoUserId, changedAt);
        sessions.revokeAllForUser(successorUserId, changedAt);
        audit.record("CEO_SUCCESSION_COMPLETED", "USER_ACCOUNT", successorUserId.toString(),
                "{\"formerCeoUserId\":\"" + currentCeoUserId + "\",\"formerCeoDepartmentId\":\""
                        + formerCeoDepartmentId + "\",\"successorPreviousRole\":\"" + successorPreviousRole
                        + "\",\"reason\":\"" + json(normalizedReason) + "\"}");
        return new SuccessionResult(currentCeoUserId, successorUserId, formerCeoDepartmentId, changedAt);
    }

    @Transactional(readOnly = true)
    public void validateArchivedRecovery(UUID actorUserId, UUID targetUserId, SystemRole targetRole,
                                         UUID departmentId) {
        UserAccount actor = users.findById(actorUserId).filter(UserAccount::isEnabled)
                .orElseThrow(() -> forbidden("An active System Admin account is required"));
        if (!actor.getRoles().equals(Set.of(SystemRole.ROLE_SYSTEM_ADMIN))) {
            throw forbidden("Only System Admin can recover an archived account");
        }
        UserAccount target = users.findById(targetUserId).orElseThrow(() ->
                new BusinessException("USER_NOT_FOUND", "User account was not found", HttpStatus.NOT_FOUND));
        validateRecoveryTarget(target, targetRole, departmentId);
        if (targetRole == SystemRole.ROLE_CEO && users
                .findDistinctByRolesContainingAndStatusAndEnabledTrueAndArchivedFalse(
                        SystemRole.ROLE_CEO, com.brainserve.appointment.iam.domain.AccountStatus.ACTIVE)
                .stream().anyMatch(account -> !account.getId().equals(targetUserId))) {
            throw new BusinessException("CEO_ALREADY_ASSIGNED",
                    "The company already has an active CEO. Select another role for this recovery",
                    HttpStatus.CONFLICT);
        }
        requireTargetAssignmentAvailable(targetUserId, targetRole, departmentId);
    }

    @Transactional
    public RecoveryResult recoverArchived(UUID actorUserId, UUID targetUserId, SystemRole targetRole,
                                          UUID departmentId, String reason) {
        UserAccount actor = users.findByIdForUpdate(actorUserId).filter(UserAccount::isEnabled)
                .orElseThrow(() -> forbidden("An active System Admin account is required"));
        if (!actor.getRoles().equals(Set.of(SystemRole.ROLE_SYSTEM_ADMIN))) {
            throw forbidden("Only System Admin can recover an archived account");
        }
        UserAccount target = users.findByIdForUpdate(targetUserId).orElseThrow(() ->
                new BusinessException("USER_NOT_FOUND", "User account was not found", HttpStatus.NOT_FOUND));
        validateRecoveryTarget(target, targetRole, departmentId);
        SystemRole previousRole = target.getRoles().iterator().next();
        UUID previousDepartmentId = target.getEmployeeId() == null
                ? null : employees.departmentIdForEmployee(target.getEmployeeId());

        if (departmentId != null) organization.lockActiveDepartment(departmentId);
        if (targetRole == SystemRole.ROLE_CEO) requireChiefExecutiveSlot(targetUserId);
        requireTargetAssignmentAvailable(targetUserId, targetRole, departmentId);
        endConflictingAssignments(actorUserId, target, targetRole, departmentId);

        if (target.getEmployeeId() != null && departmentId != null) {
            employees.restoreAfterAccountRecovery(target.getEmployeeId(), departmentId,
                    designationForRecovery(targetRole));
        }
        boolean roleChanged;
        try {
            roleChanged = target.recoverArchivedWithRole(targetRole);
        } catch (IllegalStateException exception) {
            throw new BusinessException("ACCOUNT_RECOVERY_INVALID", exception.getMessage(),
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }
        users.saveAndFlush(target);
        createTargetAssignment(actorUserId, target, targetRole, departmentId);
        Instant changedAt = Instant.now();
        sessions.revokeAllForUser(targetUserId, changedAt);
        boolean departmentChanged = !java.util.Objects.equals(previousDepartmentId, departmentId);
        audit.record("ARCHIVED_ACCOUNT_RECOVERED", "USER_ACCOUNT", targetUserId.toString(),
                "{\"fromRole\":\"" + previousRole + "\",\"toRole\":\"" + targetRole
                        + "\",\"fromDepartmentId\":\"" + (previousDepartmentId == null ? "" : previousDepartmentId)
                        + "\",\"toDepartmentId\":\"" + (departmentId == null ? "" : departmentId)
                        + "\",\"roleChanged\":" + roleChanged + ",\"departmentChanged\":" + departmentChanged
                        + ",\"reason\":\"" + json(reason) + "\"}");
        return new RecoveryResult(targetUserId, target.getEmployeeId(), previousRole, targetRole,
                previousDepartmentId, departmentId, roleChanged, departmentChanged, changedAt);
    }

    @Transactional(readOnly = true)
    public Page<Candidate> candidates(UUID actorUserId, String query, Pageable pageable) {
        UserAccount actor = users.findById(actorUserId).filter(UserAccount::isEnabled)
                .orElseThrow(() -> forbidden("An active CEO or System Admin account is required"));
        if (!actor.getRoles().equals(Set.of(SystemRole.ROLE_CEO))
                && !actor.getRoles().equals(Set.of(SystemRole.ROLE_SYSTEM_ADMIN))) {
            throw forbidden("Only CEO or System Admin can view role-transition candidates");
        }
        String normalized = query == null || query.isBlank()
                ? ""
                : query.trim().toLowerCase(Locale.ROOT);
        boolean includeFormerCeo = actor.getRoles().equals(Set.of(SystemRole.ROLE_SYSTEM_ADMIN));
        Page<UserAccount> page = users.findOperationalRoleTransitionCandidates(
                com.brainserve.appointment.iam.domain.AccountStatus.ACTIVE,
                normalized, OPERATIONAL_ROLES, includeFormerCeo, SystemRole.ROLE_CEO,
                FORMER_CEO_STATUSES, pageable);
        return page.map(account -> {
            if (account.getRoles().size() != 1
                    || (!OPERATIONAL_ROLES.containsAll(account.getRoles())
                    && !account.getRoles().equals(Set.of(SystemRole.ROLE_CEO)))
                    || account.getEmployeeId() == null) {
                throw new IllegalStateException("The role-transition query returned an invalid account");
            }
            return new Candidate(account.getId(), account.getEmployeeId(),
                    account.getFullName(), account.getEmail(), account.getRoles().iterator().next(),
                    employees.departmentIdForEmployee(account.getEmployeeId()));
        });
    }

    private void endPreviousAssignment(UUID actorUserId, UserAccount target, SystemRole role) {
        if (role == SystemRole.ROLE_TEAM_LEAD) {
            teamLeads.endForRoleTransition(actorUserId, target.getId());
        } else if (role == SystemRole.ROLE_HR_ADMIN) {
            departmentHrs.endForRoleTransition(actorUserId, target.getId());
        } else if (role == SystemRole.ROLE_MANAGER) {
            managers.endForRoleTransition(actorUserId, target.getId());
        }
    }

    private void requireTargetAssignmentAvailable(UUID targetUserId, SystemRole role, UUID departmentId) {
        if (departmentId == null) return;
        boolean occupied = switch (role) {
            case ROLE_TEAM_LEAD -> teamLeads.activeForDepartment(departmentId)
                    .filter(value -> !value.teamLeadUserId().equals(targetUserId)).isPresent();
            case ROLE_HR_ADMIN -> departmentHrs.activeForDepartment(departmentId)
                    .filter(value -> !value.hrUserId().equals(targetUserId)).isPresent();
            case ROLE_MANAGER -> managers.activeForDepartment(departmentId)
                    .filter(value -> !value.managerUserId().equals(targetUserId)).isPresent();
            default -> false;
        };
        if (occupied) {
            throw new BusinessException("ROLE_TRANSITION_DEPARTMENT_OCCUPIED",
                    "The selected department already has an active " + role.name().replace("ROLE_", "")
                            .replace('_', ' '), HttpStatus.CONFLICT);
        }
    }

    private void endConflictingAssignments(UUID actorUserId, UserAccount target, SystemRole targetRole,
                                           UUID targetDepartmentId) {
        teamLeads.activeForUser(target.getId()).ifPresent(value -> {
            if (targetRole != SystemRole.ROLE_TEAM_LEAD
                    || !value.departmentId().equals(targetDepartmentId)) {
                teamLeads.endForRoleTransition(actorUserId, target.getId());
            }
        });
        departmentHrs.activeForUser(target.getId()).ifPresent(value -> {
            if (targetRole != SystemRole.ROLE_HR_ADMIN
                    || !value.departmentId().equals(targetDepartmentId)) {
                departmentHrs.endForRoleTransition(actorUserId, target.getId());
            }
        });
        managers.activeForUser(target.getId()).ifPresent(value -> {
            if (targetRole != SystemRole.ROLE_MANAGER
                    || !value.departmentId().equals(targetDepartmentId)) {
                managers.endForRoleTransition(actorUserId, target.getId());
            }
        });
    }

    private void createTargetAssignment(UUID actorUserId, UserAccount target, SystemRole role,
                                        UUID departmentId) {
        if (departmentId == null || target.getEmployeeId() == null) return;
        if (role == SystemRole.ROLE_TEAM_LEAD) {
            teamLeads.assignForRoleTransition(
                    actorUserId, departmentId, target.getId(), target.getEmployeeId());
        } else if (role == SystemRole.ROLE_HR_ADMIN) {
            departmentHrs.assignForRoleTransition(
                    actorUserId, departmentId, target.getId(), target.getEmployeeId());
        } else if (role == SystemRole.ROLE_MANAGER) {
            managers.assignForRoleTransition(
                    actorUserId, departmentId, target.getId(), target.getEmployeeId());
        }
    }

    private void requireAnotherActiveChiefExecutive(UUID targetUserId) {
        boolean successorExists = users.findGoverningRoleAccountsForUpdate(
                        SystemRole.ROLE_CEO,
                        EnumSet.of(com.brainserve.appointment.iam.domain.AccountStatus.ACTIVE,
                                com.brainserve.appointment.iam.domain.AccountStatus.PENDING_APPROVAL))
                .stream()
                .anyMatch(account -> !account.getId().equals(targetUserId)
                        && account.isEnabled()
                        && account.getRoles().equals(Set.of(SystemRole.ROLE_CEO)));
        if (!successorExists) {
            throw new BusinessException("CEO_SUCCESSION_REQUIRED",
                    "Activate the successor CEO before moving the current CEO to Manager",
                    HttpStatus.CONFLICT);
        }
    }

    private void requireChiefExecutiveSlot(UUID targetUserId) {
        boolean occupied = users.findGoverningRoleAccountsForUpdate(
                        SystemRole.ROLE_CEO,
                        EnumSet.of(com.brainserve.appointment.iam.domain.AccountStatus.ACTIVE,
                                com.brainserve.appointment.iam.domain.AccountStatus.PENDING_APPROVAL))
                .stream().anyMatch(account -> !account.getId().equals(targetUserId));
        if (occupied) {
            throw new BusinessException("CEO_ALREADY_ASSIGNED",
                    "The company already has an active or pending CEO. Select another role for this recovery",
                    HttpStatus.CONFLICT);
        }
    }

    private void validateRecoveryTarget(UserAccount target, SystemRole targetRole, UUID departmentId) {
        if (!target.isArchived() || target.isEnabled()) {
            throw new BusinessException("ACCOUNT_NOT_ARCHIVED",
                    "Only a currently archived account can be recovered", HttpStatus.CONFLICT);
        }
        if (target.getRoles().size() != 1 || !RECOVERY_ROLES.contains(targetRole)) {
            throw new BusinessException("ACCOUNT_RECOVERY_ROLE_INVALID",
                    "Select one supported recovery role", HttpStatus.UNPROCESSABLE_ENTITY);
        }
        boolean employeeRole = targetRole == SystemRole.ROLE_CEO || OPERATIONAL_ROLES.contains(targetRole);
        if (employeeRole && target.getEmployeeId() == null) {
            throw new BusinessException("ACCOUNT_RECOVERY_EMPLOYEE_REQUIRED",
                    "This role requires the archived account's existing employee ID",
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }
        if (employeeRole && departmentId == null) {
            throw new BusinessException("ACCOUNT_RECOVERY_DEPARTMENT_REQUIRED",
                    "Select a department for this recovered role", HttpStatus.UNPROCESSABLE_ENTITY);
        }
        if (!employeeRole && departmentId != null) {
            throw new BusinessException("ACCOUNT_RECOVERY_DEPARTMENT_NOT_ALLOWED",
                    "Receptionist and Security recovery remain company-wide",
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }
        if (departmentId != null) organization.requireActiveDepartment(departmentId);
        if (target.getEmployeeId() != null && employeeRole) employees.requireEmployee(target.getEmployeeId());
    }

    private String designationFor(SystemRole role) {
        return switch (role) {
            case ROLE_MANAGER -> "Department Manager";
            case ROLE_HR_ADMIN -> "HR Business Partner";
            case ROLE_TEAM_LEAD -> "Team Lead";
            default -> null;
        };
    }

    private String designationForRecovery(SystemRole role) {
        return switch (role) {
            case ROLE_CEO -> "Chief Executive Officer";
            case ROLE_MANAGER -> "Department Manager";
            case ROLE_HR_ADMIN -> "HR Business Partner";
            case ROLE_TEAM_LEAD -> "Team Lead";
            case ROLE_EMPLOYEE -> "Employee";
            default -> null;
        };
    }

    private String json(String value) {
        return value == null ? "" : value.trim().replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private BusinessException forbidden(String message) {
        return new BusinessException("ROLE_TRANSITION_FORBIDDEN", message, HttpStatus.FORBIDDEN);
    }

    public record Result(UUID userId, SystemRole previousRole, SystemRole role,
                         UUID departmentId, Instant changedAt) {}
    public record RecoveryResult(UUID userId, UUID employeeId, SystemRole previousRole,
                                 SystemRole role, UUID previousDepartmentId, UUID departmentId,
                                 boolean roleChanged, boolean departmentChanged, Instant changedAt) {}
    public record SuccessionResult(UUID formerCeoUserId, UUID successorCeoUserId,
                                   UUID formerCeoDepartmentId, Instant changedAt) {}
    public record Candidate(UUID userId, UUID employeeId, String fullName, String email,
                            SystemRole role, UUID departmentId) {}
}
