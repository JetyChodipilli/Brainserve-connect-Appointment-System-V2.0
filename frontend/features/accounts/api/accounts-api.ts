import { apiRequest, requestSpringPage } from "../../../lib/api-client";
import type { ProvisioningAccount, CeoSlot, HrAccountApprovalInput, AccountClosureRequest, AccountLifecycleAccount, AccountClosureCandidate, ArchivedAccount, AccountLifecycleRecord, DirectArchiveChallenge, ArchivedRecoveryChallenge, RecoveredAccount, AccountRecoveryRequest, StaffAccount, HrLifecycleAccount } from "../../../types/api";

export const accountsApi = {
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
permissionOverrides(userId: string, grants: string[], denies: string[]) {
    return apiRequest<{ userId: string; grantedOverrides: string[]; deniedOverrides: string[]; effectivePermissions: string[] }>(`/admin/users/${userId}/permissions`, {
      method: "PUT",
      body: JSON.stringify({ grants, denies }),
    });
  },
hrLifecycleAccounts() { return apiRequest<HrLifecycleAccount[]>("/governance/hr-accounts"); },
};
