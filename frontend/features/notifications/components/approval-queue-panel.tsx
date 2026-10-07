'use client';

import { useCallback, useEffect, useRef, useState, type FormEvent } from 'react';
import { Clock3, ShieldCheck } from 'lucide-react';
import { isBackendConfigured } from '../../../lib/api-client';
import type { Role } from '../../../types/workspace';
import { workboardApi } from '../../workboard/api/workboard-api';
import type { WorkPlanning } from '../../workboard/types/workboard';
import { notificationPolicyApi } from '../api/notification-policy-api';
import type { ApprovalPolicy, ApprovalQueue, ApprovalQueueItem } from '../types/notification-policy';

const readable = (value: string) => value.replaceAll('_', ' ').toLowerCase();
const date = (value: string) => new Date(value).toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' });

export function ApprovalQueuePanel({ role }: { role: Role }) {
    const [queue, setQueue] = useState<ApprovalQueue | null>(null);
    const [overdue, setOverdue] = useState(false);
    const [page, setPage] = useState(0);
    const [busy, setBusy] = useState(false);
    const [error, setError] = useState('');
    const [ended, setEnded] = useState(false);
    const generation = useRef(0);
    const controller = useRef<AbortController | null>(null);
    const load = useCallback(async () => {
        if (!isBackendConfigured) return;
        controller.current?.abort(); controller.current = new AbortController(); const current = ++generation.current;
        setBusy(true); setQueue(null); setError('');
        try {
            const result = await notificationPolicyApi.queue(overdue, page, controller.current.signal);
            if (current === generation.current) setQueue(result);
        } catch (reason) { if (current === generation.current) setError(reason instanceof Error ? reason.message : 'Approval queue unavailable.'); }
        finally { if (current === generation.current) setBusy(false); }
    }, [overdue, page]);
    const cancelPending = useCallback(() => { generation.current++; controller.current?.abort(); }, []);
    useEffect(() => {
        const timer = window.setTimeout(() => void load(), 0);
        const clear = () => { generation.current++; controller.current?.abort(); setQueue(null); setError(''); setEnded(true); setBusy(false); };
        window.addEventListener('brainserve:auth-session-changed', clear); window.addEventListener('brainserve:auth-session-expired', clear);
        return () => { window.clearTimeout(timer); cancelPending(); window.removeEventListener('brainserve:auth-session-changed', clear); window.removeEventListener('brainserve:auth-session-expired', clear); };
    }, [load, cancelPending]);
    return <section className='notification-policy-panel panel glass-panel' aria-labelledby='approval-queue-title'>
        <header className='notification-policy-heading'><div><h2 id='approval-queue-title'>Approval deadlines & delegation</h2><p>Unresolved stages keep their original policy and entry time.</p></div><Clock3 size={22} aria-hidden='true' /></header>
        <p className='notification-mandatory-note'><ShieldCheck size={20} aria-hidden='true' /><span>Escalation sends a reminder. Review authority, evidence authorship and final CEO decisions remain governed by the existing workflow.</span></p>
        {!isBackendConfigured && <p>Approval deadlines and delegation require the connected service.</p>}
        {ended ? <p role='status'>The account changed. Open the queue from the current workspace.</p> : isBackendConfigured && <>
            <div className='notification-policy-actions'><label className='notification-check'><input type='checkbox' checked={overdue} disabled={busy} onChange={e => { setOverdue(e.target.checked); setPage(0); }} />Overdue stages only</label><button type='button' className='button button-secondary' disabled={busy} onClick={() => void load()}>Reload approval queue</button></div>
            {error && <p className='login-error' role='alert'>{error}</p>}
            {busy && <p role='status'>Loading current approval stages…</p>}
            {queue && <><p className='notification-policy-meta'>Updated {date(queue.generatedAt)} · deadlines use elapsed minutes, including weekends and holidays.</p>
                {queue.items.map(item => <ApprovalStageCard key={`${item.id}:${item.resourceVersion}:${item.delegationId}`} item={item} refresh={load} />)}
                {queue.items.length === 0 && <p role='status'>{overdue ? 'No eligible overdue stages on this page.' : 'No eligible unresolved stages on this page.'}{queue.hasMore ? ' Continue to the next source page.' : ''}</p>}
                <div className='notification-policy-actions'><button type='button' className='button button-secondary' disabled={page === 0 || busy} onClick={() => setPage(p => p - 1)}>Previous approval page</button><span>Page {page + 1}</span><button type='button' className='button button-secondary' disabled={!queue.hasMore || busy} onClick={() => setPage(p => p + 1)}>Next approval page</button></div>
            </>}
            {role === 'System Admin' && <ApprovalPolicyEditor />}
        </>}
    </section>;
}

