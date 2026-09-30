"use client";

import { workboardApi as brainServeApi } from "../api/workboard-api";

import { isBackendConfigured } from "../../../lib/api-client";
import type { WorkInsight, WorkTask } from "../types/workboard";
import type { ManagerAssignment, StaffAccount } from "../../../types/api";
import { readDemoAccounts } from "../../../preview/accounts";
import { readDemoDepartmentHrAssignments } from "../../../preview/directory";
import { initialStaffAccounts } from "../../../preview/fixtures/workspace";
import {
    demoAccountDepartment,
    readDemoInternalNotifications,
    writeDemoInternalNotifications,
} from "../../../preview/notifications";
import { type DemoProvisioningAccount } from "../../../preview/types";
import { readDemoWorkInsights, readDemoWorkTasks, writeDemoWorkInsights, writeDemoWorkTasks } from "../preview";
import { PageTitle } from "../../../components/ui/page-title";
import { type Department, type Employee, type Role } from "../../../types/workspace";
import { newClientId } from "../../../utils/ids";
import { visitorInitials } from "../../appointments/appointment-utils";
import { insightStatusLabel, officeDateFromInstant, workTaskStatusLabel, workWeekStart } from "../utils/work-utils";
import {
    ArrowRight,
    BadgeCheck,
    BriefcaseBusiness,
    Check,
    CheckCircle2,
    ChevronRight,
    FileClock,
    RotateCcw,
    Search,
    ShieldCheck,
    UserCog,
    X,
} from "lucide-react";
import { type FormEvent, useCallback, useEffect, useState } from "react";

