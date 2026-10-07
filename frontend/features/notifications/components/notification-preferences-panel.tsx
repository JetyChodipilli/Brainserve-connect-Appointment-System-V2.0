'use client';

import { useCallback, useEffect, useRef, useState, type FormEvent } from 'react';
import { Bell, ShieldCheck } from 'lucide-react';
import { ApiError, isBackendConfigured } from '../../../lib/api-client';
import { applyNotificationSoundPreference } from '../../../services/notification-sounds';
import { notificationPolicyApi } from '../api/notification-policy-api';
import type { NotificationPreference } from '../types/notification-policy';

export function NotificationPreferencesPanel() {
    const [value, setValue] = useState<NotificationPreference | null>(null);
    const [busy, setBusy] = useState(false);
    const [blocked, setBlocked] = useState(false);
    const [error, setError] = useState('');
    const [message, setMessage] = useState('');
    const [sessionEnded, setSessionEnded] = useState(false);
    const generation = useRef(0);
    const request = useRef<AbortController | null>(null);
    const errorRef = useRef<HTMLDivElement>(null);
    const load = useCallback(async () => {
        if (!isBackendConfigured) return;
        request.current?.abort(); request.current = new AbortController(); const current = ++generation.current;
        setBusy(true); setError(''); setMessage('');
        try {
            const saved = await notificationPolicyApi.preferences(request.current.signal);
            if (current !== generation.current) return;
            setValue(saved); setBlocked(false); applyNotificationSoundPreference(saved.soundEnabled);
        } catch (reason) {
            if (current === generation.current) setError(reason instanceof Error ? reason.message : 'Preferences could not be loaded.');
        } finally { if (current === generation.current) setBusy(false); }
    }, []);
    const cancelPending = useCallback(() => { generation.current++; request.current?.abort(); }, []);
    useEffect(() => {
        const timer = window.setTimeout(() => void load(), 0);
        const clear = () => { generation.current++; request.current?.abort(); setValue(null); setError(''); setMessage(''); setSessionEnded(true); setBusy(false); };
        window.addEventListener('brainserve:auth-session-changed', clear);
        window.addEventListener('brainserve:auth-session-expired', clear);
        return () => { window.clearTimeout(timer); cancelPending(); window.removeEventListener('brainserve:auth-session-changed', clear); window.removeEventListener('brainserve:auth-session-expired', clear); };
    }, [load, cancelPending]);
    const edit = <K extends keyof NotificationPreference>(key: K, next: NotificationPreference[K]) => setValue(previous => previous ? { ...previous, [key]: next } : previous);
    const sameQuietTimes = Boolean(value?.quietEnabled && value.quietStart === value.quietEnd);
    const save = async (event: FormEvent) => {
        event.preventDefault(); if (!value || blocked || busy || sameQuietTimes || sessionEnded) return;
        setBusy(true); setError(''); setMessage(''); const current = generation.current;
        try {
            const saved = await notificationPolicyApi.savePreferences(value, request.current?.signal);
            if (current !== generation.current) return;
            setValue(saved); applyNotificationSoundPreference(saved.soundEnabled); setMessage('Delivery preferences saved. Existing history is retained.');
        } catch (reason) {
            if (current !== generation.current) return;
            setBlocked(true);
            setError(reason instanceof ApiError && reason.status === 409 ? 'Preferences changed in another session. Reload saved preferences before editing again.'
                : 'The save was not confirmed. Reload saved preferences to check the result before trying again.');
            window.requestAnimationFrame(() => errorRef.current?.focus());
        } finally { if (current === generation.current) setBusy(false); }
    };
    return <section className='notification-policy-panel panel glass-panel' aria-labelledby='delivery-preferences-title'>
        <header className='notification-policy-heading'><div><h2 id='delivery-preferences-title'>Delivery preferences</h2><p>Choose how routine messages reach you.</p></div><Bell aria-hidden='true' size={22} /></header>
        <p className='notification-mandatory-note'><ShieldCheck size={20} aria-hidden='true' /><span>Account security, approval actions, visitor and escalation notices always reach the inbox immediately. Quiet hours and routine channel choices cannot disable them.</span></p>
        {!isBackendConfigured && <p>Delivery preferences are available when you sign in to the connected service.</p>}
        {sessionEnded && <p role='status'>The account changed. Open preferences from the current workspace.</p>}
        {error && <div className='login-error' role='alert' tabIndex={-1} ref={errorRef}>{error}</div>}
        {value && !sessionEnded && <form onSubmit={save} aria-busy={busy}>
            <fieldset disabled={busy || blocked}><legend>Routine channels</legend><div className='notification-checks'>
                <label><input type='checkbox' checked={value.inAppEnabled} onChange={e => edit('inAppEnabled', e.target.checked)} />Routine inbox delivery</label>
                <label><input type='checkbox' checked={value.emailEnabled} onChange={e => edit('emailEnabled', e.target.checked)} />Routine email copies</label>
                <label><input type='checkbox' checked={value.soundEnabled} onChange={e => edit('soundEnabled', e.target.checked)} />Browser notification sounds</label>
            </div><p>Email copies contain a sign-in prompt. Private message content stays in the authorized app. Routine messages remain available in Archive when inbox delivery is off.</p></fieldset>
            <fieldset disabled={busy || blocked}><legend>Delivery schedule</legend><div className='notification-policy-fields'>
                <label>Routine cadence<select value={value.cadence} onChange={e => edit('cadence', e.target.value as NotificationPreference['cadence'])}><option value='IMMEDIATE'>Immediately</option><option value='HOURLY'>Hourly digest</option><option value='DAILY'>Daily digest at 09:00</option></select></label>
                <label>Notification time zone<input required maxLength={80} value={value.zoneId} onChange={e => edit('zoneId', e.target.value)} placeholder='Asia/Kolkata' /><small>Use an IANA time zone, such as Asia/Kolkata or America/New_York.</small></label>
            </div><label className='notification-check'><input type='checkbox' checked={value.quietEnabled} onChange={e => edit('quietEnabled', e.target.checked)} />Use quiet hours for routine messages</label>
            <div className='notification-policy-fields'><label>Quiet hours start<input type='time' required disabled={!value.quietEnabled} value={value.quietStart} onChange={e => edit('quietStart', e.target.value)} aria-invalid={sameQuietTimes} aria-describedby={sameQuietTimes ? 'quiet-time-error' : undefined} /></label>
                <label>Quiet hours end<input type='time' required disabled={!value.quietEnabled} value={value.quietEnd} onChange={e => edit('quietEnd', e.target.value)} aria-invalid={sameQuietTimes} aria-describedby={sameQuietTimes ? 'quiet-time-error' : undefined} /></label></div>
                {sameQuietTimes && <p id='quiet-time-error' role='alert'>Choose different start and end times. Overnight quiet hours are supported.</p>}
                <p>Hourly and daily deliveries wait until quiet hours finish. A daily digest uses 09:00 in your selected time zone.</p></fieldset>
            <div className='notification-policy-actions'><button type='submit' className='button button-primary' disabled={busy || blocked || sameQuietTimes}>{busy ? 'Saving…' : 'Save delivery preferences'}</button><button type='button' className='button button-secondary' disabled={busy} onClick={() => void load()}>Reload saved preferences</button><small>Saved version {value.version}</small></div>
        </form>}
        {!value && isBackendConfigured && !sessionEnded && <button type='button' className='button button-secondary' disabled={busy} onClick={() => void load()}>{busy ? 'Loading preferences…' : 'Retry delivery preferences'}</button>}
        {message && <p className='success-banner' role='status'>{message}</p>}
    </section>;
}
