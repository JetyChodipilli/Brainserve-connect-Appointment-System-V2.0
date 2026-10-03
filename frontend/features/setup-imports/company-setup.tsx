"use client";
import { useEffect, useEffectEvent, useRef, useState } from "react";
import { CheckCircle2, Circle, RefreshCw, ArrowRight } from "lucide-react";
import { ApiError, isBackendConfigured } from "../../lib/api-client";
import type { Role, SettingsSection } from "../../types/workspace";
import type { SetupState } from "./types";
import { setupImportsApi } from "./api/setup-imports-api";
import { validateSetupState } from "./import-model";
import { useOperationScope, useSessionRevision } from "./use-operation-scope";
import styles from "./setup-imports.module.css";

const guidance: Record<string, string> = {
    company: "Use Company profile to enter the actual company name, official email domain, address and support email.",
    departments: "Create real departments with the department CSV template. Ask the CEO to assign each department’s HR Admin and Manager through Organization and the approval ledger.",
    roles: "Use Identity & access to provision the single CEO through the governed approval flow. HR Admin and Manager invitations and department ownership remain subject to CEO approval.",
    policy: "Review appointment rules in Appointment policy. The office timezone below is the running deployment’s timezone; changing a stored preference does not change the running service.",
    notifications: "Review transactional notification settings and verify the configured delivery service. Do not use demo recipients in a production deployment.",
    privacy: "Review the current consent version and governed retention policy. Existing data lifecycle safeguards remain in force.",
    review: "This checklist uses current configuration and active leadership. A previous completion is rechecked after configuration changes.",
};
const sectionByStep: Record<string, SettingsSection> = { company: "company", departments: "imports", roles: "identity", policy: "policy", notifications: "notifications", privacy: "privacy" };

export function CompanySetup({ role, userEmail, onConfigure }: { role: Role; userEmail: string; onConfigure: (section: SettingsSection) => void }) {
    const revision = useSessionRevision();
    if (role !== "System Admin") return null;
    return <SetupPanel key={`${role}:${userEmail}:${revision}`} onConfigure={onConfigure} />;
}