export function WorkInsightsView({ role, userEmail, departments, employees, staffAccounts, managerAssignments }: {
    role: Role; userEmail: string; departments: Department[]; employees: Employee[];
    staffAccounts: StaffAccount[]; managerAssignments: ManagerAssignment[];
}) {
    const [weekStart, setWeekStart] = useState(() => workWeekStart());
    const [items, setItems] = useState<WorkInsight[]>([]);
    const [busy, setBusy] = useState("");
    const [message, setMessage] = useState("");
    const [error, setError] = useState("");
    const [query, setQuery] = useState("");
    const [statusFilter, setStatusFilter] = useState("ALL");
    const [departmentFilter, setDepartmentFilter] = useState("ALL");
    const [expandedTaskId, setExpandedTaskId] = useState("");
    const [decisionDialog, setDecisionDialog] = useState<{ item: WorkInsight; kind: "HR_REWORK" | "MANAGER_REWORK" | "MANAGER_APPROVE" | "CEO_REWORK" | "CEO_APPROVE" } | null>(null);
    const [decisionNote, setDecisionNote] = useState("");

    const demoInsight = useCallback((task: WorkTask, retained?: WorkInsight): WorkInsight => {
        const employee = employees.find((item) => (item.uuid ?? item.id) === task.employeeId);
        const teamLead = staffAccounts.find((item) => item.userId === task.teamLeadUserId)
            ?? initialStaffAccounts.find((item) => item.userId === task.teamLeadUserId);
        return retained ?? { auditRecordId: null, workTaskId: task.id,
            weekStart: workWeekStart(officeDateFromInstant(task.createdAt)), departmentId: task.departmentId,
            departmentName: departments.find((item) => item.id === task.departmentId)?.name ?? task.departmentBranch,
            employeeId: task.employeeId, employeeNumber: employee?.id ?? task.employeeId,
            employeeName: employee?.name ?? "Assigned employee", teamLeadUserId: task.teamLeadUserId,
            teamLeadName: teamLead?.fullName ?? "Team Lead", assignedByRole: task.assignedByRole,
            assigneeRole: task.assigneeRole, taskTitle: task.title, taskStatus: task.status,
            auditStatus: "NOT_AUDITED", hrAuditedAt: null,
            managerDecidedAt: null, managerRemarks: null, ceoDecidedAt: null, ceoRemarks: null,
            reworkRequestedByRole: null, reworkReason: null, reworkRequestedAt: null,
            teamLeadReworkGuidance: null, teamLeadRespondedAt: null, reworkCycle: 0 };
    }, [departments, employees, staffAccounts]);

    const load = useCallback(async () => {
        try {
            if (isBackendConfigured) setItems(await brainServeApi.workInsights(weekStart));
            else {
                const retained = readDemoWorkInsights().filter((item) => item.weekStart === weekStart);
                if (role === "HR Admin") {
                    const account = readDemoAccounts().find((item) => item.email.toLowerCase() === userEmail.toLowerCase());
                    const departmentId = demoAccountDepartment(account?.id ?? userEmail, userEmail);
                    const retainedByTask = new Map(retained.map((item) => [item.workTaskId, item]));
                    setItems(readDemoWorkTasks().filter((task) => Boolean(departmentId)
                        && task.departmentId === departmentId
                        && workWeekStart(officeDateFromInstant(task.createdAt)) === weekStart)
                        .map((task) => demoInsight(task, retainedByTask.get(task.id))));
                } else if (role === "Manager") {
                    const account = readDemoAccounts().find((item) => item.email.toLowerCase() === userEmail.toLowerCase());
                    const departmentId = demoAccountDepartment(account?.id ?? userEmail, userEmail);
                    setItems(departmentId ? retained.filter((item) => item.departmentId === departmentId) : []);
                } else setItems(retained);
            }
            setError("");
        } catch (reason) { setError(reason instanceof Error ? reason.message : "Weekly work insights could not be loaded."); }
    }, [demoInsight, role, userEmail, weekStart]);

    useEffect(() => {
        const timer = window.setTimeout(() => void load(), 0);
        return () => window.clearTimeout(timer);
    }, [load]);

    const auditTask = async (item: WorkInsight) => {
        setBusy(item.workTaskId); setError(""); setMessage("");
        try {
            let updated: WorkInsight;
            if (isBackendConfigured) updated = await brainServeApi.auditWorkInsight(item.workTaskId);
            else {
                updated = { ...item, auditRecordId: item.auditRecordId ?? newClientId(), auditStatus: "PENDING_MANAGER_APPROVAL",
                    hrAuditedAt: new Date().toISOString(), managerDecidedAt: null, managerRemarks: null,
                    ceoDecidedAt: null, ceoRemarks: null };
                writeDemoWorkInsights([updated, ...readDemoWorkInsights().filter((value) => value.workTaskId !== item.workTaskId)]);
                const sender = readDemoAccounts().find((account) => account.email.toLowerCase() === userEmail.toLowerCase());
                const managerAssignment = managerAssignments.find((assignment) => assignment.active
                    && assignment.departmentId === item.departmentId);
                const manager = readDemoAccounts().find((account) => account.id === managerAssignment?.managerUserId
                    && account.status === "ACTIVE");
                if (sender && manager) {
                    const now = new Date().toISOString();
                    writeDemoInternalNotifications([{ id: newClientId(), senderUserId: sender.id,
                        recipientUserId: manager.id, senderName: sender.fullName, recipientName: manager.fullName,
                        message: `HR audited ${item.employeeName}'s worksheet “${item.taskTitle}” in ${item.departmentName}. Manager verification is required.`,
                        deliveryStatus: "DELIVERED", sentAt: now, deliveredAt: now, readAt: null,
                        senderEmail: sender.email, recipientEmail: manager.email }, ...readDemoInternalNotifications()]);
                }
            }
            setItems((values) => values.map((value) => value.workTaskId === item.workTaskId ? updated : value));
            setMessage(item.auditStatus === "REWORK_ASSIGNED"
                ? "Reworked worksheet audited again and returned to the Manager verification queue."
                : "Worksheet marked audited and sent to the assigned Manager.");
        } catch (reason) { setError(reason instanceof Error ? reason.message : "The worksheet could not be audited."); }
        finally { setBusy(""); }
    };

    const requestHrRework = async (item: WorkInsight, reason: string) => {
        if (!reason.trim()) { setError("HR must enter the flaws before returning work to the Team Lead."); return false; }
        setBusy(`${item.workTaskId}:hr-rework`); setError(""); setMessage("");
        try {
            let updated: WorkInsight;
            if (isBackendConfigured) updated = await brainServeApi.requestWorkInsightRework(item.workTaskId, reason.trim());
            else {
                const now = new Date().toISOString();
                updated = { ...item, auditRecordId: item.auditRecordId ?? newClientId(), auditStatus: "HR_REWORK_REQUESTED",
                    reworkRequestedByRole: "HR_ADMIN", reworkReason: reason.trim(), reworkRequestedAt: now,
                    teamLeadReworkGuidance: null, teamLeadRespondedAt: null, reworkCycle: (item.reworkCycle ?? 0) + 1 };
                writeDemoWorkInsights([updated, ...readDemoWorkInsights().filter((value) => value.workTaskId !== item.workTaskId)]);
                writeDemoWorkTasks(readDemoWorkTasks().map((task) => task.id === item.workTaskId
                    ? { ...task, status: "INSIGHT_REWORK_REQUESTED" as const, insightReviewSource: "HR",
                        insightReviewReason: reason.trim(), insightReviewRequestedAt: now,
                        reworkCycle: (task.reworkCycle ?? 0) + 1, approvedAt: null, acknowledgedAt: null }
                    : task));
                const sender = readDemoAccounts().find((account) => account.email.toLowerCase() === userEmail.toLowerCase());
                const recipient = readDemoAccounts().find((account) => account.id === item.teamLeadUserId);
                if (sender && recipient) writeDemoInternalNotifications([{ id: newClientId(), senderUserId: sender.id,
                    recipientUserId: recipient.id, senderName: sender.fullName, recipientName: recipient.fullName,
                    message: `HR returned “${item.taskTitle}” for rework. Flaws: ${reason.trim()}`,
                    deliveryStatus: "DELIVERED", sentAt: now, deliveredAt: now, readAt: null,
                    senderEmail: sender.email, recipientEmail: recipient.email }, ...readDemoInternalNotifications()]);
            }
            setItems((values) => values.map((value) => value.workTaskId === item.workTaskId ? updated : value));
            setMessage("Worksheet returned to the assigned Team Lead with a mandatory rework reason.");
            return true;
        } catch (reasonValue) { setError(reasonValue instanceof Error ? reasonValue.message : "The rework request could not be saved."); return false; }
        finally { setBusy(""); }
    };

    const decide = async (item: WorkInsight, reviewer: "MANAGER" | "CEO", approved: boolean, remarks: string) => {
        if (!item.auditRecordId) return false;
        if (!approved && !remarks.trim()) { setError(`${reviewer === "MANAGER" ? "Manager" : "CEO"} must explain the flaws before returning work for rework.`); return false; }
        setBusy(item.auditRecordId); setError(""); setMessage("");
        try {
            const updated = isBackendConfigured
                ? reviewer === "MANAGER"
                    ? await brainServeApi.decideManagerWorkInsight(item.auditRecordId, approved, remarks.trim())
                    : await brainServeApi.decideWorkInsight(item.auditRecordId, approved, remarks.trim())
                : { ...item,
                    auditStatus: reviewer === "MANAGER"
                        ? approved ? "PENDING_CEO_APPROVAL" as const : "MANAGER_REWORK_REQUESTED" as const
                        : approved ? "CEO_APPROVED" as const : "CEO_REWORK_REQUESTED" as const,
                    taskStatus: reviewer === "CEO" && approved && item.assigneeRole === "TEAM_LEAD"
                        ? "APPROVED" as const : item.taskStatus,
                    managerDecidedAt: reviewer === "MANAGER" ? new Date().toISOString() : item.managerDecidedAt,
                    managerRemarks: reviewer === "MANAGER" ? remarks.trim() || null : item.managerRemarks,
                    ceoDecidedAt: reviewer === "CEO" ? new Date().toISOString() : item.ceoDecidedAt,
                    ceoRemarks: reviewer === "CEO" ? remarks.trim() || null : item.ceoRemarks,
                    reworkRequestedByRole: approved ? item.reworkRequestedByRole : reviewer,
                    reworkReason: approved ? item.reworkReason : remarks.trim(),
                    reworkRequestedAt: approved ? item.reworkRequestedAt : new Date().toISOString(),
                    teamLeadReworkGuidance: approved ? item.teamLeadReworkGuidance : null,
                    teamLeadRespondedAt: approved ? item.teamLeadRespondedAt : null,
                    reworkCycle: approved ? item.reworkCycle : (item.reworkCycle ?? 0) + 1 };
            if (!isBackendConfigured) writeDemoWorkInsights(readDemoWorkInsights().map((value) =>
                value.auditRecordId === item.auditRecordId ? updated : value));
            if (!isBackendConfigured) {
                const now = new Date().toISOString();
                const accounts = readDemoAccounts();
                const sender = accounts.find((account) => account.email.toLowerCase() === userEmail.toLowerCase());
                const hrAssignment = readDemoDepartmentHrAssignments().find((assignment) => assignment.active
                    && assignment.departmentId === item.departmentId);
                const hr = accounts.find((account) => account.id === hrAssignment?.hrUserId
                    && account.status === "ACTIVE");
                const ceo = accounts.find((account) => account.role === "ROLE_CEO" && account.status === "ACTIVE");
                const assignee = item.assigneeRole === "TEAM_LEAD"
                    ? accounts.find((account) => account.id === item.teamLeadUserId && account.status === "ACTIVE")
                    : accounts.find((account) => account.employeeId === item.employeeId && account.status === "ACTIVE");
                const teamLead = accounts.find((account) => account.id === item.teamLeadUserId
                    && account.status === "ACTIVE");
                const recipients = [hr, reviewer === "MANAGER" && approved ? ceo : undefined,
                    reviewer === "CEO" && approved ? assignee : undefined,
                    reviewer === "CEO" && approved && item.assigneeRole === "EMPLOYEE" ? teamLead : undefined]
                    .filter((recipient): recipient is DemoProvisioningAccount => Boolean(recipient))
                    .filter((recipient, index, values) => values.findIndex((value) => value.id === recipient.id) === index);
                if (sender) {
                    const notices = recipients.filter((recipient) => recipient.id !== sender.id).map((recipient) => ({
                        id: newClientId(), senderUserId: sender.id, recipientUserId: recipient.id,
                        senderName: sender.fullName, recipientName: recipient.fullName,
                        message: `${reviewer === "MANAGER" ? "Manager" : "CEO"} ${approved
                            ? reviewer === "MANAGER" ? "verified" : "approved" : "returned for rework"} “${item.taskTitle}”.${reviewer === "MANAGER" && approved
                            ? " CEO final approval is required." : !approved ? ` Reason: ${remarks.trim()}` : ""}`,
                        deliveryStatus: "DELIVERED" as const, sentAt: now, deliveredAt: now, readAt: null,
                        senderEmail: sender.email, recipientEmail: recipient.email,
                    }));
                    if (notices.length) writeDemoInternalNotifications([...notices, ...readDemoInternalNotifications()]);
                }
            }
            if (!isBackendConfigured && !approved) {
                const now = new Date().toISOString();
                writeDemoWorkTasks(readDemoWorkTasks().map((task) => task.id === item.workTaskId
                    ? { ...task, status: "INSIGHT_REWORK_REQUESTED" as const, insightReviewSource: reviewer,
                        insightReviewReason: remarks.trim(), insightReviewRequestedAt: now,
                        reworkCycle: (task.reworkCycle ?? 0) + 1, approvedAt: null, acknowledgedAt: null }
                    : task));
                const sender = readDemoAccounts().find((account) => account.email.toLowerCase() === userEmail.toLowerCase());
                const recipient = readDemoAccounts().find((account) => account.id === item.teamLeadUserId);
                if (sender && recipient) writeDemoInternalNotifications([{ id: newClientId(), senderUserId: sender.id,
                    recipientUserId: recipient.id, senderName: sender.fullName, recipientName: recipient.fullName,
                    message: `${reviewer === "MANAGER" ? "Manager" : "CEO"} returned “${item.taskTitle}” for rework. Flaws: ${remarks.trim()}`,
                    deliveryStatus: "DELIVERED", sentAt: now, deliveredAt: now, readAt: null,
                    senderEmail: sender.email, recipientEmail: recipient.email }, ...readDemoInternalNotifications()]);
            }
            if (!isBackendConfigured && reviewer === "CEO" && approved && item.assigneeRole === "TEAM_LEAD") {
                const now = new Date().toISOString();
                writeDemoWorkTasks(readDemoWorkTasks().map((task) => task.id === item.workTaskId
                    ? { ...task, status: "APPROVED" as const, approvedAt: now, acknowledgedAt: null }
                    : task));
            }
            setItems((values) => values.map((value) => value.auditRecordId === item.auditRecordId ? updated : value));
            setMessage(approved ? reviewer === "MANAGER"
                    ? "Manager verified the audit and routed it to the CEO for final approval."
                    : "Weekly work audit approved and retained for System Admin."
                : "Audit rejected with feedback. The assigned Team Lead now has an actionable rework card.");
            return true;
        } catch (reason) { setError(reason instanceof Error ? reason.message : `The ${reviewer.toLowerCase()} decision could not be saved.`); return false; }
        finally { setBusy(""); }
    };

    const openDecision = (item: WorkInsight, kind: "HR_REWORK" | "MANAGER_REWORK" | "MANAGER_APPROVE" | "CEO_REWORK" | "CEO_APPROVE") => {
        setDecisionDialog({ item, kind });
        setDecisionNote(kind.endsWith("APPROVE") ? "" : item.reworkReason ?? "");
        setError(""); setMessage("");
    };

    const submitDecision = async (event: FormEvent<HTMLFormElement>) => {
        event.preventDefault();
        if (!decisionDialog) return;
        if (!decisionDialog.kind.endsWith("APPROVE") && decisionNote.trim().length < 5) {
            setError("Enter at least 5 characters describing the flaws and required correction."); return;
        }
        const succeeded = decisionDialog.kind === "HR_REWORK"
            ? await requestHrRework(decisionDialog.item, decisionNote)
            : await decide(decisionDialog.item,
                decisionDialog.kind.startsWith("MANAGER") ? "MANAGER" : "CEO",
                decisionDialog.kind.endsWith("APPROVE"), decisionNote);
        if (succeeded) { setDecisionDialog(null); setDecisionNote(""); }
    };

    const audited = items.filter((item) => item.auditStatus !== "NOT_AUDITED").length;
    const pendingManager = items.filter((item) => item.auditStatus === "PENDING_MANAGER_APPROVAL").length;
    const pendingCeo = items.filter((item) => item.auditStatus === "PENDING_CEO_APPROVAL").length;
    const rework = items.filter((item) => ["HR_REWORK_REQUESTED", "MANAGER_REWORK_REQUESTED", "CEO_REWORK_REQUESTED", "REWORK_ASSIGNED"].includes(item.auditStatus)).length;
    const insightDepartments = [...new Set(items.map((item) => item.departmentName))].sort();
    const visibleItems = items.filter((item) => (statusFilter === "ALL" || item.auditStatus === statusFilter)
        && (departmentFilter === "ALL" || item.departmentName === departmentFilter)
        && `${item.employeeName} ${item.employeeNumber} ${item.taskTitle} ${item.teamLeadName} ${item.departmentName}`.toLowerCase().includes(query.toLowerCase()));
    const auditReady = (item: WorkInsight) => item.assigneeRole === "TEAM_LEAD"
        ? item.taskStatus === "COMPLETED"
        : ["APPROVED", "ACKNOWLEDGED"].includes(item.taskStatus);
    const decisionCopy = decisionDialog ? decisionDialog.kind === "CEO_APPROVE"
            ? ["Approve weekly work audit", "Confirm that HR evidence and Team Lead verification are sufficient.", "CEO decision note", "Optional governance note", "Approve audit"]
            : decisionDialog.kind === "CEO_REWORK"
                ? ["Reject audit and request rework", "Your feedback will appear immediately in the assigned Team Lead’s Work Board.", "CEO findings", "Describe the flaws, missing evidence and expected correction", "Return for rework"]
                : decisionDialog.kind === "MANAGER_APPROVE"
                    ? ["Verify department work audit", "Confirm the HR evidence before routing this audit to the CEO.", "Manager decision note", "Optional department governance note", "Verify & send to CEO"]
                    : decisionDialog.kind === "MANAGER_REWORK"
                        ? ["Return audit for department rework", "Your findings will be retained and routed to the assigned Team Lead.", "Manager findings", "Describe the flaws, missing evidence and expected correction", "Return for rework"]
                        : ["Return worksheet for rework", "HR feedback will be retained and routed to the assigned Team Lead.", "HR findings", "Describe the flaws, missing evidence and expected correction", "Return to Team Lead"]
        : null;
    return <section className="work-insights-page"><PageTitle eyebrow="WEEKLY WORK GOVERNANCE"
                                                              title={role === "System Admin" ? "Retained work insight register" : role === "CEO" ? "Work audit approvals"
                                                                  : role === "Manager" ? "Department work oversight" : "Work insights"}
                                                              detail={role === "HR Admin" ? "Audit completed worksheets or return flawed work to the Team Lead. Reworked delivery follows the same retained approval cycle."
                                                                  : role === "CEO" ? "Approve completed audits or return them with mandatory feedback that becomes actionable for the assigned Team Lead."
                                                                      : role === "Manager" ? "Verify HR-audited work for your assigned department before it reaches the CEO, or return it with corrective findings."
                                                                          : "Review weekly employee work, rejection reasons, Team Lead rework plans and final decisions retained for future audits."}
                                                              action={<label className="insight-week-picker">Week commencing<input type="date" value={weekStart}
                                                                                                                                   onChange={(event) => { if (event.target.value) setWeekStart(workWeekStart(event.target.value)); }} /></label>} />
        <section className="work-metrics glass-panel"><div><span>Weekly worksheets</span><strong>{items.length}</strong><small>{weekStart}</small></div><i />
            <div><span>HR reviewed</span><strong>{audited}</strong><small>Retained snapshots</small></div><i />
            <div><span>Manager / CEO queue</span><strong>{pendingManager} / {pendingCeo}</strong><small>Sequential decisions</small></div><i />
            <div><span>Rework cycle</span><strong>{rework}</strong><small>Feedback being resolved</small></div></section>
        <article className="panel glass-panel insight-register"><div className="panel-heading"><div><span>WEEKLY WORK TABLE</span><h2>Employee worksheet audit trail</h2><p>Open any row to review the complete approval and rework cycle without crowding the table.</p></div><FileClock size={22} /></div>
            <div className="insight-toolbar"><div><Search size={16} /><input value={query} onChange={(event) => setQuery(event.target.value)} placeholder="Search employee, worksheet or Team Lead" /></div><select value={departmentFilter} onChange={(event) => setDepartmentFilter(event.target.value)}><option value="ALL">All departments</option>{insightDepartments.map((department) => <option key={department}>{department}</option>)}</select><select value={statusFilter} onChange={(event) => setStatusFilter(event.target.value)}><option value="ALL">All audit states</option>{(["NOT_AUDITED", "HR_REWORK_REQUESTED", "PENDING_MANAGER_APPROVAL", "MANAGER_REWORK_REQUESTED", "PENDING_CEO_APPROVAL", "CEO_REWORK_REQUESTED", "REWORK_ASSIGNED", "CEO_APPROVED"] as WorkInsight["auditStatus"][]).map((status) => <option key={status} value={status}>{insightStatusLabel(status)}</option>)}</select></div>
            <div className="records-table-wrap"><table className="records-table work-insight-table"><thead><tr><th>Employee</th><th>Worksheet</th><th>Assigned by</th><th>Department</th><th>Task status</th><th>Audit status</th><th>Action</th></tr></thead>
                {visibleItems.map((item) => <tbody key={item.workTaskId} className={expandedTaskId === item.workTaskId ? "expanded" : ""}><tr><td><strong>{item.employeeName}</strong><small>{item.employeeNumber}</small></td>
                    <td><strong>{item.taskTitle}</strong><small>Week of {item.weekStart}</small></td><td><strong>{item.assignedByRole === "HR_ADMIN" ? "HR Admin" : item.teamLeadName}</strong><small>{item.assignedByRole === "HR_ADMIN" ? `Assigned to ${item.assigneeRole === "TEAM_LEAD" ? "Team Lead" : "Employee"}` : "Team Lead"}</small></td>
                    <td><strong>{item.departmentName}</strong><small>{item.departmentId}</small></td><td><span className="insight-pill">{workTaskStatusLabel(item.taskStatus)}</span></td>
                    <td><span className={`insight-pill insight-${item.auditStatus.toLowerCase().replaceAll("_", "-")}`}>{insightStatusLabel(item.auditStatus)}</span><small>Cycle {item.reworkCycle || 0}</small></td>
                    <td><div className="insight-row-actions"><button className="button button-secondary" onClick={() => setExpandedTaskId((value) => value === item.workTaskId ? "" : item.workTaskId)}>{expandedTaskId === item.workTaskId ? "Close details" : "View cycle"}<ChevronRight size={13} /></button>{role === "HR Admin" && ["NOT_AUDITED", "REWORK_ASSIGNED"].includes(item.auditStatus) && auditReady(item)
                        ? <div className="insight-actions"><button className="button button-reject" disabled={Boolean(busy)} onClick={() => openDecision(item, "HR_REWORK")}><RotateCcw size={14} /> Reject & rework</button><button className="button button-primary" disabled={Boolean(busy)} onClick={() => void auditTask(item)}><BadgeCheck size={14} />{busy === item.workTaskId ? "Auditing…" : item.auditStatus === "REWORK_ASSIGNED" ? "Re-audit" : "Mark audited"}</button></div>
                        : role === "HR Admin" && item.auditStatus === "NOT_AUDITED" ? <small>{item.assigneeRole === "TEAM_LEAD" ? "Await Team Lead completion" : "Await Team Lead approval"}</small>
                            : role === "Manager" && item.auditStatus === "PENDING_MANAGER_APPROVAL" ? <div className="insight-actions"><button className="button button-reject" disabled={Boolean(busy)} onClick={() => openDecision(item, "MANAGER_REWORK")}><RotateCcw size={14} /> Reject & rework</button><button className="button button-approve" disabled={Boolean(busy)} onClick={() => openDecision(item, "MANAGER_APPROVE")}><Check size={14} /> Verify</button></div>
                                : role === "CEO" && item.auditStatus === "PENDING_CEO_APPROVAL" ? <div className="insight-actions"><button className="button button-reject" disabled={Boolean(busy)} onClick={() => openDecision(item, "CEO_REWORK")}><RotateCcw size={14} /> Reject & rework</button><button className="button button-approve" disabled={Boolean(busy)} onClick={() => openDecision(item, "CEO_APPROVE")}><Check size={14} /> Approve</button></div>
                                    : <small>{item.auditStatus === "REWORK_ASSIGNED" && auditReady(item) ? "Rework ready for HR audit"
                                        : ["HR_REWORK_REQUESTED", "MANAGER_REWORK_REQUESTED", "CEO_REWORK_REQUESTED"].includes(item.auditStatus) ? "Waiting for Team Lead plan"
                                            : item.auditStatus === "REWORK_ASSIGNED" && item.taskStatus === "COMPLETED" && item.assigneeRole === "EMPLOYEE" ? "Waiting for Team Lead reapproval"
                                                : item.auditStatus === "REWORK_ASSIGNED" ? "Employee rework in progress"
                                                    : item.ceoDecidedAt ? `Decided ${new Date(item.ceoDecidedAt).toLocaleDateString("en-IN")}` : "Retained for audit"}</small>}</div></td></tr>{expandedTaskId === item.workTaskId && <tr className="insight-detail-row"><td colSpan={7}><div className="insight-cycle"><header><span><small>AUDIT CYCLE</small><strong>Worksheet governance history</strong></span><b>Cycle {item.reworkCycle || 0}</b></header><div className="insight-cycle-steps"><span className="done"><i><BadgeCheck size={13} /></i><strong>{item.assigneeRole === "TEAM_LEAD" ? "Team Lead delivery" : "Team Lead review"}</strong><small>{workTaskStatusLabel(item.taskStatus)}</small></span><b /><span className={item.hrAuditedAt ? "done" : "current"}><i><UserCog size={13} /></i><strong>HR audit</strong><small>{item.hrAuditedAt ? new Date(item.hrAuditedAt).toLocaleString("en-IN", { dateStyle: "medium", timeStyle: "short" }) : "Waiting for review"}</small></span><b /><span className={item.auditStatus === "PENDING_MANAGER_APPROVAL" ? "current" : item.auditStatus === "MANAGER_REWORK_REQUESTED" ? "returned" : item.managerDecidedAt ? "done" : ""}><i><BriefcaseBusiness size={13} /></i><strong>Manager verification</strong><small>{item.managerDecidedAt ? item.managerRemarks || "Decision recorded" : "Waiting for Manager"}</small></span><b /><span className={item.auditStatus === "CEO_APPROVED" ? "done" : item.auditStatus === "PENDING_CEO_APPROVAL" ? "current" : item.auditStatus === "CEO_REWORK_REQUESTED" ? "returned" : ""}><i><ShieldCheck size={13} /></i><strong>CEO decision</strong><small>{insightStatusLabel(item.auditStatus)}</small></span><b /><span className={item.teamLeadReworkGuidance ? "done" : item.reworkReason ? "current" : ""}><i><RotateCcw size={13} /></i><strong>Rework response</strong><small>{item.teamLeadReworkGuidance ? "Guidance assigned" : item.reworkReason ? "Waiting for Team Lead" : "Not required"}</small></span></div><div className="insight-evidence-grid"><article><small>Reviewer findings</small><strong>{item.reworkRequestedByRole === "CEO" ? "CEO feedback" : item.reworkRequestedByRole === "MANAGER" ? "Manager feedback" : item.reworkRequestedByRole ? "HR feedback" : "No rejection recorded"}</strong><p>{item.reworkReason ?? item.managerRemarks ?? "This worksheet has not been returned for rework."}</p></article><article><small>Team Lead corrective plan</small><strong>{item.teamLeadReworkGuidance ? item.teamLeadName : "Awaiting response"}</strong><p>{item.teamLeadReworkGuidance ?? "A corrective plan will appear here after the Team Lead responds."}</p></article><article><small>Retention evidence</small><strong>{item.auditRecordId ? "Database snapshot retained" : "Live worksheet"}</strong><p>{item.auditRecordId ? `Audit record ${item.auditRecordId.slice(0, 8).toUpperCase()} · Week ${item.weekStart}` : "HR has not yet created the retained audit snapshot."}</p></article></div></div></td></tr>}</tbody>)}
            </table>{visibleItems.length === 0 && <div className="empty-state table-empty"><FileClock size={28} /><strong>No matching weekly work records</strong><small>{items.length ? "Change the search or filters to see more records." : role === "HR Admin" ? "Worksheets assigned during this week will appear here." : role === "Manager" ? "HR-audited work from your assigned department will appear here." : "HR-audited worksheets will appear after submission."}</small></div>}</div>
        </article>{decisionDialog && decisionCopy && <div className="modal-backdrop" role="presentation" onMouseDown={(event) => { if (event.target === event.currentTarget) setDecisionDialog(null); }}><section className="modal insight-decision-modal" role="dialog" aria-modal="true" aria-labelledby="insight-decision-title"><header><div><span>INSIGHTS DECISION</span><h2 id="insight-decision-title">{decisionCopy[0]}</h2><p>{decisionCopy[1]}</p></div><button className="icon-button" type="button" onClick={() => setDecisionDialog(null)} aria-label="Close Insights decision"><X size={18} /></button></header><form onSubmit={submitDecision}><div className="decision-work-context"><span className="avatar">{visitorInitials(decisionDialog.item.employeeName)}</span><span><small>{decisionDialog.item.departmentName} · {decisionDialog.item.employeeNumber}</small><strong>{decisionDialog.item.taskTitle}</strong><p>Assigned by {decisionDialog.item.assignedByRole === "HR_ADMIN" ? "HR Admin" : decisionDialog.item.teamLeadName}</p></span><span className={`insight-pill insight-${decisionDialog.item.auditStatus.toLowerCase().replaceAll("_", "-")}`}>{insightStatusLabel(decisionDialog.item.auditStatus)}</span></div><label>{decisionCopy[2]}<textarea value={decisionNote} onChange={(event) => setDecisionNote(event.target.value)} placeholder={decisionCopy[3]} minLength={decisionDialog.kind.endsWith("APPROVE") ? undefined : 5} maxLength={1000} required={!decisionDialog.kind.endsWith("APPROVE")} autoFocus /></label><div className="decision-policy"><ShieldCheck size={16} /><span><strong>{decisionDialog.kind === "CEO_APPROVE" ? "Final approval" : decisionDialog.kind === "MANAGER_APPROVE" ? "Manager verification" : "Controlled rework"}</strong><small>{decisionDialog.kind === "CEO_APPROVE" ? "This decision closes the active audit cycle and remains visible to System Admin." : decisionDialog.kind === "MANAGER_APPROVE" ? "This routes the retained audit to the CEO for final approval." : "The worksheet is locked until the assigned Team Lead creates corrective guidance."}</small></span></div><small className="dialog-character-count">{decisionNote.length}/1000</small>{error && <div className="login-error" role="alert">{error}</div>}<div className="modal-actions"><button type="button" className="button button-secondary" onClick={() => setDecisionDialog(null)}>Cancel</button><button className={`button ${decisionDialog.kind.endsWith("APPROVE") ? "button-approve" : "button-primary"}`} disabled={Boolean(busy)}>{busy ? "Saving…" : decisionCopy[4]}<ArrowRight size={15} /></button></div></form></section></div>}{message && <div className="success-banner"><CheckCircle2 size={17} />{message}</div>}{error && !decisionDialog && <div className="login-error" role="alert">{error}</div>}
    </section>;
}