function ApprovalStageCard({ item, refresh }: { item: ApprovalQueueItem; refresh: () => Promise<void> }) {
    const [remarks, setRemarks] = useState('');
    const [candidate, setCandidate] = useState('');
    const [candidates, setCandidates] = useState<{ userId: string; name: string }[] | null>(null);
    const [expires, setExpires] = useState('');
    const [reason, setReason] = useState('');
    const [busy, setBusy] = useState(false);
    const [blocked, setBlocked] = useState(false);
    const [error, setError] = useState('');
    const alive = useRef(true);
    const controller = useRef(new AbortController());
    const errorRef = useRef<HTMLParagraphElement>(null);
    useEffect(() => { alive.current = true; controller.current = new AbortController(); const owned = controller.current; return () => { alive.current = false; owned.abort(); }; }, []);
    const mutation = async (operation: () => Promise<unknown>) => {
        if (busy || blocked) return; setBusy(true); setError('');
        try { await operation(); if (alive.current) await refresh(); }
        catch (failure) {
            if (!alive.current) return;
            setBlocked(true); setError(`${failure instanceof Error ? failure.message : 'The action was not confirmed.'} Reload the approval queue to reconcile the current stage before another action.`);
            window.requestAnimationFrame(() => errorRef.current?.focus());
        } finally { if (alive.current) setBusy(false); }
    };
    const loadCandidates = async () => {
        setBusy(true); setError('');
        try { const result = await notificationPolicyApi.candidates(item.id, controller.current.signal); if (alive.current) { setCandidates(result); setCandidate(''); } }
        catch (failure) { if (alive.current) setError(failure instanceof Error ? failure.message : 'Eligible delegates unavailable.'); }
        finally { if (alive.current) setBusy(false); }
    };
    const delegate = (event: FormEvent) => {
        event.preventDefault(); if (!candidate || !expires || reason.trim().length < 5) return;
        void mutation(() => notificationPolicyApi.delegate(item.id, candidate, new Date(expires).toISOString(), reason.trim(), controller.current.signal));
    };
    const corrective = item.kind === 'WORK' && item.status === 'INSIGHT_REWORK_REQUESTED';
    return <details className='approval-stage-card'>
        <summary><span><strong>{item.title}</strong><small>{readable(item.kind)} · {readable(item.stage)} · {item.delegated ? 'Delegated to you' : 'Current review stage'}</small></span><span className='approval-deadline'>{item.deadlineAt ? `Due ${date(item.deadlineAt)}` : 'Deadline policy inactive'}</span></summary>
        <div className='approval-stage-body'><p className='notification-policy-meta'>Policy version {item.policyVersion} · {item.entryKnown ? `Entered ${date(item.enteredAt)}` : `Observed ${date(item.enteredAt)}; original stage entry is unknown`} · resource version {item.resourceVersion}</p>
            <p className='approval-detail-text'>{item.detail}</p>
            {item.kind === 'WORK' && item.canReview && <ReviewEvidence taskId={item.resourceId} />}
            {item.delegationId && <p className='notification-mandatory-note'><span>Delegation expires {date(item.delegationExpiresAt!)}. The delegate is revalidated on every action.</span>{(item.canRevokeDelegation ?? item.canDelegate) && <button type='button' className='button button-secondary' disabled={busy || blocked} onClick={() => void mutation(() => notificationPolicyApi.revoke(item.delegationId!, controller.current.signal))}>Revoke delegation</button>}</p>}
            {error && <p className='login-error' role='alert' tabIndex={-1} ref={errorRef}>{error}</p>}
            {item.canReview && <form onSubmit={e => { e.preventDefault(); if (remarks.trim().length >= 2) void mutation(() => notificationPolicyApi.decide(item, true, remarks.trim(), controller.current.signal)); }}>
                <label>{corrective ? 'Corrective plan' : 'Review remarks'}<textarea required minLength={2} maxLength={500} value={remarks} disabled={busy || blocked} onChange={e => setRemarks(e.target.value)} /></label>
                <div className='notification-policy-actions'><button type='submit' className='button button-primary' disabled={busy || blocked || remarks.trim().length < 2}>{busy ? 'Confirming…' : corrective ? 'Assign corrective plan' : 'Approve current stage'}</button>
                    {!corrective && <button type='button' className='button button-secondary' disabled={busy || blocked || remarks.trim().length < 2} onClick={() => void mutation(() => notificationPolicyApi.decide(item, false, remarks.trim(), controller.current.signal))}>{item.kind === 'WORK' ? 'Request changes' : 'Reject current stage'}</button>}</div>
            </form>}
            {item.canDelegate && !item.delegationId && <details className='approval-delegation'><summary>Delegate this review stage</summary><p>Choose an active reviewer with the same role, department and review permission. Delegation expires within 30 days.</p>
                <button type='button' className='button button-secondary' disabled={busy || blocked} onClick={() => void loadCandidates()}>Load eligible reviewers</button>
                {candidates && candidates.length === 0 && <p role='status'>No eligible alternate reviewer is available in this department.</p>}
                {candidates && candidates.length > 0 && <form onSubmit={delegate}><div className='notification-policy-fields'><label>Delegate reviewer<select required value={candidate} disabled={busy || blocked} onChange={e => setCandidate(e.target.value)}><option value=''>Choose a reviewer</option>{candidates.map(c => <option key={c.userId} value={c.userId}>{c.name}</option>)}</select></label>
                    <label>Delegation expiry<input type='datetime-local' required value={expires} disabled={busy || blocked} onChange={e => setExpires(e.target.value)} /><small>Time is interpreted in this browser’s time zone.</small></label></div><label>Delegation reason<textarea required minLength={5} maxLength={500} value={reason} disabled={busy || blocked} onChange={e => setReason(e.target.value)} /></label><button type='submit' className='button button-primary' disabled={busy || blocked || !candidate || !expires || reason.trim().length < 5}>Confirm reviewer delegation</button></form>}
            </details>}
            {item.stage === 'CEO' && <p>Final CEO approval cannot be delegated.</p>}
        </div>
    </details>;
}

