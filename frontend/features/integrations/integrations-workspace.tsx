'use client';

import { useCallback, useEffect, useRef, useState, type FormEvent } from 'react';
import { ApiError, isBackendConfigured } from '../../lib/api-client';
import { integrationsApi } from './api/integrations-api';
import { useAdminSession } from './use-admin-session';
import type { Connection, Delivery, DeliveryAttempt, IntegrationProvider, TestScenario } from './types';
import styles from './admin-tools.module.css';

const date = (value: string | null) => value ? new Date(value).toLocaleString('en-IN') : 'Not recorded';
const human = (value: string) => value.replaceAll('_', ' ').toLowerCase();
const providerName = (value: IntegrationProvider) => value === 'SIMULATOR_CALENDAR' ? 'Calendar simulator' : 'Messaging simulator';
const localDate = (milliseconds: number) => { const value = new Date(milliseconds); return new Date(value.getTime() - value.getTimezoneOffset() * 60000).toISOString().slice(0, 16); };

export function IntegrationsWorkspace() {
    const [connections, setConnections] = useState<Connection[]>([]), [loaded, setLoaded] = useState(false);
    const [selectedId, setSelectedId] = useState('');
    const selectedIdRef = useRef('');
    useEffect(() => { selectedIdRef.current = selectedId; }, [selectedId]);
    const [deliveries, setDeliveries] = useState<Delivery[]>([]), [deliveryPage, setDeliveryPage] = useState(0), [deliveryPages, setDeliveryPages] = useState(0);
    const [deliveriesLoaded, setDeliveriesLoaded] = useState(false), [attempts, setAttempts] = useState<Record<string, DeliveryAttempt[]>>({});
    const [provider, setProvider] = useState<IntegrationProvider>('SIMULATOR_CALENDAR'), [label, setLabel] = useState('');
    const [expires, setExpires] = useState(''), [reconnectExpires, setReconnectExpires] = useState('');
    const [scenario, setScenario] = useState<TestScenario>('SUCCESS'), [revokeConfirmed, setRevokeConfirmed] = useState(false);
    const [blocked, setBlocked] = useState(false), [error, setError] = useState(''), [message, setMessage] = useState('');
    const credentialRef = useRef<HTMLInputElement>(null), reconnectCredentialRef = useRef<HTMLInputElement>(null), errorRef = useRef<HTMLDivElement>(null);
    const { begin, busy, sessionEnded, clock } = useAdminSession(() => {
        if (credentialRef.current) credentialRef.current.value = '';
        if (reconnectCredentialRef.current) reconnectCredentialRef.current.value = '';
        setConnections([]); setDeliveries([]); setAttempts({}); setSelectedId(''); setLabel(''); setExpires(''); setReconnectExpires(''); setError(''); setMessage('');
    });
    const selected = connections.find(item => item.id === selectedId);
    const eligible = Boolean(clock > 0 && selected?.status === 'ACTIVE' && Date.parse(selected.credentialExpiresAt) > clock);
    const expiryMin = clock > 0 ? localDate(clock + 60000) : undefined, expiryMax = clock > 0 ? localDate(clock + 90 * 86400000) : undefined;
    const load = useCallback(async () => {
        if (!isBackendConfigured) return;
        const operation = begin(); if (!operation) return;
        setError('');
        try {
            const result = await integrationsApi.connections(operation.signal);
            if (!operation.current()) return;
            const id = result.some(item => item.id === selectedIdRef.current) ? selectedIdRef.current : result[0]?.id ?? '';
            // Refresh detail and its observed versions together before unlocking any mutation.
            const backlog = id ? await integrationsApi.deliveries(id, 0, operation.signal) : null;
            if (!operation.current()) return;
            setConnections(result); setSelectedId(id); setLoaded(true);
            setDeliveries(backlog?.content ?? []); setDeliveryPage(0); setDeliveryPages(backlog?.totalPages ?? 0); setDeliveriesLoaded(Boolean(id)); setAttempts({});
            setBlocked(false); setRevokeConfirmed(false); setMessage('Current connections and delivery versions loaded.');
        } catch { if (operation.current()) { setBlocked(true); setError('Connections could not be verified. Reload connections before making changes.'); } }
        finally { operation.finish(); }
    }, [begin]);
    useEffect(() => { const timer = window.setTimeout(() => void load(), 0); return () => window.clearTimeout(timer); }, [load]);
    const loadDeliveries = async (id: string, requestedPage: number) => {
        const operation = begin(); if (!operation) return;
        setError('');
        try {
            const result = await integrationsApi.deliveries(id, requestedPage, operation.signal);
            if (!operation.current()) return;
            setSelectedId(id); setDeliveries(result.content); setDeliveryPage(result.number ?? requestedPage); setDeliveryPages(result.totalPages ?? 0); setDeliveriesLoaded(true); setAttempts({}); setRevokeConfirmed(false); setReconnectExpires('');
            if (reconnectCredentialRef.current) reconnectCredentialRef.current.value = '';
        } catch { if (operation.current()) { setBlocked(true); setError('Delivery status could not be verified. Reload connections before making changes.'); } }
        finally { operation.finish(); }
    };
    const mutate = async (action: (signal: AbortSignal) => Promise<unknown>, success: string) => {
        if (blocked) return;
        const operation = begin(); if (!operation) return;
        setError(''); setMessage('');
        try {
            await action(operation.signal);
            if (operation.current()) { setBlocked(true); setMessage(`${success} Reload connections to verify the current status before another change.`); }
        } catch (reason) {
            if (!operation.current()) return;
            setBlocked(true);
            setError(reason instanceof ApiError && reason.status === 409
                ? 'The observed version changed in another session. Reload connections before trying again. Your nonsecret form choices are retained.'
                : 'The change was not confirmed. Reload connections to check the result before trying again. Your nonsecret form choices are retained.');
            window.requestAnimationFrame(() => errorRef.current?.focus());
        } finally { operation.finish(); }
    };
    const create = (event: FormEvent) => {
        event.preventDefault(); if (busy || blocked || sessionEnded) return;
        const credential = credentialRef.current?.value ?? '';
        if (credentialRef.current) credentialRef.current.value = '';
        if (!credential || !label.trim() || !expires || Date.parse(expires) <= clock) return;
        void mutate(signal => integrationsApi.create({ requestId: crypto.randomUUID(), provider, label: label.trim(), credential, credentialExpiresAt: new Date(expires).toISOString() }, signal), 'Connection creation accepted.');
    };
    const reconnect = (event: FormEvent) => {
        event.preventDefault(); if (!selected || busy || blocked || sessionEnded) return;
        const credential = reconnectCredentialRef.current?.value ?? '';
        if (reconnectCredentialRef.current) reconnectCredentialRef.current.value = '';
        if (!credential || !reconnectExpires || Date.parse(reconnectExpires) <= clock) return;
        void mutate(signal => integrationsApi.reconnect(selected.id, selected.version, credential, new Date(reconnectExpires).toISOString(), signal), 'Replacement credential accepted.');
    };
    const loadAttempts = async (id: string) => {
        const operation = begin(); if (!operation) return;
        try { const result = await integrationsApi.attempts(id, operation.signal); if (operation.current()) setAttempts(previous => ({ ...previous, [id]: result })); }
        catch { if (operation.current()) setError('Delivery attempts could not be loaded. Retry the read when the service is available.'); }
        finally { operation.finish(); }
    };
    return <section className={styles.workspace} aria-labelledby='integrations-title'>
        <header className={styles.heading}><div><h1 id='integrations-title'>Integrations</h1><p>Manage connections and check delivery recovery.</p></div><button className='button button-secondary' type='button' disabled={busy || sessionEnded || !isBackendConfigured} onClick={() => void load()}>Reload connections</button></header>
        <p className={styles.notice}>Calendar and messaging simulators exercise delivery behaviour. Live Microsoft, Google, Teams and Slack connections are planned for a later release. Provider failures leave appointment operations available.</p>
        {!isBackendConfigured && <p role='status'>Sign in to the connected service to manage integrations.</p>}
        {sessionEnded && <p role='status'>The account changed. Open Integrations from the current workspace.</p>}
        {error && <div className='login-error' role='alert' tabIndex={-1} ref={errorRef}>{error}</div>}
        {message && <p className={styles.notice} role='status'>{message}</p>}
        {isBackendConfigured && !sessionEnded && <>
            <details className={styles.panel}><summary>Add a simulator connection</summary><form onSubmit={create} autoComplete='off' aria-busy={busy}><fieldset disabled={busy || blocked}><legend>Connection details</legend><div className={styles.fields}>
                <label>Provider<select value={provider} onChange={e => setProvider(e.target.value as IntegrationProvider)}><option value='SIMULATOR_CALENDAR'>Calendar simulator</option><option value='SIMULATOR_MESSAGING'>Messaging simulator</option></select></label>
                <label>Connection label<input required maxLength={80} value={label} onChange={e => setLabel(e.target.value)} /></label>
                <label>Connection credential<input ref={credentialRef} type='password' required minLength={16} maxLength={4096} autoComplete='off' spellCheck={false} /></label>
                <label>Credential expiry<input type='datetime-local' required min={expiryMin} max={expiryMax} value={expires} onChange={e => setExpires(e.target.value)} /></label>
            </div><p className={styles.muted}>Use a credential of at least 16 characters and an expiry within 90 days. Credentials are cleared when submitted and cannot be viewed again. Minimum scopes are fixed by the service.</p><div className={styles.actions}><button className='button button-primary' type='submit' disabled={busy || blocked}>Create connection</button></div></fieldset></form></details>
            <div className={styles.columns}><section className={styles.panel} aria-labelledby='connection-list-title'><h2 id='connection-list-title'>Connections</h2>
                {!loaded && <p role='status'>{busy ? 'Loading connections…' : 'Reload connections to verify the source.'}</p>}
                {loaded && connections.length === 0 && <p>No connections available. Add a simulator to begin.</p>}
                <ul className={styles.list}>{connections.map(item => <li key={item.id}><button type='button' className={styles.connection} aria-pressed={selectedId === item.id} disabled={busy} onClick={() => void loadDeliveries(item.id, 0)}><strong>{item.label}</strong><small>{providerName(item.provider)}</small><span className={styles.status}>{human(item.status)}</span></button></li>)}</ul>
            </section><section className={styles.panel} aria-labelledby='connection-detail-title'><h2 id='connection-detail-title'>{selected ? selected.label : 'Connection details'}</h2>
                {!selected && <p>Select a connection to inspect its status and delivery attempts.</p>}
                {selected && <><dl className={styles.facts}><div><dt>Status</dt><dd>{human(selected.status)}{selected.status === 'ACTIVE' && !eligible ? ' · credential expired' : ''}</dd></div><div><dt>Provider</dt><dd>{providerName(selected.provider)}</dd></div><div><dt>Credential expiry</dt><dd>{date(selected.credentialExpiresAt)}</dd></div><div><dt>Observed version</dt><dd>{selected.version} · credential version {selected.credentialVersion}</dd></div><div><dt>Owner reference</dt><dd>{selected.ownerId}</dd></div><div><dt>Minimum scopes</dt><dd>{selected.minimumScopes.join(', ')}</dd></div><div><dt>Last checked</dt><dd>{date(selected.lastCheckedAt)}</dd></div><div><dt>Last result</dt><dd>{selected.lastResultCode ?? 'Not checked'}</dd></div></dl>
                    <details><summary>Test delivery behaviour</summary><label className={styles.field}>Simulator scenario<select value={scenario} disabled={busy || blocked || !eligible} onChange={e => setScenario(e.target.value as TestScenario)}><option value='SUCCESS'>Successful delivery</option><option value='OUTAGE'>Provider outage</option><option value='RATE_LIMITED'>Rate limit</option><option value='REAUTH_REQUIRED'>Reauthentication required</option><option value='PERMANENT_FAILURE'>Permanent failure</option></select></label><p>Creates an explicit simulator test delivery for this connection. Reload to inspect the outcome and attempts.</p><div className={styles.actions}><button type='button' className='button button-primary' disabled={busy || blocked || !eligible} onClick={() => void mutate(signal => integrationsApi.test(selected.id, selected.version, scenario, signal), 'Simulator test accepted.')}>Run simulator test</button></div></details>
                    <details><summary>Replace credential</summary><form onSubmit={reconnect} autoComplete='off'><fieldset disabled={busy || blocked}><div className={styles.fields}><label>Replacement credential<input ref={reconnectCredentialRef} type='password' required minLength={16} maxLength={4096} autoComplete='off' spellCheck={false} /></label><label>Replacement expiry<input type='datetime-local' required min={expiryMin} max={expiryMax} value={reconnectExpires} onChange={e => setReconnectExpires(e.target.value)} /></label></div><p>Replacing a credential advances its version. Pending deliveries use the new credential only after the service verifies eligibility.</p><div className={styles.actions}><button className='button button-primary' type='submit' disabled={busy || blocked}>Reconnect connection</button></div></fieldset></form></details>
                    <details><summary>Revoke connection</summary><p>Revocation stops new deliveries and cancels pending work for this connection.</p><label className={styles.check}><input type='checkbox' checked={revokeConfirmed} disabled={busy || blocked || selected.status === 'REVOKED'} onChange={e => setRevokeConfirmed(e.target.checked)} />I confirm revoking this connection.</label><div className={styles.actions}><button type='button' className='button button-secondary' disabled={busy || blocked || !revokeConfirmed || selected.status === 'REVOKED'} onClick={() => void mutate(signal => integrationsApi.revoke(selected.id, selected.version, signal), 'Revocation accepted.')}>Confirm connection revocation</button></div></details>
                    <h3>Delivery backlog</h3><p className={styles.muted}>Statuses and attempt history contain references and result codes; private delivery content stays out of this view.</p>
                    {deliveriesLoaded && deliveries.length === 0 && <p>No deliveries on this page.</p>}
                    {deliveries.map(item => <article className={styles.row} key={item.id} aria-label={`Delivery ${item.id}`}><div className={styles.rowHeader}><strong>Revision {item.businessRevision}</strong><span className={styles.status}>{human(item.status)}</span></div><dl className={styles.facts}><div><dt>Delivery reference</dt><dd>{item.id}</dd></div><div><dt>Attempts</dt><dd>{item.totalAttempts} of 20 total · {item.manualRetries} of 3 manual retries</dd></div><div><dt>Next attempt</dt><dd>{date(item.nextAttemptAt)}</dd></div><div><dt>Last result</dt><dd>{item.lastResultCode ?? 'Pending'}</dd></div></dl><div className={styles.actions}><button type='button' className='button button-secondary' disabled={busy} onClick={() => void loadAttempts(item.id)}>Load delivery attempts</button><button type='button' className='button button-secondary' disabled={busy || blocked || !eligible || item.manualRetries >= 3 || item.totalAttempts >= 20 || !['FAILED', 'NEEDS_RECONNECT'].includes(item.status)} onClick={() => void mutate(signal => integrationsApi.retry(item.id, item.version, signal), 'Delivery retry accepted.')}>Retry failed delivery</button></div>
                        {attempts[item.id] && <ol>{attempts[item.id].map(attempt => <li key={attempt.id}>Attempt {attempt.attemptNumber}: {attempt.outcome} · {date(attempt.startedAt)}</li>)}{attempts[item.id].length === 0 && <li>No attempts recorded.</li>}</ol>}
                    </article>)}
                    {deliveryPages > 1 && <div className={styles.actions}><button type='button' className='button button-secondary' aria-label='Previous deliveries page' disabled={busy || deliveryPage === 0} onClick={() => void loadDeliveries(selected.id, deliveryPage - 1)}>Previous</button><span>Page {deliveryPage + 1} of {deliveryPages}</span><button type='button' className='button button-secondary' aria-label='Next deliveries page' disabled={busy || deliveryPage + 1 >= deliveryPages} onClick={() => void loadDeliveries(selected.id, deliveryPage + 1)}>Next</button></div>}
                </>}
            </section></div>
        </>}
    </section>;
}
