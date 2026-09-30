import type { AvailableSlot, PublicAppointment, PublicHost } from "./appointments";

const configuredApiBaseUrl = process.env.NEXT_PUBLIC_API_BASE_URL?.trim().replace(/\/+$/, "");
export const isBackendConfigured = Boolean(configuredApiBaseUrl);
const API_BASE_URL = configuredApiBaseUrl ?? "";
const API_REQUEST_TIMEOUT_MS = 20_000;
const AUTH_SESSION_EXPIRED_EVENT = "brainserve:auth-session-expired";
const inFlightGetRequests = new Map<string, Promise<unknown>>();

type ProblemResponse = {
  title?: string;
  detail?: string;
  errorCode?: string;
  fieldErrors?: Array<{ field: string; message: string }>;
};

export class ApiError extends Error {
  constructor(public readonly status: number, public readonly problem: ProblemResponse) {
    super(problem.detail ?? problem.title ?? "Request failed");
  }
}

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
  category?: "GENERAL" | "ACTION_REQUIRED" | "VISITOR" | "WORK" | "INSIGHT" | "LEAVE";
  conversationKey?: string;
  deliveryStatus: "QUEUED" | "DELIVERED" | "FAILED";
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

const ACCESS_TOKEN_KEY = "brainserve.connect.access-token";
const REFRESH_TOKEN_KEY = "brainserve.connect.refresh-token";
const sessionValue = (key: string) => typeof window === "undefined" ? null : window.sessionStorage.getItem(key);
let accessToken: string | null = sessionValue(ACCESS_TOKEN_KEY);
let refreshToken: string | null = sessionValue(REFRESH_TOKEN_KEY);
let refreshPromise: Promise<boolean> | null = null;

export function setAccessToken(token: string | null) {
  accessToken = token;
  if (typeof window !== "undefined") {
    if (token) window.sessionStorage.setItem(ACCESS_TOKEN_KEY, token);
    else window.sessionStorage.removeItem(ACCESS_TOKEN_KEY);
  }
  if (token === null) {
    refreshToken = null;
    if (typeof window !== "undefined") window.sessionStorage.removeItem(REFRESH_TOKEN_KEY);
  }
}

export function setAuthTokens(access: string, refresh: string) {
  accessToken = access;
  refreshToken = refresh;
  if (typeof window !== "undefined") {
    window.sessionStorage.setItem(ACCESS_TOKEN_KEY, access);
    window.sessionStorage.setItem(REFRESH_TOKEN_KEY, refresh);
  }
}

export function hasAuthSession() {
  return Boolean(accessToken || refreshToken);
}

function expireAuthSession() {
  setAccessToken(null);
  if (typeof window !== "undefined") window.dispatchEvent(new CustomEvent(AUTH_SESSION_EXPIRED_EVENT));
}

export function onAuthSessionExpired(listener: () => void) {
  if (typeof window === "undefined") return () => undefined;
  window.addEventListener(AUTH_SESSION_EXPIRED_EVENT, listener);
  return () => window.removeEventListener(AUTH_SESSION_EXPIRED_EVENT, listener);
}

async function refreshAccessToken() {
  if (!API_BASE_URL) return false;
  if (!refreshToken) return false;
  if (!refreshPromise) {
    const token = refreshToken;
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), API_REQUEST_TIMEOUT_MS);
    refreshPromise = fetch(`${API_BASE_URL}/auth/refresh`, {
      method: "POST", headers: { "Content-Type": "application/json", Accept: "application/json" },
      body: JSON.stringify({ refreshToken: token }), credentials: "omit", signal: controller.signal,
    }).then(async (response) => {
      if (!response.ok) {
        if (response.status === 400 || response.status === 401 || response.status === 403) {
          expireAuthSession();
          return false;
        }
        // Surface a temporary refresh failure, not the original expired-access-token 401.
        // Session restoration can then retry without discarding the refresh credential.
        throw new ApiError(response.status, { detail: "Session verification is temporarily unavailable. Please retry." });
      }
      const tokens = await response.json() as { accessToken: string; refreshToken: string };
      setAuthTokens(tokens.accessToken, tokens.refreshToken);
      return true;
    }).finally(() => { clearTimeout(timeout); refreshPromise = null; });
  }
  return refreshPromise;
}

export function apiRequest<T>(path: string, init: RequestInit = {}, retry = true): Promise<T> {
  const method = (init.method ?? "GET").toUpperCase();
  if (method !== "GET" || init.signal) return performApiRequest<T>(path, init, retry);

  const existing = inFlightGetRequests.get(path);
  if (existing) return existing as Promise<T>;

  const request = performApiRequest<T>(path, init, retry);
  inFlightGetRequests.set(path, request);
  const clearRequest = () => {
    if (inFlightGetRequests.get(path) === request) inFlightGetRequests.delete(path);
  };
  void request.then(clearRequest, clearRequest);
  return request;
}

async function performApiRequest<T>(path: string, init: RequestInit = {}, retry = true): Promise<T> {
  if (!API_BASE_URL) {
    throw new Error("BrainServe Connect is not connected to its secure backend.");
  }
  const headers = new Headers(init.headers);
  headers.set("Accept", "application/json");
  if (init.body && !(init.body instanceof FormData)) headers.set("Content-Type", "application/json");
  if (accessToken) headers.set("Authorization", `Bearer ${accessToken}`);
  const controller = new AbortController();
  let timedOut = false;
  const abortFromCaller = () => controller.abort(init.signal?.reason);
  if (init.signal?.aborted) abortFromCaller();
  else init.signal?.addEventListener("abort", abortFromCaller, { once: true });
  const timeout = setTimeout(() => { timedOut = true; controller.abort(); }, API_REQUEST_TIMEOUT_MS);
  try {
    const response = await fetch(`${API_BASE_URL}${path}`, {
      ...init, headers, credentials: "omit", signal: controller.signal,
    });
    if (response.status === 401 && retry && refreshToken && !path.endsWith("/auth/refresh") && !path.endsWith("/auth/login")) {
      if (await refreshAccessToken()) return performApiRequest<T>(path, init, false);
    }
    if (!response.ok) {
      const problem = (await response.json().catch(() => ({}))) as ProblemResponse;
      throw new ApiError(response.status, problem);
    }
    const contentType = response.headers.get("Content-Type")?.toLowerCase() ?? "";
    if (response.status === 204 || !contentType.includes("json")) return undefined as T;
    return response.json() as Promise<T>;
  } catch (reason) {
    if (timedOut) throw new Error("The BrainServe Connect service did not respond within 20 seconds. Please try again.");
    throw reason;
  } finally {
    clearTimeout(timeout);
    init.signal?.removeEventListener("abort", abortFromCaller);
  }
}

export type RealtimeConnectionState = "connecting" | "live" | "reconnecting" | "offline";

