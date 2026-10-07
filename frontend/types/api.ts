export type { WorkTask, WorkTaskAssignee, WorkTaskWorkspace, TeamLeadPerformance, WorkInsight, WorkTaskWorkflowState } from "../features/workboard/types/workboard";

export type ProblemResponse = {
  title?: string;
  detail?: string;
  errorCode?: string;
  fieldErrors?: Array<{ field: string; message: string }>;
};

export type ProvisioningAccount = {
  id: string;
  fullName: string;
  email: string;
  role: string;
  status: string;
  employeeId?: string | null;
  createdByUserId: string | null;
  approvedByUserId: string | null;
  createdAt: string;
  approvedAt: string | null;
  rejectedByUserId?: string | null;
  rejectedAt?: string | null;
};

export type CeoSlot = {
  available: boolean;
  userId: string | null;
  fullName: string | null;
  email: string | null;
  status: string | null;
};

export type HrAccountApprovalInput = {
  departmentId: string;
  phoneNumber: string | null;
  designation: string;
  joiningDate: string;
};

export type VisitorPass = {
  referenceNumber: string;
  visitorDisplayName: string;
  status: string;
  validFrom: string;
  expiresAt: string;
  token: string;
  qrCodeDataUrl: string;
};

export type WorkspaceSetting = {
  key: string;
  value: string;
  type: "STRING" | "INTEGER" | "BOOLEAN";
  description: string;
  version: number;
};

export type RoleDefinition = { role: string; defaultPermissions: string[] };

export type CompanyProfile = { name: string; emailDomain: string; hqAddress: string; supportEmail: string;
  consentVersion: string };

export type MyProfile = { userId: string; employeeId: string | null; fullName: string; email: string; roles: string[];
  employeeNumber: string | null; designation: string | null; employeeStatus: string | null;
  departmentId: string | null; departmentCode: string | null; departmentName: string | null;
  departmentActive: boolean | null; photoDocumentId: string | null; photoUrl: string | null;
  photoUrlExpiresAt: string | null };

export type EmployeeTerminationRequest = { id: string; employeeId: string; employeeNumber: string;
  employeeName: string; employeeEmail: string; departmentId: string; requestedByHrUserId: string;
  requestedByHrName: string; reason: string; effectiveDate: string;
  status: "PENDING_CEO_APPROVAL" | "APPROVED" | "REJECTED"; requestedAt: string;
  decidedByCeoUserId: string | null; decidedByCeoName: string | null; decidedAt: string | null;
  decisionNote: string | null };

export type EssentialLogRecord = { id: string; category: string; eventType: string; subjectType: string;
  subjectId: string; referenceId: string | null; actorUserId: string | null; approverUserId: string | null;
  status: string; title: string; detail: string; occurredAt: string };

export type AccountClosureStatus = "REQUESTED" | "BUSINESS_APPROVED" | "PENDING_SYSTEM_ADMIN"
    | "SCHEDULED" | "ARCHIVED" | "REJECTED" | "CANCELLED";

export type AccountClosureRequest = { id: string; targetUserId: string; targetName: string; targetEmail: string;
  targetRole: string; employeeId: string | null; departmentId: string | null; departmentName: string | null;
  requesterUserId: string; origin: "SELF_SERVICE" | "SYSTEM_ADMIN_EMERGENCY" | "EMPLOYEE_TERMINATION";
  reason: string; requestedEffectiveDate: string; replacementUserId: string | null;
  replacementName: string | null; status: AccountClosureStatus; requestedAt: string;
  businessApproverUserId: string | null; businessApprovedAt: string | null;
  systemAdminApproverUserId: string | null; systemAdminApprovedAt: string | null;
  decisionNote: string | null; scheduledAt: string | null; archivedAt: string | null;
  cancelledAt: string | null };

export type AccountLifecycleAccount = { userId: string; fullName: string; email: string; role: string;
  status: string; enabled: boolean; archived: boolean; employeeId: string | null;
  departmentId: string | null; departmentName: string | null; protectedAccount: boolean };

export type AccountClosureCandidate = { userId: string; fullName: string; email: string; role: string;
  employeeId: string | null; departmentId: string | null };

export type ArchivedAccount = { id: string; originalUserId: string; fullName: string; email: string;
  role: string; departmentId: string | null; departmentName: string | null; employeeId: string | null;
  employeeNumber: string | null; previousStatus: string; reason: string; closureRequestId: string;
  archivedByUserId: string; archivedAt: string; retentionUntil: string };

export type AccountLifecycleRecord = { id: string; closureRequestId: string; targetUserId: string;
  eventType: string; fromStatus: string | null; toStatus: string; actorUserId: string | null;
  detail: string; occurredAt: string };

