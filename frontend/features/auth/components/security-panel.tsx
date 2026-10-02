"use client";

import { useCallback, useEffect, useState } from "react";
import { ShieldCheck } from "lucide-react";
import { securityApi, type DeviceSession, type SecurityState } from "../api/security-api";
import { MfaChallenge } from "./mfa-challenge";
import { authApi, isBackendConfigured, setAccessToken } from "../../../lib/api-client";
import styles from "./security.module.css";

export function SecurityPanel() {
    const [security, setSecurity] = useState<SecurityState | null>(null);
    const [sessions, setSessions] = useState<DeviceSession[]>([]);
    const [page, setPage] = useState(0);
    const [hasMore, setHasMore] = useState(false);
    const [busy, setBusy] = useState(false);
    const [error, setError] = useState("");
    const [message, setMessage] = useState("");
    const [challenge, setChallenge] = useState<"verify" | "enroll" | null>(null);
    const [confirmRevoke, setConfirmRevoke] = useState<string | null>(null);
    const load = useCallback(async () => {
        const status = await securityApi.status();
        const devices = await securityApi.sessions();
        setSecurity(status); setSessions(devices.sessions); setPage(0); setHasMore(devices.hasMore);
    }, []);
    useEffect(() => {
        if (!isBackendConfigured) return;
        let active = true;
        void Promise.all([securityApi.status(), securityApi.sessions()]).then(([status, devices]) => {
            if (active) { setSecurity(status); setSessions(devices.sessions); setHasMore(devices.hasMore); }
        }).catch((reason: unknown) => { if (active) setError(reason instanceof Error ? reason.message : "Security settings could not be loaded."); });
        return () => { active = false; };
    }, []);
    if (!isBackendConfigured) return null;
    const run = async (action: () => Promise<void>) => {
        setBusy(true); setError(""); setMessage("");
        try { await action(); }
        catch (reason) { setError(reason instanceof Error ? reason.message : "The security action could not be completed."); }
        finally { setBusy(false); }
    };
    const endLocalSession = () => { setAccessToken(null); window.dispatchEvent(new CustomEvent("brainserve:auth-session-expired")); };
    return <article className={`${styles.panel} glass-panel`} aria-label="Account security" aria-busy={busy}>
        <div className={styles.heading}><ShieldCheck size={23} aria-hidden="true" /><div><h2>Account security</h2><p>Manage your authenticator and signed-in sessions.</p></div></div>
        {!security && !error && <p role="status">Loading security settings…</p>}
        {security && <><p>{security.mfaEnrolled ? `Authenticator enabled · ${security.recoveryCodesRemaining} recovery codes remaining.` : security.mfaRequired ? "Your role requires an authenticator." : "Add an authenticator for an extra sign-in check."}</p>
            {challenge ? <MfaChallenge enrolled={challenge === "verify"} onCancel={() => setChallenge(null)} onComplete={() => {
                setChallenge(null); void run(async () => { await load(); setMessage("Identity verified. You can retry your action."); });
            }} /> : <div className={styles.actions}>
                {security.mfaEnrolled && <button className="button button-secondary" disabled={busy} onClick={() => setChallenge("verify")}>Verify identity</button>}
                <button className="button button-secondary" disabled={busy || (security.mfaEnrolled && security.stepUpRequired)} onClick={() => setChallenge("enroll")}>{security.mfaEnrolled ? "Replace authenticator" : "Set up authenticator"}</button>
            </div>}
            {security.mfaEnrolled && security.stepUpRequired && <p>Verify your identity before replacing your authenticator or revoking another session.</p>}
            <h3>Signed-in sessions</h3>
            <p>Revoking a session also ends its live updates. Verify your identity before revoking another session.</p>
            <ul className={styles.sessions}>{sessions.map((session) => <li key={session.familyId}><span><strong>{session.current ? "This session" : "Another session"}</strong><small>Started {new Date(session.createdAt).toLocaleString()}</small><small>Last renewed {new Date(session.lastSeenAt).toLocaleString()}</small></span>
                {confirmRevoke === session.familyId ? <div className={styles.actions}><button className="button button-reject" disabled={busy} onClick={() => void run(async () => {
                    if (session.current) await authApi.logout(); else await securityApi.revoke(session.familyId); setConfirmRevoke(null);
                    if (session.current) endLocalSession(); else { await load(); setMessage("Session revoked."); }
                })}>Confirm revoke</button><button className="button button-secondary" onClick={() => setConfirmRevoke(null)}>Cancel</button></div>
                    : <button className="button button-secondary" disabled={busy || (!session.current && (!security.mfaEnrolled || security.stepUpRequired))} onClick={() => setConfirmRevoke(session.familyId)}>{session.current ? "Sign out this session" : "Revoke session"}</button>}
            </li>)}</ul>
            {hasMore && <button className="button button-secondary" disabled={busy} onClick={() => void run(async () => {
                const devices = await securityApi.sessions(page + 1);
                setSessions((current) => [...current, ...devices.sessions.filter((item) => !current.some((existing) => existing.familyId === item.familyId))]);
                setPage(devices.page); setHasMore(devices.hasMore);
            })}>Show more sessions</button>}
            <div className={styles.actions}><button className="button button-secondary" disabled={busy} onClick={() => void run(load)}>Refresh sessions</button>
                <button className="button button-reject" disabled={busy} onClick={() => setConfirmRevoke("all")}>Sign out everywhere</button></div>
            {confirmRevoke === "all" && <div className={styles.actions}><p>This also signs you out of this session.</p><button className="button button-reject" disabled={busy} onClick={() => void run(async () => { await securityApi.logoutAll(); endLocalSession(); })}>Confirm sign out everywhere</button><button className="button button-secondary" onClick={() => setConfirmRevoke(null)}>Cancel</button></div>}
        </>}
        {message && <div className="success-banner" role="status">{message}</div>}
        {error && <div className="login-error" role="alert">{error}<div className={styles.actions}><button className="text-button" disabled={busy} onClick={() => void run(load)}>Retry security settings</button><button className="text-button" onClick={() => void authApi.logout().then(endLocalSession)}>Sign out</button></div></div>}
    </article>;
}
