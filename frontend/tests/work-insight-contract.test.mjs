import { readFrontendSource, readApiSource } from "./frontend-source.mjs";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";
import { sourceIncludes } from "./source-contract-utils.mjs";

const read = (path) =>
    readFileSync(new URL(`../${path}`, import.meta.url), "utf8");

const service = read(
    "../backend/src/main/java/com/brainserve/appointment/workinsight/application/WorkInsightService.java",
);
const controller = read(
    "../backend/src/main/java/com/brainserve/appointment/workinsight/api/WorkInsightController.java",
);
const domain = read(
    "../backend/src/main/java/com/brainserve/appointment/workinsight/domain/WorkTaskAuditRecord.java",
);
const repository = read(
    "../backend/src/main/java/com/brainserve/appointment/workinsight/infrastructure/WorkTaskAuditRecordRepository.java",
);
const migration = read(
    "../backend/src/main/resources/db/migration/V21__weekly_work_insights.sql",
);
const reworkMigration = read(
    "../backend/src/main/resources/db/migration/V22__work_insight_rework_cycle.sql",
);
const governanceMigration = read(
    "../backend/src/main/resources/db/migration/V47__work_task_manager_governance.sql",
);
const closureMigration = read(
    "../backend/src/main/resources/db/migration/V48__close_ceo_approved_team_lead_work.sql",
);
const listener = read(
    "../backend/src/main/java/com/brainserve/appointment/notification/application/WorkInsightNotificationListener.java",
);
const notificationGateway = read(
    "../backend/src/main/java/com/brainserve/appointment/notification/api/InternalNotificationGateway.java",
);
const notificationService = read(
    "../backend/src/main/java/com/brainserve/appointment/notification/application/InternalCallNotificationService.java",
);
const workTaskDirectory = read(
    "../backend/src/main/java/com/brainserve/appointment/worktask/api/WorkTaskDirectory.java",
);
const workTaskService = read(
    "../backend/src/main/java/com/brainserve/appointment/worktask/application/DepartmentWorkTaskService.java",
);
const teamLead = read(
    "../backend/src/main/java/com/brainserve/appointment/teamlead/application/TeamLeadAssignmentService.java",
);
const teamLeadController = read(
    "../backend/src/main/java/com/brainserve/appointment/teamlead/api/TeamLeadController.java",
);
const api = readApiSource();
const app = readFrontendSource();

test("Team Lead workspace fetches assignment, department and roster atomically", () => {
  assert.ok(
      sourceIncludes(
          teamLead,
          "public Workspace workspace(UUID teamLeadUserId, Pageable pageable)",
      ),
  );
  assert.ok(
      sourceIncludes(
          teamLead,
          "employees.departmentMembers(assignment.departmentId(), pageable)",
      ),
  );
  assert.ok(
      teamLeadController.includes("requireBoundedPage(pageable)"),
  );
  assert.ok(
      teamLeadController.includes('@GetMapping("/me/workspace")'),
  );
  assert.ok(api.includes("myTeamLeadWorkspace"));
  assert.ok(
      app.includes(
          "const workspace = await brainServeApi.myTeamLeadWorkspace()",
      ),
  );
});

test("weekly work insights retain complete audit snapshots", () => {
  for (const field of [
    "work_task_id",
    "week_start",
    "department_name",
    "employee_number",
    "employee_name",
    "team_lead_name",
    "task_title",
    "task_status",
    "audit_status",
    "hr_audited_at",
    "ceo_decided_at",
  ]) {
    assert.ok(
        migration.includes(field),
        `missing retained field ${field}`,
    );
  }

  assert.ok(domain.includes("PENDING_CEO_APPROVAL"));
  assert.ok(domain.includes("PENDING_MANAGER_APPROVAL"));
  assert.ok(domain.includes("CEO_APPROVED"));
  assert.ok(domain.includes("CEO_REWORK_REQUESTED"));

  for (const field of [
    "assigned_by_role",
    "assignee_role",
    "manager_decided_by_user_id",
    "manager_decided_at",
    "manager_remarks",
  ]) {
    assert.ok(
        governanceMigration.includes(field),
        `missing Manager governance field ${field}`,
    );
  }
});

