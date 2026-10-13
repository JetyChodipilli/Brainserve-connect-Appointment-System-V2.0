package com.brainserve.appointment.iam.application;

import com.brainserve.appointment.audit.api.AuditService;
import com.brainserve.appointment.iam.api.CurrentAccountAuthority;
import com.brainserve.appointment.departmenthr.api.DepartmentHrDirectory;
import com.brainserve.appointment.employee.api.EmployeeDirectory;
import com.brainserve.appointment.iam.domain.Permission;
import com.brainserve.appointment.iam.domain.SystemRole;
import com.brainserve.appointment.iam.domain.UserAccount;
import com.brainserve.appointment.iam.infrastructure.UserAccountRepository;
import com.brainserve.appointment.shared.application.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;
import java.util.UUID;

@Service
public class PermissionAdministrationService {
    private final UserAccountRepository users;
    private final AuditService audit;
    private final DepartmentHrDirectory departmentHrs;
    private final EmployeeDirectory employees;
    private final CurrentAccountAuthority authority;

    public PermissionAdministrationService(UserAccountRepository users, AuditService audit,
                                           DepartmentHrDirectory departmentHrs,
                                           EmployeeDirectory employees, CurrentAccountAuthority authority) {
        this.users = users; this.audit = audit; this.departmentHrs = departmentHrs;
        this.employees = employees; this.authority = authority;
    }

    @Transactional(timeout = 10)
    public UserAccount replaceFromSession(Jwt jwt, UUID targetId, Set<Permission> grants, Set<Permission> denies) {
        authority.requireFreshPermissionWriter(jwt, targetId);
        return replaceOverrides(UUID.fromString(jwt.getSubject()), targetId, grants, denies);
    }

    @Transactional
    public UserAccount replaceOverrides(UUID actorId, UUID targetId, Set<Permission> grants, Set<Permission> denies) {
        if (actorId.equals(targetId))
            throw new BusinessException("SELF_PRIVILEGE_CHANGE", "Users cannot change their own privileged access", HttpStatus.FORBIDDEN);
        UserAccount actor = require(actorId);
        UserAccount target = require(targetId);
        // Both additions and removals require authority. Unchanged administrator overrides are preserved.
        Set<Permission> requested = changed(target.getGrantedPermissions(), grants);
        requested.addAll(changed(target.getDeniedPermissions(), denies));
        Set<Permission> manageable = manageablePermissions(actor, target);
        if (!manageable.containsAll(requested))
            throw new BusinessException("PERMISSION_GRANT_EXCEEDS_AUTHORITY", "One or more permissions are outside your management scope", HttpStatus.FORBIDDEN);
        if (!java.util.Collections.disjoint(grants, denies))
            throw new BusinessException("CONFLICTING_PERMISSION_OVERRIDE", "A permission cannot be both granted and denied", HttpStatus.BAD_REQUEST);
        target.replacePermissionOverrides(grants, denies);
        audit.record("USER_PERMISSION_OVERRIDE", "USER_ACCOUNT", targetId.toString(),
                "{\"grants\":" + grants.size() + ",\"denies\":" + denies.size() + "}");
        return target;
    }

    @Transactional(readOnly = true)
    public UserAccount permissionDetails(UUID actorId, UUID targetId) {
        UserAccount actor = require(actorId);
        UserAccount target = require(targetId);
        manageablePermissions(actor, target);
        return target;
    }

    private static Set<Permission> changed(Set<Permission> before, Set<Permission> after) {
        Set<Permission> difference = new java.util.HashSet<>(before);
        difference.addAll(after);
        Set<Permission> unchanged = new java.util.HashSet<>(before);
        unchanged.retainAll(after);
        difference.removeAll(unchanged);
        return difference;
    }

    private Set<Permission> manageablePermissions(UserAccount actor, UserAccount target) {
        if (!actor.isEnabled() || actor.isArchived()
                || java.util.Collections.disjoint(actor.effectivePermissions(),
                    Set.of(Permission.ROLE_MANAGE, Permission.STAFF_ACCOUNT_MANAGE))) {
            throw new BusinessException("PERMISSION_MANAGEMENT_FORBIDDEN",
                    "An active account with permission management authority is required", HttpStatus.FORBIDDEN);
        }
        if (actor.getRoles().contains(SystemRole.ROLE_SYSTEM_ADMIN)) {
            return java.util.EnumSet.allOf(Permission.class);
        }
        if (actor.getRoles().contains(SystemRole.ROLE_HR_ADMIN)) {
            Set<SystemRole> lowerRoles = java.util.EnumSet.of(
                    SystemRole.ROLE_EMPLOYEE, SystemRole.ROLE_TEAM_LEAD,
                    SystemRole.ROLE_RECEPTIONIST, SystemRole.ROLE_SECURITY);
            if (target.getRoles().isEmpty() || !lowerRoles.containsAll(target.getRoles())) {
                throw new BusinessException("PERMISSION_TARGET_SCOPE_DENIED",
                        "HR may change permissions only for Team Lead, Employee, Receptionist or Security accounts",
                        HttpStatus.FORBIDDEN);
            }
            requireActorScope(actor, target);
            java.util.EnumSet<Permission> permissions = java.util.EnumSet.noneOf(Permission.class);
            lowerRoles.forEach(role -> permissions.addAll(role.permissions()));
            permissions.add(Permission.STAFF_ACCOUNT_APPROVE);
            return permissions;
        }
        throw new BusinessException("PERMISSION_MANAGEMENT_FORBIDDEN",
                "Your role cannot manage permissions", HttpStatus.FORBIDDEN);
    }

    private void requireActorScope(UserAccount actor, UserAccount target) {
        UUID actorDepartmentId = departmentHrs.requireForUser(actor.getId()).departmentId();
        UUID targetEmployeeId = target.getEmployeeId();
        if (targetEmployeeId != null
                && actorDepartmentId.equals(employees.departmentIdForEmployee(targetEmployeeId))) {
            return;
        }
        if (targetEmployeeId == null && actor.getId().equals(target.getCreatedByUserId())) {
            return;
        }
        throw new BusinessException("PERMISSION_TARGET_DEPARTMENT_SCOPE_DENIED",
                "HR may change permissions only for staff assigned to their department",
                HttpStatus.FORBIDDEN);
    }

    @Transactional(readOnly = true)
    public UserAccount require(UUID id) {
        return users.findById(id).orElseThrow(() -> new BusinessException("USER_NOT_FOUND", "User account was not found", HttpStatus.NOT_FOUND));
    }
}
