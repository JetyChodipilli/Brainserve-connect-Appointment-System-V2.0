"use client";

import { brainServeApi, type EmployeeTerminationRequest, isBackendConfigured } from "../../lib/api";
import { readDemoAccounts, writeDemoAccounts } from "../../preview/accounts";
import {
    readDemoEmployees,
    readDemoTeamLeadAssignments,
    writeDemoEmployees,
    writeDemoTeamLeadAssignments,
} from "../../preview/directory";
import {
    readDemoEssentialLogs,
    readDemoTerminations,
    writeDemoEssentialLogs,
    writeDemoTerminations,
} from "../../preview/governance";
import { readDemoInternalNotifications, writeDemoInternalNotifications } from "../../preview/notifications";
import { PageTitle } from "../../shared/components/page-title";
import { type Role } from "../../shared/types/workspace";
import { newClientId } from "../../shared/utils/ids";
import { Check, ShieldCheck, UserCog, X } from "lucide-react";
import { type FormEvent, useEffect, useState } from "react";

export function TerminationsView({ role, userEmail, onEmployeeTerminated }: {
    role: Role; userEmail: string; onEmployeeTerminated: (employeeId: string) => void;
}) {
    const [requests, setRequests] = useState<EmployeeTerminationRequest[]>([]);
    const [selected, setSelected] = useState<EmployeeTerminationRequest | null>(null);
    const [decision, setDecision] = useState<"approve" | "reject">("approve");
    const [busy, setBusy] = useState(false);
    const [error, setError] = useState("");
    useEffect(() => {
        let active = true;
        const load = async () => {
            try {
                const values = isBackendConfigured
                    ? role === "HR Admin"
                        ? await brainServeApi.myEmployeeTerminations()
                        : await Promise.all([
                            brainServeApi.pendingEmployeeTerminations(),
                            brainServeApi.employeeTerminationHistory(),
                        ]).then(([pending, history]) => Array.from(
                            new Map([...pending, ...history].map((item) => [item.id, item])).values(),
                        ))
                    : readDemoTerminations();
                if (active) setRequests(values);
            } catch (reason) { if (active) setError(reason instanceof Error ? reason.message : "Termination requests could not be loaded."); }
        };
        void load(); return () => { active = false; };
    }, [role]);
    const decide = async (event: FormEvent<HTMLFormElement>) => {
        event.preventDefault(); if (!selected) return;
        setBusy(true); setError(""); const note = String(new FormData(event.currentTarget).get("note") ?? "").trim();
        try {
            let updated: EmployeeTerminationRequest;
            if (isBackendConfigured) updated = await brainServeApi.decideEmployeeTermination(selected.id, decision, note);
            else {
                const ceo = readDemoAccounts().find((item) => item.email.toLowerCase() === userEmail.toLowerCase());
                const now = new Date().toISOString();
                updated = { ...selected, status: decision === "approve" ? "APPROVED" : "REJECTED",
                    decidedByCeoUserId: ceo?.id ?? "demo-ceo", decidedByCeoName: ceo?.fullName ?? "BrainServe CEO",
                    decidedAt: now, decisionNote: note || null };
                writeDemoTerminations(readDemoTerminations().map((item) => item.id === updated.id ? updated : item));
                if (decision === "approve") {
                    const nextEmployees = readDemoEmployees().map((item) => (item.uuid ?? item.id) === updated.employeeId
                        ? { ...item, status: "Terminated" as const } : item);
                    writeDemoEmployees(nextEmployees);
                    writeDemoAccounts(readDemoAccounts().map((item) => item.employeeId === updated.employeeId
                    || item.email.toLowerCase() === updated.employeeEmail.toLowerCase()
                        ? { ...item, enabled: false, status: "DISABLED" } : item));
                    writeDemoTeamLeadAssignments(readDemoTeamLeadAssignments().map((item) => item.active
                    && item.teamLeadEmployeeId === updated.employeeId
                        ? { ...item, active: false, endedByUserId: updated.decidedByCeoUserId, endedAt: now } : item));
                    onEmployeeTerminated(updated.employeeId);
                }
                writeDemoEssentialLogs([{ id: newClientId(), category: "EMPLOYEE_LIFECYCLE",
                    eventType: decision === "approve" ? "TERMINATION_APPROVED" : "TERMINATION_REJECTED",
                    subjectType: "EMPLOYEE", subjectId: updated.employeeId, referenceId: updated.id,
                    actorUserId: updated.requestedByHrUserId, approverUserId: updated.decidedByCeoUserId,
                    status: updated.status, title: `Termination ${decision === "approve" ? "approved" : "rejected"} for ${updated.employeeName}`,
                    detail: note || updated.reason, occurredAt: now }, ...readDemoEssentialLogs()]);
                writeDemoInternalNotifications([{ id: newClientId(), senderUserId: updated.decidedByCeoUserId ?? "demo-ceo",
                    recipientUserId: updated.requestedByHrUserId, senderName: updated.decidedByCeoName ?? "BrainServe CEO",
                    recipientName: updated.requestedByHrName,
                    message: `CEO ${decision === "approve" ? "approved" : "rejected"} the termination request for ${updated.employeeName}${note ? `: ${note}` : "."}`,
                    priority: "URGENT", category: "ACTION_REQUIRED", conversationKey: `termination:${updated.id}`,
                    deliveryStatus: "DELIVERED", sentAt: now, deliveredAt: now, readAt: null,
                    senderEmail: userEmail, recipientEmail: "hr.admin@brainserve.in" }, ...readDemoInternalNotifications()]);
            }
            setRequests((items) => items.map((item) => item.id === updated.id ? updated : item));
            if (decision === "approve" && isBackendConfigured) onEmployeeTerminated(updated.employeeId);
            setSelected(null);
        } catch (reasonValue) { setError(reasonValue instanceof Error ? reasonValue.message : "CEO decision could not be saved."); }
        finally { setBusy(false); }
    };
    const pending = requests.filter((item) => item.status === "PENDING_CEO_APPROVAL");
    return <><PageTitle eyebrow="EMPLOYEE GOVERNANCE" title={role === "CEO" ? "Termination approvals" : "Termination requests"}
                        detail={role === "CEO" ? "Review HR evidence before employment access is disabled. Every decision becomes an immutable audit and business log." : "Track requests submitted by HR. Employee access remains active until CEO approval."} />
        <section className="employee-summary"><div><strong>{pending.length}</strong><span>Awaiting CEO</span></div><i /><div><strong>{requests.filter((item) => item.status === "APPROVED").length}</strong><span>Approved</span></div><i /><div><strong>{requests.filter((item) => item.status === "REJECTED").length}</strong><span>Rejected</span></div><i /><div><strong>{requests.length}</strong><span>Total retained</span></div></section>
        {error && <div className="login-error" role="alert">{error}</div>}
        <article className="panel glass-panel records-panel"><div className="panel-heading"><div><span>CEO-CONTROLLED LIFECYCLE</span><h2>Employee termination register</h2><p>HR request, CEO decision and effective date remain joined to the employee record.</p></div><b>{requests.length}</b></div><div className="records-table-wrap"><table className="records-table termination-records-table"><thead><tr><th>Employee</th><th>HR request</th><th>Effective date</th><th>Status</th><th>CEO decision</th></tr></thead><tbody>{requests.map((item) => <tr key={item.id}><td><strong>{item.employeeName}</strong><small>{item.employeeNumber} · {item.employeeEmail}</small><code>{item.id}</code></td><td><strong>{item.requestedByHrName}</strong><small>{item.reason}</small><small>{new Date(item.requestedAt).toLocaleString("en-IN", { dateStyle: "medium", timeStyle: "short" })}</small></td><td><strong>{item.effectiveDate}</strong><small>Access changes only after approval</small></td><td><span className={`business-status business-${item.status.toLowerCase()}`}>{item.status.replaceAll("_", " ")}</span></td><td>{role === "CEO" && item.status === "PENDING_CEO_APPROVAL" ? <div className="approval-actions"><button className="button button-reject" onClick={() => { setDecision("reject"); setSelected(item); }}><X size={15} />Reject</button><button className="button button-approve" onClick={() => { setDecision("approve"); setSelected(item); }}><Check size={15} />Approve</button></div> : <><strong>{item.decidedByCeoName ?? "Pending CEO"}</strong><small>{item.decisionNote ?? "No decision note"}</small></>}</td></tr>)}{requests.length === 0 && <tr><td colSpan={5}><div className="empty-state table-empty"><UserCog size={28} /><strong>No termination requests</strong><small>Requests initiated from the HR employee directory will appear here.</small></div></td></tr>}</tbody></table></div></article>
        {selected && <div className="modal-backdrop"><div className="modal termination-modal"><header><div><span>CEO DECISION</span><h2>{decision === "approve" ? "Approve termination" : "Reject termination"}</h2><p>{selected.employeeName} · {selected.employeeNumber}</p></div><button className="icon-button" onClick={() => setSelected(null)}><X size={18} /></button></header><form onSubmit={decide}><div className="modal-review-source"><ShieldCheck size={19} /><span><strong>HR reason</strong><small>{selected.reason}</small></span></div><label>{decision === "approve" ? "Decision note (optional)" : "Rejection reason"}<textarea name="note" minLength={decision === "reject" ? 5 : undefined} maxLength={1000} required={decision === "reject"} placeholder={decision === "approve" ? "Optional governance note" : "Explain what HR must correct or reconsider."} /></label><div className="modal-actions"><button type="button" className="button button-secondary" onClick={() => setSelected(null)}>Cancel</button><button className={`button ${decision === "approve" ? "button-approve" : "button-reject"}`} disabled={busy}>{busy ? "Saving…" : decision === "approve" ? "Approve & disable access" : "Reject request"}</button></div></form></div></div>}
    </>;
}

