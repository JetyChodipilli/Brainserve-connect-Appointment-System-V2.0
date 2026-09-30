"use client";

import { brainServeApi, type InternalNotificationRecipient, isBackendConfigured, type ResourceDiscussion } from "../../services/brainserve-api";
import { type Role } from "../../types/workspace";
import { newClientId } from "../../utils/ids";
import { BriefcaseBusiness, CheckCircle2, Clock3, Send, Users } from "lucide-react";
import { type FormEvent, useCallback, useEffect, useState } from "react";

export function ResourceDiscussionWorkspace({ role, recipients }: { role: Role;
    recipients: InternalNotificationRecipient[] }) {
    const [items, setItems] = useState<ResourceDiscussion[]>([]);
    const [busy, setBusy] = useState("");
    const [error, setError] = useState("");
    const [message, setMessage] = useState("");
    const hrRecipients = recipients.filter((item) => item.roles.includes("ROLE_HR_ADMIN"));
    const [minimumMeeting] = useState(() => {
        const value = new Date(Date.now() + 60 * 60 * 1000);
        return new Date(value.getTime() - value.getTimezoneOffset() * 60 * 1000).toISOString().slice(0, 16);
    });

    const load = useCallback(async () => {
        if (!isBackendConfigured) return;
        try { setItems(await brainServeApi.resourceDiscussions()); setError(""); }
        catch (reason) { setError(reason instanceof Error ? reason.message : "Resource discussions could not be loaded."); }
    }, []);

    useEffect(() => { const initial = window.setTimeout(() => void load(), 0);
        return () => window.clearTimeout(initial); }, [load]);

    const create = async (event: FormEvent<HTMLFormElement>) => {
        event.preventDefault(); const form = event.currentTarget; const data = new FormData(form);
        setBusy("create"); setError(""); setMessage("");
        const payload = {
            hrRecipientUserId: String(data.get("hrRecipientUserId")), projectName: String(data.get("projectName")),
            requiredRoles: String(data.get("requiredRoles")), requestedHeadcount: Number(data.get("requestedHeadcount")),
            priority: String(data.get("priority")), preferredAt: new Date(String(data.get("preferredAt"))).toISOString(),
            justification: String(data.get("justification")),
        };
        try {
            const created = isBackendConfigured ? await brainServeApi.createResourceDiscussion(payload) : {
                id: newClientId(), requestedByUserId: "demo-team-lead", departmentId: "TECH", ...payload,
                status: "REQUESTED" as const, hrResponse: null, scheduledAt: null, hrDecidedAt: null,
                completedAt: null, createdAt: new Date().toISOString(), version: 0,
            } as ResourceDiscussion;
            setItems((current) => [created, ...current]); form.reset();
            setMessage("Resource discussion sent to HR through BrainServe Internal Calls.");
        } catch (reason) { setError(reason instanceof Error ? reason.message : "Resource discussion could not be created."); }
        finally { setBusy(""); }
    };

    const hrAction = async (item: ResourceDiscussion, event: FormEvent<HTMLFormElement>) => {
        event.preventDefault(); const form = event.currentTarget; const data = new FormData(form);
        const submitter = (event.nativeEvent as SubmitEvent).submitter as HTMLButtonElement | null;
        const action = (submitter?.value ?? "REQUEST_INFORMATION") as "SCHEDULE" | "REQUEST_INFORMATION" | "DECLINE";
        const scheduledValue = String(data.get("scheduledAt") ?? "");
        setBusy(item.id); setError(""); setMessage("");
        try {
            const updated = isBackendConfigured
                ? await brainServeApi.decideResourceDiscussion(item.id, action, String(data.get("response")),
                    action === "SCHEDULE" && scheduledValue ? new Date(scheduledValue).toISOString() : null)
                : { ...item, status: action === "SCHEDULE" ? "SCHEDULED" as const
                        : action === "DECLINE" ? "DECLINED" as const : "NEEDS_INFORMATION" as const,
                    hrResponse: String(data.get("response")), scheduledAt: action === "SCHEDULE" && scheduledValue
                        ? new Date(scheduledValue).toISOString() : null, hrDecidedAt: new Date().toISOString() };
            setItems((current) => current.map((value) => value.id === item.id ? updated : value));
            setMessage(`Resource discussion ${action.toLowerCase().replaceAll("_", " ")} update sent to the Team Lead.`);
        } catch (reason) { setError(reason instanceof Error ? reason.message : "HR action could not be saved."); }
        finally { setBusy(""); }
    };

    const complete = async (item: ResourceDiscussion) => {
        setBusy(item.id); setError("");
        try {
            const updated = isBackendConfigured ? await brainServeApi.completeResourceDiscussion(item.id)
                : { ...item, status: "COMPLETED" as const, completedAt: new Date().toISOString() };
            setItems((current) => current.map((value) => value.id === item.id ? updated : value));
            setMessage("Discussion marked completed and the other participant was notified.");
        } catch (reason) { setError(reason instanceof Error ? reason.message : "Discussion could not be completed."); }
        finally { setBusy(""); }
    };

    const revise = async (item: ResourceDiscussion, event: FormEvent<HTMLFormElement>) => {
        event.preventDefault(); const data = new FormData(event.currentTarget); setBusy(item.id); setError("");
        const payload = { requiredRoles: String(data.get("requiredRoles")),
            requestedHeadcount: Number(data.get("requestedHeadcount")),
            preferredAt: new Date(String(data.get("preferredAt"))).toISOString(),
            justification: String(data.get("justification")) };
        try {
            const updated = isBackendConfigured ? await brainServeApi.reviseResourceDiscussion(item.id, payload)
                : { ...item, ...payload, status: "REQUESTED" as const, hrResponse: null, hrDecidedAt: null };
            setItems((current) => current.map((value) => value.id === item.id ? updated : value));
            setMessage("Updated resource details sent back to HR.");
        } catch (reason) { setError(reason instanceof Error ? reason.message : "Resource details could not be revised."); }
        finally { setBusy(""); }
    };

    return <article className="panel glass-panel resource-discussion-panel">
        <div className="panel-heading"><div><span>PROJECT RESOURCE PLANNING</span><h2>{role === "Team Lead"
            ? "Discuss project resources with HR" : role === "HR Admin" ? "HR resource discussion queue"
                : "Resource planning visibility"}</h2><p>Structured requests, meeting decisions, real-time notifications and audit history remain connected.</p></div><BriefcaseBusiness size={22} /></div>
        {role === "Team Lead" && (hrRecipients.length ? <form className="resource-discussion-form" onSubmit={create}>
            <label>HR partner<select name="hrRecipientUserId" required>{hrRecipients.map((recipient) =>
                <option value={recipient.userId} key={recipient.userId}>{recipient.fullName} · {recipient.email}</option>)}</select></label>
            <label>Project name<input name="projectName" minLength={2} maxLength={160} required placeholder="Customer analytics platform" /></label>
            <label>Required roles or skills<input name="requiredRoles" maxLength={500} required placeholder="2 Java developers, 1 QA engineer" /></label>
            <label>Headcount<input name="requestedHeadcount" type="number" min={1} max={100} defaultValue={1} required /></label>
            <label>Priority<select name="priority" defaultValue="NORMAL"><option value="NORMAL">Normal</option><option value="HIGH">High</option><option value="URGENT">Urgent</option></select></label>
            <label>Preferred discussion time<input name="preferredAt" type="datetime-local" min={minimumMeeting} required /></label>
            <label className="full-field">Business justification<textarea name="justification" minLength={5} maxLength={1000} required placeholder="Explain workload, delivery date and why these resources are needed." /></label>
            <button className="button button-primary" disabled={busy === "create"}><Send size={16} />{busy === "create" ? "Sending…" : "Send resource request"}</button>
        </form> : <div className="empty-state"><Users size={27} /><strong>No active HR recipient</strong><small>An active HR Admin account is required.</small></div>)}
        <div className="resource-discussion-list">{items.map((item) => <section key={item.id} className={`resource-discussion-card priority-${item.priority.toLowerCase()}`}>
            <header><span><small>{item.priority} PRIORITY · {item.id.slice(0, 8).toUpperCase()}</small><strong>{item.projectName}</strong></span><code>{item.status.replaceAll("_", " ")}</code></header>
            <div className="resource-facts"><span><Users size={15} /><strong>{item.requestedHeadcount}</strong> requested</span><span><Clock3 size={15} />Preferred {new Date(item.preferredAt).toLocaleString("en-IN", { dateStyle: "medium", timeStyle: "short" })}</span></div>
            <p><strong>Skills:</strong> {item.requiredRoles}</p><p>{item.justification}</p>
            {item.hrResponse && <div className="resource-response"><strong>HR response</strong><span>{item.hrResponse}</span>{item.scheduledAt && <small>Meeting: {new Date(item.scheduledAt).toLocaleString("en-IN", { dateStyle: "full", timeStyle: "short" })}</small>}</div>}
            {role === "HR Admin" && item.status === "REQUESTED" && <form className="resource-decision-form" onSubmit={(event) => void hrAction(item, event)}>
                <label>Response<textarea name="response" maxLength={1000} placeholder="Add meeting details, questions or decline reason." /></label>
                <label>Meeting time<input name="scheduledAt" type="datetime-local" min={minimumMeeting} /></label>
                <div><button className="button button-approve" name="action" value="SCHEDULE" disabled={busy === item.id}>Schedule</button><button className="button button-secondary" name="action" value="REQUEST_INFORMATION" disabled={busy === item.id}>Request details</button><button className="button button-reject" name="action" value="DECLINE" disabled={busy === item.id}>Decline</button></div>
            </form>}
            {["Team Lead", "HR Admin"].includes(role) && item.status === "SCHEDULED" && <button className="button button-secondary" disabled={busy === item.id} onClick={() => void complete(item)}><CheckCircle2 size={15} /> Mark discussion complete</button>}
            {role === "Team Lead" && item.status === "NEEDS_INFORMATION" && <form className="resource-revision-form" onSubmit={(event) => void revise(item, event)}>
                <strong>Provide the details requested by HR</strong><label>Updated skills<input name="requiredRoles" defaultValue={item.requiredRoles} maxLength={500} required /></label><label>Headcount<input name="requestedHeadcount" type="number" min={1} max={100} defaultValue={item.requestedHeadcount} required /></label><label>Preferred time<input name="preferredAt" type="datetime-local" min={minimumMeeting} required /></label><label className="full-field">Updated justification<textarea name="justification" defaultValue={item.justification} minLength={5} maxLength={1000} required /></label><button className="button button-primary" disabled={busy === item.id}><Send size={15} /> Resubmit to HR</button>
            </form>}
        </section>)}{items.length === 0 && <div className="empty-state"><BriefcaseBusiness size={28} /><strong>No resource discussions yet</strong><small>{role === "Team Lead" ? "Create the first structured request above." : "New Team Lead requests will appear here."}</small></div>}</div>
        {message && <div className="success-banner"><CheckCircle2 size={17} />{message}</div>}{error && <div className="login-error" role="alert">{error}</div>}
    </article>;
}