function SetupPanel({ onConfigure }: { onConfigure: (section: SettingsSection) => void }) {
    const [state, setState] = useState<SetupState | null>(null), [busy, setBusy] = useState(isBackendConfigured), [error, setError] = useState("");
    const [message, setMessage] = useState("");
    const errorSummary = useRef<HTMLDivElement>(null);
    const scope = useOperationScope();
    const fetchSetup = () => {
        if (!isBackendConfigured) return Promise.resolve();
        const request = scope.request();
        return setupImportsApi.setup(request.signal)
            .then((result) => { const next = validateSetupState(result); if (request.current()) setState(next); })
            .catch((reason: unknown) => { if (request.current()) { setState(null); setError(reason instanceof Error ? reason.message : "Company setup could not be loaded."); } })
            .finally(() => { if (request.current()) setBusy(false); request.finish(); });
    };
    const initialize = useEffectEvent(fetchSetup);
    useEffect(() => { void initialize(); }, []);
    const load = async () => { setBusy(true); setError(""); await fetchSetup(); };
    useEffect(() => { if (error) errorSummary.current?.focus(); }, [error]);
    const update = async (stepId?: string) => {
        if (!state || busy) return;
        const request = scope.request(); setBusy(true); setError(""); setMessage("");
        try {
            const next = validateSetupState(stepId ? await setupImportsApi.progress(state.revision, stepId, request.signal)
                : await setupImportsApi.complete(state.revision, request.signal));
            if (request.current()) { setState(next); setMessage(stepId ? "Your place in setup is saved. Configuration readiness is checked by the service." : "Company setup completed using current configuration."); }
        } catch (reason) {
            if (request.current()) {
                setState(null);
                setError(reason instanceof ApiError && reason.status === 409 ? "Setup changed in another session. Refresh the checklist, then review the current settings and try again."
                    : reason instanceof Error ? reason.message : "Setup could not be saved.");
            }
        } finally { if (request.current()) setBusy(false); request.finish(); }
    };
    const step = state?.steps.find((item) => item.id === state.currentStep);
    const prerequisites = state?.steps.filter((item) => item.id !== "review") ?? [];
    return <section className={styles.feature} aria-label="Company setup"><article className={styles.panel}>
        <header className={styles.heading}><div><span className={styles.eyebrow}>COMPANY READINESS</span><h2>Set up your workspace</h2><p>Resume where you left off. Completion follows verified settings and active leadership.</p></div>
            <button className={styles.button} type="button" onClick={() => void load()} disabled={busy || !isBackendConfigured}><RefreshCw size={16} aria-hidden="true" />Refresh checklist</button></header>
        {!isBackendConfigured && <p className={styles.notice} role="status">Demo preview only. Setup readiness and completion require the secure backend. Synthetic demo accounts are never added to your company.</p>}
        {busy && <p role="status">Loading and checking company setup…</p>}
        {error && <div className={styles.error} role="alert" ref={errorSummary} tabIndex={-1}><h3>Setup needs attention</h3><p>{error}</p><button className={styles.button} type="button" onClick={() => void load()} disabled={busy}>Reload current setup</button></div>}
        {state && <><p className={styles.metadata}>Policy {state.policyVersion} · Revision {state.revision} · Runtime office timezone: <strong>{state.officeZone}</strong></p>
            <p role="status">{state.status === "COMPLETE" ? "Currently ready" : "Setup in progress"} · {prerequisites.filter((item) => item.complete).length} of {prerequisites.length} prerequisites ready</p>
            <div className={styles.wizard}><nav aria-label="Company setup steps"><ol className={styles.steps}>{state.steps.map((item, index) => <li key={item.id}><button type="button" aria-current={item.id === state.currentStep ? "step" : undefined} disabled={busy} onClick={() => void update(item.id)}>
                {item.complete ? <CheckCircle2 size={18} aria-hidden="true" /> : <Circle size={18} aria-hidden="true" />}<span>{index + 1}. {item.title}<small>{item.complete ? "Ready" : "Needs attention"}</small></span></button></li>)}</ol></nav>
                {step && <div className={styles.stepContent}><h3>{step.title}</h3><p>{guidance[step.id]}</p>
                    {step.id === "review" ? <ul className={styles.checklist}>{prerequisites.map((item) => <li key={item.id}><strong>{item.title}: {item.complete ? "Ready" : "Blocked"}</strong>{item.issues.length > 0 && <ul>{item.issues.map((issue) => <li key={issue}>{issue}</li>)}</ul>}</li>)}</ul>
                        : <><p><strong>{step.complete ? "This prerequisite is ready." : "Resolve these prerequisites to continue:"}</strong></p><ul className={styles.checklist}>{step.issues.map((issue) => <li key={issue}>{issue}</li>)}</ul>
                            <button className={styles.button} type="button" onClick={() => onConfigure(sectionByStep[step.id])}>Open {step.id === "departments" ? "department imports" : step.id === "roles" ? "Identity & access" : step.title}<ArrowRight size={16} aria-hidden="true" /></button></>}
                    <div className={styles.actions}>{step.id !== "review" && <button className={styles.button} type="button" disabled={busy} onClick={() => void update(state.steps[Math.min(state.steps.findIndex((item) => item.id === step.id) + 1, state.steps.length - 1)].id)}>Save place &amp; continue</button>}
                        {step.id === "review" && <button className={`${styles.button} ${styles.primary}`} type="button" disabled={busy || !prerequisites.every((item) => item.complete) || state.status === "COMPLETE"} onClick={() => void update()}>Complete company setup</button>}</div>
                </div>}</div></>}
        {message && <p role="status">{message}</p>}
    </article></section>;
}
