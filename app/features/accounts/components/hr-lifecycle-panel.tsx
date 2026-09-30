"use client";

import { brainServeApi, type HrLifecycleAccount, isBackendConfigured } from "../../../lib/api";
import { UserCog } from "lucide-react";
import { useEffect, useState } from "react";

export function HrLifecyclePanel() {
    const [accounts, setAccounts] = useState<HrLifecycleAccount[]>([]); const [error, setError] = useState("");
    useEffect(() => { if (!isBackendConfigured) return; let active = true;
        brainServeApi.hrLifecycleAccounts().then((items) => { if (active) setAccounts(items); })
            .catch((reason) => { if (active) setError(reason instanceof Error ? reason.message : "HR accounts could not be loaded."); });
        return () => { active = false; }; }, []);
    return <article className="panel glass-panel"><div className="panel-heading"><div><span>HR IDENTITY DIRECTORY</span><h2>HR account status</h2><p>Direct deactivation is retired. HR closes their account through My profile, CEO completes business review, and System Admin performs Deactivate &amp; archive in Account lifecycle.</p></div><UserCog size={22} /></div><div className="record-list">{accounts.map((account) => <div key={account.userId}><span><strong>{account.fullName}</strong><small>{account.email}</small></span><code>{account.status.replaceAll("_", " ")}</code><span className={account.enabled ? "closure-status closure-active" : "closure-status closure-rejected"}>{account.enabled ? "ACTIVE" : "INACTIVE"}</span></div>)}{accounts.length === 0 && <div className="empty-state"><UserCog size={28} /><strong>No HR accounts found</strong></div>}</div>{error && <div className="login-error" role="alert">{error}</div>}</article>;
}

