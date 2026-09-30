package com.brainserve.appointment.iam.application;

import com.brainserve.appointment.audit.api.AuditService;
import com.brainserve.appointment.departmenthr.api.DepartmentHrDirectory;
import com.brainserve.appointment.employee.api.EmployeeDirectory;
import com.brainserve.appointment.essentiallog.api.EssentialLogService;
import com.brainserve.appointment.iam.api.AccountArchiveService;
import com.brainserve.appointment.iam.api.EmailService;
import com.brainserve.appointment.iam.domain.AccountClosureRequest;
import com.brainserve.appointment.iam.domain.AccountClosureStatus;
import com.brainserve.appointment.iam.domain.AccountLifecycleRecord;
import com.brainserve.appointment.iam.domain.AccountStatus;
import com.brainserve.appointment.iam.domain.ArchivedAccount;
import com.brainserve.appointment.iam.domain.SystemRole;
import com.brainserve.appointment.iam.domain.UserAccount;
import com.brainserve.appointment.iam.infrastructure.AccountClosureRequestRepository;
import com.brainserve.appointment.iam.infrastructure.AccountLifecycleRecordRepository;
import com.brainserve.appointment.iam.infrastructure.ArchivedAccountRepository;
import com.brainserve.appointment.iam.infrastructure.RefreshTokenSessionRepository;
import com.brainserve.appointment.iam.infrastructure.UserAccountRepository;
import com.brainserve.appointment.notification.api.InternalNotificationGateway;
import com.brainserve.appointment.organization.api.OrganizationDirectory;
import com.brainserve.appointment.shared.application.BusinessException;
import com.brainserve.appointment.teamlead.api.TeamLeadDirectory;
import com.brainserve.appointment.manager.api.ManagerDirectory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
public class AccountClosureService implements AccountArchiveService {
    private static final Set<AccountClosureStatus> OPEN = EnumSet.of(AccountClosureStatus.REQUESTED,
            AccountClosureStatus.BUSINESS_APPROVED, AccountClosureStatus.PENDING_SYSTEM_ADMIN,
            AccountClosureStatus.SCHEDULED);
    private static final Set<SystemRole> SELF_SERVICE_ROLES = EnumSet.of(
            SystemRole.ROLE_MANAGER, SystemRole.ROLE_HR_ADMIN, SystemRole.ROLE_TEAM_LEAD, SystemRole.ROLE_RECEPTIONIST,
            SystemRole.ROLE_SECURITY);

    private final AccountClosureRequestRepository requests;
    private final ArchivedAccountRepository archivedAccounts;
    private final AccountLifecycleRecordRepository lifecycle;
    private final UserAccountRepository users;
    private final RefreshTokenSessionRepository sessions;
    private final EmployeeDirectory employees;
    private final DepartmentHrDirectory departmentHrs;
    private final TeamLeadDirectory teamLeads;
    private final ManagerDirectory managers;
    private final OrganizationDirectory organization;
    private final InternalNotificationGateway notifications;
    private final EssentialLogService logs;
    private final AuditService audit;
    private final OperationalRoleTransitionService roleTransitions;
    private final PasswordEncoder passwordEncoder;
    private final StringRedisTemplate redis;
    private final EmailService emailService;
    private final int retentionYears;
    private final long otpMinutes;
    private final long otpResendSeconds;
    private final int otpMaxAttempts;
    private final long passwordLockMinutes;
    private final ZoneId officeZone;
    private final SecureRandom random = new SecureRandom();

    public AccountClosureService(AccountClosureRequestRepository requests,
                                 ArchivedAccountRepository archivedAccounts,
                                 AccountLifecycleRecordRepository lifecycle,
                                 UserAccountRepository users, RefreshTokenSessionRepository sessions,
                                 EmployeeDirectory employees, DepartmentHrDirectory departmentHrs,
                                 TeamLeadDirectory teamLeads, ManagerDirectory managers,
                                 OrganizationDirectory organization,
                                 InternalNotificationGateway notifications, EssentialLogService logs,
                                 AuditService audit, OperationalRoleTransitionService roleTransitions,
                                 PasswordEncoder passwordEncoder,
                                 StringRedisTemplate redis, EmailService emailService,
                                 @Value("${brainserve.identity.archive-retention-years:7}") int retentionYears,
                                 @Value("${brainserve.security.account-archive-otp-minutes:10}") long otpMinutes,
                                 @Value("${brainserve.security.account-archive-otp-resend-seconds:60}")
                                 long otpResendSeconds,
                                 @Value("${brainserve.security.account-archive-otp-max-attempts:5}")
                                 int otpMaxAttempts,
                                 @Value("${brainserve.security.admin-password-verification-lock-minutes:15}")
                                 long passwordLockMinutes,
                                 @Value("${brainserve.appointment.office-zone:Asia/Kolkata}") String officeZone) {
        this.requests = requests; this.archivedAccounts = archivedAccounts; this.lifecycle = lifecycle;
        this.users = users; this.sessions = sessions; this.employees = employees;
        this.departmentHrs = departmentHrs; this.teamLeads = teamLeads;
        this.managers = managers; this.organization = organization;
        this.notifications = notifications; this.logs = logs; this.audit = audit;
        this.roleTransitions = roleTransitions;
        this.passwordEncoder = passwordEncoder; this.redis = redis; this.emailService = emailService;
        this.retentionYears = retentionYears; this.otpMinutes = otpMinutes;
        this.otpResendSeconds = Math.max(30, otpResendSeconds);
        this.otpMaxAttempts = Math.max(3, otpMaxAttempts);
        this.passwordLockMinutes = Math.max(5, passwordLockMinutes);
        this.officeZone = ZoneId.of(officeZone);
    }

    @Transactional
    public View requestSelf(UUID actorUserId, String reason, LocalDate effectiveDate, UUID replacementUserId) {
        UserAccount target = requireActive(actorUserId);
        SystemRole role = singleRole(target);
        if (role == SystemRole.ROLE_SYSTEM_ADMIN) throw protectedAdmin();
        if (role == SystemRole.ROLE_EMPLOYEE) throw new BusinessException("EMPLOYEE_TERMINATION_WORKFLOW_REQUIRED",
                "Employee accounts are closed only through the HR to CEO termination workflow", HttpStatus.CONFLICT);
        if (!SELF_SERVICE_ROLES.contains(role)) throw forbidden("This account role cannot request self-service closure");
        validateNewRequest(target, reason, effectiveDate);
        UUID departmentId = departmentFor(target, role);
        if (replacementUserId != null) validateReplacement(role, target, departmentId, replacementUserId);
        AccountClosureRequest request = requests.saveAndFlush(new AccountClosureRequest(target.getId(), role,
                departmentId, actorUserId, AccountClosureRequest.Origin.SELF_SERVICE, requireText(reason),
                requireEffectiveDate(effectiveDate), replacementUserId));
        transition(request, null, AccountClosureStatus.REQUESTED, actorUserId, "Self-service account closure requested");
        notifications.notifyAccountClosureReview(actorUserId, role.name(), departmentId,
                target.getFullName() + " requested account closure effective " + effectiveDate
                        + ". Review it in Account lifecycle.");
        return view(request);
    }

    @Transactional(readOnly = true)
    public List<View> mine(UUID actorUserId) {
        requireUser(actorUserId);
        return requests.findTop100ByRequesterUserIdOrderByRequestedAtDesc(actorUserId).stream().map(this::view).toList();
    }

    @Transactional(readOnly = true)
    public List<View> businessPending(UUID actorUserId) {
        UserAccount actor = requireActive(actorUserId);
        if (!actor.getRoles().contains(SystemRole.ROLE_CEO)
                && !actor.getRoles().contains(SystemRole.ROLE_HR_ADMIN)) {
            throw forbidden("Only CEO or HR can review business account closure requests");
        }
        return requests.findTop200ByStatusInOrderByRequestedAtAsc(Set.of(AccountClosureStatus.REQUESTED)).stream()
                .filter(request -> canBusinessReview(actor, request)).map(this::view).toList();
    }

    @Transactional
    public View businessApprove(UUID actorUserId, UUID requestId, UUID replacementUserId, String note) {
        UserAccount actor = requireActive(actorUserId);
        AccountClosureRequest request = requireForUpdate(requestId);
        if (!canBusinessReview(actor, request)) throw forbidden("This closure request belongs to another approval route");
        UserAccount target = requireUser(request.getTargetUserId());
        UUID replacement = replacementUserId == null ? request.getReplacementUserId() : replacementUserId;
        if (replacement != null) validateReplacement(request.getTargetRole(), target, request.getDepartmentId(), replacement);
        request.setReplacementUserId(replacement);
        AccountClosureStatus from = request.getStatus();
        request.businessApprove(actorUserId, note);
        requests.saveAndFlush(request);
        transition(request, from, AccountClosureStatus.BUSINESS_APPROVED, actorUserId, "Business owner approved account closure");
        request.forwardToSystemAdmin();
        requests.saveAndFlush(request);
        transition(request, AccountClosureStatus.BUSINESS_APPROVED, AccountClosureStatus.PENDING_SYSTEM_ADMIN,
                actorUserId, "Account closure forwarded to System Admin");
        notifications.notifySystemAdminOfAccountClosure(actorUserId,
                target.getFullName() + " passed business closure review. Final System Admin action is required.");
        return view(request);
    }

