"use client";
import type { DraftFields } from "./draft-session";
import type { useFormDraft } from "./use-form-draft";
import styles from "./drafts.module.css";

export function DraftStatus({ draft, onRestore, onConfirmed }: { draft: ReturnType<typeof useFormDraft>; onRestore: (fields: DraftFields) => void; onConfirmed?: () => void }) {
    if (!draft.enabled) return null;
    const { state, session } = draft;
    const label = ({ loading: "Checking saved draft…", ready: "Unsaved", unsaved: "Unsaved changes", saving: "Saving draft…", saved: "Draft saved", pending: "Saved draft available", offline: "Unsaved · service unavailable", conflict: "Draft changed in another tab", denied: "Draft access removed", submitting: "Submitting…", unknown: "Submission needs confirmation", submitted: "Submission confirmed" } as const)[state.phase];
    return <section className={styles.status} aria-label="Saved draft" data-draft-phase={state.phase}>
        <div role="status" aria-live="polite"><strong>{label}</strong>{state.saved && !state.saved.receipt && <small>Saved {new Date(state.saved.updatedAt).toLocaleString()} · Expires {new Date(state.saved.expiresAt).toLocaleDateString()}</small>}</div>
        {state.message && <p role={["offline", "conflict", "denied", "unknown"].includes(state.phase) ? "alert" : undefined}>{state.message}</p>}
        {state.phase === "pending" && <><p>Restore replaces the open form with your saved fields. Discard deletes the saved draft and keeps your open form.</p><div className={styles.actions}><button type="button" className="button button-secondary" onClick={() => { const fields = session.restore(); if (fields) onRestore(fields); }}>Restore draft</button><button type="button" className="button button-secondary" onClick={() => void session.discard()}>Discard saved draft</button></div></>}
        {(state.phase === "conflict" || state.phase === "unknown") && <button type="button" className="button button-secondary" onClick={() => void session.load()}>Check current saved draft</button>}
        {state.phase === "offline" && <button type="button" className="button button-secondary" onClick={() => void session.load()}>Reconnect draft service</button>}
        {(state.phase === "saved" || state.phase === "unsaved") && state.saved && <button type="button" className="button button-secondary" onClick={() => void session.discard()}>Discard saved draft</button>}
        {state.phase === "submitted" && <button type="button" className="button button-secondary" onClick={() => void session.discard()}>Clear confirmed submission receipt</button>}
        {state.phase === "submitted" && onConfirmed && <button type="button" className="button button-primary" onClick={onConfirmed}>Continue after confirmed submission</button>}
    </section>;
}
