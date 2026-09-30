"use client";

import { brainServeApi, type VisitorIdentity } from "../../lib/api";
import { StatusPill } from "../../shared/components/status-pill";
import { visitorInitials } from "../appointments/appointment-utils";
import { CheckCircle2, IdCard, Search, ShieldCheck } from "lucide-react";
import { type FormEvent, useState } from "react";

export function VisitorIdentityRegistry() {
    const [query, setQuery] = useState("");
    const [visitors, setVisitors] = useState<VisitorIdentity[]>([]);
    const [selected, setSelected] = useState<VisitorIdentity | null>(null);
    const [busy, setBusy] = useState("");
    const [error, setError] = useState("");
    const search = async (event: FormEvent<HTMLFormElement>) => {
        event.preventDefault();
        setBusy("search"); setError(""); setSelected(null);
        try {
            const result = await brainServeApi.searchVisitors(query.trim(), 0, 25);
            setVisitors(result.content);
        } catch (reason) {
            setError(reason instanceof Error ? reason.message : "Visitor identities could not be searched.");
        } finally { setBusy(""); }
    };
    const open = async (visitor: VisitorIdentity) => {
        setBusy(visitor.id); setError("");
        try { setSelected(await brainServeApi.visitor(visitor.id)); }
        catch (reason) {
            setError(reason instanceof Error ? reason.message : "The visitor identity could not be loaded.");
        } finally { setBusy(""); }
    };
    const verify = async () => {
        if (!selected) return;
        setBusy("verify"); setError("");
        try {
            const verified = await brainServeApi.verifyVisitor(selected.id);
            setSelected(verified);
            setVisitors((items) => items.map((item) => item.id === verified.id ? verified : item));
        } catch (reason) {
            setError(reason instanceof Error ? reason.message : "The visitor identity could not be verified.");
        } finally { setBusy(""); }
    };
    return <article className="panel glass-panel visitor-identity-registry">
        <div className="panel-heading"><div><span>VISITOR IDENTITY SERVICE</span><h2>Find and verify returning visitors</h2>
            <p>Search the PostgreSQL visitor registry by name or email. Verification is audited and never stored in browser Preview data.</p></div><IdCard size={22} /></div>
        <form className="inline-account-form" onSubmit={search}><label>Visitor search<input value={query}
                                                                                            onChange={(event) => setQuery(event.target.value)} minLength={2}
                                                                                            placeholder="Name or email address" required /></label><button className="button button-secondary"
                                                                                                                                                           disabled={busy === "search"}><Search size={16} />{busy === "search" ? "Searching…" : "Search registry"}</button></form>
        <div className="visitor-identity-results">{visitors.map((visitor) => <button type="button"
                                                                                     key={visitor.id} onClick={() => void open(visitor)} disabled={busy === visitor.id}>
            <span className="avatar">{visitorInitials(visitor.name)}</span><span><strong>{visitor.name}</strong>
        <small>{visitor.email} · {visitor.company ?? "Independent visitor"}</small></span>
            <StatusPill status={visitor.identityVerified ? "Verified" : "Pending verification"} />
        </button>)}{visitors.length === 0 && <div className="empty-state compact-empty"><Search size={24} />
            <strong>Search the live visitor registry</strong><small>Registration records appear here after public booking.</small></div>}</div>
        {selected && <div className="visitor-identity-detail"><IdCard size={20} /><span><strong>{selected.name}</strong>
      <small>{selected.phone} · {selected.governmentIdMasked ?? "No government ID supplied"} · consent {selected.consentVersion}</small></span>
            {selected.identityVerified ? <span className="protected-lifecycle-label"><CheckCircle2 size={15} /> Verified</span>
                : <button type="button" className="button button-approve" disabled={busy === "verify"}
                          onClick={() => void verify()}><ShieldCheck size={15} />{busy === "verify" ? "Verifying…" : "Verify identity"}</button>}</div>}
        {error && <div className="login-error" role="alert">{error}</div>}
    </article>;
}