    @Transactional
    public View businessReject(UUID actorUserId, UUID requestId, String note) {
        UserAccount actor = requireActive(actorUserId);
        AccountClosureRequest request = requireForUpdate(requestId);
        if (!canBusinessReview(actor, request)) throw forbidden("This closure request belongs to another approval route");
        AccountClosureStatus from = request.getStatus();
        request.rejectByBusiness(actorUserId, requireText(note));
        requests.saveAndFlush(request);
        transition(request, from, AccountClosureStatus.REJECTED, actorUserId, "Business owner rejected account closure");
        notifications.notifyAccountClosureDecision(actorUserId, request.getTargetUserId(),
                "Your account closure request was rejected: " + note.trim());
        return view(request);
    }

    @Transactional
    public View cancel(UUID actorUserId, UUID requestId) {
        AccountClosureRequest request = requireForUpdate(requestId);
        if (!request.getRequesterUserId().equals(actorUserId)) throw forbidden("Only the requester can cancel this closure");
        AccountClosureStatus from = request.getStatus();
        request.cancel();
        requests.saveAndFlush(request);
        transition(request, from, AccountClosureStatus.CANCELLED, actorUserId, "Requester cancelled account closure");
        return view(request);
    }

    @Transactional(readOnly = true)
    public List<View> systemAdminRequests(UUID actorUserId) {
        requireSystemAdmin(actorUserId);
        return requests.findTop500ByOrderByRequestedAtDesc().stream().map(this::view).toList();
    }

    @Transactional(readOnly = true)
    public Page<AccountView> activeAccounts(UUID actorUserId, String query, SystemRole role,
                                            UUID departmentId, Pageable pageable) {
        requireSystemAdmin(actorUserId);
        if (role == SystemRole.ROLE_EMPLOYEE && departmentId == null) {
            throw new BusinessException("EMPLOYEE_DEPARTMENT_REQUIRED",
                    "Select a department before loading employee accounts", HttpStatus.BAD_REQUEST);
        }
        if (departmentId != null && role != SystemRole.ROLE_EMPLOYEE) {
            throw new BusinessException("EMPLOYEE_DEPARTMENT_FILTER_INVALID",
                    "Department filtering is available when the Employee role is selected",
                    HttpStatus.BAD_REQUEST);
        }
        if (departmentId != null) organization.requireActiveDepartment(departmentId);
        String normalizedQuery = normalizeSearch(query);
        return users.findOperationalAccounts(AccountStatus.ACTIVE, normalizedQuery, role, departmentId, pageable)
                .map(this::accountView);
    }

    @Transactional(readOnly = true)
    public Page<ArchivedView> archivedAccounts(UUID actorUserId, String query, Pageable pageable) {
        requireSystemAdmin(actorUserId);
        String normalizedQuery = query == null || query.isBlank() ? null : query.trim();
        return archivedAccounts.search(normalizedQuery, pageable).map(ArchivedView::from);
    }

    @Transactional(readOnly = true)
    public List<LifecycleView> lifecycle(UUID actorUserId, UUID requestId) {
        requireSystemAdmin(actorUserId);
        requireRequest(requestId);
        return lifecycle.findAllByClosureRequestIdOrderByOccurredAtAsc(requestId).stream()
                .map(LifecycleView::from).toList();
    }

    @Transactional(readOnly = true)
    public List<Candidate> candidates(UUID actorUserId, UUID targetUserId) {
        UserAccount actor = requireActive(actorUserId);
        UserAccount target = requireActive(targetUserId);
        SystemRole targetRole = singleRole(target);
        UUID departmentId = departmentFor(target, targetRole);
        boolean authorized = actorUserId.equals(targetUserId) || actor.getRoles().contains(SystemRole.ROLE_SYSTEM_ADMIN)
                || requests.findTop100ByRequesterUserIdOrderByRequestedAtDesc(targetUserId).stream()
                .anyMatch(request -> canBusinessReview(actor, request));
        if (!authorized) throw forbidden("You cannot view replacement candidates for this account");
        return candidateAccounts(targetRole, target, departmentId).stream().map(this::candidate).toList();
    }

    @Transactional
    public View systemAdminApprove(UUID actorUserId, UUID requestId, UUID replacementUserId, String note) {
        requireSystemAdmin(actorUserId);
        AccountClosureRequest request = requireForUpdate(requestId);
        boolean ready = request.getTargetRole() == SystemRole.ROLE_CEO
                ? request.getStatus() == AccountClosureStatus.REQUESTED
                : request.getStatus() == AccountClosureStatus.PENDING_SYSTEM_ADMIN;
        if (!ready) {
            throw new BusinessException("ACCOUNT_CLOSURE_BUSINESS_APPROVAL_REQUIRED",
                    request.getStatus() == AccountClosureStatus.ARCHIVED
                            || request.getStatus() == AccountClosureStatus.REJECTED
                            || request.getStatus() == AccountClosureStatus.CANCELLED
                            ? "This account closure request has already been decided"
                            : "Business approval is required before System Admin can archive this account",
                    HttpStatus.CONFLICT);
        }
        UUID replacement = replacementUserId == null ? request.getReplacementUserId() : replacementUserId;
        UserAccount target = requireActive(request.getTargetUserId());
        validateReplacement(request.getTargetRole(), target, request.getDepartmentId(), replacement);
        request.setReplacementUserId(replacement);
        AccountClosureStatus from = request.getStatus();
        request.systemAdminApprove(actorUserId, note);
        if (request.getRequestedEffectiveDate().isAfter(today())) {
            request.schedule();
            requests.saveAndFlush(request);
            transition(request, from, AccountClosureStatus.SCHEDULED, actorUserId,
                    "System Admin approved and scheduled account archival");
            notifications.notifyAccountClosureDecision(actorUserId, target.getId(),
                    "Your account closure was approved and scheduled for " + request.getRequestedEffectiveDate() + ".");
            return view(request);
        }
        return archiveNow(request, actorUserId, from);
    }

    @Transactional
    public View systemAdminReject(UUID actorUserId, UUID requestId, String note) {
        requireSystemAdmin(actorUserId);
        AccountClosureRequest request = requireForUpdate(requestId);
        if (!OPEN.contains(request.getStatus())) throw alreadyDecided();
        AccountClosureStatus from = request.getStatus();
        request.rejectBySystemAdmin(actorUserId, requireText(note));
        requests.saveAndFlush(request);
        transition(request, from, AccountClosureStatus.REJECTED, actorUserId, "System Admin rejected account closure");
        notifications.notifyAccountClosureDecision(actorUserId, request.getTargetUserId(),
                "System Admin rejected your account closure request: " + note.trim());
        return view(request);
    }

    @Transactional
    public DirectArchiveChallengeView requestDirectArchiveOtp(UUID systemAdminUserId, UUID targetUserId,
                                                              String currentPassword, String reason,
                                                              UUID replacementUserId) {
        UserAccount admin = requireSystemAdminForUpdate(systemAdminUserId);
        if (activeRecoveryChallenge(systemAdminUserId) != null) {
            throw new BusinessException("ACCOUNT_LIFECYCLE_VERIFICATION_PENDING",
                    "Finish or cancel the active archived-account recovery first", HttpStatus.CONFLICT);
        }
        verifySystemAdminPassword(admin, currentPassword);
        UserAccount target = requireActive(targetUserId);
        ensureNotProtectedOrEmployee(target);
        validateNewRequest(target, reason, today());
        SystemRole role = singleRole(target);
        UUID departmentId = departmentFor(target, role);
        validateReplacement(role, target, departmentId, replacementUserId);

        clearActiveChallenge(systemAdminUserId);
        UUID challengeId = UUID.randomUUID();
        Instant createdAt = Instant.now();
        Instant expiresAt = createdAt.plusSeconds(otpMinutes * 60);
        Instant resendAvailableAt = createdAt.plusSeconds(otpResendSeconds);
        String otp = String.format("%06d", random.nextInt(1_000_000));
        ArchiveChallenge challenge = new ArchiveChallenge(challengeId, systemAdminUserId, targetUserId, role,
                departmentId, requireText(reason), replacementUserId, createdAt, expiresAt,
                resendAvailableAt, 0, hash(otp));
        storeChallenge(challenge);
        try {
            emailService.sendAccountArchiveOtp(admin.getEmail(), admin.getFullName(), target.getFullName(),
                    target.getEmail(), otp, expiresAt);
        } catch (RuntimeException ex) {
            clearChallenge(challenge);
            throw ex;
        }
        audit.record("ACCOUNT_ARCHIVE_OTP_REQUESTED", "USER_ACCOUNT", targetUserId.toString(),
                "{\"challengeId\":\"" + challengeId + "\",\"expiresAt\":\"" + expiresAt + "\"}");
        logs.record("ACCOUNT_LIFECYCLE", "ACCOUNT_ARCHIVE_OTP_REQUESTED", "USER_ACCOUNT",
                targetUserId.toString(), challengeId.toString(), systemAdminUserId, systemAdminUserId,
                "PENDING_OTP", "Account archive verification started",
                "System Admin password verified; a short-lived mailbox challenge was issued");
        return challengeView(challenge, target);
    }