function ReviewEvidence({ taskId }: { taskId: string }) {
    const [planning, setPlanning] = useState<WorkPlanning | null>(null);
    const [error, setError] = useState('');
    const controller = useRef(new AbortController());
    const alive = useRef(true);
    useEffect(() => { alive.current = true; controller.current = new AbortController(); const owned = controller.current; return () => { alive.current = false; owned.abort(); }; }, []);
    const load = async () => {
        setError('');
        try { const value = await workboardApi.planning(taskId, controller.current.signal); if (alive.current) setPlanning(value); }
        catch (reason) { if (alive.current) { setPlanning(null); setError(reason instanceof Error ? reason.message : 'Evidence could not be loaded.'); } }
    };
    const download = async (id: string, filename: string) => {
        try {
            const blob = await workboardApi.downloadEvidence(taskId, id, controller.current.signal); if (!alive.current) return;
            const url = URL.createObjectURL(blob); const anchor = document.createElement('a'); anchor.href = url; anchor.download = filename; anchor.click(); window.setTimeout(() => URL.revokeObjectURL(url), 0);
        } catch (reason) { if (alive.current) setError(reason instanceof Error ? reason.message : 'File access could not be verified.'); }
    };
    return <div className='approval-review-evidence'><button type='button' className='button button-secondary' onClick={() => void load()}>Load retained review evidence</button>
        {error && <p role='alert'>{error}</p>}
        {planning?.submissions.map(s => <article key={s.version}><strong>Submission {s.version} · {s.authorName ?? 'Unknown legacy author'}</strong><p>{s.employeeUpdate}</p>{s.checklist.map(c => <p key={c.id}>{c.completed ? 'Completed' : 'Incomplete'}: {c.title}{c.required ? ' (required)' : ''}</p>)}{s.evidence.map(e => <button key={e.id} type='button' className='button button-secondary' onClick={() => void download(e.id, e.filename)}>Download {e.filename}</button>)}</article>)}
        {planning && planning.submissions.length === 0 && <p>No retained submission evidence is available.</p>}
    </div>;
}

