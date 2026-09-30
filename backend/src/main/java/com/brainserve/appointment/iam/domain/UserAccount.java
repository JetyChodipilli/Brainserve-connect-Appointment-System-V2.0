package com.brainserve.appointment.iam.domain;

import com.brainserve.appointment.shared.domain.AuditableEntity;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "iam_user_account")
public class UserAccount extends AuditableEntity {
    @Column(nullable = false, unique = true, length = 180)
    private String email;
    @Column(name = "full_name", nullable = false, length = 170)
    private String fullName;
    @Column(name = "employee_id", unique = true)
    private UUID employeeId;
    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;
    @Column(nullable = false)
    private boolean enabled = true;
    @Column(name = "force_password_change", nullable = false)
    private boolean forcePasswordChange;
    @Column(name = "failed_login_count", nullable = false)
    private int failedLoginCount;
    @Column(name = "locked_until")
    private Instant lockedUntil;
    @Enumerated(EnumType.STRING)
    @Column(name = "account_status", nullable = false, length = 40)
    private AccountStatus status = AccountStatus.ACTIVE;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by_user_id")
    private UserAccount createdByUser;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "approved_by_user_id")
    private UserAccount approvedByUser;
    @Column(name = "approved_at")
    private Instant approvedAt;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "rejected_by_user_id")
    private UserAccount rejectedByUser;
    @Column(name = "rejected_at")
    private Instant rejectedAt;
    @Column(nullable = false)
    private boolean archived;
    @Column(name = "archived_at")
    private Instant archivedAt;
    @Column(name = "archive_reason", length = 1000)
    private String archiveReason;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "iam_user_role", joinColumns = @JoinColumn(name = "user_id"))
    @Enumerated(EnumType.STRING)
    @Column(name = "role_name", nullable = false, length = 60)
    private Set<SystemRole> roles = new HashSet<>();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "iam_user_permission_grant", joinColumns = @JoinColumn(name = "user_id"))
    @Enumerated(EnumType.STRING)
    @Column(name = "permission_name", nullable = false, length = 80)
    private Set<Permission> grantedPermissions = new HashSet<>();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "iam_user_permission_deny", joinColumns = @JoinColumn(name = "user_id"))
    @Enumerated(EnumType.STRING)
    @Column(name = "permission_name", nullable = false, length = 80)
    private Set<Permission> deniedPermissions = new HashSet<>();

    protected UserAccount() {}

    public UserAccount(String email, UUID employeeId, String passwordHash, boolean forcePasswordChange, Set<SystemRole> roles) {
        this(email, email.substring(0, email.indexOf('@') > 0 ? email.indexOf('@') : email.length()), employeeId,
                passwordHash, forcePasswordChange, AccountStatus.ACTIVE, roles, null);
    }

    public UserAccount(String email, String fullName, UUID employeeId, String passwordHash,
                       boolean forcePasswordChange, AccountStatus status, Set<SystemRole> roles,
                       UserAccount createdByUser) {
        this.email = email.trim().toLowerCase();
        this.fullName = fullName.trim();
        this.employeeId = employeeId;
        this.passwordHash = passwordHash;
        this.forcePasswordChange = forcePasswordChange;
        this.status = status;
        this.enabled = status == AccountStatus.ACTIVE;
        this.createdByUser = createdByUser;
        if (roles == null || roles.size() != 1) {
            throw new IllegalArgumentException("A user account must have exactly one effective role");
        }
        this.roles.addAll(roles);
    }

    public String getEmail() { return email; }
    public String getFullName() { return fullName; }
    public UUID getEmployeeId() { return employeeId; }
    public String getPasswordHash() { return passwordHash; }
    public boolean isEnabled() { return enabled && status == AccountStatus.ACTIVE; }
    public boolean isForcePasswordChange() { return forcePasswordChange; }
    public AccountStatus getStatus() { return status; }
    public UserAccount getCreatedByUser() { return createdByUser; }
    public UserAccount getApprovedByUser() { return approvedByUser; }
    public UUID getCreatedByUserId() { return createdByUser == null ? null : createdByUser.getId(); }
    public UUID getApprovedByUserId() { return approvedByUser == null ? null : approvedByUser.getId(); }
    public Instant getApprovedAt() { return approvedAt; }
    public UserAccount getRejectedByUser() { return rejectedByUser; }
    public UUID getRejectedByUserId() { return rejectedByUser == null ? null : rejectedByUser.getId(); }
    public Instant getRejectedAt() { return rejectedAt; }
    public boolean isArchived() { return archived; }
    public Instant getArchivedAt() { return archivedAt; }
    public String getArchiveReason() { return archiveReason; }
    public Set<SystemRole> getRoles() { return Set.copyOf(roles); }
    public Set<Permission> effectivePermissions() {
        Set<Permission> effective = new HashSet<>(grantedPermissions);
        roles.forEach(role -> effective.addAll(role.permissions()));
        effective.removeAll(deniedPermissions);
        return Set.copyOf(effective);
    }
    public Set<Permission> getGrantedPermissions() { return Set.copyOf(grantedPermissions); }
    public Set<Permission> getDeniedPermissions() { return Set.copyOf(deniedPermissions); }
    public void replacePermissionOverrides(Set<Permission> grants, Set<Permission> denies) {
        if (!java.util.Collections.disjoint(grants, denies)) throw new IllegalArgumentException("A permission cannot be both granted and denied");
        grantedPermissions.clear(); grantedPermissions.addAll(grants);
        deniedPermissions.clear(); deniedPermissions.addAll(denies);
    }
    public boolean isLocked() { return lockedUntil != null && lockedUntil.isAfter(Instant.now()); }
    public void recordSuccessfulLogin() { failedLoginCount = 0; lockedUntil = null; }
    public void recordFailedLogin() { failedLoginCount++; if (failedLoginCount >= 5) lockedUntil = Instant.now().plusSeconds(15 * 60); }
    public void disable() { enabled = false; status = AccountStatus.DISABLED; }
    public void archive(String reason, Instant when) {
        if (roles.contains(SystemRole.ROLE_SYSTEM_ADMIN)) {
            throw new IllegalStateException("The permanent System Admin account cannot be archived");
        }
        enabled = false;
        status = AccountStatus.DISABLED;
        archived = true;
        archivedAt = when;
        archiveReason = reason == null ? null : reason.trim().replaceAll("\\s+", " ");
        failedLoginCount = 0;
        lockedUntil = null;
    }
    public void enable() {
        if (archived) throw new IllegalStateException("Archived accounts cannot be re-enabled");
        enabled = true; status = AccountStatus.ACTIVE; failedLoginCount = 0; lockedUntil = null;
    }
    public void approve(UserAccount approver) {
        enabled = true;
        status = AccountStatus.ACTIVE;
        approvedByUser = approver;
        approvedAt = Instant.now();
        rejectedByUser = null;
        rejectedAt = null;
        failedLoginCount = 0;
        lockedUntil = null;
    }
    public void reject(UserAccount rejector) {
        enabled = false;
        status = AccountStatus.REJECTED;
        approvedByUser = null;
        approvedAt = null;
        rejectedByUser = rejector;
        rejectedAt = Instant.now();
    }
    public void changeEmail(String nextEmail) { email = nextEmail.trim().toLowerCase(); }
    public void linkEmployee(UUID nextEmployeeId) {
        if (nextEmployeeId == null) throw new IllegalArgumentException("Employee ID is required");
        if (employeeId != null && !employeeId.equals(nextEmployeeId)) {
            throw new IllegalStateException("User account is already linked to another employee");
        }
        employeeId = nextEmployeeId;
    }
    public void promoteToTeamLead() {
        if (!enabled || status != AccountStatus.ACTIVE || employeeId == null
                || roles.size() != 1 || !roles.contains(SystemRole.ROLE_EMPLOYEE)) {
            throw new IllegalStateException("Only one active employee account can be promoted to Team Lead");
        }
        roles.clear(); roles.add(SystemRole.ROLE_TEAM_LEAD);
    }
    public void demoteTeamLeadToEmployee() {
        if (roles.size() != 1 || !roles.contains(SystemRole.ROLE_TEAM_LEAD)) return;
        roles.clear(); roles.add(SystemRole.ROLE_EMPLOYEE);
    }
    public void replaceOperationalRole(SystemRole nextRole) {
        Set<SystemRole> operationalRoles = java.util.EnumSet.of(SystemRole.ROLE_EMPLOYEE,
                SystemRole.ROLE_TEAM_LEAD, SystemRole.ROLE_HR_ADMIN, SystemRole.ROLE_MANAGER);
        if (!enabled || status != AccountStatus.ACTIVE || archived || employeeId == null) {
            throw new IllegalStateException("Only an active employee-linked account can change operational role");
        }
        if (roles.size() != 1 || !operationalRoles.containsAll(roles) || !operationalRoles.contains(nextRole)) {
            throw new IllegalStateException("The account must have one supported operational role");
        }
        roles.clear();
        roles.add(nextRole);
        grantedPermissions.clear();
        deniedPermissions.clear();
    }
    public void replaceFormerChiefExecutiveWithManager() {
        if (archived || employeeId == null || roles.size() != 1
                || !roles.contains(SystemRole.ROLE_CEO)) {
            throw new IllegalStateException(
                    "Only a non-archived, employee-linked former CEO account can become Manager");
        }
        enabled = true;
        status = AccountStatus.ACTIVE;
        rejectedByUser = null;
        rejectedAt = null;
        failedLoginCount = 0;
        lockedUntil = null;
        roles.clear();
        roles.add(SystemRole.ROLE_MANAGER);
        grantedPermissions.clear();
        deniedPermissions.clear();
    }
    public void appointChiefExecutive() {
        Set<SystemRole> eligibleRoles = java.util.EnumSet.of(SystemRole.ROLE_EMPLOYEE,
                SystemRole.ROLE_TEAM_LEAD, SystemRole.ROLE_HR_ADMIN, SystemRole.ROLE_MANAGER);
        if (!enabled || status != AccountStatus.ACTIVE || archived || employeeId == null
                || roles.size() != 1 || !eligibleRoles.containsAll(roles)) {
            throw new IllegalStateException(
                    "Only one active employee-linked operational account can become CEO");
        }
        roles.clear();
        roles.add(SystemRole.ROLE_CEO);
        grantedPermissions.clear();
        deniedPermissions.clear();
    }
    public boolean recoverArchivedWithRole(SystemRole nextRole) {
        if (!archived || roles.size() != 1 || nextRole == null
                || nextRole == SystemRole.ROLE_SYSTEM_ADMIN) {
            throw new IllegalStateException("Only one archived operational identity can be recovered");
        }
        boolean roleChanged = !roles.contains(nextRole);
        if (roleChanged) {
            roles.clear();
            roles.add(nextRole);
        }
        enabled = true;
        status = AccountStatus.ACTIVE;
        archived = false;
        archivedAt = null;
        archiveReason = null;
        approvedByUser = null;
        approvedAt = null;
        rejectedByUser = null;
        rejectedAt = null;
        failedLoginCount = 0;
        lockedUntil = null;
        grantedPermissions.clear();
        deniedPermissions.clear();
        return roleChanged;
    }
    public void resetPassword(String hash) { passwordHash = hash; forcePasswordChange = true; failedLoginCount = 0; lockedUntil = null; }
    public void changePassword(String hash) { passwordHash = hash; forcePasswordChange = false; }
    public void recoverPassword(String hash) {
        passwordHash = hash;
        forcePasswordChange = false;
        failedLoginCount = 0;
        lockedUntil = null;
    }
}