    @Transactional(readOnly = true)
    public DirectArchiveChallengeView activeDirectArchiveChallenge(UUID systemAdminUserId) {
        requireSystemAdmin(systemAdminUserId);
        ArchiveChallenge challenge = activeChallenge(systemAdminUserId);
        if (challenge == null) return null;
        UserAccount target = users.findById(challenge.targetUserId()).orElse(null);
        if (target == null || !target.isEnabled() || target.isArchived()) {
            clearChallenge(challenge);
            return null;
        }
        return challengeView(challenge, target);
    }

    @Transactional
    public DirectArchiveChallengeView resendDirectArchiveOtp(UUID systemAdminUserId, UUID challengeId) {
        UserAccount admin = requireSystemAdmin(systemAdminUserId);
        ArchiveChallenge challenge = requireChallenge(systemAdminUserId, challengeId);
        Instant now = Instant.now();
        if (now.isBefore(challenge.resendAvailableAt())) throw new BusinessException(
                "ACCOUNT_ARCHIVE_OTP_RESEND_COOLDOWN",
                "Wait before requesting another confirmation code", HttpStatus.TOO_MANY_REQUESTS);
        UserAccount target = requireActive(challenge.targetUserId());
        ensureChallengeStillMatches(challenge, target);
        String otp = String.format("%06d", random.nextInt(1_000_000));
        ArchiveChallenge refreshed = new ArchiveChallenge(challenge.challengeId(), challenge.adminUserId(),
                challenge.targetUserId(), challenge.targetRole(), challenge.departmentId(), challenge.reason(),
                challenge.replacementUserId(), challenge.createdAt(), now.plusSeconds(otpMinutes * 60),
                now.plusSeconds(otpResendSeconds), 0, hash(otp));
        storeChallenge(refreshed);
        try {
            emailService.sendAccountArchiveOtp(admin.getEmail(), admin.getFullName(), target.getFullName(),
                    target.getEmail(), otp, refreshed.expiresAt());
        } catch (RuntimeException ex) {
            storeChallenge(challenge);
            throw ex;
        }
        audit.record("ACCOUNT_ARCHIVE_OTP_RESENT", "USER_ACCOUNT", target.getId().toString(),
                "{\"challengeId\":\"" + challengeId + "\"}");
        logs.record("ACCOUNT_LIFECYCLE", "ACCOUNT_ARCHIVE_OTP_RESENT", "USER_ACCOUNT",
                target.getId().toString(), challengeId.toString(), systemAdminUserId, systemAdminUserId,
                "PENDING_OTP", "Account archive confirmation code resent",
                "The active verification challenge was renewed without retaining the System Admin password");
        return challengeView(refreshed, target);
    }

    @Transactional
    public void cancelDirectArchiveChallenge(UUID systemAdminUserId, UUID challengeId) {
        requireSystemAdmin(systemAdminUserId);
        ArchiveChallenge challenge = requireChallenge(systemAdminUserId, challengeId);
        clearChallenge(challenge);
        audit.record("ACCOUNT_ARCHIVE_CHALLENGE_CANCELLED", "USER_ACCOUNT",
                challenge.targetUserId().toString(), "{\"challengeId\":\"" + challengeId + "\"}");
        logs.record("ACCOUNT_LIFECYCLE", "ACCOUNT_ARCHIVE_CHALLENGE_CANCELLED", "USER_ACCOUNT",
                challenge.targetUserId().toString(), challengeId.toString(), systemAdminUserId, systemAdminUserId,
                "CANCELLED", "Account archive verification cancelled",
                "System Admin cancelled the short-lived verification challenge before archival");
    }

