'use client';

import { useCallback, useEffect, useRef, useState, type FormEvent } from 'react';
import { ApiError, isBackendConfigured } from '../../lib/api-client';
import { integrationsApi } from './api/integrations-api';
import { googleAuthorizationUrl } from './api/google-authorization-url';
import { useAdminSession } from './use-admin-session';
import type { CalendarReconciliation, Connection, Delivery, DeliveryAttempt, GoogleCalendarConfig, GoogleConnectionMetadata, GoogleConsent, IntegrationProvider, SlackConfig, SlackConnectionMetadata, TestScenario } from './types';
import styles from './admin-tools.module.css';

const date = (value: string | null) => value ? new Date(value).toLocaleString('en-IN') : 'Not recorded';
const human = (value: string) => value.replaceAll('_', ' ').toLowerCase();
const providerName = (value: IntegrationProvider) => value === 'GOOGLE_CALENDAR' ? 'Google Calendar' : value === 'SLACK_MESSAGING' ? 'Slack' : value === 'SIMULATOR_CALENDAR' ? 'Calendar simulator' : 'Messaging simulator';
const localDate = (milliseconds: number) => { const value = new Date(milliseconds); return new Date(value.getTime() - value.getTimezoneOffset() * 60000).toISOString().slice(0, 16); };
const slackErrors: Record<string, string> = {
    SLACK_NOT_CONFIGURED: 'Slack is not configured on this service. Ask your service operator to enable arrival notices.',
    SLACK_INVALID_TOKEN: 'Use a nonrotating Slack bot token beginning with xoxb-.',
    SLACK_AUTH_REJECTED: 'Slack could not verify this bot with only the chat:write scope. Check its installation and scope in Slack.',
    SLACK_DEDICATED_BOT_REQUIRED: 'This dedicated bot already belongs to a connection. Use that connection’s renewal controls.',
    SLACK_REVOCATION_PENDING: 'Finish remote revocation before renewing this Slack connection.',
    SLACK_ROTATION_REQUIRED: 'Rotate or revoke the old token in Slack before supplying its replacement.',
    SLACK_DESTINATION_CHANGED: 'Use a replacement token for the same workspace and bot. A different destination needs a new connection.',
    SLACK_REVOCATION_RETRY_LIMIT: 'No remote revocation retry is available. Review its status; at most three manual retries are allowed.',
    SLACK_DUPLICATE_RISK_ACK_REQUIRED: 'Check the Slack channel and acknowledge the duplicate-notice risk before retrying this uncertain delivery.',
    SLACK_DELIVERY_EXPIRED: 'This Slack notice expired 24 hours after its event and cannot be sent again.'
};

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
    const [googleConfig, setGoogleConfig] = useState<GoogleCalendarConfig | null>(null), [googleVerified, setGoogleVerified] = useState(false);
    const [consents, setConsents] = useState<GoogleConsent[]>([]), [authorization, setAuthorization] = useState<{ url: string; expiresAt: string } | null>(null);
    const [googleLabel, setGoogleLabel] = useState(''), [consentConfirmed, setConsentConfirmed] = useState(false), [calendarId, setCalendarId] = useState('');
    const [reconsentConfirmed, setReconsentConfirmed] = useState(false);
    const [googleMetadata, setGoogleMetadata] = useState<GoogleConnectionMetadata | null>(null), [reconciliation, setReconciliation] = useState<CalendarReconciliation | null>(null);
    const [slackConfig, setSlackConfig] = useState<SlackConfig | null>(null), [slackVerified, setSlackVerified] = useState(false), [slackMetadata, setSlackMetadata] = useState<SlackConnectionMetadata | null>(null);
    const [slackLabel, setSlackLabel] = useState(''), [slackChannel, setSlackChannel] = useState(''), [slackExpires, setSlackExpires] = useState('');
    const [slackConfirmed, setSlackConfirmed] = useState(false), [slackRenewalConfirmed, setSlackRenewalConfirmed] = useState(false), [duplicateRisk, setDuplicateRisk] = useState<Record<string, boolean>>({});
    const slackCredentialRef = useRef<HTMLInputElement>(null);
    const credentialRef = useRef<HTMLInputElement>(null), reconnectCredentialRef = useRef<HTMLInputElement>(null), errorRef = useRef<HTMLDivElement>(null);
    const { begin, busy, sessionEnded, clock } = useAdminSession(() => {
        if (credentialRef.current) credentialRef.current.value = '';
        if (reconnectCredentialRef.current) reconnectCredentialRef.current.value = '';
        if (slackCredentialRef.current) slackCredentialRef.current.value = '';
        setConnections([]); setDeliveries([]); setAttempts({}); setSelectedId(''); setLabel(''); setExpires(''); setReconnectExpires(''); setError(''); setMessage('');
        setGoogleConfig(null); setGoogleVerified(false); setConsents([]); setAuthorization(null); setGoogleLabel(''); setConsentConfirmed(false); setReconsentConfirmed(false); setCalendarId(''); setGoogleMetadata(null); setReconciliation(null);
        setSlackConfig(null); setSlackVerified(false); setSlackMetadata(null); setSlackLabel(''); setSlackChannel(''); setSlackExpires(''); setSlackConfirmed(false); setSlackRenewalConfirmed(false); setDuplicateRisk({});
    });
    const selected = connections.find(item => item.id === selectedId);
    const isGoogle = selected?.provider === 'GOOGLE_CALENDAR';
    const isSlack = selected?.provider === 'SLACK_MESSAGING';
    const slackChannelValid = /^[CG][A-Z0-9]{8,31}$/.test(slackChannel.trim());
    const canSlack = Boolean(slackVerified && slackConfig?.configured && !busy && !blocked && !sessionEnded);
    const canRenewSlack = Boolean(canSlack && (selected?.status !== 'REVOKED' || slackMetadata?.revocationStatus === 'COMPLETE'));
    const calendarIdValid = /^[A-Za-z0-9._%+@-]{3,512}$/.test(calendarId.trim()) && calendarId.includes('@');
    const canConsent = Boolean(googleVerified && googleConfig?.configured && !busy && !blocked && !sessionEnded);
    const eligible = Boolean(clock > 0 && selected?.status === 'ACTIVE' && Date.parse(selected.credentialExpiresAt) > clock);
    const expiryMin = clock > 0 ? localDate(clock + 60000) : undefined, expiryMax = clock > 0 ? localDate(clock + 90 * 86400000) : undefined;
    useEffect(() => {
        if (!authorization || clock < Date.parse(authorization.expiresAt)) return;
        const timer = window.setTimeout(() => setAuthorization(null), 0);
        return () => window.clearTimeout(timer);
    }, [authorization, clock]);
    const load = useCallback(async () => {
        if (!isBackendConfigured) return;
        const operation = begin(); if (!operation) return;
        setError(''); setAuthorization(null); setGoogleVerified(false); setSlackVerified(false);
        try {
            // Optional provider configuration does not prevent simulator administration.
            const [connectionRead, configRead, slackConfigRead] = await Promise.allSettled([integrationsApi.connections(operation.signal), integrationsApi.googleConfig(operation.signal), integrationsApi.slackConfig(operation.signal)]);
            if (connectionRead.status === 'rejected') throw connectionRead.reason;
            const result = connectionRead.value;
            if (!operation.current()) return;
            const id = result.some(item => item.id === selectedIdRef.current) ? selectedIdRef.current : result[0]?.id ?? '';
            const google = result.find(item => item.id === id)?.provider === 'GOOGLE_CALENDAR';
            const slack = result.find(item => item.id === id)?.provider === 'SLACK_MESSAGING';
            const config = configRead.status === 'fulfilled' && typeof configRead.value?.configured === 'boolean' ? configRead.value : null;
            const slackConfiguration = slackConfigRead.status === 'fulfilled' && typeof slackConfigRead.value?.configured === 'boolean' && slackConfigRead.value.scope === 'chat:write' && slackConfigRead.value.usesDedicatedBot === true ? slackConfigRead.value : null;
            // Refresh detail and its observed versions together before unlocking any mutation.
            const [backlog, consentRead, metadataRead, progressRead, slackMetadataRead] = await Promise.all([
                id ? integrationsApi.deliveries(id, 0, operation.signal) : null,
                config?.configured ? integrationsApi.googleConsents(operation.signal) : [],
                google ? integrationsApi.googleConnection(id, operation.signal) : null,
                google ? integrationsApi.reconciliation(id, operation.signal) : null,
                slack ? integrationsApi.slackConnection(id, operation.signal) : null
            ]);
            if (!operation.current()) return;
            setConnections(result); setSelectedId(id); setLoaded(true);
            setGoogleConfig(config); setGoogleVerified(Boolean(config)); setConsents(consentRead); setGoogleMetadata(metadataRead); setReconciliation(progressRead); setCalendarId(''); setConsentConfirmed(false); setReconsentConfirmed(false);
            setSlackConfig(slackConfiguration); setSlackVerified(Boolean(slackConfiguration)); setSlackMetadata(slackMetadataRead); setSlackRenewalConfirmed(false); setDuplicateRisk({});
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
            const google = connections.find(item => item.id === id)?.provider === 'GOOGLE_CALENDAR';
            const slack = connections.find(item => item.id === id)?.provider === 'SLACK_MESSAGING';
            const [result, metadata, progress, slackDetail] = await Promise.all([integrationsApi.deliveries(id, requestedPage, operation.signal), google ? integrationsApi.googleConnection(id, operation.signal) : null, google ? integrationsApi.reconciliation(id, operation.signal) : null, slack ? integrationsApi.slackConnection(id, operation.signal) : null]);
            if (!operation.current()) return;
            setSelectedId(id); setDeliveries(result.content); setDeliveryPage(result.number ?? requestedPage); setDeliveryPages(result.totalPages ?? 0); setDeliveriesLoaded(true); setAttempts({}); setRevokeConfirmed(false); setReconnectExpires('');
            setGoogleMetadata(metadata); setReconciliation(progress); setCalendarId(''); setConsentConfirmed(false); setReconsentConfirmed(false);
            setSlackMetadata(slackDetail); setSlackRenewalConfirmed(false); setDuplicateRisk({});
            if (reconnectCredentialRef.current) reconnectCredentialRef.current.value = '';
        } catch { if (operation.current()) { setBlocked(true); setError('Delivery status could not be verified. Reload connections before making changes.'); } }
        finally { operation.finish(); }
    };
    const mutate = async (action: (signal: AbortSignal) => Promise<unknown>, success: string) => {
        if (blocked) return;
        const operation = begin(); if (!operation) return;
        setError(''); setMessage(''); setAuthorization(null);
        try {
            await action(operation.signal);
            if (operation.current()) { setBlocked(true); setMessage(`${success} Reload connections to verify the current status before another change.`); }
        } catch (reason) {
            if (!operation.current()) return;
            setBlocked(true);
            const slackRecovery = reason instanceof ApiError && reason.problem.errorCode ? slackErrors[reason.problem.errorCode] : undefined;
            setError(reason instanceof ApiError && reason.problem.errorCode === 'MFA_STEP_UP_REQUIRED'
                ? 'Verify your identity in My profile → Account security, then reload connections before trying again.'
                : slackRecovery
                ? `${slackRecovery} Reload connections to verify the current status before another change.`
                : reason instanceof ApiError && reason.problem.errorCode === 'CALENDAR_RECONCILE_LIMIT'
                ? 'Reconciliation is limited to one active run, five minutes between runs, three runs per 24 hours and 500 resources. Reload connections to check progress before trying again.'
                : reason instanceof ApiError && reason.problem.errorCode === 'CONSENT_SESSION_MISMATCH'
                ? 'Finish consent in the original signed-in session. Reload connections to review the request before starting a new consent.'
                : reason instanceof ApiError && reason.status === 409
                ? 'The observed version changed in another session. Reload connections before trying again. Your nonsecret form choices are retained.'
                : 'The change was not confirmed. Reload connections to check the result before trying again. Your nonsecret form choices are retained.');
            window.requestAnimationFrame(() => errorRef.current?.focus());
        } finally { operation.finish(); }
    };
    const create = (event: FormEvent) => {
        event.preventDefault(); if (!['SIMULATOR_CALENDAR', 'SIMULATOR_MESSAGING'].includes(provider) || busy || blocked || sessionEnded) return;
        const credential = credentialRef.current?.value ?? '';
        if (credentialRef.current) credentialRef.current.value = '';
        if (!credential || !label.trim() || !expires || Date.parse(expires) <= clock) return;
        void mutate(signal => integrationsApi.create({ requestId: crypto.randomUUID(), provider, label: label.trim(), credential, credentialExpiresAt: new Date(expires).toISOString() }, signal), 'Connection creation accepted.');
    };
    const createSlack = (event: FormEvent) => {
        event.preventDefault(); if (!canSlack || !slackConfirmed || !slackChannelValid) return;
        const credential = slackCredentialRef.current?.value ?? '';
        if (slackCredentialRef.current) slackCredentialRef.current.value = '';
        if (!credential || !slackLabel.trim() || !slackExpires || Date.parse(slackExpires) <= clock) return;
        setSlackConfirmed(false);
        void mutate(signal => integrationsApi.createSlack({ requestId: crypto.randomUUID(), label: slackLabel.trim(), channelId: slackChannel.trim(), credential, credentialExpiresAt: new Date(slackExpires).toISOString() }, signal), 'Slack connection creation accepted.');
    };
    const reconnect = (event: FormEvent) => {
        event.preventDefault(); if (!selected || isGoogle || busy || blocked || sessionEnded || (isSlack && (!canRenewSlack || !slackRenewalConfirmed))) return;
        const credential = reconnectCredentialRef.current?.value ?? '';
        if (reconnectCredentialRef.current) reconnectCredentialRef.current.value = '';
        if (!credential || !reconnectExpires || Date.parse(reconnectExpires) <= clock) return;
        if (isSlack) {
            setSlackRenewalConfirmed(false);
            void mutate(signal => integrationsApi.renewSlack(selected.id, selected.version, credential, new Date(reconnectExpires).toISOString(), signal), 'Slack credential renewal accepted.');
        } else void mutate(signal => integrationsApi.reconnect(selected.id, selected.version, credential, new Date(reconnectExpires).toISOString(), signal), 'Replacement credential accepted.');
    };
    const loadAttempts = async (id: string) => {
        const operation = begin(); if (!operation) return;
        try { const result = await integrationsApi.attempts(id, operation.signal); if (operation.current()) setAttempts(previous => ({ ...previous, [id]: result })); }
        catch { if (operation.current()) setError('Delivery attempts could not be loaded. Retry the read when the service is available.'); }
        finally { operation.finish(); }
    };
    const startConsent = async (connection?: Connection) => {
        if (!canConsent || !(connection ? reconsentConfirmed : consentConfirmed) || (!connection && !googleLabel.trim())) return;
        const operation = begin(); if (!operation) return;
        setAuthorization(null); setError(''); setMessage('');
        try {
            const result = await integrationsApi.startGoogleConsent({ requestId: crypto.randomUUID(), label: connection?.label ?? googleLabel.trim(), ...(connection ? { connectionId: connection.id, expectedVersion: connection.version } : {}) }, operation.signal);
            if (!operation.current()) return;
            const url = googleAuthorizationUrl(result.authorizationUrl);
            setConsents(previous => [...previous.filter(item => item.id !== result.id), { id: result.id, connectionId: result.connectionId, status: result.status, expiresAt: result.expiresAt, lastResultCode: null }]);
            setAuthorization({ url, expiresAt: result.expiresAt }); setBlocked(true); setConsentConfirmed(false); setReconsentConfirmed(false);
            setMessage('Consent request created. Continue to Google in a new tab, then return here and reload connections.');
        } catch (reason) {
            if (!operation.current()) return;
            setBlocked(true);
            setError(reason instanceof ApiError && reason.problem.errorCode === 'MFA_STEP_UP_REQUIRED'
                ? 'Verify your identity in My profile → Account security, then reload connections before starting consent.'
                : 'Consent was not confirmed. Reload connections to check request metadata before starting again.');
            window.requestAnimationFrame(() => errorRef.current?.focus());
        } finally { operation.finish(); }
    };
    const recoverCalendar = (event: FormEvent) => {
        event.preventDefault();
        if (!selected || !isGoogle || !calendarIdValid) return;
        void mutate(signal => integrationsApi.recoverGoogleCalendar(selected.id, selected.version, calendarId.trim(), signal), 'Calendar recovery accepted.');
    };
    const downloadCalendar = async () => {
        const operation = begin(); if (!operation) return;
        setError(''); setMessage('');
        let objectUrl: string | null = null;
        try {
            const blob = await integrationsApi.calendarFile(operation.signal);
            if (!operation.current()) return;
            objectUrl = URL.createObjectURL(blob);
            const anchor = document.createElement('a'); anchor.href = objectUrl; anchor.download = 'brainserve-calendar.ics';
            document.body.appendChild(anchor);
            try { anchor.click(); } finally { anchor.remove(); }
            setMessage('Calendar file downloaded. Importing it into a calendar does not keep it synchronized.');
        } catch (reason) {
            if (operation.current()) setError(reason instanceof ApiError && reason.problem.errorCode === 'MFA_STEP_UP_REQUIRED'
                ? 'Verify your identity in My profile → Account security before downloading the calendar file.'
                : reason instanceof ApiError && reason.problem.errorCode === 'CALENDAR_EXPORT_LIMIT'
                    ? 'The 30-day calendar exceeds the 500-event export limit. No partial file was downloaded.'
                    : 'The calendar file could not be downloaded. Verify your current access and retry the download.');
        } finally { if (objectUrl) URL.revokeObjectURL(objectUrl); operation.finish(); }
    };
    return <section className={styles.workspace} aria-labelledby='integrations-title' aria-busy={busy}>
        <header className={styles.heading}><div><h1 id='integrations-title'>Integrations</h1><p>Manage connections and check delivery recovery.</p></div><button className='button button-secondary' type='button' disabled={busy || sessionEnded || !isBackendConfigured} onClick={() => void load()}>Reload connections</button></header>
        <p className={styles.notice}>Google Calendar receives approved appointment times in a dedicated app-created calendar. Visitor details and invitations are excluded. Provider failures leave appointment operations available.</p>
        {!isBackendConfigured && <p role='status'>Sign in to the connected service to manage integrations.</p>}
        {sessionEnded && <p role='status'>The account changed. Open Integrations from the current workspace.</p>}
        {busy && <p role='status'>Waiting for the service response…</p>}
        {error && <div className='login-error' role='alert' tabIndex={-1} ref={errorRef}>{error}</div>}
        {message && <p className={styles.notice} role='status'>{message}</p>}
        {isBackendConfigured && !sessionEnded && <>
            <section className={styles.panel} aria-labelledby='google-calendar-title'><h2 id='google-calendar-title'>Google Calendar</h2>
                {!googleVerified && <p role='status'>{busy ? 'Checking Google Calendar availability…' : 'Google Calendar availability could not be verified. Reload connections to check configuration.'}</p>}
                {googleVerified && !googleConfig?.configured && <p role='status'>Google Calendar is not configured on this service. Ask your service operator to configure consent, or download a calendar file below.</p>}
                {googleVerified && googleConfig?.configured && <><p>Consent uses Google’s app-created calendar scope. Only the consenting administrator owns the dedicated calendar. After granting access in the new tab, return here and reload connections, then choose Finish connection.</p>
                    <form onSubmit={event => { event.preventDefault(); void startConsent(); }}><fieldset disabled={!canConsent}><label className={styles.field}>Google Calendar connection label<input required maxLength={80} value={googleLabel} onChange={event => setGoogleLabel(event.target.value)} /></label><label className={styles.check}><input type='checkbox' checked={consentConfirmed} onChange={event => setConsentConfirmed(event.target.checked)} />I approve granting access to a dedicated BrainServe calendar.</label><div className={styles.actions}><button className='button button-primary' type='submit' disabled={!canConsent || !consentConfirmed || !googleLabel.trim()}>Start Google consent</button></div></fieldset></form>
                </>}
                {authorization && Date.parse(authorization.expiresAt) > clock && <p className={styles.actions}><a className='button button-primary' href={authorization.url} target='_blank' rel='noopener noreferrer' referrerPolicy='no-referrer' onClick={() => setAuthorization(null)}>Continue to Google (opens new tab)</a></p>}
                {consents.length > 0 && <><h3>Consent requests</h3><ul className={styles.list}>{consents.map(item => <li className={styles.row} key={item.id}><strong>{human(item.status)}</strong><p className={styles.muted}>Expires {date(item.expiresAt)}{item.lastResultCode ? ` · ${item.lastResultCode}` : ''}</p>
                    {item.status === 'DENIED' && <p>Permission was not granted. Start a new consent request only when you are ready to grant access.</p>}
                    {['INITIATED', 'AUTHORIZED'].includes(item.status) && <p>Waiting for Google consent. Return to this tab after the callback and reload connections.</p>}
                    {item.status === 'CALLBACK_RECEIVED' && <><p>Consent returned. Finish in this original signed-in session to verify access and connect the calendar.</p><button type='button' className='button button-primary' disabled={busy || blocked || clock === 0 || Date.parse(item.expiresAt) <= clock} onClick={() => void mutate(signal => integrationsApi.completeGoogleConsent(item.id, signal), 'Connection completion accepted.')}>Finish connection</button></>}
                    {['EXCHANGE_UNKNOWN', 'FAILED', 'EXPIRED', 'CANCELLED'].includes(item.status) && <p>This request cannot be finished again. Review the connection status after reloading; a new consent may be needed.</p>}
                </li>)}</ul></>}
                <details><summary>Download calendar file</summary><p>Download upcoming approved appointments for the next 30 days, up to 500 events. The file contains generic titles and UTC times. Larger exports are rejected without a partial file. Importing it is a one-time copy.</p><button type='button' className='button button-secondary' disabled={busy} onClick={() => void downloadCalendar()}>Download calendar (.ics)</button></details>
            </section>
            <section className={styles.panel} aria-labelledby='slack-title'><h2 id='slack-title'>Slack arrival notices</h2>
                <p>Send a generic arrival notice to one Slack channel, with a link to the signed-in BrainServe workspace. Visitor names, appointment details and access codes are excluded.</p>
                {!slackVerified && <p role='status'>{busy ? 'Checking Slack availability…' : 'Slack availability could not be verified. Reload connections to check configuration.'}</p>}
                {slackVerified && !slackConfig?.configured && <p role='status'>Slack is not configured on this service. Ask your service operator to enable Slack arrival notices.</p>}
                {slackVerified && slackConfig?.configured && <><p>Use a dedicated Slack app with only the chat:write bot scope. Invite its bot to the channel first. Use a nonrotating bot token; renew it manually in Slack before its BrainServe expiry, within 90 days.</p>
                    <details><summary>Add a Slack connection</summary><form onSubmit={createSlack} autoComplete='off' aria-busy={busy}><fieldset disabled={!canSlack}><div className={styles.fields}>
                        <label>Slack connection label<input required maxLength={80} value={slackLabel} onChange={event => setSlackLabel(event.target.value)} /></label>
                        <label>Slack channel ID<input required pattern='[CG][A-Z0-9]{8,31}' maxLength={32} value={slackChannel} onChange={event => setSlackChannel(event.target.value)} aria-invalid={Boolean(slackChannel && !slackChannelValid)} aria-describedby='slack-channel-help' /></label>
                        <label>Slack bot token<input ref={slackCredentialRef} type='password' required pattern='xoxb-(?:[A-Za-z0-9]|-){12,250}' minLength={17} maxLength={255} autoComplete='off' spellCheck={false} /></label>
                        <label>Slack credential expiry<input type='datetime-local' required min={expiryMin} max={expiryMax} value={slackExpires} onChange={event => setSlackExpires(event.target.value)} /></label>
                    </div><p id='slack-channel-help' className={styles.muted}>{slackChannel && !slackChannelValid ? 'Enter a Slack channel ID beginning with C or G, without spaces, a channel name or a URL.' : 'Copy the channel ID from Slack. The destination cannot be changed after creation.'}</p>
                        <p className={styles.muted}>The token is cleared when submitted and cannot be viewed again. A connection check verifies the token; use Send Slack test notice after creation to check delivery to the channel.</p>
                        <label className={styles.check}><input type='checkbox' checked={slackConfirmed} onChange={event => setSlackConfirmed(event.target.checked)} />I confirm this dedicated bot is invited to the selected channel.</label>
                        <div className={styles.actions}><button type='submit' className='button button-primary' disabled={!canSlack || !slackConfirmed || !slackLabel.trim() || !slackChannelValid}>Create Slack connection</button></div>
                    </fieldset></form></details>
                </>}
            </section>
            <details className={styles.panel}><summary>Add a simulator connection</summary><form onSubmit={create} autoComplete='off' aria-busy={busy}><fieldset disabled={busy || blocked}><legend>Connection details</legend><div className={styles.fields}>
                <label>Provider<select value={provider} onChange={e => setProvider(e.target.value as IntegrationProvider)}><option value='SIMULATOR_CALENDAR'>Calendar simulator</option><option value='SIMULATOR_MESSAGING'>Messaging simulator</option></select></label>
                <label>Connection label<input required maxLength={80} value={label} onChange={e => setLabel(e.target.value)} /></label>
                <label>Connection credential<input ref={credentialRef} type='password' required minLength={16} maxLength={4096} autoComplete='off' spellCheck={false} /></label>
                <label>Credential expiry<input type='datetime-local' required min={expiryMin} max={expiryMax} value={expires} onChange={e => setExpires(e.target.value)} /></label>
            </div><p className={styles.muted}>Use a credential of at least 16 characters and an expiry within 90 days. Credentials are cleared when submitted and cannot be viewed again. Minimum scopes are fixed by the service.</p><div className={styles.actions}><button className='button button-primary' type='submit' disabled={busy || blocked}>Create connection</button></div></fieldset></form></details>
            <div className={styles.columns}><section className={styles.panel} aria-labelledby='connection-list-title'><h2 id='connection-list-title'>Connections</h2>
                {!loaded && <p role='status'>{busy ? 'Loading connections…' : 'Reload connections to verify the source.'}</p>}
                {loaded && connections.length === 0 && <p>No connections available. {slackConfig?.configured ? 'Add a Slack connection, connect an available calendar or add a simulator to begin.' : googleConfig?.configured ? 'Connect Google Calendar or add a simulator to begin.' : 'Add a simulator or use the calendar file fallback.'}</p>}
                <ul className={styles.list}>{connections.map(item => <li key={item.id}><button type='button' className={styles.connection} aria-pressed={selectedId === item.id} disabled={busy} onClick={() => void loadDeliveries(item.id, 0)}><strong>{item.label}</strong><small>{providerName(item.provider)}</small><span className={styles.status}>{human(item.status)}</span></button></li>)}</ul>
            </section><section className={styles.panel} aria-labelledby='connection-detail-title'><h2 id='connection-detail-title'>{selected ? selected.label : 'Connection details'}</h2>
                {!selected && <p>Select a connection to inspect its status and delivery attempts.</p>}
                {selected && <><dl className={styles.facts}><div><dt>Status</dt><dd>{human(selected.status)}{selected.status === 'ACTIVE' && !eligible ? isGoogle ? ' · consent expired' : ' · credential expired' : ''}</dd></div><div><dt>Provider</dt><dd>{providerName(selected.provider)}</dd></div><div><dt>{isGoogle ? 'Consent eligibility until' : 'Credential expiry'}</dt><dd>{date(selected.credentialExpiresAt)}</dd></div><div><dt>Observed version</dt><dd>{selected.version} · credential version {selected.credentialVersion}</dd></div><div><dt>Owner reference</dt><dd>{selected.ownerId}</dd></div><div><dt>Minimum scopes</dt><dd>{selected.minimumScopes.join(', ')}</dd></div><div><dt>Last checked</dt><dd>{date(selected.lastCheckedAt)}</dd></div><div><dt>Last result</dt><dd>{selected.lastResultCode ?? 'Not checked'}</dd></div></dl>
                    {isGoogle && <><dl className={styles.facts}><div><dt>Calendar provisioning</dt><dd>{googleMetadata ? human(googleMetadata.provisioningStatus) : 'Not verified'}</dd></div><div><dt>Remote revocation</dt><dd>{googleMetadata ? human(googleMetadata.revocationStatus) : 'Not verified'}</dd></div><div><dt>Google recovery result</dt><dd>{googleMetadata?.lastResultCode ?? 'Not recorded'}</dd></div></dl>
                        {selected.status !== 'REVOKED' && <details><summary>Reconnect Google Calendar</summary><p>Grant consent again to restore access to the same retained calendar. Reconsent does not create a replacement calendar.</p><label className={styles.check}><input type='checkbox' checked={reconsentConfirmed} disabled={!canConsent} onChange={event => setReconsentConfirmed(event.target.checked)} />I approve renewing access to this BrainServe calendar.</label><button type='button' className='button button-primary' disabled={!canConsent || !reconsentConfirmed} onClick={() => void startConsent(selected)}>Start Google reconsent</button></details>}
                        {googleMetadata?.provisioningStatus === 'PROVISIONING_UNKNOWN' && selected.status !== 'REVOKED' && <details open><summary>Recover existing Google calendar</summary><p>Calendar creation had an unknown outcome. Find the existing BrainServe calendar in Google Calendar settings and enter its calendar ID. The service verifies the app marker before using it. Do not enter a URL or create another calendar.</p><form onSubmit={recoverCalendar}><fieldset disabled={busy || blocked}><label className={styles.field}>Existing Google calendar ID<input required maxLength={512} value={calendarId} onChange={event => setCalendarId(event.target.value)} aria-invalid={Boolean(calendarId && !calendarIdValid)} aria-describedby='calendar-id-help' /></label><p id='calendar-id-help' className={styles.muted}>{calendarId && !calendarIdValid ? 'Enter a calendar ID containing @, without spaces or a web address.' : 'Use only the calendar ID from its settings, without spaces or a web address.'}</p><button type='submit' className='button button-primary' disabled={busy || blocked || !calendarIdValid}>Verify and recover calendar</button></fieldset></form></details>}
                        {googleMetadata?.revocationStatus === 'FAILED' && <details><summary>Retry remote revocation</summary><p>Local revocation already stops deliveries. Retry removing the retained Google access grant.</p><button type='button' className='button button-secondary' disabled={busy || blocked} onClick={() => void mutate(signal => integrationsApi.retryGoogleRevocation(selected.id, selected.version, signal), 'Remote revocation retry accepted.')}>Retry Google revocation</button></details>}
                        <details><summary>Reconcile Google calendar</summary><p>Check the latest approved appointment state and repair missing or drifted BrainServe events in batches, up to 500 resources per run. Reconciliation does not import Google edits. Runs are limited to one every five minutes and three per 24 hours.</p>
                            {reconciliation && <p role='status'>Reconciliation {human(reconciliation.status)} · {reconciliation.processed} resources processed{reconciliation.completedAt ? ` · finished ${date(reconciliation.completedAt)}` : '. Reload to check progress.'}</p>}
                            <div className={styles.actions}><button type='button' className='button button-primary' disabled={busy || blocked || !eligible || googleMetadata?.provisioningStatus !== 'READY' || ['QUEUED', 'RUNNING'].includes(reconciliation?.status ?? '')} onClick={() => void mutate(signal => integrationsApi.reconcile(selected.id, selected.version, signal), 'Reconciliation accepted.')}>Start calendar reconciliation</button><button type='button' className='button button-secondary' disabled={busy} onClick={() => void loadDeliveries(selected.id, deliveryPage)}>Check reconciliation progress</button></div>
                        </details>
                    </>}
                    {isSlack && <><dl className={styles.facts}><div><dt>Slack workspace ID</dt><dd>{slackMetadata?.workspaceId ?? 'Not verified'}</dd></div><div><dt>Slack channel ID</dt><dd>{slackMetadata?.channelId ?? 'Not verified'}</dd></div><div><dt>Slack bot ID</dt><dd>{slackMetadata?.botId ?? 'Not verified'}</dd></div><div><dt>Remote revocation</dt><dd>{slackMetadata ? human(slackMetadata.revocationStatus) : 'Not verified'}</dd></div><div><dt>Slack recovery result</dt><dd>{slackMetadata?.lastResultCode ?? 'Not recorded'}</dd></div></dl>
                        <details><summary>Test Slack delivery</summary><p>Sends a fixed test notice to this channel. Reload connections to inspect its delivery status and attempt history.</p><button type='button' className='button button-primary' disabled={!canSlack || !eligible} onClick={() => void mutate(signal => integrationsApi.test(selected.id, selected.version, 'SUCCESS', signal), 'Slack test notice accepted.')}>Send Slack test notice</button></details>
                        {(selected.status !== 'REVOKED' || slackMetadata?.revocationStatus === 'COMPLETE') && <details><summary>Renew Slack credential</summary><p>Rotate or revoke the old token in Slack first, then provide a replacement for the same workspace and bot. Reinvite the bot to this channel if Slack removed it. BrainServe does not refresh Slack tokens automatically. To change the channel, revoke this connection and create a new one.</p>{selected.status === 'REVOKED' && <p>Remote revocation is complete. A new token from Slack and renewed channel membership reactivate this connection for future notices. Previously cancelled notices stay cancelled.</p>}<form onSubmit={reconnect} autoComplete='off'><fieldset disabled={!canRenewSlack}><div className={styles.fields}>
                            <label>Replacement Slack bot token<input ref={reconnectCredentialRef} type='password' required pattern='xoxb-(?:[A-Za-z0-9]|-){12,250}' minLength={17} maxLength={255} autoComplete='off' spellCheck={false} /></label><label>Replacement Slack expiry<input type='datetime-local' required min={expiryMin} max={expiryMax} value={reconnectExpires} onChange={event => setReconnectExpires(event.target.value)} /></label>
                        </div><label className={styles.check}><input type='checkbox' checked={slackRenewalConfirmed} onChange={event => setSlackRenewalConfirmed(event.target.checked)} />I rotated or revoked the old token in Slack before this renewal.</label><div className={styles.actions}><button type='submit' className='button button-primary' disabled={!canRenewSlack || !slackRenewalConfirmed}>Renew Slack credential</button></div></fieldset></form></details>}
                        {slackMetadata?.revocationStatus === 'FAILED' && <details><summary>Retry Slack remote revocation</summary><p>Local revocation already stops deliveries. Retry revoking the retained bot token in Slack. Revoking this dedicated bot token removes the bot from its channels.</p><button type='button' className='button button-secondary' disabled={busy || blocked} onClick={() => void mutate(signal => integrationsApi.retrySlackRevocation(selected.id, selected.version, signal), 'Slack remote revocation retry accepted.')}>Retry Slack revocation</button></details>}
                    </>}
                    {!isGoogle && !isSlack && <><details><summary>Test delivery behaviour</summary><label className={styles.field}>Simulator scenario<select value={scenario} disabled={busy || blocked || !eligible} onChange={e => setScenario(e.target.value as TestScenario)}><option value='SUCCESS'>Successful delivery</option><option value='OUTAGE'>Provider outage</option><option value='RATE_LIMITED'>Rate limit</option><option value='REAUTH_REQUIRED'>Reauthentication required</option><option value='PERMANENT_FAILURE'>Permanent failure</option></select></label><p>Creates an explicit simulator test delivery for this connection. Reload to inspect the outcome and attempts.</p><div className={styles.actions}><button type='button' className='button button-primary' disabled={busy || blocked || !eligible} onClick={() => void mutate(signal => integrationsApi.test(selected.id, selected.version, scenario, signal), 'Simulator test accepted.')}>Run simulator test</button></div></details>
                    <details><summary>Replace credential</summary><form onSubmit={reconnect} autoComplete='off'><fieldset disabled={busy || blocked}><div className={styles.fields}><label>Replacement credential<input ref={reconnectCredentialRef} type='password' required minLength={16} maxLength={4096} autoComplete='off' spellCheck={false} /></label><label>Replacement expiry<input type='datetime-local' required min={expiryMin} max={expiryMax} value={reconnectExpires} onChange={e => setReconnectExpires(e.target.value)} /></label></div><p>Replacing a credential advances its version. Pending deliveries use the new credential only after the service verifies eligibility.</p><div className={styles.actions}><button className='button button-primary' type='submit' disabled={busy || blocked}>Reconnect connection</button></div></fieldset></form></details></>}
                    <details><summary>Revoke connection</summary><p>Revocation stops new deliveries and cancels pending work for this connection.</p>{isSlack && <p>Remote revocation is queued and its status appears above after reloading. Revoking the dedicated bot token in Slack removes the bot from its channels. Completed notices remain in Slack.</p>}<label className={styles.check}><input type='checkbox' checked={revokeConfirmed} disabled={busy || blocked || selected.status === 'REVOKED'} onChange={e => setRevokeConfirmed(e.target.checked)} />I confirm revoking this connection.</label><div className={styles.actions}><button type='button' className='button button-secondary' disabled={busy || blocked || !revokeConfirmed || selected.status === 'REVOKED'} onClick={() => void mutate(signal => integrationsApi.revoke(selected.id, selected.version, signal), 'Revocation accepted.')}>Confirm connection revocation</button></div></details>
                    <h3>Delivery backlog</h3><p className={styles.muted}>Statuses and attempt history contain references and result codes; private delivery content stays out of this view.</p>
                    {isSlack && <p>Arrival notices expire 24 hours after the event. Provider rate limits delay confirmed retries; an unknown acknowledgement requires your review before retrying.</p>}
                    {deliveriesLoaded && deliveries.length === 0 && <p>No deliveries on this page.</p>}
                    {deliveries.map(item => <article className={styles.row} key={item.id} aria-label={`Delivery ${item.id}`}><div className={styles.rowHeader}><strong>Revision {item.businessRevision}</strong><span className={styles.status}>{human(item.status)}</span></div><dl className={styles.facts}><div><dt>Delivery reference</dt><dd>{item.id}</dd></div><div><dt>Attempts</dt><dd>{item.totalAttempts} of 20 total · {item.manualRetries} of 3 manual retries</dd></div><div><dt>Next attempt</dt><dd>{date(item.nextAttemptAt)}</dd></div><div><dt>Last result</dt><dd>{item.lastResultCode ?? 'Pending'}</dd></div></dl>
                        {item.status === 'UNKNOWN' && <><p>Slack may already have accepted this notice before its acknowledgement was lost. Check the channel before retrying. A retry can send a duplicate notice.</p><label className={styles.check}><input type='checkbox' checked={Boolean(duplicateRisk[item.id])} disabled={busy || blocked} onChange={event => setDuplicateRisk(previous => ({ ...previous, [item.id]: event.target.checked }))} />I accept the duplicate-notice risk for delivery {item.id}.</label></>}
                        <div className={styles.actions}><button type='button' className='button button-secondary' disabled={busy} onClick={() => void loadAttempts(item.id)}>Load delivery attempts</button><button type='button' className='button button-secondary' disabled={busy || blocked || !eligible || (isSlack && !canSlack) || item.manualRetries >= 3 || item.totalAttempts >= 20 || !['FAILED', 'NEEDS_RECONNECT', 'UNKNOWN'].includes(item.status) || (item.status === 'UNKNOWN' && !duplicateRisk[item.id])} onClick={() => { const acceptDuplicateRisk = item.status === 'UNKNOWN' && duplicateRisk[item.id]; setDuplicateRisk(previous => ({ ...previous, [item.id]: false })); void mutate(signal => integrationsApi.retry(item.id, item.version, signal, acceptDuplicateRisk || undefined), 'Delivery retry accepted.'); }}>{item.status === 'UNKNOWN' ? 'Retry uncertain delivery' : 'Retry failed delivery'}</button></div>
                        {attempts[item.id] && <ol>{attempts[item.id].map(attempt => <li key={attempt.id}>Attempt {attempt.attemptNumber}: {attempt.outcome} · {date(attempt.startedAt)}</li>)}{attempts[item.id].length === 0 && <li>No attempts recorded.</li>}</ol>}
                    </article>)}
                    {deliveryPages > 1 && <div className={styles.actions}><button type='button' className='button button-secondary' aria-label='Previous deliveries page' disabled={busy || deliveryPage === 0} onClick={() => void loadDeliveries(selected.id, deliveryPage - 1)}>Previous</button><span>Page {deliveryPage + 1} of {deliveryPages}</span><button type='button' className='button button-secondary' aria-label='Next deliveries page' disabled={busy || deliveryPage + 1 >= deliveryPages} onClick={() => void loadDeliveries(selected.id, deliveryPage + 1)}>Next</button></div>}
                </>}
            </section></div>
        </>}
    </section>;
}