type SpringPage<T> = {
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

function normalizeSpringPage<T>(result: SpringPage<T>): SpringPage<T> {
  const number = result.number ?? result.page?.number;
  const size = result.size ?? result.page?.size;
  const totalElements = result.totalElements ?? result.page?.totalElements;
  const totalPages = result.totalPages ?? result.page?.totalPages;

  return {
    ...result,
    number,
    size,
    totalElements,
    totalPages,
    last: result.last ?? (number !== undefined && totalPages !== undefined
        ? number + 1 >= totalPages
        : undefined),
  };
}

async function requestSpringPage<T>(path: string, init: RequestInit = {}): Promise<SpringPage<T>> {
  return normalizeSpringPage(await apiRequest<SpringPage<T>>(path, init));
}

export type CountedCursorPage<T> = {
  items: T[];
  nextCursor: string | null;
  hasMore: boolean;
  total: number;
};

async function allSpringPageContent<T>(path: string, pageSize = 200): Promise<{ content: T[] }> {
  const separator = path.includes("?") ? "&" : "?";
  const content: T[] = [];
  let page = 0;
  while (true) {
    const result = await requestSpringPage<T>(
        `${path}${separator}page=${page}&size=${pageSize}`,
        { cache: "no-store" },
    );
    content.push(...result.content);
    const isLast = result.last ?? (result.totalPages !== undefined
        ? page + 1 >= result.totalPages
        : result.content.length < pageSize);
    if (isLast) return { content };
    page += 1;
  }
}

/**
 * Opens the authenticated server stream for the browser tab elected as the
 * workspace-update leader. Events contain no business data; they only prompt
 * clients to reload endpoints already authorized for their signed-in roles.
 */
function subscribeDirectlyToWorkspaceUpdates(
    onUpdate: () => void,
    onStateChange: (state: RealtimeConnectionState) => void,
) {
  let stopped = false;
  let controller: AbortController | null = null;
  let reconnectTimer: ReturnType<typeof setTimeout> | null = null;
  let reconnectAttempt = 0;

  const scheduleReconnect = () => {
    if (stopped) return;
    onStateChange("reconnecting");
    const baseDelay = Math.min(30_000, 3_000 * (2 ** reconnectAttempt));
    const jitter = Math.round(Math.random() * 750);
    reconnectAttempt = Math.min(reconnectAttempt + 1, 4);
    reconnectTimer = setTimeout(() => void connect(), baseDelay + jitter);
  };

  const consume = async (response: Response) => {
    if (!response.body) throw new Error("The live update stream is unavailable.");
    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    let buffer = "";
    while (!stopped) {
      const { value, done } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true }).replaceAll("\r\n", "\n");
      const frames = buffer.split("\n\n");
      buffer = frames.pop() ?? "";
      frames.forEach((frame) => {
        const eventName = frame.split("\n").find((line) => line.startsWith("event:"))?.slice(6).trim();
        if (eventName === "workspace-refresh") onUpdate();
      });
    }
  };

  const connect = async () => {
    if (stopped || !accessToken) { onStateChange("offline"); return; }
    controller = new AbortController();
    onStateChange("connecting");
    let handshakeTimedOut = false;
    const handshakeTimeout = setTimeout(() => {
      handshakeTimedOut = true;
      controller?.abort();
    }, API_REQUEST_TIMEOUT_MS);
    try {
      let response = await fetch(`${API_BASE_URL}/realtime/stream`, {
        method: "GET",
        headers: { Accept: "text/event-stream", Authorization: `Bearer ${accessToken}` },
        credentials: "omit",
        cache: "no-store",
        signal: controller.signal,
      });
      if (response.status === 401 && await refreshAccessToken() && accessToken) {
        response = await fetch(`${API_BASE_URL}/realtime/stream`, {
          method: "GET",
          headers: { Accept: "text/event-stream", Authorization: `Bearer ${accessToken}` },
          credentials: "omit",
          cache: "no-store",
          signal: controller.signal,
        });
      }
      clearTimeout(handshakeTimeout);
      if (!response.ok) throw new Error(`Live update connection failed (${response.status}).`);
      reconnectAttempt = 0;
      onStateChange("live");
      await consume(response);
      if (!stopped) scheduleReconnect();
    } catch (reason) {
      clearTimeout(handshakeTimeout);
      if (!stopped && (handshakeTimedOut
          || !(reason instanceof DOMException && reason.name === "AbortError"))) scheduleReconnect();
    }
  };

  void connect();
  return () => {
    stopped = true;
    controller?.abort();
    if (reconnectTimer) clearTimeout(reconnectTimer);
  };
}

const WORKSPACE_UPDATE_CHANNEL = "brainserve.connect.workspace-updates.v1";
const WORKSPACE_UPDATE_LOCK = "brainserve.connect.workspace-update-leader.v1";
const WORKSPACE_UPDATE_LEASE_KEY = "brainserve.connect.workspace-update-lease.v1";
const WORKSPACE_UPDATE_MESSAGE_KEY = "brainserve.connect.workspace-update-message.v1";
const WORKSPACE_UPDATE_HEARTBEAT_MS = 4_000;
const WORKSPACE_UPDATE_LEASE_MS = 15_000;
let workspaceUpdateLeader = false;

export function isWorkspaceUpdateLeader() {
  return workspaceUpdateLeader;
}

type WorkspaceUpdateCoordinationMessage = {
  type: "refresh" | "leader-state" | "leader-released";
  senderId: string;
  sentAt: number;
  state?: RealtimeConnectionState;
  nonce?: string;
};

type WorkspaceUpdateLease = {
  leaderId: string;
  expiresAt: number;
};

type WorkspaceUpdateLockManager = {
  request(
      name: string,
      options: { mode: "exclusive"; signal: AbortSignal },
      callback: () => Promise<void>,
  ): Promise<void>;
};

/**
 * Coordinates live updates across tabs on the same browser origin.
 *
 * The Web Locks API provides atomic leader election where available. Exactly one
 * tab owns the backend stream; followers receive refresh signals over
 * BroadcastChannel. A localStorage lease/message fallback keeps the behavior safe
 * in older browsers. Hidden follower tabs do not perform workspace refreshes;
 * brainserve-app.tsx refreshes them once when they become visible again.
 */