test("HR, Manager or CEO rejection creates an actionable Team Lead rework cycle", () => {
  for (const state of [
    "HR_REWORK_REQUESTED",
    "CEO_REWORK_REQUESTED",
    "REWORK_ASSIGNED",
  ]) {
    assert.ok(reworkMigration.includes(state));
  }

  assert.ok(
      governanceMigration.includes("MANAGER_REWORK_REQUESTED"),
  );
  assert.ok(
      controller.includes("/tasks/{taskId}/request-rework"),
  );
  assert.ok(
      controller.includes("/tasks/{taskId}/assign-rework"),
  );
  assert.ok(
      controller.includes("/tasks/{taskId}/revise-rework"),
  );
  assert.ok(controller.includes("/task-workflow-states"));
  assert.ok(
      controller.includes(
          "hasAnyRole('EMPLOYEE','TEAM_LEAD') and hasAuthority('WORK_TASK_READ')",
      ),
  );
  assert.ok(service.includes("requestInsightRework"));
  assert.ok(service.includes("assignInsightRework"));
  assert.ok(service.includes("reviseReworkSubmission"));
  assert.ok(app.includes("Create rework plan"));
  assert.ok(app.includes("Update & resubmit"));
  assert.ok(app.includes("Awaiting HR re-audit"));
  assert.ok(app.includes("Reject & rework"));
  assert.ok(api.includes("requestWorkInsightRework"));
  assert.ok(api.includes("assignWorkInsightRework"));
  assert.ok(api.includes("reviseWorkInsightRework"));
  assert.ok(api.includes("reviseEmployeeWorkTaskRework"));
  assert.ok(api.includes("workTaskWorkflowStates"));
  assert.ok(
      repository.includes(
          "findTop500ByTeamLeadUserIdOrderByHrAuditedAtDesc",
      ),
  );
  assert.ok(
      repository.includes(
          "findTop500ByEmployeeIdOrderByHrAuditedAtDesc",
      ),
  );
  assert.ok(
      app.includes(
          'workflowStateByTaskId.get(task.id) === "REWORK_ASSIGNED"',
      ),
  );
  assert.ok(app.includes('"Awaiting Manager review"'));
  assert.ok(app.includes('"Awaiting CEO approval"'));
  assert.ok(app.includes('task.assigneeRole === "EMPLOYEE"'));
  assert.ok(
      app.includes(
          "Your Team Lead can now review the corrected worksheet",
      ),
  );
});

test("CEO approval closes Team Lead carry-forward work and repairs existing records", () => {
  assert.ok(workTaskDirectory.includes("finalizeInsightApproval"));
  assert.ok(
      workTaskService.includes('audit(task, "GOVERNANCE_APPROVED")'),
  );
  assert.ok(
      service.includes(
          "tasks.finalizeInsightApproval(record.getWorkTaskId())",
      ),
  );
  assert.ok(
      service.includes("WorkInsightEvents.FinalApprovalRecipient"),
  );
  assert.equal(
      service.includes("WorkTaskEvents.DirectNotificationRequested"),
      false,
  );
  assert.ok(
      listener.includes("notifyWorkerOfCeoWorkInsightApproval"),
  );
  assert.ok(
      notificationGateway.includes(
          "notifyWorkerOfCeoWorkInsightApproval",
      ),
  );
  assert.ok(
      notificationService.includes(
          "WORK_INSIGHT_FINAL_APPROVAL_ROUTE_DENIED",
      ),
  );
  assert.ok(app.includes('workflowState === "CEO_APPROVED"'));
  assert.ok(app.includes('return "Completed"'));
  assert.ok(
      closureMigration.includes("task.assignee_role = 'TEAM_LEAD'"),
  );
  assert.ok(
      closureMigration.includes(
          "audit.audit_status = 'CEO_APPROVED'",
      ),
  );
  assert.ok(
      closureMigration.includes("SET task_status = 'APPROVED'"),
  );
});