    @Transactional
    public View directArchive(UUID systemAdminUserId, UUID challengeId, String otp) {
        requireSystemAdmin(systemAdminUserId);
        ArchiveChallenge challenge = requireChallenge(systemAdminUserId, challengeId);
        verifyChallengeOtp(challenge, otp);
        String processingKey = processingKey(challengeId);
        if (!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(processingKey, systemAdminUserId.toString(),
                Duration.ofSeconds(30)))) {
            throw new BusinessException("ACCOUNT_ARCHIVE_ALREADY_PROCESSING",
                    "This archive verification is already being processed", HttpStatus.CONFLICT);
        }
        try {
            UserAccount target = requireActiveForUpdate(challenge.targetUserId());
            ensureNotProtectedOrEmployee(target);
            ensureChallengeStillMatches(challenge, target);
            validateNewRequest(target, challenge.reason(), today());
            validateReplacement(challenge.targetRole(), target, challenge.departmentId(),
                    challenge.replacementUserId());
            AccountClosureRequest request = requests.saveAndFlush(new AccountClosureRequest(target.getId(),
                    challenge.targetRole(), challenge.departmentId(), systemAdminUserId,
                    AccountClosureRequest.Origin.SYSTEM_ADMIN_EMERGENCY, challenge.reason(), today(),
                    challenge.replacementUserId()));
            transition(request, null, AccountClosureStatus.REQUESTED, systemAdminUserId,
                    "System Admin initiated emergency account closure with password and OTP confirmation");
            request.systemAdminApprove(systemAdminUserId, "Emergency direct archive");
            View archived = archiveNow(request, systemAdminUserId, AccountClosureStatus.REQUESTED);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { clearChallenge(challenge); }
                @Override public void afterCompletion(int status) { redis.delete(processingKey); }
            });
            return archived;
        } catch (RuntimeException ex) {
            redis.delete(processingKey);
            throw ex;
        }
    }

    @Transactional
    public ArchivedRecoveryChallengeView requestArchivedRecoveryOtp(
            UUID systemAdminUserId, UUID archivedAccountId, SystemRole targetRole,
            UUID departmentId, String currentPassword, String reason) {
        UserAccount admin = requireSystemAdminForUpdate(systemAdminUserId);
        if (activeChallenge(systemAdminUserId) != null) {
            throw new BusinessException("ACCOUNT_LIFECYCLE_VERIFICATION_PENDING",
                    "Finish or cancel the active account archive verification first", HttpStatus.CONFLICT);
        }
        verifySystemAdminPassword(admin, currentPassword);
        ArchivedAccount archived = archivedAccounts.findForUpdateById(archivedAccountId)
                .orElseThrow(() -> new BusinessException("ARCHIVED_ACCOUNT_NOT_FOUND",
                        "The archived account record was not found", HttpStatus.NOT_FOUND));
        ensureArchiveRecoverable(archived);
        UserAccount target = requireUser(archived.getOriginalUserId());
        roleTransitions.validateArchivedRecovery(systemAdminUserId, target.getId(), targetRole, departmentId);
        String normalizedReason = requireText(reason);

        clearActiveRecoveryChallenge(systemAdminUserId);
        UUID challengeId = UUID.randomUUID();
        Instant createdAt = Instant.now();
        Instant expiresAt = createdAt.plusSeconds(otpMinutes * 60);
        Instant resendAvailableAt = createdAt.plusSeconds(otpResendSeconds);
        String otp = String.format("%06d", random.nextInt(1_000_000));
        RecoveryChallenge challenge = new RecoveryChallenge(challengeId, systemAdminUserId,
                archivedAccountId, target.getId(), singleRole(target), archived.getDepartmentIdSnapshot(),
                targetRole, departmentId, normalizedReason, createdAt, expiresAt,
                resendAvailableAt, 0, hash(otp));
        storeRecoveryChallenge(challenge);
        try {
            emailService.sendArchivedAccountRecoveryOtp(admin.getEmail(), admin.getFullName(),
                    target.getFullName(), target.getEmail(), targetRole.name(), otp, expiresAt);
        } catch (RuntimeException exception) {
            clearRecoveryChallenge(challenge);
            throw exception;
        }
        audit.record("ARCHIVED_ACCOUNT_RECOVERY_OTP_REQUESTED", "USER_ACCOUNT", target.getId().toString(),
                "{\"challengeId\":\"" + challengeId + "\",\"targetRole\":\"" + targetRole
                        + "\",\"expiresAt\":\"" + expiresAt + "\"}");
        logs.record("ACCOUNT_LIFECYCLE", "ARCHIVED_ACCOUNT_RECOVERY_OTP_REQUESTED", "USER_ACCOUNT",
                target.getId().toString(), archived.getClosureRequestId().toString(), systemAdminUserId,
                systemAdminUserId, "PENDING_OTP", "Archived account recovery verification started",
                "System Admin password verified; the recovery role and department were frozen in a short-lived challenge");
        return recoveryChallengeView(challenge, target);
    }

    @Transactional(readOnly = true)
    public ArchivedRecoveryChallengeView activeArchivedRecoveryChallenge(UUID systemAdminUserId) {
        requireSystemAdmin(systemAdminUserId);
        RecoveryChallenge challenge = activeRecoveryChallenge(systemAdminUserId);
        if (challenge == null) return null;
        ArchivedAccount archived = archivedAccounts.findById(challenge.archivedAccountId()).orElse(null);
        UserAccount target = users.findById(challenge.targetUserId()).orElse(null);
        if (archived == null || target == null || archived.isRecovered()
                || !target.isArchived() || target.isEnabled()) {
            clearRecoveryChallenge(challenge);
            return null;
        }
        return recoveryChallengeView(challenge, target);
    }

    @Transactional
    public ArchivedRecoveryChallengeView resendArchivedRecoveryOtp(UUID systemAdminUserId, UUID challengeId) {
        UserAccount admin = requireSystemAdmin(systemAdminUserId);
        RecoveryChallenge challenge = requireRecoveryChallenge(systemAdminUserId, challengeId);
        Instant now = Instant.now();
        if (now.isBefore(challenge.resendAvailableAt())) throw new BusinessException(
                "ACCOUNT_RECOVERY_OTP_RESEND_COOLDOWN",
                "Wait before requesting another recovery code", HttpStatus.TOO_MANY_REQUESTS);
        ArchivedAccount archived = archivedAccounts.findById(challenge.archivedAccountId())
                .orElseThrow(() -> new BusinessException("ARCHIVED_ACCOUNT_NOT_FOUND",
                        "The archived account record was not found", HttpStatus.NOT_FOUND));
        UserAccount target = requireUser(challenge.targetUserId());
        ensureRecoveryChallengeStillMatches(challenge, archived, target);
        String otp = String.format("%06d", random.nextInt(1_000_000));
        RecoveryChallenge refreshed = new RecoveryChallenge(challenge.challengeId(), challenge.adminUserId(),
                challenge.archivedAccountId(), challenge.targetUserId(), challenge.previousRole(),
                challenge.previousDepartmentId(), challenge.targetRole(), challenge.targetDepartmentId(),
                challenge.reason(), challenge.createdAt(), now.plusSeconds(otpMinutes * 60),
                now.plusSeconds(otpResendSeconds), 0, hash(otp));
        storeRecoveryChallenge(refreshed);
        try {
            emailService.sendArchivedAccountRecoveryOtp(admin.getEmail(), admin.getFullName(),
                    target.getFullName(), target.getEmail(), challenge.targetRole().name(),
                    otp, refreshed.expiresAt());
        } catch (RuntimeException exception) {
            storeRecoveryChallenge(challenge);
            throw exception;
        }
        return recoveryChallengeView(refreshed, target);
    }

    @Transactional
    public void cancelArchivedRecoveryChallenge(UUID systemAdminUserId, UUID challengeId) {
        requireSystemAdmin(systemAdminUserId);
        RecoveryChallenge challenge = requireRecoveryChallenge(systemAdminUserId, challengeId);
        clearRecoveryChallenge(challenge);
        audit.record("ARCHIVED_ACCOUNT_RECOVERY_CANCELLED", "USER_ACCOUNT",
                challenge.targetUserId().toString(), "{\"challengeId\":\"" + challengeId + "\"}");
    }

    @Transactional
    public RecoveredAccountView recoverArchivedAccount(UUID systemAdminUserId, UUID challengeId, String otp) {
        requireSystemAdmin(systemAdminUserId);
        RecoveryChallenge challenge = requireRecoveryChallenge(systemAdminUserId, challengeId);
        verifyRecoveryChallengeOtp(challenge, otp);
        String processingKey = recoveryProcessingKey(challengeId);
        if (!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(processingKey, systemAdminUserId.toString(),
                Duration.ofSeconds(30)))) {
            throw new BusinessException("ACCOUNT_RECOVERY_ALREADY_PROCESSING",
                    "This recovery verification is already being processed", HttpStatus.CONFLICT);
        }
        try {
            ArchivedAccount archived = archivedAccounts.findForUpdateById(challenge.archivedAccountId())
                    .orElseThrow(() -> new BusinessException("ARCHIVED_ACCOUNT_NOT_FOUND",
                            "The archived account record was not found", HttpStatus.NOT_FOUND));
            UserAccount target = requireUser(challenge.targetUserId());
            ensureRecoveryChallengeStillMatches(challenge, archived, target);
            OperationalRoleTransitionService.RecoveryResult result = roleTransitions.recoverArchived(
                    systemAdminUserId, target.getId(), challenge.targetRole(),
                    challenge.targetDepartmentId(), challenge.reason());
            archived.markRecovered(systemAdminUserId, challenge.targetRole().name(),
                    challenge.targetDepartmentId(), challenge.reason(), result.changedAt());
            archivedAccounts.saveAndFlush(archived);
            lifecycle.saveAndFlush(new AccountLifecycleRecord(archived.getClosureRequestId(), target.getId(),
                    "ACCOUNT_RECOVERED", "ARCHIVED", "ACTIVE", systemAdminUserId,
                    "Recovered with the same user and employee IDs; " + (result.roleChanged()
                            ? "the single current role was replaced" : "the existing role was retained")
                            + (result.departmentChanged() ? " and the employee department was updated"
                            : " and the employee department was retained")));
            logs.record("ACCOUNT_LIFECYCLE", "ARCHIVED_ACCOUNT_RECOVERED", "USER_ACCOUNT",
                    target.getId().toString(), archived.getClosureRequestId().toString(),
                    systemAdminUserId, systemAdminUserId, "ACTIVE",
                    "Archived account recovered",
                    "Current role " + challenge.targetRole() + "; current department "
                            + (challenge.targetDepartmentId() == null ? "company-wide"
                            : challenge.targetDepartmentId()) + "; old sessions revoked");
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { clearRecoveryChallenge(challenge); }
                @Override public void afterCompletion(int status) { redis.delete(processingKey); }
            });
            return new RecoveredAccountView(result.userId(), result.employeeId(), result.previousRole(),
                    result.role(), result.previousDepartmentId(), result.departmentId(),
                    result.roleChanged(), result.departmentChanged(), result.changedAt());
        } catch (RuntimeException exception) {
            redis.delete(processingKey);
            throw exception;
        }
    }

    @Scheduled(cron = "${brainserve.identity.account-closure-schedule:0 5 0 * * *}",
            zone = "${brainserve.appointment.office-zone:Asia/Kolkata}")
    @Transactional
    public void archiveScheduledAccounts() {
        requests.findAllByStatusAndRequestedEffectiveDateLessThanEqual(AccountClosureStatus.SCHEDULED, today())
                .forEach(request -> archiveNow(request, request.getSystemAdminApproverUserId(), AccountClosureStatus.SCHEDULED));
    }

    @Override
    @Transactional
    public void archiveAfterEmployeeTermination(UUID hrUserId, UUID ceoUserId, UUID employeeId,
                                                UUID terminationRequestId, String reason, LocalDate effectiveDate) {
        UserAccount target = users.findByEmployeeId(employeeId).orElse(null);
        if (target == null || target.isArchived()) return;
        SystemRole role = singleRole(target);
        AccountClosureRequest closure = requests.saveAndFlush(new AccountClosureRequest(target.getId(), role,
                employees.employeeSummary(employeeId).departmentId(), hrUserId,
                AccountClosureRequest.Origin.EMPLOYEE_TERMINATION, requireText(reason), effectiveDate, null));
        transition(closure, null, AccountClosureStatus.REQUESTED, hrUserId,
                "Employee termination created an automatic account archive request " + terminationRequestId);
        archiveNow(closure, ceoUserId, AccountClosureStatus.REQUESTED);
    }

    private View archiveNow(AccountClosureRequest request, UUID actorUserId, AccountClosureStatus from) {
        UserAccount target = requireUser(request.getTargetUserId());
        if (target.isArchived()) throw new BusinessException("ACCOUNT_ALREADY_ARCHIVED",
                "This account is already archived", HttpStatus.CONFLICT);
        if (request.getOrigin() != AccountClosureRequest.Origin.EMPLOYEE_TERMINATION) {
            applyReplacement(request, target, actorUserId);
        }
        notifications.notifyAccountClosureDecision(actorUserId, target.getId(),
                "Your BrainServe Connect account is now deactivated and archived. Contact System Admin for lifecycle records.");
        Snapshot snapshot = snapshot(target, request.getDepartmentId());
        Instant archivedAt = Instant.now();
        String previousStatus = target.getStatus().name();
        target.archive(request.getReason(), archivedAt);
        if (target.getEmployeeId() != null) {
            employees.deactivateForAccountArchive(target.getEmployeeId());
        }
        sessions.revokeAllForUser(target.getId(), archivedAt);
        archivedAccounts.saveAndFlush(new ArchivedAccount(target.getId(), target.getFullName(), target.getEmail(),
                request.getTargetRole().name(), snapshot.departmentId(), snapshot.departmentName(),
                target.getEmployeeId(), snapshot.employeeNumber(), previousStatus, request.getReason(),
                request.getId(), actorUserId, archivedAt, today().plusYears(retentionYears)));
        request.archive(archivedAt);
        requests.saveAndFlush(request);
        transition(request, from, AccountClosureStatus.ARCHIVED, actorUserId,
                "Login disabled, sessions revoked and immutable account snapshot retained");
        return view(request);
    }

    private void applyReplacement(AccountClosureRequest request, UserAccount target, UUID actorUserId) {
        UUID replacementUserId = request.getReplacementUserId();
        validateReplacement(request.getTargetRole(), target, request.getDepartmentId(), replacementUserId);
        UserAccount replacement = requireActive(replacementUserId);
        if (request.getTargetRole() == SystemRole.ROLE_HR_ADMIN) {
            departmentHrs.replaceForAccountClosure(actorUserId, target.getId(), replacement.getId());
        } else if (request.getTargetRole() == SystemRole.ROLE_MANAGER) {
            managers.replaceForAccountClosure(actorUserId, target.getId(), replacement.getId());
        } else if (request.getTargetRole() == SystemRole.ROLE_TEAM_LEAD) {
            teamLeads.replaceForAccountClosure(actorUserId, target.getId(), replacement.getEmployeeId());
        }
    }

    private void validateReplacement(SystemRole targetRole, UserAccount target, UUID departmentId,
                                     UUID replacementUserId) {
        if (replacementUserId == null && (targetRole == SystemRole.ROLE_RECEPTIONIST
                || targetRole == SystemRole.ROLE_SECURITY)) return;
        if (replacementUserId == null) throw new BusinessException("ACCOUNT_CLOSURE_REPLACEMENT_REQUIRED",
                "Select an active replacement before this account can be archived", HttpStatus.UNPROCESSABLE_ENTITY);
        UserAccount replacement = requireActive(replacementUserId);
        if (target.getId().equals(replacement.getId())) throw new BusinessException(
                "ACCOUNT_CLOSURE_REPLACEMENT_REQUIRED", "The replacement must be a different account",
                HttpStatus.UNPROCESSABLE_ENTITY);
        if (targetRole == SystemRole.ROLE_TEAM_LEAD) {
            if (!replacement.getRoles().equals(Set.of(SystemRole.ROLE_EMPLOYEE)) || replacement.getEmployeeId() == null
                    || !employees.departmentIdForEmployee(replacement.getEmployeeId()).equals(departmentId)) {
                throw new BusinessException("TEAM_LEAD_REPLACEMENT_INVALID",
                        "Choose an active Employee from the same department as replacement Team Lead",
                        HttpStatus.UNPROCESSABLE_ENTITY);
            }
            employees.requireActiveEmployee(replacement.getEmployeeId());
            return;
        }
        if (targetRole == SystemRole.ROLE_HR_ADMIN && departmentHrs.activeForUser(replacement.getId()).isPresent()) {
            throw new BusinessException("HR_REPLACEMENT_ALREADY_ASSIGNED",
                    "Choose an active unassigned HR Admin so another department is not orphaned",
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }
        if (targetRole == SystemRole.ROLE_MANAGER && managers.activeForUser(replacement.getId()).isPresent()) {
            throw new BusinessException("MANAGER_REPLACEMENT_ALREADY_ASSIGNED",
                    "Choose an active unassigned Manager so another department is not orphaned",
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }
        if (!replacement.getRoles().contains(targetRole)) throw new BusinessException(
                "ACCOUNT_CLOSURE_REPLACEMENT_ROLE_MISMATCH",
                "The replacement account must hold the same operational role", HttpStatus.UNPROCESSABLE_ENTITY);
    }

    private List<UserAccount> candidateAccounts(SystemRole role, UserAccount target, UUID departmentId) {
        SystemRole candidateRole = role == SystemRole.ROLE_TEAM_LEAD ? SystemRole.ROLE_EMPLOYEE : role;
        UUID candidateDepartmentId = role == SystemRole.ROLE_TEAM_LEAD ? departmentId : null;
        Page<UserAccount> page = users.findOperationalAccounts(AccountStatus.ACTIVE, "", candidateRole,
                candidateDepartmentId, PageRequest.of(0, 100, Sort.by(Sort.Direction.ASC, "fullName")));
        return page.getContent().stream().filter(user -> !user.getId().equals(target.getId()))
                .filter(user -> role == SystemRole.ROLE_TEAM_LEAD
                        ? user.getRoles().equals(Set.of(SystemRole.ROLE_EMPLOYEE)) && user.getEmployeeId() != null
                          && employees.departmentIdForEmployee(user.getEmployeeId()).equals(departmentId)
                        : user.getRoles().contains(role))
                .filter(user -> role != SystemRole.ROLE_HR_ADMIN
                        || departmentHrs.activeForUser(user.getId()).isEmpty())
                .filter(user -> role != SystemRole.ROLE_MANAGER
                        || managers.activeForUser(user.getId()).isEmpty())
                .toList();
    }

    private String normalizeSearch(String query) {
        return query == null || query.isBlank()
                ? ""
                : query.trim().toLowerCase(Locale.ROOT);
    }

    private boolean canBusinessReview(UserAccount actor, AccountClosureRequest request) {
        if (request.getStatus() != AccountClosureStatus.REQUESTED) return false;
        if (request.getTargetRole() == SystemRole.ROLE_HR_ADMIN) {
            return actor.getRoles().contains(SystemRole.ROLE_CEO);
        }
        if (request.getTargetRole() == SystemRole.ROLE_MANAGER) {
            return actor.getRoles().contains(SystemRole.ROLE_CEO);
        }
        if (request.getTargetRole() == SystemRole.ROLE_TEAM_LEAD) {
            if (!actor.getRoles().contains(SystemRole.ROLE_HR_ADMIN) || request.getDepartmentId() == null) return false;
            return departmentHrs.requireForUser(actor.getId()).departmentId().equals(request.getDepartmentId());
        }
        return actor.getRoles().contains(SystemRole.ROLE_HR_ADMIN)
                && (request.getTargetRole() == SystemRole.ROLE_RECEPTIONIST
                || request.getTargetRole() == SystemRole.ROLE_SECURITY);
    }

    private UUID departmentFor(UserAccount target, SystemRole role) {
        if (role == SystemRole.ROLE_HR_ADMIN) return departmentHrs.requireForUser(target.getId()).departmentId();
        if (role == SystemRole.ROLE_MANAGER) return managers.requireForUser(target.getId()).departmentId();
        if (role == SystemRole.ROLE_TEAM_LEAD) return teamLeads.requireForUser(target.getId()).departmentId();
        if (target.getEmployeeId() != null) return employees.employeeSummary(target.getEmployeeId()).departmentId();
        return null;
    }

    private Snapshot snapshot(UserAccount target, UUID fallbackDepartmentId) {
        UUID departmentId = fallbackDepartmentId;
        String employeeNumber = null;
        if (target.getEmployeeId() != null) {
            var employee = employees.employeeSummary(target.getEmployeeId());
            departmentId = employee.departmentId(); employeeNumber = employee.employeeNumber();
        }
        String departmentName = departmentId == null ? null
                : organization.findDepartment(departmentId).map(OrganizationDirectory.DepartmentSummary::name).orElse(null);
        return new Snapshot(departmentId, departmentName, employeeNumber);
    }

    private void transition(AccountClosureRequest request, AccountClosureStatus from, AccountClosureStatus to,
                            UUID actorUserId, String detail) {
        lifecycle.save(new AccountLifecycleRecord(request.getId(), request.getTargetUserId(),
                "ACCOUNT_CLOSURE_" + to.name(), from == null ? null : from.name(), to.name(), actorUserId, detail));
        audit.record("ACCOUNT_CLOSURE_" + to.name(), "USER_ACCOUNT", request.getTargetUserId().toString(),
                "{\"closureRequestId\":\"" + request.getId() + "\",\"status\":\"" + to + "\"}");
        logs.record("ACCOUNT_LIFECYCLE", "ACCOUNT_CLOSURE_" + to.name(), "USER_ACCOUNT",
                request.getTargetUserId().toString(), request.getId().toString(), request.getRequesterUserId(),
                actorUserId, to.name(), "Account closure " + to.name().replace('_', ' ').toLowerCase(), detail);
    }

    private View view(AccountClosureRequest request) {
        UserAccount target = requireUser(request.getTargetUserId());
        UserAccount replacement = request.getReplacementUserId() == null ? null
                : users.findById(request.getReplacementUserId()).orElse(null);
        String departmentName = request.getDepartmentId() == null ? null
                : organization.findDepartment(request.getDepartmentId())
                .map(OrganizationDirectory.DepartmentSummary::name).orElse(null);
        return new View(request.getId(), target.getId(), target.getFullName(), target.getEmail(),
                request.getTargetRole(), target.getEmployeeId(), request.getDepartmentId(), departmentName,
                request.getRequesterUserId(), request.getOrigin(), request.getReason(),
                request.getRequestedEffectiveDate(), request.getReplacementUserId(),
                replacement == null ? null : replacement.getFullName(), request.getStatus(), request.getRequestedAt(),
                request.getBusinessApproverUserId(), request.getBusinessApprovedAt(),
                request.getSystemAdminApproverUserId(), request.getSystemAdminApprovedAt(),
                request.getDecisionNote(), request.getScheduledAt(), request.getArchivedAt(), request.getCancelledAt());
    }

    private AccountView accountView(UserAccount user) {
        SystemRole role = singleRole(user);
        UUID departmentId;
        try { departmentId = departmentFor(user, role); } catch (BusinessException ex) { departmentId = null; }
        String departmentName = departmentId == null ? null : organization.findDepartment(departmentId)
                .map(OrganizationDirectory.DepartmentSummary::name).orElse(null);
        return new AccountView(user.getId(), user.getFullName(), user.getEmail(), role, user.getStatus().name(),
                user.isEnabled(), user.isArchived(), user.getEmployeeId(), departmentId, departmentName,
                role == SystemRole.ROLE_SYSTEM_ADMIN || role == SystemRole.ROLE_CEO);
    }

    private Candidate candidate(UserAccount user) {
        SystemRole role = singleRole(user);
        UUID departmentId;
        try { departmentId = departmentFor(user, role); } catch (BusinessException ex) { departmentId = null; }
        return new Candidate(user.getId(), user.getFullName(), user.getEmail(), role, user.getEmployeeId(), departmentId);
    }

    private void validateNewRequest(UserAccount target, String reason, LocalDate effectiveDate) {
        if (target.isArchived()) throw new BusinessException("ACCOUNT_ALREADY_ARCHIVED", "This account is already archived",
                HttpStatus.CONFLICT);
        if (requests.existsByTargetUserIdAndStatusIn(target.getId(), OPEN)) throw new BusinessException(
                "ACCOUNT_CLOSURE_ALREADY_PENDING", "This account already has an open closure request",
                HttpStatus.CONFLICT);
        requireText(reason); requireEffectiveDate(effectiveDate);
    }

    private void ensureNotProtectedOrEmployee(UserAccount target) {
        SystemRole role = singleRole(target);
        if (role == SystemRole.ROLE_SYSTEM_ADMIN) throw protectedAdmin();
        if (role == SystemRole.ROLE_CEO) throw new BusinessException("CEO_SUCCESSION_REQUIRED",
                "The single active CEO cannot be archived through general account lifecycle. Use a governed CEO succession",
                HttpStatus.CONFLICT);
        if (role == SystemRole.ROLE_EMPLOYEE) throw new BusinessException("EMPLOYEE_TERMINATION_WORKFLOW_REQUIRED",
                "Use the HR to CEO employee termination workflow", HttpStatus.CONFLICT);
    }

    private UserAccount requireSystemAdmin(UUID actorUserId) {
        UserAccount actor = requireActive(actorUserId);
        if (!actor.getRoles().contains(SystemRole.ROLE_SYSTEM_ADMIN)) throw forbidden("System Admin access is required");
        return actor;
    }
    private UserAccount requireSystemAdminForUpdate(UUID actorUserId) {
        UserAccount actor = requireActiveForUpdate(actorUserId);
        if (!actor.getRoles().equals(Set.of(SystemRole.ROLE_SYSTEM_ADMIN))) {
            throw forbidden("System Admin access is required");
        }
        return actor;
    }
    private UserAccount requireActive(UUID userId) {
        UserAccount user = requireUser(userId);
        if (!user.isEnabled()) throw new BusinessException("ACCOUNT_NOT_ACTIVE", "The account is not active",
                HttpStatus.UNPROCESSABLE_ENTITY);
        return user;
    }
    private UserAccount requireActiveForUpdate(UUID userId) {
        UserAccount user = users.findByIdForUpdate(userId).orElseThrow(() ->
                new BusinessException("USER_NOT_FOUND", "User account was not found", HttpStatus.NOT_FOUND));
        if (!user.isEnabled()) throw new BusinessException("ACCOUNT_NOT_ACTIVE", "The account is not active",
                HttpStatus.UNPROCESSABLE_ENTITY);
        return user;
    }
    private UserAccount requireUser(UUID userId) { return users.findById(userId).orElseThrow(() ->
            new BusinessException("USER_NOT_FOUND", "User account was not found", HttpStatus.NOT_FOUND)); }
    private AccountClosureRequest requireForUpdate(UUID id) { return requests.findForUpdateById(id).orElseThrow(() ->
            new BusinessException("ACCOUNT_CLOSURE_NOT_FOUND", "Account closure request was not found", HttpStatus.NOT_FOUND)); }
    private AccountClosureRequest requireRequest(UUID id) { return requests.findById(id).orElseThrow(() ->
            new BusinessException("ACCOUNT_CLOSURE_NOT_FOUND", "Account closure request was not found", HttpStatus.NOT_FOUND)); }
    private SystemRole singleRole(UserAccount user) {
        if (user.getRoles().size() != 1) throw new BusinessException("ACCOUNT_ROLE_INVALID",
                "Account closure requires exactly one assigned role", HttpStatus.CONFLICT);
        return user.getRoles().iterator().next();
    }
    private String requireText(String value) {
        String normalized = value == null ? "" : value.trim().replaceAll("\\s+", " ");
        if (normalized.length() < 5 || normalized.length() > 1000) throw new BusinessException(
                "INVALID_ACCOUNT_CLOSURE_REASON", "Provide a reason containing 5 to 1000 characters",
                HttpStatus.UNPROCESSABLE_ENTITY);
        return normalized;
    }
    private LocalDate requireEffectiveDate(LocalDate value) {
        if (value == null || value.isBefore(today())) throw new BusinessException(
                "INVALID_ACCOUNT_CLOSURE_DATE", "The effective date must be today or a future date",
                HttpStatus.UNPROCESSABLE_ENTITY);
        return value;
    }
    private void verifySystemAdminPassword(UserAccount admin, String currentPassword) {
        String key = passwordFailureKey(admin.getId());
        long failures = parseLong(redis.opsForValue().get(key), 0);
        if (failures >= 5) throw new BusinessException("SYSTEM_ADMIN_VERIFICATION_LOCKED",
                "Too many incorrect password attempts. Try again later", HttpStatus.TOO_MANY_REQUESTS);
        if (currentPassword == null || !passwordEncoder.matches(currentPassword, admin.getPasswordHash())) {
            Long next = redis.opsForValue().increment(key);
            if (next != null && next == 1) redis.expire(key, Duration.ofMinutes(passwordLockMinutes));
            throw new BusinessException("INVALID_CREDENTIALS",
                    next != null && next >= 5
                            ? "Too many incorrect password attempts. Try again later"
                            : "Current System Admin password is incorrect",
                    next != null && next >= 5 ? HttpStatus.TOO_MANY_REQUESTS : HttpStatus.UNAUTHORIZED);
        }
        redis.delete(key);
    }

    private void verifyChallengeOtp(ArchiveChallenge challenge, String otp) {
        if (otp == null || !MessageDigest.isEqual(challenge.otpHash().getBytes(StandardCharsets.UTF_8),
                hash(otp.trim()).getBytes(StandardCharsets.UTF_8))) {
            Long failures = redis.opsForHash().increment(challengeKey(challenge.challengeId()), "failedAttempts", 1);
            int remaining = Math.max(0, otpMaxAttempts - (failures == null ? otpMaxAttempts : failures.intValue()));
            if (remaining == 0) {
                clearChallenge(challenge);
                throw new BusinessException("ACCOUNT_ARCHIVE_OTP_ATTEMPTS_EXHAUSTED",
                        "The confirmation challenge was cancelled after too many incorrect codes",
                        HttpStatus.TOO_MANY_REQUESTS);
            }
            throw new BusinessException("INVALID_OTP",
                    "The confirmation code is incorrect. " + remaining + " attempts remain",
                    HttpStatus.UNAUTHORIZED);
        }
    }

    private void ensureChallengeStillMatches(ArchiveChallenge challenge, UserAccount target) {
        SystemRole currentRole = singleRole(target);
        UUID currentDepartmentId = departmentFor(target, currentRole);
        if (currentRole != challenge.targetRole()
                || !Objects.equals(currentDepartmentId, challenge.departmentId())) {
            clearChallenge(challenge);
            throw new BusinessException("ACCOUNT_ARCHIVE_CHALLENGE_STALE",
                    "The account role or department changed. Start a new archive verification",
                    HttpStatus.CONFLICT);
        }
    }

    private void ensureArchiveRecoverable(ArchivedAccount archived) {
        if (archived.isRecovered()) {
            throw new BusinessException("ARCHIVED_ACCOUNT_ALREADY_RECOVERED",
                    "This archive record has already been recovered", HttpStatus.CONFLICT);
        }
        UserAccount target = requireUser(archived.getOriginalUserId());
        if (!target.isArchived() || target.isEnabled()) {
            throw new BusinessException("ACCOUNT_NOT_ARCHIVED",
                    "The linked account is no longer archived", HttpStatus.CONFLICT);
        }
    }

    private void verifyRecoveryChallengeOtp(RecoveryChallenge challenge, String otp) {
        if (otp == null || !MessageDigest.isEqual(challenge.otpHash().getBytes(StandardCharsets.UTF_8),
                hash(otp.trim()).getBytes(StandardCharsets.UTF_8))) {
            Long failures = redis.opsForHash().increment(
                    recoveryChallengeKey(challenge.challengeId()), "failedAttempts", 1);
            int remaining = Math.max(0,
                    otpMaxAttempts - (failures == null ? otpMaxAttempts : failures.intValue()));
            if (remaining == 0) {
                clearRecoveryChallenge(challenge);
                throw new BusinessException("ACCOUNT_RECOVERY_OTP_ATTEMPTS_EXHAUSTED",
                        "The recovery challenge was cancelled after too many incorrect codes",
                        HttpStatus.TOO_MANY_REQUESTS);
            }
            throw new BusinessException("INVALID_OTP",
                    "The recovery code is incorrect. " + remaining + " attempts remain",
                    HttpStatus.UNAUTHORIZED);
        }
    }

    private void ensureRecoveryChallengeStillMatches(RecoveryChallenge challenge,
                                                     ArchivedAccount archived,
                                                     UserAccount target) {
        UUID currentDepartmentId = target.getEmployeeId() == null
                ? null : employees.departmentIdForEmployee(target.getEmployeeId());
        if (archived.isRecovered()
                || !archived.getOriginalUserId().equals(target.getId())
                || !target.isArchived() || target.isEnabled()
                || singleRole(target) != challenge.previousRole()
                || !Objects.equals(currentDepartmentId, challenge.previousDepartmentId())) {
            clearRecoveryChallenge(challenge);
            throw new BusinessException("ACCOUNT_RECOVERY_CHALLENGE_STALE",
                    "The archived identity changed. Start a new recovery verification",
                    HttpStatus.CONFLICT);
        }
        roleTransitions.validateArchivedRecovery(challenge.adminUserId(), target.getId(),
                challenge.targetRole(), challenge.targetDepartmentId());
    }

    private void storeRecoveryChallenge(RecoveryChallenge challenge) {
        String key = recoveryChallengeKey(challenge.challengeId());
        redis.opsForHash().putAll(key, Map.ofEntries(
                Map.entry("adminUserId", challenge.adminUserId().toString()),
                Map.entry("archivedAccountId", challenge.archivedAccountId().toString()),
                Map.entry("targetUserId", challenge.targetUserId().toString()),
                Map.entry("previousRole", challenge.previousRole().name()),
                Map.entry("previousDepartmentId", challenge.previousDepartmentId() == null
                        ? "" : challenge.previousDepartmentId().toString()),
                Map.entry("targetRole", challenge.targetRole().name()),
                Map.entry("targetDepartmentId", challenge.targetDepartmentId() == null
                        ? "" : challenge.targetDepartmentId().toString()),
                Map.entry("reason", challenge.reason()),
                Map.entry("createdAt", challenge.createdAt().toString()),
                Map.entry("expiresAt", challenge.expiresAt().toString()),
                Map.entry("resendAvailableAt", challenge.resendAvailableAt().toString()),
                Map.entry("failedAttempts", Integer.toString(challenge.failedAttempts())),
                Map.entry("otpHash", challenge.otpHash())));
        Duration ttl = Duration.between(Instant.now(), challenge.expiresAt());
        if (ttl.isNegative() || ttl.isZero()) ttl = Duration.ofSeconds(1);
        redis.expire(key, ttl);
        redis.opsForValue().set(activeRecoveryChallengeKey(challenge.adminUserId()),
                challenge.challengeId().toString(), ttl);
    }

    private RecoveryChallenge activeRecoveryChallenge(UUID adminUserId) {
        String value = redis.opsForValue().get(activeRecoveryChallengeKey(adminUserId));
        if (value == null) return null;
        try {
            RecoveryChallenge challenge = readRecoveryChallenge(UUID.fromString(value));
            if (challenge == null || !challenge.adminUserId().equals(adminUserId)
                    || !Instant.now().isBefore(challenge.expiresAt())) {
                if (challenge != null) clearRecoveryChallenge(challenge);
                else redis.delete(activeRecoveryChallengeKey(adminUserId));
                return null;
            }
            return challenge;
        } catch (IllegalArgumentException exception) {
            redis.delete(activeRecoveryChallengeKey(adminUserId));
            return null;
        }
    }

    private RecoveryChallenge requireRecoveryChallenge(UUID adminUserId, UUID challengeId) {
        RecoveryChallenge challenge = readRecoveryChallenge(challengeId);
        if (challenge == null || !challenge.adminUserId().equals(adminUserId)
                || !Instant.now().isBefore(challenge.expiresAt())) {
            if (challenge != null && challenge.adminUserId().equals(adminUserId)) {
                clearRecoveryChallenge(challenge);
            }
            throw new BusinessException("ACCOUNT_RECOVERY_CHALLENGE_EXPIRED",
                    "The archived account recovery has expired. Start again", HttpStatus.GONE);
        }
        return challenge;
    }

    private RecoveryChallenge readRecoveryChallenge(UUID challengeId) {
        Map<Object, Object> values = redis.opsForHash().entries(recoveryChallengeKey(challengeId));
        if (values == null || values.isEmpty()) return null;
        try {
            return new RecoveryChallenge(challengeId,
                    UUID.fromString(field(values, "adminUserId")),
                    UUID.fromString(field(values, "archivedAccountId")),
                    UUID.fromString(field(values, "targetUserId")),
                    SystemRole.valueOf(field(values, "previousRole")),
                    nullableUuid(field(values, "previousDepartmentId")),
                    SystemRole.valueOf(field(values, "targetRole")),
                    nullableUuid(field(values, "targetDepartmentId")),
                    field(values, "reason"),
                    Instant.parse(field(values, "createdAt")),
                    Instant.parse(field(values, "expiresAt")),
                    Instant.parse(field(values, "resendAvailableAt")),
                    Math.toIntExact(parseLong(field(values, "failedAttempts"), 0)),
                    field(values, "otpHash"));
        } catch (IllegalArgumentException | ArithmeticException exception) {
            redis.delete(recoveryChallengeKey(challengeId));
            return null;
        }
    }

    private void clearActiveRecoveryChallenge(UUID adminUserId) {
        RecoveryChallenge current = activeRecoveryChallenge(adminUserId);
        if (current != null) clearRecoveryChallenge(current);
    }

    private void clearRecoveryChallenge(RecoveryChallenge challenge) {
        redis.delete(List.of(recoveryChallengeKey(challenge.challengeId()),
                recoveryProcessingKey(challenge.challengeId())));
        String pointerKey = activeRecoveryChallengeKey(challenge.adminUserId());
        String activeId = redis.opsForValue().get(pointerKey);
        if (challenge.challengeId().toString().equals(activeId)) redis.delete(pointerKey);
    }

    private void storeChallenge(ArchiveChallenge challenge) {
        String key = challengeKey(challenge.challengeId());
        redis.opsForHash().putAll(key, Map.ofEntries(
                Map.entry("adminUserId", challenge.adminUserId().toString()),
                Map.entry("targetUserId", challenge.targetUserId().toString()),
                Map.entry("targetRole", challenge.targetRole().name()),
                Map.entry("departmentId", challenge.departmentId() == null ? "" : challenge.departmentId().toString()),
                Map.entry("reason", challenge.reason()),
                Map.entry("replacementUserId", challenge.replacementUserId() == null
                        ? "" : challenge.replacementUserId().toString()),
                Map.entry("createdAt", challenge.createdAt().toString()),
                Map.entry("expiresAt", challenge.expiresAt().toString()),
                Map.entry("resendAvailableAt", challenge.resendAvailableAt().toString()),
                Map.entry("failedAttempts", Integer.toString(challenge.failedAttempts())),
                Map.entry("otpHash", challenge.otpHash())));
        Duration ttl = Duration.between(Instant.now(), challenge.expiresAt());
        if (ttl.isNegative() || ttl.isZero()) ttl = Duration.ofSeconds(1);
        redis.expire(key, ttl);
        redis.opsForValue().set(activeChallengeKey(challenge.adminUserId()),
                challenge.challengeId().toString(), ttl);
    }

    private ArchiveChallenge activeChallenge(UUID adminUserId) {
        String value = redis.opsForValue().get(activeChallengeKey(adminUserId));
        if (value == null) return null;
        try {
            ArchiveChallenge challenge = readChallenge(UUID.fromString(value));
            if (challenge == null || !challenge.adminUserId().equals(adminUserId)
                    || !Instant.now().isBefore(challenge.expiresAt())) {
                if (challenge != null) clearChallenge(challenge);
                else redis.delete(activeChallengeKey(adminUserId));
                return null;
            }
            return challenge;
        } catch (IllegalArgumentException ex) {
            redis.delete(activeChallengeKey(adminUserId));
            return null;
        }
    }

    private ArchiveChallenge requireChallenge(UUID adminUserId, UUID challengeId) {
        ArchiveChallenge challenge = readChallenge(challengeId);
        if (challenge == null || !challenge.adminUserId().equals(adminUserId)
                || !Instant.now().isBefore(challenge.expiresAt())) {
            if (challenge != null && challenge.adminUserId().equals(adminUserId)) clearChallenge(challenge);
            throw new BusinessException("ACCOUNT_ARCHIVE_CHALLENGE_EXPIRED",
                    "The account archive verification has expired. Start again", HttpStatus.GONE);
        }
        return challenge;
    }

    private ArchiveChallenge readChallenge(UUID challengeId) {
        Map<Object, Object> values = redis.opsForHash().entries(challengeKey(challengeId));
        if (values == null || values.isEmpty()) return null;
        try {
            return new ArchiveChallenge(challengeId,
                    UUID.fromString(field(values, "adminUserId")),
                    UUID.fromString(field(values, "targetUserId")),
                    SystemRole.valueOf(field(values, "targetRole")),
                    nullableUuid(field(values, "departmentId")),
                    field(values, "reason"),
                    nullableUuid(field(values, "replacementUserId")),
                    Instant.parse(field(values, "createdAt")),
                    Instant.parse(field(values, "expiresAt")),
                    Instant.parse(field(values, "resendAvailableAt")),
                    Math.toIntExact(parseLong(field(values, "failedAttempts"), 0)),
                    field(values, "otpHash"));
        } catch (IllegalArgumentException | ArithmeticException ex) {
            redis.delete(challengeKey(challengeId));
            return null;
        }
    }

    private DirectArchiveChallengeView challengeView(ArchiveChallenge challenge, UserAccount target) {
        UserAccount replacement = challenge.replacementUserId() == null ? null
                : users.findById(challenge.replacementUserId()).orElse(null);
        String departmentName = challenge.departmentId() == null ? null
                : organization.findDepartment(challenge.departmentId())
                .map(OrganizationDirectory.DepartmentSummary::name).orElse(null);
        return new DirectArchiveChallengeView(challenge.challengeId(), target.getId(), target.getFullName(),
                target.getEmail(), challenge.targetRole(), challenge.departmentId(), departmentName,
                challenge.reason(), challenge.replacementUserId(),
                replacement == null ? null : replacement.getFullName(), challenge.createdAt(),
                challenge.expiresAt(), challenge.resendAvailableAt(),
                Math.max(0, otpMaxAttempts - challenge.failedAttempts()));
    }

    private ArchivedRecoveryChallengeView recoveryChallengeView(RecoveryChallenge challenge,
                                                                UserAccount target) {
        String previousDepartmentName = challenge.previousDepartmentId() == null ? null
                : organization.findDepartment(challenge.previousDepartmentId())
                .map(OrganizationDirectory.DepartmentSummary::name).orElse(null);
        String targetDepartmentName = challenge.targetDepartmentId() == null ? null
                : organization.findDepartment(challenge.targetDepartmentId())
                .map(OrganizationDirectory.DepartmentSummary::name).orElse(null);
        return new ArchivedRecoveryChallengeView(challenge.challengeId(),
                challenge.archivedAccountId(), target.getId(), target.getFullName(), target.getEmail(),
                target.getEmployeeId(), challenge.previousRole(), challenge.previousDepartmentId(),
                previousDepartmentName, challenge.targetRole(), challenge.targetDepartmentId(),
                targetDepartmentName, challenge.reason(), challenge.createdAt(), challenge.expiresAt(),
                challenge.resendAvailableAt(), Math.max(0, otpMaxAttempts - challenge.failedAttempts()));
    }

    private void clearActiveChallenge(UUID adminUserId) {
        ArchiveChallenge current = activeChallenge(adminUserId);
        if (current != null) clearChallenge(current);
    }

    private void clearChallenge(ArchiveChallenge challenge) {
        redis.delete(List.of(challengeKey(challenge.challengeId()), processingKey(challenge.challengeId())));
        String pointerKey = activeChallengeKey(challenge.adminUserId());
        String activeId = redis.opsForValue().get(pointerKey);
        if (challenge.challengeId().toString().equals(activeId)) redis.delete(pointerKey);
    }

    private String field(Map<Object, Object> values, String name) {
        Object value = values.get(name);
        if (value == null) throw new IllegalArgumentException("Archive challenge is incomplete");
        return value.toString();
    }
    private long parseLong(String value, long fallback) {
        try { return value == null ? fallback : Long.parseLong(value); }
        catch (NumberFormatException ex) { return fallback; }
    }
    private UUID nullableUuid(String value) { return value == null || value.isBlank() ? null : UUID.fromString(value); }
    private String activeChallengeKey(UUID adminId) { return "iam:account-archive-challenge:active:" + adminId; }
    private String challengeKey(UUID challengeId) { return "iam:account-archive-challenge:" + challengeId; }
    private String processingKey(UUID challengeId) { return "iam:account-archive-challenge:processing:" + challengeId; }
    private String activeRecoveryChallengeKey(UUID adminId) {
        return "iam:archived-account-recovery-challenge:active:" + adminId;
    }
    private String recoveryChallengeKey(UUID challengeId) {
        return "iam:archived-account-recovery-challenge:" + challengeId;
    }
    private String recoveryProcessingKey(UUID challengeId) {
        return "iam:archived-account-recovery-challenge:processing:" + challengeId;
    }
    private String passwordFailureKey(UUID adminId) { return "iam:account-archive-password-failures:" + adminId; }
    private LocalDate today() { return LocalDate.now(officeZone); }
    private String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException("SHA-256 is unavailable", ex); }
    }
    private BusinessException protectedAdmin() { return new BusinessException("SYSTEM_ADMIN_ACCOUNT_PROTECTED",
            "The permanent System Admin account cannot be closed or archived", HttpStatus.CONFLICT); }
    private BusinessException forbidden(String message) { return new BusinessException("ACCOUNT_CLOSURE_ACCESS_DENIED",
            message, HttpStatus.FORBIDDEN); }
    private BusinessException alreadyDecided() { return new BusinessException("ACCOUNT_CLOSURE_ALREADY_DECIDED",
            "This account closure request has already been decided", HttpStatus.CONFLICT); }

    private record Snapshot(UUID departmentId, String departmentName, String employeeNumber) {}
    private record ArchiveChallenge(UUID challengeId, UUID adminUserId, UUID targetUserId,
                                    SystemRole targetRole, UUID departmentId, String reason,
                                    UUID replacementUserId, Instant createdAt, Instant expiresAt,
                                    Instant resendAvailableAt, int failedAttempts, String otpHash) {}
    private record RecoveryChallenge(UUID challengeId, UUID adminUserId, UUID archivedAccountId,
                                     UUID targetUserId, SystemRole previousRole, UUID previousDepartmentId,
                                     SystemRole targetRole, UUID targetDepartmentId, String reason,
                                     Instant createdAt, Instant expiresAt, Instant resendAvailableAt,
                                     int failedAttempts, String otpHash) {}
    public record DirectArchiveChallengeView(UUID challengeId, UUID targetUserId, String targetName,
                                             String targetEmail, SystemRole targetRole, UUID departmentId,
                                             String departmentName, String reason, UUID replacementUserId,
                                             String replacementName, Instant createdAt, Instant expiresAt,
                                             Instant resendAvailableAt, int attemptsRemaining) {}
    public record ArchivedRecoveryChallengeView(
            UUID challengeId, UUID archivedAccountId, UUID targetUserId, String targetName,
            String targetEmail, UUID employeeId, SystemRole previousRole, UUID previousDepartmentId,
            String previousDepartmentName, SystemRole targetRole, UUID targetDepartmentId,
            String targetDepartmentName, String reason, Instant createdAt, Instant expiresAt,
            Instant resendAvailableAt, int attemptsRemaining) {}
    public record RecoveredAccountView(
            UUID userId, UUID employeeId, SystemRole previousRole, SystemRole role,
            UUID previousDepartmentId, UUID departmentId, boolean roleChanged,
            boolean departmentChanged, Instant recoveredAt) {}
    public record View(UUID id, UUID targetUserId, String targetName, String targetEmail,
                       SystemRole targetRole, UUID employeeId, UUID departmentId, String departmentName,
                       UUID requesterUserId, AccountClosureRequest.Origin origin, String reason,
                       LocalDate requestedEffectiveDate, UUID replacementUserId, String replacementName,
                       AccountClosureStatus status, Instant requestedAt, UUID businessApproverUserId,
                       Instant businessApprovedAt, UUID systemAdminApproverUserId,
                       Instant systemAdminApprovedAt, String decisionNote, Instant scheduledAt,
                       Instant archivedAt, Instant cancelledAt) {}
    public record AccountView(UUID userId, String fullName, String email, SystemRole role, String status,
                              boolean enabled, boolean archived, UUID employeeId, UUID departmentId,
                              String departmentName, boolean protectedAccount) {}
    public record Candidate(UUID userId, String fullName, String email, SystemRole role,
                            UUID employeeId, UUID departmentId) {}
    public record ArchivedView(UUID id, UUID originalUserId, String fullName, String email, String role,
                               UUID departmentId, String departmentName, UUID employeeId, String employeeNumber,
                               String previousStatus, String reason, UUID closureRequestId, UUID archivedByUserId,
                               Instant archivedAt, LocalDate retentionUntil) {
        static ArchivedView from(ArchivedAccount value) {
            return new ArchivedView(value.getId(), value.getOriginalUserId(), value.getFullNameSnapshot(),
                    value.getEmailSnapshot(), value.getRoleSnapshot(), value.getDepartmentIdSnapshot(),
                    value.getDepartmentNameSnapshot(), value.getEmployeeIdSnapshot(),
                    value.getEmployeeNumberSnapshot(), value.getPreviousAccountStatus(), value.getClosureReason(),
                    value.getClosureRequestId(), value.getArchivedByUserId(), value.getArchivedAt(),
                    value.getRetentionUntil());
        }
    }
    public record LifecycleView(UUID id, UUID closureRequestId, UUID targetUserId, String eventType,
                                String fromStatus, String toStatus, UUID actorUserId, String detail,
                                Instant occurredAt) {
        static LifecycleView from(AccountLifecycleRecord value) {
            return new LifecycleView(value.getId(), value.getClosureRequestId(), value.getTargetUserId(),
                    value.getEventType(), value.getFromStatus(), value.getToStatus(), value.getActorUserId(),
                    value.getDetail(), value.getOccurredAt());
        }
    }
}
