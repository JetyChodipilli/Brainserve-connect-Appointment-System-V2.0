import { readFrontendSource, readApiSource } from "./frontend-source.mjs";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";
import { sourceIncludes } from "./source-contract-utils.mjs";

const read = (path) =>
    readFileSync(new URL(`../${path}`, import.meta.url), "utf8");

const domain = read(
    "../backend/src/main/java/com/brainserve/appointment/worktask/domain/DepartmentWorkTask.java",
);
const service = read(
    "../backend/src/main/java/com/brainserve/appointment/worktask/application/DepartmentWorkTaskService.java",
);
const controller = read(
    "../backend/src/main/java/com/brainserve/appointment/worktask/api/DepartmentWorkTaskController.java",
);
const listener = read(
    "../backend/src/main/java/com/brainserve/appointment/notification/application/WorkTaskNotificationListener.java",
);
const notificationService = read(
    "../backend/src/main/java/com/brainserve/appointment/notification/application/InternalCallNotificationService.java",
);
const migration = read(
    "../backend/src/main/resources/db/migration/V19__department_work_tasks.sql",
);
const branchMigration = read(
    "../backend/src/main/resources/db/migration/V20__work_task_department_branch.sql",
);
const governanceMigration = read(
    "../backend/src/main/resources/db/migration/V47__work_task_manager_governance.sql",
);
const repository = read(
    "../backend/src/main/java/com/brainserve/appointment/worktask/infrastructure/DepartmentWorkTaskRepository.java",
);
const roles = read(
    "../backend/src/main/java/com/brainserve/appointment/iam/domain/SystemRole.java",
);
const api = readApiSource();
const app = readFrontendSource();
const styles = read("app/globals.css");

test("Employee, Team Lead, HR and Manager use the governed work board", () => {
  assert.ok(
      app.includes('"Team Lead": ["overview", "work"'),
  );
  assert.ok(
      app.includes('Employee: ["overview", "work"'),
  );
  assert.ok(
      app.includes('"HR Admin": ["overview", "appointments", "work"'),
  );
  assert.ok(
      app.includes('Manager: ["overview", "appointments", "work", "insights"'),
  );
  assert.ok(
      app.includes('{ id: "work", label: "Work board"'),
  );
  assert.ok(
      app.includes(
          "Visitor updates stay in Notifications",
      ),
  );
  assert.ok(
      app.includes("Department visitor approvals"),
  );
});

test("task sheets never fall back to another employee's assignments", () => {
  assert.ok(
      app.includes(
          'if (role === "Employee" && !currentEmployeeId)',
      ),
  );
  assert.ok(
      app.includes(
          "values.filter((item) => item.employeeId === currentEmployeeId)",
      ),
  );
  assert.ok(app.includes("setTasks([])"));
  assert.ok(app.includes("Create task sheet"));
  assert.ok(app.includes("NEW TASK SHEET"));
  assert.ok(app.includes("Worksheet instructions"));
  assert.ok(
      app.includes('className="task-sheet-grid"'),
  );
});

test("task sheets use a compact readable summary with progressive disclosure", () => {
  assert.ok(app.includes('className="task-sheet-summary"'));
  assert.ok(app.includes('className="task-sheet-brief"'));
  assert.ok(app.includes("WORK TO COMPLETE"));
  assert.ok(app.includes('aria-labelledby={`work-task-${task.id}-title`}'));
  assert.ok(app.includes("expandedTaskId"));
  assert.ok(app.includes("aria-expanded={expanded}"));
  assert.ok(app.includes("View details"));
  assert.ok(styles.includes(".task-sheet-brief p"));
  assert.ok(styles.includes("-webkit-line-clamp: 2"));
  assert.ok(styles.includes(".task-sheet-summary h2 { min-height: 0"));
  assert.ok(styles.includes("repeat(auto-fit, minmax(min(100%, 390px), 1fr))"));
  assert.ok(styles.includes("white-space: pre-wrap"));
  assert.ok(styles.includes("align-items: stretch"));
  assert.ok(styles.includes(".task-sheet-card.is-expanded { grid-column: 1 / -1"));
  assert.ok(app.includes('className="task-sheet-summary-alert-slot"'));
  assert.ok(styles.includes(".task-sheet-summary-alert-slot { min-height: 27px"));
  assert.ok(styles.includes(".task-sheet-card.is-expanded .task-sheet-summary-meta"));
  assert.ok(styles.includes(".task-sheet-card { display: flex; flex-direction: column; min-width: 0; height: 100%; overflow: hidden; padding: 12px"));
  assert.ok(styles.includes(".task-flow { display: flex; align-items: center; min-width: 0; overflow: hidden"));
  assert.ok(styles.includes(".task-sheet-card > header > div { min-width: 0; overflow: hidden"));
  assert.ok(styles.includes(".task-sheet-card > footer .button { max-width: 100%"));
  assert.ok(app.includes("{expanded && <>"));
});