export type DirectArchiveChallenge = {
  challengeId: string;
  targetUserId: string;
  targetName: string;
  targetEmail: string;
  targetRole: string;
  departmentId: string | null;
  departmentName: string | null;
  reason: string;
  replacementUserId: string | null;
  replacementName: string | null;
  createdAt: string;
  expiresAt: string;
  resendAvailableAt: string;
  attemptsRemaining: number;
};

export type ArchivedRecoveryChallenge = {
  challengeId: string;
  archivedAccountId: string;
  targetUserId: string;
  targetName: string;
  targetEmail: string;
  employeeId: string | null;
  previousRole: string;
  previousDepartmentId: string | null;
  previousDepartmentName: string | null;
  targetRole: string;
  targetDepartmentId: string | null;
  targetDepartmentName: string | null;
  reason: string;
  createdAt: string;
  expiresAt: string;
  resendAvailableAt: string;
  attemptsRemaining: number;
};

export type RecoveredAccount = {
  userId: string;
  employeeId: string | null;
  previousRole: string;
  role: string;
  previousDepartmentId: string | null;
  departmentId: string | null;
  roleChanged: boolean;
  departmentChanged: boolean;
  recoveredAt: string;
};

export type DepartmentEmployeeSummary = { departmentId: string; totalEmployees: number; activeEmployees: number;
  onLeaveEmployees: number; onboardingEmployees: number };

export type DepartmentEmployee = { id: string; employeeNumber: string; displayName: string; officialEmail: string;
  departmentId: string; designation: string; status: string; lifecycleProtected?: boolean };

export type CompensationRecord = { id: string; employeeId: string; basicSalary: number; hra: number;
  grossSalary: number; totalDeductions: number; netSalary: number; annualCtc: number; currency: string;
  effectiveFrom: string; effectiveTo: string | null; version: number };

export type EmployeeDocument = { id: string; ownerType: "EMPLOYEE" | "VISITOR"; ownerId: string;
  category: "PHOTO" | "IDENTITY" | "EMPLOYMENT" | "OTHER"; filename: string; contentType: string;
  sizeBytes: number; sha256: string; status: string; createdAt: string };

export type VisitorIdentity = { id: string; name: string; email: string; phone: string; company: string | null;
  governmentIdMasked: string | null; identityVerified: boolean; consentVersion: string;
  consentedAt: string; restricted: boolean };

export type IntegrationOverview = { status: "READY" | "DEGRADED"; checkedAt: string;
  services: Array<{ name: string; purpose: string; ready: boolean; detail: string; latencyMs: number }> };

export type PublicDirectoryEmployee = { id: string; displayName: string; designation: string; departmentId: string };

export type TeamLeadAssignment = { id: string; departmentId: string; teamLeadUserId: string;
  teamLeadEmployeeId: string; active: boolean; assignedByUserId: string; assignedAt: string;
  endedByUserId: string | null; endedAt: string | null };

export type DepartmentHrAssignment = { id: string; departmentId: string; hrUserId: string;
  hrEmployeeId: string; active: boolean; assignedByUserId: string; assignedAt: string;
  endedByUserId: string | null; endedAt: string | null };

export type ManagerAssignment = { id: string; departmentId: string; managerUserId: string;
  managerEmployeeId: string; active: boolean; assignedByUserId: string; assignedAt: string;
  endedByUserId: string | null; endedAt: string | null };

export type RoleDepartmentChangeRequest = {
  id: string; requesterUserId: string; requesterEmployeeId: string | null;
  requesterName: string; requesterEmail: string; requesterRole: "HR_ADMIN" | "TEAM_LEAD";
  fromDepartmentId: string | null; fromDepartmentName: string | null;
  targetDepartmentId: string; targetDepartmentName: string; targetOccupied: boolean;
  targetOccupantUserId: string | null; targetOccupantName: string | null; reason: string;
  status: "PENDING" | "APPROVED" | "REJECTED" | "CANCELLED"; requestedAt: string;
  resolution: "MOVE" | "REPLACE" | "SWAP" | null; decisionNote: string | null;
  decidedByUserId: string | null; decidedAt: string | null;
};

export type AccountRecoveryRequest = {
  id: string;
  userId: string;
  fullName: string;
  email: string;
  role: string;
  type: "PASSWORD" | "EMAIL";
  status: "PENDING" | "APPROVED" | "REJECTED" | "USED";
  requestedAt: string;
  approvedAt: string | null;
  expiresAt: string | null;
  recoveryCode: string | null;
};

