'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import { ApiError, isBackendConfigured } from '../../lib/api-client';
import { useAdminSession } from '../integrations/use-admin-session';
import { supportApi } from './api/support-api';
import type { DiagnosticPackage, DiagnosticPreview } from './types';
import styles from '../integrations/admin-tools.module.css';

const date = (value: string) => new Date(value).toLocaleString('en-IN');
const previewFields = (value: DiagnosticPreview) => Object.entries(value).flatMap(([key, item]) => typeof item === 'object' && item !== null
    ? Object.entries(item).map(([child, count]) => [ `${key}.${child}`, String(count) ]) : [[key, String(item)]]);

export function SupportWorkspace() {
    const [hours, setHours] = useState(24), [preview, setPreview] = useState<DiagnosticPreview | null>(null);
    const [packages, setPackages] = useState<DiagnosticPackage[]>([]), [loaded, setLoaded] = useState(false);
    const [accepted, setAccepted] = useState(false), [blocked, setBlocked] = useState(false), [error, setError] = useState(''), [message, setMessage] = useState('');
    const errorRef = useRef<HTMLDivElement>(null);
    const { begin, busy, sessionEnded, clock } = useAdminSession(() => { setPreview(null); setPackages([]); setAccepted(false); setError(''); setMessage(''); });
    const load = useCallback(async () => {
        if (!isBackendConfigured) return;
        const operation = begin(); if (!operation) return;
        setError('');
        try {
            const result = await supportApi.packages(operation.signal);
            if (!operation.current()) return;
            setPackages(result); setLoaded(true); setPreview(null); setAccepted(false); setBlocked(false); setMessage('Current package list loaded. Preview the included fields before generating another package.');
        } catch { if (operation.current()) { setBlocked(true); setError('Diagnostic packages could not be verified. Reload packages before generating a package.'); } }
        finally { operation.finish(); }
    }, [begin]);
    useEffect(() => { const timer = window.setTimeout(() => void load(), 0); return () => window.clearTimeout(timer); }, [load]);
    const loadPreview = async () => {
        if (blocked) return;
        const operation = begin(); if (!operation) return;
        setError(''); setPreview(null); setAccepted(false); setMessage('');
        try { const result = await supportApi.preview(hours, operation.signal); if (operation.current()) setPreview(result); }
        catch { if (operation.current()) setError('Included fields could not be previewed. Retry the preview when the service is available.'); }
        finally { operation.finish(); }
    };
    const generate = async () => {
        if (blocked || !accepted || !preview) return;
        const operation = begin(); if (!operation) return;
        setError(''); setMessage('');
        try {
            const result = await supportApi.generate(hours, operation.signal);
            if (!operation.current()) return;
            setPackages(previous => [result, ...previous.filter(item => item.id !== result.id)].slice(0, 20)); setLoaded(true); setPreview(null); setAccepted(false); setMessage('Diagnostic package generated. It is available to your active admin account for 24 hours.');
        } catch (reason) {
            if (!operation.current()) return;
            setBlocked(true);
            setError(reason instanceof ApiError && reason.status === 409 ? 'The package request changed. Reload packages and preview the included fields before generating again.'
                : 'Package generation was not confirmed. Reload packages to check the result before generating again.');
            window.requestAnimationFrame(() => errorRef.current?.focus());
        } finally { operation.finish(); }
    };
    const download = async (item: DiagnosticPackage) => {
        const operation = begin(); if (!operation) return;
        setError(''); setMessage('');
        let objectUrl: string | null = null;
        try {
            const content = await supportApi.download(item.id, operation.signal);
            if (!operation.current()) return;
            const blob = new Blob([JSON.stringify(content, null, 2)], { type: 'application/json' });
            objectUrl = URL.createObjectURL(blob);
            const anchor = document.createElement('a'); anchor.href = objectUrl; anchor.download = `brainserve-diagnostics-${item.id}.json`;
            document.body.appendChild(anchor); anchor.click(); anchor.remove();
            setPackages(previous => previous.map(saved => saved.id === item.id ? { ...saved, downloadCount: saved.downloadCount + 1 } : saved)); setMessage('Diagnostic package downloaded.');
        } catch (reason) {
            if (!operation.current()) return;
            setError(reason instanceof ApiError && reason.status === 410 ? 'This diagnostic package expired. Reload packages and generate a new package after previewing the fields.'
                : 'The package could not be downloaded. Verify your current access and reload packages before retrying.');
        } finally { if (objectUrl) URL.revokeObjectURL(objectUrl); operation.finish(); }
    };
    return <section className={styles.workspace} aria-labelledby='support-title'>
        <header className={styles.heading}><div><h1 id='support-title'>Support diagnostics</h1><p>Review the included fields and create a bounded support snapshot.</p></div><button type='button' className='button button-secondary' disabled={busy || sessionEnded || !isBackendConfigured} onClick={() => void load()}>Reload packages</button></header>
        <p className={styles.notice}>Packages include release and environment identifiers, database migration status, and aggregate integration delivery counts. They exclude people, appointment content, credentials, connection labels, documents, and logs. Each package expires after 24 hours and can be downloaded only by its creator while still an active System Admin.</p>
        {!isBackendConfigured && <p role='status'>Sign in to the connected service to generate support diagnostics.</p>}
        {sessionEnded && <p role='status'>The account changed. Open Support diagnostics from the current workspace.</p>}
        {error && <div className='login-error' role='alert' tabIndex={-1} ref={errorRef}>{error}</div>}
        {message && <p className={styles.notice} role='status'>{message}</p>}
        {isBackendConfigured && !sessionEnded && <div className={styles.columns}>
            <section className={styles.panel} aria-labelledby='diagnostic-preview-title'><h2 id='diagnostic-preview-title'>Included fields preview</h2><label className={styles.field}>Diagnostic time window<select value={hours} disabled={busy || blocked} onChange={e => { setHours(Number(e.target.value)); setPreview(null); setAccepted(false); }}><option value={1}>Last hour</option><option value={6}>Last 6 hours</option><option value={12}>Last 12 hours</option><option value={24}>Last 24 hours</option></select></label><div className={styles.actions}><button type='button' className='button button-secondary' disabled={busy || blocked} onClick={() => void loadPreview()}>Preview included fields</button></div>
                {!preview && <p>Load the preview to inspect every included field before generating.</p>}
                {preview && <><p className={styles.muted}>Snapshot generated {date(preview.generatedAt)}. Counts may change when the package is generated.</p><div className={styles.preview} role='region' aria-label='Diagnostic fields' tabIndex={0}><dl>{previewFields(preview).map(([name, value]) => <div key={name}><dt>{name}</dt><dd>{value}</dd></div>)}</dl></div><fieldset disabled={busy || blocked}><label className={styles.check}><input type='checkbox' checked={accepted} onChange={e => setAccepted(e.target.checked)} />I reviewed the included fields for this time window.</label></fieldset></>}
                <div className={styles.actions}><button type='button' className='button button-primary' disabled={busy || blocked || !accepted || !preview} onClick={() => void generate()}>Generate diagnostic package</button></div>
            </section>
            <section className={styles.panel} aria-labelledby='diagnostic-packages-title'><h2 id='diagnostic-packages-title'>Your available packages</h2>
                {!loaded && <p role='status'>{busy ? 'Loading package metadata…' : 'Reload packages to verify the source.'}</p>}
                {loaded && packages.length === 0 && <p>No available packages. Preview the fields to create one.</p>}
                {packages.map(item => <article className={styles.row} key={item.id} aria-label={`Diagnostic package ${item.id}`}><strong>{item.id}</strong><dl className={styles.facts}><div><dt>Generated</dt><dd>{date(item.createdAt)}</dd></div><div><dt>Expires</dt><dd>{date(item.expiresAt)}</dd></div><div><dt>Window</dt><dd>{date(item.windowStart)} to {date(item.windowEnd)}</dd></div><div><dt>Size and downloads</dt><dd>{item.sizeBytes.toLocaleString()} bytes · {item.downloadCount} downloads</dd></div></dl><button type='button' className='button button-secondary' disabled={busy || clock === 0 || Date.parse(item.expiresAt) <= clock} onClick={() => void download(item)}>Download diagnostic package</button></article>)}
            </section>
        </div>}
    </section>;
}
