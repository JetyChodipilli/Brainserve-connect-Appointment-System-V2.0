"use client";

import {
    type AccountRecoveryRequest,
    ApiError,
    brainServeApi,
    isBackendConfigured,
    isWorkspaceUpdateLeader,
} from "../../../services/brainserve-api";
import { newDemoRecoveryCode, readDemoRecoveryRequests, writeDemoRecoveryRequests } from "../../../preview/recovery";
import { DEMO_RECOVERY_REQUESTS_KEY } from "../../../preview/storage-keys";
import { Check, CheckCircle2, FileText, Fingerprint, RotateCcw, ShieldCheck, X } from "lucide-react";
import { useCallback, useEffect, useState } from "react";

export function AccountRecoveryApprovalPanel({ generated, onGeneratedChange }: {
    generated: AccountRecoveryRequest | null;
    onGeneratedChange: (request: AccountRecoveryRequest | null) => void;
}) {
    const [requests, setRequests] = useState<AccountRecoveryRequest[]>(() =>
        isBackendConfigured ? [] : readDemoRecoveryRequests().filter((item) => item.status === "PENDING"));
    const [busyId, setBusyId] = useState("");
    const [copied, setCopied] = useState(false);
    const [message, setMessage] = useState("");
    const [error, setError] = useState("");

    const loadRequests = useCallback(async (showError = true) => {
        if (!isBackendConfigured) {
            setRequests(readDemoRecoveryRequests().filter((item) => item.status === "PENDING"));
            setError("");
            return;
        }
        try {
            const items = await brainServeApi.pendingAccountRecoveryRequests();
            setRequests(items);
            setError("");
        } catch (reason) {
            if (showError) {
                setError(reason instanceof ApiError ? reason.message : "Recovery requests could not be loaded.");
            }
        }
    }, []);

    useEffect(() => {
        const initialLoad = window.setTimeout(() => void loadRequests(), 0);
        const timer = window.setInterval(() => {
            if (isWorkspaceUpdateLeader() && document.visibilityState === "visible") {
                void loadRequests(false);
            }
        }, 10000);
        const refreshWhenVisible = () => {
            if (document.visibilityState === "visible") void loadRequests(false);
        };
        const refreshPreviewStorage = (event: StorageEvent) => {
            if (!isBackendConfigured && event.key === DEMO_RECOVERY_REQUESTS_KEY) void loadRequests(false);
        };
        const refreshPreviewWindow = () => {
            if (!isBackendConfigured) void loadRequests(false);
        };
        window.addEventListener("focus", refreshWhenVisible);
        window.addEventListener("storage", refreshPreviewStorage);
        window.addEventListener("brainserve:demo-recovery-updated", refreshPreviewWindow);
        document.addEventListener("visibilitychange", refreshWhenVisible);
        return () => {
            window.clearTimeout(initialLoad);
            window.clearInterval(timer);
            window.removeEventListener("focus", refreshWhenVisible);
            window.removeEventListener("storage", refreshPreviewStorage);
            window.removeEventListener("brainserve:demo-recovery-updated", refreshPreviewWindow);
            document.removeEventListener("visibilitychange", refreshWhenVisible);
        };
    }, [loadRequests]);

    useEffect(() => {
        if (!generated?.expiresAt) return;
        const expiresAt = Date.parse(generated.expiresAt);
        if (!Number.isFinite(expiresAt)) return;
        const expiryTimer = window.setTimeout(() => {
            onGeneratedChange(null);
            setCopied(false);
            setMessage("");
            setError("The displayed recovery code has expired.");
        }, Math.max(0, expiresAt - Date.now()));
        return () => window.clearTimeout(expiryTimer);
    }, [generated?.id, generated?.expiresAt, onGeneratedChange]);

    const decide = async (request: AccountRecoveryRequest, decision: "approve" | "reject") => {
        setBusyId(request.id); setError(""); setMessage(""); setCopied(false);
        try {
            let result: AccountRecoveryRequest;
            if (isBackendConfigured) {
                result = await brainServeApi.decideAccountRecovery(request.id, decision,
                    decision === "reject" ? "Rejected after System Admin identity review" : "");
            } else if (decision === "approve") {
                const code = newDemoRecoveryCode();
                const approvedAt = new Date();
                result = { ...request, status: "APPROVED", recoveryCode: code,
                    approvedAt: approvedAt.toISOString(), expiresAt: new Date(approvedAt.getTime() + 30 * 60 * 1000).toISOString() };
                writeDemoRecoveryRequests(readDemoRecoveryRequests().map((item) => item.id === request.id
                    ? { ...result, recoveryCode: code } : item));
            } else {
                result = { ...request, status: "REJECTED", recoveryCode: null };
                writeDemoRecoveryRequests(readDemoRecoveryRequests().map((item) => item.id === request.id
                    ? { ...item, status: "REJECTED", recoveryCode: null } : item));
            }
            setRequests((items) => items.filter((item) => item.id !== request.id));
            if (decision === "approve") {
                if (!result.recoveryCode) {
                    throw new Error("Recovery was approved, but the backend did not return the one-time code.");
                }
                onGeneratedChange(result);
                setMessage(`Recovery approved for ${request.fullName}. Give the code to the verified account owner securely.`);
            } else {
                setMessage(`Recovery request for ${request.fullName} was rejected.`);
            }
        } catch (reason) {
            setError(reason instanceof Error ? reason.message : "The recovery decision failed.");
        } finally { setBusyId(""); }
    };

    const copyCode = async () => {
        if (!generated?.recoveryCode) return;
        try {
            await navigator.clipboard.writeText(generated.recoveryCode);
            setCopied(true);
        } catch { setError("Copy was blocked by the browser. Select the code and copy it manually."); }
    };

    return <article className="panel glass-panel recovery-approval-panel"><div className="panel-heading"><div><span>ACCOUNT RECOVERY APPROVALS</span><h2>Password & company email requests</h2><p>Verify the requester’s identity before approval. Only the System Admin can issue these one-time codes.</p></div><span className="panel-heading-actions"><b>{requests.length}</b><button type="button" className="button button-secondary" onClick={() => void loadRequests()}><RotateCcw size={15} /> Refresh requests</button></span></div>{generated?.recoveryCode && <div className="recovery-code-card"><span><ShieldCheck size={18} /> APPROVED · AVAILABLE UNTIL DISMISSED OR EXPIRED</span><h3>{generated.fullName}</h3><p>{generated.type === "PASSWORD" ? "Password reset" : "Company email recovery"} · expires {generated.expiresAt ? new Date(generated.expiresAt).toLocaleTimeString("en-IN", { hour: "numeric", minute: "2-digit" }) : "in 30 minutes"}</p><code>{generated.recoveryCode}</code><div><button type="button" className="button button-secondary" onClick={() => void copyCode()}>{copied ? <Check size={16} /> : <FileText size={16} />}{copied ? "Copied" : "Copy code"}</button><button type="button" className="text-button" onClick={() => { onGeneratedChange(null); setCopied(false); setMessage(""); }}>Dismiss code</button></div><small>The raw code stays only in the current authenticated dashboard memory. It disappears on logout, page reload, manual dismissal or expiry.</small></div>}<div className="staff-account-list">{requests.map((request) => <div className="staff-account-row" key={request.id}><div className="staff-account-head"><span className="role-icon"><Fingerprint size={18} /></span><span><strong>{request.fullName}</strong><small>{request.email} · {request.role.replace("ROLE_", "").replaceAll("_", " ")} · {request.type === "PASSWORD" ? "Password reset" : "Email recovery"}</small></span><span className="status-pill status-pending"><span />Pending</span></div><div className="approval-actions"><button type="button" className="button button-reject" disabled={busyId === request.id} onClick={() => void decide(request, "reject")}><X size={16} /> Reject</button><button type="button" className="button button-approve" disabled={busyId === request.id} onClick={() => void decide(request, "approve")}><Check size={16} /> Verify & issue code</button></div></div>)}{requests.length === 0 && <div className="empty-state"><CheckCircle2 size={28} /><strong>No pending recovery requests</strong><small>Password and email recovery approvals will appear here.</small></div>}</div>{message && <div className="success-banner"><CheckCircle2 size={17} />{message}</div>}{error && <div className="login-error" role="alert">{error}</div>}</article>;
}