test("HR audit, Manager verification and CEO decision endpoints are independently role locked", () => {
  assert.ok(controller.includes("WORK_INSIGHT_READ"));
  assert.ok(
      controller.includes(
          "hasRole('HR_ADMIN') and hasAuthority('WORK_INSIGHT_AUDIT')",
      ),
  );
  assert.ok(controller.includes("/pending-hr-audit"));
  assert.ok(
      controller.includes(
          "hasRole('CEO') and hasAuthority('WORK_INSIGHT_CEO_APPROVE')",
      ),
  );
  assert.ok(
      controller.includes(
          "hasRole('MANAGER') and hasAuthority('WORK_INSIGHT_MANAGER_APPROVE')",
      ),
  );
  assert.ok(service.includes("WORK_INSIGHT_TASK_NOT_FINAL"));
  assert.ok(service.includes("roles.contains(SYSTEM_ADMIN)"));
});

test("work insight notifications are emitted only after the database commit", () => {
  assert.ok(listener.includes("TransactionPhase.AFTER_COMMIT"));
  assert.ok(listener.includes('@Async("notificationExecutor")'));
  assert.ok(
      listener.includes("notifyManagerOfWorkInsightAudit"),
  );
  assert.ok(
      listener.includes("notifyCeoOfManagerWorkInsightApproval"),
  );
  assert.ok(
      listener.includes("notifyHrOfManagerWorkInsightDecision"),
  );
  assert.ok(
      listener.includes("notifyHrOfWorkInsightDecision"),
  );
  assert.ok(
      listener.includes("notifyWorkerOfCeoWorkInsightApproval"),
  );
  assert.ok(
      listener.includes("notifyTeamLeadOfWorkInsightRework"),
  );
  assert.ok(listener.includes("sendWorkTaskUpdate"));
});

test("HR, Manager, CEO and System Admin receive role-specific Insights tables", () => {
  assert.ok(app.includes('{ id: "insights", label: "Insights"'));
  assert.ok(
      app.includes(
          '"HR Admin": ["overview", "appointments", "work", "performance", "insights"',
      ),
  );
  assert.ok(
      app.includes(
          'Manager: ["overview", "appointments", "work", "insights"',
      ),
  );
  assert.ok(
      app.includes('CEO: ["overview", "appointments", "insights"'),
  );
  assert.ok(
      app.includes('"System Admin": ["overview", "insights"'),
  );
  assert.ok(app.includes("Mark audited"));
  assert.ok(app.includes("Work audit approvals"));
  assert.ok(app.includes("Retained work insight register"));
  assert.ok(api.includes("auditWorkInsight"));
  assert.ok(api.includes("pendingHrWorkInsights"));
  assert.ok(api.includes("decideWorkInsight"));
  assert.ok(api.includes("decideManagerWorkInsight"));
});

test("Insights and Work Board use role-owned forms and expandable audit evidence", () => {
  for (const marker of [
    "insight-decision-modal",
    "work-action-modal",
    "insight-cycle-steps",
    "insight-evidence-grid",
    "insight-toolbar",
    "work-rework-alert",
    "task-flow",
  ]) {
    assert.ok(
        app.includes(marker),
        `missing enhanced UI ${marker}`,
    );
  }

  assert.ok(
      app.includes(
          'role === "Employee" && task.assigneeRole === "EMPLOYEE"',
      ),
  );
  assert.ok(
      app.includes(
          'role === "Team Lead" && task.assigneeRole === "EMPLOYEE" && task.status === "COMPLETED"',
      ),
  );
  assert.ok(
      app.includes(
          "Open any row to review the complete approval and rework cycle",
      ),
  );
});
