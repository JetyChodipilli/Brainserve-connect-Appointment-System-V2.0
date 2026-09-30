"use client";

import {
    brainServeApi,
    type DepartmentHrAssignment,
    isBackendConfigured,
    type RoleDepartmentChangeRequest,
    type StaffAccount,
    type TeamLeadAssignment,
} from "../../../lib/api";
import { readDemoAccounts, writeDemoAccounts } from "../../../preview/accounts";
import {
    readDemoDepartmentHrAssignments,
    readDemoEmployees,
    readDemoTeamLeadAssignments,
    writeDemoDepartmentHrAssignments,
    writeDemoEmployees,
    writeDemoTeamLeadAssignments,
} from "../../../preview/directory";
import { readDemoRoleDepartmentChanges, writeDemoRoleDepartmentChanges } from "../../../preview/governance";
import { readDemoInternalNotifications, writeDemoInternalNotifications } from "../../../preview/notifications";
import { StatusPill } from "../../../shared/components/status-pill";
import { type Department, type Employee } from "../../../shared/types/workspace";
import { fail } from "../../../shared/utils/errors";
import { newClientId } from "../../../shared/utils/ids";
import { visitorInitials } from "../../appointments/appointment-utils";
import { ArrowRight, CheckCircle2, ShieldCheck, UserCog, Users, X } from "lucide-react";
import { type FormEvent, useCallback, useEffect, useState } from "react";

