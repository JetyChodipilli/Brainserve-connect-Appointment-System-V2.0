"use client";

import {
    brainServeApi,
    isBackendConfigured,
    isWorkspaceUpdateLeader,
    type StaffAccount,
    type TeamLeadAssignment,
    type WorkInsight,
    type WorkTask,
    type WorkTaskWorkspace,
} from "../../lib/api";
import { nextBusinessDays, officeToday } from "../../lib/appointments";
import { readDemoAccounts } from "../../preview/accounts";
import { initialStaffAccounts } from "../../preview/fixtures/workspace";
import { demoSenderName, readDemoInternalNotifications, writeDemoInternalNotifications } from "../../preview/notifications";
import { readDemoWorkInsights, readDemoWorkTasks, writeDemoWorkInsights, writeDemoWorkTasks } from "../../preview/work";
import { PageTitle } from "../../shared/components/page-title";
import { type Appointment, type Department, type Employee, type Role, type View } from "../../shared/types/workspace";
import { newClientId } from "../../shared/utils/ids";
import { visitorInitials } from "../appointments/appointment-utils";
import { WorkTaskPill } from "./components/work-task-pill";
import { officeDateFromInstant, workTaskStatusLabel, workWeekStart } from "./work-utils";
import {
    ArrowRight,
    BadgeCheck,
    BriefcaseBusiness,
    Building2,
    CalendarDays,
    Check,
    CheckCircle2,
    ChevronRight,
    CircleUserRound,
    Clock3,
    FileClock,
    FileText,
    MessageSquare,
    RotateCcw,
    Search,
    Send,
    ShieldCheck,
    X,
} from "lucide-react";
import { type FormEvent, useCallback, useEffect, useRef, useState } from "react";

