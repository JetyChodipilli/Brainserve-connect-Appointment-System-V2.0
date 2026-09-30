"use client";

import { organizationApi } from "../../organization/api/organization-api";
import { workboardApi as brainServeApi } from "../api/workboard-api";
import { isBackendConfigured, isWorkspaceUpdateLeader } from "../../../lib/api-client";
import type { WorkInsight, WorkTask, WorkTaskWorkspace } from "../types/workboard";
import { officeToday } from "../../../lib/appointments";
import { readDemoAccounts } from "../../../preview/accounts";
import { initialStaffAccounts } from "../../../preview/fixtures/workspace";
import { demoSenderName, readDemoInternalNotifications, writeDemoInternalNotifications } from "../../../preview/notifications";
import { readDemoWorkInsights, readDemoWorkTasks, writeDemoWorkInsights, writeDemoWorkTasks } from "../preview";
import { type Department, type Role } from "../../../types/workspace";
import { newClientId } from "../../../utils/ids";
import { officeDateFromInstant, workTaskStatusLabel, workWeekStart } from "../utils/work-utils";
import { type FormEvent, useCallback, useEffect, useRef, useState } from "react";
import type { WorkboardProps } from "../types/props";

export function useWorkboard({ role, refreshKey, userEmail, employees, staffAccounts, departments, teamLeadAssignments, appointments }: WorkboardProps) {
    const [tasks, setTasks] = useState<WorkTask[]>([]);
    const [showCreate, setShowCreate] = useState(false);
    const [query, setQuery] = useState("");
    const [statusFilter, setStatusFilter] = useState("ALL");
    const [branchFilter, setBranchFilter] = useState("ALL");
    const [queueScope, setQueueScope] = useState<"TODAY" | "CARRY_FORWARD">("TODAY");
    const [expandedTaskId, setExpandedTaskId] = useState<string | null>(null);
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
    const loadInFlightRef = useRef(false);
    const hasLoadedTasksRef = useRef(false);
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
        if (loadInFlightRef.current) return;
        loadInFlightRef.current = true;
        if (!hasLoadedTasksRef.current || intent === "manual") setLoading(true);
        try {
            if (role === "Employee" && !currentEmployeeId) {
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
                const [taskResult, workspaceResult] = await Promise.allSettled([
                    brainServeApi.workTasks(),
                    (["HR Admin", "Team Lead"] as Role[]).includes(role)
                        ? brainServeApi.workTaskWorkspace() : Promise.resolve(null),
                ] as const);
                const [auditResult, scopeResult, workflowStateResult] = await Promise.allSettled([
                    role === "HR Admin" ? brainServeApi.pendingHrWorkInsights() : Promise.resolve([]),
                    (["Manager", "Employee"] as Role[]).includes(role)
                        ? organizationApi.visibleDepartments() : Promise.resolve([] as Department[]),
                    (["Employee", "Team Lead"] as Role[]).includes(role)
                        ? brainServeApi.workTaskWorkflowStates() : Promise.resolve([]),
                ] as const);
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
                    warnings.push("The assignee list could not be refreshed.");
                }

                if (scopeResult.status === "fulfilled") {
                    if (scopeResult.value.length > 0) setScopeDepartments(scopeResult.value);
                } else if ((["Manager", "Employee"] as Role[]).includes(role)) {
                    warnings.push("The department scope could not be refreshed.");
                }

                if (role === "HR Admin") {
                    if (auditResult.status === "fulfilled") {
                        setPendingHrAuditTaskIds(new Set(auditResult.value.map((item) => item.workTaskId)));
                    } else warnings.push("The HR audit queue could not be refreshed.");
                }

                if ((["Employee", "Team Lead"] as Role[]).includes(role)) {
                    if (workflowStateResult.status === "fulfilled") {
                        setWorkflowStateByTaskId(new Map(workflowStateResult.value
                            .map((item) => [item.workTaskId, item.auditStatus])));
                    } else {
                        setWorkflowStateByTaskId(new Map());
                        warnings.push("Approval and rework stages could not be refreshed, so submitted evidence is locked for safety.");
                    }
                } else setWorkflowStateByTaskId(new Map());

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
                values = taskResult.value;
                setRefreshWarning(warnings.join(" "));
            }

            const scoped = role === "Employee"
                ? values.filter((item) => item.employeeId === currentEmployeeId)
                : (role === "Team Lead" || role === "HR Admin") && !isBackendConfigured
                    ? values.filter((item) => Boolean(scopeDepartmentIdRef.current)
                        && item.departmentId === scopeDepartmentIdRef.current)
                    : values;
            setTasks(scoped);
            hasLoadedTasksRef.current = true;
            setLoadError("");
        } catch (reason) {
            const detail = reason instanceof Error
                ? reason.message : "The worksheet service is temporarily unavailable.";
            if (hasLoadedTasksRef.current) {
                setRefreshWarning(`Live refresh was interrupted. The last loaded worksheets remain visible. ${detail}`);
            } else {
                setLoadError(`Worksheets could not be loaded. ${detail}`);
            }
        } finally {
            loadInFlightRef.current = false;
            setLoading(false);
        }
    }, [currentEmployeeId, role, scopeDepartmentIdRef]);
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
    }, [load]);
    useEffect(() => {
        if (lastWorkspaceRefreshKeyRef.current === refreshKey) return;
        lastWorkspaceRefreshKeyRef.current = refreshKey;
        const refresh = window.setTimeout(() => {
            if (document.visibilityState === "visible") void load("background");
        }, 0);
        return () => window.clearTimeout(refresh);
    }, [load, refreshKey]);
    const saveDemo = (updated: WorkTask) => {
        const all = readDemoWorkTasks().map((item) => item.id === updated.id ? updated : item);
        writeDemoWorkTasks(all);
        setTasks((items) => items.map((item) => item.id === updated.id ? updated : item));
    };
    const createTask = async (event: FormEvent<HTMLFormElement>) => {
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
            setTasks((items) => [created, ...items]); form.reset(); setShowCreate(false);
            setMessage("Task sheet created for the selected department member, and BrainServe Internal Calls notified them immediately.");
        } catch (reason) { setError(reason instanceof Error ? reason.message : "The task could not be assigned."); }
        finally { setBusy(""); }
    };
    const act = async (task: WorkTask, action: "start" | "complete" | "approve" | "request-changes" | "acknowledge", note = "") => {
        if (["complete", "request-changes"].includes(action) && !note.trim()) {
            setError(action === "complete" ? "Describe the completed work before submitting it for review."
                : "A review note is required when requesting changes."); return false;
        }
        setBusy(`${task.id}:${action}`); setError(""); setMessage("");
        try {
            let updated: WorkTask;
            if (isBackendConfigured) updated = action === "acknowledge"
                ? await brainServeApi.acknowledgeWorkTask(task.id)
                : await brainServeApi.updateWorkTask(task.id, action, note?.trim() ?? "");
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
            if (isBackendConfigured) setTasks((items) => items.map((item) => item.id === updated.id ? updated : item));
            setMessage(action === "approve" ? "Task approved. The employee and assigned HR were notified."
                : action === "acknowledge" ? "Team Lead approval acknowledged." : "Task status updated and delivered to the other participant.");
            return true;
        } catch (reason) { setError(reason instanceof Error ? reason.message : "The task action failed."); return false; }
        finally { setBusy(""); }
    };
    const assignInsightRework = async (task: WorkTask, guidance: string) => {
        if (!guidance.trim()) { setError("Rework guidance is required before restarting the worksheet."); return false; }
        setBusy(`${task.id}:insight-rework`); setError(""); setMessage("");
        try {
            if (isBackendConfigured) {
                await brainServeApi.assignWorkInsightRework(task.id, guidance.trim());
                await load();
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
            setMessage(task.assigneeRole === "TEAM_LEAD"
                ? "Corrective plan recorded. You can now update and resubmit the worksheet in the same audit cycle."
                : "Rework guidance sent to the employee. The worksheet is now tracked in the same audit cycle.");
            return true;
        } catch (reason) { setError(reason instanceof Error ? reason.message : "The rework plan could not be assigned."); return false; }
        finally { setBusy(""); }
    };
    const reviseReworkSubmission = async (task: WorkTask, update: string) => {
        if (!update.trim()) { setError("Describe the corrected delivery before resubmitting it."); return false; }
        setBusy(`${task.id}:revise-rework`); setError(""); setMessage("");
        try {
            if (isBackendConfigured) {
                if (task.assigneeRole === "EMPLOYEE") {
                    const updated = await brainServeApi.reviseEmployeeWorkTaskRework(task.id, update.trim());
                    setTasks((items) => items.map((item) => item.id === updated.id ? updated : item));
                } else {
                    await brainServeApi.reviseWorkInsightRework(task.id, update.trim());
                    await load();
                }
            } else {
                const now = new Date().toISOString();
                saveDemo({ ...task, employeeUpdate: update.trim(), completedAt: now,
                    version: task.version + 1 });
            }
            setMessage(task.assigneeRole === "EMPLOYEE"
                ? "Rework submission updated. Your Team Lead can now review the corrected worksheet."
                : "Rework submission updated. HR can now re-audit the corrected worksheet.");
            return true;
        } catch (reason) {
            const detail = reason instanceof Error ? reason.message : "The rework submission could not be updated.";
            if (detail.includes("can no longer be updated")) {
                setActionDialog(null);
                setActionNote("");
                setMessage(task.assigneeRole === "EMPLOYEE"
                    ? "Your Team Lead has already reviewed this worksheet. The submitted evidence is locked and approval review is continuing."
                    : "HR has already re-audited this worksheet. The submitted evidence is locked and approval review is continuing.");
                void load("background");
                return false;
            }
            setError(detail);
            return false;
        } finally { setBusy(""); }
    };
    const auditHrTask = async (task: WorkTask) => {
        setBusy(`${task.id}:hr-audit`); setError(""); setMessage("");
        try {
            if (isBackendConfigured) await brainServeApi.auditWorkInsight(task.id);
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
            setPendingHrAuditTaskIds((items) => { const next = new Set(items); next.delete(task.id); return next; });
            setMessage("HR audit submitted. The assigned Manager was notified for verification before CEO approval.");
            return true;
        } catch (reason) { setError(reason instanceof Error ? reason.message : "The HR audit could not be submitted."); return false; }
        finally { setBusy(""); }
    };
    const requestHrTaskRework = async (task: WorkTask, reason: string) => {
        if (!reason.trim()) { setError("Explain the flaws before returning this worksheet."); return false; }
        setBusy(`${task.id}:hr-rework`); setError(""); setMessage("");
        try {
            if (isBackendConfigured) await brainServeApi.requestWorkInsightRework(task.id, reason.trim());
            else {
                const now = new Date().toISOString();
                saveDemo({ ...task, status: "INSIGHT_REWORK_REQUESTED", insightReviewSource: "HR_ADMIN",
                    insightReviewReason: reason.trim(), insightReviewRequestedAt: now,
                    reworkCycle: (task.reworkCycle ?? 0) + 1, version: task.version + 1 });
            }
            setPendingHrAuditTaskIds((items) => { const next = new Set(items); next.delete(task.id); return next; });
            if (isBackendConfigured) await load();
            setMessage("Worksheet returned to the Team Lead with HR rework findings.");
            return true;
        } catch (reason) { setError(reason instanceof Error ? reason.message : "The worksheet could not be returned."); return false; }
        finally { setBusy(""); }
    };
    const openTaskAction = (task: WorkTask, action: "start" | "complete" | "approve" | "request-changes" | "insight-rework" | "revise-rework" | "hr-rework") => {
        setActionDialog({ task, action });
        setActionNote(action === "insight-rework" ? task.insightReviewReason ?? ""
            : action === "revise-rework" ? task.employeeUpdate ?? "" : "");
        setError(""); setMessage("");
    };
    const submitTaskAction = async (event: FormEvent<HTMLFormElement>) => {
        event.preventDefault();
        if (!actionDialog) return;
        const { task, action } = actionDialog;
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
    const isTodayTask = (task: WorkTask) => officeDateFromInstant(task.createdAt) === today;
    const isOpenCarryForwardTask = (task: WorkTask) => {
        if (isTodayTask(task)) return false;
        const workflowState = workflowStateByTaskId.get(task.id);
        if (workflowState === "CEO_APPROVED") {
            return role === "Employee" && task.status === "APPROVED";
        }
        if (role === "HR Admin" && pendingHrAuditTaskIds.has(task.id)) return true;
        if (role === "Employee" && task.status === "APPROVED") return true;
        return (["ASSIGNED", "IN_PROGRESS", "COMPLETED", "CHANGES_REQUESTED", "INSIGHT_REWORK_REQUESTED"] as WorkTask["status"][])
            .includes(task.status);
    };
    const todayTasks = tasks.filter(isTodayTask);
    const carryForwardTasks = tasks.filter(isOpenCarryForwardTask);
    const queueTasks = queueScope === "TODAY" ? todayTasks : carryForwardTasks;
    const branches = [...new Set([assignedDepartment?.name, ...tasks.map((item) => item.departmentBranch)]
        .filter((item): item is string => Boolean(item)))].sort();
    const filtered = queueTasks.filter((item) => (statusFilter === "ALL" || item.status === statusFilter)
        && (branchFilter === "ALL" || item.departmentBranch === branchFilter)
        && `${item.title} ${item.description} ${item.departmentBranch}`.toLowerCase().includes(query.toLowerCase()));
    const employeeName = (id: string) => employees.find((item) => (item.uuid ?? item.id) === id)?.name ?? "Assigned employee";
    const pendingVisitorApprovals = role === "Team Lead" ? appointments.filter((item) => item.status === "Awaiting Team Lead") : [];
    const insightReworkQueue = role === "Team Lead" ? tasks.filter((item) => item.status === "INSIGHT_REWORK_REQUESTED") : [];
    const metric = (statuses: WorkTask["status"][]) => queueTasks.filter((item) => statuses.includes(item.status)).length;
    const pendingHrAuditCount = queueTasks.filter((item) => pendingHrAuditTaskIds.has(item.id)).length;
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
        const workflowState = workflowStateByTaskId.get(task.id);
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
    return { tasks, showCreate, setShowCreate, query, setQuery, statusFilter, setStatusFilter, branchFilter, setBranchFilter, queueScope, setQueueScope, expandedTaskId, setExpandedTaskId, busy, message, error, loadError, refreshWarning, loading, pendingHrAuditTaskIds, actionDialog, setActionDialog, actionNote, setActionNote, assignedDepartment, eligibleAssignees, createTask, act, auditHrTask, openTaskAction, submitTaskAction, isInsightsReworkCycle, isReworkCycle, reworkFeedback, reworkSourceName, reworkSourceLabel, taskActionCopy, isTodayTask, todayTasks, carryForwardTasks, queueTasks, branches, filtered, employeeName, pendingVisitorApprovals, insightReworkQueue, metric, pendingHrAuditCount, canProgress, canReviseRework, taskStageLabel };
}
