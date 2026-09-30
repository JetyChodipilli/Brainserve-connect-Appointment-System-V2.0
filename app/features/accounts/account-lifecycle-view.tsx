"use client";

import {
    type AccountClosureCandidate,
    type AccountClosureRequest,
    type AccountLifecycleAccount,
    type AccountLifecycleRecord,
    ApiError,
    type ArchivedAccount,
    type ArchivedRecoveryChallenge,
    brainServeApi,
    type DirectArchiveChallenge,
    type EssentialLogRecord,
    isBackendConfigured,
    type StaffAccount,
} from "../../lib/api";
import { officeToday } from "../../lib/appointments";
import { readDemoAccounts, verifyPreviewSystemAdminPassword, writeDemoAccounts } from "../../preview/accounts";
import {
    readDemoDepartmentHrAssignments,
    readDemoEmployees,
    readDemoTeamLeadAssignments,
    writeDemoDepartmentHrAssignments,
    writeDemoEmployees,
    writeDemoTeamLeadAssignments,
} from "../../preview/directory";
import { DEMO_SYSTEM_ADMIN } from "../../preview/fixtures/accounts";
import {
    clearPreviewArchivePasswordFailures,
    previewArchivePasswordFailure,
    readDemoAccountClosures,
    readDemoAccountLifecycle,
    readDemoArchivedAccounts,
    readDemoEssentialLogs,
    readPreviewArchivedRecoveryChallenge,
    readPreviewDirectArchiveChallenge,
    recordDemoClosureTransition,
    writeDemoAccountClosures,
    writeDemoAccountLifecycle,
    writeDemoArchivedAccounts,
    writeDemoEssentialLogs,
    writePreviewArchivedRecoveryChallenge,
    writePreviewDirectArchiveChallenge,
} from "../../preview/governance";
import { readDemoManagerAssignments, writeDemoManagerAssignments } from "../../preview/manager-assignments";
import { previewOtpIsValid } from "../../preview/recovery";
import { DEMO_ACCOUNTS_KEY } from "../../preview/storage-keys";
import { PageTitle } from "../../shared/components/page-title";
import { SYSTEM_ADMIN_EMAIL } from "../../shared/config/identity";
import { type Department, type Employee, type Role } from "../../shared/types/workspace";
import { fail } from "../../shared/utils/errors";
import { newClientId } from "../../shared/utils/ids";
import { visitorInitials } from "../appointments/appointment-utils";
import {
    Archive,
    BadgeCheck,
    CheckCircle2,
    FileClock,
    LockKeyhole,
    Mail,
    MoreHorizontal,
    RotateCcw,
    Search,
    ShieldCheck,
    Users,
    X,
} from "lucide-react";
import { type FormEvent, useCallback, useEffect, useRef, useState } from "react";

