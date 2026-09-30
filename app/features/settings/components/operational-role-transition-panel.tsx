"use client";

import { brainServeApi, isBackendConfigured, type ManagerAssignment } from "../../../lib/api";
import { readDemoAccounts, writeDemoAccounts } from "../../../preview/accounts";
import {
    readDemoDepartmentHrAssignments,
    readDemoDepartments,
    readDemoEmployees,
    readDemoTeamLeadAssignments,
    writeDemoDepartmentHrAssignments,
    writeDemoEmployees,
    writeDemoTeamLeadAssignments,
} from "../../../preview/directory";
import { DEMO_SYSTEM_ADMIN } from "../../../preview/fixtures/accounts";
import { readDemoManagerAssignments, writeDemoManagerAssignments } from "../../../preview/manager-assignments";
import { type Department } from "../../../shared/types/workspace";
import { fail } from "../../../shared/utils/errors";
import { newClientId } from "../../../shared/utils/ids";
import { employeesDepartment } from "../assignment-utils";
import { BadgeCheck, CheckCircle2, Search, ShieldCheck, UserCog } from "lucide-react";
import { type FormEvent, useCallback, useEffect, useState } from "react";

export function OperationalRoleTransitionPanel({ actorRole, departments, managerAssignments, onChanged }: {
    actorRole: "CEO" | "System Admin";
    departments: Department[];
    managerAssignments: ManagerAssignment[];
    onChanged: () => Promise<void>;
}) {
    const [candidates, setCandidates] = useState<Array<{ userId: string; employeeId: string;
        fullName: string; email: string; role: string; departmentId: string }>>([]);
    const [query, setQuery] = useState("");
    const [busy, setBusy] = useState(false);
    const [message, setMessage] = useState("");
    const [error, setError] = useState("");
    const [successionCandidates, setSuccessionCandidates] = useState<Array<{ userId: string; employeeId: string;
        fullName: string; email: string; role: string; departmentId: string }>>([]);

    const load = useCallback(async (search = "") => {
        setError("");
        try {
            if (isBackendConfigured) {
                setCandidates((await brainServeApi.operationalRoleCandidates(search)).content);
            } else {
                const operationalRoles = ["ROLE_EMPLOYEE", "ROLE_TEAM_LEAD", "ROLE_HR_ADMIN", "ROLE_MANAGER"];
                setCandidates(readDemoAccounts().filter((account) => account.employeeId
                    && ((account.status === "ACTIVE" && operationalRoles.includes(account.role))
                        || (actorRole === "System Admin" && account.role === "ROLE_CEO"
                            && ["ACTIVE", "REJECTED", "DISABLED"].includes(account.status)))
                    && (!search || `${account.fullName} ${account.email}`.toLowerCase().includes(search.toLowerCase())))
                    .map((account) => ({ userId: account.id, employeeId: account.employeeId as string,
                        fullName: account.fullName, email: account.email, role: account.role,
                        departmentId: employeesDepartment(account.employeeId as string, departments) })));
            }
        } catch (reason) {
            setError(reason instanceof Error ? reason.message : "Role-transition candidates could not be loaded.");
        }
    }, [actorRole, departments]);

    useEffect(() => { const timer = window.setTimeout(() => void load(), 0);
        return () => window.clearTimeout(timer); }, [load]);

    useEffect(() => {
        if (actorRole !== "System Admin" || !isBackendConfigured) return;
        let active = true;
        brainServeApi.operationalRoleCandidates("").then((page) => {
            if (active) setSuccessionCandidates(page.content);
        }).catch((reason) => {
            if (active) setError(reason instanceof Error ? reason.message
                : "CEO succession candidates could not be loaded.");
        });
        return () => { active = false; };
    }, [actorRole]);

    const submit = async (event: FormEvent<HTMLFormElement>) => {
        event.preventDefault();
        const form = event.currentTarget;
        const data = new FormData(form);
        const userId = String(data.get("userId"));
        const targetRole = String(data.get("role")) as "ROLE_EMPLOYEE" | "ROLE_TEAM_LEAD" | "ROLE_HR_ADMIN" | "ROLE_MANAGER";
        const departmentId = String(data.get("departmentId"));
        const reason = String(data.get("reason"));
        const candidate = candidates.find((item) => item.userId === userId);
        if (!candidate) { setError("Select an active employee-linked account."); return; }
        setBusy(true); setError(""); setMessage("");
        try {
            if (isBackendConfigured) {
                await brainServeApi.transitionOperationalRole(userId, targetRole, departmentId, reason);
            } else {
                const supportedRoles = ["ROLE_EMPLOYEE", "ROLE_TEAM_LEAD", "ROLE_HR_ADMIN", "ROLE_MANAGER"];
                const targetAccount = readDemoAccounts().find((account) => account.id === userId);
                const department = readDemoDepartments().find((item) => item.id === departmentId && item.active);
                const formerCeoTransition = targetAccount?.role === "ROLE_CEO";
                const eligibleSource = targetAccount && (supportedRoles.includes(targetAccount.role)
                    && targetAccount.status === "ACTIVE"
                    || formerCeoTransition && actorRole === "System Admin"
                    && ["ACTIVE", "REJECTED", "DISABLED"].includes(targetAccount.status));
                if (!targetAccount || !eligibleSource || !targetAccount.employeeId
                    || !supportedRoles.includes(targetRole)) {
                    fail("Select one active employee-linked operational account.");
                }
                if (formerCeoTransition && targetRole !== "ROLE_MANAGER") {
                    fail("A former CEO can transition only to Manager.");
                }
                if (formerCeoTransition && !readDemoAccounts().some((account) => account.id !== userId
                    && account.role === "ROLE_CEO" && account.status === "ACTIVE")) {
                    fail("Activate the successor CEO before moving the current CEO to Manager.");
                }
                if (!department) fail("Select an active department.");
                if (targetAccount.role === targetRole) {
                    fail(`Select a different role for ${candidate.fullName}.`);
                }

                const currentTeamLeads = readDemoTeamLeadAssignments();
                const currentDepartmentHrs = readDemoDepartmentHrAssignments();
                const currentManagers = readDemoManagerAssignments();
                const occupied = targetRole === "ROLE_TEAM_LEAD"
                    ? currentTeamLeads.some((item) => item.active && item.departmentId === departmentId
                        && item.teamLeadUserId !== userId)
                    : targetRole === "ROLE_HR_ADMIN"
                        ? currentDepartmentHrs.some((item) => item.active && item.departmentId === departmentId
                            && item.hrUserId !== userId)
                        : targetRole === "ROLE_MANAGER"
                            ? currentManagers.some((item) => item.active && item.departmentId === departmentId
                                && item.managerUserId !== userId)
                            : false;
                if (occupied) {
                    fail(`The selected department already has an active ${targetRole
                        .replace("ROLE_", "").replaceAll("_", " ")}.`);
                }

                const now = new Date().toISOString();
                const actorUserId = actorRole === "System Admin" ? DEMO_SYSTEM_ADMIN.id : "demo-ceo";
                let nextTeamLeads = currentTeamLeads.map((item) =>
                    item.active && item.teamLeadUserId === userId
                        ? { ...item, active: false, endedByUserId: actorUserId, endedAt: now } : item);
                let nextDepartmentHrs = currentDepartmentHrs.map((item) =>
                    item.active && item.hrUserId === userId
                        ? { ...item, active: false, endedByUserId: actorUserId, endedAt: now } : item);
                let nextManagers = currentManagers.map((item) =>
                    item.active && item.managerUserId === userId
                        ? { ...item, active: false, endedByUserId: actorUserId, endedAt: now } : item);

                if (targetRole === "ROLE_TEAM_LEAD") {
                    nextTeamLeads = [{
                        id: newClientId(), departmentId, teamLeadUserId: userId,
                        teamLeadEmployeeId: targetAccount.employeeId, active: true,
                        assignedByUserId: actorUserId, assignedAt: now, endedByUserId: null, endedAt: null,
                    }, ...nextTeamLeads];
                } else if (targetRole === "ROLE_HR_ADMIN") {
                    nextDepartmentHrs = [{
                        id: newClientId(), departmentId, hrUserId: userId,
                        hrEmployeeId: targetAccount.employeeId, active: true,
                        assignedByUserId: actorUserId, assignedAt: now, endedByUserId: null, endedAt: null,
                    }, ...nextDepartmentHrs];
                } else if (targetRole === "ROLE_MANAGER") {
                    nextManagers = [{
                        id: newClientId(), departmentId, managerUserId: userId,
                        managerEmployeeId: targetAccount.employeeId, active: true,
                        assignedByUserId: actorUserId, assignedAt: now, endedByUserId: null, endedAt: null,
                    }, ...nextManagers];
                }

                const employee = readDemoEmployees().find((item) =>
                    (item.uuid ?? item.id) === targetAccount.employeeId);
                if (!employee || employee.status !== "Active") {
                    fail("Only an active employee profile can receive the Manager position.");
                }
                const nextDesignation = targetRole === "ROLE_MANAGER" ? "Department Manager"
                    : targetRole === "ROLE_HR_ADMIN" ? "HR Business Partner"
                        : targetRole === "ROLE_TEAM_LEAD" ? "Team Lead" : null;
                const nextEmployees = readDemoEmployees().map((item) =>
                    (item.uuid ?? item.id) === targetAccount.employeeId
                        ? { ...item, departmentId, department: department.name,
                            role: nextDesignation ?? item.role, status: "Active" as const } : item);
                const nextAccounts = readDemoAccounts().map((account) =>
                    account.id === userId ? { ...account, role: targetRole, status: "ACTIVE",
                        rejectedAt: null } : account);

                // Compute and validate every next collection before committing any local
                // write. The account write is last and is the session-revocation signal.
                writeDemoTeamLeadAssignments(nextTeamLeads);
                writeDemoDepartmentHrAssignments(nextDepartmentHrs);
                writeDemoManagerAssignments(nextManagers);
                writeDemoEmployees(nextEmployees);
                writeDemoAccounts(nextAccounts);
            }
            await onChanged();
            setMessage(`${candidate.fullName} changed from ${candidate.role.replace("ROLE_", "").replaceAll("_", " ")} to ${targetRole.replace("ROLE_", "").replaceAll("_", " ")}. Existing sessions were revoked and the department assignment was updated atomically.`);
            form.reset(); setQuery(""); await load();
        } catch (reasonValue) {
            setError(reasonValue instanceof Error ? reasonValue.message : "The operational role could not be changed.");
        } finally { setBusy(false); }
    };

    const submitSuccession = async (event: FormEvent<HTMLFormElement>) => {
        event.preventDefault();
        const form = event.currentTarget;
        const data = new FormData(form);
        const currentCeoUserId = String(data.get("currentCeoUserId"));
        const successorUserId = String(data.get("successorUserId"));
        const formerCeoDepartmentId = String(data.get("formerCeoDepartmentId"));
        const reason = String(data.get("reason"));
        setBusy(true); setError(""); setMessage("");
        try {
            await brainServeApi.succeedChiefExecutive(
                currentCeoUserId, successorUserId, formerCeoDepartmentId, reason);
            await onChanged();
            const refreshed = await brainServeApi.operationalRoleCandidates("");
            setSuccessionCandidates(refreshed.content);
            setMessage("CEO succession completed atomically. Both sessions were revoked, the successor now has company-wide CEO authority, and the former CEO is the selected department Manager.");
            form.reset();
        } catch (reasonValue) {
            setError(reasonValue instanceof Error ? reasonValue.message
                : "CEO succession could not be completed.");
        } finally { setBusy(false); }
    };

    return <article className="panel glass-panel team-lead-access-card">
        <div className="panel-heading"><div><span>SINGLE-ROLE TRANSITION</span><h2>Change operational role safely</h2><p>CEO or System Admin can move an active operational account into one new role and department. System Admin can also move a former CEO to Manager after a successor CEO is active.</p></div><ShieldCheck size={22} /></div>
        <div className="team-lead-access-note"><BadgeCheck size={17} /><span><strong>One account · one role · one department assignment</strong><small>Old Team Lead, HR or Manager ownership is ended, custom permission overrides are cleared, and refresh sessions are revoked before the new access becomes effective.</small></span></div>
        <form className="staff-create-form team-lead-access-form" onSubmit={submit}>
            <label>Find account<div className="directory-search-row"><input value={query} onChange={(event) => setQuery(event.target.value)} placeholder="Name or company email" /><button type="button" className="button button-secondary" disabled={busy} onClick={() => void load(query)}><Search size={15} /> Search</button></div></label>
            <label>Account<select name="userId" required defaultValue=""><option value="">Select active account</option>{candidates.map((candidate) => <option key={candidate.userId} value={candidate.userId}>{candidate.fullName} · {candidate.role.replace("ROLE_", "").replaceAll("_", " ")}</option>)}</select></label>
            <label>New role<select name="role" required defaultValue="ROLE_MANAGER"><option value="ROLE_MANAGER">Manager</option><option value="ROLE_HR_ADMIN">HR Admin</option><option value="ROLE_TEAM_LEAD">Team Lead</option><option value="ROLE_EMPLOYEE">Employee</option></select></label>
            <label>Department<select name="departmentId" required defaultValue=""><option value="">Select active department</option>{departments.filter((item) => item.active).map((department) => <option key={department.id} value={department.id}>{department.name}</option>)}</select></label>
            <label>Reason<textarea name="reason" required minLength={5} maxLength={500} placeholder="Explain the approved responsibility change." /></label>
            <button className="button button-primary" disabled={busy}><UserCog size={16} />{busy ? "Changing role…" : "Apply role transition"}</button>
        </form>
        <div className="active-team-lead-list">{managerAssignments.filter((item) => item.active).map((assignment) => <span key={assignment.id}><BadgeCheck size={14} /><strong>{departments.find((item) => item.id === assignment.departmentId)?.name ?? "Department"}</strong><small>Assigned Manager</small></span>)}{managerAssignments.every((item) => !item.active) && <small>No department Managers assigned yet.</small>}</div>
        {actorRole === "System Admin" && <div className="ceo-succession-section">
            <div className="panel-heading"><div><span>ATOMIC CEO SUCCESSION</span><h2>Transfer company CEO authority</h2><p>The successor receives the CEO role in the same transaction that moves the current CEO to Manager. Existing leadership assignments and both sessions are ended safely.</p></div><ShieldCheck size={22} /></div>
            <form className="staff-create-form team-lead-access-form" onSubmit={submitSuccession}>
                <label>Current CEO<select name="currentCeoUserId" required defaultValue=""><option value="">Select current CEO</option>{successionCandidates.filter((item) => item.role === "ROLE_CEO").map((item) => <option key={item.userId} value={item.userId}>{item.fullName} · {item.email}</option>)}</select></label>
                <label>Successor CEO<select name="successorUserId" required defaultValue=""><option value="">Select active successor</option>{successionCandidates.filter((item) => ["ROLE_EMPLOYEE", "ROLE_TEAM_LEAD", "ROLE_HR_ADMIN", "ROLE_MANAGER"].includes(item.role)).map((item) => <option key={item.userId} value={item.userId}>{item.fullName} · {item.role.replace("ROLE_", "").replaceAll("_", " ")}</option>)}</select></label>
                <label>Former CEO Manager department<select name="formerCeoDepartmentId" required defaultValue=""><option value="">Select active department</option>{departments.filter((item) => item.active).map((department) => <option key={department.id} value={department.id}>{department.name}</option>)}</select></label>
                <label>Succession reason<textarea name="reason" required minLength={5} maxLength={500} placeholder="Record the approved executive handover." /></label>
                <button className="button button-primary" disabled={busy}><UserCog size={16} />{busy ? "Transferring authority…" : "Complete CEO succession"}</button>
            </form>
        </div>}
        {message && <div className="success-banner"><CheckCircle2 size={17} />{message}</div>}
        {error && <div className="login-error" role="alert">{error}</div>}
    </article>;
}

