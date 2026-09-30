"use client";

import { brainServeApi, isBackendConfigured } from "../../../services/brainserve-api";
import { fail } from "../../../utils/errors";
import { Bell, CheckCircle2, ShieldCheck } from "lucide-react";
import { type FormEvent, useState } from "react";

export function PasswordChangeCard() {
    const [otpRequested, setOtpRequested] = useState(false);
    const [busy, setBusy] = useState(false);
    const [message, setMessage] = useState("");
    const [error, setError] = useState("");

    const requestOtp = async (event: FormEvent<HTMLFormElement>) => {
        event.preventDefault(); setBusy(true); setError(""); setMessage("");
        const data = new FormData(event.currentTarget);
        try {
            if (!isBackendConfigured) fail("Connect the Spring backend and SMTP service to change passwords by email OTP.");
            await brainServeApi.requestPasswordChangeOtp(String(data.get("currentPassword")));
            setOtpRequested(true);
            setMessage("A six-digit OTP was sent to your login email and expires in 10 minutes.");
            event.currentTarget.reset();
        } catch (reason) { setError(reason instanceof Error ? reason.message : "The OTP could not be sent."); }
        finally { setBusy(false); }
    };

    const confirm = async (event: FormEvent<HTMLFormElement>) => {
        event.preventDefault(); setBusy(true); setError(""); setMessage("");
        const form = event.currentTarget;
        const data = new FormData(form);
        const newPassword = String(data.get("newPassword"));
        if (newPassword !== String(data.get("confirmPassword"))) {
            setError("The new password and confirmation do not match.");
            setBusy(false);
            return;
        }
        try {
            if (!isBackendConfigured) fail("Connect the Spring backend to confirm password changes.");
            await brainServeApi.confirmPasswordChange(String(data.get("otp")), newPassword);
            form.reset(); setOtpRequested(false);
            setMessage("Password changed successfully. Other signed-in devices have been logged out.");
        } catch (reason) { setError(reason instanceof Error ? reason.message : "The password could not be changed."); }
        finally { setBusy(false); }
    };

    return <article className="panel glass-panel"><div className="panel-heading"><div><span>OPTIONAL PASSWORD CHANGE</span><h2>Email OTP confirmation</h2><p>Your current password remains valid until you request an OTP and confirm a new password.</p></div><ShieldCheck size={22} /></div>{!otpRequested ? <form className="inline-account-form" onSubmit={requestOtp}><label>Current password<input name="currentPassword" type="password" maxLength={128} autoComplete="current-password" required /></label><button className="button button-secondary" disabled={busy}><Bell size={16} /> {busy ? "Sending…" : "Email me an OTP"}</button></form> : <form className="staff-create-form" onSubmit={confirm}><label>Six-digit OTP<input name="otp" inputMode="numeric" pattern="[0-9]{6}" maxLength={6} placeholder="000000" required /></label><label>New password<input name="newPassword" type="password" minLength={12} maxLength={64} autoComplete="new-password" required /></label><label>Confirm password<input name="confirmPassword" type="password" minLength={12} maxLength={64} autoComplete="new-password" required /></label><button className="button button-primary" disabled={busy}><ShieldCheck size={16} /> {busy ? "Confirming…" : "Confirm password change"}</button></form>}<div className="password-policy">12-64 characters · uppercase · lowercase · number · special character · no spaces</div>{message && <div className="success-banner"><CheckCircle2 size={17} /> {message}</div>}{error && <div className="login-error" role="alert">{error}</div>}</article>;
}

