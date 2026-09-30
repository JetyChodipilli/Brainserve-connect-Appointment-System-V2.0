"use client";

import { brainServeApi, isBackendConfigured, type ManagerAssignment } from "../../../lib/api";
import { type Department } from "../../../shared/types/workspace";
import { BadgeCheck, Building2, CheckCircle2, LockKeyhole } from "lucide-react";
import { type FormEvent, useCallback, useEffect, useState } from "react";

export function ManagerDepartmentAssignmentPanel({ departments, assignments, onChanged }: {
    departments: Department[]; assignments: ManagerAssignment[]; onChanged: () => Promise<void>;
}) {
    const [candidates, setCandidates] = useState<Array<{ userId: string; employeeId: string;
        fullName: string; email: string; currentDepartmentId: string | null;
        currentDepartmentCode: string | null; currentDepartmentName: string | null }>>([]);
    const [busy, setBusy] = useState("");
    const [error, setError] = useState("");
    const [message, setMessage] = useState("");
    const load = useCallback(async () => {
        if (!isBackendConfigured) return;
        try { setCandidates(await brainServeApi.managerCandidates()); }
        catch (reason) {
            setError(reason instanceof Error ? reason.message : "Manager candidates could not be loaded.");
        }
    }, []);
    useEffect(() => {
        const timer = window.setTimeout(() => void load(), 0);
        return () => window.clearTimeout(timer);
    }, [load]);
    const assign = async (event: FormEvent<HTMLFormElement>) => {
        event.preventDefault();
        const form = event.currentTarget;
        const data = new FormData(form);
        setBusy("assign"); setError(""); setMessage("");
        try {
            await brainServeApi.assignManager(String(data.get("departmentId")), String(data.get("managerUserId")));
            setMessage("The Manager and employee profile were moved to the selected department atomically.");
            form.reset(); await Promise.all([load(), onChanged()]);
        } catch (reason) {
            setError(reason instanceof Error ? reason.message : "The Manager could not be assigned.");
        } finally { setBusy(""); }
    };
    const end = async (assignment: ManagerAssignment) => {
        setBusy(assignment.id); setError(""); setMessage("");
        try {
            await brainServeApi.endManagerAssignment(assignment.id);
            setMessage("The Manager assignment ended. Assign a replacement before routing a CEO visit to this department.");
            await Promise.all([load(), onChanged()]);
        } catch (reason) {
            setError(reason instanceof Error ? reason.message : "The Manager assignment could not be ended.");
        } finally { setBusy(""); }
    };
    const candidateName = (userId: string) =>
        candidates.find((candidate) => candidate.userId === userId)?.fullName ?? "Assigned Manager";
    return <article className="panel glass-panel team-lead-access-card">
        <div className="panel-heading"><div><span>MANAGER DEPARTMENT OWNERSHIP</span><h2>Move an existing Manager</h2>
            <p>Use this after a Manager role exists. The assignment and linked employee department change in one backend transaction.</p></div><Building2 size={22} /></div>
        {!isBackendConfigured ? <div className="governance-connection-note"><LockKeyhole size={18} /><span>
      <strong>Backend connection required</strong><small>Manager ownership is never simulated in Preview data.</small></span></div>
            : <form className="staff-create-form team-lead-access-form" onSubmit={assign}>
                <label>Manager<select name="managerUserId" required defaultValue=""><option value="">Select active Manager</option>
                    {candidates.map((candidate) => <option key={candidate.userId} value={candidate.userId}>
                        {candidate.fullName} · {candidate.currentDepartmentName ?? "unassigned"}</option>)}</select></label>
                <label>Department<select name="departmentId" required defaultValue=""><option value="">Select active department</option>
                    {departments.filter((department) => department.active).map((department) =>
                        <option key={department.id} value={department.id}>{department.name}</option>)}</select></label>
                <button className="button button-primary" disabled={busy === "assign"}><BadgeCheck size={15} />
                    {busy === "assign" ? "Assigning…" : "Assign Manager"}</button>
            </form>}
        <div className="active-team-lead-list">{assignments.filter((item) => item.active).map((assignment) =>
            <span key={assignment.id}><BadgeCheck size={14} /><strong>
        {departments.find((item) => item.id === assignment.departmentId)?.name ?? "Department"}</strong>
        <small>{candidateName(assignment.managerUserId)}</small>
                {isBackendConfigured && <button type="button" className="button button-quiet"
                                                disabled={busy === assignment.id} onClick={() => void end(assignment)}>End</button>}</span>)}
            {assignments.every((item) => !item.active) && <small>No department Managers assigned yet.</small>}</div>
        {message && <div className="success-banner"><CheckCircle2 size={17} />{message}</div>}
        {error && <div className="login-error" role="alert">{error}</div>}
    </article>;
}

