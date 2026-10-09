'use client';

import { useCallback, useEffect, useRef, useState, type FormEvent } from 'react';
import { ApiError, isBackendConfigured } from '../../lib/api-client';
import { useAdminSession } from '../integrations/use-admin-session';
import { integrationsApi } from '../integrations/api/integrations-api';
import { releaseApi } from './api/release-api';
import { agreementStatuses, type ReleaseProfile, type ReleaseSnapshot } from './types';
import styles from '../integrations/admin-tools.module.css';

type Availability = { name: string; configured: boolean | null };
const statusName = (value: string) => value.charAt(0) + value.slice(1).toLowerCase();
function checked(value: ReleaseSnapshot): ReleaseSnapshot {
    const profile = value?.profile;
    if (!Number.isSafeInteger(value?.version) || value.version < 0 || !profile || !agreementStatuses.includes(profile.status)
        || !['reference', 'supportOwner', 'supportEmail', 'supportHours'].every(key => typeof profile[key as keyof ReleaseProfile] === 'string')
        || ![profile.startsOn, profile.renewsOn].every(date => date === null || typeof date === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(date))
        || typeof value.officeZone !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(value.officeDate) || typeof value.renewalDue !== 'boolean') {
        throw new Error('Release source could not be verified');
    }
    return value;
}