function ApprovalPolicyEditor() {
    const [policies, setPolicies] = useState<ApprovalPolicy[] | null>(null);
    const [error, setError] = useState('');
    const alive = useRef(true);
    const controller = useRef(new AbortController());
    useEffect(() => { alive.current = true; controller.current = new AbortController(); const owned = controller.current; return () => { alive.current = false; owned.abort(); }; }, []);
    const getSignal = () => controller.current.signal;
    const load = async () => {
        setError('');
        try { const result = await notificationPolicyApi.policies(controller.current.signal); if (alive.current) setPolicies(result); }
        catch (reason) { if (alive.current) setError(reason instanceof Error ? reason.message : 'Policies unavailable.'); }
    };
    return <details className='approval-policy-editor'><summary>Configure stage deadline policies</summary><p>Policies start disabled. Changes create a new version for newly entered stages. Existing stages retain their captured policy. Elapsed time includes weekends and holidays.</p>
        <button type='button' className='button button-secondary' onClick={() => void load()}>Reload deadline policies</button>{error && <p role='alert'>{error}</p>}
        {policies?.map(policy => <PolicyForm key={`${policy.kind}:${policy.stage}:${policy.version}`} initial={policy} onSaved={setPolicies} getSignal={getSignal} />)}
    </details>;
}
function PolicyForm({ initial, onSaved, getSignal }: { initial: ApprovalPolicy; onSaved: (policies: ApprovalPolicy[]) => void; getSignal: () => AbortSignal }) {
    const [value, setValue] = useState(initial);
    const [busy, setBusy] = useState(false);
    const [blocked, setBlocked] = useState(false);
    const [error, setError] = useState('');
    const save = async (event: FormEvent) => {
        event.preventDefault(); const signal = getSignal(); if (busy || blocked || signal.aborted) return; setBusy(true); setError('');
        try { const result = await notificationPolicyApi.savePolicy(value, signal); if (!signal.aborted) onSaved(result); }
        catch (reason) { if (!signal.aborted) { setBlocked(true); setError(`${reason instanceof Error ? reason.message : 'Policy save unconfirmed.'} Reload deadline policies before another save.`); } }
        finally { if (!signal.aborted) setBusy(false); }
    };
    const label = `${readable(value.kind)} ${readable(value.stage)}`;
    return <form className='approval-policy-form' onSubmit={save}><h3>{label} · version {value.version}</h3><fieldset disabled={busy || blocked}><legend>New stage policy</legend>
        <label className='notification-check'><input type='checkbox' checked={value.enabled} onChange={e => setValue(v => ({ ...v, enabled: e.target.checked }))} />Enable reminders for {label}</label>
        <div className='notification-policy-fields'><label>Deadline minutes for {label}<input type='number' required min={5} max={43200} value={value.deadlineMinutes} onChange={e => setValue(v => ({ ...v, deadlineMinutes: Number(e.target.value) }))} /></label><label>Reminder minutes for {label}<input type='number' required min={5} max={10080} value={value.reminderMinutes} onChange={e => setValue(v => ({ ...v, reminderMinutes: Number(e.target.value) }))} /></label>
        <label>Escalation role for {label}<select value={value.escalationRole} onChange={e => setValue(v => ({ ...v, escalationRole: e.target.value }))}><option value='HR_ADMIN'>Department HR Admin</option><option value='MANAGER'>Department Manager</option><option value='CEO'>Company CEO</option></select></label></div></fieldset>
        {error && <p role='alert'>{error}</p>}<button type='submit' className='button button-primary' disabled={busy || blocked}>Save new {label} policy version</button></form>;
}