export function subscribeToWorkspaceUpdates(
    onUpdate: () => void,
    onStateChange: (state: RealtimeConnectionState) => void,
) {
  if (typeof window === "undefined") return () => undefined;

  const realtimeStates: RealtimeConnectionState[] = ["connecting", "live", "reconnecting", "offline"];
  const runtimeCrypto = globalThis.crypto as (Crypto & { randomUUID?: () => string }) | undefined;
  const tabId = typeof runtimeCrypto?.randomUUID === "function"
      ? runtimeCrypto.randomUUID()
      : `${Date.now()}-${Math.random().toString(36).slice(2)}`;
  let stopped = false;
  let leader = false;
  let leaderState: RealtimeConnectionState = "connecting";
  let directUnsubscribe: (() => void) | null = null;
  let heartbeatTimer: ReturnType<typeof setInterval> | null = null;
  let fallbackElectionTimer: ReturnType<typeof setInterval> | null = null;
  let fallbackClaimTimer: ReturnType<typeof setTimeout> | null = null;
  let usingLeaseFallback = false;
  let lockAbortController: AbortController | null = null;
  let releaseWebLock: (() => void) | null = null;
  let channel: BroadcastChannel | null = null;

  try {
    if ("BroadcastChannel" in window) channel = new BroadcastChannel(WORKSPACE_UPDATE_CHANNEL);
  } catch {
    channel = null;
  }

  const publish = (message: WorkspaceUpdateCoordinationMessage) => {
    if (stopped) return;
    if (channel) {
      channel.postMessage(message);
      return;
    }
    try {
      window.localStorage.setItem(WORKSPACE_UPDATE_MESSAGE_KEY, JSON.stringify({
        ...message,
        nonce: `${message.sentAt}-${Math.random().toString(36).slice(2)}`,
      }));
    } catch {
      // If browser storage is blocked, leader election falls back to one stream per tab.
    }
  };

  const readLease = (): WorkspaceUpdateLease | null => {
    try {
      const stored = window.localStorage.getItem(WORKSPACE_UPDATE_LEASE_KEY);
      if (!stored) return null;
      const candidate = JSON.parse(stored) as Partial<WorkspaceUpdateLease>;
      return typeof candidate.leaderId === "string" && typeof candidate.expiresAt === "number"
          ? { leaderId: candidate.leaderId, expiresAt: candidate.expiresAt }
          : null;
    } catch {
      return null;
    }
  };

  const writeLease = () => {
    try {
      window.localStorage.setItem(WORKSPACE_UPDATE_LEASE_KEY, JSON.stringify({
        leaderId: tabId,
        expiresAt: Date.now() + WORKSPACE_UPDATE_LEASE_MS,
      } satisfies WorkspaceUpdateLease));
      return readLease()?.leaderId === tabId;
    } catch {
      return false;
    }
  };

  const releaseOwnedLease = () => {
    try {
      if (readLease()?.leaderId === tabId) window.localStorage.removeItem(WORKSPACE_UPDATE_LEASE_KEY);
    } catch {
      // The lease expires automatically if storage becomes unavailable during cleanup.
    }
  };

  const publishLeaderState = () => publish({
    type: "leader-state",
    senderId: tabId,
    sentAt: Date.now(),
    state: leaderState,
  });

  const stopLeading = (announce: boolean) => {
    if (!leader) return;
    leader = false;
    workspaceUpdateLeader = false;
    directUnsubscribe?.();
    directUnsubscribe = null;
    if (heartbeatTimer) clearInterval(heartbeatTimer);
    heartbeatTimer = null;
    if (usingLeaseFallback) releaseOwnedLease();
    if (announce && !stopped) publish({ type: "leader-released", senderId: tabId, sentAt: Date.now() });
  };

  const startLeading = () => {
    if (stopped || leader) return;
    leader = true;
    workspaceUpdateLeader = true;
    leaderState = "connecting";
    onStateChange(leaderState);
    publishLeaderState();
    directUnsubscribe = subscribeDirectlyToWorkspaceUpdates(
        () => {
          if (stopped || !leader) return;
          onUpdate();
          publish({ type: "refresh", senderId: tabId, sentAt: Date.now() });
        },
        (state) => {
          if (stopped || !leader) return;
          leaderState = state;
          onStateChange(state);
          publishLeaderState();
        },
    );
    heartbeatTimer = setInterval(() => {
      if (stopped || !leader) return;
      if (usingLeaseFallback) {
        const lease = readLease();
        if (lease && lease.leaderId !== tabId && lease.expiresAt > Date.now()) {
          stopLeading(false);
          onStateChange("reconnecting");
          return;
        }
        if (!writeLease()) {
          stopLeading(false);
          onStateChange("reconnecting");
          return;
        }
      }
      publishLeaderState();
    }, WORKSPACE_UPDATE_HEARTBEAT_MS);
  };

  const acceptCoordinationMessage = (value: unknown) => {
    if (!value || typeof value !== "object") return;
    const message = value as Partial<WorkspaceUpdateCoordinationMessage>;
    if (message.senderId === tabId || typeof message.senderId !== "string") return;
    if (message.type === "refresh") {
      onUpdate();
      return;
    }
    if (message.type === "leader-state" && message.state
        && realtimeStates.includes(message.state)) {
      if (!leader) onStateChange(message.state);
      return;
    }
    if (message.type === "leader-released" && !leader) onStateChange("reconnecting");
  };

  if (channel) channel.onmessage = (event: MessageEvent<unknown>) => acceptCoordinationMessage(event.data);

  const evaluateFallbackLeadership = () => {
    if (stopped || !usingLeaseFallback) return;
    const lease = readLease();
    if (leader) {
      if (lease && lease.leaderId !== tabId && lease.expiresAt > Date.now()) {
        stopLeading(false);
        onStateChange("reconnecting");
      }
      return;
    }
    if (lease && lease.expiresAt > Date.now()) return;
    if (fallbackClaimTimer) return;
    fallbackClaimTimer = setTimeout(() => {
      fallbackClaimTimer = null;
      if (stopped || leader) return;
      const current = readLease();
      if (current && current.expiresAt > Date.now()) return;
      if (writeLease()) startLeading();
    }, 100 + Math.round(Math.random() * 400));
  };

  const startLeaseFallback = () => {
    if (stopped || usingLeaseFallback) return;
    try {
      const probeKey = `${WORKSPACE_UPDATE_LEASE_KEY}.probe.${tabId}`;
      window.localStorage.setItem(probeKey, "1");
      window.localStorage.removeItem(probeKey);
    } catch {
      // Browser storage can be disabled by privacy policy. Preserve live updates
      // by falling back to a direct stream in this tab.
      startLeading();
      return;
    }
    usingLeaseFallback = true;
    evaluateFallbackLeadership();
    fallbackElectionTimer = setInterval(evaluateFallbackLeadership, WORKSPACE_UPDATE_HEARTBEAT_MS);
  };

  const handleStorage = (event: StorageEvent) => {
    if (event.key === WORKSPACE_UPDATE_MESSAGE_KEY && event.newValue) {
      try { acceptCoordinationMessage(JSON.parse(event.newValue)); }
      catch { /* Ignore malformed messages from unrelated or older clients. */ }
      return;
    }
    if (event.key === WORKSPACE_UPDATE_LEASE_KEY && usingLeaseFallback) evaluateFallbackLeadership();
  };
  window.addEventListener("storage", handleStorage);
  onStateChange("connecting");

  const lockManager = typeof navigator !== "undefined"
      ? (navigator as Navigator & { locks?: WorkspaceUpdateLockManager }).locks
      : undefined;
  if (lockManager?.request) {
    lockAbortController = new AbortController();
    void lockManager.request(
        WORKSPACE_UPDATE_LOCK,
        { mode: "exclusive", signal: lockAbortController.signal },
        async () => {
          if (stopped) return;
          startLeading();
          await new Promise<void>((resolve) => { releaseWebLock = resolve; });
          releaseWebLock = null;
          stopLeading(false);
        },
    ).catch((reason: unknown) => {
      if (stopped || (reason instanceof DOMException && reason.name === "AbortError")) return;
      startLeaseFallback();
    });
  } else {
    startLeaseFallback();
  }

  return () => {
    stopped = true;
    window.removeEventListener("storage", handleStorage);
    lockAbortController?.abort();
    releaseWebLock?.();
    releaseWebLock = null;
    stopLeading(false);
    if (fallbackElectionTimer) clearInterval(fallbackElectionTimer);
    if (fallbackClaimTimer) clearTimeout(fallbackClaimTimer);
    releaseOwnedLease();
    channel?.close();
  };
}