export function RoleAssignmentChangePanel({ role, userEmail, accounts, departments, employees, teamLeadAssignments,
                                       departmentHrAssignments, onChanged }: { role: "CEO" | "HR Admin"; userEmail: string;
    accounts: StaffAccount[]; departments: Department[]; employees: Employee[];
    teamLeadAssignments: TeamLeadAssignment[]; departmentHrAssignments: DepartmentHrAssignment[];
    onChanged: () => Promise<void> }) {
    const [requests, setRequests] = useState<RoleDepartmentChangeRequest[]>(() => {
        if (isBackendConfigured) return [];
        const actor = readDemoAccounts().find((item) => item.email.toLowerCase() === userEmail.toLowerCase());
        return readDemoRoleDepartmentChanges().filter((item) => item.status === "PENDING"
            && (role === "CEO" ? item.requesterRole === "HR_ADMIN"
                : item.requesterRole === "TEAM_LEAD" && readDemoDepartmentHrAssignments().some((assignment) =>
                assignment.active && assignment.departmentId === item.targetDepartmentId
                && assignment.hrUserId === (actor?.id ?? "demo-hr-admin"))));
    });
    const [busy, setBusy] = useState("");
    const [error, setError] = useState("");
    const [message, setMessage] = useState("");

    const load = useCallback(async () => {
        if (!isBackendConfigured) return;
        try { setRequests(await brainServeApi.pendingRoleDepartmentChanges()); setError(""); }
        catch (reason) { setError(reason instanceof Error ? reason.message : "Role assignment requests could not be loaded."); }
    }, []);
    useEffect(() => { const timer = window.setTimeout(() => void load(), 0); return () => window.clearTimeout(timer); }, [load]);

    const accountName = (userId: string) => accounts.find((item) => item.userId === userId)?.fullName
        ?? readDemoAccounts().find((item) => item.id === userId)?.fullName;

    const applyDemoApproval = (request: RoleDepartmentChangeRequest,
                               resolution: "MOVE" | "REPLACE" | "SWAP") => {
        const now = new Date().toISOString();
        const actor = readDemoAccounts().find((item) => item.email.toLowerCase() === userEmail.toLowerCase());
        const actorId = actor?.id ?? (role === "CEO" ? "demo-ceo" : "demo-hr-admin");
        if (request.requesterRole === "HR_ADMIN") {
            let workforce = readDemoEmployees();
            let allAccounts = readDemoAccounts();
            let current = readDemoDepartmentHrAssignments().find((item) => item.active && item.hrUserId === request.requesterUserId);
            const target = readDemoDepartmentHrAssignments().find((item) => item.active && item.departmentId === request.targetDepartmentId
                && item.hrUserId !== request.requesterUserId);
            let employeeId = current?.hrEmployeeId ?? request.requesterEmployeeId ?? undefined;
            if (!employeeId) {
                const account = allAccounts.find((item) => item.id === request.requesterUserId);
                const department = departments.find((item) => item.id === request.targetDepartmentId);
                employeeId = newClientId();
                const employee: Employee = { id: `BSPL-${department?.code ?? "HR"}-${newClientId().replaceAll("-", "").slice(-4).toUpperCase()}`,
                    uuid: employeeId, departmentId: request.targetDepartmentId, name: request.requesterName,
                    initials: visitorInitials(request.requesterName), role: "HR Admin", department: department?.name ?? "Department",
                    email: request.requesterEmail, status: "Active" };
                workforce = [employee, ...workforce];
                if (account) allAccounts = allAccounts.map((item) => item.id === account.id ? { ...item, employeeId } : item);
            }
            const sourceDepartmentId = current?.departmentId ?? request.fromDepartmentId;
            let assignments = readDemoDepartmentHrAssignments().map((item) => item.active
            && (item.hrUserId === request.requesterUserId || item.departmentId === request.targetDepartmentId)
                ? { ...item, active: false, endedByUserId: actorId, endedAt: now } : item);
            workforce = workforce.map((item) => (item.uuid ?? item.id) === employeeId
                ? { ...item, departmentId: request.targetDepartmentId,
                    department: departments.find((value) => value.id === request.targetDepartmentId)?.name ?? item.department } : item);
            assignments = [{ id: newClientId(), departmentId: request.targetDepartmentId,
                hrUserId: request.requesterUserId, hrEmployeeId: employeeId, active: true,
                assignedByUserId: actorId, assignedAt: now, endedByUserId: null, endedAt: null }, ...assignments];
            if (resolution === "SWAP" && target) {
                if (!sourceDepartmentId) fail("This HR Admin has no source department to swap.");
                workforce = workforce.map((item) => (item.uuid ?? item.id) === target.hrEmployeeId
                    ? { ...item, departmentId: sourceDepartmentId,
                        department: departments.find((value) => value.id === sourceDepartmentId)?.name ?? item.department } : item);
                assignments = [{ id: newClientId(), departmentId: sourceDepartmentId,
                    hrUserId: target.hrUserId, hrEmployeeId: target.hrEmployeeId, active: true,
                    assignedByUserId: actorId, assignedAt: now, endedByUserId: null, endedAt: null }, ...assignments];
            }
            writeDemoEmployees(workforce); writeDemoAccounts(allAccounts); writeDemoDepartmentHrAssignments(assignments);
            current = assignments.find((item) => item.active && item.hrUserId === request.requesterUserId);
            if (!current) fail("The HR assignment could not be updated.");
        } else {
            const current = readDemoTeamLeadAssignments().find((item) => item.active && item.teamLeadUserId === request.requesterUserId);
            if (!current) fail("The Team Lead has no active source assignment.");
            const target = readDemoTeamLeadAssignments().find((item) => item.active
                && item.departmentId === request.targetDepartmentId && item.teamLeadUserId !== request.requesterUserId);
            let assignments = readDemoTeamLeadAssignments().map((item) => item.active
            && (item.teamLeadUserId === request.requesterUserId || item.departmentId === request.targetDepartmentId)
                ? { ...item, active: false, endedByUserId: actorId, endedAt: now } : item);
            let workforce = readDemoEmployees().map((item) => (item.uuid ?? item.id) === current.teamLeadEmployeeId
                ? { ...item, departmentId: request.targetDepartmentId,
                    department: departments.find((value) => value.id === request.targetDepartmentId)?.name ?? item.department } : item);
            assignments = [{ id: newClientId(), departmentId: request.targetDepartmentId,
                teamLeadUserId: request.requesterUserId, teamLeadEmployeeId: current.teamLeadEmployeeId,
                active: true, assignedByUserId: actorId, assignedAt: now, endedByUserId: null, endedAt: null }, ...assignments];
            if (resolution === "SWAP" && target) {
                workforce = workforce.map((item) => (item.uuid ?? item.id) === target.teamLeadEmployeeId
                    ? { ...item, departmentId: current.departmentId,
                        department: departments.find((value) => value.id === current.departmentId)?.name ?? item.department } : item);
                assignments = [{ id: newClientId(), departmentId: current.departmentId,
                    teamLeadUserId: target.teamLeadUserId, teamLeadEmployeeId: target.teamLeadEmployeeId,
                    active: true, assignedByUserId: actorId, assignedAt: now, endedByUserId: null, endedAt: null }, ...assignments];
            } else if (target) {
                const targetEmployee = workforce.find((item) => (item.uuid ?? item.id) === target.teamLeadEmployeeId);
                if (targetEmployee) writeDemoAccounts(readDemoAccounts().map((item) => item.email.toLowerCase() === targetEmployee.email.toLowerCase()
                    ? { ...item, role: "ROLE_EMPLOYEE" } : item));
            }
            writeDemoEmployees(workforce); writeDemoTeamLeadAssignments(assignments);
        }
        return { ...request, status: "APPROVED" as const, resolution, decidedByUserId: actorId,
            decidedAt: now, decisionNote: "Approved through Roles & responsibilities" };
    };

    const decide = async (request: RoleDepartmentChangeRequest, event: FormEvent<HTMLFormElement>) => {
        event.preventDefault(); setBusy(request.id); setError(""); setMessage("");
        const data = new FormData(event.currentTarget);
        const action = ((event.nativeEvent as SubmitEvent).submitter as HTMLButtonElement | null)?.value ?? "approve";
        const note = String(data.get("note") ?? "").trim();
        try {
            let updated: RoleDepartmentChangeRequest;
            if (action === "reject") {
                if (note.length < 5) fail("Enter a rejection reason containing at least 5 characters.");
                updated = isBackendConfigured ? await brainServeApi.rejectRoleDepartmentChange(request.id, note)
                    : { ...request, status: "REJECTED" as const, decisionNote: note,
                        decidedByUserId: role === "CEO" ? "demo-ceo" : "demo-hr-admin", decidedAt: new Date().toISOString() };
            } else {
                const resolution = request.targetOccupied ? String(data.get("resolution")) as "REPLACE" | "SWAP" : "MOVE";
                updated = isBackendConfigured ? await brainServeApi.approveRoleDepartmentChange(request.id, resolution, note)
                    : applyDemoApproval(request, resolution);
            }
            if (!isBackendConfigured) writeDemoRoleDepartmentChanges(readDemoRoleDepartmentChanges()
                .map((item) => item.id === request.id ? updated : item));
            if (!isBackendConfigured) {
                const actor = readDemoAccounts().find((item) => item.email.toLowerCase() === userEmail.toLowerCase());
                const now = new Date().toISOString();
                writeDemoInternalNotifications([{ id: newClientId(), senderUserId: actor?.id ?? userEmail,
                    recipientUserId: request.requesterUserId, senderName: actor?.fullName ?? role,
                    recipientName: request.requesterName,
                    message: `Your ${request.requesterRole === "HR_ADMIN" ? "HR Admin" : "Team Lead"} department change to ${request.targetDepartmentName} was ${updated.status.toLowerCase()}${updated.resolution ? ` using ${updated.resolution.toLowerCase()}` : ""}${updated.decisionNote ? `: ${updated.decisionNote}` : "."}`,
                    priority: "HIGH", category: "ACTION_REQUIRED",
                    conversationKey: `role-department-change:${request.id}`, deliveryStatus: "DELIVERED",
                    sentAt: now, deliveredAt: now, readAt: null, senderEmail: userEmail,
                    recipientEmail: request.requesterEmail }, ...readDemoInternalNotifications()]);
            }
            setRequests((items) => items.filter((item) => item.id !== request.id));
            await onChanged();
            setMessage(`${request.requesterName}’s department change was ${updated.status.toLowerCase()}.`);
        } catch (reason) { setError(reason instanceof Error ? reason.message : "The role assignment decision could not be saved."); }
        finally { setBusy(""); }
    };

    const assignments = role === "CEO" ? departmentHrAssignments.filter((item) => item.active)
            .map((item) => ({ id: item.id, userId: item.hrUserId, employeeId: item.hrEmployeeId,
                name: accountName(item.hrUserId) ?? "HR Admin", departmentId: item.departmentId }))
        : teamLeadAssignments.filter((item) => item.active).map((item) => ({ id: item.id,
            userId: item.teamLeadUserId, employeeId: item.teamLeadEmployeeId,
            name: employees.find((employee) => (employee.uuid ?? employee.id) === item.teamLeadEmployeeId)?.name ?? "Team Lead",
            departmentId: item.departmentId }));

    return <article className="panel glass-panel role-assignment-ledger"><div className="panel-heading"><div><span>ROLE ASSIGNMENT LEDGER</span><h2>{role === "CEO" ? "HR details & department ownership" : "Team Lead details & department ownership"}</h2><p>{role === "CEO" ? "Review HR requests, see current department ownership, and resolve occupied departments explicitly." : "Review Team Lead requests addressed to your department and protect one-lead-per-department ownership."}</p></div><UserCog size={23} /></div>
        <div className="role-assignment-directory">{assignments.map((assignment) => { const department = departments.find((item) => item.id === assignment.departmentId); return <div key={assignment.id}><span className="avatar">{visitorInitials(assignment.name)}</span><span><strong>{assignment.name}</strong><small>{department?.name ?? "Department"} · {role === "CEO" ? "HR Admin" : "Team Lead"}</small></span><code>{department?.code ?? "—"}</code><StatusPill status="Active" /></div>; })}{assignments.length === 0 && <div className="empty-state"><Users size={26} /><strong>No active role assignments</strong></div>}</div>
        <div className="assignment-request-heading"><span><strong>Pending change requests</strong><small>Every decision is retained in the audit trail and sent through BrainServe Internal Delivery.</small></span><b>{requests.length}</b></div>
        <div className="assignment-request-list">{requests.map((request) => <form key={request.id} onSubmit={(event) => void decide(request, event)}><header><span className="avatar">{visitorInitials(request.requesterName)}</span><span><strong>{request.requesterName}</strong><small>{request.requesterEmail} · {request.requesterRole.replaceAll("_", " ")}</small></span><span className="status-pill status-pending"><span />Pending</span></header><div className="assignment-route"><span><small>FROM</small><strong>{request.fromDepartmentName ?? "Unassigned"}</strong></span><ArrowRight size={19} /><span><small>TO</small><strong>{request.targetDepartmentName}</strong></span></div><p>{request.reason}</p>{request.targetOccupied && <div className="role-conflict-warning"><ShieldCheck size={17} /><span><strong>Occupied by {request.targetOccupantName ?? "another role owner"}</strong><small>Choose a safe reassignment action before approval.</small></span></div>}<div className="assignment-decision-fields">{request.targetOccupied && <label>Change option<select name="resolution" defaultValue="SWAP"><option value="SWAP">Swap both department assignments</option><option value="REPLACE">Replace current role owner</option></select></label>}<label>Decision note<input name="note" maxLength={500} placeholder="Required when rejecting; optional when approving" /></label></div><div className="assignment-decision-actions"><button className="button button-approve" value="approve" disabled={busy === request.id}><CheckCircle2 size={15} />Approve {request.targetOccupied ? "change" : "move"}</button><button className="button button-reject" value="reject" disabled={busy === request.id}><X size={15} />Reject</button></div></form>)}{requests.length === 0 && <div className="assignment-queue-clear"><CheckCircle2 size={18} /><span><strong>No department changes await your decision</strong><small>New requests will appear here in requested order.</small></span></div>}</div>
        {message && <div className="success-banner"><CheckCircle2 size={17} />{message}</div>}{error && <div className="login-error" role="alert">{error}</div>}
    </article>;
}

