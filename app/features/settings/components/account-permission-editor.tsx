"use client";

import { type RoleDefinition, type StaffAccount } from "../../../lib/api";
import { ShieldCheck, UserCog } from "lucide-react";
import { useState } from "react";

export function AccountPermissionEditor({ accounts, roles, onUpdate }: { accounts: StaffAccount[]; roles: RoleDefinition[]; onUpdate: (userId: string, grants: string[], denies: string[]) => Promise<void> }) {
    const [selectedId, setSelectedId] = useState(accounts[0]?.userId ?? "");
    const [busy, setBusy] = useState("");
    const [error, setError] = useState("");
    const account = accounts.find((item) => item.userId === selectedId) ?? accounts[0];
    const lowerRoles = roles.filter((item) => ["ROLE_TEAM_LEAD", "ROLE_EMPLOYEE", "ROLE_RECEPTIONIST", "ROLE_SECURITY"].includes(item.role));
    const manageable = [...new Set(lowerRoles.flatMap((item) => item.defaultPermissions))].sort();
    const defaults = new Set(roles.find((item) => item.role === account?.roles[0])?.defaultPermissions ?? []);
    const toggle = async (permission: string, enabled: boolean) => {
        if (!account) return;
        const grants = new Set(account.grantedPermissions); const denies = new Set(account.deniedPermissions);
        if (enabled) { denies.delete(permission); if (!defaults.has(permission)) grants.add(permission); }
        else { grants.delete(permission); if (defaults.has(permission)) denies.add(permission); }
        setBusy(permission); setError("");
        try { await onUpdate(account.userId, [...grants], [...denies]); }
        catch (reason) { setError(reason instanceof Error ? reason.message : "Permission update failed."); }
        finally { setBusy(""); }
    };
    return <article className="panel glass-panel"><div className="panel-heading"><div><span>INDIVIDUAL OVERRIDES</span><h2>HR-controlled key permissions</h2><p>Approve lower-role accounts in the HR queue, then grant or deny only operational permissions within HR scope.</p></div><ShieldCheck size={22} /></div>{accounts.length ? <><label className="account-select">Managed account<select value={account?.userId ?? ""} onChange={(event) => setSelectedId(event.target.value)}>{accounts.map((item) => <option value={item.userId} key={item.userId}>{item.fullName} · {item.roles[0].replace("ROLE_", "")}</option>)}</select></label><div className="permission-check-grid">{manageable.map((permission) => <label key={permission}><input type="checkbox" checked={account?.effectivePermissions.includes(permission) ?? false} disabled={Boolean(busy)} onChange={(event) => void toggle(permission, event.target.checked)} /><span><strong>{permission.replaceAll("_", " ")}</strong><small>{defaults.has(permission) ? "Role default" : "Optional grant"}</small></span></label>)}</div>{error && <div className="login-error" role="alert">{error}</div>}</> : <div className="empty-state"><UserCog size={28} /><strong>No HR-managed accounts</strong><small>Employee, Receptionist and Security accounts appear here after creation.</small></div>}</article>;
}