export type ManagedAppointment = {
  id: string;
  referenceNumber: string;
  type: string;
  status: string;
  visitorName: string;
  visitorEmail: string;
  visitorPhone: string;
  visitorCompany: string | null;
  hostEmployeeId: string;
  routingDepartmentId: string | null;
  requestedEmployeeId: string | null;
  slotStart: string;
  slotEnd: string;
  purpose: string;
  securityIntakeActorId: string | null;
  securityIntakeAt: string | null;
  arrivalVisitorName: string | null;
  arrivalPurpose: string | null;
  identityDocumentType: string | null;
  identityDocumentLastFour: string | null;
  securityNotes: string | null;
  receptionVerificationActorId: string | null;
  receptionVerifiedAt: string | null;
  receptionVerificationRemarks: string | null;
  hrApprovalActorId: string | null;
  hrDecisionAt: string | null;
  hrDecisionRemarks: string | null;
  teamLeadApprovalActorId: string | null;
  teamLeadDecisionAt: string | null;
  teamLeadDecisionRemarks: string | null;
  managerApprovalActorId: string | null;
  managerDecisionAt: string | null;
  managerDecisionRemarks: string | null;
  ceoApprovalActorId: string | null;
  ceoDecisionAt: string | null;
  ceoDecisionRemarks: string | null;
  receptionForwardActorId: string | null;
  receptionForwardedAt: string | null;
  receptionForwardRemarks: string | null;
  createdAt: string;
  assignedToCurrentActor: boolean;
};

export type StaffAccount = {
  userId: string;
  employeeId?: string | null;
  fullName: string;
  email: string;
  roles: string[];
  enabled: boolean;
  forcePasswordChange: boolean;
  status: string;
  grantedPermissions: string[];
  deniedPermissions: string[];
  effectivePermissions: string[];
};

export type InternalNotificationRecipient = {
  userId: string;
  fullName: string;
  email: string;
  roles: string[];
};

export type InternalNotification = {
  id: string;
  senderUserId: string;
  recipientUserId: string;
  senderName: string;
  recipientName: string;
  senderEmail?: string | null;
  recipientEmail?: string | null;
  senderRoles?: string[];
  recipientRoles?: string[];
  message: string;
  priority?: "NORMAL" | "HIGH" | "URGENT";
  category?: "GENERAL" | "ACTION_REQUIRED" | "VISITOR" | "WORK" | "INSIGHT" | "LEAVE" | 'SECURITY' | 'APPROVAL' | 'ESCALATION';
  conversationKey?: string;
  deliveryStatus: "QUEUED" | "DELIVERED" | "FAILED" | 'SUPPRESSED';
  mandatory?: boolean;
  deliveryDueAt?: string | null;
  preferenceVersion?: number | null;
  sentAt: string;
  deliveredAt: string | null;
  readAt: string | null;
  archivedAt?: string | null;
};

export type LeaveRequest = { id: string; employeeId: string; requesterUserId: string; startDate: string;
  endDate: string; reason: string; status: "PENDING" | "APPROVED" | "REJECTED" | "CANCELLED";
  decidedByUserId: string | null; decidedAt: string | null; decisionReason: string | null; createdAt: string };

export type ResourceDiscussion = { id: string; requestedByUserId: string; hrRecipientUserId: string;
  departmentId: string; projectName: string; requiredRoles: string; requestedHeadcount: number;
  priority: "NORMAL" | "HIGH" | "URGENT"; preferredAt: string; justification: string;
  status: "REQUESTED" | "NEEDS_INFORMATION" | "SCHEDULED" | "DECLINED" | "COMPLETED";
  hrResponse: string | null; scheduledAt: string | null; hrDecidedAt: string | null;
  completedAt: string | null; createdAt: string; version: number };

export type HrLifecycleAccount = { userId: string; fullName: string; email: string; status: string; enabled: boolean };

export type MonthlyRecords = { period: string; generatedAt: string; visitorCount: number; employeeCount: number;
  joinedEmployees: number; relievedEmployees: number; pendingLeaveRequests: number;
  visitors: Array<{ id: string; referenceNumber: string; visitorName: string; visitorEmail: string;
    visitorPhone: string; visitorCompany: string | null; type: string; status: string; hostEmployeeId: string;
    hostName: string; routingDepartmentId: string | null; requestedEmployeeId: string | null;
    requestedEmployeeName: string | null; slotStart: string; purpose: string; identityDocumentType: string | null;
    identityDocumentLastFour: string | null; securityActorId: string | null; securityIntakeAt: string;
    receptionActorId: string; receptionVerifiedAt: string; receptionRemarks: string | null;
    hrActorId: string | null; hrDecisionAt: string | null; teamLeadActorId: string | null;
    teamLeadDecisionAt: string | null; managerActorId: string | null;
    managerDecisionAt: string | null; ceoActorId: string | null;
    ceoDecisionAt: string | null; receptionForwardActorId: string | null;
    receptionForwardedAt: string | null; receptionForwardRemarks: string | null;
    badgeNumber: string | null; checkedInAt: string | null; checkedOutAt: string | null;
    processedBy: string | null }>;
  employees: Array<{ id: string; employeeNumber: string; displayName: string; officialEmail: string;
    designation: string; status: string; joiningDate: string; relievingDate: string | null }>;
  leaveRequests: LeaveRequest[] };