export function ReleaseWorkspace() {
    const [snapshot, setSnapshot] = useState<ReleaseSnapshot | null>(null), [draft, setDraft] = useState<ReleaseProfile | null>(null);
    const [availability, setAvailability] = useState<Availability[]>([]), [blocked, setBlocked] = useState(true);
    const [error, setError] = useState(''), [message, setMessage] = useState('');
    const errorRef = useRef<HTMLDivElement>(null);
    useEffect(() => { if (error) errorRef.current?.focus(); }, [error]);
    const { begin, busy, sessionEnded } = useAdminSession(() => {
        setSnapshot(null); setDraft(null); setAvailability([]); setBlocked(true); setError(''); setMessage('');
    });
    const load = useCallback(async () => {
        if (!isBackendConfigured) return;
        const operation = begin(); if (!operation) return;
        setSnapshot(null); setDraft(null); setAvailability([]); setBlocked(true); setError(''); setMessage('');
        try {
            const [record, google, slack, kiosk] = await Promise.allSettled([
                releaseApi.read(operation.signal), integrationsApi.googleConfig(operation.signal),
                integrationsApi.slackConfig(operation.signal), releaseApi.kioskConfig(operation.signal),
            ]);
            if (!operation.current()) return;
            if (record.status === 'rejected') throw record.reason;
            const value = checked(record.value); setSnapshot(value); setDraft({ ...value.profile }); setBlocked(false);
            setAvailability([
                { name: 'Google Calendar', configured: google.status === 'fulfilled' && typeof google.value.configured === 'boolean' ? google.value.configured : null },
                { name: 'Slack arrival notices', configured: slack.status === 'fulfilled' && typeof slack.value.configured === 'boolean' ? slack.value.configured : null },
                { name: 'Visitor kiosk intake', configured: kiosk.status === 'fulfilled' && typeof kiosk.value.enabled === 'boolean' ? kiosk.value.enabled : null },
            ]);
        } catch (reason) {
            if (operation.current()) setError(reason instanceof ApiError && reason.problem.errorCode === 'MFA_STEP_UP_REQUIRED'
                ? 'Verify your identity in My profile → Account security, then reload the saved record.'
                : 'The saved release record could not be verified. Reload when the service is available.');
        } finally { operation.finish(); }
    }, [begin]);
    useEffect(() => { const timer = window.setTimeout(() => void load(), 0); return () => window.clearTimeout(timer); }, [load]);
    const save = async (event: FormEvent) => {
        event.preventDefault(); if (!snapshot || !draft || blocked) return;
        const operation = begin(); if (!operation) return;
        setError(''); setMessage('');
        try {
            const value = await releaseApi.save(snapshot.version, draft, operation.signal);
            if (!operation.current()) return;
            const verified = checked(value); setSnapshot(verified); setDraft({ ...verified.profile });
            setMessage('Agreement and support record saved.');
        } catch (reason) {
            if (!operation.current()) return;
            const invalid = reason instanceof ApiError && reason.status === 400 && reason.problem.errorCode === 'RELEASE_PROFILE_INVALID';
            setBlocked(!invalid);
            setError(reason instanceof ApiError && reason.status === 409
                ? 'Another administrator changed this record. Reload the saved record before editing again.'
                : reason instanceof ApiError && reason.problem.errorCode === 'MFA_STEP_UP_REQUIRED'
                    ? 'Verify your identity in My profile → Account security, then reload the saved record before saving.'
                    : reason instanceof ApiError && reason.problem.errorCode === 'RELEASE_PROFILE_INVALID'
                        ? 'Check the agreement dates and required support details, then save the corrected record.'
                        : 'Saving was not confirmed. Reload the saved record to check the result before saving again.');
        } finally { operation.finish(); }
    };
    const change = (key: keyof ReleaseProfile, value: string | null) => setDraft(previous => previous ? { ...previous, [key]: value } : previous);
    const term = draft?.status === 'ACTIVE' || draft?.status === 'PILOT';
    return <section className={styles.workspace} aria-labelledby='release-title' aria-busy={busy}>
        <header className={styles.heading}><div><h1 id='release-title'>Release and support</h1><p>Keep the agreed subscription dates and support responsibilities current.</p></div>
            <button type='button' className='button button-secondary' disabled={busy || sessionEnded || !isBackendConfigured} onClick={() => void load()}>Reload saved record</button></header>
        <p className={styles.notice}>The agreement record does not change staff access or visitor operations. Release approval comes from the separate pilot and operational signoffs.</p>
        {!isBackendConfigured && <p role='status'>Sign in to the connected service to manage the release record.</p>}
        {sessionEnded && <p role='status'>The account changed. Open Release and support from the current workspace.</p>}
        {busy && <p role='status'>Waiting for the service response…</p>}
        {error && <div className='login-error' role='alert' tabIndex={-1} ref={errorRef}>{error}</div>}
        {message && <p className={styles.notice} role='status'>{message}</p>}
        {snapshot && draft && !sessionEnded && <>
            <section className={styles.panel} aria-labelledby='agreement-title'><h2 id='agreement-title'>Agreement and renewal</h2>
                <p>Recorded status: <strong>{statusName(snapshot.profile.status)}</strong>. Dates use {snapshot.officeZone}; today is {snapshot.officeDate}.</p>
                {snapshot.renewalDue && <p className={styles.notice} role='status'>The recorded renewal date is due. Contact the support owner to review the agreement.</p>}
                <form onSubmit={event => void save(event)}><fieldset className={styles.fields} disabled={busy || blocked}>
                    <label className={styles.field}>Agreement status<select value={draft.status} onChange={event => change('status', event.target.value)}>{agreementStatuses.map(value => <option key={value} value={value}>{statusName(value)}</option>)}</select></label>
                    <label className={styles.field}>Agreement reference<input value={draft.reference} onChange={event => change('reference', event.target.value)} maxLength={80} required={term} /></label>
                    <label className={styles.field}>Term starts on<input type='date' value={draft.startsOn ?? ''} onChange={event => change('startsOn', event.target.value || null)} required={term || Boolean(draft.renewsOn)} /></label>
                    <label className={styles.field}>Renewal date<input type='date' value={draft.renewsOn ?? ''} min={draft.startsOn ?? undefined} onChange={event => change('renewsOn', event.target.value || null)} required={term || Boolean(draft.startsOn)} /></label>
                    <label className={styles.field}>Support owner<input value={draft.supportOwner} onChange={event => change('supportOwner', event.target.value)} maxLength={120} required={term} autoComplete='name' /></label>
                    <label className={styles.field}>Support contact email<input type='email' value={draft.supportEmail} onChange={event => change('supportEmail', event.target.value)} maxLength={254} required={term} autoComplete='email' /></label>
                    <label className={styles.field}>Agreed support hours<input value={draft.supportHours} onChange={event => change('supportHours', event.target.value)} maxLength={160} required={term} placeholder='Days, hours and time zone from the agreement' /></label>
                </fieldset><div className={styles.actions}><button type='submit' className='button button-primary' disabled={busy || blocked}>Save agreement and support</button></div></form>
            </section>
            <section className={styles.panel} aria-labelledby='optional-features-title'><h2 id='optional-features-title'>Optional feature configuration</h2>
                <p>Availability comes from the connected service. A configured feature still needs its connection or device checks and customer acceptance.</p>
                <dl className={styles.facts}>{availability.map(item => <div key={item.name}><dt>{item.name}</dt><dd>{item.configured === null ? 'Could not verify' : item.configured ? 'Configured on this service' : 'Not configured on this service'}</dd></div>)}</dl>
                <p>Manage calendar and Slack connections in Integrations. Manage visitor codes in Visitor devices. Use Support diagnostics to preview a redacted incident package.</p>
            </section>
        </>}
    </section>;
}
