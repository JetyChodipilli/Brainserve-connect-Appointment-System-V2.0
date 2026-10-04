package com.brainserve.appointment.worktask.application;

import com.brainserve.appointment.audit.api.AuditService;
import com.brainserve.appointment.departmenthr.api.DepartmentHrDirectory;
import com.brainserve.appointment.employee.api.EmployeeDirectory;
import com.brainserve.appointment.iam.api.StaffCommunicationDirectory;
import com.brainserve.appointment.iam.api.CurrentAccountAuthority;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
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
public class DepartmentWorkTaskService implements WorkTaskDirectory, com.brainserve.appointment.worktask.api.ScheduledWorkMaterializer {
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
    private final CurrentAccountAuthority authority;
    private final EntityManager entityManager;
    private com.brainserve.appointment.notification.api.RecurringWorkNotifications recurringNotifications;

    @org.springframework.beans.factory.annotation.Autowired
    public void recurringNotifications(com.brainserve.appointment.notification.api.RecurringWorkNotifications notifications) {
        this.recurringNotifications = notifications;
    }

    public DepartmentWorkTaskService(DepartmentWorkTaskRepository tasks, EmployeeDirectory employees,
                                     TeamLeadDirectory teamLeads, OrganizationDirectory organization,
                                     StaffCommunicationDirectory staff,
                                     ApplicationEventPublisher events, AuditService audit,
                                     DepartmentHrDirectory departmentHrs, ManagerDirectory managers,
                                     CurrentAccountAuthority authority, EntityManager entityManager) {
        this.tasks = tasks;
        this.employees = employees;
        this.teamLeads = teamLeads;
        this.organization = organization;
        this.staff = staff;
        this.events = events;
        this.audit = audit;
        this.departmentHrs = departmentHrs;
        this.managers = managers;
        this.authority = authority;
        this.entityManager = entityManager;
    }

    @Transactional
    public DepartmentWorkTask create(UUID actorUserId, CreateCommand command) {
        Set<String> actorRoles = staff.requireActive(actorUserId).roles();
        if (actorRoles.contains(HR)) return createByHr(actorUserId, command);
        if (actorRoles.contains(TEAM_LEAD)) return createByTeamLead(actorUserId, command);
        throw new BusinessException("WORK_TASK_CREATE_DENIED",
                "Only the assigned HR or Team Lead can create department work", HttpStatus.FORBIDDEN);
    }

    @Override
    @Transactional(readOnly = true)
    public com.brainserve.appointment.worktask.api.ScheduledWorkMaterializer.Workspace routineWorkspace(UUID actor) {
        Workspace workspace = workspace(actor);
        return new com.brainserve.appointment.worktask.api.ScheduledWorkMaterializer.Workspace(workspace.departmentId(), workspace.departmentName(),
                workspace.eligibleAssignees().stream().map(a -> new com.brainserve.appointment.worktask.api.ScheduledWorkMaterializer.EligibleAssignee(a.employeeId(),a.displayName(),a.role())).toList());
    }

    @Override
    public void validateScheduled(UUID actor, com.brainserve.appointment.worktask.api.ScheduledWorkMaterializer.Command command) {
        var current = requirePermission(actor,"WORK_TASK_CREATE");
        var scope = authority.requireWorkScope(actor);
        requireCurrentAuthority(current,scope.authority());
        if (!scope.departmentId().equals(command.departmentId())
                || !Set.of(HR,TEAM_LEAD).contains(current.role())) {
            throw new BusinessException("WORK_TASK_CREATE_DENIED","The routine creator no longer has this department assignment",HttpStatus.FORBIDDEN);
        }
        if (command.employeeId().equals(current.employeeId())) throw new BusinessException("WORK_TASK_SELF_ASSIGNMENT_NOT_ALLOWED","A routine cannot assign work to its creator",HttpStatus.UNPROCESSABLE_ENTITY);
        var activeLead=teamLeads.activeForDepartment(scope.departmentId()).orElseThrow(() -> new BusinessException(
                "WORK_TASK_TEAM_LEAD_REQUIRED","Assign an active Team Lead before creating recurring work",HttpStatus.UNPROCESSABLE_ENTITY));
        var leadScope=authority.requireWorkScope(activeLead.teamLeadUserId());
        if(!TEAM_LEAD.equals(leadScope.authority().role()) || !scope.departmentId().equals(leadScope.departmentId())
                || !activeLead.teamLeadEmployeeId().equals(leadScope.authority().employeeId())) throw new BusinessException(
                "WORK_TASK_TEAM_LEAD_REQUIRED","The current review Team Lead must remain active in this department",HttpStatus.UNPROCESSABLE_ENTITY);
        var candidate = workspace(actor).eligibleAssignees().stream()
                .filter(a -> a.employeeId().equals(command.employeeId()) && a.role().equals(command.assigneeRule()))
                .findFirst().orElseThrow(() -> new BusinessException("WORK_TASK_ASSIGNEE_ROLE_REQUIRED",
                        "The scheduled assignee needs an active login with the exact template role in this department",HttpStatus.UNPROCESSABLE_ENTITY));
        var recipient = staff.activeByEmployeeId(candidate.employeeId()).orElseThrow(() -> new BusinessException(
                "WORK_TASK_ASSIGNEE_LOGIN_REQUIRED","The scheduled assignee needs an active login",HttpStatus.UNPROCESSABLE_ENTITY));
        var recipientAuthority = authority.requireActive(recipient.userId());
        if (!("ROLE_" + command.assigneeRule()).equals(recipientAuthority.role())) throw new BusinessException(
                "WORK_TASK_ASSIGNEE_ROLE_REQUIRED","The scheduled assignee's role changed",HttpStatus.UNPROCESSABLE_ENTITY);
        requireUnchangedScope(actor,scope);
    }