export type HistoryDataset = "VISITS" | "EMPLOYEES" | "TERMINATIONS" | "WORKBOARD" | "AUDIT" | "CHECKPOINTS" | "ESSENTIAL_LOGS";

export type HistoryRow = { id: string; occurredAt: string; dataset: HistoryDataset; departmentId: string | null;
  primaryLabel: string; secondaryLabel: string; status: string; details: Record<string, unknown> };

export type CursorPage<T> = { items: T[]; nextCursor: string | null; hasMore: boolean; size: number };

export type ReportExportJob = { id: string; requestedByUserId: string; requestedRole: string;
  dataset: HistoryDataset; format: "CSV" | "XLSX"; status: "QUEUED" | "RUNNING" | "COMPLETED" | "FAILED" | "EXPIRED";
  filename: string | null; rowCount: number; sizeBytes: number; errorMessage: string | null;
  expiresAt: string | null; startedAt: string | null; completedAt: string | null; createdAt: string };

export type RetentionPolicy = { dataset: string; hotDays: number; warmMonths: number; archiveYears: number;
  disposalAction: "DELETE" | "ANONYMIZE"; enabled: boolean; updatedAt: string; updatedBy: string };

export type ArchiveManifest = { dataset: string; partitionName: string; periodStart: string; periodEnd: string;
  rowCount: number; status: "WARM" | "ARCHIVE_ELIGIBLE" | "ARCHIVING" | "ARCHIVED" | "VERIFYING"
      | "VERIFIED" | "DATABASE_REMOVED" | "HOLD_BLOCKED" | "FAILED" | "DISPOSED";
  objectKey: string | null; checksumSha256: string | null; encryptionAlgorithm: string | null;
  encryptionKeyVersion: string | null; objectSizeBytes: number; verifiedAt: string | null;
  restoreTestedAt: string | null; verifiedRowCount: number | null; databaseRemovedAt: string | null;
  disposedAt: string | null; backupExpiresAt: string | null; lastError: string | null;
  holdBlocked: boolean; discoveredAt: string; archivedAt: string | null };

export type DataLegalHold = { id: string; dataset: string; holdKind: "LEGAL_HOLD" | "ACTIVE_INVESTIGATION";
  scopeType: "DATASET" | "PARTITION" | "SUBJECT"; scopeRef: string | null; caseReference: string;
  reason: string; reviewOn: string | null; placedBy: string; placedAt: string; releasedBy: string | null;
  releasedAt: string | null; releaseReason: string | null };

export type GovernanceLedgerEntry = { id: string; sequence: number; actionType: string; dataset: string;
  targetRef: string; actor: string; outcome: string; detailsJson: string; occurredAt: string;
  previousHash: string; entryHash: string };

export type GovernanceLedgerPage = { items: GovernanceLedgerEntry[]; integrityValid: boolean; entriesChecked: number };

export type GovernanceOverview = { archiveStatuses: Record<string, number>; activeHolds: number;
  pendingBackupExpiries: number; ledgerIntegrityValid: boolean; ledgerEntriesChecked: number };

export type RealtimeConnectionState = "connecting" | "live" | "reconnecting" | "offline";

export type SpringPage<T> = {
  content: T[];
  number?: number;
  size?: number;
  totalElements?: number;
  totalPages?: number;
  last?: boolean;
  page?: {
    number: number;
    size: number;
    totalElements: number;
    totalPages: number;
  };
};

export type CountedCursorPage<T> = {
  items: T[];
  nextCursor: string | null;
  hasMore: boolean;
  total: number;
};

export type WorkspaceUpdateCoordinationMessage = {
  type: "refresh" | "leader-state" | "leader-released";
  senderId: string;
  sentAt: number;
  state?: RealtimeConnectionState;
  nonce?: string;
};

export type WorkspaceUpdateLease = {
  leaderId: string;
  expiresAt: number;
};

export type WorkspaceUpdateLockManager = {
  request(
      name: string,
      options: { mode: "exclusive"; signal: AbortSignal },
      callback: () => Promise<void>,
  ): Promise<void>;
};
