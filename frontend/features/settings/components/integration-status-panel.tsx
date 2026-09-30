"use client";

import { brainServeApi, type IntegrationOverview, isBackendConfigured } from "../../../services/brainserve-api";
import { LockKeyhole, RotateCcw, ShieldCheck } from "lucide-react";
import { useCallback, useEffect, useState } from "react";

export function IntegrationStatusPanel() {
    const [overview, setOverview] = useState<IntegrationOverview | null>(null);
    const [busy, setBusy] = useState(false);
    const [error, setError] = useState("");
    const load = useCallback(async () => {
        if (!isBackendConfigured) return;
        setBusy(true); setError("");
        try { setOverview(await brainServeApi.integrationHealth()); }
        catch (reason) {
            setError(reason instanceof Error ? reason.message : "Integration health could not be loaded.");
        } finally { setBusy(false); }
    }, []);
    useEffect(() => { const timer = window.setTimeout(() => void load(), 0);
        return () => window.clearTimeout(timer); }, [load]);
    return <article className="panel glass-panel integration-status-panel">
        <div className="panel-heading"><div><span>FULL-STACK READINESS</span><h2>Connected service health</h2>
            <p>Live checks for PostgreSQL, Redis, internal delivery, SMTP, private object storage and malware scanning.</p></div>
            <button type="button" className="button button-secondary" disabled={busy || !isBackendConfigured}
                    onClick={() => void load()}><RotateCcw size={15} />{busy ? "Checking…" : "Check services"}</button></div>
        {!isBackendConfigured && <div className="governance-connection-note"><LockKeyhole size={18} /><span>
      <strong>Backend URL not configured</strong><small>Set NEXT_PUBLIC_API_BASE_URL to activate the live service checks.</small></span></div>}
        {overview && <><div className={`integration-overall status-${overview.status.toLowerCase()}`}>
            <ShieldCheck size={18} /><span><strong>{overview.status === "READY" ? "All required services are ready" : "One or more services require attention"}</strong>
        <small>Checked {new Date(overview.checkedAt).toLocaleString("en-IN")}</small></span></div>
            <div className="integration-service-grid">{overview.services.map((service) => <div key={service.name}
                                                                                               className={service.ready ? "ready" : "degraded"}><span><i /><strong>{service.name}</strong></span>
                <small>{service.purpose}</small><small>{service.detail} · {service.latencyMs} ms</small></div>)}</div></>}
        {error && <div className="login-error" role="alert">{error}</div>}
    </article>;
}

