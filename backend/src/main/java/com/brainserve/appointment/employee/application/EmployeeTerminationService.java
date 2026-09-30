package com.brainserve.appointment.employee.application;

import com.brainserve.appointment.audit.api.AuditService;
import com.brainserve.appointment.departmenthr.api.DepartmentHrDirectory;
import com.brainserve.appointment.employee.api.EmployeeDirectory;
import com.brainserve.appointment.employee.domain.EmployeeTerminationRequest;
import com.brainserve.appointment.employee.infrastructure.EmployeeTerminationRequestRepository;
import com.brainserve.appointment.essentiallog.api.EssentialLogService;
import com.brainserve.appointment.iam.api.StaffCommunicationDirectory;
import com.brainserve.appointment.iam.api.AccountArchiveService;
import com.brainserve.appointment.notification.api.InternalNotificationGateway;
import com.brainserve.appointment.shared.application.BusinessException;
import com.brainserve.appointment.teamlead.api.TeamLeadDirectory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class EmployeeTerminationService {
    private static final String HR = "ROLE_HR_ADMIN";
    private static final String CEO = "ROLE_CEO";
    private static final Set<String> TERMINABLE = Set.of("ACTIVE", "ON_LEAVE", "NOTICE_PERIOD", "SUSPENDED");
    private static final Set<String> PROTECTED_ROLES = Set.of("ROLE_SYSTEM_ADMIN", "ROLE_CEO", "ROLE_MANAGER", "ROLE_HR_ADMIN",
            "ROLE_RECEPTIONIST", "ROLE_SECURITY");

    private final EmployeeTerminationRequestRepository requests;
    private final EmployeeDirectory employees;
    private final DepartmentHrDirectory departmentHrs;
    private final TeamLeadDirectory teamLeads;
    private final StaffCommunicationDirectory staff;
    private final InternalNotificationGateway notifications;
    private final EssentialLogService logs;
    private final AuditService audit;
    private final AccountArchiveService accountArchives;

    public EmployeeTerminationService(EmployeeTerminationRequestRepository requests, EmployeeDirectory employees,
                                      DepartmentHrDirectory departmentHrs, TeamLeadDirectory teamLeads,
                                      StaffCommunicationDirectory staff, InternalNotificationGateway notifications,
                                      EssentialLogService logs, AuditService audit,
                                      AccountArchiveService accountArchives) {
        this.requests = requests; this.employees = employees; this.departmentHrs = departmentHrs;
        this.teamLeads = teamLeads; this.staff = staff; this.notifications = notifications;
        this.logs = logs; this.audit = audit; this.accountArchives = accountArchives;
    }

    @Transactional
    public View request(UUID hrUserId, UUID employeeId, String reason, LocalDate effectiveDate) {
        var hr = staff.requireActive(hrUserId);
        if (!hr.roles().contains(HR)) throw forbidden("Only HR can request employee termination");
        var employee = employees.employeeSummary(employeeId);
        departmentHrs.requireAssignedReviewer(employee.departmentId(), hrUserId);
        if (!TERMINABLE.contains(employee.status())) throw new BusinessException("EMPLOYEE_NOT_TERMINABLE",
                "Only an active, on-leave, notice-period or suspended employee can enter termination review",
                HttpStatus.CONFLICT);
        if (employees.isChiefExecutive(employeeId)) {
            throw new BusinessException("CEO_LIFECYCLE_PROTECTED",
                    "Department HR cannot request termination of the company CEO. "
                            + "Only System Admin can transfer CEO authority through governed succession.",
                    HttpStatus.FORBIDDEN);
        }
        staff.activeByEmployeeId(employeeId).ifPresent(account -> {
            if (account.roles().stream().anyMatch(PROTECTED_ROLES::contains)) throw new BusinessException(
                    "EMPLOYEE_TERMINATION_ROLE_NOT_ALLOWED",
                    "Executive, HR, Reception and Security accounts use their dedicated governance lifecycle",
                    HttpStatus.UNPROCESSABLE_ENTITY);
        });
        if (requests.existsByEmployeeIdAndStatus(employeeId, EmployeeTerminationRequest.Status.PENDING_CEO_APPROVAL)) {
            throw new BusinessException("TERMINATION_ALREADY_PENDING",
                    "This employee already has a pending CEO termination review", HttpStatus.CONFLICT);
        }
        String normalizedReason = requireText(reason, "termination reason");
        if (effectiveDate == null || effectiveDate.isAfter(LocalDate.now())) throw new BusinessException(
                "INVALID_TERMINATION_EFFECTIVE_DATE", "The effective date must be today or earlier",
                HttpStatus.UNPROCESSABLE_ENTITY);
        var request = requests.saveAndFlush(new EmployeeTerminationRequest(employeeId, employee.departmentId(),
                hrUserId, normalizedReason, effectiveDate));
        String detail = employee.displayName() + " (" + employee.employeeNumber() + ") · " + normalizedReason;
        audit.record("EMPLOYEE_TERMINATION_REQUESTED", "EMPLOYEE_TERMINATION", request.getId().toString(),
                "{\"employeeId\":\"" + employeeId + "\",\"departmentId\":\""
                        + employee.departmentId() + "\"}");
        logs.record("EMPLOYEE_LIFECYCLE", "TERMINATION_REQUESTED", "EMPLOYEE", employeeId.toString(),
                request.getId().toString(), hrUserId, null, request.getStatus().name(),
                "Termination requested for " + employee.displayName(), detail);
        notifications.notifyCeoOfTerminationRequest(hrUserId, "Termination approval required for "
                + employee.displayName() + " (" + employee.employeeNumber() + "). Review the request in Terminations.");
        return view(request);
    }

    @Transactional(readOnly = true)
    public List<View> mine(UUID hrUserId) {
        var actor = staff.requireActive(hrUserId);
        if (!actor.roles().contains(HR)) throw forbidden("Only HR can view submitted termination requests");
        return requests.findTop100ByRequestedByHrUserIdOrderByRequestedAtDesc(hrUserId).stream().map(this::view).toList();
    }

    @Transactional(readOnly = true)
    public List<View> pending(UUID ceoUserId) {
        requireCeo(ceoUserId);
        return requests.findAllByStatusOrderByRequestedAtAsc(EmployeeTerminationRequest.Status.PENDING_CEO_APPROVAL)
                .stream().map(this::view).toList();
    }

    @Transactional(readOnly = true)
    public List<View> history(UUID actorUserId) {
        var actor = staff.requireActive(actorUserId);
        if (actor.roles().contains(HR)) return mine(actorUserId);
        if (!actor.roles().contains(CEO) && !actor.roles().contains("ROLE_SYSTEM_ADMIN")) {
            throw forbidden("Termination history is restricted to HR, CEO and System Admin");
        }
        return requests.findTop100ByOrderByRequestedAtDesc().stream().map(this::view).toList();
    }

    @Transactional
    public View approve(UUID ceoUserId, UUID requestId, String note) {
        requireCeo(ceoUserId);
        var request = requirePending(requestId);
        var employee = employees.employeeSummary(request.getEmployeeId());
        teamLeads.endForEmployeeIfAssigned(ceoUserId, employee.id());
        accountArchives.archiveAfterEmployeeTermination(request.getRequestedByHrUserId(), ceoUserId,
                employee.id(), requestId, request.getReason(), request.getEffectiveDate());
        employees.terminateAfterApproval(employee.id(), request.getEffectiveDate());
        request.approve(ceoUserId, note);
        requests.saveAndFlush(request);
        audit.record("EMPLOYEE_TERMINATION_APPROVED", "EMPLOYEE_TERMINATION", requestId.toString(),
                "{\"employeeId\":\"" + employee.id() + "\"}");
        logs.record("EMPLOYEE_LIFECYCLE", "TERMINATION_APPROVED", "EMPLOYEE", employee.id().toString(),
                requestId.toString(), request.getRequestedByHrUserId(), ceoUserId, request.getStatus().name(),
                "Termination approved for " + employee.displayName(),
                employee.displayName() + " (" + employee.employeeNumber() + ") · effective "
                        + request.getEffectiveDate() + decisionSuffix(note));
        notifications.notifyHrOfTerminationDecision(ceoUserId, request.getRequestedByHrUserId(),
                "CEO approved the termination of " + employee.displayName() + ". Access is disabled and the decision is archived.");
        return view(request);
    }

    @Transactional
    public View reject(UUID ceoUserId, UUID requestId, String note) {
        requireCeo(ceoUserId);
        var request = requirePending(requestId);
        String normalizedNote = requireText(note, "rejection reason");
        var employee = employees.employeeSummary(request.getEmployeeId());
        request.reject(ceoUserId, normalizedNote);
        requests.saveAndFlush(request);
        audit.record("EMPLOYEE_TERMINATION_REJECTED", "EMPLOYEE_TERMINATION", requestId.toString(),
                "{\"employeeId\":\"" + employee.id() + "\"}");
        logs.record("EMPLOYEE_LIFECYCLE", "TERMINATION_REJECTED", "EMPLOYEE", employee.id().toString(),
                requestId.toString(), request.getRequestedByHrUserId(), ceoUserId, request.getStatus().name(),
                "Termination rejected for " + employee.displayName(), normalizedNote);
        notifications.notifyHrOfTerminationDecision(ceoUserId, request.getRequestedByHrUserId(),
                "CEO rejected the termination request for " + employee.displayName() + ": " + normalizedNote);
        return view(request);
    }

    private EmployeeTerminationRequest requirePending(UUID requestId) {
        var request = requests.findById(requestId).orElseThrow(() -> new BusinessException(
                "TERMINATION_REQUEST_NOT_FOUND", "Termination request was not found", HttpStatus.NOT_FOUND));
        if (request.getStatus() != EmployeeTerminationRequest.Status.PENDING_CEO_APPROVAL) throw new BusinessException(
                "TERMINATION_REQUEST_NOT_PENDING", "This termination request has already been decided", HttpStatus.CONFLICT);
        return request;
    }

    private void requireCeo(UUID actorId) {
        var chiefExecutive = staff.requireChiefExecutive();
        if (!chiefExecutive.userId().equals(actorId)) {
            throw forbidden("Only the single company CEO can decide employee termination");
        }
    }

    private View view(EmployeeTerminationRequest request) {
        var employee = employees.employeeSummary(request.getEmployeeId());
        var requester = staff.findByUserId(request.getRequestedByHrUserId()).orElse(null);
        var approver = request.getDecidedByCeoUserId() == null ? null
                : staff.findByUserId(request.getDecidedByCeoUserId()).orElse(null);
        return new View(request.getId(), employee.id(), employee.employeeNumber(), employee.displayName(),
                employee.officialEmail(), employee.departmentId(), request.getRequestedByHrUserId(),
                requester == null ? "Former HR Admin" : requester.fullName(), request.getReason(),
                request.getEffectiveDate(), request.getStatus(), request.getRequestedAt(),
                request.getDecidedByCeoUserId(), approver == null ? null : approver.fullName(),
                request.getDecidedAt(), request.getDecisionNote());
    }

    private String requireText(String value, String label) {
        String normalized = value == null ? "" : value.trim().replaceAll("\\s+", " ");
        if (normalized.length() < 5 || normalized.length() > 1000) throw new BusinessException(
                "INVALID_TERMINATION_TEXT", "Provide a " + label + " containing 5 to 1000 characters",
                HttpStatus.UNPROCESSABLE_ENTITY);
        return normalized;
    }
    private String decisionSuffix(String value) { return value == null || value.isBlank() ? "" : " · " + value.trim(); }
    private BusinessException forbidden(String message) { return new BusinessException("TERMINATION_ACCESS_DENIED", message, HttpStatus.FORBIDDEN); }

    public record View(UUID id, UUID employeeId, String employeeNumber, String employeeName,
                       String employeeEmail, UUID departmentId, UUID requestedByHrUserId,
                       String requestedByHrName, String reason, LocalDate effectiveDate,
                       EmployeeTerminationRequest.Status status, java.time.Instant requestedAt,
                       UUID decidedByCeoUserId, String decidedByCeoName, java.time.Instant decidedAt,
                       String decisionNote) {}
}
