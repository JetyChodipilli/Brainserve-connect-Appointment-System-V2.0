"use client";

import { brainServeApi, isBackendConfigured } from "../../lib/api";
import { hashDemoPassword, readDemoAccounts, writeDemoAccounts } from "../../preview/accounts";
import { type DemoProvisioningAccount } from "../../preview/types";
import { Logo } from "../../shared/components/logo";
import { SYSTEM_ADMIN_EMAIL } from "../../shared/config/identity";
import { type Screen } from "../../shared/types/workspace";
import { fail } from "../../shared/utils/errors";
import { newClientId } from "../../shared/utils/ids";
import { ArrowLeft, ArrowRight, BadgeCheck, CheckCircle2, LockKeyhole, ShieldCheck } from "lucide-react";
import { type FormEvent, useState } from "react";

export function AccountRegistration({ onNavigate }: { onNavigate: (screen: Screen) => void }) {
    const [busy, setBusy] = useState(false);
    const [error, setError] = useState("");
    const [success, setSuccess] = useState("");

    const submit = async (event: FormEvent<HTMLFormElement>) => {
        event.preventDefault(); setBusy(true); setError(""); setSuccess("");
        const form = event.currentTarget;
        const data = new FormData(form);
        const fullName = String(data.get("fullName")).trim();
        const email = String(data.get("email")).trim().toLowerCase();
        const requestedRole = String(data.get("role"));
        const password = String(data.get("password"));
        if (password !== String(data.get("confirmPassword"))) {
            setError("Password and confirmation do not match."); setBusy(false); return;
        }
        const strongPassword = /[A-Z]/.test(password) && /[a-z]/.test(password) && /\d/.test(password)
            && /[^A-Za-z0-9]/.test(password) && !/\s/.test(password);
        if (!strongPassword) {
            setError("Password must include uppercase, lowercase, number and special characters without spaces.");
            setBusy(false); return;
        }
        try {
            let result: { message: string };
            if (isBackendConfigured) {
                result = await brainServeApi.registerAccount(fullName, email, password, requestedRole);
            } else {
                if (requestedRole === "ROLE_CEO") {
                    fail("CEO is the single company authority and can be created only by System Admin.");
                }
                if (!email.endsWith("@brainserve.in")) fail("Use an official @brainserve.in email address.");
                const accounts = readDemoAccounts();
                if (accounts.some((item) => item.email === email) || email === SYSTEM_ADMIN_EMAIL) {
                    fail("An account already uses this email address.");
                }
                const lowerRole = ["ROLE_EMPLOYEE", "ROLE_RECEPTIONIST", "ROLE_SECURITY"].includes(requestedRole);
                const pending: DemoProvisioningAccount = {
                    id: newClientId(), fullName, email, role: requestedRole,
                    status: lowerRole ? "PENDING_HR_APPROVAL" : "PENDING_APPROVAL",
                    createdByUserId: null, approvedByUserId: null, createdAt: new Date().toISOString(), approvedAt: null,
                    passwordHash: await hashDemoPassword(password),
                };
                writeDemoAccounts([...accounts, pending]);
                result = { message: ["ROLE_HR_ADMIN", "ROLE_MANAGER"].includes(requestedRole)
                        ? "Registration submitted to the company CEO for approval"
                        : "Registration submitted for HR Admin approval" };
            }
            setSuccess(result.message + ". You can sign in after an authorized approver activates your account.");
            form.reset();
        } catch (reason) { setError(reason instanceof Error ? reason.message : "Registration could not be submitted."); }
        finally { setBusy(false); }
    };

    return <main className="login-page"><div className="ambient ambient-one" /><section className="login-brand"><Logo /><div><span className="eyebrow">Staff registration</span><h1>Request secure<br />workplace access.</h1><p>HR Admin and Manager requests go to the single company CEO. Employee, Receptionist and Security requests go to the assigned HR Admin.</p></div><div className="login-trust"><ShieldCheck size={20} /><span><strong>Role-based activation</strong><small>No pending account can sign in</small></span></div></section><section className="login-card glass-panel"><div className="login-card-head"><span className="avatar large">BS</span><div><small>NEW ACCOUNT REQUEST</small><h2>Register with BrainServe Connect</h2><p>Use your official company email.</p></div></div><div className="team-lead-registration-note"><BadgeCheck size={17} /><span><strong>CEO and Team Lead are governed roles</strong><small>System Admin creates the single CEO. Employees become Team Leads only through an audited role transition in their department. Receptionist and Security accounts are never eligible.</small></span></div><form onSubmit={submit}><label>Full name<input name="fullName" minLength={2} maxLength={170} required /></label><label>Company email<input name="email" type="email" placeholder="name@brainserve.in" required /></label><label>Requested role<select name="role"><option value="ROLE_MANAGER">Manager</option><option value="ROLE_HR_ADMIN">HR Admin</option><option value="ROLE_EMPLOYEE">Employee</option><option value="ROLE_RECEPTIONIST">Receptionist</option><option value="ROLE_SECURITY">Security</option></select></label><label>Password<div className="password-field"><input name="password" type="password" minLength={12} maxLength={64} autoComplete="new-password" required /><LockKeyhole size={17} /></div></label><label>Confirm password<div className="password-field"><input name="confirmPassword" type="password" minLength={12} maxLength={64} autoComplete="new-password" required /><LockKeyhole size={17} /></div></label><div className="password-policy">12-64 characters · uppercase · lowercase · number · special character · no spaces</div>{success && <div className="success-banner"><CheckCircle2 size={17} /> {success}</div>}{error && <div className="login-error" role="alert">{error}</div>}<button className="button button-primary button-large full-button" disabled={busy}>{busy ? "Submitting…" : "Submit account request"}<ArrowRight size={18} /></button></form><button type="button" className="text-button back-home" onClick={() => onNavigate("login")}><ArrowLeft size={16} /> Back to sign in</button></section></main>;
}