test("work board refreshes are isolated, non-overlapping and preserve the last good data", () => {
  assert.ok(app.includes("loadInFlightRef.current"));
  assert.ok(app.includes("hasLoadedTasksRef.current"));
  assert.ok(app.includes("await Promise.allSettled(["));
  assert.ok(app.includes("The last loaded worksheets remain visible"));
  assert.ok(app.includes('document.visibilityState === "visible"'));
  assert.ok(app.includes('void load("background")'));
  assert.equal(app.includes('void load("manual")'), false);
  assert.equal(app.includes("key={`work:${workspaceRevision}`"), false);
});

test("HR and Manager retain a visible department scope even on an empty daily board", () => {
  assert.ok(app.includes("workBoardDepartments.length === 1 ? workBoardDepartments[0].id"));
  assert.ok(app.includes("brainServeApi.visibleDepartments()"));
  assert.ok(app.includes("setScopeDepartments(scopeResult.value)"));
  assert.ok(app.includes('className="work-scope-summary"'));
  assert.ok(app.includes("DEPARTMENT SCOPE"));
  assert.ok(app.includes("assignedDepartment?.name, ...tasks.map"));
  assert.ok(app.includes('aria-label="Department scope"'));
});

test("the work board defaults to today while preserving open carry-forward work", () => {
  assert.ok(app.includes('useState<"TODAY" | "CARRY_FORWARD">("TODAY")'));
  assert.ok(app.includes("officeDateFromInstant(task.createdAt) === today"));
  assert.ok(app.includes("isOpenCarryForwardTask"));
  assert.ok(app.includes("Open carry-forward"));
  assert.ok(app.includes("Closed older worksheets stay stored for governance and audit"));
  assert.ok(app.includes('queueScope === "TODAY" ? todayTasks : carryForwardTasks'));
});

test("older worksheets remain persisted instead of being deleted from history", () => {
  assert.ok(domain.includes("@Entity"));
  assert.ok(migration.includes("CREATE TABLE department_work_task"));
  assert.ok(migration.includes("created_at"));
  assert.ok(repository.includes("findTop200ByEmployeeIdOrderByCreatedAtDesc"));
  assert.ok(repository.includes("findTop500ByDepartmentIdOrderByCreatedAtDesc"));
  assert.ok(governanceMigration.includes("work_task_audit_record"));
});

test("demo organization and Team Lead scope persist across login sessions", () => {
  assert.ok(app.includes("DEMO_EMPLOYEES_KEY"));
  assert.ok(app.includes("DEMO_DEPARTMENTS_KEY"));
  assert.ok(
      app.includes("DEMO_TEAM_LEAD_ASSIGNMENTS_KEY"),
  );
  assert.ok(
      app.includes("writeDemoEmployees(updated)"),
  );
  assert.ok(
      app.includes("writeDemoDepartments(updated)"),
  );
  assert.ok(
      app.includes(
          "writeDemoTeamLeadAssignments(updated)",
      ),
  );
  assert.ok(
      app.includes(
          'assignedByUserId: "demo-legacy-migration"',
      ),
  );
  assert.ok(
      app.includes(
          "if (migrated) writeDemoTeamLeadAssignments(assignments)",
      ),
  );
  assert.ok(
      sourceIncludes(
          app,
          "isBackendConfigured ? [] : readDemoEmployees()",
      ),
  );
  assert.ok(
      sourceIncludes(
          app,
          "isBackendConfigured ? [] : readDemoDepartments()",
      ),
  );
  assert.ok(
      sourceIncludes(
          app,
          "isBackendConfigured ? [] : readDemoTeamLeadAssignments()",
      ),
  );
});

test("work tasks follow the employee and Team Lead verification state machine", () => {
  for (const transition of [
    "start",
    "complete",
    "requestChanges",
    "approve",
    "acknowledge",
  ]) {
    assert.ok(
        domain.includes(`void ${transition}`),
        `missing ${transition}`,
    );
  }

  for (const status of [
    "ASSIGNED",
    "IN_PROGRESS",
    "COMPLETED",
    "CHANGES_REQUESTED",
    "APPROVED",
    "ACKNOWLEDGED",
  ]) {
    assert.ok(
        migration.includes(`'${status}'`),
        `missing ${status}`,
    );
  }
});

