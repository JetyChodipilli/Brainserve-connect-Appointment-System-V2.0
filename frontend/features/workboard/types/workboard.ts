export type WorkTask = { id: string; departmentId: string; employeeId: string; teamLeadUserId: string;
  assignedByUserId: string; assignedByRole: "HR_ADMIN" | "TEAM_LEAD";
  assigneeRole: "EMPLOYEE" | "TEAM_LEAD";
  title: string; description: string; departmentBranch: string; dueDate: string;
  status: "ASSIGNED" | "IN_PROGRESS" | "COMPLETED" | "CHANGES_REQUESTED" | "INSIGHT_REWORK_REQUESTED" | "APPROVED" | "ACKNOWLEDGED";
  employeeUpdate: string | null; teamLeadReview: string | null; startedAt: string | null;
  insightReviewSource?: string | null; insightReviewReason?: string | null;
  insightReviewRequestedAt?: string | null; reworkCycle?: number;
  completedAt: string | null; approvedAt: string | null; acknowledgedAt: string | null;
  createdAt: string; version: number };

export type WorkTaskAssignee = { employeeId: string; displayName: string;
  designation: string; role: "EMPLOYEE" | "TEAM_LEAD" };

export type WorkTaskWorkspace = { departmentId: string; departmentCode: string; departmentName: string;
  eligibleAssignees: WorkTaskAssignee[] };

export type TeamLeadPerformance = { teamLeadUserId: string; departmentId: string; totalTasks: number;
  completedTasks: number; approvedTasks: number; inProgressTasks: number; pendingReviewTasks: number;
  overdueTasks: number; completionRate: number; lastApprovedAt: string | null };

export type WorkInsight = { auditRecordId: string | null; workTaskId: string; weekStart: string;
  departmentId: string; departmentName: string; employeeId: string; employeeNumber: string;
  employeeName: string; teamLeadUserId: string; teamLeadName: string;
  assignedByRole: WorkTask["assignedByRole"]; assigneeRole: WorkTask["assigneeRole"]; taskTitle: string;
  taskStatus: WorkTask["status"]; auditStatus: "NOT_AUDITED" | "HR_REWORK_REQUESTED" | "PENDING_MANAGER_APPROVAL" | "MANAGER_REWORK_REQUESTED" | "PENDING_CEO_APPROVAL" | "CEO_APPROVED" | "CEO_REWORK_REQUESTED" | "REWORK_ASSIGNED";
  hrAuditedAt: string | null; managerDecidedAt: string | null; managerRemarks: string | null;
  ceoDecidedAt: string | null; ceoRemarks: string | null;
  reworkRequestedByRole: string | null; reworkReason: string | null; reworkRequestedAt: string | null;
  teamLeadReworkGuidance: string | null; teamLeadRespondedAt: string | null; reworkCycle: number };

export type WorkTaskWorkflowState = { workTaskId: string; auditStatus: WorkInsight["auditStatus"] };
