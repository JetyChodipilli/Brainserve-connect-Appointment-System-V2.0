package com.brainserve.appointment.workinsight.application;

import com.brainserve.appointment.audit.api.AuditService;
import com.brainserve.appointment.departmenthr.api.DepartmentHrDirectory;
import com.brainserve.appointment.employee.api.EmployeeDirectory;
import com.brainserve.appointment.iam.api.StaffCommunicationDirectory;
import com.brainserve.appointment.manager.api.ManagerDirectory;
import com.brainserve.appointment.shared.application.BusinessException;

import com.brainserve.appointment.workinsight.api.WorkInsightEvents;
import com.brainserve.appointment.workinsight.domain.WorkInsightStatus;
import com.brainserve.appointment.workinsight.domain.WorkTaskAuditRecord;
import com.brainserve.appointment.workinsight.infrastructure.WorkTaskAuditRecordRepository;
import com.brainserve.appointment.worktask.api.WorkTaskDirectory;
import com.brainserve.appointment.worktask.api.WorkTaskDirectory.TaskSnapshot;
import com.brainserve.appointment.worktask.api.WorkTaskEvents;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class WorkInsightService {
    private static final String HR = "ROLE_HR_ADMIN";
    private static final String MANAGER = "ROLE_MANAGER";
    private static final String CEO = "ROLE_CEO";
    private static final String SYSTEM_ADMIN = "ROLE_SYSTEM_ADMIN";
    private static final String TEAM_LEAD = "ROLE_TEAM_LEAD";
    private static final String TEAM_LEAD_ASSIGNEE = "TEAM_LEAD";

    private final ZoneId officeZone;
    private final WorkTaskDirectory tasks;
    private final WorkTaskAuditRecordRepository audits;
    private final EmployeeDirectory employees;
    private final StaffCommunicationDirectory staff;
    private final ApplicationEventPublisher events;
    private final AuditService audit;
    private final DepartmentHrDirectory departmentHrs;
    private final ManagerDirectory managers;

    public WorkInsightService(WorkTaskDirectory tasks, WorkTaskAuditRecordRepository audits,
                              EmployeeDirectory employees, StaffCommunicationDirectory staff,
                              ApplicationEventPublisher events, AuditService audit,
                              DepartmentHrDirectory departmentHrs, ManagerDirectory managers,
                              @Value("${brainserve.appointment.office-zone:Asia/Kolkata}")
                              String officeZone) {
        this.tasks = tasks;
        this.audits = audits;
        this.employees = employees;
        this.staff = staff;
        this.events = events;
        this.audit = audit;
        this.departmentHrs = departmentHrs;
        this.managers = managers;
        this.officeZone = ZoneId.of(officeZone);
    }

    @Transactional(readOnly = true)
    public List<Insight> list(UUID actorUserId, LocalDate requestedWeek) {
        Set<String> roles = staff.requireActive(actorUserId).roles();
        LocalDate weekStart = normalizeWeek(requestedWeek);
        if (roles.contains(HR)) {
            UUID departmentId = departmentHrs.requireForUser(actorUserId).departmentId();
            Map<UUID, WorkTaskAuditRecord> retained = new HashMap<>();
            List<WorkTaskAuditRecord> retainedForWeek = audits
                    .findTop1000ByWeekStartAndDepartmentIdOrderByHrAuditedAtDesc(weekStart, departmentId);
            retainedForWeek.forEach(record -> retained.put(record.getWorkTaskId(), record));
            List<Insight> result = new ArrayList<>(tasks.recentForDepartment(departmentId).stream()
                    .filter(task -> weekStart(task.createdAt()).equals(weekStart))
                    .map(task -> liveInsight(task, retained.get(task.id())))
                    .toList());
            Set<UUID> included = result.stream().map(Insight::workTaskId)
                    .collect(java.util.stream.Collectors.toSet());
            retainedForWeek.stream()
                    .filter(record -> !included.contains(record.getWorkTaskId()))
                    .map(this::retainedInsight)
                    .forEach(result::add);
            return List.copyOf(result);
        }
        if (roles.contains(MANAGER)) {
            UUID departmentId = managers.requireForUser(actorUserId).departmentId();
            return audits.findTop1000ByWeekStartAndDepartmentIdOrderByHrAuditedAtDesc(
                            weekStart, departmentId).stream()
                    .map(this::retainedInsight)
                    .toList();
        }
        if (roles.contains(CEO) || roles.contains(SYSTEM_ADMIN)) {
            return audits.findTop1000ByWeekStartOrderByHrAuditedAtDesc(weekStart).stream()
                    .map(this::retainedInsight)
                    .toList();
        }
        throw new BusinessException("WORK_INSIGHT_ROLE_REQUIRED",
                "Only Manager, HR, CEO and System Admin can view work insights", HttpStatus.FORBIDDEN);
    }

    @Transactional(readOnly = true)
    public List<Insight> pendingHrAudit(UUID hrUserId) {
        requireRole(hrUserId, HR, "Only HR can view worksheets waiting for audit");
        UUID departmentId = departmentHrs.requireForUser(hrUserId).departmentId();
        Map<UUID, WorkTaskAuditRecord> retained = new HashMap<>();
        audits.findTop1000ByDepartmentIdOrderByHrAuditedAtDesc(departmentId)
                .forEach(record -> retained.put(record.getWorkTaskId(), record));
        return tasks.recentForDepartment(departmentId).stream()
                .filter(this::isReadyForHrAudit)
                .filter(task -> {
                    WorkTaskAuditRecord record = retained.get(task.id());
                    return record == null || record.getAuditStatus() == WorkInsightStatus.REWORK_ASSIGNED;
                })
                .map(task -> liveInsight(task, retained.get(task.id())))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<TaskWorkflowState> taskWorkflowStates(UUID userId) {
        StaffCommunicationDirectory.StaffMember member = staff.requireActive(userId);
        List<WorkTaskAuditRecord> records;
        if (member.roles().contains(TEAM_LEAD)) {
            records = audits.findTop500ByTeamLeadUserIdOrderByHrAuditedAtDesc(userId);
        } else if (member.roles().contains("ROLE_EMPLOYEE") && member.employeeId() != null) {
            records = audits.findTop500ByEmployeeIdOrderByHrAuditedAtDesc(member.employeeId());
        } else {
            throw new BusinessException("WORK_TASK_WORKFLOW_STATE_DENIED",
                    "Only the assigned Employee or Team Lead can inspect worksheet workflow states",
                    HttpStatus.FORBIDDEN);
        }
        return records.stream()
                .map(record -> new TaskWorkflowState(record.getWorkTaskId(),
                        record.getAuditStatus().name()))
                .toList();
    }

    @Transactional
    public Insight markAudited(UUID hrUserId, UUID workTaskId) {
        requireRole(hrUserId, HR, "Only HR can audit a worksheet");
        TaskSnapshot task = requireAuditReadyTask(workTaskId);
        departmentHrs.requireAssignedReviewer(task.departmentId(), hrUserId);
        ManagerDirectory.Assignment manager = managers.requireForDepartment(task.departmentId());
        WorkTaskAuditRecord record = audits.findByWorkTaskId(workTaskId).orElse(null);
        if (record == null) {
            record = audits.saveAndFlush(newRecord(task, hrUserId));
        } else if (record.getAuditStatus() == WorkInsightStatus.REWORK_ASSIGNED) {
            record.resubmit(hrUserId, task.status());
        } else {
            return retainedInsight(record);
        }
        events.publishEvent(new WorkInsightEvents.HrAuditSubmitted(hrUserId, manager.managerUserId(),
                "HR audited worksheet ‘" + task.title() + "’ for " + employee(task).displayName()
                        + " in " + task.departmentBranch()
                        + ". Manager verification is required before CEO approval."));
        audit.record("WORK_INSIGHT_HR_AUDITED", "WORK_TASK_AUDIT", record.getId().toString(),
                "{\"workTaskId\":\"" + task.id() + "\"}");
        return retainedInsight(record);
    }

    @Transactional
    public Insight requestHrRework(UUID hrUserId, UUID workTaskId, String reason) {
        requireRole(hrUserId, HR, "Only HR can return an Insights worksheet for rework");
        TaskSnapshot task = requireAuditReadyTask(workTaskId);
        departmentHrs.requireAssignedReviewer(task.departmentId(), hrUserId);
        WorkTaskAuditRecord record = audits.findByWorkTaskId(workTaskId)
                .orElseGet(() -> audits.saveAndFlush(newRecord(task, hrUserId)));
        record.requestHrRework(hrUserId, reason);
        TaskSnapshot reworked = tasks.requestInsightRework(workTaskId, "HR", reason);
        record.syncTaskStatus(reworked.status());
        events.publishEvent(new WorkInsightEvents.ReworkRequested(hrUserId, reworked.teamLeadUserId(),
                "HR returned worksheet ‘" + reworked.title() + "’ for rework. Flaws noted: "
                        + reason.trim() + ". Open Work Board and create the corrective plan."));
        audit.record("WORK_INSIGHT_HR_REWORK_REQUESTED", "WORK_TASK_AUDIT", record.getId().toString(),
                "{\"workTaskId\":\"" + reworked.id() + "\",\"cycle\":"
                        + record.getReworkCycle() + "}");
        return retainedInsight(record);
    }

    @Transactional
    public Insight assignRework(UUID teamLeadUserId, UUID workTaskId, String guidance) {
        WorkTaskAuditRecord record = audits.findByWorkTaskId(workTaskId)
                .orElseThrow(() -> new BusinessException("WORK_INSIGHT_NOT_FOUND",
                        "The Insights rework request was not found", HttpStatus.NOT_FOUND));
        if (!record.getTeamLeadUserId().equals(teamLeadUserId)) {
            throw new BusinessException("WORK_INSIGHT_TEAM_LEAD_SCOPE_DENIED",
                    "This rework request belongs to another Team Lead", HttpStatus.FORBIDDEN);
        }
        TaskSnapshot task = tasks.assignInsightRework(workTaskId, guidance);
        record.assignRework(guidance, task.status());
        if (!TEAM_LEAD_ASSIGNEE.equals(task.assigneeRole())) {
            UUID employeeUserId = staff.activeByEmployeeId(task.employeeId())
                    .map(StaffCommunicationDirectory.StaffMember::userId)
                    .orElseThrow(() -> new BusinessException("WORK_TASK_EMPLOYEE_LOGIN_REQUIRED",
                            "The assigned Employee login is no longer active", HttpStatus.CONFLICT));
            events.publishEvent(new WorkInsightEvents.ReworkAssigned(teamLeadUserId, employeeUserId,
                    "Your worksheet ‘" + task.title() + "’ was returned by "
                            + record.getReworkRequestedByRole() + ". Team Lead rework guidance: "
                            + guidance.trim() + ". Open Work Board to update and resubmit it."));
        }
        audit.record("WORK_INSIGHT_REWORK_ASSIGNED", "WORK_TASK_AUDIT", record.getId().toString(),
                "{\"workTaskId\":\"" + task.id() + "\",\"cycle\":"
                        + record.getReworkCycle() + "}");
        return liveInsight(task, record);
    }

    @Transactional
    public Insight reviseReworkSubmission(UUID teamLeadUserId, UUID workTaskId, String update) {
        WorkTaskAuditRecord record = audits.findByWorkTaskId(workTaskId)
                .orElseThrow(() -> new BusinessException("WORK_INSIGHT_NOT_FOUND",
                        "The Insights rework request was not found", HttpStatus.NOT_FOUND));
        if (!record.getTeamLeadUserId().equals(teamLeadUserId)) {
            throw new BusinessException("WORK_INSIGHT_TEAM_LEAD_SCOPE_DENIED",
                    "This rework request belongs to another Team Lead", HttpStatus.FORBIDDEN);
        }
        if (!TEAM_LEAD_ASSIGNEE.equals(record.getAssigneeRole())) {
            throw new BusinessException("WORK_INSIGHT_REWORK_UPDATE_DENIED",
                    "Employee rework must be resubmitted through the employee review flow",
                    HttpStatus.CONFLICT);
        }
        if (record.getAuditStatus() != WorkInsightStatus.REWORK_ASSIGNED) {
            throw new BusinessException("WORK_INSIGHT_REWORK_UPDATE_CLOSED",
                    "This rework submission can no longer be updated because HR has already reviewed it",
                    HttpStatus.CONFLICT);
        }
        TaskSnapshot task = tasks.reviseInsightReworkSubmission(teamLeadUserId, workTaskId, update);
        record.syncTaskStatus(task.status());
        audit.record("WORK_INSIGHT_REWORK_RESUBMITTED", "WORK_TASK_AUDIT",
                record.getId().toString(), "{\"workTaskId\":\"" + task.id()
                        + "\",\"cycle\":" + record.getReworkCycle() + "}");
        return liveInsight(task, record);
    }

    @Transactional
    public Insight decideByManager(UUID managerUserId, UUID recordId, boolean approved, String remarks) {
        requireRole(managerUserId, MANAGER, "Only the assigned Manager can decide a work audit");
        WorkTaskAuditRecord record = requireRecord(recordId);
        managers.requireAssignedReviewer(record.getDepartmentId(), managerUserId);
        record.decideByManager(managerUserId, approved, remarks);
        if (!approved) {
            requireAuditReadyTask(record.getWorkTaskId());
            TaskSnapshot task = tasks.requestInsightRework(record.getWorkTaskId(), "MANAGER", remarks);
            record.syncTaskStatus(task.status());
            events.publishEvent(new WorkInsightEvents.ReworkRequested(managerUserId,
                    record.getTeamLeadUserId(), "Manager returned worksheet ‘" + record.getTaskTitle()
                    + "’ for rework. Flaws noted: " + remarks.trim()
                    + ". Open Work Board and create the corrective plan."));
        }
        events.publishEvent(new WorkInsightEvents.ManagerDecisionRecorded(managerUserId,
                record.getHrAuditedByUserId(), approved,
                "Manager " + (approved ? "verified" : "returned for rework")
                        + " the work audit for ‘" + record.getTaskTitle() + "’ assigned to "
                        + record.getEmployeeName()
                        + (approved ? ". CEO final approval is required."
                        : ". Reason: " + remarks.trim())));
        audit.record(approved ? "WORK_INSIGHT_MANAGER_APPROVED"
                        : "WORK_INSIGHT_MANAGER_REWORK_REQUESTED",
                "WORK_TASK_AUDIT", record.getId().toString(),
                "{\"workTaskId\":\"" + record.getWorkTaskId() + "\"}");
        return retainedInsight(record);
    }

    @Transactional
    public Insight decideByCeo(
            UUID ceoUserId,
            UUID recordId,
            boolean approved,
            String remarks
    ) {
        requireRole(
                ceoUserId,
                CEO,
                "Only the CEO can decide a work audit"
        );

        WorkTaskAuditRecord record = requireRecord(recordId);
        record.decideByCeo(ceoUserId, approved, remarks);

        TaskSnapshot task;

        if (!approved) {
            requireAuditReadyTask(record.getWorkTaskId());

            task = tasks.requestInsightRework(
                    record.getWorkTaskId(),
                    "CEO",
                    remarks
            );

            record.syncTaskStatus(task.status());

            events.publishEvent(
                    new WorkInsightEvents.ReworkRequested(
                            ceoUserId,
                            record.getTeamLeadUserId(),
                            "CEO returned worksheet ‘"
                                    + record.getTaskTitle()
                                    + "’ for rework. Flaws noted: "
                                    + remarks.trim()
                                    + ". Open Work Board and create the corrective plan."
                    )
            );
        } else {
            task = tasks.finalizeInsightApproval(record.getWorkTaskId());
            record.syncTaskStatus(task.status());

            String finalMessage =
                    "CEO gave final approval to worksheet ‘"
                            + record.getTaskTitle()
                            + "’. The governance cycle is complete and the worksheet is closed.";

            UUID assigneeUserId =
                    TEAM_LEAD_ASSIGNEE.equals(task.assigneeRole())
                            ? task.teamLeadUserId()
                            : staff.activeByEmployeeId(task.employeeId())
                            .map(StaffCommunicationDirectory.StaffMember::userId)
                            .orElse(null);

            if (assigneeUserId != null) {
                events.publishEvent(
                        new WorkInsightEvents.FinalApprovalRecipient(
                                ceoUserId,
                                assigneeUserId,
                                finalMessage
                        )
                );
            }

            if (!task.teamLeadUserId().equals(assigneeUserId)) {
                events.publishEvent(
                        new WorkInsightEvents.FinalApprovalRecipient(
                                ceoUserId,
                                task.teamLeadUserId(),
                                finalMessage
                        )
                );
            }
        }

        events.publishEvent(
                new WorkInsightEvents.CeoDecisionRecorded(
                        ceoUserId,
                        record.getHrAuditedByUserId(),
                        "CEO "
                                + (approved
                                ? "approved"
                                : "returned for rework")
                                + " the work audit for ‘"
                                + record.getTaskTitle()
                                + "’ assigned to "
                                + record.getEmployeeName()
                                + (approved
                                ? "."
                                : ". Reason: " + remarks.trim())
                )
        );

        audit.record(
                approved
                        ? "WORK_INSIGHT_CEO_APPROVED"
                        : "WORK_INSIGHT_CEO_REWORK_REQUESTED",
                "WORK_TASK_AUDIT",
                record.getId().toString(),
                "{\"workTaskId\":\""
                        + record.getWorkTaskId()
                        + "\"}"
        );

        return retainedInsight(record);
    }
    private WorkTaskAuditRecord requireRecord(UUID recordId) {
        return audits.findById(recordId).orElseThrow(() -> new BusinessException(
                "WORK_INSIGHT_NOT_FOUND", "The weekly work audit was not found", HttpStatus.NOT_FOUND));
    }

    private Insight liveInsight(TaskSnapshot task, WorkTaskAuditRecord record) {
        EmployeeDirectory.EmployeeSummary employee = employees.employeeSummary(task.employeeId());
        String teamLeadName = staff.findByUserId(task.teamLeadUserId())
                .map(StaffCommunicationDirectory.StaffMember::fullName)
                .orElse("Former Team Lead");
        return new Insight(record == null ? null : record.getId(), task.id(),
                weekStart(task.createdAt()), task.departmentId(), task.departmentBranch(),
                task.employeeId(), employee.employeeNumber(), employee.displayName(),
                task.teamLeadUserId(), teamLeadName, task.assignedByRole(), task.assigneeRole(),
                task.title(), task.status(),
                record == null ? "NOT_AUDITED" : record.getAuditStatus().name(),
                record == null ? null : record.getHrAuditedAt(),
                record == null ? null : record.getManagerDecidedAt(),
                record == null ? null : record.getManagerRemarks(),
                record == null ? null : record.getCeoDecidedAt(),
                record == null ? null : record.getCeoRemarks(),
                record == null ? null : record.getReworkRequestedByRole(),
                record == null ? null : record.getReworkReason(),
                record == null ? null : record.getReworkRequestedAt(),
                record == null ? null : record.getTeamLeadReworkGuidance(),
                record == null ? null : record.getTeamLeadRespondedAt(),
                record == null ? 0 : record.getReworkCycle());
    }

    private Insight retainedInsight(WorkTaskAuditRecord record) {
        return new Insight(record.getId(), record.getWorkTaskId(), record.getWeekStart(),
                record.getDepartmentId(), record.getDepartmentName(), record.getEmployeeId(),
                record.getEmployeeNumber(), record.getEmployeeName(), record.getTeamLeadUserId(),
                record.getTeamLeadName(), record.getAssignedByRole(), record.getAssigneeRole(),
                record.getTaskTitle(), record.getTaskStatus(), record.getAuditStatus().name(),
                record.getHrAuditedAt(), record.getManagerDecidedAt(), record.getManagerRemarks(),
                record.getCeoDecidedAt(), record.getCeoRemarks(), record.getReworkRequestedByRole(),
                record.getReworkReason(), record.getReworkRequestedAt(),
                record.getTeamLeadReworkGuidance(), record.getTeamLeadRespondedAt(),
                record.getReworkCycle());
    }

    private TaskSnapshot requireAuditReadyTask(UUID workTaskId) {
        TaskSnapshot task = tasks.requireTask(workTaskId);
        if (!isReadyForHrAudit(task)) {
            String requiredStage = TEAM_LEAD_ASSIGNEE.equals(task.assigneeRole())
                    ? "Team Lead completion" : "Team Lead approval";
            throw new BusinessException("WORK_INSIGHT_TASK_NOT_FINAL",
                    "HR can audit this worksheet only after " + requiredStage,
                    HttpStatus.CONFLICT);
        }
        return task;
    }

    private boolean isReadyForHrAudit(TaskSnapshot task) {
        return TEAM_LEAD_ASSIGNEE.equals(task.assigneeRole())
                ? "COMPLETED".equals(task.status())
                : "APPROVED".equals(task.status()) || "ACKNOWLEDGED".equals(task.status());
    }

    private EmployeeDirectory.EmployeeSummary employee(TaskSnapshot task) {
        return employees.employeeSummary(task.employeeId());
    }

    private WorkTaskAuditRecord newRecord(TaskSnapshot task, UUID hrUserId) {
        EmployeeDirectory.EmployeeSummary employee = employee(task);
        String teamLeadName = staff.findByUserId(task.teamLeadUserId())
                .map(StaffCommunicationDirectory.StaffMember::fullName)
                .orElse("Former Team Lead");
        return new WorkTaskAuditRecord(task.id(), weekStart(Instant.now()), task.departmentId(),
                task.departmentBranch(), task.employeeId(), employee.employeeNumber(),
                employee.displayName(), task.teamLeadUserId(), teamLeadName,
                task.assignedByRole(), task.assigneeRole(), task.title(), task.status(), hrUserId);
    }

    private void requireRole(UUID userId, String role, String message) {
        if (CEO.equals(role)) {
            if (!staff.requireChiefExecutive().userId().equals(userId)) {
                throw new BusinessException("WORK_INSIGHT_ROLE_REQUIRED", message, HttpStatus.FORBIDDEN);
            }
            return;
        }
        if (!staff.requireActive(userId).roles().contains(role)) {
            throw new BusinessException("WORK_INSIGHT_ROLE_REQUIRED", message, HttpStatus.FORBIDDEN);
        }
    }

    private LocalDate normalizeWeek(LocalDate value) {
        LocalDate date = value == null ? LocalDate.now(officeZone) : value;
        return date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    private LocalDate weekStart(Instant value) {
        return value.atZone(officeZone).toLocalDate()
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    public record Insight(UUID auditRecordId, UUID workTaskId, LocalDate weekStart,
                          UUID departmentId, String departmentName, UUID employeeId,
                          String employeeNumber, String employeeName, UUID teamLeadUserId,
                          String teamLeadName, String assignedByRole, String assigneeRole,
                          String taskTitle, String taskStatus, String auditStatus,
                          Instant hrAuditedAt, Instant managerDecidedAt, String managerRemarks,
                          Instant ceoDecidedAt, String ceoRemarks, String reworkRequestedByRole,
                          String reworkReason, Instant reworkRequestedAt,
                          String teamLeadReworkGuidance, Instant teamLeadRespondedAt,
                          int reworkCycle) {}

    public record TaskWorkflowState(UUID workTaskId, String auditStatus) {}
}
