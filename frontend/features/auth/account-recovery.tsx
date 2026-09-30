"use client";

import { brainServeApi, isBackendConfigured } from "../../services/brainserve-api";
import { hashDemoPassword, readDemoAccounts, writeDemoAccounts } from "../../preview/accounts";
import { readDemoRecoveryRequests, writeDemoRecoveryRequests } from "../../preview/recovery";
import { Logo } from "../../components/shared/logo";
import { type Screen } from "../../types/workspace";
import { fail } from "../../utils/errors";
import { newClientId } from "../../utils/ids";
import { ArrowLeft, ArrowRight, CheckCircle2, Fingerprint, LockKeyhole, ShieldCheck, UserCog } from "lucide-react";
import { type FormEvent, useState } from "react";

export function AccountRecovery({ type, onNavigate }: {
    type: "PASSWORD" | "EMAIL";
    onNavigate: (screen: Screen) => void;
}) {
    const [busy, setBusy] = useState("");
    const [requestMessage, setRequestMessage] = useState("");
    const [success, setSuccess] = useState("");
    const [error, setError] = useState("");
    const isPassword = type === "PASSWORD";
    const title = isPassword ? "Reset your password" : "Recover your company email";

    const requestRecovery = async (event: FormEvent<HTMLFormElement>) => {
        event.preventDefault();
        if (busy) return;

        const form = event.currentTarget;
        const data = new FormData(form);
        const identifier = String(data.get("identifier") ?? "").trim();
        const accountRole = String(data.get("role") ?? "").trim();

        setError("");
        setSuccess("");
        setRequestMessage("");

        if (!identifier) {
            setError("Enter your company email or exact full name.");
            return;
        }
        if (!accountRole) {
            setError("Select your account role.");
            return;
        }

        setBusy("request");
        try {
            if (isBackendConfigured) {
                const result = await brainServeApi.requestAccountRecovery(identifier, accountRole, type);
                setRequestMessage(result.message);
            } else {
                const accounts = readDemoAccounts();
                const normalizedIdentifier = identifier.toLowerCase();
                const target = identifier.includes("@")
                    ? accounts.find((account) => account.status === "ACTIVE"
                        && account.role !== "ROLE_SYSTEM_ADMIN"
                        && account.email.toLowerCase() === normalizedIdentifier)
                    : accounts.find((account) => account.status === "ACTIVE" && account.role === accountRole
                        && account.fullName.toLowerCase() === normalizedIdentifier);
                if (target) {
                    const requests = readDemoRecoveryRequests();
                    const alreadyPending = requests.some((item) => item.userId === target.id && item.type === type
                        && item.status === "PENDING");
                    if (!alreadyPending) {
                        writeDemoRecoveryRequests([...requests, {
                            id: newClientId(), userId: target.id, fullName: target.fullName, email: target.email,
                            role: target.role, type, status: "PENDING", requestedAt: new Date().toISOString(),
                            approvedAt: null, expiresAt: null, recoveryCode: null,
                        }]);
                    }
                }
                setRequestMessage("Preview request saved in this browser. Open System Admin in this same browser profile to review it. Connect Spring Boot for requests shared across devices.");
            }
            form.reset();
        } catch (reason) {
            setError(reason instanceof Error ? reason.message : "The recovery request could not be submitted.");
        } finally {
            setBusy("");
        }
    };

    const completeRecovery = async (event: FormEvent<HTMLFormElement>) => {
        event.preventDefault(); setBusy("recover"); setError(""); setSuccess("");
        const form = event.currentTarget;
        const data = new FormData(form);
        const code = String(data.get("code")).trim().toUpperCase();
        const primary = String(data.get(isPassword ? "newPassword" : "newEmail")).trim();
        const confirmation = String(data.get(isPassword ? "confirmPassword" : "confirmEmail")).trim();
        if (primary !== confirmation) {
            setError(`${isPassword ? "Password" : "Email"} and confirmation do not match.`);
            setBusy(""); return;
        }
        if (isPassword) {
            const strong = primary.length >= 12 && primary.length <= 64 && /[A-Z]/.test(primary)
                && /[a-z]/.test(primary) && /\d/.test(primary) && /[^A-Za-z0-9]/.test(primary) && !/\s/.test(primary);
            if (!strong) {
                setError("Password must be 12-64 characters with uppercase, lowercase, number and special character, without spaces.");
                setBusy(""); return;
            }
        }
        try {
            if (isBackendConfigured) {
                if (isPassword) await brainServeApi.recoverPassword(code, primary, confirmation);
                else await brainServeApi.recoverEmail(code, primary, confirmation);
            } else {
                const requests = readDemoRecoveryRequests();
                const request = requests.find((item) => item.type === type && item.status === "APPROVED"
                    && item.recoveryCode === code && item.expiresAt && new Date(item.expiresAt).getTime() > Date.now());
                if (!request) fail("Recovery code is invalid, expired or already used.");
                const accounts = readDemoAccounts();
                if (isPassword) {
                    const nextHash = await hashDemoPassword(primary);
                    if (accounts.some((account) => account.id === request.userId && account.passwordHash === nextHash)) {
                        fail("New password must differ from the current password.");
                    }
                    writeDemoAccounts(accounts.map((account) => account.id === request.userId
                        ? { ...account, passwordHash: nextHash } : account));
                } else {
                    const normalized = primary.toLowerCase();
                    if (!normalized.endsWith("@brainserve.in")) fail("Use an official @brainserve.in email address.");
                    if (accounts.some((account) => account.id !== request.userId && account.email === normalized)) {
                        fail("A login account already uses this email.");
                    }
                    writeDemoAccounts(accounts.map((account) => account.id === request.userId
                        ? { ...account, email: normalized } : account));
                }
                writeDemoRecoveryRequests(requests.map((item) => item.id === request.id
                    ? { ...item, status: "USED", recoveryCode: null } : item));
            }
            form.reset(); setRequestMessage("");
            setSuccess(`${isPassword ? "Password" : "Company email"} updated successfully. The recovery code is now invalid.`);
        } catch (reason) {
            setError(reason instanceof Error ? reason.message : "The recovery could not be completed.");
        } finally { setBusy(""); }
    };

    return <main className="login-page"><div className="ambient ambient-one" /><section className="login-brand"><Logo /><div><span className="eyebrow">System Admin approved recovery</span><h1>Regain access.<br />Securely.</h1><p>BrainServe Connect never retrieves an existing password. A one-time code must first be approved by the System Admin and expires after 30 minutes.</p></div><div className="login-trust"><ShieldCheck size={20} /><span><strong>One-time recovery</strong><small>Hashed code · Full audit trail · Sessions revoked</small></span></div></section><section className="login-card recovery-card glass-panel"><div className="login-card-head"><span className="avatar large"><Fingerprint size={22} /></span><div><small>ACCOUNT RECOVERY</small><h2>{title}</h2><p>Request approval, then use the code supplied by your System Admin.</p></div></div><div className="recovery-type-switch"><button type="button" className={isPassword ? "active" : ""} onClick={() => onNavigate("forgot-password")}>Password</button><button type="button" className={!isPassword ? "active" : ""} onClick={() => onNavigate("forgot-email")}>Company email</button></div><form onSubmit={requestRecovery} className="recovery-request-form"><strong>1. Request System Admin approval</strong><label>{isPassword ? "Company email or exact full name" : "Exact full name (or remembered company email)"}<input name="identifier" minLength={2} maxLength={255} autoComplete="off" required /></label><label>Account role<select name="role" defaultValue="ROLE_CEO"><option value="ROLE_CEO">CEO</option><option value="ROLE_HR_ADMIN">HR Admin</option><option value="ROLE_MANAGER">Manager</option><option value="ROLE_TEAM_LEAD">Team Lead</option><option value="ROLE_EMPLOYEE">Employee</option><option value="ROLE_RECEPTIONIST">Receptionist</option><option value="ROLE_SECURITY">Security</option></select></label><button type="submit" className="button button-secondary full-button" disabled={Boolean(busy)}><UserCog size={16} />{busy === "request" ? "Sending request…" : "Request approval"}</button></form>{requestMessage && <div className="success-banner"><CheckCircle2 size={17} />{requestMessage}</div>}<div className="login-divider"><span>After approval</span></div><form onSubmit={completeRecovery}><strong>2. Use your one-time code</strong><label>System Admin recovery code<input name="code" placeholder="BSR-XXXX-XXXX-XXXX" pattern="BSR-[A-Za-z2-9]{4}-[A-Za-z2-9]{4}-[A-Za-z2-9]{4}" autoComplete="one-time-code" required /></label>{isPassword ? <><label>New password<div className="password-field"><input name="newPassword" type="password" minLength={12} maxLength={64} autoComplete="new-password" required /><LockKeyhole size={17} /></div></label><label>Confirm new password<div className="password-field"><input name="confirmPassword" type="password" minLength={12} maxLength={64} autoComplete="new-password" required /><LockKeyhole size={17} /></div></label><div className="password-policy">12-64 characters · uppercase · lowercase · number · special character · no spaces</div></> : <><label>New company email<input name="newEmail" type="email" placeholder="name@brainserve.in" autoComplete="email" required /></label><label>Confirm company email<input name="confirmEmail" type="email" placeholder="name@brainserve.in" autoComplete="email" required /></label></>}<button type="submit" className="button button-primary button-large full-button" disabled={Boolean(busy)}>{busy === "recover" ? "Updating…" : isPassword ? "Set new password" : "Set company email"}<ArrowRight size={18} /></button></form>{success && <div className="success-banner"><CheckCircle2 size={17} />{success}</div>}{error && <div className="login-error" role="alert">{error}</div>}<button type="button" className="text-button back-home" onClick={() => onNavigate("login")}><ArrowLeft size={16} /> Back to sign in</button></section></main>;
}

