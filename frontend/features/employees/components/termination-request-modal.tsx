"use client";

import { brainServeApi, type EmployeeTerminationRequest, isBackendConfigured } from "../../../services/brainserve-api";
import { officeToday } from "../../../lib/appointments";
import { readDemoAccounts } from "../../../preview/accounts";
import {
    readDemoEssentialLogs,
    readDemoTerminations,
    writeDemoEssentialLogs,
    writeDemoTerminations,
} from "../../../preview/governance";
import { readDemoInternalNotifications, writeDemoInternalNotifications } from "../../../preview/notifications";
import { type Employee } from "../../../types/workspace";
import { fail } from "../../../utils/errors";
import { newClientId } from "../../../utils/ids";
import { Send, ShieldCheck, UserCog, X } from "lucide-react";
import { type FormEvent, useState } from "react";
import { useModalDialog } from "../../../hooks/use-modal-dialog";

export function TerminationRequestModal({ employee, userEmail, onClose, onSubmitted }: {
    employee: Employee; userEmail: string; onClose: () => void; onSubmitted: () => void;
}) {
    const [busy, setBusy] = useState(false);
    const [error, setError] = useState("");
    useModalDialog(onClose);
    const submit = async (event: FormEvent<HTMLFormElement>) => {
        event.preventDefault(); setBusy(true); setError("");
        const data = new FormData(event.currentTarget);
        const reason = String(data.get("reason") ?? "").trim();
        const effectiveDate = String(data.get("effectiveDate") ?? "");
        try {
            if (isBackendConfigured) {
                if (!employee.uuid) fail("This employee is missing its database identifier. Refresh and try again.");
                await brainServeApi.requestEmployeeTermination(employee.uuid, reason, effectiveDate);
            } else {
                if (readDemoTerminations().some((item) => item.employeeId === (employee.uuid ?? employee.id)
                    && item.status === "PENDING_CEO_APPROVAL")) fail("This employee already has a pending CEO review.");
                const requester = readDemoAccounts().find((item) => item.email.toLowerCase() === userEmail.toLowerCase());
                const now = new Date().toISOString();
                const created: EmployeeTerminationRequest = { id: newClientId(), employeeId: employee.uuid ?? employee.id,
                    employeeNumber: employee.id, employeeName: employee.name, employeeEmail: employee.email,
                    departmentId: employee.departmentId ?? "", requestedByHrUserId: requester?.id ?? "demo-hr-admin",
                    requestedByHrName: requester?.fullName ?? "Department HR Admin", reason, effectiveDate,
                    status: "PENDING_CEO_APPROVAL", requestedAt: now, decidedByCeoUserId: null,
                    decidedByCeoName: null, decidedAt: null, decisionNote: null };
                writeDemoTerminations([created, ...readDemoTerminations()]);
                writeDemoEssentialLogs([{ id: newClientId(), category: "EMPLOYEE_LIFECYCLE",
                    eventType: "TERMINATION_REQUESTED", subjectType: "EMPLOYEE", subjectId: created.employeeId,
                    referenceId: created.id, actorUserId: created.requestedByHrUserId, approverUserId: null,
                    status: created.status, title: `Termination requested for ${employee.name}`,
                    detail: `${employee.name} (${employee.id}) · ${reason}`, occurredAt: now }, ...readDemoEssentialLogs()]);
                writeDemoInternalNotifications([{ id: newClientId(), senderUserId: created.requestedByHrUserId,
                    recipientUserId: "demo-ceo", senderName: created.requestedByHrName, recipientName: "BrainServe CEO",
                    message: `Termination approval required for ${employee.name} (${employee.id}). Review the request in Terminations.`,
                    priority: "URGENT", category: "ACTION_REQUIRED", conversationKey: `termination:${created.id}`,
                    deliveryStatus: "DELIVERED", sentAt: now, deliveredAt: now, readAt: null,
                    senderEmail: userEmail, recipientEmail: "ceo@brainserve.in" }, ...readDemoInternalNotifications()]);
            }
            onSubmitted();
        } catch (reasonValue) { setError(reasonValue instanceof Error ? reasonValue.message : "Termination request could not be submitted."); }
        finally { setBusy(false); }
    };
    return <div className="modal-backdrop"><div className="modal termination-modal" role="dialog" aria-modal="true" aria-labelledby="termination-request-title"><header><div><span>HR TERMINATION REQUEST</span><h2 id="termination-request-title">Send to CEO for approval</h2><p>No access or employment status changes until CEO approves.</p></div><button type="button" className="icon-button" aria-label="Close termination request" onClick={onClose}><X size={18} /></button></header><form onSubmit={submit}>
        <div className="modal-review-source"><UserCog size={19} /><span><strong>{employee.name} · {employee.id}</strong><small>{employee.role} · {employee.department} · currently {employee.status.toLowerCase()}</small></span></div>
        <label>Reason for termination<textarea name="reason" minLength={5} maxLength={1000} required placeholder="State the documented business and policy reason." /></label>
        <label>Effective date<input name="effectiveDate" type="date" defaultValue={officeToday()} max={officeToday()} required /></label>
        <div className="decision-policy"><ShieldCheck size={18} /><span><strong>Two-step authorization</strong><small>HR submits this request. CEO decides it. Approval disables the employee login and closes any active Team Lead assignment.</small></span></div>
        {error && <div className="login-error" role="alert">{error}</div>}
        <div className="modal-actions"><button type="button" className="button button-secondary" onClick={onClose}>Cancel</button><button className="button button-primary" disabled={busy}><Send size={16} />{busy ? "Submitting…" : "Request CEO approval"}</button></div>
    </form></div></div>;
}

