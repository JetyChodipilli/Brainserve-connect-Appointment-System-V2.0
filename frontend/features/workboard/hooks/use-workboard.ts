"use client";

import { organizationApi } from "../../organization/api/organization-api";
import { workboardApi as brainServeApi } from "../api/workboard-api";
import { ApiError, isBackendConfigured, isWorkspaceUpdateLeader } from "../../../lib/api-client";
import type { WorkInsight, WorkTask, WorkTaskWorkspace, WorkboardCriteria, WorkboardDetail, WorkboardItem, WorkboardPage, WorkboardPreferences, WorkboardScope } from "../types/workboard";
import { officeToday } from "../../../lib/appointments";
import { readDemoAccounts } from "../../../preview/accounts";
import { initialStaffAccounts } from "../../../preview/fixtures/workspace";
import { demoSenderName, readDemoInternalNotifications, writeDemoInternalNotifications } from "../../../preview/notifications";
import { readDemoWorkInsights, readDemoWorkTasks, writeDemoWorkInsights, writeDemoWorkTasks } from "../preview";
import { type Department, type Role } from "../../../types/workspace";
import { newClientId } from "../../../utils/ids";
import { workTaskStatusLabel, workWeekStart } from "../utils/work-utils";
import { type FormEvent, useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from "react";
import type { WorkboardProps } from "../types/props";
import { defaultCriteria, defaultPreferences, previewWorkboardPage, readPreviewPreferences, writePreviewPreferences, validWorkboardPreferences } from "../utils/workboard-model";

export function useWorkboard({ role, refreshKey, userEmail, employees, staffAccounts, departments, teamLeadAssignments, appointments }: WorkboardProps) {
    const [tasks, setTasks] = useState<WorkTask[]>([]);
    const [showCreate, setShowCreate] = useState(false);
    const [query, setQuery] = useState("");
    const [statusFilter, setStatusFilter] = useState("ALL");
    const [branchFilter, setBranchFilter] = useState("ALL");
    const [queueScope, setQueueScope] = useState<WorkboardScope>("TODAY");
    const [quickFilter, setQuickFilter] = useState<WorkboardCriteria["quickFilter"]>("ALL");
    const [sort, setSort] = useState<WorkboardCriteria["sort"]>("DUE_DATE");
    const [page, setPage] = useState(0), [size, setSize] = useState(20);
    const [serverPage, setServerPage] = useState<WorkboardPage | null>(null);
    const [detail, setDetail] = useState<WorkboardDetail | null>(null), [detailError, setDetailError] = useState("");
    const [preferences, setPreferences] = useState<WorkboardPreferences>(defaultPreferences);
    const [preferenceBusy, setPreferenceBusy] = useState(false), [preferenceError, setPreferenceError] = useState("");
    const [conflict, setConflict] = useState(false);
    const [expandedTaskId, setSelectedTaskId] = useState<string | null>(null);
    const [busy, setBusy] = useState("");
    const [message, setMessage] = useState("");
    const [error, setError] = useState("");
    const [loadError, setLoadError] = useState("");
    const [refreshWarning, setRefreshWarning] = useState("");
    const [loading, setLoading] = useState(true);
    const [workspace, setWorkspace] = useState<WorkTaskWorkspace | null>(null);
    const [scopeDepartments, setScopeDepartments] = useState<Department[]>(departments);
    const [pendingHrAuditTaskIds, setPendingHrAuditTaskIds] = useState<Set<string>>(new Set());
    const [workflowStateByTaskId, setWorkflowStateByTaskId] = useState<Map<string, WorkInsight["auditStatus"]>>(new Map());
    const [actionDialog, setActionDialog] = useState<{ task: WorkTask; action: "start" | "complete" | "approve" | "request-changes" | "insight-rework" | "revise-rework" | "hr-rework" } | null>(null);
    const [actionNote, setActionNote] = useState("");
    const [initialActionNote, setInitialActionNote] = useState("");
    const lifecycle = useRef(0), loadSequence = useRef(0), detailSequence = useRef(0);
    const loadController = useRef<AbortController | null>(null), detailController = useRef<AbortController | null>(null);
    const preferencesController = useRef<AbortController | null>(null);
    const preferenceSequence = useRef(0);
    const selectedRef = useRef(expandedTaskId);
    const scopeKey = `${role}:${userEmail.toLowerCase()}`;
    const scopeRef = useRef(scopeKey);
    useLayoutEffect(() => { scopeRef.current = scopeKey; }, [scopeKey]);
    const hasLoadedTasksRef = useRef(false);
    const setExpandedTaskId = useCallback((id: string | null) => {
        selectedRef.current = id; detailSequence.current++; detailController.current?.abort();
        setSelectedTaskId(id); setDetail(null); setDetailError("");
    }, []);
    const [dataScope, setDataScope] = useState(scopeKey);
    const [reloadRevision, setReloadRevision] = useState(0);
    const criteria = useMemo<WorkboardCriteria>(() => ({ scope: queueScope, quickFilter, query, status: statusFilter as WorkboardCriteria["status"], branch: branchFilter === "ALL" ? "" : branchFilter, sort }), [queueScope, quickFilter, query, statusFilter, branchFilter, sort]);
    const criteriaKey = JSON.stringify(criteria);
    const clearAuthority = useCallback(() => {
        lifecycle.current++; loadSequence.current++; detailSequence.current++; preferenceSequence.current++;
        loadController.current?.abort(); detailController.current?.abort(); preferencesController.current?.abort();
        setTasks([]); setServerPage(null); setDetail(null); setExpandedTaskId(null); setActionDialog(null); setActionNote(""); setInitialActionNote("");
        setWorkspace(null); setScopeDepartments([]); setPendingHrAuditTaskIds(new Set()); setWorkflowStateByTaskId(new Map());
        setPreferences(defaultPreferences); setPreferenceError(""); setShowCreate(false); setBusy(""); setPreferenceBusy(false);
        setError(""); setMessage(""); setConflict(false); hasLoadedTasksRef.current = false;
        setLoadError(""); setRefreshWarning(""); setDetailError(""); setLoading(false);
    }, [setExpandedTaskId]);
    const failAction = (reason: unknown, fallback: string) => {
        if (reason instanceof ApiError && [401, 403].includes(reason.status)) clearAuthority();
        setConflict(reason instanceof ApiError && reason.status === 409);
        setError(reason instanceof Error ? reason.message : fallback);
    };
    const lastWorkspaceRefreshKeyRef = useRef(refreshKey);
    const workBoardDepartments = departments.length > 0 ? departments : scopeDepartments;
    const demoAccounts = !isBackendConfigured ? readDemoAccounts() : [];
    const currentDemoAccount = demoAccounts.find((account) =>
        account.email.toLowerCase() === userEmail.toLowerCase());
    const profileEmployee = employees.find((item) => item.email.toLowerCase() === userEmail.toLowerCase()
        || Boolean(currentDemoAccount?.employeeId && (item.uuid ?? item.id) === currentDemoAccount.employeeId));
    const profileEmployeeId = profileEmployee?.uuid ?? profileEmployee?.id ?? currentDemoAccount?.employeeId ?? undefined;
    const activeTeamLeadAssignments = teamLeadAssignments.filter((assignment) => assignment.active);
    const matchedTeamLeadAssignment = role === "Team Lead" ? activeTeamLeadAssignments.find((assignment) =>
        assignment.teamLeadUserId === currentDemoAccount?.id
        || assignment.teamLeadEmployeeId === profileEmployeeId) : undefined;
    const soleTeamLeadAssignment = activeTeamLeadAssignments.length === 1 ? activeTeamLeadAssignments[0] : undefined;
    const soleAssignmentHasKnownPreviewOwner = Boolean(soleTeamLeadAssignment && demoAccounts.some((account) =>
        account.id === soleTeamLeadAssignment.teamLeadUserId && account.status === "ACTIVE" && account.role === "ROLE_TEAM_LEAD"));
    const currentTeamLeadAssignment = matchedTeamLeadAssignment
        ?? (role === "Team Lead" && soleTeamLeadAssignment
        && (isBackendConfigured || (Boolean(currentDemoAccount) && !soleAssignmentHasKnownPreviewOwner))
            ? soleTeamLeadAssignment : undefined);
    const currentEmployee = profileEmployee ?? (currentTeamLeadAssignment
        ? employees.find((item) => (item.uuid ?? item.id) === currentTeamLeadAssignment.teamLeadEmployeeId)
        : undefined);
    const currentEmployeeId = currentEmployee?.uuid ?? currentEmployee?.id ?? profileEmployeeId;
    const teamLeadDepartmentId = workspace?.departmentId ?? (role === "Team Lead"
            ? currentTeamLeadAssignment?.departmentId ?? currentEmployee?.departmentId : currentEmployee?.departmentId)
        ?? (workBoardDepartments.length === 1 ? workBoardDepartments[0].id : undefined);
    const scopeDepartmentIdRef = useRef(teamLeadDepartmentId);
    useEffect(() => { scopeDepartmentIdRef.current = teamLeadDepartmentId; }, [teamLeadDepartmentId]);
    const assignedDepartment = workBoardDepartments.find((department) => department.id === teamLeadDepartmentId)
        ?? (workspace ? { id: workspace.departmentId, code: workspace.departmentCode,
            name: workspace.departmentName, active: true, version: 0 } : undefined);
    const assignedTeamLead = activeTeamLeadAssignments.find((assignment) =>
        assignment.departmentId === teamLeadDepartmentId);
    const activeAccounts = isBackendConfigured ? staffAccounts : readDemoAccounts().map((account) => ({
        userId: account.id, fullName: account.fullName, email: account.email, status: account.status,
        enabled: account.status === "ACTIVE", forcePasswordChange: account.forcePasswordChange,
        roles: [account.role], employeeId: account.employeeId ?? null,
        grantedPermissions: [], deniedPermissions: [], effectivePermissions: [],
    }));
    const previewEligibleEmployees = employees.filter((item) => {
        const employeeId = item.uuid ?? item.id;
        if (item.status !== "Active" || !teamLeadDepartmentId || item.departmentId !== teamLeadDepartmentId) return false;
        const account = activeAccounts.find((candidate) => candidate.employeeId === employeeId
            || candidate.email.toLowerCase() === item.email.toLowerCase());
        const accountActive = Boolean(account && account.status === "ACTIVE" && account.enabled !== false);
        if (!accountActive) return false;
        if (role === "Team Lead") {
            return employeeId !== currentEmployeeId && account?.roles.includes("ROLE_EMPLOYEE");
        }
        if (role === "HR Admin") {
            return account?.roles.includes("ROLE_EMPLOYEE")
                || employeeId === assignedTeamLead?.teamLeadEmployeeId;
        }
        return false;
    });
    const eligibleAssignees = isBackendConfigured
        ? workspace?.eligibleAssignees ?? []
        : previewEligibleEmployees.map((item) => ({ employeeId: item.uuid ?? item.id,
            displayName: item.name, designation: item.role,
            role: (item.uuid ?? item.id) === assignedTeamLead?.teamLeadEmployeeId
                ? "TEAM_LEAD" as const : "EMPLOYEE" as const }));
    const load = useCallback(async (intent: "initial" | "background" | "manual" = "background") => {
        const generation = lifecycle.current, sequence = ++loadSequence.current, owner = scopeKey;
        loadController.current?.abort(); const controller = new AbortController(); loadController.current = controller;
        const current = () => generation === lifecycle.current && sequence === loadSequence.current && owner === scopeRef.current && !controller.signal.aborted;
        if (!hasLoadedTasksRef.current || intent === "manual") setLoading(true);
        try {
            if (!isBackendConfigured && role === "Employee" && !currentEmployeeId) {
                setTasks([]);
                setWorkflowStateByTaskId(new Map());
                setLoadError("Your Employee login is not linked to a saved employee profile. Ask HR to complete your department assignment.");
                hasLoadedTasksRef.current = true;
                return;
            }

            let values: WorkTask[];
            if (!isBackendConfigured) {
                values = readDemoWorkTasks();
                if (role === "HR Admin") {
                    const retained = new Map(readDemoWorkInsights().map((item) => [item.workTaskId, item]));
                    setPendingHrAuditTaskIds(new Set(values.filter((task) => {
                        const ready = task.assigneeRole === "TEAM_LEAD" ? task.status === "COMPLETED"
                            : ["APPROVED", "ACKNOWLEDGED"].includes(task.status);
                        const existing = retained.get(task.id);
                        return ready && (!existing || existing.auditStatus === "REWORK_ASSIGNED");
                    }).map((task) => task.id)));
                }
                setWorkflowStateByTaskId((["Employee", "Team Lead"] as Role[]).includes(role)
                    ? new Map(readDemoWorkInsights().map((item) => [item.workTaskId, item.auditStatus]))
                    : new Map());
                setWorkspace(null);
                setRefreshWarning("");
            } else {
                const [taskResult, workspaceResult, scopeResult] = await Promise.allSettled([
                    brainServeApi.workboard(criteria, page, size, controller.signal),
                    (["HR Admin", "Team Lead"] as Role[]).includes(role)
                        ? brainServeApi.workTaskWorkspace() : Promise.resolve(null),
                    (["Manager", "Employee"] as Role[]).includes(role)
                        ? organizationApi.visibleDepartments() : Promise.resolve([] as Department[]),
                ] as const);
                if (!current()) return;
                const denied = [taskResult, workspaceResult, scopeResult].find((result, index) => result.status === "rejected" && result.reason instanceof ApiError && (result.reason.status === 401 || index === 0 && result.reason.status === 403));
                if (denied?.status === "rejected") { clearAuthority(); setLoadError("Your current account no longer has access to this work scope."); return; }
                const warnings: string[] = [];

                if (workspaceResult.status === "fulfilled") {
                    setWorkspace(workspaceResult.value);
                    if (workspaceResult.value) {
                        setScopeDepartments([{ id: workspaceResult.value.departmentId,
                            code: workspaceResult.value.departmentCode, name: workspaceResult.value.departmentName,
                            active: true, version: 0 }]);
                    }
                }
                else if ((["HR Admin", "Team Lead"] as Role[]).includes(role)) {
                    setWorkspace(null); setShowCreate(false);
                    warnings.push("The assignee list could not be refreshed.");
                }

                if (scopeResult.status === "fulfilled") {
                    if (scopeResult.value.length > 0) setScopeDepartments(scopeResult.value);
                } else if ((["Manager", "Employee"] as Role[]).includes(role)) {
                    warnings.push("The department scope could not be refreshed.");
                }

                if (taskResult.status === "rejected") {
                    const detail = taskResult.reason instanceof Error
                        ? taskResult.reason.message : "The worksheet service is temporarily unavailable.";
                    const recoverableDetail = detail.includes("did not respond within 20 seconds")
                        ? "The backend is taking longer than expected."
                        : detail;
                    if (hasLoadedTasksRef.current) {
                        setRefreshWarning(`Live refresh was interrupted. The last loaded worksheets remain visible. ${recoverableDetail}`);
                    } else {
                        setLoadError(`Worksheets could not be loaded. ${recoverableDetail}`);
                    }
                    return;
                }
                if (taskResult.value.policyVersion !== "workboard.v1") throw new Error("The Workboard response could not be verified.");
                setServerPage(taskResult.value);
                values = taskResult.value.items;
                setPendingHrAuditTaskIds(new Set(taskResult.value.items.filter((item) => item.allowedActions.includes("hr-audit")).map((item) => item.id)));
                setWorkflowStateByTaskId(new Map(taskResult.value.items.map((item) => [item.id, item.auditStatus])));
                setRefreshWarning(warnings.join(" "));
            }

            if (!current()) return;
            const scoped = !isBackendConfigured && role === "Employee"
                ? values.filter((item) => item.employeeId === currentEmployeeId)
                : (role === "Team Lead" || role === "HR Admin") && !isBackendConfigured
                    ? values.filter((item) => Boolean(scopeDepartmentIdRef.current)
                        && item.departmentId === scopeDepartmentIdRef.current)
                    : values;
            setTasks(scoped);
            setDataScope(owner);
            hasLoadedTasksRef.current = true;
            setLoadError("");
        } catch (reason) {
            if (!current()) return;
            const detail = reason instanceof Error
                ? reason.message : "The worksheet service is temporarily unavailable.";
            if (hasLoadedTasksRef.current) {
                setRefreshWarning(`Live refresh was interrupted. The last loaded worksheets remain visible. ${detail}`);
            } else {
                setLoadError(`Worksheets could not be loaded. ${detail}`);
            }
        } finally {
            if (current()) setLoading(false);
        }
    }, [currentEmployeeId, role, scopeKey, criteria, page, size, clearAuthority, scopeDepartmentIdRef]);
    useEffect(() => {
        const reset = window.setTimeout(() => { clearAuthority(); setDataScope(scopeKey); setQuery(""); setStatusFilter("ALL"); setBranchFilter("ALL"); setQueueScope("TODAY"); setQuickFilter("ALL"); setSort("DUE_DATE"); setPage(0); }, 0);
        const changed = () => { clearAuthority(); setReloadRevision((value) => value + 1); };
        window.addEventListener("brainserve:auth-session-changed", changed); window.addEventListener("brainserve:auth-session-expired", changed);
        return () => { window.clearTimeout(reset); clearAuthority(); window.removeEventListener("brainserve:auth-session-changed", changed); window.removeEventListener("brainserve:auth-session-expired", changed); };
    }, [scopeKey, clearAuthority, setExpandedTaskId]);
    useEffect(() => { const reset = window.setTimeout(() => setPage(0), 0); return () => window.clearTimeout(reset); }, [criteriaKey, size]);
    useEffect(() => {
        const refreshWhenVisible = () => {
            if (document.visibilityState === "visible") void load("background");
        };
        const initial = window.setTimeout(() => void load("initial"), 0);
        const timer = window.setInterval(() => {
            if (isWorkspaceUpdateLeader()) refreshWhenVisible();
        }, 30000);
        document.addEventListener("visibilitychange", refreshWhenVisible);
        return () => {
            window.clearTimeout(initial);
            window.clearInterval(timer);
            document.removeEventListener("visibilitychange", refreshWhenVisible);
        };
    }, [load, reloadRevision]);
    useEffect(() => {
        if (lastWorkspaceRefreshKeyRef.current === refreshKey) return;
        lastWorkspaceRefreshKeyRef.current = refreshKey;
        const refresh = window.setTimeout(() => {
            if (document.visibilityState === "visible") void load("background");
        }, 0);
        return () => window.clearTimeout(refresh);
    }, [load, refreshKey]);
    const reloadDetail = useCallback(async () => {
        const id = selectedRef.current;
        if (!id || !isBackendConfigured) return;
        const generation = lifecycle.current, owner = scopeKey, sequence = ++detailSequence.current;
        detailController.current?.abort(); const controller = new AbortController(); detailController.current = controller;
        const current = () => generation === lifecycle.current && sequence === detailSequence.current && owner === scopeRef.current && selectedRef.current === id && !controller.signal.aborted;
        try {
            const result = await brainServeApi.workboardDetail(id, controller.signal);
            if (!current()) return;
            if (result.item.id !== id) throw new Error("The selected worksheet could not be verified.");
            setDetail(result); setDetailError(""); return result;
        } catch (reason) {
            if (!current()) return;
            if (reason instanceof ApiError && [401, 403].includes(reason.status)) { clearAuthority(); setLoadError("Your current account no longer has access to this work scope."); }
            else if (reason instanceof ApiError && reason.status === 404) { setExpandedTaskId(null); setDetail(null); setActionDialog(null); setActionNote(""); setMessage("This worksheet is no longer available in your current scope."); }
            else setDetailError(reason instanceof Error ? reason.message : "The worksheet details could not be loaded.");
        }
    }, [scopeKey, clearAuthority, setExpandedTaskId]);
    useEffect(() => {
        if (!expandedTaskId) return;
        if (isBackendConfigured) void reloadDetail();
    }, [expandedTaskId, reloadDetail, reloadRevision, refreshKey, serverPage?.generatedAt]);
    const reloadPreferences = useCallback(async () => {
        const generation = lifecycle.current, owner = scopeKey, sequence = ++preferenceSequence.current;
        preferencesController.current?.abort(); const controller = new AbortController(); preferencesController.current = controller;
        try {
            const value = isBackendConfigured ? await brainServeApi.workboardPreferences(controller.signal) : readPreviewPreferences(scopeKey);
            if (generation !== lifecycle.current || sequence !== preferenceSequence.current || owner !== scopeRef.current || controller.signal.aborted) return;
            if (!validWorkboardPreferences(value)) throw new Error("The Workboard preferences could not be verified.");
            setPreferences(value); setPreferenceError("");
        } catch (reason) {
            if (generation !== lifecycle.current || sequence !== preferenceSequence.current || owner !== scopeRef.current || controller.signal.aborted) return;
            if (reason instanceof ApiError && [401, 403].includes(reason.status)) clearAuthority();
            setPreferenceError(reason instanceof Error ? reason.message : "Preferences could not be loaded.");
        }
    }, [scopeKey, clearAuthority]);
    useEffect(() => { const initial = window.setTimeout(() => void reloadPreferences(), 0); return () => window.clearTimeout(initial); }, [reloadPreferences, reloadRevision]);
    const savePreferences = async (patch: Partial<Pick<WorkboardPreferences, "layout" | "density" | "savedFilters">>) => {
        if (preferenceBusy) return;
        const generation = lifecycle.current, owner = scopeKey, sequence = ++preferenceSequence.current;
        preferencesController.current?.abort();
        setPreferenceBusy(true); setPreferenceError("");
        try {
            const updated = isBackendConfigured ? await brainServeApi.saveWorkboardPreferences({ ...preferences, ...patch })
                : writePreviewPreferences(scopeKey, { ...preferences, ...patch });
            if (generation !== lifecycle.current || sequence !== preferenceSequence.current || owner !== scopeRef.current) return;
            if (!validWorkboardPreferences(updated)) throw new Error("The saved Workboard preferences could not be verified.");
            setPreferences(updated);
        } catch (reason) {
            if (generation !== lifecycle.current || sequence !== preferenceSequence.current || owner !== scopeRef.current) return;
            if (reason instanceof ApiError && [401, 403].includes(reason.status)) clearAuthority();
            setPreferenceError(reason instanceof ApiError && reason.status === 409
                ? "Preferences changed in another window. Reload preferences, then try saving again."
                : reason instanceof Error ? reason.message : "Preferences could not be saved.");
        } finally { if (generation === lifecycle.current && owner === scopeRef.current) setPreferenceBusy(false); }
    };
    const applyCriteria = (value: WorkboardCriteria) => {
        setQueueScope(value.scope); setQuickFilter(value.quickFilter); setQuery(value.query); setStatusFilter(value.status); setBranchFilter(value.branch || "ALL"); setSort(value.sort); setPage(0);
    };
    const reloadAfterAction = async () => { await Promise.all([load(), reloadDetail()]); };
    const saveDemo = (updated: WorkTask) => {
        const all = readDemoWorkTasks().map((item) => item.id === updated.id ? updated : item);
        writeDemoWorkTasks(all);
        setTasks((items) => items.map((item) => item.id === updated.id ? updated : item));
    };
    const createTask = async (event: FormEvent<HTMLFormElement>) => {
        const generation = lifecycle.current, owner = scopeKey;
        event.preventDefault(); const form = event.currentTarget; const data = new FormData(form);
        const payload = { employeeId: String(data.get("employeeId")), title: String(data.get("title")).trim(),
            description: String(data.get("description")).trim(), dueDate: String(data.get("dueDate")) };
        setBusy("create"); setError(""); setMessage("");
        try {
            const created = isBackendConfigured ? await brainServeApi.createWorkTask(payload) : (() => {
                const selectedEmployee = employees.find((item) => (item.uuid ?? item.id) === payload.employeeId);
                const actorAccount = readDemoAccounts().find((account) => account.email.toLowerCase() === userEmail.toLowerCase());
                const selectedIsTeamLead = payload.employeeId === assignedTeamLead?.teamLeadEmployeeId;
                return {
                    id: newClientId(), departmentId: selectedEmployee?.departmentId ?? teamLeadDepartmentId ?? "",
                    employeeId: payload.employeeId,
                    teamLeadUserId: assignedTeamLead?.teamLeadUserId ?? actorAccount?.id ?? userEmail,
                    assignedByUserId: actorAccount?.id ?? userEmail,
                    assignedByRole: role === "HR Admin" ? "HR_ADMIN" as const : "TEAM_LEAD" as const,
                    assigneeRole: selectedIsTeamLead ? "TEAM_LEAD" as const : "EMPLOYEE" as const,
                    title: payload.title,
                    description: payload.description, departmentBranch: assignedDepartment?.name ?? selectedEmployee?.department ?? "Assigned department",
                    dueDate: payload.dueDate,
                    status: "ASSIGNED" as const, employeeUpdate: null, teamLeadReview: null, startedAt: null,
                    completedAt: null, approvedAt: null, acknowledgedAt: null, createdAt: new Date().toISOString(), version: 0,
                };
            })();
            if (generation !== lifecycle.current || owner !== scopeRef.current) return;
            if (!isBackendConfigured) {
                writeDemoWorkTasks([created, ...readDemoWorkTasks()]);
                const selectedEmployee = employees.find((item) => (item.uuid ?? item.id) === payload.employeeId);
                const recipient = readDemoAccounts().find((account) => account.employeeId === payload.employeeId
                    || account.email.toLowerCase() === selectedEmployee?.email.toLowerCase());
                if (recipient) {
                    const now = new Date().toISOString();
                    writeDemoInternalNotifications([{ id: newClientId(), senderUserId: created.assignedByUserId,
                        recipientUserId: recipient.id, senderName: demoSenderName(role, userEmail), recipientName: recipient.fullName,
                        message: `New ${created.departmentBranch} task sheet: ${created.title}. Due ${created.dueDate}.`,
                        deliveryStatus: "DELIVERED", sentAt: now, deliveredAt: now, readAt: null,
                        senderEmail: userEmail, recipientEmail: recipient.email }, ...readDemoInternalNotifications()]);
                }
            }
            if (isBackendConfigured) await load(); else setTasks((items) => [created, ...items]);
            if (generation !== lifecycle.current || owner !== scopeRef.current) return;
            form.reset(); setShowCreate(false);
            setMessage("Task sheet created for the selected department member, and BrainServe Internal Calls notified them immediately.");
            return true;
        } catch (reason) { if (generation === lifecycle.current && owner === scopeRef.current) failAction(reason, "The task could not be assigned."); return false; }
        finally { if (generation === lifecycle.current && owner === scopeRef.current) setBusy(""); }
    };
    const act = async (task: WorkTask, action: "start" | "complete" | "approve" | "request-changes" | "acknowledge", note = "") => {
        const generation = lifecycle.current, owner = scopeKey;
        if (["complete", "request-changes"].includes(action) && !note.trim()) {
            setError(action === "complete" ? "Describe the completed work before submitting it for review."
                : "A review note is required when requesting changes."); return false;
        }
        setBusy(`${task.id}:${action}`); setError(""); setMessage("");
        try {
            let updated: WorkTask;
            if (isBackendConfigured) updated = action === "acknowledge"
                ? await brainServeApi.acknowledgeWorkTask(task.id, task.version)
                : await brainServeApi.updateWorkTask(task.id, action, note?.trim() ?? "", task.version);
            else {
                const now = new Date().toISOString();
                const nextStatus: WorkTask["status"] = action === "start" ? "IN_PROGRESS" : action === "complete" ? "COMPLETED"
                    : action === "approve" ? "APPROVED" : action === "request-changes" ? "CHANGES_REQUESTED" : "ACKNOWLEDGED";
                updated = { ...task, status: nextStatus, version: task.version + 1,
                    employeeUpdate: ["start", "complete"].includes(action) ? note?.trim() || task.employeeUpdate : task.employeeUpdate,
                    teamLeadReview: ["approve", "request-changes"].includes(action) ? note?.trim() || task.teamLeadReview : task.teamLeadReview,
                    startedAt: action === "start" ? now : task.startedAt,
                    completedAt: action === "complete" ? now : task.completedAt,
                    approvedAt: action === "approve" ? now : task.approvedAt,
                    acknowledgedAt: action === "acknowledge" ? now : task.acknowledgedAt };
                saveDemo(updated);
                const employee = employees.find((item) => (item.uuid ?? item.id) === task.employeeId);
                const accounts = readDemoAccounts();
                const actor = accounts.find((account) => account.email.toLowerCase() === userEmail.toLowerCase());
                const recipient = role === "Employee"
                    ? accounts.find((account) => account.id === task.teamLeadUserId)
                    ?? initialStaffAccounts.find((account) => account.userId === task.teamLeadUserId)
                    : role === "Team Lead" && task.assigneeRole === "TEAM_LEAD"
                        ? accounts.find((account) => account.role === "ROLE_HR_ADMIN" && account.status === "ACTIVE")
                        : accounts.find((account) => account.employeeId === task.employeeId
                            || account.email.toLowerCase() === employee?.email.toLowerCase());
                if (recipient) {
                    const recipientId = "id" in recipient ? recipient.id : recipient.userId;
                    const recipientEmail = recipient.email;
                    writeDemoInternalNotifications([{ id: newClientId(), senderUserId: actor?.id ?? userEmail,
                        recipientUserId: recipientId, senderName: demoSenderName(role, userEmail), recipientName: recipient.fullName,
                        message: `Task sheet “${task.title}” is now ${workTaskStatusLabel(nextStatus).toLowerCase()}${note?.trim() ? `: ${note.trim()}` : "."}`,
                        deliveryStatus: "DELIVERED", sentAt: now, deliveredAt: now, readAt: null,
                        senderEmail: userEmail, recipientEmail }, ...readDemoInternalNotifications()]);
                }
            }
            if (generation !== lifecycle.current || owner !== scopeRef.current) return false;
            if (isBackendConfigured) await reloadAfterAction();
            if (generation !== lifecycle.current || owner !== scopeRef.current) return false;
            setMessage(action === "approve" ? "Task approved. The employee and assigned HR were notified."
                : action === "acknowledge" ? "Team Lead approval acknowledged." : "Task status updated and delivered to the other participant.");
            return true;
        } catch (reason) { if (generation === lifecycle.current && owner === scopeRef.current) failAction(reason, "The task action failed."); return false; }
        finally { if (generation === lifecycle.current && owner === scopeRef.current) setBusy(""); }
    };
    const assignInsightRework = async (task: WorkTask, guidance: string) => {
        const generation = lifecycle.current, owner = scopeKey;
        if (!guidance.trim()) { setError("Rework guidance is required before restarting the worksheet."); return false; }
        setBusy(`${task.id}:insight-rework`); setError(""); setMessage("");
        try {
            if (isBackendConfigured) {
                await brainServeApi.assignWorkInsightRework(task.id, guidance.trim(), task.version);
                if (generation !== lifecycle.current || owner !== scopeRef.current) return false;
                await reloadAfterAction();
            } else {
                const now = new Date().toISOString();
                const updated: WorkTask = { ...task, status: "CHANGES_REQUESTED", teamLeadReview: guidance.trim(),
                    startedAt: null, completedAt: null, approvedAt: null, acknowledgedAt: null, version: task.version + 1 };
                saveDemo(updated);
                writeDemoWorkInsights(readDemoWorkInsights().map((item) => item.workTaskId === task.id
                    ? { ...item, auditStatus: "REWORK_ASSIGNED" as const, teamLeadReworkGuidance: guidance.trim(),
                        teamLeadRespondedAt: now } : item));
                const employee = employees.find((item) => (item.uuid ?? item.id) === task.employeeId);
                const sender = readDemoAccounts().find((account) => account.email.toLowerCase() === userEmail.toLowerCase());
                const recipient = readDemoAccounts().find((account) => account.employeeId === task.employeeId
                    || account.email.toLowerCase() === employee?.email.toLowerCase());
                if (task.assigneeRole === "EMPLOYEE" && sender && recipient) writeDemoInternalNotifications([{ id: newClientId(), senderUserId: sender.id,
                    recipientUserId: recipient.id, senderName: sender.fullName, recipientName: recipient.fullName,
                    message: `Rework required for “${task.title}”. Team Lead guidance: ${guidance.trim()}`,
                    deliveryStatus: "DELIVERED", sentAt: now, deliveredAt: now, readAt: null,
                    senderEmail: sender.email, recipientEmail: recipient.email }, ...readDemoInternalNotifications()]);
            }
            if (generation !== lifecycle.current || owner !== scopeRef.current) return false;
            setMessage(task.assigneeRole === "TEAM_LEAD"
                ? "Corrective plan recorded. You can now update and resubmit the worksheet in the same audit cycle."
                : "Rework guidance sent to the employee. The worksheet is now tracked in the same audit cycle.");
            return true;
        } catch (reason) { if (generation === lifecycle.current && owner === scopeRef.current) failAction(reason, "The rework plan could not be assigned."); return false; }
        finally { if (generation === lifecycle.current && owner === scopeRef.current) setBusy(""); }
    };
    const reviseReworkSubmission = async (task: WorkTask, update: string) => {
        const generation = lifecycle.current, owner = scopeKey;
        if (!update.trim()) { setError("Describe the corrected delivery before resubmitting it."); return false; }
        setBusy(`${task.id}:revise-rework`); setError(""); setMessage("");
        try {
            if (isBackendConfigured) {
                if (task.assigneeRole === "EMPLOYEE") {
                    await brainServeApi.reviseEmployeeWorkTaskRework(task.id, update.trim(), task.version);
                } else {
                    await brainServeApi.reviseWorkInsightRework(task.id, update.trim(), task.version);
                }
                if (generation !== lifecycle.current || owner !== scopeRef.current) return false;
                await reloadAfterAction();
            } else {
                const now = new Date().toISOString();
                saveDemo({ ...task, employeeUpdate: update.trim(), completedAt: now,
                    version: task.version + 1 });
            }
            if (generation !== lifecycle.current || owner !== scopeRef.current) return false;
            setMessage(task.assigneeRole === "EMPLOYEE"
                ? "Rework submission updated. Your Team Lead can now review the corrected worksheet."
                : "Rework submission updated. HR can now re-audit the corrected worksheet.");
            return true;
        } catch (reason) {
            if (generation !== lifecycle.current || owner !== scopeRef.current) return false;
            const detail = reason instanceof Error ? reason.message : "The rework submission could not be updated.";
            failAction(reason, detail);
            return false;
        } finally { if (generation === lifecycle.current && owner === scopeRef.current) setBusy(""); }
    };
    const auditHrTask = async (task: WorkTask) => {
        const generation = lifecycle.current, owner = scopeKey;
        setBusy(`${task.id}:hr-audit`); setError(""); setMessage("");
        try {
            if (isBackendConfigured) await brainServeApi.auditWorkInsight(task.id, task.version);
            else {
                const employee = employees.find((item) => (item.uuid ?? item.id) === task.employeeId);
                const lead = staffAccounts.find((item) => item.userId === task.teamLeadUserId)
                    ?? initialStaffAccounts.find((item) => item.userId === task.teamLeadUserId);
                const now = new Date().toISOString();
                const retained = readDemoWorkInsights().filter((item) => item.workTaskId !== task.id);
                writeDemoWorkInsights([{ auditRecordId: newClientId(), workTaskId: task.id,
                    weekStart: workWeekStart(), departmentId: task.departmentId,
                    departmentName: task.departmentBranch, employeeId: task.employeeId,
                    employeeNumber: employee?.id ?? task.employeeId, employeeName: employee?.name ?? "Assigned employee",
                    teamLeadUserId: task.teamLeadUserId, teamLeadName: lead?.fullName ?? "Team Lead",
                    assignedByRole: task.assignedByRole, assigneeRole: task.assigneeRole, taskTitle: task.title,
                    taskStatus: task.status, auditStatus: "PENDING_MANAGER_APPROVAL", hrAuditedAt: now,
                    managerDecidedAt: null, managerRemarks: null, ceoDecidedAt: null, ceoRemarks: null,
                    reworkRequestedByRole: null, reworkReason: null, reworkRequestedAt: null,
                    teamLeadReworkGuidance: null, teamLeadRespondedAt: null, reworkCycle: task.reworkCycle ?? 0 }, ...retained]);
            }
            if (generation !== lifecycle.current || owner !== scopeRef.current) return false;
            if (isBackendConfigured) await reloadAfterAction();
            if (generation !== lifecycle.current || owner !== scopeRef.current) return false;
            if (!isBackendConfigured) setPendingHrAuditTaskIds((items) => { const next = new Set(items); next.delete(task.id); return next; });
            setMessage("HR audit submitted. The assigned Manager was notified for verification before CEO approval.");
            return true;
        } catch (reason) { if (generation === lifecycle.current && owner === scopeRef.current) failAction(reason, "The HR audit could not be submitted."); return false; }
        finally { if (generation === lifecycle.current && owner === scopeRef.current) setBusy(""); }
    };
    const requestHrTaskRework = async (task: WorkTask, reason: string) => {
        const generation = lifecycle.current, owner = scopeKey;
        if (!reason.trim()) { setError("Explain the flaws before returning this worksheet."); return false; }
        setBusy(`${task.id}:hr-rework`); setError(""); setMessage("");
        try {
            if (isBackendConfigured) await brainServeApi.requestWorkInsightRework(task.id, reason.trim(), task.version);
            else {
                const now = new Date().toISOString();
                saveDemo({ ...task, status: "INSIGHT_REWORK_REQUESTED", insightReviewSource: "HR_ADMIN",
                    insightReviewReason: reason.trim(), insightReviewRequestedAt: now,
                    reworkCycle: (task.reworkCycle ?? 0) + 1, version: task.version + 1 });
            }
            if (generation !== lifecycle.current || owner !== scopeRef.current) return false;
            if (!isBackendConfigured) setPendingHrAuditTaskIds((items) => { const next = new Set(items); next.delete(task.id); return next; });
            if (isBackendConfigured) await reloadAfterAction();
            if (generation !== lifecycle.current || owner !== scopeRef.current) return false;
            setMessage("Worksheet returned to the Team Lead with HR rework findings.");
            return true;
        } catch (reason) { if (generation === lifecycle.current && owner === scopeRef.current) failAction(reason, "The worksheet could not be returned."); return false; }
        finally { if (generation === lifecycle.current && owner === scopeRef.current) setBusy(""); }
    };
    const openTaskAction = (task: WorkTask, action: "start" | "complete" | "approve" | "request-changes" | "insight-rework" | "revise-rework" | "hr-rework") => {
        setConflict(false);
        setActionDialog({ task, action });
        const note = action === "insight-rework" ? task.insightReviewReason ?? "" : action === "revise-rework" ? task.employeeUpdate ?? "" : "";
        setActionNote(note); setInitialActionNote(note);
        setError(""); setMessage("");
    };
    const submitTaskAction = async (event: FormEvent<HTMLFormElement>) => {
        event.preventDefault();
        if (!actionDialog) return;
        const { task, action } = actionDialog;
        if (conflict || ("allowedActions" in task && !(task as WorkboardItem).allowedActions.includes(action))) {
            setError("This action is no longer available. Reload the current worksheet before retrying."); return;
        }
        const succeeded = action === "insight-rework" ? await assignInsightRework(task, actionNote)
            : action === "revise-rework" ? await reviseReworkSubmission(task, actionNote)
                : action === "hr-rework" ? await requestHrTaskRework(task, actionNote)
                    : await act(task, action, actionNote);
        if (succeeded) { setActionDialog(null); setActionNote(""); }
    };
    const isTeamLeadRequestedRework = (task: WorkTask) => task.assigneeRole === "EMPLOYEE"
        && Boolean(task.teamLeadReview)
        && (["CHANGES_REQUESTED", "IN_PROGRESS", "COMPLETED"] as WorkTask["status"][]).includes(task.status);
    const isInsightsReworkCycle = (task: WorkTask) => Boolean(task.insightReviewReason)
        && (task.reworkCycle ?? 0) > 0;
    const isReworkCycle = (task: WorkTask) => isTeamLeadRequestedRework(task) || isInsightsReworkCycle(task);
    const reworkFeedback = (task: WorkTask) => task.insightReviewReason
        ?? (isTeamLeadRequestedRework(task) ? task.teamLeadReview : null);
    const reworkSourceName = (task: WorkTask) => task.insightReviewSource === "CEO" ? "CEO"
        : task.insightReviewSource === "MANAGER" ? "Manager"
            : task.insightReviewReason ? "HR" : "Team Lead";
    const reworkSourceLabel = (task: WorkTask) => `${reworkSourceName(task).toUpperCase()} FEEDBACK`;
    const completingRework = actionDialog?.action === "complete"
        && isReworkCycle(actionDialog.task);
    const taskActionCopy = actionDialog ? {
        start: isReworkCycle(actionDialog.task)
            ? ["Start the required rework", "Record how you are addressing the reviewer feedback before submitting corrected evidence.", "Rework starting note", "Describe the correction now in progress", "Start rework"]
            : ["Start this worksheet", actionDialog.task.assigneeRole === "TEAM_LEAD" ? "Add a concise progress note for HR visibility." : "Add a concise progress note so your Team Lead knows how work began.", "Starting note", "Describe the first step or current focus", "Start work"],
        complete: completingRework
            ? ["Submit corrected rework", actionDialog.task.assigneeRole === "TEAM_LEAD"
                ? "This completion update is the corrected submission HR will re-audit. Include the final evidence now."
                : "This completion update is the corrected submission your Team Lead will review. Include the final evidence now.",
                "Corrected completion update", "Describe what was corrected, tested or delivered",
                actionDialog.task.assigneeRole === "TEAM_LEAD" ? "Submit for HR re-audit" : "Submit for Team Lead review"]
            : ["Submit completed work", actionDialog.task.assigneeRole === "TEAM_LEAD" ? "Summarize the result and evidence HR should audit." : "Summarize the result and evidence your Team Lead should review.", "Completion update", "What was completed, tested or delivered?", "Submit for review"],
        approve: ["Approve employee delivery", "Record the Team Lead decision before the worksheet enters HR Insights.", "Approval note", "Optional verification or quality note", "Approve worksheet"],
        "request-changes": ["Return for employee changes", "Explain exactly what the employee must correct before resubmitting.", "Required changes", "List the flaws and acceptance criteria", "Send changes"],
        "insight-rework": actionDialog.task.assigneeRole === "TEAM_LEAD"
            ? ["Start your Insights rework", "Turn the HR, Manager or CEO feedback into a corrective plan before updating the worksheet.", "Corrective plan", "Explain the correction and expected evidence", "Begin rework"]
            : ["Create an Insights rework plan", "Translate the HR, Manager or CEO feedback into clear corrective work for the assigned Employee.", "Rework guidance", "Explain the flaws, correction and expected evidence", "Assign rework"],
        "revise-rework": ["Update the rework submission", actionDialog.task.assigneeRole === "TEAM_LEAD"
            ? "Correct the completed delivery note while this rework cycle is still waiting for HR re-audit."
            : "Correct the completed delivery note while it is still waiting for Team Lead re-review.", "Corrected completion update", "Describe what was corrected, tested or delivered", "Update & resubmit"],
        "hr-rework": ["Return worksheet for rework", "Record the flaws the Team Lead must turn into a corrective plan.", "HR audit findings", "Explain the issue, required correction and acceptance evidence", "Return to Team Lead"],
    }[actionDialog.action] : null;
    const today = officeToday();
    const employeeName = (id: string) => employees.find((item) => (item.uuid ?? item.id) === id)?.name ?? "Assigned employee";
    const pendingVisitorApprovals = role === "Team Lead" ? appointments.filter((item) => item.status === "Awaiting Team Lead") : [];
    const canProgress = (task: WorkTask) => role === "Employee" && task.assigneeRole === "EMPLOYEE"
        || role === "Team Lead" && task.assigneeRole === "TEAM_LEAD";
    const canReviseRework = (task: WorkTask) => (role === "Team Lead"
            && task.assigneeRole === "TEAM_LEAD"
            && task.status === "COMPLETED"
            && isInsightsReworkCycle(task)
            && workflowStateByTaskId.get(task.id) === "REWORK_ASSIGNED")
        || (role === "Employee"
            && task.assigneeRole === "EMPLOYEE"
            && task.status === "COMPLETED"
            && isReworkCycle(task));
    const taskStageLabel = (task: WorkTask) => {
        const workflowState = "auditStatus" in task ? (task as WorkboardItem).auditStatus : workflowStateByTaskId.get(task.id);
        if (workflowState === "CEO_APPROVED") return "Completed";
        if (workflowState === "PENDING_CEO_APPROVAL") return "Awaiting CEO approval";
        if (workflowState === "PENDING_MANAGER_APPROVAL") return "Awaiting Manager review";
        if (["HR_REWORK_REQUESTED", "MANAGER_REWORK_REQUESTED", "CEO_REWORK_REQUESTED"].includes(workflowState ?? "")) {
            return "Awaiting Team Lead rework plan";
        }
        if (workflowState === "REWORK_ASSIGNED") {
            if (task.assigneeRole === "TEAM_LEAD" && task.status === "COMPLETED") return "Awaiting HR re-audit";
            if (task.assigneeRole === "EMPLOYEE" && task.status === "COMPLETED") return "Awaiting Team Lead re-review";
            if (task.assigneeRole === "EMPLOYEE" && ["APPROVED", "ACKNOWLEDGED"].includes(task.status)) return "Awaiting HR re-audit";
            return "Rework in progress";
        }
        if (task.status === "COMPLETED" && isTeamLeadRequestedRework(task)) return "Awaiting Team Lead re-review";
        return workTaskStatusLabel(task.status);
    };
    const previewAudits = !isBackendConfigured ? new Map(readDemoWorkInsights().map((item) => [item.workTaskId, item])) : new Map<string, WorkInsight>();
    const previewItems: WorkboardItem[] = !isBackendConfigured ? tasks.map((task) => {
        const allowedActions: WorkboardItem["allowedActions"] = [];
        if (canProgress(task) && ["ASSIGNED", "CHANGES_REQUESTED"].includes(task.status)) allowedActions.push("start");
        if (canProgress(task) && ["ASSIGNED", "IN_PROGRESS", "CHANGES_REQUESTED"].includes(task.status)) allowedActions.push("complete");
        if (role === "Team Lead" && task.assigneeRole === "EMPLOYEE" && task.status === "COMPLETED") allowedActions.push("request-changes", "approve");
        if (role === "Team Lead" && task.status === "INSIGHT_REWORK_REQUESTED") allowedActions.push("insight-rework");
        if (canReviseRework(task)) allowedActions.push("revise-rework");
        if (role === "Employee" && task.status === "APPROVED") allowedActions.push("acknowledge");
        if (role === "HR Admin" && pendingHrAuditTaskIds.has(task.id)) allowedActions.push("hr-rework", "hr-audit");
        const audit = previewAudits.get(task.id);
        if (role === "Manager" && audit?.auditStatus === "PENDING_MANAGER_APPROVAL") allowedActions.push("open-oversight");
        const auditStatus = audit?.auditStatus ?? "NOT_AUDITED";
        const lane = auditStatus === "CEO_APPROVED" && task.status !== "APPROVED" ? "CLOSED" : ["CHANGES_REQUESTED", "INSIGHT_REWORK_REQUESTED"].includes(task.status) || isReworkCycle(task) && task.status === "IN_PROGRESS" ? "REWORK"
            : ["COMPLETED", "APPROVED", "ACKNOWLEDGED"].includes(task.status) ? "REVIEW" : "DELIVERY";
        const nextActor = auditStatus === "CEO_APPROVED" ? (task.status === "APPROVED" ? "Employee acknowledgement" : null)
            : auditStatus === "PENDING_CEO_APPROVAL" ? "CEO" : auditStatus === "PENDING_MANAGER_APPROVAL" ? "Manager"
                : task.status === "INSIGHT_REWORK_REQUESTED" ? "Team Lead" : task.status === "COMPLETED" ? (task.assigneeRole === "TEAM_LEAD" ? "HR Admin" : "Team Lead")
                    : ["APPROVED", "ACKNOWLEDGED"].includes(task.status) ? "HR Admin" : task.assigneeRole === "TEAM_LEAD" ? "Team Lead" : "Employee";
        return { ...task, assigneeName: employeeName(task.employeeId), auditStatus, auditRecordId: audit?.auditRecordId ?? null, auditVersion: null,
            updatedAt: task.acknowledgedAt ?? task.approvedAt ?? task.completedAt ?? task.startedAt ?? task.createdAt,
            submissionVersion: null, priority: null, blocked: null, allowedActions, lane, nextActor };
    }) : [];
    const boardPage = dataScope === scopeKey ? isBackendConfigured ? serverPage : previewWorkboardPage(previewItems, criteria, page, size, today, role === "Employee") : null;
    const previewSelected = previewItems.find((item) => item.id === expandedTaskId);
    const selectedDetail = dataScope !== scopeKey ? null : isBackendConfigured ? detail?.item.id === expandedTaskId ? detail : null : previewSelected ? {
        item: previewSelected, historyTruncated: false,
        history: ([ ["created", "Worksheet assigned", previewSelected.createdAt, null], ["started", "Work started", previewSelected.startedAt, null],
            ["submitted", "Current work submitted", previewSelected.completedAt, previewSelected.employeeUpdate], ["approved", "Delivery approved", previewSelected.approvedAt, previewSelected.teamLeadReview],
            ["acknowledged", "Approval acknowledged", previewSelected.acknowledgedAt, null] ] as const)
            .filter((event) => Boolean(event[2])).map(([id, title, occurredAt, note]) => ({ id, title, occurredAt: occurredAt!, actorRole: null, note }))
    } : null;
    const reloadConflict = async () => {
        const generation = lifecycle.current, owner = scopeKey;
        const current = selectedRef.current;
        if (!current) return;
        // Preserve the user's note; only replace the observed task version after an explicit reload.
        const updated = isBackendConfigured ? await reloadDetail() : selectedDetail;
        if (generation !== lifecycle.current || owner !== scopeRef.current || current !== selectedRef.current || !updated) return;
        await load();
        if (generation !== lifecycle.current || owner !== scopeRef.current || current !== selectedRef.current) return;
        setActionDialog((value) => value && value.task.id === current ? { ...value, task: updated.item } : value); setConflict(false);
        setError(actionDialog && !updated.item.allowedActions.includes(actionDialog.action) ? "This action is no longer available. Your unsaved note is kept." : "");
    };
    return { tasks: dataScope === scopeKey ? tasks : [], showCreate: dataScope === scopeKey && showCreate, setShowCreate,
        query: dataScope === scopeKey ? query : "", setQuery, statusFilter: dataScope === scopeKey ? statusFilter : "ALL", setStatusFilter,
        branchFilter: dataScope === scopeKey ? branchFilter : "ALL", setBranchFilter,
        queueScope, setQueueScope, quickFilter, setQuickFilter, sort, setSort, page, setPage, size, setSize, criteria, applyCriteria, resetCriteria: () => applyCriteria(defaultCriteria),
        boardPage, preferences: dataScope === scopeKey ? preferences : defaultPreferences, savePreferences, preferenceBusy, preferenceError, reloadPreferences,
        selectedDetail, detailError, reloadDetail, conflict, reloadConflict, actionDirty: Boolean(actionDialog && actionNote !== initialActionNote),
        expandedTaskId: dataScope === scopeKey ? expandedTaskId : null, setExpandedTaskId, busy, message, error, loadError, refreshWarning, loading, pendingHrAuditTaskIds,
        actionDialog: dataScope === scopeKey ? actionDialog : null, setActionDialog, actionNote: dataScope === scopeKey ? actionNote : "", setActionNote,
        assignedDepartment: dataScope === scopeKey ? assignedDepartment : undefined, eligibleAssignees: dataScope === scopeKey ? eligibleAssignees : [],
        canCreateTask: dataScope === scopeKey && (isBackendConfigured ? workspace !== null : Boolean(assignedDepartment && eligibleAssignees.length)), createTask, act, auditHrTask,
        openTaskAction, submitTaskAction, isInsightsReworkCycle, isReworkCycle, reworkFeedback, reworkSourceName, reworkSourceLabel, taskActionCopy,
        employeeName, pendingVisitorApprovals: dataScope === scopeKey ? pendingVisitorApprovals : [], canProgress, canReviseRework, taskStageLabel };
}
