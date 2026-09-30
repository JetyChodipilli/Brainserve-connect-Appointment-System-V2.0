"use client";

import { brainServeApi, isBackendConfigured } from "../../services/brainserve-api";
import { hashDemoPassword, readDemoAccounts, writeDemoAccounts } from "../../preview/accounts";
import { Logo } from "../../components/shared/logo";
import { fail } from "../../utils/errors";
import { LockKeyhole, ShieldCheck } from "lucide-react";
import { type FormEvent, useState } from "react";

export function ForcedPasswordChange({ email, currentPassword: initialPassword, onComplete, onLogout }: {
    email: string;
    currentPassword: string;
    onComplete: () => void;
    onLogout: () => void;
}) {
    const [currentPassword, setCurrentPassword] = useState(initialPassword);
    const [otp, setOtp] = useState("");
    const [newPassword, setNewPassword] = useState("");
    const [confirmPassword, setConfirmPassword] = useState("");
    const [step, setStep] = useState<"request" | "confirm">("request");
    const [busy, setBusy] = useState(false);
    const [error, setError] = useState("");
    const [previewOtp, setPreviewOtp] = useState("");
    const requestOtp = async (event: FormEvent) => {
        event.preventDefault(); setBusy(true); setError("");
        try {
            if (isBackendConfigured) {
                await brainServeApi.requestPasswordChangeOtp(currentPassword);
            } else {
                const passwordHash = await hashDemoPassword(currentPassword);
                const account = readDemoAccounts().find((item) => item.email === email.toLowerCase()
                    && item.passwordHash === passwordHash && item.status === "ACTIVE" && item.forcePasswordChange);
                if (!account) fail("The temporary password is invalid or has already been changed.");
                const value = String(crypto.getRandomValues(new Uint32Array(1))[0] % 1_000_000).padStart(6, "0");
                setPreviewOtp(value);
            }
            setStep("confirm");
        } catch (reason) {
            setError(reason instanceof Error ? reason.message : "The verification code could not be sent.");
        } finally { setBusy(false); }
    };
    const confirm = async (event: FormEvent) => {
        event.preventDefault(); setError("");
        if (newPassword !== confirmPassword) { setError("New passwords do not match."); return; }
        const strongPassword = newPassword.length >= 12 && newPassword.length <= 64
            && /[A-Z]/.test(newPassword) && /[a-z]/.test(newPassword) && /\d/.test(newPassword)
            && /[^A-Za-z0-9]/.test(newPassword) && !/\s/.test(newPassword);
        if (!strongPassword) {
            setError("Password must be 12-64 characters with uppercase, lowercase, number and special character, without spaces.");
            return;
        }
        setBusy(true);
        try {
            if (isBackendConfigured) {
                await brainServeApi.confirmPasswordChange(otp, newPassword);
            } else {
                if (!previewOtp || otp !== previewOtp) fail("The preview verification code is incorrect.");
                const passwordHash = await hashDemoPassword(newPassword);
                writeDemoAccounts(readDemoAccounts().map((item) => item.email === email.toLowerCase()
                    ? { ...item, passwordHash, forcePasswordChange: false } : item));
            }
            onComplete();
        } catch (reason) {
            setError(reason instanceof Error ? reason.message : "The password could not be changed.");
        } finally { setBusy(false); }
    };
    return <main className="login-page"><div className="ambient ambient-one" /><section className="login-brand">
        <Logo productName="BrainServe Connect" /><div><span className="eyebrow">First-login protection</span>
        <h1>Secure your account.</h1><p>Replace the temporary password before opening the workplace.</p></div>
        <div className="login-trust"><ShieldCheck size={20} /><span><strong>Mandatory password change</strong>
      <small>OTP verified · Existing sessions revoked</small></span></div></section>
        <section className="login-card glass-panel"><div className="login-card-head"><span className="avatar large"><LockKeyhole size={22} /></span>
            <div><small>BRAINSERVE CONNECT</small><h2>Choose your password</h2><p>{email}</p></div></div>
            {step === "request" ? <form onSubmit={requestOtp}><label>Temporary password<input type="password"
                                                                                              value={currentPassword} onChange={(event) => setCurrentPassword(event.target.value)} minLength={8}
                                                                                              autoComplete="current-password" required /></label><button className="button button-primary button-large full-button"
                                                                                                                                                         disabled={busy}>{busy ? "Sending…" : "Send verification code"}</button></form>
                : <form onSubmit={confirm}><label>Six-digit code<input inputMode="numeric" pattern="[0-9]{6}" maxLength={6}
                                                                       value={otp} onChange={(event) => setOtp(event.target.value.replace(/\D/g, ""))} required /></label>
                    {!isBackendConfigured && previewOtp && <div className="success-banner" role="status">
                        <ShieldCheck size={17} /> Preview verification code: <strong>{previewOtp}</strong>
                    </div>}
                    <label>New password<input type="password" minLength={12} maxLength={64} value={newPassword}
                                              onChange={(event) => setNewPassword(event.target.value)} autoComplete="new-password" required /></label>
                    <label>Confirm new password<input type="password" minLength={12} maxLength={64} value={confirmPassword}
                                                      onChange={(event) => setConfirmPassword(event.target.value)} autoComplete="new-password" required /></label>
                    <button className="button button-primary button-large full-button" disabled={busy || otp.length !== 6}>
                        {busy ? "Changing…" : "Change password"}</button></form>}
            {error && <div className="login-error" role="alert">{error}</div>}
            <button className="text-button back-home" onClick={onLogout}>Sign out</button></section></main>;
}