test("backend scopes tasks and assignees to the actor's department", () => {
  assert.ok(
      service.includes(
          "WORK_TASK_DEPARTMENT_MISMATCH",
      ),
  );
  assert.ok(
      service.includes(
          "WORK_TASK_TEAM_LEAD_SCOPE_DENIED",
      ),
  );
  assert.ok(
      service.includes(
          "WORK_TASK_EMPLOYEE_SCOPE_DENIED",
      ),
  );
  assert.ok(
      service.includes("teamLeads.requireForUser"),
  );
  assert.ok(service.includes("activeByEmployeeId"));
  assert.ok(service.includes("managers.requireForUser(userId).departmentId()"));
  assert.ok(service.includes("staff.activeWithAnyRoleInDepartment(Set.of(EMPLOYEE, TEAM_LEAD), departmentId, 200)"));
  assert.ok(controller.includes('@GetMapping("/workspace")'));
  assert.ok(
      service.includes(
          "organization.requireActiveDepartment(lead.departmentId())",
      ),
  );
  assert.ok(service.includes("department.name()"));
  assert.ok(
      app.includes(
          "item.departmentId === scopeDepartmentIdRef.current",
      ),
  );
  assert.ok(
      app.includes(
          "item.employeeId === currentEmployeeId",
      ),
  );
});

test("task endpoints use explicit role permissions", () => {
  for (const permission of [
    "WORK_TASK_READ",
    "WORK_TASK_CREATE",
    "WORK_TASK_PROGRESS",
    "WORK_TASK_REVIEW",
    "WORK_TASK_PERFORMANCE_READ",
  ]) {
    assert.ok(
        controller.includes(permission),
        `controller missing ${permission}`,
    );
    assert.ok(
        roles.includes(permission),
        `role catalog missing ${permission}`,
    );
  }

  assert.ok(controller.includes("hasAnyRole('HR_ADMIN','TEAM_LEAD')"));
  assert.ok(
      controller.includes("hasRole('EMPLOYEE')"),
  );
  assert.ok(
      controller.includes("hasRole('HR_ADMIN')"),
  );
});

test("Kafka task and HR performance messages publish only after commit", () => {
  assert.ok(
      listener.includes(
          "TransactionPhase.AFTER_COMMIT",
      ),
  );
  assert.ok(
      listener.includes('@Async("notificationExecutor")'),
  );
  assert.ok(listener.includes("sendWorkTaskUpdate"));
  assert.ok(
      listener.includes("notifyHrOfWorkTaskUpdate"),
  );
  assert.ok(
      notificationService.includes(
          "WORK_TASK_NOTIFICATION_ROUTE_DENIED",
      ),
  );
});

test("PostgreSQL and frontend use department branches, performance and all workflow actions", () => {
  for (const field of [
    "employee_id",
    "team_lead_user_id",
    "due_date",
    "employee_update",
    "team_lead_review",
    "approved_at",
    "acknowledged_at",
  ]) {
    assert.ok(
        migration.includes(field),
        `migration missing ${field}`,
    );
  }

  assert.ok(migration.includes("ix_work_task_department"));
  for (const field of ["assigned_by_user_id", "assigned_by_role", "assignee_role"]) {
    assert.ok(governanceMigration.includes(field), `governance migration missing ${field}`);
  }
  assert.ok(governanceMigration.includes("PENDING_MANAGER_APPROVAL"));
  assert.ok(
      branchMigration.includes("department_branch"),
  );
  assert.ok(
      branchMigration.includes(
          "FROM org_department department",
      ),
  );
  assert.ok(
      branchMigration.includes("DROP COLUMN category"),
  );

  for (const call of [
    "workTasks",
    "workTaskWorkspace",
    "createWorkTask",
    "updateWorkTask",
    "acknowledgeWorkTask",
    "teamLeadPerformance",
  ]) {
    assert.ok(
        api.includes(call),
        `frontend API missing ${call}`,
    );
  }

  assert.ok(app.includes("Team Lead performance"));
  assert.ok(app.includes("Department / branch"));
  assert.ok(app.includes("No department assigned"));
  assert.ok(!app.includes("Category / stream"));
  assert.ok(app.includes("Employee work update"));
  assert.ok(app.includes("Team Lead decision"));
  assert.ok(app.includes("Audit & send to Manager"));
  assert.ok(app.includes("Open work oversight"));
});