export const brainServeApi = {
  companyProfile() {
    return apiRequest<CompanyProfile>("/public/company-profile");
  },
  registerVisitor(payload: { name: string; email: string; phone: string; company: string | null;
    governmentId: string | null; consentVersion: string }, idempotencyKey: string) {
    return apiRequest<VisitorIdentity>("/public/visitors", {
      method: "POST", headers: { "Idempotency-Key": idempotencyKey }, body: JSON.stringify(payload),
    });
  },
  visitor(id: string) {
    return apiRequest<VisitorIdentity>(`/visitors/${id}`);
  },
  searchVisitors(query: string, page = 0, size = 25) {
    const params = new URLSearchParams({ query, page: String(page), size: String(size), sort: "name,asc" });
    return requestSpringPage<VisitorIdentity>(`/visitors/search?${params}`, { cache: "no-store" });
  },
  verifyVisitor(id: string) {
    return apiRequest<VisitorIdentity>(`/visitors/${id}/verify`, { method: "POST" });
  },
  login(email: string, password: string) {
    return apiRequest<{ accessToken: string; refreshToken: string; forcePasswordChange: boolean }>("/auth/login", {
      method: "POST",
      body: JSON.stringify({ email, password }),
    });
  },
  async logout() {
    const token = refreshToken;
    setAccessToken(null);
    if (!token) return;
    try {
      await apiRequest<void>("/auth/logout", {
        method: "POST", body: JSON.stringify({ refreshToken: token }), keepalive: true,
      }, false);
    } catch {
      // Local credentials are already cleared. A disconnected backend must not
      // prevent the user from ending the browser session.
    }
  },
  requestPasswordChangeOtp(currentPassword: string) {
    return apiRequest<void>("/auth/change-password/request-otp", {
      method: "POST",
      body: JSON.stringify({ currentPassword }),
    });
  },
  confirmPasswordChange(otp: string, newPassword: string) {
    return apiRequest<void>("/auth/change-password/confirm", {
      method: "POST",
      body: JSON.stringify({ otp, newPassword }),
    });
  },
  registerAccount(fullName: string, email: string, password: string, role: string) {
    return apiRequest<{ id: string; email: string; status: string; message: string }>("/register", {
      method: "POST",
      body: JSON.stringify({ fullName, email, password, role }),
    });
  },
  requestAccountRecovery(identifier: string, role: string, type: "PASSWORD" | "EMAIL") {
    return apiRequest<{ message: string }>("/auth/recovery/requests", {
      method: "POST", body: JSON.stringify({ identifier, role, type }),
    });
  },
  recoverPassword(code: string, newPassword: string, confirmPassword: string) {
    return apiRequest<void>("/auth/recovery/password", {
      method: "POST", body: JSON.stringify({ code, newPassword, confirmPassword }),
    });
  },
  recoverEmail(code: string, newEmail: string, confirmEmail: string) {
    return apiRequest<void>("/auth/recovery/email", {
      method: "POST", body: JSON.stringify({ code, newEmail, confirmEmail }),
    });
  },
  pendingAccountRecoveryRequests() {
    return apiRequest<AccountRecoveryRequest[]>("/admin/account-recovery");
  },
  decideAccountRecovery(id: string, decision: "approve" | "reject", reason = "") {
    return apiRequest<AccountRecoveryRequest>(`/admin/account-recovery/${id}/${decision}`, {
      method: "POST", body: JSON.stringify({ reason }),
    });
  },
  pendingSystemAdminUsers() {
    return apiRequest<ProvisioningAccount[]>("/admin/users");
  },
  ceoSlot() {
    return apiRequest<CeoSlot>("/admin/users/ceo-slot");
  },
  createPrivilegedAccount(fullName: string, email: string, role: string) {
    return apiRequest<ProvisioningAccount>("/admin/users", {
      method: "POST",
      body: JSON.stringify({ fullName, email, role }),
    });
  },
  decideSystemAdminUser(id: string, decision: "approve" | "reject",
                        onboarding?: HrAccountApprovalInput, reason = "") {
    return apiRequest<ProvisioningAccount>(`/admin/users/${id}/${decision}`, {
      method: "POST",
      body: decision === "approve" ? (onboarding ? JSON.stringify(onboarding) : undefined)
          : JSON.stringify({ reason }),
    });
  },
  pendingCeoUsers() {
    return apiRequest<ProvisioningAccount[]>("/ceo/users");
  },
  decideCeoUser(id: string, decision: "approve" | "reject",
                onboarding?: HrAccountApprovalInput, reason = "") {
    return apiRequest<ProvisioningAccount>(`/ceo/users/${id}/${decision}`, {
      method: "POST",
      body: decision === "approve" ? JSON.stringify(onboarding) : JSON.stringify({ reason }),
    });
  },
  pendingHrUsers() {
    return apiRequest<ProvisioningAccount[]>("/hr/users");
  },
  decideHrUser(id: string, decision: "approve" | "reject",
               onboarding?: HrAccountApprovalInput, reason = "") {
    return apiRequest<ProvisioningAccount>(`/hr/users/${id}/${decision}`, {
      method: "POST",
      body: decision === "approve"
          ? (onboarding ? JSON.stringify(onboarding) : undefined)
          : JSON.stringify({ reason }),
    });
  },
  createAppointment(payload: unknown, idempotencyKey: string) {
    return apiRequest<PublicAppointment>("/public/appointments", {
      method: "POST",
      headers: { "Idempotency-Key": idempotencyKey },
      body: JSON.stringify(payload),
    });
  },
  verifyAppointment(reference: string, otp: string) {
    return apiRequest<PublicAppointment>(`/public/appointments/${reference}/verify-otp`, {
      method: "POST",
      body: JSON.stringify({ otp }),
    });
  },
  trackAppointment(reference: string) {
    return apiRequest<PublicAppointment>(`/public/appointments/${encodeURIComponent(reference.toUpperCase())}`);
  },
  requestAppointmentCancellationOtp(reference: string) {
    return apiRequest<void>(
        `/public/appointments/${encodeURIComponent(reference.toUpperCase())}/cancel/request-otp`,
        { method: "POST" });
  },
  cancelAppointment(reference: string, otp: string) {
    return apiRequest<PublicAppointment>(`/public/appointments/${encodeURIComponent(reference.toUpperCase())}/cancel`, {
      method: "POST", body: JSON.stringify({ otp }),
    });
  },
  visitorPass(reference: string) {
    return apiRequest<VisitorPass>(`/public/appointments/${encodeURIComponent(reference.toUpperCase())}/pass`);
  },
  publicHosts() {
    return apiRequest<PublicHost[]>("/public/hosts");
  },
  publicEmployees(departmentId: string, query = "") {
    const params = new URLSearchParams({
      departmentId, page: "0", size: "25", sort: "displayName,asc",
    });
    if (query.trim()) params.set("query", query.trim());
    return requestSpringPage<PublicDirectoryEmployee>(`/public/employees?${params}`, { cache: "no-store" });
  },
  publicDepartments() {
    return apiRequest<Array<{ id: string; code: string; name: string; active: boolean; version: number }>>(
        "/public/departments", { cache: "no-store" },
    );
  },
  availableSlots(employeeId: string, date: string, appointmentType: string) {
    return apiRequest<AvailableSlot[]>(`/public/hosts/${employeeId}/available-slots?date=${encodeURIComponent(date)}&type=${encodeURIComponent(appointmentType)}`);
  },
  visitTypeCounts(from: string, to: string) {
    return apiRequest<Array<{ type: string; total: number }>>(`/dashboard/visit-types?${new URLSearchParams({ from, to })}`);
  },
  dashboard(range?: { from: string; to: string }) {
    return apiRequest<{ awaitingApproval: number; activeVisits: number; visitorsInside: number;
      totalEmployees: number; activeEmployees: number; scheduledVisits: number; arrivedVisits: number;
      completedVisits: number; cancelledVisits: number; rejectedVisits: number; assignedWork: number;
      inProgressWork: number; completedWork: number; approvedWork: number; averageWaitSeconds: number;
      role: string; scope: string; departmentId: string | null; from: string; to: string; generatedAt: string }>(`/dashboard/summary${range ? `?${new URLSearchParams({ period: "CUSTOM", ...range })}` : ""}`);
  },
  history(filters: { dataset: HistoryDataset; from: string; to: string; departmentId?: string;
    status?: string; query?: string; cursor?: string; size?: number }) {
    const params = new URLSearchParams({ dataset: filters.dataset, from: filters.from, to: filters.to,
      size: String(filters.size ?? 50) });
    if (filters.departmentId) params.set("departmentId", filters.departmentId);
    if (filters.status) params.set("status", filters.status);
    if (filters.query) params.set("query", filters.query);
    if (filters.cursor) params.set("cursor", filters.cursor);
    return apiRequest<CursorPage<HistoryRow>>(`/history?${params.toString()}`);
  },
  requestReportExport(payload: { dataset: HistoryDataset; format: "CSV" | "XLSX"; from: string; to: string;
    departmentId?: string; status?: string; query?: string }) {
    return apiRequest<ReportExportJob>("/report-exports", { method: "POST", body: JSON.stringify(payload) });
  },
  reportExports() { return apiRequest<ReportExportJob[]>("/report-exports"); },
  reportExportDownload(id: string) {
    return apiRequest<{ url: string; expiresAt: string; filename: string }>(`/report-exports/${id}/download-url`);
  },
  retryReportExport(id: string) {
    return apiRequest<ReportExportJob>(`/report-exports/${id}/retry`, { method: "POST" });
  },
  retentionPolicies() { return apiRequest<RetentionPolicy[]>("/admin/data-governance/retention-policies"); },
  updateRetentionPolicy(dataset: string, payload: Pick<RetentionPolicy,
      "hotDays" | "warmMonths" | "archiveYears" | "disposalAction" | "enabled">) {
    return apiRequest<RetentionPolicy>(`/admin/data-governance/retention-policies/${encodeURIComponent(dataset)}`,
        { method: "PUT", body: JSON.stringify(payload) });
  },
  archiveManifests() { return apiRequest<ArchiveManifest[]>("/admin/data-governance/archive-manifests"); },
  governanceOverview() { return apiRequest<GovernanceOverview>("/admin/data-governance/overview"); },
  dataLegalHolds() { return apiRequest<DataLegalHold[]>("/admin/data-governance/legal-holds"); },
  createDataLegalHold(payload: { dataset: string; holdKind: DataLegalHold["holdKind"];
    scopeType: DataLegalHold["scopeType"]; scopeRef: string | null; caseReference: string;
    reason: string; reviewOn: string | null }) {
    return apiRequest<DataLegalHold>("/admin/data-governance/legal-holds",
        { method: "POST", body: JSON.stringify(payload) });
  },
  releaseDataLegalHold(holdId: string, reason: string) {
    return apiRequest<DataLegalHold>(`/admin/data-governance/legal-holds/${encodeURIComponent(holdId)}/release`,
        { method: "POST", body: JSON.stringify({ reason }) });
  },
  governanceLedger(size = 50) {
    return apiRequest<GovernanceLedgerPage>(`/admin/data-governance/ledger?size=${size}`);
  },
  me() {
    return apiRequest<{ userId: string; employeeId: string | null; email: string; roles: string[]; permissions: string[]; forcePasswordChange: boolean }>("/auth/me");
  },
  appointments() {
    // Reception and Security must never lose a newly-created walk-in behind the
    // first server page. Fetch every authorized page for the current office day.
    return allSpringPageContent<ManagedAppointment>("/appointments?sort=slotStart,asc");
  },
  registerAtReception(payload: unknown, idempotencyKey: string) {
    return apiRequest<ManagedAppointment>("/appointments", {
      method: "POST",
      headers: { "Idempotency-Key": idempotencyKey },
      body: JSON.stringify(payload),
    });
  },
  registerAtSecurity(payload: unknown, idempotencyKey: string) {
    return apiRequest<ManagedAppointment>("/appointments/security-walk-ins", {
      method: "POST",
      headers: { "Idempotency-Key": idempotencyKey },
      body: JSON.stringify(payload),
    });
  },
  decideVisit(id: string, stage: "hr" | "team-lead" | "manager" | "ceo", decision: "approve" | "reject", remarks = "") {
    return apiRequest<ManagedAppointment>(`/appointments/${id}/${stage}-${decision}`, {
      method: "POST",
      body: JSON.stringify({ remarks }),
    });
  },
  decideHostVisit(id: string, decision: "approve" | "reject", remarks = "") {
    return apiRequest<ManagedAppointment>(`/appointments/${id}/${decision}`, {
      method: "POST", body: JSON.stringify({ remarks }),
    });
  },
  recordSecurityIntake(id: string, payload: { visitorName: string; purpose: string;
    identityDocumentType: string | null; identityDocumentLastFour: string | null; notes: string | null }) {
    return apiRequest<ManagedAppointment>(`/appointments/${id}/security-intake`, {
      method: "POST", body: JSON.stringify(payload),
    });
  },
  decideReceptionVisit(id: string, decision: "verify" | "reject", remarks = "") {
    return apiRequest<ManagedAppointment>(`/appointments/${id}/reception-${decision}`, {
      method: "POST", body: JSON.stringify({ remarks }),
    });
  },
  forwardReceptionVisit(id: string, remarks = "") {
    return apiRequest<ManagedAppointment>(`/appointments/${id}/reception-forward`, {
      method: "POST", body: JSON.stringify({ remarks }),
    });
  },
  employees(departmentId?: string) {
    return this.employeePage({ departmentId, page: 0, size: 100 });
  },
  employeePage(filters: { query?: string; departmentId?: string; status?: string;
    page?: number; size?: number; sort?: string } = {}, signal?: AbortSignal) {
    const params = new URLSearchParams({
      page: String(filters.page ?? 0),
      size: String(Math.max(25, Math.min(filters.size ?? 50, 100))),
      sort: filters.sort ?? "displayName,asc",
    });
    if (filters.query?.trim()) params.set("query", filters.query.trim());
    if (filters.departmentId) params.set("departmentId", filters.departmentId);
    if (filters.status && filters.status !== "All") params.set("status", filters.status);
    return requestSpringPage<DepartmentEmployee>(`/employees?${params}`, {
      cache: "no-store",
      signal,
    });
  },
  departmentEmployeeSummary() {
    return apiRequest<DepartmentEmployeeSummary[]>("/employees/department-summary");
  },
  teamLeadAssignments() {
    return apiRequest<TeamLeadAssignment[]>("/team-leads/assignments");
  },
  departmentHrAssignments() {
    return apiRequest<DepartmentHrAssignment[]>("/department-hrs/assignments");
  },
  managerAssignments() {
    return apiRequest<ManagerAssignment[]>("/managers/assignments");
  },
  managerCandidates() {
    return apiRequest<Array<{ userId: string; employeeId: string; fullName: string; email: string;
      currentDepartmentId: string | null; currentDepartmentCode: string | null;
      currentDepartmentName: string | null }>>("/managers/candidates");
  },
  assignManager(departmentId: string, managerUserId: string) {
    return apiRequest<ManagerAssignment>("/managers/assignments", {
      method: "POST", body: JSON.stringify({ departmentId, managerUserId }),
    });
  },
  endManagerAssignment(id: string) {
    return apiRequest<ManagerAssignment>(`/managers/assignments/${id}/end`, { method: "POST" });
  },
  myManagerAssignment() {
    return apiRequest<{ assignmentId: string; departmentId: string; managerUserId: string;
      managerEmployeeId: string; fullName: string; email: string }>("/managers/me/assignment");
  },
  transitionOperationalRole(userId: string, role: "ROLE_EMPLOYEE" | "ROLE_TEAM_LEAD" | "ROLE_HR_ADMIN" | "ROLE_MANAGER",
                            departmentId: string, reason: string) {
    return apiRequest<{ userId: string; previousRole: string; role: string; departmentId: string;
      changedAt: string }>(`/admin/role-transitions/${userId}`, {
      method: "POST", body: JSON.stringify({ role, departmentId, reason }),
    });
  },
  operationalRoleCandidates(query = "") {
    const params = new URLSearchParams({ page: "0", size: "100", sort: "fullName,asc" });
    if (query.trim()) params.set("query", query.trim());
    return requestSpringPage<{ userId: string; employeeId: string; fullName: string;
      email: string; role: string; departmentId: string }>(
        `/admin/role-transitions/candidates?${params}`, { cache: "no-store" });
  },
  succeedChiefExecutive(currentCeoUserId: string, successorUserId: string,
                        formerCeoDepartmentId: string, reason: string) {
    return apiRequest<{ formerCeoUserId: string; successorCeoUserId: string;
      formerCeoDepartmentId: string; changedAt: string }>(
        "/admin/role-transitions/ceo-succession", {
          method: "POST",
          body: JSON.stringify({ currentCeoUserId, successorUserId, formerCeoDepartmentId, reason }),
        });
  },
  departmentHrCandidates() {
    return apiRequest<Array<{ userId: string; employeeId: string; fullName: string; email: string;
      currentDepartmentId: string | null; currentDepartmentCode: string | null; currentDepartmentName: string | null }>>(
        "/department-hrs/candidates");
  },
  assignDepartmentHr(departmentId: string, hrUserId: string) {
    return apiRequest<DepartmentHrAssignment>("/department-hrs/assignments", {
      method: "POST", body: JSON.stringify({ departmentId, hrUserId }),
    });
  },
  endDepartmentHrAssignment(id: string) {
    return apiRequest<DepartmentHrAssignment>(`/department-hrs/assignments/${id}/end`, { method: "POST" });
  },
  assignTeamLead(departmentId: string, employeeId: string) {
    return apiRequest<TeamLeadAssignment>("/team-leads/assignments", {
      method: "POST", body: JSON.stringify({ departmentId, employeeId }),
    });
  },
  endTeamLeadAssignment(id: string) {
    return apiRequest<TeamLeadAssignment>(`/team-leads/assignments/${id}/end`, { method: "POST" });
  },
  myTeamLeadAssignment() {
    return apiRequest<{ assignmentId: string; departmentId: string; teamLeadUserId: string;
      teamLeadEmployeeId: string; fullName: string; email: string }>("/team-leads/me/assignment");
  },
  myTeam() {
    return requestSpringPage<DepartmentEmployee>("/team-leads/me/team?page=0&size=50&sort=displayName,asc");
  },
  myTeamLeadWorkspace() {
    return apiRequest<{ assignment: { assignmentId: string; departmentId: string; teamLeadUserId: string;
        teamLeadEmployeeId: string; fullName: string; email: string };
      department: { id: string; code: string; name: string };
      employees: SpringPage<DepartmentEmployee> }>("/team-leads/me/workspace?page=0&size=50&sort=displayName,asc")
        .then((workspace) => ({ ...workspace, employees: normalizeSpringPage(workspace.employees) }));
  },
  createEmployee(payload: unknown) {
    return apiRequest<{ employee: { id: string; employeeNumber: string; displayName: string;
        officialEmail: string; departmentId: string; designation: string; status: string } }>("/employees", {
      method: "POST", body: JSON.stringify(payload),
    });
  },
  upsertExecutiveProfile(payload: { departmentId: string; phoneNumber: string; designation: string; joiningDate: string }) {
    return apiRequest<{ id: string; employeeNumber: string; displayName: string; officialEmail: string;
      phoneNumber: string | null; departmentId: string; designation: string; joiningDate: string;
      status: string; version: number }>("/employees/me/executive-profile", {
      method: "PUT", body: JSON.stringify(payload),
    });
  },
  changeEmployeeStatus(id: string, status: string) {
    return apiRequest(`/employees/${id}/status`, { method: "PATCH", body: JSON.stringify({ status }) });
  },
  requestEmployeeTermination(employeeId: string, reason: string, effectiveDate: string) {
    return apiRequest<EmployeeTerminationRequest>("/employee-terminations", {
      method: "POST", body: JSON.stringify({ employeeId, reason, effectiveDate }),
    });
  },
  myEmployeeTerminations() {
    return apiRequest<EmployeeTerminationRequest[]>("/employee-terminations/mine");
  },
  pendingEmployeeTerminations() {
    return apiRequest<EmployeeTerminationRequest[]>("/employee-terminations/pending");
  },
  employeeTerminationHistory() {
    return apiRequest<EmployeeTerminationRequest[]>("/employee-terminations/history");
  },
  decideEmployeeTermination(id: string, decision: "approve" | "reject", note: string) {
    return apiRequest<EmployeeTerminationRequest>(`/employee-terminations/${id}/${decision}`, {
      method: "POST", body: JSON.stringify({ note }),
    });
  },
  departments() {
    return apiRequest<Array<{ id: string; code: string; name: string; active: boolean; version: number }>>("/departments");
  },
  visibleDepartments() {
    return apiRequest<Array<{ id: string; code: string; name: string; active: boolean; version: number }>>("/departments/visible");
  },
  departmentLeadership() {
    return apiRequest<Array<{ departmentId: string;
      teamLead: { fullName: string } | null;
      hr: { fullName: string } | null;
      manager: { fullName: string } | null }>>("/departments/leadership");
  },
  myProfile() {
    return apiRequest<MyProfile>("/profile/me");
  },
  uploadMyProfilePhoto(file: File) {
    const body = new FormData();
    body.append("file", file);
    return apiRequest<MyProfile>("/profile/me/photo", { method: "POST", body });
  },
  requestMyAccountClosure(reason: string, effectiveDate: string, replacementUserId: string | null) {
    return apiRequest<AccountClosureRequest>("/account-closures/me", {
      method: "POST", body: JSON.stringify({ reason, effectiveDate, replacementUserId }),
    });
  },
  myAccountClosures() {
    return apiRequest<AccountClosureRequest[]>("/account-closures/me");
  },
  cancelAccountClosure(id: string) {
    return apiRequest<AccountClosureRequest>(`/account-closures/${id}/cancel`, { method: "POST" });
  },
  businessPendingAccountClosures() {
    return apiRequest<AccountClosureRequest[]>("/account-closures/business-pending");
  },
  decideBusinessAccountClosure(id: string, decision: "approve" | "reject",
                               replacementUserId: string | null, note: string) {
    return apiRequest<AccountClosureRequest>(`/account-closures/${id}/business-${decision}`, {
      method: "POST", body: JSON.stringify(decision === "approve" ? { replacementUserId, note } : { note }),
    });
  },
  accountClosureCandidates(targetUserId: string) {
    return apiRequest<AccountClosureCandidate[]>(`/account-closures/candidates?targetUserId=${encodeURIComponent(targetUserId)}`);
  },
  accountClosureRequests() {
    return apiRequest<AccountClosureRequest[]>("/admin/account-closures");
  },
  accountLifecycleAccountPage(filters: {
    query?: string; role?: string; departmentId?: string; page?: number; size?: number;
  } = {}) {
    const params = new URLSearchParams({
      page: String(filters.page ?? 0),
      size: String(Math.max(25, Math.min(filters.size ?? 25, 100))),
    });
    if (filters.query?.trim()) params.set("query", filters.query.trim());
    if (filters.role && filters.role !== "ALL") params.set("role", filters.role);
    if (filters.departmentId) params.set("departmentId", filters.departmentId);
    return requestSpringPage<AccountLifecycleAccount>(
        `/admin/account-closures/active-accounts?${params}`, { cache: "no-store" });
  },
  archivedAccountPage(filters: { query?: string; page?: number; size?: number } = {}) {
    const params = new URLSearchParams({
      page: String(filters.page ?? 0),
      size: String(Math.max(25, Math.min(filters.size ?? 25, 100))),
    });
    if (filters.query?.trim()) params.set("query", filters.query.trim());
    return requestSpringPage<ArchivedAccount>(
        `/admin/account-closures/archived?${params}`, { cache: "no-store" });
  },
  accountClosureHistory(id: string) {
    return apiRequest<AccountLifecycleRecord[]>(`/admin/account-closures/${id}/history`);
  },
  decideSystemAdminAccountClosure(id: string, decision: "approve" | "reject",
                                  replacementUserId: string | null, note: string) {
    return apiRequest<AccountClosureRequest>(`/admin/account-closures/${id}/${decision}`, {
      method: "POST", body: JSON.stringify(decision === "approve" ? { replacementUserId, note } : { note }),
    });
  },
  activeDirectArchiveChallenge() {
    return apiRequest<DirectArchiveChallenge | undefined>(
        "/admin/account-closures/direct-archive/challenge", { cache: "no-store" });
  },
  requestDirectArchiveOtp(targetUserId: string, currentPassword: string, reason: string,
                          replacementUserId: string | null) {
    return apiRequest<DirectArchiveChallenge>("/admin/account-closures/direct-archive/request-otp", {
      method: "POST", body: JSON.stringify({ targetUserId, currentPassword, reason, replacementUserId }),
    });
  },
  resendDirectArchiveOtp(challengeId: string) {
    return apiRequest<DirectArchiveChallenge>(
        `/admin/account-closures/direct-archive/challenge/${challengeId}/resend`, { method: "POST" });
  },
  cancelDirectArchiveChallenge(challengeId: string) {
    return apiRequest<void>(
        `/admin/account-closures/direct-archive/challenge/${challengeId}`, { method: "DELETE" });
  },
  directArchiveAccount(challengeId: string, otp: string) {
    return apiRequest<AccountClosureRequest>("/admin/account-closures/direct-archive", {
      method: "POST", body: JSON.stringify({ challengeId, otp }),
    });
  },
  activeArchivedRecoveryChallenge() {
    return apiRequest<ArchivedRecoveryChallenge | undefined>(
        "/admin/account-closures/archived-recovery/challenge", { cache: "no-store" });
  },
  requestArchivedRecoveryOtp(payload: {
    archivedAccountId: string; targetRole: string; departmentId: string | null;
    currentPassword: string; reason: string;
  }) {
    return apiRequest<ArchivedRecoveryChallenge>(
        "/admin/account-closures/archived-recovery/request-otp", {
          method: "POST", body: JSON.stringify(payload),
        });
  },
  resendArchivedRecoveryOtp(challengeId: string) {
    return apiRequest<ArchivedRecoveryChallenge>(
        `/admin/account-closures/archived-recovery/challenge/${challengeId}/resend`, { method: "POST" });
  },
  cancelArchivedRecoveryChallenge(challengeId: string) {
    return apiRequest<void>(
        `/admin/account-closures/archived-recovery/challenge/${challengeId}`, { method: "DELETE" });
  },
  recoverArchivedAccount(challengeId: string, otp: string) {
    return apiRequest<RecoveredAccount>("/admin/account-closures/archived-recovery", {
      method: "POST", body: JSON.stringify({ challengeId, otp }),
    });
  },
  myRoleDepartmentChanges() {
    return apiRequest<RoleDepartmentChangeRequest[]>("/role-department-changes/me");
  },
  pendingRoleDepartmentChanges() {
    return apiRequest<RoleDepartmentChangeRequest[]>("/role-department-changes/pending");
  },
  requestRoleDepartmentChange(payload: { targetDepartmentId: string; reason: string;
    phoneNumber?: string | null; designation?: string | null; joiningDate?: string | null }) {
    return apiRequest<RoleDepartmentChangeRequest>("/role-department-changes", {
      method: "POST", body: JSON.stringify(payload),
    });
  },
  approveRoleDepartmentChange(id: string, resolution: "MOVE" | "REPLACE" | "SWAP", note: string) {
    return apiRequest<RoleDepartmentChangeRequest>(`/role-department-changes/${id}/approve`, {
      method: "POST", body: JSON.stringify({ resolution, note }),
    });
  },
  rejectRoleDepartmentChange(id: string, note: string) {
    return apiRequest<RoleDepartmentChangeRequest>(`/role-department-changes/${id}/reject`, {
      method: "POST", body: JSON.stringify({ note }),
    });
  },
  cancelRoleDepartmentChange(id: string) {
    return apiRequest<RoleDepartmentChangeRequest>(`/role-department-changes/${id}/cancel`, { method: "POST" });
  },
  createDepartment(code: string, name: string) {
    return apiRequest<{ id: string; code: string; name: string; active: boolean; version: number }>("/departments", {
      method: "POST", body: JSON.stringify({ code, name }),
    });
  },
  changeDepartmentStatus(id: string, active: boolean) {
    return apiRequest<{ id: string; code: string; name: string; active: boolean; version: number }>(`/departments/${id}/status`, {
      method: "PATCH", body: JSON.stringify({ active }),
    });
  },
  staffAccounts() {
    return this.staffAccountPage({ page: 0, size: 100 }).then((page) => page.content);
  },
  staffAccountPage(filters: { query?: string; page?: number; size?: number } = {}) {
    const params = new URLSearchParams({
      page: String(filters.page ?? 0),
      size: String(Math.max(25, Math.min(filters.size ?? 50, 100))),
    });
    if (filters.query?.trim()) params.set("query", filters.query.trim());
    return requestSpringPage<StaffAccount>(`/admin/staff-accounts?${params}`, { cache: "no-store" });
  },
  createStaffAccount(email: string, temporaryPassword: string, role: string) {
    return apiRequest<StaffAccount>("/admin/staff-accounts", {
      method: "POST",
      body: JSON.stringify({ email, temporaryPassword, role }),
    });
  },
  checkIn(appointmentId: string) {
    return apiRequest<{ id: string; appointmentId: string; visitorName: string; badgeNumber: string;
      checkedInAt: string; checkedOutAt: string | null; processedBy: string }>(`/reception/appointments/${appointmentId}/check-in`, {
      method: "POST",
    });
  },
  checkInByReference(referenceNumber: string) {
    return apiRequest<{ id: string; appointmentId: string; visitorName: string; badgeNumber: string;
      checkedInAt: string; checkedOutAt: string | null; processedBy: string }>(
        `/reception/appointments/reference/${encodeURIComponent(referenceNumber.toUpperCase())}/check-in`, { method: "POST" });
  },
  visitorsInside() {
    return apiRequest<Array<{ id: string; appointmentId: string; visitorName: string; badgeNumber: string;
      checkedInAt: string; checkedOutAt: string | null; processedBy: string }>>("/reception/visitors-inside");
  },
  checkOut(accessRecordId: string) {
    return apiRequest(`/reception/access-records/${accessRecordId}/check-out`, { method: "POST" });
  },
  auditEvents(filters: { from?: string; to?: string; outcome?: string; query?: string;
    cursor?: string; size?: number } = {}) {
    const params = new URLSearchParams({ size: String(Math.max(25, Math.min(filters.size ?? 50, 100))) });
    Object.entries(filters).forEach(([key, value]) => {
      if (key !== "size" && value) params.set(key, String(value));
    });
    return apiRequest<CountedCursorPage<{ id: string; occurredAt: string; actorId: string; eventType: string;
      targetType: string; targetId: string; outcome: string; correlationId: string | null }>>(
        `/audit-events?${params}`, { cache: "no-store" });
  },
  essentialLogs(filters: { from?: string; to?: string; category?: string; status?: string;
    query?: string; cursor?: string; size?: number } = {}) {
    const params = new URLSearchParams({ size: String(Math.max(25, Math.min(filters.size ?? 50, 100))) });
    Object.entries(filters).forEach(([key, value]) => {
      if (key !== "size" && value && value !== "All") params.set(key, String(value));
    });
    return apiRequest<CountedCursorPage<EssentialLogRecord>>(`/logs?${params}`, { cache: "no-store" });
  },
  changeStaffEmail(userId: string, email: string) {
    return apiRequest(`/admin/staff-accounts/${userId}/email`, {
      method: "PATCH",
      body: JSON.stringify({ email }),
    });
  },
  resetStaffPassword(userId: string, temporaryPassword: string) {
    return apiRequest(`/admin/staff-accounts/${userId}/reset-password`, {
      method: "POST",
      body: JSON.stringify({ temporaryPassword }),
    });
  },
  setStaffEnabled(userId: string, enabled: boolean) {
    return apiRequest(`/admin/staff-accounts/${userId}/status`, {
      method: "PATCH",
      body: JSON.stringify({ enabled }),
    });
  },
  changeMyEmail(currentPassword: string, newEmail: string) {
    return apiRequest<void>("/auth/change-email", {
      method: "POST",
      body: JSON.stringify({ currentPassword, newEmail }),
    });
  },
  permissionOverrides(userId: string, grants: string[], denies: string[]) {
    return apiRequest<{ userId: string; grantedOverrides: string[]; deniedOverrides: string[]; effectivePermissions: string[] }>(`/admin/users/${userId}/permissions`, {
      method: "PUT",
      body: JSON.stringify({ grants, denies }),
    });
  },
  roleDefinitions() {
    return apiRequest<RoleDefinition[]>("/admin/roles");
  },
  workspaceSettings() {
    return apiRequest<WorkspaceSetting[]>("/workspace-settings");
  },
  updateWorkspaceSetting(key: string, value: string) {
    return apiRequest<WorkspaceSetting>(`/workspace-settings/${encodeURIComponent(key)}`, {
      method: "PUT", body: JSON.stringify({ value }),
    });
  },
  systemSettings() {
    return apiRequest<WorkspaceSetting[]>("/system-settings");
  },
  updateSystemSetting(key: string, value: string) {
    return apiRequest<WorkspaceSetting>(`/system-settings/${encodeURIComponent(key)}`, {
      method: "PUT", body: JSON.stringify({ value }),
    });
  },
  integrationHealth() {
    return apiRequest<IntegrationOverview>("/admin/integrations", { cache: "no-store" });
  },
  currentCompensation(employeeId: string) {
    return apiRequest<CompensationRecord>(`/employees/${employeeId}/compensation/current`, { cache: "no-store" });
  },
  compensationHistory(employeeId: string) {
    return apiRequest<CompensationRecord[]>(`/employees/${employeeId}/compensation/history`, { cache: "no-store" });
  },
  createCompensation(employeeId: string, payload: {
    components: { basicSalary: number; hra: number; transportAllowance: number; medicalAllowance: number;
      specialAllowance: number; otherAllowance: number; providentFundDeduction: number;
      professionalTax: number; incomeTaxEstimate: number; otherDeductions: number };
    currency: string; effectiveFrom: string; effectiveTo: string | null;
  }) {
    return apiRequest<CompensationRecord>(`/employees/${employeeId}/compensation`, {
      method: "POST", body: JSON.stringify(payload),
    });
  },
  employeeDocuments(employeeId: string) {
    return apiRequest<EmployeeDocument[]>(
        `/documents?ownerType=EMPLOYEE&ownerId=${encodeURIComponent(employeeId)}`, { cache: "no-store" });
  },
  uploadEmployeeDocument(employeeId: string, category: EmployeeDocument["category"], file: File) {
    const body = new FormData();
    body.append("ownerType", "EMPLOYEE");
    body.append("ownerId", employeeId);
    body.append("category", category);
    body.append("file", file);
    return apiRequest<EmployeeDocument>("/documents", { method: "POST", body });
  },
  employeeDocumentDownload(documentId: string) {
    return apiRequest<{ url: string; expiresAt: string }>(`/documents/${documentId}/download-url`);
  },
  deleteEmployeeDocument(documentId: string) {
    return apiRequest<void>(`/documents/${documentId}`, { method: "DELETE" });
  },
  internalNotificationRecipients() {
    return apiRequest<InternalNotificationRecipient[]>("/internal-notifications/recipients");
  },
  internalNotificationInbox() {
    return apiRequest<InternalNotification[]>("/internal-notifications/inbox");
  },
  internalNotificationSent() {
    return apiRequest<InternalNotification[]>("/internal-notifications/sent");
  },
  internalNotificationArchive(page = 0, size = 50) {
    return apiRequest<InternalNotification[]>(`/internal-notifications/archive?page=${page}&size=${size}`);
  },
  deleteInternalNotification(notificationId: string) {
    return apiRequest<void>(`/internal-notifications/${notificationId}`, { method: "DELETE" });
  },
  internalNotificationUnreadCount() {
    return apiRequest<{ unreadCount: number }>("/internal-notifications/unread-count");
  },
  sendInternalNotification(recipientUserId: string, message: string,
                           priority: InternalNotification["priority"] = "NORMAL",
                           category: InternalNotification["category"] = "GENERAL") {
    return apiRequest<InternalNotification>("/internal-notifications", {
      method: "POST", body: JSON.stringify({ recipientUserId, message, priority, category }),
    });
  },
  markInternalNotificationRead(notificationId: string) {
    return apiRequest<InternalNotification>(`/internal-notifications/${notificationId}/read`, { method: "POST" });
  },
  createLeaveRequest(startDate: string, endDate: string, reason: string) {
    return apiRequest<LeaveRequest>("/leave-requests", { method: "POST", body: JSON.stringify({ startDate, endDate, reason }) });
  },
  myLeaveRequests() { return apiRequest<LeaveRequest[]>("/leave-requests/me"); },
  pendingLeaveRequests() { return apiRequest<LeaveRequest[]>("/leave-requests/pending"); },
  decideLeaveRequest(id: string, decision: "approve" | "reject", remarks = "") {
    return apiRequest<LeaveRequest>(`/leave-requests/${id}/${decision}`, { method: "POST", body: JSON.stringify({ remarks }) });
  },
  resourceDiscussions() { return apiRequest<ResourceDiscussion[]>("/resource-discussions"); },
  createResourceDiscussion(payload: { hrRecipientUserId: string; projectName: string; requiredRoles: string;
    requestedHeadcount: number; priority: string; preferredAt: string; justification: string }) {
    return apiRequest<ResourceDiscussion>("/resource-discussions", {
      method: "POST", body: JSON.stringify(payload),
    });
  },
  decideResourceDiscussion(id: string, action: "SCHEDULE" | "REQUEST_INFORMATION" | "DECLINE",
                           response: string, scheduledAt: string | null) {
    return apiRequest<ResourceDiscussion>(`/resource-discussions/${id}/hr-action`, {
      method: "POST", body: JSON.stringify({ action, response, scheduledAt }),
    });
  },
  reviseResourceDiscussion(id: string, payload: { requiredRoles: string; requestedHeadcount: number;
    preferredAt: string; justification: string }) {
    return apiRequest<ResourceDiscussion>(`/resource-discussions/${id}/revise`, {
      method: "POST", body: JSON.stringify(payload),
    });
  },
  completeResourceDiscussion(id: string) {
    return apiRequest<ResourceDiscussion>(`/resource-discussions/${id}/complete`, { method: "POST" });
  },
  workTasks() { return apiRequest<WorkTask[]>("/work-tasks"); },
  workTaskWorkspace() { return apiRequest<WorkTaskWorkspace>("/work-tasks/workspace"); },
  createWorkTask(payload: { employeeId: string; title: string; description: string; dueDate: string }) {
    return apiRequest<WorkTask>("/work-tasks", { method: "POST", body: JSON.stringify(payload) });
  },
  updateWorkTask(id: string, action: "start" | "complete" | "approve" | "request-changes", note = "") {
    return apiRequest<WorkTask>(`/work-tasks/${id}/${action}`, {
      method: "POST", body: JSON.stringify({ note }),
    });
  },
  reviseEmployeeWorkTaskRework(id: string, update: string) {
    return apiRequest<WorkTask>(`/work-tasks/${id}/revise-rework`, {
      method: "POST", body: JSON.stringify({ note: update }),
    });
  },
  acknowledgeWorkTask(id: string) {
    return apiRequest<WorkTask>(`/work-tasks/${id}/acknowledge`, { method: "POST" });
  },
  teamLeadPerformance() { return apiRequest<TeamLeadPerformance[]>("/work-tasks/performance"); },
  workInsights(weekStart: string) {
    return apiRequest<WorkInsight[]>(`/work-insights?weekStart=${encodeURIComponent(weekStart)}`);
  },
  pendingHrWorkInsights() {
    return apiRequest<WorkInsight[]>("/work-insights/pending-hr-audit");
  },
  workTaskWorkflowStates() {
    return apiRequest<WorkTaskWorkflowState[]>("/work-insights/task-workflow-states");
  },
  auditWorkInsight(taskId: string) {
    return apiRequest<WorkInsight>(`/work-insights/tasks/${taskId}/audit`, { method: "POST" });
  },
  requestWorkInsightRework(taskId: string, reason: string) {
    return apiRequest<WorkInsight>(`/work-insights/tasks/${taskId}/request-rework`, {
      method: "POST", body: JSON.stringify({ reason }),
    });
  },
  assignWorkInsightRework(taskId: string, guidance: string) {
    return apiRequest<WorkInsight>(`/work-insights/tasks/${taskId}/assign-rework`, {
      method: "POST", body: JSON.stringify({ guidance }),
    });
  },
  reviseWorkInsightRework(taskId: string, update: string) {
    return apiRequest<WorkInsight>(`/work-insights/tasks/${taskId}/revise-rework`, {
      method: "POST", body: JSON.stringify({ update }),
    });
  },
  decideWorkInsight(recordId: string, approved: boolean, remarks = "") {
    return apiRequest<WorkInsight>(`/work-insights/${recordId}/ceo-decision`, {
      method: "POST", body: JSON.stringify({ approved, remarks }),
    });
  },
  decideManagerWorkInsight(recordId: string, approved: boolean, remarks = "") {
    return apiRequest<WorkInsight>(`/work-insights/${recordId}/manager-decision`, {
      method: "POST", body: JSON.stringify({ approved, remarks }),
    });
  },
  monthlyRecords(year: number, month: number) {
    return apiRequest<MonthlyRecords>(`/admin/records/monthly?year=${year}&month=${month}`);
  },
  hrLifecycleAccounts() { return apiRequest<HrLifecycleAccount[]>("/governance/hr-accounts"); },
  verifyVisitorPass(token: string) {
    return apiRequest<{ appointmentId: string; referenceNumber: string; visitorName: string; visitorCompany: string | null;
      appointmentStatus: string; slotStart: string; slotEnd: string; validUntil: string }>("/reception/passes/verify", {
      method: "POST", body: JSON.stringify({ token }),
    });
  },
  checkInWithVisitorPass(token: string) {
    return apiRequest<{ id: string; appointmentId: string; visitorName: string; badgeNumber: string;
      checkedInAt: string; checkedOutAt: string | null; processedBy: string }>("/reception/passes/check-in", {
      method: "POST", body: JSON.stringify({ token }),
    });
  },
};