export function AccountLifecycleView({ role, userEmail, departments, employees }: {
    role: Role; userEmail: string; staffAccounts: StaffAccount[]; departments: Department[]; employees: Employee[];
}) {
    const [tab, setTab] = useState<"pending" | "active" | "archived">("pending");
    const [requests, setRequests] = useState<AccountClosureRequest[]>(() => !isBackendConfigured ? readDemoAccountClosures() : []);
    const [accounts, setAccounts] = useState<AccountLifecycleAccount[]>([]);
    const [archived, setArchived] = useState<ArchivedAccount[]>(() => !isBackendConfigured ? readDemoArchivedAccounts() : []);
    const [accountQuery, setAccountQuery] = useState("");
    const [accountRole, setAccountRole] = useState("ALL");
    const [accountDepartmentId, setAccountDepartmentId] = useState("");
    const [accountPage, setAccountPage] = useState(0);
    const [accountPageCount, setAccountPageCount] = useState(1);
    const [accountTotal, setAccountTotal] = useState(0);
    const [accountPageBusy, setAccountPageBusy] = useState(false);
    const [archivedQuery, setArchivedQuery] = useState("");
    const [archivedPage, setArchivedPage] = useState(0);
    const [archivedPageCount, setArchivedPageCount] = useState(1);
    const [archivedTotal, setArchivedTotal] = useState(0);
    const [archivedPageBusy, setArchivedPageBusy] = useState(false);
    const [replacements, setReplacements] = useState<Record<string, string>>({});
    const [candidateMap, setCandidateMap] = useState<Record<string, AccountClosureCandidate[]>>({});
    const [notes, setNotes] = useState<Record<string, string>>({});
    const [busyId, setBusyId] = useState("");
    const [message, setMessage] = useState("");
    const [error, setError] = useState("");
    const [directTargetId, setDirectTargetId] = useState("");
    const [archiveChallenge, setArchiveChallenge] = useState<DirectArchiveChallenge | null>(
        () => !isBackendConfigured ? readPreviewDirectArchiveChallenge() : null);
    const [archivePanelMinimized, setArchivePanelMinimized] = useState(false);
    const [recoveryTargetId, setRecoveryTargetId] = useState("");
    const [recoveryRole, setRecoveryRole] = useState("ROLE_MANAGER");
    const [recoveryDepartmentId, setRecoveryDepartmentId] = useState("");
    const [recoveryChallenge, setRecoveryChallenge] = useState<ArchivedRecoveryChallenge | null>(
        () => !isBackendConfigured ? readPreviewArchivedRecoveryChallenge() : null);
    const [recoveryPanelMinimized, setRecoveryPanelMinimized] = useState(false);
    const [challengeClock, setChallengeClock] = useState(() => Date.now());
    const [history, setHistory] = useState<AccountLifecycleRecord[]>([]);
    const [historyRequestId, setHistoryRequestId] = useState("");
    const directArchivePanelRef = useRef<HTMLElement>(null);
    const recoveryPanelRef = useRef<HTMLElement>(null);
    const accountPageSize = 25;

    const demoAccounts = useCallback((): AccountLifecycleAccount[] => {
        const currentEmployees = isBackendConfigured ? employees : readDemoEmployees();
        return readDemoAccounts()
            .filter((item) => item.status === "ACTIVE")
            .map((item) => {
                const employee = currentEmployees.find((value) => value.email.toLowerCase() === item.email.toLowerCase()
                    || Boolean(item.employeeId && (value.uuid ?? value.id) === item.employeeId));
                const department = departments.find((value) => value.id === employee?.departmentId)
                    ?? (item.role === "ROLE_HR_ADMIN" ? departments.find((value) => readDemoDepartmentHrAssignments()
                        .some((assignment) => assignment.active && assignment.departmentId === value.id && assignment.hrUserId === item.id)) : undefined)
                    ?? (item.role === "ROLE_MANAGER" ? departments.find((value) => readDemoManagerAssignments()
                        .some((assignment) => assignment.active && assignment.departmentId === value.id && assignment.managerUserId === item.id)) : undefined)
                    ?? (item.role === "ROLE_TEAM_LEAD" ? departments.find((value) => readDemoTeamLeadAssignments()
                        .some((assignment) => assignment.active && assignment.departmentId === value.id && assignment.teamLeadUserId === item.id)) : undefined);
                return { userId: item.id, fullName: item.fullName, email: item.email, role: item.role,
                    status: item.status, enabled: item.status === "ACTIVE", archived: false,
                    employeeId: item.employeeId ?? employee?.uuid ?? null, departmentId: department?.id ?? null,
                    departmentName: department?.name ?? null,
                    protectedAccount: ["ROLE_SYSTEM_ADMIN", "ROLE_CEO"].includes(item.role) };
            });
    }, [departments, employees]);

    const load = useCallback(async () => {
        setError("");
        try {
            if (isBackendConfigured) {
                if (role === "System Admin") {
                    const allRequests = await brainServeApi.accountClosureRequests();
                    setRequests(allRequests);
                    const candidateEntries = await Promise.all(allRequests.filter((item) => ["REQUESTED", "PENDING_SYSTEM_ADMIN"].includes(item.status))
                        .map(async (item) => [item.targetUserId, await brainServeApi.accountClosureCandidates(item.targetUserId)] as const));
                    setCandidateMap(Object.fromEntries(candidateEntries));
                } else {
                    const pending = await brainServeApi.businessPendingAccountClosures();
                    setRequests(pending);
                    const candidateEntries = await Promise.all(pending.map(async (item) =>
                        [item.targetUserId, await brainServeApi.accountClosureCandidates(item.targetUserId)] as const));
                    setCandidateMap(Object.fromEntries(candidateEntries));
                }
            } else {
                const all = readDemoAccountClosures();
                setRequests(role === "System Admin" ? all : all.filter((item) => item.status === "REQUESTED"
                    && (role === "CEO" ? ["ROLE_HR_ADMIN", "ROLE_MANAGER"].includes(item.targetRole)
                        : ["ROLE_TEAM_LEAD", "ROLE_RECEPTIONIST", "ROLE_SECURITY"].includes(item.targetRole))));
            }
        } catch (reason) { setError(reason instanceof Error ? reason.message : "Account lifecycle records could not be loaded."); }
    }, [role]);

    const loadAccountPage = useCallback(async () => {
        if (role !== "System Admin") return;
        if (accountRole === "ROLE_EMPLOYEE" && !accountDepartmentId) {
            setAccounts([]);
            setAccountPageCount(1);
            setAccountTotal(0);
            setAccountPageBusy(false);
            return;
        }
        setAccountPageBusy(true);
        try {
            if (isBackendConfigured) {
                const result = await brainServeApi.accountLifecycleAccountPage({
                    query: accountQuery, role: accountRole, departmentId: accountDepartmentId || undefined,
                    page: accountPage, size: accountPageSize,
                });
                const pageCount = Math.max(1, result.totalPages ?? 1);
                if (accountPage >= pageCount && accountPage > 0) {
                    setAccountPage(pageCount - 1);
                    return;
                }
                setAccounts(result.content);
                setAccountPageCount(pageCount);
                setAccountTotal(result.totalElements ?? result.content.length);
            } else {
                const query = accountQuery.trim().toLowerCase();
                const filtered = demoAccounts().filter((account) =>
                    (!query || `${account.fullName} ${account.email}`.toLowerCase().includes(query))
                    && (accountRole === "ALL" || account.role === accountRole)
                    && (!accountDepartmentId || account.departmentId === accountDepartmentId));
                const pageCount = Math.max(1, Math.ceil(filtered.length / accountPageSize));
                if (accountPage >= pageCount && accountPage > 0) {
                    setAccountPage(pageCount - 1);
                    return;
                }
                setAccounts(filtered.slice(accountPage * accountPageSize, (accountPage + 1) * accountPageSize));
                setAccountPageCount(pageCount);
                setAccountTotal(filtered.length);
            }
        } catch (reason) {
            setError(reason instanceof Error ? reason.message : "Operational accounts could not be loaded.");
        } finally { setAccountPageBusy(false); }
    }, [accountDepartmentId, accountPage, accountPageSize, accountQuery, accountRole, demoAccounts, role]);

    const loadArchivedPage = useCallback(async () => {
        if (role !== "System Admin") return;
        setArchivedPageBusy(true);
        try {
            if (isBackendConfigured) {
                const result = await brainServeApi.archivedAccountPage({
                    query: archivedQuery, page: archivedPage, size: accountPageSize,
                });
                const pageCount = Math.max(1, result.totalPages ?? 1);
                if (archivedPage >= pageCount && archivedPage > 0) {
                    setArchivedPage(pageCount - 1);
                    return;
                }
                setArchived(result.content);
                setArchivedPageCount(pageCount);
                setArchivedTotal(result.totalElements ?? result.content.length);
            } else {
                const query = archivedQuery.trim().toLowerCase();
                const filtered = readDemoArchivedAccounts().filter((account) =>
                    !query || `${account.fullName} ${account.email} ${account.role} ${account.departmentName ?? ""}`
                        .toLowerCase().includes(query));
                const pageCount = Math.max(1, Math.ceil(filtered.length / accountPageSize));
                if (archivedPage >= pageCount && archivedPage > 0) {
                    setArchivedPage(pageCount - 1);
                    return;
                }
                setArchived(filtered.slice(archivedPage * accountPageSize, (archivedPage + 1) * accountPageSize));
                setArchivedPageCount(pageCount);
                setArchivedTotal(filtered.length);
            }
        } catch (reason) {
            setError(reason instanceof Error ? reason.message : "Archived accounts could not be loaded.");
        } finally { setArchivedPageBusy(false); }
    }, [accountPageSize, archivedPage, archivedQuery, role]);

    useEffect(() => { Promise.resolve().then(load); }, [load]);
    useEffect(() => {
        const timer = window.setTimeout(() => { void loadAccountPage(); }, 250);
        return () => window.clearTimeout(timer);
    }, [loadAccountPage]);
    useEffect(() => {
        if (role !== "System Admin" || isBackendConfigured) return;
        const refreshDirectory = () => { void loadAccountPage(); };
        const refreshFromStorage = (event: StorageEvent) => {
            if (!event.key || event.key === DEMO_ACCOUNTS_KEY) refreshDirectory();
        };
        window.addEventListener("brainserve:demo-accounts-updated", refreshDirectory);
        window.addEventListener("storage", refreshFromStorage);
        window.addEventListener("focus", refreshDirectory);
        return () => {
            window.removeEventListener("brainserve:demo-accounts-updated", refreshDirectory);
            window.removeEventListener("storage", refreshFromStorage);
            window.removeEventListener("focus", refreshDirectory);
        };
    }, [loadAccountPage, role]);
    useEffect(() => {
        const timer = window.setTimeout(() => { void loadArchivedPage(); }, 250);
        return () => window.clearTimeout(timer);
    }, [loadArchivedPage]);
    useEffect(() => {
        if (role !== "System Admin" || !isBackendConfigured) return;
        let active = true;
        void brainServeApi.activeDirectArchiveChallenge()
            .then((challenge) => {
                if (!active || !challenge) return;
                setArchiveChallenge(challenge);
                setDirectTargetId(challenge.targetUserId);
                setArchivePanelMinimized(true);
            })
            .catch((reason) => {
                if (active && !(reason instanceof ApiError && reason.status === 404)) {
                    setError(reason instanceof Error ? reason.message : "Archive verification could not be restored.");
                }
            });
        return () => { active = false; };
    }, [role]);
    useEffect(() => {
        if (role !== "System Admin" || !isBackendConfigured) return;
        let active = true;
        void brainServeApi.activeArchivedRecoveryChallenge()
            .then((challenge) => {
                if (!active || !challenge) return;
                setRecoveryChallenge(challenge);
                setRecoveryTargetId(challenge.archivedAccountId);
                setRecoveryRole(challenge.targetRole);
                setRecoveryDepartmentId(challenge.targetDepartmentId ?? "");
                setRecoveryPanelMinimized(true);
            })
            .catch((reason) => {
                if (active && !(reason instanceof ApiError && reason.status === 404)) {
                    setError(reason instanceof Error ? reason.message : "Account recovery verification could not be restored.");
                }
            });
        return () => { active = false; };
    }, [role]);
    useEffect(() => {
        if (!archiveChallenge) return;
        const timer = window.setInterval(() => {
            const now = Date.now();
            setChallengeClock(now);
            if (now >= Date.parse(archiveChallenge.expiresAt)) {
                if (!isBackendConfigured) writePreviewDirectArchiveChallenge(null);
                setArchiveChallenge(null);
                setDirectTargetId("");
                setArchivePanelMinimized(false);
                setError("The account archive confirmation code expired. Start the verification again.");
            }
        }, 1000);
        return () => window.clearInterval(timer);
    }, [archiveChallenge]);
    useEffect(() => {
        if (!recoveryChallenge) return;
        const timer = window.setInterval(() => {
            const now = Date.now();
            setChallengeClock(now);
            if (now >= Date.parse(recoveryChallenge.expiresAt)) {
                if (!isBackendConfigured) writePreviewArchivedRecoveryChallenge(null);
                setRecoveryChallenge(null);
                setRecoveryTargetId("");
                setRecoveryPanelMinimized(false);
                setError("The account recovery code expired. Start the verification again.");
            }
        }, 1000);
        return () => window.clearInterval(timer);
    }, [recoveryChallenge]);
    useEffect(() => {
        if ((!directTargetId && !archiveChallenge) || archivePanelMinimized) return;
        window.requestAnimationFrame(() => {
            directArchivePanelRef.current?.scrollIntoView({ behavior: "smooth", block: "start" });
            directArchivePanelRef.current?.querySelector<HTMLElement>("textarea, input, button")?.focus();
        });
    }, [archiveChallenge, archivePanelMinimized, directTargetId]);
    useEffect(() => {
        if ((!recoveryTargetId && !recoveryChallenge) || recoveryPanelMinimized) return;
        window.requestAnimationFrame(() => {
            recoveryPanelRef.current?.scrollIntoView({ behavior: "smooth", block: "start" });
            recoveryPanelRef.current?.querySelector<HTMLElement>("textarea, select, input, button")?.focus();
        });
    }, [recoveryChallenge, recoveryPanelMinimized, recoveryTargetId]);

    const candidatesFor = (target: AccountClosureRequest | AccountLifecycleAccount) => {
        const targetId = "targetUserId" in target ? target.targetUserId : target.userId;
        if (isBackendConfigured && candidateMap[targetId]) return candidateMap[targetId];
        const all = isBackendConfigured ? accounts : demoAccounts();
        const targetRole = "targetRole" in target ? target.targetRole : target.role;
        if (targetRole === "ROLE_TEAM_LEAD") {
            const departmentId = target.departmentId;
            return all.filter((item) => item.enabled && item.role === "ROLE_EMPLOYEE" && item.departmentId === departmentId
                && item.userId !== ("targetUserId" in target ? target.targetUserId : target.userId));
        }
        return all.filter((item) => item.enabled && item.role === targetRole && item.userId !== targetId)
            .filter((item) => isBackendConfigured || targetRole !== "ROLE_HR_ADMIN" || !readDemoDepartmentHrAssignments()
                .some((assignment) => assignment.active && assignment.hrUserId === item.userId));
    };

    const openDirectAccount = async (account: AccountLifecycleAccount) => {
        setError(""); setMessage("");
        if (recoveryChallenge) {
            setError(`Finish or cancel the active recovery verification for ${recoveryChallenge.targetName} first.`);
            return;
        }
        if (archiveChallenge) {
            if (archiveChallenge.targetUserId !== account.userId) {
                setError(`Finish or cancel the active archive verification for ${archiveChallenge.targetName} first.`);
                return;
            }
            setDirectTargetId(account.userId);
            setArchivePanelMinimized(false);
            return;
        }
        setDirectTargetId(account.userId);
        setArchivePanelMinimized(false);
        if (!isBackendConfigured) return;
        try {
            const candidates = await brainServeApi.accountClosureCandidates(account.userId);
            setCandidateMap((items) => ({ ...items, [account.userId]: candidates }));
        } catch (reason) {
            setDirectTargetId("");
            setError(reason instanceof Error ? reason.message : "Replacement candidates could not be loaded.");
        }
    };

    const businessDecision = async (request: AccountClosureRequest, decision: "approve" | "reject") => {
        setBusyId(request.id); setError(""); setMessage("");
        const replacementUserId = replacements[request.id] || request.replacementUserId || null;
        const note = notes[request.id]?.trim() || (decision === "approve" ? "Business responsibilities reviewed" : "Closure request rejected by business owner");
        try {
            let updated: AccountClosureRequest;
            if (isBackendConfigured) updated = await brainServeApi.decideBusinessAccountClosure(request.id, decision, replacementUserId, note);
            else {
                if (decision === "approve" && !["ROLE_RECEPTIONIST", "ROLE_SECURITY"].includes(request.targetRole) && !replacementUserId) {
                    fail("Select an active replacement before approval.");
                }
                const now = new Date().toISOString();
                updated = decision === "approve" ? { ...request, replacementUserId,
                        replacementName: candidatesFor(request).find((item) => item.userId === replacementUserId)?.fullName ?? request.replacementName,
                        status: "PENDING_SYSTEM_ADMIN", businessApproverUserId: userEmail,
                        businessApprovedAt: now, decisionNote: note }
                    : { ...request, status: "REJECTED", businessApproverUserId: userEmail,
                        businessApprovedAt: null, decisionNote: note };
                writeDemoAccountClosures(readDemoAccountClosures().map((item) => item.id === updated.id ? updated : item));
                if (decision === "approve") {
                    recordDemoClosureTransition({ ...updated, status: "BUSINESS_APPROVED" }, "ACCOUNT_CLOSURE_BUSINESS_APPROVED",
                        request.status, userEmail, "Business owner approved account closure");
                    recordDemoClosureTransition(updated, "ACCOUNT_CLOSURE_PENDING_SYSTEM_ADMIN", "BUSINESS_APPROVED",
                        userEmail, "Account closure forwarded to System Admin");
                } else recordDemoClosureTransition(updated, "ACCOUNT_CLOSURE_REJECTED", request.status, userEmail,
                    "Business owner rejected account closure");
            }
            setRequests((items) => items.filter((item) => item.id !== request.id));
            setMessage(decision === "approve" ? "Request forwarded to System Admin for final action." : "Closure request rejected.");
        } catch (reason) { setError(reason instanceof Error ? reason.message : "The lifecycle decision failed."); }
        finally { setBusyId(""); }
    };

    const archiveDemoAccount = (request: AccountClosureRequest, actor: string) => {
        const account = demoAccounts().find((item) => item.userId === request.targetUserId);
        if (!account) fail("The target account could not be found.");
        const archivedAt = new Date().toISOString();
        const snapshot: ArchivedAccount = { id: newClientId(), originalUserId: account.userId,
            fullName: account.fullName, email: account.email, role: account.role,
            departmentId: account.departmentId, departmentName: account.departmentName,
            employeeId: account.employeeId, employeeNumber: employees.find((item) => item.uuid === account.employeeId)?.id ?? null,
            previousStatus: account.status, reason: request.reason, closureRequestId: request.id,
            archivedByUserId: actor, archivedAt,
            retentionUntil: `${new Date().getFullYear() + 7}-${String(new Date().getMonth() + 1).padStart(2, "0")}-${String(new Date().getDate()).padStart(2, "0")}` };
        writeDemoArchivedAccounts([snapshot, ...readDemoArchivedAccounts()]);
        writeDemoAccounts(readDemoAccounts().map((item) => item.id === account.userId
            ? { ...item, status: "DISABLED" } : item));
        return { ...request, status: "ARCHIVED" as const, archivedAt };
    };

    const systemDecision = async (request: AccountClosureRequest, decision: "approve" | "reject") => {
        setBusyId(request.id); setError(""); setMessage("");
        const replacementUserId = replacements[request.id] || request.replacementUserId || null;
        const note = notes[request.id]?.trim() || (decision === "approve" ? "System Admin compliance review completed" : "System Admin rejected closure");
        try {
            let updated: AccountClosureRequest;
            if (isBackendConfigured) updated = await brainServeApi.decideSystemAdminAccountClosure(request.id, decision, replacementUserId, note);
            else {
                if (decision === "approve" && !["ROLE_RECEPTIONIST", "ROLE_SECURITY"].includes(request.targetRole) && !replacementUserId) {
                    fail("Select an active replacement before final archival.");
                }
                if (decision === "reject") updated = { ...request, status: "REJECTED", decisionNote: note,
                    systemAdminApproverUserId: SYSTEM_ADMIN_EMAIL, systemAdminApprovedAt: null };
                else if (request.requestedEffectiveDate > officeToday()) updated = { ...request, replacementUserId,
                    replacementName: candidatesFor(request).find((item) => item.userId === replacementUserId)?.fullName ?? request.replacementName,
                    status: "SCHEDULED", decisionNote: note, systemAdminApproverUserId: SYSTEM_ADMIN_EMAIL,
                    systemAdminApprovedAt: new Date().toISOString(), scheduledAt: new Date().toISOString() };
                else updated = archiveDemoAccount({ ...request, replacementUserId,
                        replacementName: candidatesFor(request).find((item) => item.userId === replacementUserId)?.fullName ?? request.replacementName,
                        systemAdminApproverUserId: SYSTEM_ADMIN_EMAIL, systemAdminApprovedAt: new Date().toISOString() }, SYSTEM_ADMIN_EMAIL);
                writeDemoAccountClosures(readDemoAccountClosures().map((item) => item.id === updated.id ? updated : item));
                recordDemoClosureTransition(updated, `ACCOUNT_CLOSURE_${updated.status}`, request.status, SYSTEM_ADMIN_EMAIL,
                    updated.status === "ARCHIVED" ? "Login disabled, sessions revoked and immutable snapshot retained"
                        : updated.status === "SCHEDULED" ? "System Admin approved and scheduled account archival" : "System Admin rejected account closure");
            }
            setMessage(updated.status === "ARCHIVED" ? "Account deactivated and archived. Historical records remain available."
                : updated.status === "SCHEDULED" ? "Account closure scheduled." : "Account closure rejected.");
            await Promise.all([load(), loadAccountPage(), loadArchivedPage()]);
        } catch (reason) { setError(reason instanceof Error ? reason.message : "The System Admin decision failed."); }
        finally { setBusyId(""); }
    };

    const showHistory = async (request: AccountClosureRequest) => {
        setHistoryRequestId(request.id);
        try { setHistory(isBackendConfigured ? await brainServeApi.accountClosureHistory(request.id)
            : readDemoAccountLifecycle().filter((item) => item.closureRequestId === request.id)
                .sort((a, b) => a.occurredAt.localeCompare(b.occurredAt))); }
        catch (reason) { setError(reason instanceof Error ? reason.message : "Lifecycle history could not be loaded."); }
    };

    const selectedDirectTarget = accounts.find((item) => item.userId === directTargetId);
    const directTarget: AccountLifecycleAccount | undefined = selectedDirectTarget ?? (archiveChallenge ? {
        userId: archiveChallenge.targetUserId,
        fullName: archiveChallenge.targetName,
        email: archiveChallenge.targetEmail,
        role: archiveChallenge.targetRole,
        status: "ACTIVE",
        enabled: true,
        archived: false,
        employeeId: null,
        departmentId: archiveChallenge.departmentId,
        departmentName: archiveChallenge.departmentName,
        protectedAccount: false,
    } : undefined);
    const archiveSecondsRemaining = archiveChallenge
        ? Math.max(0, Math.ceil((Date.parse(archiveChallenge.expiresAt) - challengeClock) / 1000)) : 0;
    const archiveResendSeconds = archiveChallenge
        ? Math.max(0, Math.ceil((Date.parse(archiveChallenge.resendAvailableAt) - challengeClock) / 1000)) : 0;
    const archiveTimeRemaining = `${Math.floor(archiveSecondsRemaining / 60)}:${String(
        archiveSecondsRemaining % 60).padStart(2, "0")}`;

    const directArchive = async (event: FormEvent<HTMLFormElement>) => {
        event.preventDefault(); if (!directTarget) return;
        const form = event.currentTarget; const data = new FormData(form); setBusyId("direct"); setError(""); setMessage("");
        try {
            if (!archiveChallenge) {
                const currentPassword = String(data.get("currentPassword") ?? "");
                const replacementUserId = String(data.get("replacementUserId") ?? "") || null;
                const reason = String(data.get("reason") ?? "").trim();
                let challenge: DirectArchiveChallenge;
                if (isBackendConfigured) {
                    challenge = await brainServeApi.requestDirectArchiveOtp(
                        directTarget.userId, currentPassword, reason, replacementUserId);
                } else {
                    if (!await verifyPreviewSystemAdminPassword(userEmail, currentPassword)) {
                        const attemptsRemaining = previewArchivePasswordFailure();
                        fail(attemptsRemaining === 0
                            ? "Too many incorrect password attempts. Try again later."
                            : `Current System Admin password is incorrect. ${attemptsRemaining} attempts remain.`);
                    }
                    clearPreviewArchivePasswordFailures();
                    const createdAt = new Date();
                    challenge = {
                        challengeId: newClientId(),
                        targetUserId: directTarget.userId,
                        targetName: directTarget.fullName,
                        targetEmail: directTarget.email,
                        targetRole: directTarget.role,
                        departmentId: directTarget.departmentId,
                        departmentName: directTarget.departmentName,
                        reason,
                        replacementUserId,
                        replacementName: candidatesFor(directTarget)
                            .find((item) => item.userId === replacementUserId)?.fullName ?? null,
                        createdAt: createdAt.toISOString(),
                        expiresAt: new Date(createdAt.getTime() + 10 * 60_000).toISOString(),
                        resendAvailableAt: new Date(createdAt.getTime() + 60_000).toISOString(),
                        attemptsRemaining: 5,
                    };
                    writePreviewDirectArchiveChallenge(challenge);
                }
                form.reset();
                setArchiveChallenge(challenge);
                setChallengeClock(Date.parse(challenge.createdAt));
                setMessage("System Admin password verified. A one-time confirmation code was sent; you can minimize this section while checking it.");
            } else {
                const otp = String(data.get("otp") ?? "");
                if (isBackendConfigured) await brainServeApi.directArchiveAccount(archiveChallenge.challengeId, otp);
                else if (!previewOtpIsValid(otp)) {
                    const attemptsRemaining = archiveChallenge.attemptsRemaining - 1;
                    if (attemptsRemaining <= 0) {
                        writePreviewDirectArchiveChallenge(null);
                        setArchiveChallenge(null); setDirectTargetId("");
                        fail("The verification was cancelled after too many incorrect codes.");
                    }
                    const updated = { ...archiveChallenge, attemptsRemaining };
                    writePreviewDirectArchiveChallenge(updated);
                    setArchiveChallenge(updated);
                    fail(`The confirmation code is incorrect. ${attemptsRemaining} attempts remain.`);
                } else {
                    const now = new Date().toISOString();
                    const request: AccountClosureRequest = { id: newClientId(), targetUserId: directTarget.userId,
                        targetName: directTarget.fullName, targetEmail: directTarget.email, targetRole: directTarget.role,
                        employeeId: directTarget.employeeId, departmentId: directTarget.departmentId,
                        departmentName: directTarget.departmentName, requesterUserId: SYSTEM_ADMIN_EMAIL,
                        origin: "SYSTEM_ADMIN_EMERGENCY", reason: archiveChallenge.reason,
                        requestedEffectiveDate: officeToday(), replacementUserId: archiveChallenge.replacementUserId,
                        replacementName: archiveChallenge.replacementName,
                        status: "REQUESTED", requestedAt: now, businessApproverUserId: null, businessApprovedAt: null,
                        systemAdminApproverUserId: SYSTEM_ADMIN_EMAIL, systemAdminApprovedAt: now, decisionNote: "Emergency direct archive",
                        scheduledAt: null, archivedAt: null, cancelledAt: null };
                    const archivedRequest = archiveDemoAccount(request, SYSTEM_ADMIN_EMAIL);
                    writeDemoAccountClosures([archivedRequest, ...readDemoAccountClosures()]);
                    recordDemoClosureTransition(archivedRequest, "ACCOUNT_CLOSURE_ARCHIVED", "REQUESTED", SYSTEM_ADMIN_EMAIL,
                        "Emergency archive confirmed by System Admin password and OTP; sessions revoked");
                }
                writePreviewDirectArchiveChallenge(null);
                setDirectTargetId(""); setArchiveChallenge(null); setArchivePanelMinimized(false); form.reset();
                setMessage("Account deactivated and archived. Historical records remain available.");
                await Promise.all([load(), loadAccountPage(), loadArchivedPage()]);
            }
        } catch (reasonValue) {
            const archiveErrorCode = reasonValue instanceof ApiError ? reasonValue.problem.errorCode : undefined;
            if (["ACCOUNT_ARCHIVE_OTP_ATTEMPTS_EXHAUSTED", "ACCOUNT_ARCHIVE_CHALLENGE_EXPIRED",
                "ACCOUNT_ARCHIVE_CHALLENGE_STALE"].includes(archiveErrorCode ?? "")) {
                writePreviewDirectArchiveChallenge(null);
                setArchiveChallenge(null); setDirectTargetId(""); setArchivePanelMinimized(false);
            } else if (isBackendConfigured && archiveErrorCode === "INVALID_OTP") {
                const refreshed = await brainServeApi.activeDirectArchiveChallenge().catch(() => undefined);
                if (refreshed) setArchiveChallenge(refreshed);
            }
            setError(reasonValue instanceof Error ? reasonValue.message : "Direct archival failed.");
        }
        finally { setBusyId(""); }
    };

    const resendDirectArchiveOtp = async () => {
        if (!archiveChallenge || archiveResendSeconds > 0) return;
        setBusyId("direct-resend"); setError(""); setMessage("");
        try {
            let challenge: DirectArchiveChallenge;
            if (isBackendConfigured) {
                challenge = await brainServeApi.resendDirectArchiveOtp(archiveChallenge.challengeId);
            } else {
                const now = Date.now();
                challenge = { ...archiveChallenge, expiresAt: new Date(now + 10 * 60_000).toISOString(),
                    resendAvailableAt: new Date(now + 60_000).toISOString(), attemptsRemaining: 5 };
                writePreviewDirectArchiveChallenge(challenge);
            }
            setArchiveChallenge(challenge);
            setChallengeClock(Date.parse(challenge.resendAvailableAt) - 60_000);
            setMessage("A new confirmation code was sent to the System Admin mailbox.");
        } catch (reasonValue) {
            setError(reasonValue instanceof Error ? reasonValue.message : "The confirmation code could not be resent.");
        } finally { setBusyId(""); }
    };

    const cancelDirectArchive = async () => {
        if (busyId) return;
        setBusyId("direct-cancel"); setError(""); setMessage("");
        try {
            if (archiveChallenge && isBackendConfigured) {
                await brainServeApi.cancelDirectArchiveChallenge(archiveChallenge.challengeId);
            }
            writePreviewDirectArchiveChallenge(null);
            setArchiveChallenge(null);
            setDirectTargetId("");
            setArchivePanelMinimized(false);
            setMessage(archiveChallenge ? "Archive verification cancelled. No account changes were made." : "");
        } catch (reasonValue) {
            if (reasonValue instanceof ApiError && [404, 410].includes(reasonValue.status)) {
                writePreviewDirectArchiveChallenge(null);
                setArchiveChallenge(null); setDirectTargetId(""); setArchivePanelMinimized(false);
                setMessage("The expired archive verification was cleared. No account changes were made.");
                return;
            }
            setError(reasonValue instanceof Error ? reasonValue.message : "Archive verification could not be cancelled.");
        } finally { setBusyId(""); }
    };

    const selectedRecoveryTarget = archived.find((item) => item.id === recoveryTargetId);
    const recoveryTarget: ArchivedAccount | undefined = selectedRecoveryTarget ?? (recoveryChallenge ? {
        id: recoveryChallenge.archivedAccountId,
        originalUserId: recoveryChallenge.targetUserId,
        fullName: recoveryChallenge.targetName,
        email: recoveryChallenge.targetEmail,
        role: recoveryChallenge.previousRole,
        departmentId: recoveryChallenge.previousDepartmentId,
        departmentName: recoveryChallenge.previousDepartmentName,
        employeeId: recoveryChallenge.employeeId,
        employeeNumber: null,
        previousStatus: "ACTIVE",
        reason: recoveryChallenge.reason,
        closureRequestId: "",
        archivedByUserId: "",
        archivedAt: recoveryChallenge.createdAt,
        retentionUntil: "",
    } : undefined);
    const recoveryNeedsDepartment = !["ROLE_RECEPTIONIST", "ROLE_SECURITY"].includes(
        recoveryChallenge?.targetRole ?? recoveryRole);
    const recoverySecondsRemaining = recoveryChallenge
        ? Math.max(0, Math.ceil((Date.parse(recoveryChallenge.expiresAt) - challengeClock) / 1000)) : 0;
    const recoveryResendSeconds = recoveryChallenge
        ? Math.max(0, Math.ceil((Date.parse(recoveryChallenge.resendAvailableAt) - challengeClock) / 1000)) : 0;
    const recoveryTimeRemaining = `${Math.floor(recoverySecondsRemaining / 60)}:${String(
        recoverySecondsRemaining % 60).padStart(2, "0")}`;

    const openArchivedRecovery = (account: ArchivedAccount) => {
        setError(""); setMessage("");
        if (archiveChallenge) {
            setError(`Finish or cancel the active archive verification for ${archiveChallenge.targetName} first.`);
            return;
        }
        if (recoveryChallenge) {
            if (recoveryChallenge.archivedAccountId !== account.id) {
                setError(`Finish or cancel the active recovery verification for ${recoveryChallenge.targetName} first.`);
                return;
            }
            setRecoveryTargetId(account.id);
            setRecoveryPanelMinimized(false);
            return;
        }
        const sameRole = ["ROLE_CEO", "ROLE_MANAGER", "ROLE_HR_ADMIN", "ROLE_TEAM_LEAD",
            "ROLE_EMPLOYEE", "ROLE_RECEPTIONIST", "ROLE_SECURITY"].includes(account.role)
            ? account.role : "ROLE_EMPLOYEE";
        const activeCeoExists = readDemoAccounts().some((item) => item.status === "ACTIVE"
            && item.role === "ROLE_CEO" && item.id !== account.originalUserId);
        const nextRole = sameRole === "ROLE_CEO" && activeCeoExists ? "ROLE_MANAGER" : sameRole;
        setRecoveryRole(nextRole);
        setRecoveryDepartmentId(["ROLE_RECEPTIONIST", "ROLE_SECURITY"].includes(nextRole)
            ? "" : account.departmentId ?? "");
        setRecoveryTargetId(account.id);
        setRecoveryPanelMinimized(false);
    };

    const validatePreviewRecovery = (account: ArchivedAccount, targetRole: string,
                                     departmentId: string | null) => {
        const currentAccount = readDemoAccounts().find((item) => item.id === account.originalUserId);
        if (!currentAccount || currentAccount.status === "ACTIVE") {
            fail("The linked identity is no longer archived. Refresh the directory.");
        }
        if (!["ROLE_CEO", "ROLE_MANAGER", "ROLE_HR_ADMIN", "ROLE_TEAM_LEAD", "ROLE_EMPLOYEE",
            "ROLE_RECEPTIONIST", "ROLE_SECURITY"].includes(targetRole)) {
            fail("Select one supported recovery role.");
        }
        const employeeRole = !["ROLE_RECEPTIONIST", "ROLE_SECURITY"].includes(targetRole);
        if (employeeRole && !currentAccount.employeeId) {
            fail("This role requires the archived account's existing employee ID.");
        }
        if (employeeRole && !departmentId) fail("Select an active department.");
        if (!employeeRole && departmentId) {
            fail("Receptionist and Security recovery remain company-wide.");
        }
        if (departmentId && !departments.some((item) => item.id === departmentId && item.active)) {
            fail("Select an active department.");
        }
        if (targetRole === "ROLE_CEO" && readDemoAccounts().some((item) => item.id !== currentAccount.id
            && item.status === "ACTIVE" && item.role === "ROLE_CEO")) {
            fail("The company already has an active CEO. Select another role for this recovery.");
        }
        const occupied = targetRole === "ROLE_MANAGER"
            ? readDemoManagerAssignments().some((item) => item.active && item.departmentId === departmentId
                && item.managerUserId !== currentAccount.id)
            : targetRole === "ROLE_HR_ADMIN"
                ? readDemoDepartmentHrAssignments().some((item) => item.active && item.departmentId === departmentId
                    && item.hrUserId !== currentAccount.id)
                : targetRole === "ROLE_TEAM_LEAD"
                    ? readDemoTeamLeadAssignments().some((item) => item.active && item.departmentId === departmentId
                        && item.teamLeadUserId !== currentAccount.id)
                    : false;
        if (occupied) {
            fail(`The selected department already has an active ${statusLabel(targetRole)}.`);
        }
        return currentAccount;
    };

    const recoverPreviewAccount = (challenge: ArchivedRecoveryChallenge) => {
        const account = readDemoArchivedAccounts().find((item) => item.id === challenge.archivedAccountId);
        if (!account) fail("The archived account record could not be found.");
        const currentAccount = validatePreviewRecovery(account, challenge.targetRole,
            challenge.targetDepartmentId);
        const now = new Date().toISOString();
        const department = challenge.targetDepartmentId
            ? departments.find((item) => item.id === challenge.targetDepartmentId) : undefined;
        let teamLeads = readDemoTeamLeadAssignments();
        let departmentHrs = readDemoDepartmentHrAssignments();
        let managers = readDemoManagerAssignments();
        const sameTeamLead = challenge.targetRole === "ROLE_TEAM_LEAD" && teamLeads.some((item) =>
            item.active && item.teamLeadUserId === currentAccount.id
            && item.departmentId === challenge.targetDepartmentId);
        const sameHr = challenge.targetRole === "ROLE_HR_ADMIN" && departmentHrs.some((item) =>
            item.active && item.hrUserId === currentAccount.id
            && item.departmentId === challenge.targetDepartmentId);
        const sameManager = challenge.targetRole === "ROLE_MANAGER" && managers.some((item) =>
            item.active && item.managerUserId === currentAccount.id
            && item.departmentId === challenge.targetDepartmentId);
        teamLeads = teamLeads.map((item) => item.active && item.teamLeadUserId === currentAccount.id
        && !(sameTeamLead && item.departmentId === challenge.targetDepartmentId)
            ? { ...item, active: false, endedByUserId: DEMO_SYSTEM_ADMIN.id, endedAt: now } : item);
        departmentHrs = departmentHrs.map((item) => item.active && item.hrUserId === currentAccount.id
        && !(sameHr && item.departmentId === challenge.targetDepartmentId)
            ? { ...item, active: false, endedByUserId: DEMO_SYSTEM_ADMIN.id, endedAt: now } : item);
        managers = managers.map((item) => item.active && item.managerUserId === currentAccount.id
        && !(sameManager && item.departmentId === challenge.targetDepartmentId)
            ? { ...item, active: false, endedByUserId: DEMO_SYSTEM_ADMIN.id, endedAt: now } : item);
        if (challenge.targetRole === "ROLE_TEAM_LEAD" && !sameTeamLead) {
            teamLeads = [{ id: newClientId(), departmentId: challenge.targetDepartmentId!,
                teamLeadUserId: currentAccount.id, teamLeadEmployeeId: currentAccount.employeeId!,
                active: true, assignedByUserId: DEMO_SYSTEM_ADMIN.id, assignedAt: now,
                endedByUserId: null, endedAt: null }, ...teamLeads];
        } else if (challenge.targetRole === "ROLE_HR_ADMIN" && !sameHr) {
            departmentHrs = [{ id: newClientId(), departmentId: challenge.targetDepartmentId!,
                hrUserId: currentAccount.id, hrEmployeeId: currentAccount.employeeId!,
                active: true, assignedByUserId: DEMO_SYSTEM_ADMIN.id, assignedAt: now,
                endedByUserId: null, endedAt: null }, ...departmentHrs];
        } else if (challenge.targetRole === "ROLE_MANAGER" && !sameManager) {
            managers = [{ id: newClientId(), departmentId: challenge.targetDepartmentId!,
                managerUserId: currentAccount.id, managerEmployeeId: currentAccount.employeeId!,
                active: true, assignedByUserId: DEMO_SYSTEM_ADMIN.id, assignedAt: now,
                endedByUserId: null, endedAt: null }, ...managers];
        }
        const designation = challenge.targetRole === "ROLE_CEO" ? "Chief Executive Officer"
            : challenge.targetRole === "ROLE_MANAGER" ? "Department Manager"
                : challenge.targetRole === "ROLE_HR_ADMIN" ? "HR Business Partner"
                    : challenge.targetRole === "ROLE_TEAM_LEAD" ? "Team Lead"
                        : challenge.targetRole === "ROLE_EMPLOYEE" ? "Employee" : null;
        const nextEmployees = readDemoEmployees().map((item) =>
            currentAccount.employeeId && (item.uuid ?? item.id) === currentAccount.employeeId
                ? { ...item, ...(department ? { departmentId: department.id, department: department.name } : {}),
                    ...(designation ? { role: designation } : {}), status: "Active" as const } : item);
        const nextAccounts = readDemoAccounts().map((item) => item.id === currentAccount.id
            ? { ...item, role: challenge.targetRole, status: "ACTIVE", rejectedAt: null } : item);
        const lifecycleRecord: AccountLifecycleRecord = {
            id: newClientId(), closureRequestId: account.closureRequestId,
            targetUserId: currentAccount.id, eventType: "ACCOUNT_RECOVERED",
            fromStatus: "ARCHIVED", toStatus: "ACTIVE", actorUserId: DEMO_SYSTEM_ADMIN.id,
            detail: `Recovered with the same user and employee IDs; current role ${statusLabel(
                challenge.targetRole)}; current department ${department?.name ?? "Company-wide"}; previous role and department retained in audit history`,
            occurredAt: now,
        };
        const essentialLog: EssentialLogRecord = {
            id: newClientId(), category: "ACCOUNT_LIFECYCLE", eventType: "ARCHIVED_ACCOUNT_RECOVERED",
            subjectType: "USER_ACCOUNT", subjectId: currentAccount.id, referenceId: account.closureRequestId,
            actorUserId: DEMO_SYSTEM_ADMIN.id, approverUserId: DEMO_SYSTEM_ADMIN.id, status: "ACTIVE",
            title: `Recovered ${account.fullName}`, detail: lifecycleRecord.detail, occurredAt: now,
        };
        // All next states are validated before these writes. The account write is
        // last because it invalidates stale preview sessions across open tabs.
        writeDemoTeamLeadAssignments(teamLeads);
        writeDemoDepartmentHrAssignments(departmentHrs);
        writeDemoManagerAssignments(managers);
        writeDemoEmployees(nextEmployees);
        writeDemoArchivedAccounts(readDemoArchivedAccounts().filter((item) => item.id !== account.id));
        writeDemoAccountLifecycle([lifecycleRecord, ...readDemoAccountLifecycle()]);
        writeDemoEssentialLogs([essentialLog, ...readDemoEssentialLogs()]);
        writeDemoAccounts(nextAccounts);
    };

    const recoverArchived = async (event: FormEvent<HTMLFormElement>) => {
        event.preventDefault();
        if (!recoveryTarget) return;
        const form = event.currentTarget;
        const data = new FormData(form);
        setBusyId("recovery"); setError(""); setMessage("");
        try {
            if (!recoveryChallenge) {
                const targetRole = recoveryRole;
                const departmentId = recoveryNeedsDepartment ? recoveryDepartmentId || null : null;
                const reason = String(data.get("reason") ?? "").trim();
                const currentPassword = String(data.get("currentPassword") ?? "");
                let challenge: ArchivedRecoveryChallenge;
                if (isBackendConfigured) {
                    challenge = await brainServeApi.requestArchivedRecoveryOtp({
                        archivedAccountId: recoveryTarget.id, targetRole, departmentId, currentPassword, reason,
                    });
                } else {
                    if (!await verifyPreviewSystemAdminPassword(userEmail, currentPassword)) {
                        const attemptsRemaining = previewArchivePasswordFailure();
                        fail(attemptsRemaining === 0
                            ? "Too many incorrect password attempts. Try again later."
                            : `Current System Admin password is incorrect. ${attemptsRemaining} attempts remain.`);
                    }
                    clearPreviewArchivePasswordFailures();
                    const currentAccount = validatePreviewRecovery(recoveryTarget, targetRole, departmentId);
                    const employee = readDemoEmployees().find((item) =>
                        currentAccount.employeeId && (item.uuid ?? item.id) === currentAccount.employeeId);
                    const createdAt = new Date();
                    const targetDepartment = departments.find((item) => item.id === departmentId);
                    challenge = {
                        challengeId: newClientId(), archivedAccountId: recoveryTarget.id,
                        targetUserId: currentAccount.id, targetName: currentAccount.fullName,
                        targetEmail: currentAccount.email, employeeId: currentAccount.employeeId ?? null,
                        previousRole: currentAccount.role,
                        previousDepartmentId: employee?.departmentId ?? recoveryTarget.departmentId,
                        previousDepartmentName: employee?.department ?? recoveryTarget.departmentName,
                        targetRole, targetDepartmentId: departmentId,
                        targetDepartmentName: targetDepartment?.name ?? null, reason,
                        createdAt: createdAt.toISOString(),
                        expiresAt: new Date(createdAt.getTime() + 10 * 60_000).toISOString(),
                        resendAvailableAt: new Date(createdAt.getTime() + 60_000).toISOString(),
                        attemptsRemaining: 5,
                    };
                    writePreviewArchivedRecoveryChallenge(challenge);
                }
                form.reset();
                setRecoveryChallenge(challenge);
                setRecoveryRole(challenge.targetRole);
                setRecoveryDepartmentId(challenge.targetDepartmentId ?? "");
                setChallengeClock(Date.parse(challenge.createdAt));
                setMessage("System Admin password verified. A recovery code was sent; you can minimize this section while checking it.");
            } else {
                const otp = String(data.get("otp") ?? "");
                if (isBackendConfigured) {
                    await brainServeApi.recoverArchivedAccount(recoveryChallenge.challengeId, otp);
                } else if (!previewOtpIsValid(otp)) {
                    const attemptsRemaining = recoveryChallenge.attemptsRemaining - 1;
                    if (attemptsRemaining <= 0) {
                        writePreviewArchivedRecoveryChallenge(null);
                        setRecoveryChallenge(null); setRecoveryTargetId("");
                        fail("The recovery verification was cancelled after too many incorrect codes.");
                    }
                    const updated = { ...recoveryChallenge, attemptsRemaining };
                    writePreviewArchivedRecoveryChallenge(updated);
                    setRecoveryChallenge(updated);
                    fail(`The recovery code is incorrect. ${attemptsRemaining} attempts remain.`);
                } else {
                    recoverPreviewAccount(recoveryChallenge);
                }
                writePreviewArchivedRecoveryChallenge(null);
                setRecoveryChallenge(null); setRecoveryTargetId(""); setRecoveryPanelMinimized(false);
                form.reset();
                setMessage(`${recoveryTarget.fullName} recovered with the same employee ID and the current ${statusLabel(
                    recoveryChallenge.targetRole)} role. Previous access remains only in audit history.`);
                await Promise.all([load(), loadAccountPage(), loadArchivedPage()]);
            }
        } catch (reasonValue) {
            const errorCode = reasonValue instanceof ApiError ? reasonValue.problem.errorCode : undefined;
            if (["ACCOUNT_RECOVERY_OTP_ATTEMPTS_EXHAUSTED", "ACCOUNT_RECOVERY_CHALLENGE_EXPIRED",
                "ACCOUNT_RECOVERY_CHALLENGE_STALE", "ARCHIVED_ACCOUNT_ALREADY_RECOVERED"].includes(errorCode ?? "")) {
                writePreviewArchivedRecoveryChallenge(null);
                setRecoveryChallenge(null); setRecoveryTargetId(""); setRecoveryPanelMinimized(false);
            } else if (isBackendConfigured && errorCode === "INVALID_OTP") {
                const refreshed = await brainServeApi.activeArchivedRecoveryChallenge().catch(() => undefined);
                if (refreshed) setRecoveryChallenge(refreshed);
            }
            setError(reasonValue instanceof Error ? reasonValue.message : "Archived account recovery failed.");
        } finally { setBusyId(""); }
    };

    const resendArchivedRecoveryOtp = async () => {
        if (!recoveryChallenge || recoveryResendSeconds > 0) return;
        setBusyId("recovery-resend"); setError(""); setMessage("");
        try {
            const challenge = isBackendConfigured
                ? await brainServeApi.resendArchivedRecoveryOtp(recoveryChallenge.challengeId)
                : { ...recoveryChallenge, expiresAt: new Date(Date.now() + 10 * 60_000).toISOString(),
                    resendAvailableAt: new Date(Date.now() + 60_000).toISOString(), attemptsRemaining: 5 };
            if (!isBackendConfigured) writePreviewArchivedRecoveryChallenge(challenge);
            setRecoveryChallenge(challenge);
            setChallengeClock(Date.now());
            setMessage("A new recovery code was sent to the System Admin mailbox.");
        } catch (reasonValue) {
            setError(reasonValue instanceof Error ? reasonValue.message : "The recovery code could not be resent.");
        } finally { setBusyId(""); }
    };

    const cancelArchivedRecovery = async () => {
        if (busyId) return;
        setBusyId("recovery-cancel"); setError(""); setMessage("");
        try {
            if (recoveryChallenge && isBackendConfigured) {
                await brainServeApi.cancelArchivedRecoveryChallenge(recoveryChallenge.challengeId);
            }
            writePreviewArchivedRecoveryChallenge(null);
            setRecoveryChallenge(null); setRecoveryTargetId(""); setRecoveryPanelMinimized(false);
            setMessage(recoveryChallenge
                ? "Account recovery verification cancelled. No identity or role changes were made." : "");
        } catch (reasonValue) {
            if (reasonValue instanceof ApiError && [404, 410].includes(reasonValue.status)) {
                writePreviewArchivedRecoveryChallenge(null);
                setRecoveryChallenge(null); setRecoveryTargetId(""); setRecoveryPanelMinimized(false);
                setMessage("The expired recovery verification was cleared. No identity changes were made.");
                return;
            }
            setError(reasonValue instanceof Error ? reasonValue.message : "Recovery verification could not be cancelled.");
        } finally { setBusyId(""); }
    };

    const actionable = requests.filter((item) => role === "System Admin"
        ? item.status === "PENDING_SYSTEM_ADMIN" || (item.targetRole === "ROLE_CEO" && item.status === "REQUESTED")
        : item.status === "REQUESTED");
    const scheduled = role === "System Admin" ? requests.filter((item) => item.status === "SCHEDULED") : [];
    const statusLabel = (value: string) => value.replace("ROLE_", "").replaceAll("_", " ");

    return <section className="account-lifecycle-page">
        <PageTitle eyebrow="IDENTITY GOVERNANCE" title="Account lifecycle"
                   detail={role === "System Admin" ? "Review, schedule and archive accounts without deleting company history."
                       : "Complete the business review before System Admin performs the final archival action."} />
        {role === "System Admin" && <div className="lifecycle-tabs glass-panel"><button className={tab === "pending" ? "active" : ""} onClick={() => setTab("pending")}>Pending closure requests <b>{actionable.length + scheduled.length}</b></button><button className={tab === "active" ? "active" : ""} onClick={() => setTab("active")}>Active accounts <b>{accountTotal}</b></button><button className={tab === "archived" ? "active" : ""} onClick={() => setTab("archived")}>Archived accounts <b>{archivedTotal}</b></button></div>}
        {(role !== "System Admin" || tab === "pending") && <article className="lifecycle-panel glass-panel">
            <div className="panel-heading"><div><span>{role === "System Admin" ? "FINAL CONTROL" : "BUSINESS APPROVAL"}</span><h2>{role === "System Admin" ? "Requests awaiting final action" : "Requests awaiting your review"}</h2><p>Replacement ownership is recorded before access is disabled.</p></div><Archive size={22} /></div>
            <div className="lifecycle-table lifecycle-request-table"><div className="lifecycle-table-head"><span>Account</span><span>Route</span><span>Reason &amp; date</span><span>Replacement</span><span>Action</span></div>{actionable.map((request) => <div className="lifecycle-table-row" key={request.id}><div><strong>{request.targetName}</strong><small>{request.targetEmail}</small></div><div><span className={`closure-status closure-${request.status.toLowerCase()}`}>{statusLabel(request.targetRole)}</span><small>{request.departmentName ?? "Company-wide"}</small></div><div><strong>{request.reason}</strong><small>Effective {request.requestedEffectiveDate}</small></div><div><select value={replacements[request.id] ?? request.replacementUserId ?? ""} onChange={(event) => setReplacements((items) => ({ ...items, [request.id]: event.target.value }))}><option value="">{["ROLE_RECEPTIONIST", "ROLE_SECURITY"].includes(request.targetRole) ? "No replacement required" : "Select replacement"}</option>{candidatesFor(request).map((candidate) => <option key={candidate.userId} value={candidate.userId}>{candidate.fullName}</option>)}</select><input value={notes[request.id] ?? ""} onChange={(event) => setNotes((items) => ({ ...items, [request.id]: event.target.value }))} placeholder="Decision note" /></div><div className="lifecycle-actions"><button className="button button-reject" disabled={busyId === request.id} onClick={() => void (role === "System Admin" ? systemDecision(request, "reject") : businessDecision(request, "reject"))}>Reject</button><button className="button button-primary" disabled={busyId === request.id} onClick={() => void (role === "System Admin" ? systemDecision(request, "approve") : businessDecision(request, "approve"))}>{role === "System Admin" ? "Approve / schedule" : "Business approve"}</button>{role === "System Admin" && <button className="text-button" onClick={() => void showHistory(request)}>History</button>}</div></div>)}</div>
            {actionable.length === 0 && <div className="empty-state"><CheckCircle2 size={28} /><strong>No pending lifecycle action</strong><small>New requests will appear here as their approval route reaches you.</small></div>}
            {scheduled.length > 0 && <div className="scheduled-closure-list"><span>SCHEDULED ARCHIVAL</span>{scheduled.map((request) => <div key={request.id}><span><strong>{request.targetName}</strong><small>{statusLabel(request.targetRole)} · replacement {request.replacementName ?? "not required"}</small></span><time>{request.requestedEffectiveDate}</time><button className="text-button" onClick={() => void showHistory(request)}>History</button></div>)}</div>}
        </article>}
        {role === "System Admin" && tab === "active" && <article className="lifecycle-panel glass-panel">
            <div className="panel-heading"><div><span>ACTIVE DIRECTORY</span><h2>Operational accounts</h2><p>Only 25 matching accounts are loaded at once. Search, role and department filters run in PostgreSQL.</p></div><Users size={22} /></div>
            <div className={`lifecycle-directory-toolbar ${accountRole === "ROLE_EMPLOYEE" ? "with-department-filter" : ""}`}>
                <div className="toolbar-search wide"><Search size={16} /><input value={accountQuery}
                                                                                onChange={(event) => { setAccountPage(0); setAccountQuery(event.target.value); }}
                                                                                placeholder="Search name or company email" aria-label="Search operational accounts" /></div>
                <select value={accountRole} aria-label="Filter operational accounts by role"
                        onChange={(event) => {
                            setAccountPage(0); setAccountDepartmentId(""); setAccountRole(event.target.value);
                        }}>
                    <option value="ALL">All roles</option>
                    <option value="ROLE_CEO">CEO</option><option value="ROLE_HR_ADMIN">HR Admin</option>
                    <option value="ROLE_MANAGER">Manager</option><option value="ROLE_TEAM_LEAD">Team Lead</option>
                    <option value="ROLE_EMPLOYEE">Employee</option>
                    <option value="ROLE_RECEPTIONIST">Receptionist</option><option value="ROLE_SECURITY">Security</option>
                    <option value="ROLE_SYSTEM_ADMIN">System Admin</option>
                </select>
                {accountRole === "ROLE_EMPLOYEE" && <select value={accountDepartmentId}
                                                            aria-label="Filter employees by department"
                                                            onChange={(event) => { setAccountPage(0); setAccountDepartmentId(event.target.value); }}>
                    <option value="">Select employee department</option>
                    {departments.filter((department) => department.active).map((department) =>
                        <option key={department.id} value={department.id}>{department.name}</option>)}
                </select>}
                <span className="directory-result-count" aria-live="polite">{accountPageBusy ? "Loading…" : `${accountTotal.toLocaleString("en-IN")} matching accounts`}</span>
            </div>
            <div className="lifecycle-table" aria-busy={accountPageBusy}>
                <div className="lifecycle-table-head account-head"><span>Identity</span><span>Role</span><span>Department</span><span>Status</span><span>Action</span></div>
                {accounts.map((account) => <div className="lifecycle-table-row account-row" key={account.userId}><div><strong>{account.fullName}</strong><small>{account.email}</small></div><div>{statusLabel(account.role)}</div><div>{account.departmentName ?? "Company-wide"}</div><div><span className="closure-status closure-active">{account.status}</span></div><div>{account.protectedAccount ? <span className="protected-chip"><ShieldCheck size={13} /> Protected</span> : account.role === "ROLE_EMPLOYEE" ? <small>Use HR → CEO termination</small> : <button className={archiveChallenge?.targetUserId === account.userId ? "button button-primary" : "button button-reject"} disabled={Boolean(recoveryChallenge || (archiveChallenge && archiveChallenge.targetUserId !== account.userId))} title={recoveryChallenge ? `Finish or cancel ${recoveryChallenge.targetName}'s recovery first` : archiveChallenge && archiveChallenge.targetUserId !== account.userId ? `Finish or cancel ${archiveChallenge.targetName}'s verification first` : undefined} onClick={() => void openDirectAccount(account)}>{archiveChallenge?.targetUserId === account.userId ? "Enter OTP" : "Deactivate & archive"}</button>}</div></div>)}
                {!accountPageBusy && accounts.length === 0 && <div className="empty-state"><Search size={28} />
                    <strong>{accountRole === "ROLE_EMPLOYEE" && !accountDepartmentId
                        ? "Select an employee department" : "No matching operational accounts"}</strong>
                    <small>{accountRole === "ROLE_EMPLOYEE" && !accountDepartmentId
                        ? "Employees are loaded only after a department is selected."
                        : "Change the name, email, role or department filter."}</small></div>}
            </div>
            <div className="bounded-pagination page-number-pagination lifecycle-pagination">
                <button className="button button-secondary" disabled={accountPageBusy || accountPage === 0} onClick={() => setAccountPage((value) => Math.max(0, value - 1))}>Previous</button>
                <span>Page {accountPage + 1} of {accountPageCount}</span>
                <button className="button button-secondary" disabled={accountPageBusy || accountPage + 1 >= accountPageCount} onClick={() => setAccountPage((value) => value + 1)}>Next</button>
            </div>
        </article>}
        {role === "System Admin" && tab === "archived" && <article className="lifecycle-panel glass-panel">
            <div className="panel-heading"><div><span>RETAINED HISTORY</span><h2>Archived accounts</h2><p>No password hashes, tokens, OTPs or profile image binaries are stored here.</p></div><FileClock size={22} /></div>
            <div className="lifecycle-directory-toolbar archived-directory-toolbar">
                <div className="toolbar-search wide"><Search size={16} /><input value={archivedQuery}
                                                                                onChange={(event) => { setArchivedPage(0); setArchivedQuery(event.target.value); }}
                                                                                placeholder="Search archived identity, role or department" aria-label="Search archived accounts" /></div>
                <span className="directory-result-count" aria-live="polite">{archivedPageBusy ? "Loading…" : `${archivedTotal.toLocaleString("en-IN")} archived accounts`}</span>
            </div>
            <div className="lifecycle-table" aria-busy={archivedPageBusy}>
                <div className="lifecycle-table-head archived-head"><span>Identity snapshot</span><span>Role / department</span><span>Closure</span><span>Retention</span><span>Action</span></div>
                {archived.map((account) => <div className="lifecycle-table-row archived-row" key={account.id}><div><strong>{account.fullName}</strong><small>{account.email}</small></div><div><strong>{statusLabel(account.role)}</strong><small>{account.departmentName ?? "Company-wide"}</small></div><div><strong>{account.reason}</strong><small>{new Date(account.archivedAt).toLocaleString("en-IN")}</small></div><div><span className="closure-status closure-archived">ARCHIVED</span><small>Retain until {account.retentionUntil}</small></div><div><button className={recoveryChallenge?.archivedAccountId === account.id ? "button button-primary" : "button button-secondary"} disabled={Boolean(archiveChallenge || (recoveryChallenge && recoveryChallenge.archivedAccountId !== account.id))} title={archiveChallenge ? `Finish or cancel ${archiveChallenge.targetName}'s archive verification first` : recoveryChallenge && recoveryChallenge.archivedAccountId !== account.id ? `Finish or cancel ${recoveryChallenge.targetName}'s recovery first` : undefined} onClick={() => openArchivedRecovery(account)}>{recoveryChallenge?.archivedAccountId === account.id ? "Enter OTP" : "Recover account"}</button></div></div>)}
            </div>
            {!archivedPageBusy && archived.length === 0 && <div className="empty-state"><Archive size={28} /><strong>No archived accounts</strong><small>Archived identity snapshots will be retained here for compliance.</small></div>}
            <div className="bounded-pagination page-number-pagination lifecycle-pagination">
                <button className="button button-secondary" disabled={archivedPageBusy || archivedPage === 0} onClick={() => setArchivedPage((value) => Math.max(0, value - 1))}>Previous</button>
                <span>Page {archivedPage + 1} of {archivedPageCount}</span>
                <button className="button button-secondary" disabled={archivedPageBusy || archivedPage + 1 >= archivedPageCount} onClick={() => setArchivedPage((value) => value + 1)}>Next</button>
            </div>
        </article>}
        {recoveryChallenge && recoveryPanelMinimized && <article className="direct-archive-resume recovery-resume glass-panel" role="status">
            <span className="direct-archive-resume-icon"><RotateCcw size={19} /></span>
            <span><strong>Recovery verification pending for {recoveryChallenge.targetName}</strong><small>{statusLabel(recoveryChallenge.targetRole)} · expires in {recoveryTimeRemaining} · {recoveryChallenge.attemptsRemaining} attempts remaining</small></span>
            <button type="button" className="button button-primary" onClick={() => { setTab("archived"); setRecoveryPanelMinimized(false); }}><LockKeyhole size={15} /> Enter OTP</button>
        </article>}
        {recoveryTarget && !recoveryPanelMinimized && <article ref={recoveryPanelRef} className="direct-archive-panel recovery-panel glass-panel" aria-labelledby="archived-recovery-title" aria-describedby="archived-recovery-description">
            <header><div><span>GOVERNED RECOVERY</span><h2 id="archived-recovery-title">Recover {recoveryTarget.fullName}</h2><p id="archived-recovery-description">{recoveryChallenge ? "Enter the mailbox code to activate the frozen role and department selection. You can minimize this section without losing progress." : "Restore the same user and employee identity with one current role. Previous role and department remain in immutable lifecycle history."}</p></div><button className="icon-button" type="button" disabled={Boolean(busyId)} aria-label={recoveryChallenge ? "Minimize recovery verification" : "Close account recovery section"} onClick={() => recoveryChallenge ? setRecoveryPanelMinimized(true) : void cancelArchivedRecovery()}>{recoveryChallenge ? <MoreHorizontal size={18} /> : <X size={18} />}</button></header>
            <div className="direct-archive-steps" aria-label="Account recovery verification progress"><span className="complete"><b>1</b>Identity</span><i /><span className={recoveryChallenge ? "complete" : "active"}><b>2</b>Role &amp; password</span><i /><span className={recoveryChallenge ? "active" : ""}><b>3</b>Mailbox OTP</span></div>
            <form onSubmit={recoverArchived}>
                <div className="direct-archive-context"><span className="avatar">{visitorInitials(recoveryTarget.fullName)}</span><span><small>Archived as {statusLabel(recoveryTarget.role)} · {recoveryTarget.departmentName ?? "Company-wide"}</small><strong>{recoveryTarget.email}</strong>{recoveryTarget.employeeNumber && <small>Employee ID {recoveryTarget.employeeNumber}</small>}</span><span className="closure-status closure-archived">{recoveryChallenge ? "OTP PENDING" : "ARCHIVED"}</span></div>
                {!recoveryChallenge ? <div className="direct-archive-form-grid recovery-form-grid">
                    <label>New current role<select name="targetRole" value={recoveryRole} required onChange={(event) => {
                        const nextRole = event.target.value;
                        setRecoveryRole(nextRole);
                        if (["ROLE_RECEPTIONIST", "ROLE_SECURITY"].includes(nextRole)) setRecoveryDepartmentId("");
                    }}><option value="ROLE_MANAGER">Manager</option><option value="ROLE_HR_ADMIN">HR Admin</option><option value="ROLE_TEAM_LEAD">Team Lead</option><option value="ROLE_EMPLOYEE">Employee</option><option value="ROLE_CEO">CEO</option><option value="ROLE_RECEPTIONIST">Receptionist</option><option value="ROLE_SECURITY">Security</option></select><small>CEO is unavailable while another active CEO exists.</small></label>
                    {recoveryNeedsDepartment && <label>Department<select name="departmentId" value={recoveryDepartmentId} onChange={(event) => setRecoveryDepartmentId(event.target.value)} required><option value="">Select active department</option>{departments.filter((item) => item.active).map((department) => <option value={department.id} key={department.id}>{department.name}</option>)}</select><small>Manager, HR Admin and Team Lead slots must be unoccupied.</small></label>}
                    <label>Recovery reason<textarea name="reason" minLength={5} maxLength={1000} required placeholder="Explain why this archived identity is being restored and assigned this role." /></label>
                    <label>Current System Admin password<input name="currentPassword" type="password" minLength={8} maxLength={128} autoComplete="current-password" required /><small>Verified securely and never saved in this form or browser storage.</small></label>
                </div> : <div className="direct-archive-verification">
                    <div className="direct-archive-summary"><span><small>Role transition</small><strong>{statusLabel(recoveryChallenge.previousRole)} → {statusLabel(recoveryChallenge.targetRole)}</strong></span><span><small>Department</small><strong>{recoveryChallenge.targetDepartmentName ?? "Company-wide"}</strong></span><span><small>Recovery reason</small><strong>{recoveryChallenge.reason}</strong></span></div>
                    <div className="otp-status-strip"><Mail size={18} /><span><strong>Recovery code sent to the System Admin mailbox</strong><small>Expires in {recoveryTimeRemaining} · {recoveryChallenge.attemptsRemaining} attempts remaining</small></span></div>
                    <label>Six-digit recovery code<input name="otp" inputMode="numeric" autoComplete="one-time-code" pattern="\d{6}" minLength={6} maxLength={6} required /></label>
                </div>}
                {message && <div className="success-banner direct-panel-message"><CheckCircle2 size={17} />{message}</div>}
                {error && <div className="login-error direct-panel-message" role="alert">{error}</div>}
                <div className="direct-archive-actions">
                    {!recoveryChallenge ? <>
                        <button type="button" className="button button-secondary" disabled={Boolean(busyId)} onClick={() => void cancelArchivedRecovery()}>Cancel</button>
                        <button className="button button-primary" disabled={Boolean(busyId)}><Mail size={15} />{busyId === "recovery" ? "Verifying…" : "Verify password & send OTP"}</button>
                    </> : <>
                        <button type="button" className="button button-secondary" disabled={Boolean(busyId)} onClick={() => setRecoveryPanelMinimized(true)}>Minimize</button>
                        <button type="button" className="button button-secondary danger-text" disabled={Boolean(busyId)} onClick={() => void cancelArchivedRecovery()}>Cancel verification</button>
                        <button type="button" className="button button-secondary" disabled={Boolean(busyId) || recoveryResendSeconds > 0} onClick={() => void resendArchivedRecoveryOtp()}><RotateCcw size={15} />{busyId === "recovery-resend" ? "Sending…" : recoveryResendSeconds > 0 ? `Resend in ${recoveryResendSeconds}s` : "Resend code"}</button>
                        <button className="button button-primary" disabled={Boolean(busyId) || recoverySecondsRemaining === 0}><BadgeCheck size={16} />{busyId === "recovery" ? "Recovering…" : "Confirm account recovery"}</button>
                    </>}
                </div>
            </form>
        </article>}
        {archiveChallenge && archivePanelMinimized && <article className="direct-archive-resume glass-panel" role="status">
            <span className="direct-archive-resume-icon"><LockKeyhole size={19} /></span>
            <span><strong>OTP verification pending for {archiveChallenge.targetName}</strong><small>{statusLabel(archiveChallenge.targetRole)} · expires in {archiveTimeRemaining} · {archiveChallenge.attemptsRemaining} attempts remaining</small></span>
            <button type="button" className="button button-primary" onClick={() => { setTab("active"); setArchivePanelMinimized(false); }}><LockKeyhole size={15} /> Enter OTP</button>
        </article>}
        {directTarget && !archivePanelMinimized && <article ref={directArchivePanelRef} className="direct-archive-panel glass-panel" aria-labelledby="direct-archive-title" aria-describedby="direct-archive-description">
            <header><div><span>EMERGENCY CONTROL</span><h2 id="direct-archive-title">Deactivate &amp; archive {directTarget.fullName}</h2><p id="direct-archive-description">{archiveChallenge ? "Enter the mailbox code to complete the verified archive action. You can minimize this section without losing progress." : "Confirm the account, replacement and reason, then verify the current System Admin password."}</p></div><button className="icon-button" type="button" disabled={Boolean(busyId)} aria-label={archiveChallenge ? "Minimize archive verification" : "Close deactivate and archive section"} onClick={() => archiveChallenge ? setArchivePanelMinimized(true) : void cancelDirectArchive()}>{archiveChallenge ? <MoreHorizontal size={18} /> : <X size={18} />}</button></header>
            <div className="direct-archive-steps" aria-label="Archive verification progress"><span className="complete"><b>1</b>Account details</span><i /><span className={archiveChallenge ? "complete" : "active"}><b>2</b>Password</span><i /><span className={archiveChallenge ? "active" : ""}><b>3</b>Mailbox OTP</span></div>
            <form onSubmit={directArchive}>
                <div className="direct-archive-context"><span className="avatar">{visitorInitials(directTarget.fullName)}</span><span><small>{statusLabel(directTarget.role)} · {directTarget.departmentName ?? "Company-wide"}</small><strong>{directTarget.email}</strong></span><span className="closure-status closure-active">{archiveChallenge ? "OTP PENDING" : directTarget.status}</span></div>
                {!archiveChallenge ? <div className="direct-archive-form-grid">
                    <label>Archive reason<textarea name="reason" minLength={5} maxLength={1000} required placeholder="Explain why this account must be deactivated and archived." /></label>
                    <label>Replacement<select name="replacementUserId" required={!(["ROLE_RECEPTIONIST", "ROLE_SECURITY"].includes(directTarget.role))}><option value="">{["ROLE_RECEPTIONIST", "ROLE_SECURITY"].includes(directTarget.role) ? "No replacement required" : "Select replacement"}</option>{candidatesFor(directTarget).map((candidate) => <option key={candidate.userId} value={candidate.userId}>{candidate.fullName}</option>)}</select></label>
                    <label>Current System Admin password<input name="currentPassword" type="password" minLength={8} maxLength={128} autoComplete="current-password" required /><small>Verified securely and never saved in this form or browser storage.</small></label>
                </div> : <div className="direct-archive-verification">
                    <div className="direct-archive-summary"><span><small>Archive reason</small><strong>{archiveChallenge.reason}</strong></span><span><small>Replacement</small><strong>{archiveChallenge.replacementName ?? "Not required"}</strong></span></div>
                    <div className="otp-status-strip"><Mail size={18} /><span><strong>Code sent to the System Admin mailbox</strong><small>Expires in {archiveTimeRemaining} · {archiveChallenge.attemptsRemaining} attempts remaining</small></span></div>
                    <label>Six-digit confirmation code<input name="otp" inputMode="numeric" autoComplete="one-time-code" pattern="\d{6}" minLength={6} maxLength={6} required /></label>
                </div>}
                {message && <div className="success-banner direct-panel-message"><CheckCircle2 size={17} />{message}</div>}
                {error && <div className="login-error direct-panel-message" role="alert">{error}</div>}
                <div className="direct-archive-actions">
                    {!archiveChallenge ? <>
                        <button type="button" className="button button-secondary" disabled={Boolean(busyId)} onClick={() => void cancelDirectArchive()}>Cancel</button>
                        <button className="button button-reject" disabled={Boolean(busyId)}><Mail size={15} />{busyId === "direct" ? "Verifying…" : "Verify password & send OTP"}</button>
                    </> : <>
                        <button type="button" className="button button-secondary" disabled={Boolean(busyId)} onClick={() => setArchivePanelMinimized(true)}>Minimize</button>
                        <button type="button" className="button button-secondary danger-text" disabled={Boolean(busyId)} onClick={() => void cancelDirectArchive()}>Cancel verification</button>
                        <button type="button" className="button button-secondary" disabled={Boolean(busyId) || archiveResendSeconds > 0} onClick={() => void resendDirectArchiveOtp()}><RotateCcw size={15} />{busyId === "direct-resend" ? "Sending…" : archiveResendSeconds > 0 ? `Resend in ${archiveResendSeconds}s` : "Resend code"}</button>
                        <button className="button button-reject" disabled={Boolean(busyId) || archiveSecondsRemaining === 0}><Archive size={16} />{busyId === "direct" ? "Archiving…" : "Confirm deactivation & archive"}</button>
                    </>}
                </div>
            </form>
        </article>}
        {historyRequestId && <article className="lifecycle-history glass-panel"><div className="panel-heading"><div><span>IMMUTABLE RECORD</span><h2>Lifecycle history</h2></div><button className="icon-button" onClick={() => setHistoryRequestId("")}><X size={18} /></button></div>{history.map((record) => <div key={record.id}><i /><span><strong>{record.toStatus.replaceAll("_", " ")}</strong><small>{record.detail} · {new Date(record.occurredAt).toLocaleString("en-IN")}</small></span></div>)}</article>}
        {message && !directTarget && !recoveryTarget && <div className="success-banner"><CheckCircle2 size={17} />{message}</div>}
        {error && !directTarget && !recoveryTarget && <div className="login-error" role="alert">{error}</div>}
    </section>;
}