export function WorkBoard({ role, refreshKey, userEmail, employees, staffAccounts, departments, teamLeadAssignments, appointments, decideAppointment, onNavigate }: {
    role: Role; userEmail: string; employees: Employee[]; departments: Department[];
    refreshKey: number;
    staffAccounts: StaffAccount[];
    teamLeadAssignments: TeamLeadAssignment[]; appointments: Appointment[];
    decideAppointment: (id: string, decision: "approve" | "reject") => Promise<void>;
    onNavigate: (view: View) => void;
}) {
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
                        ? brainServeApi.visibleDepartments() : Promise.resolve([] as Department[]),
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

    return <section className="work-board-page">
        <PageTitle eyebrow="DEPARTMENT TASK SHEETS" title={role === "HR Admin" ? "Department work board" : role === "Team Lead" ? "Team task sheets" : role === "Manager" ? "Department work oversight" : "My task sheets"}
                   detail={role === "HR Admin" ? "Assign work to active Employees or your department Team Lead, then audit completed delivery before Manager and CEO review."
                       : role === "Team Lead" ? "Assign work to Employees, review their delivery, and complete worksheets assigned to you by HR."
                           : role === "Manager" ? "Monitor task delivery in your assigned department. Verify HR-audited work in Work oversight before it reaches the CEO."
                               : "Only task sheets assigned to your Employee profile appear here. Other employees’ work is never shown."}
                   action={role === "Manager" ? <button className="button button-primary" onClick={() => onNavigate("insights")}><ShieldCheck size={17} /> Open work oversight</button>
                       : (["HR Admin", "Team Lead"] as Role[]).includes(role) && <button className="button button-primary" onClick={() => setShowCreate((value) => !value)}><FileText size={17} /> {showCreate ? "Close task form" : "Create task sheet"}</button>} />
        <div className="work-notification-note glass-panel"><MessageSquare size={19} /><span><strong>Visitor updates stay in Notifications</strong><small>Appointments no longer occupy Employee or Team Lead navigation. HR visitor cards and workflow decisions are delivered through the internal message service.</small></span></div>
        {role === "Team Lead" && insightReworkQueue.length > 0 && <section className="work-rework-alert glass-panel"><span><RotateCcw size={20} /></span><div><small>INSIGHTS ACTION REQUIRED</small><strong>{insightReworkQueue.length} worksheet{insightReworkQueue.length === 1 ? "" : "s"} returned for rework</strong><p>Review the HR, Manager or CEO feedback and create a corrective plan. The assignee cannot restart until you provide guidance.</p></div><button className="button button-primary" onClick={() => { setQueueScope(insightReworkQueue.some(isTodayTask) ? "TODAY" : "CARRY_FORWARD"); setStatusFilter("INSIGHT_REWORK_REQUESTED"); setBranchFilter("ALL"); window.scrollTo({ top: 560, behavior: "smooth" }); }}>Review returned work</button></section>}
        <section className="work-metrics glass-panel">
            <div><span>{queueScope === "TODAY" ? "Today’s sheets" : "Open carry-forward"}</span><strong>{queueTasks.length}</strong><small>{queueScope === "TODAY" ? "Created today" : "Older work still requiring action"}</small></div><i />
            <div><span>In progress</span><strong>{metric(["IN_PROGRESS", "CHANGES_REQUESTED"])}</strong><small>Currently being worked</small></div><i />
            <div><span>Awaiting review</span><strong>{role === "HR Admin" ? pendingHrAuditCount : metric(["COMPLETED"])}</strong><small>{role === "HR Admin" ? "HR audit action required" : role === "Manager" ? "Review progress before HR handoff" : "Team Lead action required"}</small></div><i />
            <div><span>Insights rework</span><strong>{metric(["INSIGHT_REWORK_REQUESTED"])}</strong><small>HR, Manager or CEO feedback</small></div>
        </section>
        {showCreate && <form className="work-create-form task-sheet-form panel glass-panel" onSubmit={createTask}>
            <div className="panel-heading"><div><span>NEW TASK SHEET</span><h2>Create a department worksheet</h2><p>{role === "HR Admin" ? "Select an active Employee or your department Team Lead. A Team Lead’s own delivery goes directly to HR audit and can never be self-approved." : "Select one active Employee in your department. Team Leads cannot assign a worksheet to themselves."}</p></div><FileText size={22} /></div>
            <div className="modal-form-grid"><label>Department assignee<select name="employeeId" required defaultValue=""><option value="" disabled>Select an eligible department member</option>{eligibleAssignees.map((item) => <option key={item.employeeId} value={item.employeeId}>{item.displayName} · {item.role === "TEAM_LEAD" ? "Team Lead" : item.designation || "Employee"}</option>)}</select></label>
                <label>Department / branch<input value={assignedDepartment ? `${assignedDepartment.name} · ${assignedDepartment.code}` : "No department assigned"} readOnly aria-readonly="true" /></label>
                <label>Task<input name="title" minLength={3} maxLength={160} required placeholder="What should the employee complete?" /></label>
                <label>Due date<input name="dueDate" type="date" min={officeToday()} defaultValue={nextBusinessDays(3)[0]} required /></label>
                <label className="full-field">Worksheet instructions<textarea name="description" minLength={5} maxLength={1000} required placeholder="Describe the work, expected result and acceptance criteria" /></label></div>
            <div className="modal-actions"><button type="button" className="button button-secondary" onClick={() => setShowCreate(false)}>Cancel</button><button className="button button-primary" disabled={busy === "create" || eligibleAssignees.length === 0 || !assignedDepartment}><Send size={16} />{busy === "create" ? "Creating…" : "Create sheet & notify"}</button></div>
            {!assignedDepartment && <div className="login-error" role="alert">Your account has no active department assignment. Complete the role-to-department assignment before creating work.</div>}
            {assignedDepartment && eligibleAssignees.length === 0 && <div className="login-error" role="alert">No eligible active Employee or Team Lead login is available in {assignedDepartment.name}.</div>}
        </form>}
        <section className="work-period-panel glass-panel" aria-label="Worksheet period">
            <div className="work-period-tabs" role="group" aria-label="Choose worksheet period">
                <button type="button" className={queueScope === "TODAY" ? "active" : ""} aria-pressed={queueScope === "TODAY"} onClick={() => { setQueueScope("TODAY"); setBranchFilter("ALL"); setStatusFilter("ALL"); setExpandedTaskId(null); }}><CalendarDays size={17} /><span><strong>Today</strong><small>Current worksheets</small></span><b>{todayTasks.length}</b></button>
                <button type="button" className={queueScope === "CARRY_FORWARD" ? "active" : ""} aria-pressed={queueScope === "CARRY_FORWARD"} onClick={() => { setQueueScope("CARRY_FORWARD"); setBranchFilter("ALL"); setStatusFilter("ALL"); setExpandedTaskId(null); }}><FileClock size={17} /><span><strong>Open carry-forward</strong><small>Older work requiring action</small></span><b>{carryForwardTasks.length}</b></button>
            </div>
            <div className="work-scope-summary"><Building2 size={17} aria-hidden="true" /><span><small>DEPARTMENT SCOPE</small><strong>{assignedDepartment ? `${assignedDepartment.name} · ${assignedDepartment.code}` : "Department assignment unavailable"}</strong></span></div>
            <p>Closed older worksheets stay stored for governance and audit, but no longer crowd the daily work board.</p>
        </section>
        <div className="work-toolbar glass-panel"><div><Search size={17} /><input value={query} onChange={(event) => setQuery(event.target.value)} placeholder={queueScope === "TODAY" ? "Search today’s worksheets" : "Search open carry-forward"} /></div><select aria-label="Department scope" value={branchFilter} onChange={(event) => setBranchFilter(event.target.value)} disabled={branches.length <= 1}><option value="ALL">{assignedDepartment ? assignedDepartment.name : "Assigned department"}</option>{branches.length > 1 && branches.map((item) => <option key={item}>{item}</option>)}</select><select aria-label="Worksheet status" value={statusFilter} onChange={(event) => setStatusFilter(event.target.value)}><option value="ALL">All statuses</option>{(["ASSIGNED", "IN_PROGRESS", "COMPLETED", "CHANGES_REQUESTED", "INSIGHT_REWORK_REQUESTED", "APPROVED", "ACKNOWLEDGED"] as WorkTask["status"][]).map((item) => <option key={item} value={item}>{workTaskStatusLabel(item)}</option>)}</select></div>
        {(loadError || refreshWarning) && <div className={`work-refresh-state ${loadError ? "is-error" : "is-warning"}`} role={loadError ? "alert" : "status"}><RotateCcw size={17} aria-hidden="true" /><span><strong>{loadError ? "Work Board is reconnecting" : "Showing the last successful update"}</strong><small>{loadError || refreshWarning} The board refreshes automatically when the service recovers.</small></span></div>}
        <div className="task-sheet-grid">
            {loading && tasks.length === 0 && <div className="empty-state task-sheet-empty work-loading-state" role="status" aria-live="polite"><RotateCcw size={29} /><strong>Loading your department worksheets</strong><small>Existing records will stay visible during future background refreshes.</small></div>}
            {filtered.map((task) => {
                const expanded = expandedTaskId === task.id;
                const detailsId = `work-task-${task.id}-details`;
                const stageLabel = taskStageLabel(task);
                const feedback = reworkFeedback(task);
                return <article className={`task-sheet-card glass-panel${expanded ? " is-expanded" : ""}`} key={task.id}
                                aria-labelledby={`work-task-${task.id}-title`}>
                    <header><div><span className="task-sheet-label"><FileText size={14} /> TASK SHEET</span><small>#{task.id.slice(-8).toUpperCase()}</small></div><WorkTaskPill status={task.status} label={stageLabel} /></header>
                    <div className="task-sheet-summary">
                        <span className="work-category">{task.departmentBranch}</span>
                        <div className="task-sheet-summary-main">
                            <span className="task-sheet-summary-icon" aria-hidden="true"><BriefcaseBusiness size={17} /></span>
                            <div><span>WORK TO COMPLETE</span><h2 id={`work-task-${task.id}-title`}>{task.title}</h2><p>{task.description}</p></div>
                        </div>
                        <div className="task-sheet-summary-meta">
                            <span><CircleUserRound size={15} aria-hidden="true" /><small>Assigned to</small><strong>{role === "Employee" ? "You" : employeeName(task.employeeId)}</strong></span>
                            <span><CalendarDays size={15} aria-hidden="true" /><small>Due</small><strong>{new Date(`${task.dueDate}T00:00:00`).toLocaleDateString("en-IN", { day: "numeric", month: "short" })}</strong></span>
                        </div>
                        <div className="task-sheet-summary-alert-slot">{feedback && <span className="task-sheet-rework-flag"><RotateCcw size={14} /> Rework feedback{isInsightsReworkCycle(task) ? ` · cycle ${Math.max(1, task.reworkCycle ?? 0)}` : ""}</span>}</div>
                    </div>
                    <section className="task-sheet-details" id={detailsId} aria-label="Worksheet details" hidden={!expanded}>
                        <section className="task-sheet-brief" aria-label="Full work instructions"><span>FULL INSTRUCTIONS</span><p>{task.description}</p></section>
                        <div className="task-sheet-fields"><div><span>Assigned to</span><strong>{role === "Employee" ? "Assigned to you" : employeeName(task.employeeId)} · {task.assigneeRole === "TEAM_LEAD" ? "Team Lead" : "Employee"}</strong></div><div><span>Assigned by</span><strong>{task.assignedByRole === "HR_ADMIN" ? "HR Admin" : "Team Lead"}</strong></div><div><span>Due date</span><strong>{new Date(`${task.dueDate}T00:00:00`).toLocaleDateString("en-IN", { day: "numeric", month: "short", year: "numeric" })}</strong></div><div><span>Current stage</span><strong>{stageLabel}</strong></div></div>
                        <div className="task-flow" aria-label={`Worksheet stage: ${workTaskStatusLabel(task.status)}`}><span className="done"><i>1</i>Assigned</span><b /><span className={["IN_PROGRESS", "COMPLETED", "APPROVED", "ACKNOWLEDGED", "CHANGES_REQUESTED"].includes(task.status) ? "done" : task.status === "ASSIGNED" ? "current" : ""}><i>2</i>{task.assigneeRole === "TEAM_LEAD" ? "Team Lead work" : "Employee work"}</span><b />{task.assigneeRole === "EMPLOYEE" && <><span className={["APPROVED", "ACKNOWLEDGED"].includes(task.status) ? "done" : task.status === "COMPLETED" ? "current" : ""}><i>3</i>Team Lead review</span><b /></>}<span className={task.status === "INSIGHT_REWORK_REQUESTED" ? "returned" : (task.assigneeRole === "TEAM_LEAD" && task.status === "COMPLETED") || ["APPROVED", "ACKNOWLEDGED"].includes(task.status) ? "current" : ""}><i>{task.assigneeRole === "TEAM_LEAD" ? 3 : 4}</i>HR → Manager → CEO</span></div>
                        {feedback && <div className="insight-rework-card"><span><RotateCcw size={15} /> {isInsightsReworkCycle(task) ? `INSIGHTS REWORK · CYCLE ${Math.max(1, task.reworkCycle ?? 0)}` : "TEAM LEAD REWORK"}</span><strong>{reworkSourceName(task)} feedback</strong><p>{feedback}</p>{task.status === "INSIGHT_REWORK_REQUESTED" && <small>Waiting for the Team Lead to record a corrective plan.</small>}</div>}
                        {(task.employeeUpdate || task.teamLeadReview) && <div className="task-sheet-responses">{task.employeeUpdate && <div><span>{task.assigneeRole === "TEAM_LEAD" ? "Team Lead work update" : "Employee work update"}</span><p>{task.employeeUpdate}</p></div>}{task.teamLeadReview && <div><span>Team Lead decision</span><p>{task.teamLeadReview}</p></div>}</div>}
                    </section>
                    <footer className="work-task-actions">
                        {expanded && <>
                            {canProgress(task) && ["ASSIGNED", "CHANGES_REQUESTED"].includes(task.status) && <button className="button button-secondary" disabled={Boolean(busy)} onClick={() => openTaskAction(task, "start")}><Clock3 size={14} /> {isReworkCycle(task) ? "Start rework" : "Start"}</button>}
                            {canProgress(task) && ["ASSIGNED", "IN_PROGRESS", "CHANGES_REQUESTED"].includes(task.status) && <button className="button button-primary" disabled={Boolean(busy)} onClick={() => openTaskAction(task, "complete")}><CheckCircle2 size={14} /> {isReworkCycle(task) ? "Submit corrected work" : "Complete"}</button>}
                            {role === "Team Lead" && task.assigneeRole === "EMPLOYEE" && task.status === "COMPLETED" && <><button className="button button-reject" disabled={Boolean(busy)} onClick={() => openTaskAction(task, "request-changes")}><X size={14} /> Request changes</button><button className="button button-approve" disabled={Boolean(busy)} onClick={() => openTaskAction(task, "approve")}><BadgeCheck size={14} /> Approve delivery</button></>}
                            {role === "Team Lead" && task.status === "INSIGHT_REWORK_REQUESTED" && <button className="button button-primary" disabled={Boolean(busy)} onClick={() => openTaskAction(task, "insight-rework")}><RotateCcw size={14} /> {task.assigneeRole === "TEAM_LEAD" ? "Start rework" : "Create rework plan"}</button>}
                            {canReviseRework(task) && <button className="button button-primary" disabled={Boolean(busy)} onClick={() => openTaskAction(task, "revise-rework")}><RotateCcw size={14} /> Update & resubmit</button>}
                            {role === "Employee" && task.status === "APPROVED" && <button className="button button-approve" disabled={Boolean(busy)} onClick={() => void act(task, "acknowledge")}><BadgeCheck size={14} /> Acknowledge</button>}
                            {role === "HR Admin" && pendingHrAuditTaskIds.has(task.id) && <><button className="button button-reject" disabled={Boolean(busy)} onClick={() => openTaskAction(task, "hr-rework")}><RotateCcw size={14} /> Return for rework</button><button className="button button-approve" disabled={Boolean(busy)} onClick={() => void auditHrTask(task)}><ShieldCheck size={14} /> Audit & send to Manager</button></>}
                        </>}
                        <button type="button" className="button task-sheet-details-toggle" aria-expanded={expanded} aria-controls={detailsId} onClick={() => setExpandedTaskId(expanded ? null : task.id)}>{expanded ? "Hide details" : "View details"}<ChevronRight size={15} aria-hidden="true" /></button>
                    </footer>
                </article>;
            })}{!loading && filtered.length === 0 && <div className="empty-state task-sheet-empty"><FileText size={29} /><strong>{loadError ? "Worksheet data is temporarily unavailable" : queueScope === "TODAY" ? "No worksheets on today’s board" : "No open carry-forward work"}</strong><small>{loadError ? "The last confirmed data will return automatically after the database connection recovers." : queueScope === "TODAY" ? "New worksheets created today will appear here. Use Open carry-forward for unfinished work from earlier days." : "All older worksheets are closed or no longer require action. Their stored records remain available for governance and audit."}</small></div>}
        </div>
        {role === "Team Lead" && <article className="panel glass-panel work-visitor-approvals"><div className="panel-heading"><div><span>VISITOR WORKFLOW</span><h2>Department visitor approvals</h2><p>Visitor approval remains available here so removing Appointments never breaks Security → Reception → HR routing.</p></div><b>{pendingVisitorApprovals.length}</b></div>{pendingVisitorApprovals.map((item) => <div className="approval-item" key={item.id}><div className="approval-person"><span className="avatar">{item.initials}</span><span><strong>{item.visitor}</strong><small>Visiting {item.host} · {item.referenceNumber}</small></span></div><p>“{item.arrivalPurpose ?? item.purpose}”</p><div className="approval-actions"><button className="button button-reject" onClick={() => void decideAppointment(item.id, "reject")}><X size={15} /> Reject visit</button><button className="button button-approve" onClick={() => void decideAppointment(item.id, "approve")}><Check size={15} /> Approve visit</button></div></div>)}{pendingVisitorApprovals.length === 0 && <div className="empty-state"><ShieldCheck size={27} /><strong>No visitor approvals waiting</strong><small>HR-routed department visitors will appear here.</small></div>}</article>}
        {actionDialog && taskActionCopy && <div className="modal-backdrop" role="presentation" onMouseDown={(event) => { if (event.target === event.currentTarget) setActionDialog(null); }}><section className="modal work-action-modal" role="dialog" aria-modal="true" aria-labelledby="work-action-title"><header><div><span>WORKSHEET ACTION</span><h2 id="work-action-title">{taskActionCopy[0]}</h2><p>{taskActionCopy[1]}</p></div><button className="icon-button" type="button" onClick={() => setActionDialog(null)} aria-label="Close worksheet action"><X size={18} /></button></header><form onSubmit={submitTaskAction}><div className="work-action-context"><span className="avatar">{visitorInitials(employeeName(actionDialog.task.employeeId))}</span><span><small>{actionDialog.task.departmentBranch} · {taskStageLabel(actionDialog.task)}</small><strong>{actionDialog.task.title}</strong></span></div>{["insight-rework", "revise-rework"].includes(actionDialog.action) && reworkFeedback(actionDialog.task) && <div className="modal-review-source"><RotateCcw size={16} /><span><small>{reworkSourceLabel(actionDialog.task)}</small><strong>{reworkFeedback(actionDialog.task)}</strong></span></div>}<label>{taskActionCopy[2]}<textarea value={actionNote} onChange={(event) => setActionNote(event.target.value)} placeholder={taskActionCopy[3]} minLength={["complete", "request-changes", "insight-rework", "revise-rework", "hr-rework"].includes(actionDialog.action) ? 5 : undefined} maxLength={1000} required={["complete", "request-changes", "insight-rework", "revise-rework", "hr-rework"].includes(actionDialog.action)} autoFocus /></label><small className="dialog-character-count">{actionNote.length}/1000</small>{error && <div className="login-error" role="alert">{error}</div>}<div className="modal-actions"><button type="button" className="button button-secondary" onClick={() => setActionDialog(null)}>Cancel</button><button className="button button-primary" disabled={Boolean(busy)}>{busy ? "Saving…" : taskActionCopy[4]}<ArrowRight size={15} /></button></div></form></section></div>}
        {message && <div className="success-banner"><CheckCircle2 size={17} />{message}</div>}{error && !actionDialog && <div className="login-error" role="alert">{error}</div>}
    </section>;
}

