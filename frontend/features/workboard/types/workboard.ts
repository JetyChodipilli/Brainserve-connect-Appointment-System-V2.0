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

export type WorkboardScope = "TODAY" | "CARRY_FORWARD" | "HISTORY" | "ALL";
export type WorkboardQuickFilter = "ALL" | "MY_ACTIONS" | "DUE_TODAY" | "OVERDUE_DELIVERY" | "AWAITING_MY_REVIEW" | "RETURNED_FOR_REWORK" | "BLOCKED";
export type WorkboardSort = "DUE_DATE" | "UPDATED_AT" | "TITLE" | "PRIORITY";
export type WorkboardCriteria = { scope: WorkboardScope; quickFilter: WorkboardQuickFilter; query: string;
  status: "ALL" | WorkTask["status"]; branch: string; sort: WorkboardSort };
export type WorkboardAction = "start" | "complete" | "approve" | "request-changes" | "acknowledge" |
  "insight-rework" | "revise-rework" | "hr-rework" | "hr-audit" | "open-oversight";
export type WorkboardLane = "DELIVERY" | "REVIEW" | "REWORK" | "CLOSED";
export type WorkboardItem = WorkTask & { assigneeName: string; auditStatus: WorkInsight["auditStatus"];
  auditRecordId: string | null; auditVersion: number | null; updatedAt: string; submissionVersion: number | null;
  priority: WorkPriority | null; blocked: boolean | null; allowedActions: WorkboardAction[]; nextActor: string | null; lane: WorkboardLane };
export type WorkboardPage = { policyVersion: "workboard.v1"; generatedAt: string; officeZone: string; officeDate: string;
  scope: "OWN" | "DEPARTMENT"; departmentId: string | null; number: number; size: number; totalElements: number;
  totalPages: number; counts: { scopes: Record<WorkboardScope, number>; quickFilters: Record<WorkboardQuickFilter, number> };
  laneCounts: Record<WorkboardLane, number>; items: WorkboardItem[] };
export type WorkboardDetail = { item: WorkboardItem; history: { id: string; title: string; occurredAt: string;
  actorRole: string | null; note: string | null }[]; historyTruncated: boolean };
export type SavedWorkboardFilter = WorkboardCriteria & { id: string; name: string };
export type WorkboardPreferences = { revision: number; layout: "LIST" | "BOARD"; density: "COMPACT" | "COMFORTABLE";
  savedFilters: SavedWorkboardFilter[] };

export type WorkPriority = 'LOW' | 'NORMAL' | 'HIGH' | 'URGENT';
export type WorkChecklistItem = { id: string; title: string; position: number; required: boolean; completed: boolean };
export type WorkEvidence = { id: string; documentId: string; filename: string; contentType: string; sizeBytes: number; sha256: string; createdAt: string };
export type WorkBlocker = { id: string; reason: string; contactUserId: string | null; raisedAt: string; resolvedAt: string | null; raisedBy: string; resolvedBy: string | null; resolutionReason?: string | null };
export type WorkSubmission = { version: number; submittedAt: string; acceptedAt: string | null; acceptedByRole: string | null; checklist: WorkChecklistItem[]; evidence: WorkEvidence[] };
export type WorkPlanning = { taskId: string; taskVersion: number; priority: WorkPriority; originalDueDate: string | null; originalDueDateKnown?: boolean; dueDate: string; estimateMinutes: number | null; evidenceRequired: boolean; checklist: WorkChecklistItem[]; blockers: WorkBlocker[]; evidence: WorkEvidence[]; submissions: WorkSubmission[]; submissionsTruncated?: boolean; blockersTruncated?: boolean; contactOptions: { id: string; name: string }[]; permissions: { manage: boolean; progress: boolean; blocker: boolean; resolveBlocker: boolean; upload: boolean } };
export type WorkPlanningUpdate = { expectedVersion: number; priority: WorkPriority; estimateMinutes: number | null; evidenceRequired: boolean; dueDate: string; reason: string; checklist: { id: string; title: string; required: boolean }[] };
