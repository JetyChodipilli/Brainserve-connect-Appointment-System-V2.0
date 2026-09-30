package com.brainserve.appointment.worktask.application;

import com.brainserve.appointment.audit.api.AuditService;
import com.brainserve.appointment.departmenthr.api.DepartmentHrDirectory;
import com.brainserve.appointment.employee.api.EmployeeDirectory;
import com.brainserve.appointment.iam.api.StaffCommunicationDirectory;
import com.brainserve.appointment.manager.api.ManagerDirectory;
import com.brainserve.appointment.organization.api.OrganizationDirectory;
import com.brainserve.appointment.shared.application.BusinessException;
import com.brainserve.appointment.teamlead.api.TeamLeadDirectory;
import com.brainserve.appointment.worktask.api.WorkTaskDirectory;
import com.brainserve.appointment.worktask.api.WorkTaskEvents;
import com.brainserve.appointment.worktask.domain.DepartmentWorkTask;
import com.brainserve.appointment.worktask.infrastructure.DepartmentWorkTaskRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class DepartmentWorkTaskService implements WorkTaskDirectory {
    private static final String HR = "ROLE_HR_ADMIN";
    private static final String TEAM_LEAD = "ROLE_TEAM_LEAD";
    private static final String EMPLOYEE = "ROLE_EMPLOYEE";
    private static final String MANAGER = "ROLE_MANAGER";
    private static final String HR_ASSIGNER = "HR_ADMIN";
    private static final String TEAM_LEAD_ASSIGNER = "TEAM_LEAD";
    private static final String TEAM_LEAD_ASSIGNEE = "TEAM_LEAD";
    private static final String EMPLOYEE_ASSIGNEE = "EMPLOYEE";

    private final DepartmentWorkTaskRepository tasks;
    private final EmployeeDirectory employees;
    private final TeamLeadDirectory teamLeads;
    private final OrganizationDirectory organization;
    private final StaffCommunicationDirectory staff;
    private final ApplicationEventPublisher events;
    private final AuditService audit;
    private final DepartmentHrDirectory departmentHrs;
    private final ManagerDirectory managers;

    public DepartmentWorkTaskService(DepartmentWorkTaskRepository tasks, EmployeeDirectory employees,
                                     TeamLeadDirectory teamLeads, OrganizationDirectory organization,
                                     StaffCommunicationDirectory staff,
                                     ApplicationEventPublisher events, AuditService audit,
                                     DepartmentHrDirectory departmentHrs, ManagerDirectory managers) {
        this.tasks = tasks;
        this.employees = employees;
        this.teamLeads = teamLeads;
        this.organization = organization;
        this.staff = staff;
        this.events = events;
        this.audit = audit;
        this.departmentHrs = departmentHrs;
        this.managers = managers;
    }

    @Transactional
    public DepartmentWorkTask create(UUID actorUserId, CreateCommand command) {
        Set<String> actorRoles = staff.requireActive(actorUserId).roles();
        if (actorRoles.contains(HR)) return createByHr(actorUserId, command);
        if (actorRoles.contains(TEAM_LEAD)) return createByTeamLead(actorUserId, command);
        throw new BusinessException("WORK_TASK_CREATE_DENIED",
                "Only the assigned HR or Team Lead can create department work", HttpStatus.FORBIDDEN);
    }

    private DepartmentWorkTask createByTeamLead(UUID teamLeadUserId, CreateCommand command) {
        TeamLeadDirectory.Assignment lead = teamLeads.requireForUser(teamLeadUserId);
        OrganizationDirectory.ActiveDepartment department = organization.requireActiveDepartment(lead.departmentId());
        requireEmployeeInDepartment(command.employeeId(), lead.departmentId());
        if (lead.teamLeadEmployeeId().equals(command.employeeId())) {
            throw new BusinessException("WORK_TASK_SELF_ASSIGNMENT_NOT_ALLOWED",
                    "A Team Lead cannot assign a worksheet to themselves", HttpStatus.UNPROCESSABLE_ENTITY);
        }
        var recipient = staff.activeByEmployeeId(command.employeeId())
                .filter(member -> member.roles().contains(EMPLOYEE))
                .orElseThrow(() -> new BusinessException("WORK_TASK_EMPLOYEE_LOGIN_REQUIRED",
                        "The selected person must have an active Employee login", HttpStatus.UNPROCESSABLE_ENTITY));
        return saveAndNotify(teamLeadUserId, TEAM_LEAD_ASSIGNER, EMPLOYEE_ASSIGNEE,
                lead, department, recipient, command);
    }

    private DepartmentWorkTask createByHr(UUID hrUserId, CreateCommand command) {
        DepartmentHrDirectory.Assignment hr = departmentHrs.requireForUser(hrUserId);
        OrganizationDirectory.ActiveDepartment department = organization.requireActiveDepartment(hr.departmentId());
        TeamLeadDirectory.Assignment lead = teamLeads.activeForDepartment(hr.departmentId())
                .orElseThrow(() -> new BusinessException("WORK_TASK_TEAM_LEAD_REQUIRED",
                        "Assign an active Team Lead to this department before creating work",
                        HttpStatus.UNPROCESSABLE_ENTITY));
        requireEmployeeInDepartment(command.employeeId(), hr.departmentId());
        var recipient = staff.activeByEmployeeId(command.employeeId())
                .orElseThrow(() -> new BusinessException("WORK_TASK_ASSIGNEE_LOGIN_REQUIRED",
                        "The selected person must have an active Employee or Team Lead login",
                        HttpStatus.UNPROCESSABLE_ENTITY));
        String assigneeRole;
        if (recipient.roles().contains(TEAM_LEAD)
                && recipient.userId().equals(lead.teamLeadUserId())
                && command.employeeId().equals(lead.teamLeadEmployeeId())) {
            assigneeRole = TEAM_LEAD_ASSIGNEE;
        } else if (recipient.roles().contains(EMPLOYEE)) {
            assigneeRole = EMPLOYEE_ASSIGNEE;
        } else {
            throw new BusinessException("WORK_TASK_ASSIGNEE_ROLE_REQUIRED",
                    "HR can assign department work only to an Employee or the active Team Lead",
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }
        return saveAndNotify(hrUserId, HR_ASSIGNER, assigneeRole, lead, department, recipient, command);
    }

    private DepartmentWorkTask saveAndNotify(UUID actorUserId, String assignedByRole, String assigneeRole,
                                             TeamLeadDirectory.Assignment lead,
                                             OrganizationDirectory.ActiveDepartment department,
                                             StaffCommunicationDirectory.StaffMember recipient,
                                             CreateCommand command) {
        DepartmentWorkTask created = tasks.saveAndFlush(new DepartmentWorkTask(
                lead.departmentId(), command.employeeId(), lead.teamLeadUserId(), actorUserId,
                assignedByRole, assigneeRole, command.title(), command.description(), department.name(),
                command.dueDate()));
        String reviewer = EMPLOYEE_ASSIGNEE.equals(assigneeRole)
                ? " Your Team Lead will review the completed delivery before HR audit."
                : " Submit the completed delivery directly to HR audit; self-approval is not permitted.";
        events.publishEvent(new WorkTaskEvents.DirectNotificationRequested(actorUserId, recipient.userId(),
                "New " + department.name() + " task sheet assigned: " + command.title() + ". Due "
                        + command.dueDate() + ". Open Work Board to review and start it." + reviewer));
        audit(created, "ASSIGNED");
        return created;
    }

    private void requireEmployeeInDepartment(UUID employeeId, UUID departmentId) {
        employees.requireActiveEmployee(employeeId);
        if (!employees.departmentIdForEmployee(employeeId).equals(departmentId)) {
            throw new BusinessException("WORK_TASK_DEPARTMENT_MISMATCH",
                    "Work can be assigned only within the actor's department", HttpStatus.FORBIDDEN);
        }
    }

    @Transactional(readOnly = true)
    public List<DepartmentWorkTask> list(UUID userId, UUID employeeId) {
        Set<String> roles = staff.requireActive(userId).roles();
        if (roles.contains(HR)) {
            return tasks.findTop500ByDepartmentIdOrderByCreatedAtDesc(
                    departmentHrs.requireForUser(userId).departmentId());
        }
        if (roles.contains(TEAM_LEAD)) {
            return tasks.findTop500ByDepartmentIdOrderByCreatedAtDesc(
                    teamLeads.requireForUser(userId).departmentId());
        }
        if (roles.contains(MANAGER)) {
            return tasks.findTop500ByDepartmentIdOrderByCreatedAtDesc(
                    managers.requireForUser(userId).departmentId());
        }
        if (roles.contains(EMPLOYEE) && employeeId != null) {
            return tasks.findTop200ByEmployeeIdOrderByCreatedAtDesc(employeeId);
        }
        throw new BusinessException("WORK_TASK_ROLE_REQUIRED",
                "Only Employees, Team Leads, HR and the assigned Manager can view department work",
                HttpStatus.FORBIDDEN);
    }

    @Transactional(readOnly = true)
    public Workspace workspace(UUID actorUserId) {
        Set<String> roles = staff.requireActive(actorUserId).roles();
        UUID departmentId;
        UUID excludedEmployeeId = null;
        TeamLeadDirectory.Assignment activeLead;
        boolean hrWorkspace;

        if (roles.contains(HR)) {
            departmentId = departmentHrs.requireForUser(actorUserId).departmentId();
            activeLead = teamLeads.activeForDepartment(departmentId).orElse(null);
            hrWorkspace = true;
        } else if (roles.contains(TEAM_LEAD)) {
            activeLead = teamLeads.requireForUser(actorUserId);
            departmentId = activeLead.departmentId();
            excludedEmployeeId = activeLead.teamLeadEmployeeId();
            hrWorkspace = false;
        } else {
            throw new BusinessException("WORK_TASK_CREATE_DENIED",
                    "Only the assigned HR or Team Lead can load task assignees", HttpStatus.FORBIDDEN);
        }

        OrganizationDirectory.ActiveDepartment department = organization.requireActiveDepartment(departmentId);
        List<StaffCommunicationDirectory.StaffMember> departmentMembers =
                staff.activeWithAnyRoleInDepartment(Set.of(EMPLOYEE, TEAM_LEAD), departmentId, 200);
        Set<UUID> employeeIds = departmentMembers.stream()
                .map(StaffCommunicationDirectory.StaffMember::employeeId)
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        java.util.Map<UUID, EmployeeDirectory.EmployeeSummary> employeeSummaries =
                employees.employeeSummaries(employeeIds);

        List<EligibleAssignee> eligible = new ArrayList<>();
        for (StaffCommunicationDirectory.StaffMember member : departmentMembers) {
            if (member.employeeId() == null || member.employeeId().equals(excludedEmployeeId)) continue;
            EmployeeDirectory.EmployeeSummary employee = employeeSummaries.get(member.employeeId());
            if (employee == null) continue;
            if (!departmentId.equals(employee.departmentId()) || !"ACTIVE".equals(employee.status())) continue;

            boolean assignedTeamLead = activeLead != null
                    && activeLead.teamLeadUserId().equals(member.userId())
                    && activeLead.teamLeadEmployeeId().equals(member.employeeId())
                    && member.roles().contains(TEAM_LEAD);
            String role;
            if (assignedTeamLead && hrWorkspace) role = TEAM_LEAD_ASSIGNEE;
            else if (member.roles().contains(EMPLOYEE)) role = EMPLOYEE_ASSIGNEE;
            else continue;

            eligible.add(new EligibleAssignee(employee.id(), employee.displayName(),
                    employee.designation(), role));
        }
        eligible.sort(Comparator.comparing(EligibleAssignee::displayName, String.CASE_INSENSITIVE_ORDER));
        return new Workspace(department.id(), department.code(), department.name(), List.copyOf(eligible));
    }

    @Override
    @Transactional(readOnly = true)
    public List<TaskSnapshot> recentForDepartment(UUID departmentId) {
        return tasks.findTop500ByDepartmentIdOrderByCreatedAtDesc(departmentId)
                .stream()
                .map(this::snapshot)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public TaskSnapshot requireTask(UUID workTaskId) {
        return snapshot(require(workTaskId));
    }

    @Override
    @Transactional
    public TaskSnapshot requestInsightRework(UUID workTaskId, String reviewerRole, String reason) {
        DepartmentWorkTask task = require(workTaskId);
        task.requestInsightRework(reviewerRole, reason);
        return snapshot(task);
    }

    @Override
    @Transactional
    public TaskSnapshot assignInsightRework(UUID workTaskId, String guidance) {
        DepartmentWorkTask task = require(workTaskId);
        task.assignInsightRework(guidance);
        return snapshot(task);
    }

    @Override
    @Transactional
    public TaskSnapshot reviseInsightReworkSubmission(UUID teamLeadUserId, UUID workTaskId,
                                                      String update) {
        DepartmentWorkTask task = requireTeamLeadScope(teamLeadUserId, workTaskId);
        if (!TEAM_LEAD_ASSIGNEE.equals(task.getAssigneeRole())) {
            throw new BusinessException("WORK_INSIGHT_REWORK_UPDATE_DENIED",
                    "Only a Team Lead worksheet assigned by HR can use this rework update",
                    HttpStatus.CONFLICT);
        }
        task.reviseInsightReworkSubmission(update);
        publishHrNotification(teamLeadUserId, task,
                "Team Lead updated and resubmitted rework for worksheet ‘" + task.getTitle()
                        + "’. It is ready for HR re-audit.");
        audit(task, "REWORK_RESUBMITTED");
        return snapshot(task);
    }

    @Override
    @Transactional
    public TaskSnapshot finalizeInsightApproval(UUID workTaskId) {
        DepartmentWorkTask task = require(workTaskId);
        task.finalizeInsightApproval();
        audit(task, "GOVERNANCE_APPROVED");
        return snapshot(task);
    }

    @Transactional
    public DepartmentWorkTask start(UUID userId, UUID employeeId, UUID taskId, String update) {
        DepartmentWorkTask task = requireProgressScope(userId, employeeId, taskId);
        task.start(update);
        notifyProgress(userId, task, "started", update);
        audit(task, "IN_PROGRESS");
        return task;
    }

    @Transactional
    public DepartmentWorkTask complete(UUID userId, UUID employeeId, UUID taskId, String update) {
        DepartmentWorkTask task = requireProgressScope(userId, employeeId, taskId);
        task.complete(update);
        if (TEAM_LEAD_ASSIGNEE.equals(task.getAssigneeRole())) {
            publishHrNotification(userId, task,
                    "Team Lead completed HR-assigned worksheet ‘" + task.getTitle()
                            + "’ in " + task.getDepartmentBranch() + ". HR audit is required.");
        } else {
            notifyProgress(userId, task, "marked completed and is waiting for Team Lead approval", update);
        }
        audit(task, "COMPLETED");
        return task;
    }

    @Transactional
    public DepartmentWorkTask reviseEmployeeRework(UUID employeeUserId, UUID employeeId,
                                                   UUID taskId, String update) {
        DepartmentWorkTask task = requireEmployeeScope(employeeId, taskId);
        task.reviseEmployeeReworkSubmission(update);
        events.publishEvent(new WorkTaskEvents.DirectNotificationRequested(employeeUserId,
                task.getTeamLeadUserId(),
                "Employee updated and resubmitted rework for worksheet ‘" + task.getTitle()
                        + "’. Review the corrected delivery in Work Board."));
        audit(task, "REWORK_RESUBMITTED");
        return task;
    }

    @Transactional
    public DepartmentWorkTask approve(UUID teamLeadUserId, UUID taskId, String review) {
        DepartmentWorkTask task = requireTeamLeadReviewScope(teamLeadUserId, taskId);
        task.approve(review);
        events.publishEvent(new WorkTaskEvents.DirectNotificationRequested(teamLeadUserId, employeeUserId(task),
                "Your completed task ‘" + task.getTitle()
                        + "’ was approved by your Team Lead. Open Work Board to acknowledge the decision."));
        publishHrNotification(teamLeadUserId, task,
                "Team Lead reviewed and approved ‘" + task.getTitle() + "’ in department branch "
                        + task.getDepartmentBranch() + ". HR audit is required.");
        audit(task, "APPROVED");
        return task;
    }

    @Transactional
    public DepartmentWorkTask requestChanges(UUID teamLeadUserId, UUID taskId, String review) {
        DepartmentWorkTask task = requireTeamLeadReviewScope(teamLeadUserId, taskId);
        task.requestChanges(review);
        events.publishEvent(new WorkTaskEvents.DirectNotificationRequested(teamLeadUserId, employeeUserId(task),
                "Changes were requested for ‘" + task.getTitle() + "’: " + review.trim()));
        audit(task, "CHANGES_REQUESTED");
        return task;
    }

    @Transactional
    public DepartmentWorkTask acknowledge(UUID employeeUserId, UUID employeeId, UUID taskId) {
        DepartmentWorkTask task = requireEmployeeScope(employeeId, taskId);
        task.acknowledge();
        events.publishEvent(new WorkTaskEvents.DirectNotificationRequested(employeeUserId,
                task.getTeamLeadUserId(),
                "Employee acknowledged Team Lead approval for ‘" + task.getTitle() + "’."));
        publishHrNotification(employeeUserId, task,
                "Employee acknowledged the approved worksheet ‘" + task.getTitle()
                        + "’. It is ready for HR audit.");
        audit(task, "ACKNOWLEDGED");
        return task;
    }

    @Transactional(readOnly = true)
    public List<Performance> performance(UUID hrUserId) {
        UUID departmentId = departmentHrs.requireForUser(hrUserId).departmentId();
        return tasks.performance().stream()
                .filter(value -> value.getDepartmentId().equals(departmentId))
                .map(value -> new Performance(value.getTeamLeadUserId(), value.getDepartmentId(),
                        value.getTotalTasks(), value.getCompletedTasks(), value.getApprovedTasks(),
                        value.getInProgressTasks(), value.getPendingReviewTasks(), value.getOverdueTasks(),
                        value.getTotalTasks() == 0 ? 0
                                : Math.round(value.getApprovedTasks() * 100.0 / value.getTotalTasks()),
                        value.getLastApprovedAt()))
                .toList();
    }

    private DepartmentWorkTask requireProgressScope(UUID userId, UUID employeeId, UUID taskId) {
        var member = staff.requireActive(userId);
        if (member.roles().contains(TEAM_LEAD)) {
            DepartmentWorkTask task = requireTeamLeadScope(userId, taskId);
            TeamLeadDirectory.Assignment lead = teamLeads.requireForUser(userId);
            if (!TEAM_LEAD_ASSIGNEE.equals(task.getAssigneeRole())
                    || !lead.teamLeadEmployeeId().equals(task.getEmployeeId())) {
                throw new BusinessException("WORK_TASK_PROGRESS_DENIED",
                        "A Team Lead can progress only a worksheet assigned to that Team Lead by HR",
                        HttpStatus.FORBIDDEN);
            }
            return task;
        }
        if (member.roles().contains(EMPLOYEE) && employeeId != null) {
            return requireEmployeeScope(employeeId, taskId);
        }
        throw new BusinessException("WORK_TASK_PROGRESS_DENIED", "You cannot update this task",
                HttpStatus.FORBIDDEN);
    }

    private DepartmentWorkTask requireTeamLeadReviewScope(UUID teamLeadUserId, UUID taskId) {
        DepartmentWorkTask task = requireTeamLeadScope(teamLeadUserId, taskId);
        if (!task.requiresTeamLeadReview()) {
            throw new BusinessException("WORK_TASK_SELF_REVIEW_NOT_ALLOWED",
                    "A Team Lead cannot approve or return their own HR-assigned worksheet",
                    HttpStatus.FORBIDDEN);
        }
        return task;
    }

    private DepartmentWorkTask requireTeamLeadScope(UUID teamLeadUserId, UUID taskId) {
        DepartmentWorkTask task = require(taskId);
        TeamLeadDirectory.Assignment lead = teamLeads.requireForUser(teamLeadUserId);
        if (!task.getDepartmentId().equals(lead.departmentId())
                || !task.getTeamLeadUserId().equals(teamLeadUserId)) {
            throw new BusinessException("WORK_TASK_TEAM_LEAD_SCOPE_DENIED",
                    "This task belongs to another Team Lead or department", HttpStatus.FORBIDDEN);
        }
        return task;
    }

    private DepartmentWorkTask requireEmployeeScope(UUID employeeId, UUID taskId) {
        DepartmentWorkTask task = require(taskId);
        if (!EMPLOYEE_ASSIGNEE.equals(task.getAssigneeRole()) || !task.getEmployeeId().equals(employeeId)) {
            throw new BusinessException("WORK_TASK_EMPLOYEE_SCOPE_DENIED",
                    "This task is assigned to another employee", HttpStatus.FORBIDDEN);
        }
        return task;
    }

    private DepartmentWorkTask require(UUID taskId) {
        return tasks.findById(taskId).orElseThrow(() -> new BusinessException("WORK_TASK_NOT_FOUND",
                "The work task was not found", HttpStatus.NOT_FOUND));
    }

    private UUID employeeUserId(DepartmentWorkTask task) {
        return staff.activeByEmployeeId(task.getEmployeeId())
                .map(StaffCommunicationDirectory.StaffMember::userId)
                .orElseThrow(() -> new BusinessException("WORK_TASK_ASSIGNEE_LOGIN_REQUIRED",
                        "The assigned Employee or Team Lead login is no longer active",
                        HttpStatus.CONFLICT));
    }

    private TaskSnapshot snapshot(DepartmentWorkTask task) {
        return new TaskSnapshot(task.getId(), task.getCreatedAt(), task.getDepartmentId(),
                task.getDepartmentBranch(), task.getEmployeeId(), task.getTeamLeadUserId(),
                task.getAssignedByUserId(), task.getAssignedByRole(), task.getAssigneeRole(),
                task.getTitle(), task.getStatus().name());
    }

    private void notifyProgress(UUID actorUserId, DepartmentWorkTask task, String action, String update) {
        if (TEAM_LEAD_ASSIGNEE.equals(task.getAssigneeRole())) {
            String note = update == null || update.isBlank() ? "" : " Update: " + update.trim();
            publishHrNotification(actorUserId, task,
                    "Team Lead worksheet ‘" + task.getTitle() + "’ was " + action + "." + note);
            return;
        }
        String note = update == null || update.isBlank() ? "" : " Update: " + update.trim();
        events.publishEvent(new WorkTaskEvents.DirectNotificationRequested(actorUserId,
                task.getTeamLeadUserId(), "Task ‘" + task.getTitle() + "’ was " + action + "." + note));
    }

    private void publishHrNotification(UUID actorUserId, DepartmentWorkTask task, String message) {
        events.publishEvent(new WorkTaskEvents.HrNotificationRequested(
                actorUserId, task.getDepartmentId(), message));
    }

    private void audit(DepartmentWorkTask task, String action) {
        audit.record("WORK_TASK_" + action, "WORK_TASK", task.getId().toString(),
                "{\"departmentId\":\"" + task.getDepartmentId() + "\",\"employeeId\":\""
                        + task.getEmployeeId() + "\",\"assignedByRole\":\""
                        + task.getAssignedByRole() + "\",\"assigneeRole\":\""
                        + task.getAssigneeRole() + "\"}");
    }

    public record CreateCommand(UUID employeeId, String title, String description, LocalDate dueDate) {}

    public record EligibleAssignee(UUID employeeId, String displayName,
                                   String designation, String role) {}

    public record Workspace(UUID departmentId, String departmentCode, String departmentName,
                            List<EligibleAssignee> eligibleAssignees) {}

    public record Performance(UUID teamLeadUserId, UUID departmentId, long totalTasks,
                              long completedTasks, long approvedTasks, long inProgressTasks,
                              long pendingReviewTasks, long overdueTasks, long completionRate,
                              Instant lastApprovedAt) {}
}
