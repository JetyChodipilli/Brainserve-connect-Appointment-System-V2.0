"use client";

import {
    type ArchiveManifest,
    brainServeApi,
    type DataLegalHold,
    type GovernanceLedgerEntry,
    type GovernanceOverview,
    isBackendConfigured,
    type RetentionPolicy,
} from "../../../lib/api";
import { Archive, CheckCircle2, FileClock, Fingerprint, LockKeyhole, ShieldCheck } from "lucide-react";
import { type FormEvent, useCallback, useEffect, useState } from "react";

export function DataGovernancePanel() {
    const [policies, setPolicies] = useState<RetentionPolicy[]>([]);
    const [manifests, setManifests] = useState<ArchiveManifest[]>([]);
    const [holds, setHolds] = useState<DataLegalHold[]>([]);
    const [ledgerEntries, setLedgerEntries] = useState<GovernanceLedgerEntry[]>([]);
    const [overview, setOverview] = useState<GovernanceOverview | null>(null);
    const [holdScope, setHoldScope] = useState<DataLegalHold["scopeType"]>("DATASET");
    const [releaseReasons, setReleaseReasons] = useState<Record<string, string>>({});
    const [busy, setBusy] = useState("");
    const [error, setError] = useState("");
    const [message, setMessage] = useState("");
    const load = useCallback(async () => {
        if (!isBackendConfigured) return;
        try {
            const [nextPolicies, nextManifests, nextHolds, nextOverview, nextLedger] = await Promise.all([
                brainServeApi.retentionPolicies(), brainServeApi.archiveManifests(), brainServeApi.dataLegalHolds(),
                brainServeApi.governanceOverview(), brainServeApi.governanceLedger(50),
            ]);
            setPolicies(nextPolicies); setManifests(nextManifests); setHolds(nextHolds);
            setOverview(nextOverview); setLedgerEntries(nextLedger.items); setError("");
        }
        catch (reason) { setError(reason instanceof Error ? reason.message : "Data-governance settings could not be loaded."); }
    }, []);
    useEffect(() => { const timer = window.setTimeout(() => void load(), 0); return () => window.clearTimeout(timer); }, [load]);
    const save = async (policy: RetentionPolicy, event: FormEvent<HTMLFormElement>) => {
        event.preventDefault(); setBusy(policy.dataset); setError(""); setMessage("");
        const data = new FormData(event.currentTarget);
        try {
            const updated = await brainServeApi.updateRetentionPolicy(policy.dataset, {
                hotDays: Number(data.get("hotDays")), warmMonths: Number(data.get("warmMonths")),
                archiveYears: Number(data.get("archiveYears")),
                disposalAction: String(data.get("disposalAction")) as RetentionPolicy["disposalAction"],
                enabled: data.get("enabled") === "on",
            });
            setPolicies((items) => items.map((item) => item.dataset === updated.dataset ? updated : item));
            setMessage(`${updated.dataset.replaceAll("_", " ")} retention policy saved.`);
        } catch (reason) { setError(reason instanceof Error ? reason.message : "Retention policy could not be saved."); }
        finally { setBusy(""); }
    };

    const createHold = async (event: FormEvent<HTMLFormElement>) => {
        event.preventDefault(); setBusy("create-hold"); setError(""); setMessage("");
        const formElement = event.currentTarget;
        const form = new FormData(formElement);
        try {
            const created = await brainServeApi.createDataLegalHold({
                dataset: String(form.get("dataset")), holdKind: String(form.get("holdKind")) as DataLegalHold["holdKind"],
                scopeType: holdScope, scopeRef: holdScope === "DATASET" ? null : String(form.get("scopeRef") ?? "").trim(),
                caseReference: String(form.get("caseReference")), reason: String(form.get("reason")),
                reviewOn: String(form.get("reviewOn") ?? "") || null,
            });
            setHolds((items) => [created, ...items]); setMessage("The hold is active. Archive removal and disposal are blocked.");
            formElement.reset(); setHoldScope("DATASET"); await load();
        } catch (reason) { setError(reason instanceof Error ? reason.message : "The legal hold could not be placed."); }
        finally { setBusy(""); }
    };

    const releaseHold = async (hold: DataLegalHold) => {
        const reason = releaseReasons[hold.id]?.trim();
        if (!reason) { setError("Enter a release reason before releasing the hold."); return; }
        setBusy(`release:${hold.id}`); setError(""); setMessage("");
        try {
            const released = await brainServeApi.releaseDataLegalHold(hold.id, reason);
            setHolds((items) => items.map((item) => item.id === released.id ? released : item));
            setReleaseReasons((items) => ({ ...items, [hold.id]: "" }));
            setMessage("The hold was formally released and the lifecycle may resume."); await load();
        } catch (reasonValue) { setError(reasonValue instanceof Error ? reasonValue.message : "The hold could not be released."); }
        finally { setBusy(""); }
    };

    const activeHolds = holds.filter((item) => !item.releasedAt);
    const verifiedArchives = manifests.filter((item) => ["VERIFIED", "DATABASE_REMOVED"].includes(item.status)).length;
    return <div className="governance-workspace">
        <article className="panel glass-panel governance-panel"><div className="panel-heading"><div><span>HOT · WARM · ENCRYPTED COLD STORAGE</span><h2>Database history lifecycle</h2><p>Each dataset has its own retention clock. A partition is removed only after AES-256-GCM archival, checksum verification and a successful restore test.</p></div><Archive size={22} /></div>
            {!isBackendConfigured && <div className="governance-connection-note"><LockKeyhole size={18} /><span><strong>Backend connection required</strong><small>Retention policies, legal holds and the immutable ledger are intentionally never stored in browser preview data.</small></span></div>}
            <div className="governance-manifest-summary"><span><strong>{overview?.activeHolds ?? activeHolds.length}</strong><small>Active holds</small></span><span><strong>{verifiedArchives}</strong><small>Restore-tested archives</small></span><span><strong>{overview?.pendingBackupExpiries ?? 0}</strong><small>Backup expiries pending</small></span><span className={overview?.ledgerIntegrityValid === false ? "governance-warning" : ""}><strong>{overview ? (overview.ledgerIntegrityValid ? "Healthy" : "Review") : "—"}</strong><small>Ledger integrity · {overview?.ledgerEntriesChecked ?? 0} entries</small></span></div>
            <div className="governance-policy-list">{policies.map((policy) => <form key={`${policy.dataset}:${policy.updatedAt}`} onSubmit={(event) => void save(policy, event)}><header><strong>{policy.dataset.replaceAll("_", " ")}</strong><label><input name="enabled" type="checkbox" defaultChecked={policy.enabled} /> Enabled</label></header><div><label>Hot days<input name="hotDays" type="number" min={1} max={3650} defaultValue={policy.hotDays} required /></label><label>Warm months<input name="warmMonths" type="number" min={1} max={240} defaultValue={policy.warmMonths} required /></label><label>Archive years<input name="archiveYears" type="number" min={1} max={25} defaultValue={policy.archiveYears} required /></label><label>At expiry<select name="disposalAction" defaultValue={policy.disposalAction}><option value="ANONYMIZE">Anonymize</option><option value="DELETE">Secure delete</option></select></label><button className="button button-secondary" disabled={busy === policy.dataset}>{busy === policy.dataset ? "Saving…" : "Save policy"}</button></div></form>)}</div>
        </article>

        <article className="panel glass-panel governance-panel"><div className="panel-heading"><div><span>LEGAL HOLD · INVESTIGATION</span><h2>Preservation controls</h2><p>An active hold overrides every retention deadline. Dataset holds are broad; partition and subject holds preserve a specific evidence scope.</p></div><ShieldCheck size={22} /></div>
            <form className="governance-hold-form" onSubmit={(event) => void createHold(event)}>
                <label>Dataset<select name="dataset" required><option value="">Select dataset</option>{policies.map((policy) => <option key={policy.dataset} value={policy.dataset}>{policy.dataset.replaceAll("_", " ")}</option>)}</select></label>
                <label>Hold type<select name="holdKind" defaultValue="LEGAL_HOLD"><option value="LEGAL_HOLD">Legal hold</option><option value="ACTIVE_INVESTIGATION">Active investigation</option></select></label>
                <label>Scope<select name="scopeType" value={holdScope} onChange={(event) => setHoldScope(event.target.value as DataLegalHold["scopeType"])}><option value="DATASET">Entire dataset</option><option value="PARTITION">Archive partition</option><option value="SUBJECT">Specific record/person</option></select></label>
                {holdScope !== "DATASET" && <label>Scope reference<input name="scopeRef" placeholder={holdScope === "PARTITION" ? "employee_history_event_2026_07" : "Record UUID"} required /></label>}
                <label>Case reference<input name="caseReference" maxLength={120} placeholder="CASE-2026-001" required /></label>
                <label>Review date<input name="reviewOn" type="date" /></label>
                <label className="governance-hold-reason">Reason<textarea name="reason" maxLength={1200} placeholder="Why this data must be preserved" required /></label>
                <button className="button button-primary" disabled={busy === "create-hold"}><LockKeyhole size={15} />{busy === "create-hold" ? "Placing hold…" : "Place hold"}</button>
            </form>
            <div className="governance-hold-list">{activeHolds.map((hold) => <div key={hold.id}><span><strong>{hold.caseReference} · {hold.dataset.replaceAll("_", " ")}</strong><small>{hold.holdKind.replaceAll("_", " ")} · {hold.scopeType.replaceAll("_", " ")}{hold.scopeRef ? ` · ${hold.scopeRef}` : ""}</small><small>{hold.reason}</small></span><div><input aria-label={`Release reason for ${hold.caseReference}`} value={releaseReasons[hold.id] ?? ""} onChange={(event) => setReleaseReasons((items) => ({ ...items, [hold.id]: event.target.value }))} placeholder="Formal release reason" /><button type="button" className="button button-secondary" disabled={busy === `release:${hold.id}`} onClick={() => void releaseHold(hold)}>{busy === `release:${hold.id}` ? "Releasing…" : "Release hold"}</button></div></div>)}{activeHolds.length === 0 && <div className="empty-state"><ShieldCheck size={25} /><strong>No active legal holds</strong><small>Eligible records can follow their configured lifecycle.</small></div>}</div>
        </article>

        <article className="panel glass-panel governance-panel"><div className="panel-heading"><div><span>ARCHIVE EVIDENCE</span><h2>Verification and removal status</h2><p>Checksums, encryption-key versions, restore results, database removal and backup expiry remain visible without exposing archive contents.</p></div><FileClock size={22} /></div>
            <div className="governance-manifest-table">{manifests.slice(0, 20).map((item) => <div key={item.partitionName}><span><strong>{item.dataset.replaceAll("_", " ")}</strong><small>{item.periodStart} → {item.periodEnd}</small></span><span><strong>{item.rowCount.toLocaleString("en-IN")} rows</strong><small>{item.encryptionAlgorithm ?? "Awaiting encryption"}{item.encryptionKeyVersion ? ` · ${item.encryptionKeyVersion}` : ""}</small></span><span className={`governance-status status-${item.status.toLowerCase()}`}><strong>{item.status.replaceAll("_", " ")}</strong><small>{item.holdBlocked ? "Blocked by active hold" : item.restoreTestedAt ? "Restore tested" : item.lastError ?? "Lifecycle pending"}</small></span></div>)}{manifests.length === 0 && <div className="empty-state"><Archive size={25} /><strong>No archive manifests yet</strong><small>The scheduled discovery creates manifests when monthly history becomes eligible.</small></div>}</div>
        </article>

        <article className="panel glass-panel governance-panel"><div className="panel-heading"><div><span>APPEND-ONLY EVIDENCE</span><h2>Immutable governance ledger</h2><p>Policy changes, holds, archive verification, database removal, anonymization, deletion and backup expiry are hash chained and protected from update or deletion.</p></div><Fingerprint size={22} /></div>
            <div className="governance-ledger-list">{ledgerEntries.slice(0, 20).map((entry) => <div key={entry.id}><code>#{entry.sequence}</code><span><strong>{entry.actionType.replaceAll("_", " ")}</strong><small>{entry.dataset.replaceAll("_", " ")} · {entry.targetRef}</small></span><span><strong>{entry.outcome}</strong><small>{new Date(entry.occurredAt).toLocaleString("en-IN")} · {entry.entryHash.slice(0, 12)}…</small></span></div>)}{ledgerEntries.length === 0 && <div className="empty-state"><Fingerprint size={25} /><strong>No governance actions recorded</strong><small>The first policy, hold or lifecycle action starts the immutable chain.</small></div>}</div>
        </article>
        {message && <div className="success-banner"><CheckCircle2 size={17} /> {message}</div>}
        {error && <div className="login-error" role="alert">{error}</div>}
    </div>;
}

