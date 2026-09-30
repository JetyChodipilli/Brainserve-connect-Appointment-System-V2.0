"use client";

import { ApiError, type StaffAccount } from "../../../lib/api";
import { UserCog } from "lucide-react";
import { useState } from "react";

export function StaffAccountRow({ account, onChangeEmail, onResetPassword, onSetEnabled }: {
    account: StaffAccount;
    onChangeEmail: (userId: string, email: string) => Promise<void>;
    onResetPassword: (userId: string, password: string) => Promise<void>;
    onSetEnabled: (userId: string, enabled: boolean) => Promise<void>;
}) {
    const [email, setEmail] = useState(account.email);
    const [password, setPassword] = useState("");
    const [message, setMessage] = useState("");
    const [busy, setBusy] = useState(false);
    const pending = account.status === "PENDING_APPROVAL" || account.status === "PENDING_HR_APPROVAL";
    const perform = async (action: () => Promise<void>, success: string) => {
        setBusy(true); setMessage("");
        try { await action(); setMessage(success); } catch (reason) { setMessage(reason instanceof ApiError ? reason.message : "Update failed."); }
        finally { setBusy(false); }
    };
    const needsDepartment = account.enabled && account.roles.length === 1 && account.roles[0] === "ROLE_EMPLOYEE" && !account.employeeId;
    return <div className="staff-account-row"><div className="staff-account-head"><span className="role-icon"><UserCog size={18} /></span><span><strong>{account.roles.map((item) => item.replace("ROLE_", "").replaceAll("_", " ")).join(", ")}</strong><small>{account.status.replaceAll("_", " ")}{account.forcePasswordChange ? " · password change required" : ""}{needsDepartment ? " · department assignment required" : ""}</small></span><span className={`status-pill ${pending || needsDepartment ? "status-pending" : account.enabled ? "status-active" : "status-on-leave"}`}><span />{pending ? "Pending approval" : needsDepartment ? "Needs department" : account.enabled ? "Active" : "Disabled"}</span></div><div className="staff-account-controls"><label>Login email<input type="email" value={email} onChange={(event) => setEmail(event.target.value)} /></label><button className="button button-secondary" disabled={busy || email === account.email} onClick={() => void perform(() => onChangeEmail(account.userId, email), "Email updated.")}>Save email</button><label>New temporary password<input type="password" minLength={12} value={password} onChange={(event) => setPassword(event.target.value)} placeholder="Minimum 12 characters" /></label><button className="button button-secondary" disabled={busy || password.length < 12} onClick={() => void perform(() => onResetPassword(account.userId, password), "Password reset.")}>Reset password</button>{!pending && <button className={account.enabled ? "button button-reject" : "button button-approve"} disabled={busy} onClick={() => void perform(() => onSetEnabled(account.userId, !account.enabled), account.enabled ? "Account disabled." : "Account enabled.")}>{account.enabled ? "Disable" : "Enable"}</button>}</div>{pending && <small className="account-message">Use the HR approval queue to activate this account.</small>}{needsDepartment && <small className="account-message">Open Employees and assign this approved login to a department.</small>}{message && <small className="account-message">{message}</small>}</div>;
}