    @Override
    @Transactional
    public UUID createScheduled(UUID actor, com.brainserve.appointment.worktask.api.ScheduledWorkMaterializer.Command command) {
        validateScheduled(actor,command);
        if (recurringNotifications == null) throw new IllegalStateException("Recurring notification outbox is not available");
        var current = authority.requireWorkScope(actor);
        CreateCommand values=new CreateCommand(command.employeeId(),command.title(),command.instructions(),command.dueDate());
        DepartmentWorkTask created=HR.equals(current.authority().role())?createByHr(actor,values,command):createByTeamLead(actor,values,command);
        requireUnchangedScope(actor,current);
        return created.getId();
    }

    private DepartmentWorkTask createByTeamLead(UUID teamLeadUserId, CreateCommand command) {
        return createByTeamLead(teamLeadUserId,command,null);
    }

    private DepartmentWorkTask createByTeamLead(UUID teamLeadUserId, CreateCommand command,
            com.brainserve.appointment.worktask.api.ScheduledWorkMaterializer.Command recurring) {
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
                lead, department, recipient, command,recurring);
    }

    private DepartmentWorkTask createByHr(UUID hrUserId, CreateCommand command) {
        return createByHr(hrUserId,command,null);
    }

    private DepartmentWorkTask createByHr(UUID hrUserId, CreateCommand command,
            com.brainserve.appointment.worktask.api.ScheduledWorkMaterializer.Command recurring) {
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
        return saveAndNotify(hrUserId, HR_ASSIGNER, assigneeRole, lead, department, recipient, command,recurring);
    }

    private DepartmentWorkTask saveAndNotify(UUID actorUserId, String assignedByRole, String assigneeRole,
                                             TeamLeadDirectory.Assignment lead,
                                             OrganizationDirectory.ActiveDepartment department,
                                             StaffCommunicationDirectory.StaffMember recipient,
                                             CreateCommand command,
                                             com.brainserve.appointment.worktask.api.ScheduledWorkMaterializer.Command recurring) {
        DepartmentWorkTask created = new DepartmentWorkTask(
                lead.departmentId(), command.employeeId(), lead.teamLeadUserId(), actorUserId,
                assignedByRole, assigneeRole, command.title(), command.description(), department.name(),
                command.dueDate());
        if(recurring!=null) for(var item:recurring.checklist())
            created.getPlanning().checklist.add(new com.brainserve.appointment.worktask.domain.TaskPlanningState.ChecklistItem(
                    UUID.randomUUID(),item.title(),created.getPlanning().checklist.size(),item.required(),false));
        // Manual and recurring worksheets share this first insert and original-deadline trigger.
        tasks.saveAndFlush(created);
        String reviewer = EMPLOYEE_ASSIGNEE.equals(assigneeRole)
                ? " Your Team Lead will review the completed delivery before HR audit."
                : " Submit the completed delivery directly to HR audit; self-approval is not permitted.";
        String message="New " + department.name() + " task sheet assigned: " + command.title() + ". Due "
                + command.dueDate() + ". Open Work Board to review and start it." + reviewer;
        if(recurring==null) events.publishEvent(new WorkTaskEvents.DirectNotificationRequested(actorUserId,recipient.userId(),message));
        else recurringNotifications.enqueue(recurring.eventKey(),actorUserId,recipient.userId(),staff.requireActive(actorUserId).fullName(),recipient.fullName(),message);
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
        var current = requirePermission(userId, "WORK_TASK_READ");
        var scope = authority.requireWorkScope(userId);
        requireCurrentAuthority(current, scope.authority());
        List<DepartmentWorkTask> result = "ROLE_EMPLOYEE".equals(current.role())
                ? tasks.findTop200ByEmployeeIdOrderByCreatedAtDesc(current.employeeId()).stream()
                    .filter(task -> "EMPLOYEE".equals(task.getAssigneeRole()) && scope.departmentId().equals(task.getDepartmentId())).toList()
                : tasks.findTop500ByDepartmentIdOrderByCreatedAtDesc(scope.departmentId());
        requireUnchangedScope(userId, scope);
        return result;
    }

    @Transactional(readOnly = true)
    public Workspace workspace(UUID actorUserId) {
        var current = requirePermission(actorUserId, "WORK_TASK_CREATE");
        var currentScope = authority.requireWorkScope(actorUserId);
        requireCurrentAuthority(current, currentScope.authority());
        Set<String> roles = Set.of(current.role());
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
        requireUnchangedScope(actorUserId, currentScope);
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
    public TaskSnapshot requireTaskForMutation(UUID taskId, Long expectedVersion) {
        DepartmentWorkTask task = requireObservedVersion(taskId, expectedVersion);
        // Audit-only decisions must invalidate the task version observed by a Workboard client too.
        entityManager.lock(task, LockModeType.PESSIMISTIC_FORCE_INCREMENT);
        return snapshot(task);
    }

    private DepartmentWorkTask requireObservedVersion(UUID taskId, Long expectedVersion) {
        return requireObservedVersion(taskId, expectedVersion, () -> {});
    }

    private DepartmentWorkTask requireObservedVersion(UUID taskId, Long expectedVersion, Runnable revalidate) {
        DepartmentWorkTask task = tasks.findById(taskId).orElseThrow(() -> new BusinessException(
                "WORK_TASK_NOT_FOUND", "The work task was not found", HttpStatus.NOT_FOUND));
        // Refresh acquires the row lock and replaces any earlier ORM snapshot in one operation.
        try { entityManager.refresh(task, LockModeType.PESSIMISTIC_WRITE); }
        catch (jakarta.persistence.EntityNotFoundException ex) {
            throw new BusinessException("WORK_TASK_NOT_FOUND", "The work task was not found", HttpStatus.NOT_FOUND);
        }
        revalidate.run();
        if (expectedVersion != null && (expectedVersion < 0 || expectedVersion != task.getVersion())) {
            throw new BusinessException("WORK_TASK_VERSION_CONFLICT",
                    "This worksheet changed. Reload it before submitting your update", HttpStatus.CONFLICT);
        }
        return task;
    }

    private void requireCurrentAuthority(CurrentAccountAuthority.Authority before, CurrentAccountAuthority.Authority after) {
        if (!before.equals(after)) throw new BusinessException("WORK_TASK_PERMISSION_DENIED",
                "Your current account changed. Reload before submitting", HttpStatus.FORBIDDEN);
    }

    private void requireUnchangedScope(UUID actor, CurrentAccountAuthority.WorkScope before) {
        if (!before.equals(authority.requireWorkScope(actor))) throw new BusinessException("WORK_TASK_PERMISSION_DENIED",
                "Your current role, permissions or assignment changed. Reload before submitting", HttpStatus.FORBIDDEN);
    }

    private CurrentAccountAuthority.Authority requirePermission(UUID actor, String permission) {
        var current = authority.requireActive(actor);
        if (!current.permissions().contains(permission)) throw new BusinessException("WORK_TASK_PERMISSION_DENIED",
                "Your current permissions do not allow this action", HttpStatus.FORBIDDEN);
        return current;
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
    public void acceptHrDeliveryEvidence(UUID workTaskId) { require(workTaskId).acceptHrEvidence(); }

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
        return start(userId, employeeId, taskId, update, null);
    }

    @Transactional
    public DepartmentWorkTask start(UUID userId, UUID employeeId, UUID taskId, String update, Long expectedVersion) {
        var current = requirePermission(userId, "WORK_TASK_PROGRESS");
        var currentScope = authority.requireWorkScope(userId);
        requireCurrentAuthority(current, currentScope.authority());
        employeeId = current.employeeId();
        requireProgressScope(userId, employeeId, taskId); // Scope errors precede version errors for guessed foreign identifiers.
        requireObservedVersion(taskId, expectedVersion, () -> requireUnchangedScope(userId, currentScope));
        DepartmentWorkTask task = requireProgressScope(userId, employeeId, taskId);
        task.start(update);
        notifyProgress(userId, task, "started", update);
        audit(task, "IN_PROGRESS");
        tasks.flush();
        return task;
    }

    @Transactional
    public DepartmentWorkTask complete(UUID userId, UUID employeeId, UUID taskId, String update) {
        return complete(userId, employeeId, taskId, update, null);
    }

    @Transactional
    public DepartmentWorkTask complete(UUID userId, UUID employeeId, UUID taskId, String update, Long expectedVersion) {
        var current = requirePermission(userId, "WORK_TASK_PROGRESS");
        var currentScope = authority.requireWorkScope(userId);
        requireCurrentAuthority(current, currentScope.authority());
        employeeId = current.employeeId();
        requireProgressScope(userId, employeeId, taskId); // Scope errors precede version errors for guessed foreign identifiers.
        requireObservedVersion(taskId, expectedVersion, () -> requireUnchangedScope(userId, currentScope));
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
        tasks.flush();
        return task;
    }

    @Transactional
    public DepartmentWorkTask reviseEmployeeRework(UUID employeeUserId, UUID employeeId,
                                                   UUID taskId, String update) {
        return reviseEmployeeRework(employeeUserId, employeeId, taskId, update, null);
    }

    @Transactional
    public DepartmentWorkTask reviseEmployeeRework(UUID employeeUserId, UUID employeeId,
                                                   UUID taskId, String update, Long expectedVersion) {
        var current = requirePermission(employeeUserId, "WORK_TASK_PROGRESS");
        var currentScope = authority.requireWorkScope(employeeUserId);
        requireCurrentAuthority(current, currentScope.authority());
        employeeId = current.employeeId();
        if (!"ROLE_EMPLOYEE".equals(current.role())) throw new BusinessException("WORK_TASK_ROLE_REQUIRED", "Only the assigned Employee can perform this action", HttpStatus.FORBIDDEN);
        requireEmployeeScope(employeeId, taskId); // Scope errors precede version errors for guessed foreign identifiers.
        requireObservedVersion(taskId, expectedVersion, () -> requireUnchangedScope(employeeUserId, currentScope));
        DepartmentWorkTask task = requireEmployeeScope(employeeId, taskId);
        task.reviseEmployeeReworkSubmission(update);
        events.publishEvent(new WorkTaskEvents.DirectNotificationRequested(employeeUserId,
                task.getTeamLeadUserId(),
                "Employee updated and resubmitted rework for worksheet ‘" + task.getTitle()
                        + "’. Review the corrected delivery in Work Board."));
        audit(task, "REWORK_RESUBMITTED");
        tasks.flush();
        return task;
    }

    @Transactional
    public DepartmentWorkTask approve(UUID teamLeadUserId, UUID taskId, String review) {
        return approve(teamLeadUserId, taskId, review, null);
    }

    @Transactional
    public DepartmentWorkTask approve(UUID teamLeadUserId, UUID taskId, String review, Long expectedVersion) {
        var current = requirePermission(teamLeadUserId, "WORK_TASK_REVIEW");
        var currentScope = authority.requireWorkScope(teamLeadUserId);
        requireCurrentAuthority(current, currentScope.authority());
        if (!"ROLE_TEAM_LEAD".equals(current.role())) throw new BusinessException("WORK_TASK_ROLE_REQUIRED", "Only the assigned Team Lead can review delivery", HttpStatus.FORBIDDEN);
        requireTeamLeadReviewScope(teamLeadUserId, taskId); // Scope errors precede version errors for guessed foreign identifiers.
        requireObservedVersion(taskId, expectedVersion, () -> requireUnchangedScope(teamLeadUserId, currentScope));
        DepartmentWorkTask task = requireTeamLeadReviewScope(teamLeadUserId, taskId);
        task.approve(review);
        events.publishEvent(new WorkTaskEvents.DirectNotificationRequested(teamLeadUserId, employeeUserId(task),
                "Your completed task ‘" + task.getTitle()
                        + "’ was approved by your Team Lead. Open Work Board to acknowledge the decision."));
        publishHrNotification(teamLeadUserId, task,
                "Team Lead reviewed and approved ‘" + task.getTitle() + "’ in department branch "
                        + task.getDepartmentBranch() + ". HR audit is required.");
        audit(task, "APPROVED");
        tasks.flush();
        return task;
    }

    @Transactional
    public DepartmentWorkTask requestChanges(UUID teamLeadUserId, UUID taskId, String review) {
        return requestChanges(teamLeadUserId, taskId, review, null);
    }

    @Transactional
    public DepartmentWorkTask requestChanges(UUID teamLeadUserId, UUID taskId, String review, Long expectedVersion) {
        var current = requirePermission(teamLeadUserId, "WORK_TASK_REVIEW");
        var currentScope = authority.requireWorkScope(teamLeadUserId);
        requireCurrentAuthority(current, currentScope.authority());
        if (!"ROLE_TEAM_LEAD".equals(current.role())) throw new BusinessException("WORK_TASK_ROLE_REQUIRED", "Only the assigned Team Lead can review delivery", HttpStatus.FORBIDDEN);
        requireTeamLeadReviewScope(teamLeadUserId, taskId); // Scope errors precede version errors for guessed foreign identifiers.
        requireObservedVersion(taskId, expectedVersion, () -> requireUnchangedScope(teamLeadUserId, currentScope));
        DepartmentWorkTask task = requireTeamLeadReviewScope(teamLeadUserId, taskId);
        task.requestChanges(review);
        events.publishEvent(new WorkTaskEvents.DirectNotificationRequested(teamLeadUserId, employeeUserId(task),
                "Changes were requested for ‘" + task.getTitle() + "’: " + review.trim()));
        audit(task, "CHANGES_REQUESTED");
        tasks.flush();
        return task;
    }

    @Transactional
    public DepartmentWorkTask acknowledge(UUID employeeUserId, UUID employeeId, UUID taskId) {
        return acknowledge(employeeUserId, employeeId, taskId, null);
    }

    @Transactional
    public DepartmentWorkTask acknowledge(UUID employeeUserId, UUID employeeId, UUID taskId, Long expectedVersion) {
        var current = requirePermission(employeeUserId, "WORK_TASK_PROGRESS");
        var currentScope = authority.requireWorkScope(employeeUserId);
        requireCurrentAuthority(current, currentScope.authority());
        employeeId = current.employeeId();
        if (!"ROLE_EMPLOYEE".equals(current.role())) throw new BusinessException("WORK_TASK_ROLE_REQUIRED", "Only the assigned Employee can perform this action", HttpStatus.FORBIDDEN);
        requireEmployeeScope(employeeId, taskId); // Scope errors precede version errors for guessed foreign identifiers.
        requireObservedVersion(taskId, expectedVersion, () -> requireUnchangedScope(employeeUserId, currentScope));
        DepartmentWorkTask task = requireEmployeeScope(employeeId, taskId);
        task.acknowledge();
        events.publishEvent(new WorkTaskEvents.DirectNotificationRequested(employeeUserId,
                task.getTeamLeadUserId(),
                "Employee acknowledged Team Lead approval for ‘" + task.getTitle() + "’."));
        publishHrNotification(employeeUserId, task,
                "Employee acknowledged the approved worksheet ‘" + task.getTitle()
                        + "’. It is ready for HR audit.");
        audit(task, "ACKNOWLEDGED");
        tasks.flush();
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
        if (!task.requiresTeamLeadReview()
                || task.getEmployeeId().equals(authority.requireActive(teamLeadUserId).employeeId())) {
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
        if (employeeId == null || !EMPLOYEE_ASSIGNEE.equals(task.getAssigneeRole()) || !task.getEmployeeId().equals(employeeId)) {
            throw new BusinessException("WORK_TASK_EMPLOYEE_SCOPE_DENIED",
                    "This task is assigned to another employee", HttpStatus.FORBIDDEN);
        }
        employees.requireActiveEmployee(employeeId);
        if (!task.getDepartmentId().equals(employees.departmentIdForEmployee(employeeId))) {
            throw new BusinessException("WORK_TASK_EMPLOYEE_SCOPE_DENIED", "This task is outside your current department", HttpStatus.FORBIDDEN);
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
