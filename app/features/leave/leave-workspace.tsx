"use client";

import { brainServeApi, isBackendConfigured, type LeaveRequest } from "../../lib/api";
import { officeToday } from "../../lib/appointments";
import { type Role } from "../../shared/types/workspace";
import { FileClock, Send } from "lucide-react";
import { type FormEvent, useCallback, useEffect, useState } from "react";

export function LeaveWorkspace({ role }: { role: Role }) {
    const [items, setItems] = useState<LeaveRequest[]>([]); const [error, setError] = useState("");
    const load = useCallback(async () => {
        if (!isBackendConfigured) return;
        try { setItems(role === "HR Admin" ? await brainServeApi.pendingLeaveRequests() : await brainServeApi.myLeaveRequests()); }
        catch (reason) { setError(reason instanceof Error ? reason.message : "Leave records could not be loaded."); }
    }, [role]);
    useEffect(() => { const initialLoad = window.setTimeout(() => void load(), 0);
        return () => window.clearTimeout(initialLoad); }, [load]);
    const create = async (event: FormEvent<HTMLFormElement>) => {
        event.preventDefault(); const form = event.currentTarget; const data = new FormData(form); setError("");
        try { await brainServeApi.createLeaveRequest(String(data.get("startDate")), String(data.get("endDate")), String(data.get("reason"))); form.reset(); await load(); }
        catch (reason) { setError(reason instanceof Error ? reason.message : "Leave request could not be submitted."); }
    };
    const decide = async (id: string, decision: "approve" | "reject") => {
        try { await brainServeApi.decideLeaveRequest(id, decision, decision === "approve" ? "Approved by HR Admin" : "Rejected by HR Admin"); await load(); }
        catch (reason) { setError(reason instanceof Error ? reason.message : "Leave decision failed."); }
    };
    const minDate = officeToday();
    return <article className="panel glass-panel leave-panel"><div className="panel-heading"><div><span>PERSISTED LEAVE WORKFLOW</span><h2>{role === "Employee" ? "Request leave from HR" : "HR leave approval queue"}</h2><p>Requests, decisions and BrainServe Internal Calls updates are retained in the System Admin monthly register.</p></div><FileClock size={21} /></div>
        {role === "Employee" && <form className="staff-create-form" onSubmit={create}><label>From<input name="startDate" type="date" min={minDate} required /></label><label>To<input name="endDate" type="date" min={minDate} required /></label><label>Reason<textarea name="reason" minLength={5} maxLength={1000} required /></label><button className="button button-primary"><Send size={16} /> Send to HR</button></form>}
        <div className="record-list">{items.map((item) => <div key={item.id}><span><strong>{item.startDate} → {item.endDate}</strong><small>{item.reason}</small></span><code>{item.status}</code>{role === "HR Admin" && item.status === "PENDING" && <span className="approval-actions"><button className="button button-approve" onClick={() => void decide(item.id, "approve")}>Approve</button><button className="button button-reject" onClick={() => void decide(item.id, "reject")}>Reject</button></span>}</div>)}{items.length === 0 && <div className="empty-state"><FileClock size={27} /><strong>No leave requests</strong></div>}</div>
        {error && <div className="login-error" role="alert">{error}</div>}
    </article>;
}

