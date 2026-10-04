"use client";
import { isBackendConfigured } from "../../lib/api-client";
import { useEffect, useState } from "react";
import { DraftStatus } from "./draft-status";
import { useFormDraft, draftBlocksSubmit, draftLocksFields } from "./use-form-draft";
import type { DraftFields } from "./draft-session";
import type { WorkspaceSetting } from "../../types/api";
import styles from "./drafts.module.css";
const keys = ["COMPANY.NAME", "COMPANY.EMAIL_DOMAIN", "COMPANY.HQ_ADDRESS", "COMPANY.SUPPORT_EMAIL"];

export function CompanyProfileDraft({ settings, accountScope, editable, onSaved }: { settings: WorkspaceSetting[]; accountScope: string; editable: boolean; onSaved: (fields: DraftFields) => Promise<void> }) {
    const ready = keys.every(key => settings.some(item => item.key === key));
    const [fields, setFields] = useState<DraftFields>(() => Object.fromEntries(keys.map(key => [key, settings.find(item => item.key === key)?.value ?? ""])));
    const draft = useFormDraft("COMPANY_PROFILE", "company", accountScope, editable && ready, fields);
    const [message, setMessage] = useState("");
    useEffect(() => {
        const clear = () => { setFields({}); setMessage(""); };
        window.addEventListener("brainserve:auth-session-changed", clear); window.addEventListener("brainserve:auth-session-expired", clear);
        return () => { window.removeEventListener("brainserve:auth-session-changed", clear); window.removeEventListener("brainserve:auth-session-expired", clear); };
    }, []);
    useEffect(() => {
        if (!["unsaved", "offline", "conflict", "unknown"].includes(draft.state.phase)) return;
        const leaving = (event: BeforeUnloadEvent) => { event.preventDefault(); event.returnValue = ""; }; window.addEventListener("beforeunload", leaving);
        return () => window.removeEventListener("beforeunload", leaving);
    }, [draft.state.phase]);
    const confirmed = async () => { await onSaved(fields); await draft.session.discard(); setMessage("Company profile saved and recorded in the audit trail."); };
    return <article className="panel glass-panel"><div className="panel-heading"><div><span>ORGANIZATION IDENTITY</span><h2>Company profile</h2><p>Save a draft while you prepare the company identity. Review all four fields before explicitly applying them.</p></div></div>
        {!ready ? <p role="status">Loading current company profile…</p> : <form onSubmit={async event => { event.preventDefault(); setMessage(""); if (!isBackendConfigured) { await onSaved(fields); setMessage("Company profile updated in demo preview."); return; } const receipt = await draft.session.submit(); if (receipt) await confirmed(); }}>
            <DraftStatus draft={draft} onRestore={setFields} onConfirmed={() => void confirmed()} />
            <fieldset className={`modal-form-grid ${styles.fields}`} disabled={!editable || draft.enabled && draftLocksFields(draft.state.phase)}>{keys.map(key => <label key={key}>{settings.find(item => item.key === key)?.description ?? key}<input name={key} type={key.endsWith("SUPPORT_EMAIL") ? "email" : "text"} required maxLength={2000} value={fields[key] ?? ""} onChange={event => setFields(current => ({ ...current, [key]: event.target.value }))} /></label>)}</fieldset>
            {editable && <div className="modal-actions"><button className="button button-primary" disabled={draft.enabled && draftBlocksSubmit(draft.state.phase)} type="submit">Apply company profile</button></div>}
        </form>}
        {message && <p role="status">{message}</p>}
    </article>;
}
