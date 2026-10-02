"use client";

import { useEffect, useRef, useState, type FormEvent } from "react";
import QRCode from "qrcode";
import { CheckCircle2, ShieldCheck } from "lucide-react";
import { securityApi } from "../api/security-api";
import styles from "./security.module.css";

export function MfaChallenge({ enrolled, onComplete, onCancel }: {
    enrolled: boolean; onComplete: () => void; onCancel: () => void;
}) {
    const [setup, setSetup] = useState<{ secret: string; otpauthUri: string } | null>(null);
    const [qr, setQr] = useState("");
    const [recovery, setRecovery] = useState<string[]>([]);
    const [useRecovery, setUseRecovery] = useState(false);
    const [busy, setBusy] = useState(false);
    const [error, setError] = useState("");
    const codeInput = useRef<HTMLInputElement>(null);
    useEffect(() => { codeInput.current?.focus(); }, [setup, useRecovery]);
    useEffect(() => {
        if (!setup) return;
        let active = true;
        void QRCode.toDataURL(setup.otpauthUri, { width: 200, margin: 2 }).then((value) => {
            if (active) setQr(value);
        }).catch(() => { /* The copyable setup key remains available. */ });
        return () => { active = false; };
    }, [setup]);
    const start = async () => {
        setBusy(true); setError("");
        try { setSetup(await securityApi.enroll()); }
        catch (reason) { setError(reason instanceof Error ? reason.message : "Authenticator setup is unavailable. Try again."); }
        finally { setBusy(false); }
    };
    const verify = async (event: FormEvent<HTMLFormElement>) => {
        event.preventDefault();
        const form = event.currentTarget;
        const code = String(new FormData(form).get("code") ?? "");
        setBusy(true); setError("");
        try {
            const result = await securityApi.verify(code, Boolean(setup));
            form.reset(); setSetup(null); setQr("");
            if (result.recoveryCodes.length) setRecovery(result.recoveryCodes);
            else onComplete();
        } catch (reason) { setError(reason instanceof Error ? reason.message : "Verification failed. Try again."); }
        finally { setBusy(false); }
    };
    return <section className={styles.challenge} aria-label="Account verification" aria-busy={busy}>
        <header><ShieldCheck size={24} aria-hidden="true" /><div><h2>{recovery.length ? "Save your recovery codes" : enrolled ? "Verify your identity" : "Set up your authenticator"}</h2>
            <p>{recovery.length ? "Each code works once. Keep these in a password manager or another safe place; they will not be shown again."
                : enrolled ? "Enter an authenticator code to continue securely." : "Add BrainServe Connect to your authenticator app to protect your account."}</p></div></header>
        {recovery.length > 0 ? <><div className={styles.codes} aria-label="Recovery codes">{recovery.map((code) => <code key={code}>{code}</code>)}</div>
            <button className="button button-primary" onClick={() => { setRecovery([]); onComplete(); }}><CheckCircle2 size={17} /> I have saved my codes</button></>
            : <>{!enrolled && !setup && <button className="button button-primary" disabled={busy} onClick={() => void start()}>{busy ? "Preparing…" : "Set up authenticator"}</button>}
                {setup && <div className={styles.setup}>{qr && /* eslint-disable-next-line @next/next/no-img-element */
                    <img src={qr} width={200} height={200} alt="Scan this QR code in your authenticator app" />}
                    <label>Or copy this setup key<input readOnly value={setup.secret} aria-label="Authenticator setup key" autoComplete="off" spellCheck={false} onFocus={(event) => event.currentTarget.select()} /></label>
                    <p>Choose a time-based account. Then enter the six-digit code from your app.</p></div>}
                {(enrolled || setup) && <form onSubmit={verify} className={styles.form}>
                    <label>{useRecovery ? "Recovery code" : "Authenticator code"}<input key={String(useRecovery)} ref={codeInput} name="code" autoComplete="one-time-code" inputMode={useRecovery ? "text" : "numeric"}
                        pattern={useRecovery ? undefined : "[0-9]{6}"} minLength={6} maxLength={useRecovery ? 64 : 6} required spellCheck={false} aria-describedby="mfa-help" /></label>
                    <p id="mfa-help">{useRecovery ? "Paste one unused recovery code. You can replace a lost authenticator from My profile after signing in." : "You can paste the code from your authenticator or password manager."}</p>
                    <button className="button button-primary" disabled={busy}>{busy ? "Verifying…" : "Verify and continue"}</button>
                </form>}
                {enrolled && <button className="text-button" disabled={busy} onClick={() => { setUseRecovery((value) => !value); setError(""); }}>{useRecovery ? "Use authenticator instead" : "Use a recovery code"}</button>}
                <button className="text-button" disabled={busy} onClick={onCancel}>Cancel verification</button>
            </>}
        {error && <div className="login-error" role="alert">{error}</div>}
    </section>;
}
