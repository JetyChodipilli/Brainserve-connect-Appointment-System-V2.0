"use client";

import { brainServeApi, isBackendConfigured, setAccessToken, setAuthTokens } from "../../lib/api";
import { hashDemoPassword, readDemoAccounts } from "../../preview/accounts";
import { BROWSER_PREVIEW_ROLE_ORDER } from "../../preview/fixtures/accounts";
import { resetBrowserPreviewWorkspace, startBrowserPreviewRole } from "../../preview/session";
import { Logo } from "../../shared/components/logo";
import { primaryRoleFromAuthorities, roleBadge, roleFromAuthority } from "../../shared/config/roles";
import { type Role, type Screen } from "../../shared/types/workspace";
import { fail } from "../../shared/utils/errors";
import { ArrowLeft, ArrowRight, LockKeyhole, RotateCcw, ShieldCheck, UserPlus } from "lucide-react";
import { type FormEvent, useState } from "react";

export function Login({ onLogin, onNavigate, sessionMessage = "", browserPreviewEnabled = false }: {
    onLogin: (role: Role, email: string, forcePasswordChange: boolean, currentPassword: string) => void;
    onNavigate: (screen: Screen) => void;
    sessionMessage?: string;
    browserPreviewEnabled?: boolean;
}) {
    const [loading, setLoading] = useState(false);
    const [error, setError] = useState("");

    const submit = async (event: FormEvent<HTMLFormElement>) => {
        event.preventDefault();
        const data = new FormData(event.currentTarget);
        if (!isBackendConfigured) {
            const email = String(data.get("email")).trim().toLowerCase();
            const password = String(data.get("password"));
            const passwordHash = await hashDemoPassword(password);
            const account = readDemoAccounts().find((item) => item.email === email
                && item.passwordHash === passwordHash && item.status === "ACTIVE");
            const accountRole = account ? roleFromAuthority(account.role) : null;
            if (account && accountRole === "Employee" && !account.employeeId) {
                setError("Your Employee login is approved, but HR must assign your department and employee ID before you can sign in.");
            } else if (account && accountRole) {
                onLogin(accountRole, account.email, Boolean(account.forcePasswordChange), password);
            }
            else setError("Invalid email or password, or the account is still pending approval.");
            return;
        }
        setLoading(true); setError("");
        try {
            const tokens = await brainServeApi.login(String(data.get("email")), String(data.get("password")));
            setAuthTokens(tokens.accessToken, tokens.refreshToken);
            const profile = await brainServeApi.me();
            const resolved = primaryRoleFromAuthorities(profile.roles);
            if (!resolved) {
                fail("This account must have exactly one supported BrainServe role. Ask System Admin to repair the account before signing in.");
            }
            if (resolved === "Employee" && !profile.employeeId) {
                fail("Your Employee login is approved, but HR must assign your department and employee ID before you can sign in.");
            }
            onLogin(resolved, profile.email, tokens.forcePasswordChange || profile.forcePasswordChange,
                String(data.get("password")));
        } catch (reason) {
            setAccessToken(null);
            setError(reason instanceof Error ? reason.message : "The BrainServe service is temporarily unavailable.");
        } finally { setLoading(false); }
    };

    return <main className="login-page"><div className="ambient ambient-one" /><section className="login-brand"><Logo /><div><span className="eyebrow">Secure workplace access</span><h1>Every visit.<br />One clear view.</h1><p>Coordinate appointments, employees and workplace access without compromising privacy.</p></div><div className="login-trust"><ShieldCheck size={20} /><span><strong>Enterprise protected</strong><small>Role-based access · Complete audit trail</small></span></div></section><section className="login-card glass-panel"><div className="login-card-head"><span className="avatar large">BS</span><div><small>INTERNAL PORTAL</small><h2>Welcome back</h2><p>Use your approved BrainServe Connect login email.</p></div></div>{browserPreviewEnabled && <section className="browser-preview-panel" aria-label="Browser preview roles"><div><span><ShieldCheck size={16} /> BROWSER PREVIEW</span><strong>Open a role workspace</strong><small>Uses isolated data in this browser. Real authentication starts automatically after the backend is connected.</small></div><div className="browser-preview-role-grid">{BROWSER_PREVIEW_ROLE_ORDER.map((previewRole) => <button type="button" key={previewRole} onClick={() => { const account = startBrowserPreviewRole(previewRole); onLogin(previewRole, account.email, false, ""); }}><span>{roleBadge(previewRole)}</span>{previewRole}</button>)}</div><button type="button" className="text-button browser-preview-reset" onClick={resetBrowserPreviewWorkspace}><RotateCcw size={14} /> Reset browser test data</button></section>}{sessionMessage && <div className="info-banner" role="status"><ShieldCheck size={17} /><span><strong>Session ended securely</strong><small>{sessionMessage}</small></span></div>}<form onSubmit={submit}><label>Login email<input name="email" type="email" placeholder="name@brainserve.in or System Admin email" autoComplete="username" required /></label><label>Password<div className="password-field"><input name="password" type="password" placeholder="Your password" autoComplete="current-password" minLength={8} required /><LockKeyhole size={17} /></div></label><div className="login-recovery-links"><button type="button" className="text-button" onClick={() => onNavigate("forgot-password")}>Forgot password?</button><button type="button" className="text-button" onClick={() => onNavigate("forgot-email")}>Forgot company email?</button></div>{error && <div className="login-error" role="alert">{error}</div>}<button className="button button-primary button-large full-button" disabled={loading}>{loading ? "Signing in…" : "Sign in securely"} {!loading && <ArrowRight size={18} />}</button></form><div className="login-divider"><span>Protected by BrainServe Connect IAM</span></div><button className="button button-secondary full-button" onClick={() => onNavigate("register")}><UserPlus size={17} /> Create an account</button><button className="text-button back-home" onClick={() => onNavigate("welcome")}><ArrowLeft size={16} /> Return to visitor portal</button></section></main>;
}

