"use client";

import { brainServeApi, isBackendConfigured, type VisitorPass } from "../../lib/api";
import { formatOfficeDate, formatOfficeTime, type PublicAppointment } from "../../lib/appointments";
import { readDemoAppointments } from "../../preview/appointments";
import { DEMO_APPOINTMENTS_KEY, DEMO_LAST_REFERENCE_KEY } from "../../preview/storage-keys";
import { Logo } from "../../shared/components/logo";
import { type Screen } from "../../shared/types/workspace";
import { fail } from "../../shared/utils/errors";
import { appointmentStatusFromApi } from "./appointment-utils";
import { ArrowLeft, CheckCircle2, QrCode, Search, ShieldCheck, X } from "lucide-react";
import Image from "next/image";
import { type FormEvent, useEffect, useState } from "react";

export function TrackAppointment({ onNavigate }: { onNavigate: (screen: Screen) => void }) {
    const [reference, setReference] = useState(() => isBackendConfigured || typeof window === "undefined"
        ? "" : window.localStorage.getItem(DEMO_LAST_REFERENCE_KEY) ?? "");
    const [result, setResult] = useState<PublicAppointment | null>(null);
    const [busy, setBusy] = useState(false);
    const [error, setError] = useState("");
    const [visitorPass, setVisitorPass] = useState<VisitorPass | null>(null);
    const [passError, setPassError] = useState("");
    const [cancellationOtpRequested, setCancellationOtpRequested] = useState(false);
    const [cancellationOtp, setCancellationOtp] = useState("");
    useEffect(() => {
        if (!result || !["APPROVED", "CHECKED_IN"].includes(result.status)) return;
        let active = true;
        const load = async () => {
            try {
                const pass = isBackendConfigured
                    ? await brainServeApi.visitorPass(result.referenceNumber)
                    : {
                        referenceNumber: result.referenceNumber, visitorDisplayName: result.visitorDisplayName,
                        status: result.status, validFrom: result.slotStart,
                        expiresAt: new Date(new Date(result.slotEnd).getTime() + 2 * 60 * 60 * 1000).toISOString(),
                        token: `brainserve-demo:${result.referenceNumber}`,
                        qrCodeDataUrl: await (await import("qrcode")).default.toDataURL(`brainserve-demo:${result.referenceNumber}`, {
                            width: 320, margin: 1, color: { dark: "#690718", light: "#ffffff" },
                        }),
                    } satisfies VisitorPass;
                if (active) setVisitorPass(pass);
            } catch (reason) {
                if (active) setPassError(reason instanceof Error ? reason.message : "The QR pass could not be generated.");
            }
        };
        void load();
        return () => { active = false; };
    }, [result]);
    const trackedReference = result?.referenceNumber;
    const trackedStatus = result?.status;
    useEffect(() => {
        if (!trackedReference || !trackedStatus) return;
        const terminalStatuses = new Set(["CANCELLED", "REJECTED", "COMPLETED", "CHECKED_OUT", "NO_SHOW", "EXPIRED"]);
        if (terminalStatuses.has(trackedStatus)) return;
        let active = true;
        const refreshStatus = async () => {
            try {
                const refreshed = isBackendConfigured
                    ? await brainServeApi.trackAppointment(trackedReference)
                    : readDemoAppointments().find((item) => item.referenceNumber === trackedReference);
                if (active && refreshed) {
                    if (!["APPROVED", "CHECKED_IN"].includes(refreshed.status)) {
                        setVisitorPass(null);
                        setPassError("");
                    }
                    setResult(refreshed);
                }
            } catch { /* Keep the last verified status during a temporary refresh failure. */ }
        };
        const timer = window.setInterval(() => void refreshStatus(), 5000);
        const onStorage = (event: StorageEvent) => {
            if (event.key === DEMO_APPOINTMENTS_KEY) void refreshStatus();
        };
        window.addEventListener("storage", onStorage);
        return () => {
            active = false;
            window.clearInterval(timer);
            window.removeEventListener("storage", onStorage);
        };
    }, [trackedReference, trackedStatus]);
    const track = async (event: FormEvent<HTMLFormElement>) => {
        event.preventDefault(); setBusy(true); setError(""); setResult(null); setVisitorPass(null); setPassError("");
        setCancellationOtpRequested(false); setCancellationOtp("");
        const normalized = reference.trim().toUpperCase();
        if (!/^BSA-[A-Z0-9]{4}-[A-Z0-9]{4}$/.test(normalized)) {
            setError("Enter a valid reference such as BSA-7M4K-26Q9."); setBusy(false); return;
        }
        try {
            const appointment = isBackendConfigured
                ? await brainServeApi.trackAppointment(normalized)
                : readDemoAppointments().find((item) => item.referenceNumber === normalized);
            if (!appointment) fail("Appointment was not found.");
            setReference(normalized); setResult(appointment);
        } catch (reason) { setError(reason instanceof Error ? reason.message : "Appointment was not found."); }
        finally { setBusy(false); }
    };
    const requestCancellationOtp = async () => {
        if (!result) return;
        setBusy(true); setError("");
        try {
            if (!isBackendConfigured) fail("Secure appointment cancellation requires the BrainServe backend.");
            await brainServeApi.requestAppointmentCancellationOtp(result.referenceNumber);
            setCancellationOtpRequested(true);
        } catch (reason) { setError(reason instanceof Error ? reason.message : "The cancellation code could not be sent."); }
        finally { setBusy(false); }
    };
    const cancel = async (event: FormEvent<HTMLFormElement>) => {
        event.preventDefault();
        if (!result) return;
        setBusy(true); setError("");
        try {
            if (!isBackendConfigured) fail("Secure appointment cancellation requires the BrainServe backend.");
            setResult(await brainServeApi.cancelAppointment(result.referenceNumber, cancellationOtp));
            setCancellationOtpRequested(false);
            setCancellationOtp("");
        } catch (reason) { setError(reason instanceof Error ? reason.message : "The appointment could not be cancelled."); }
        finally { setBusy(false); }
    };
    const readableStatus = result ? appointmentStatusFromApi(result.status) : "Pending";
    const completed = result ? ["COMPLETED", "CHECKED_OUT"].includes(result.status) : false;
    const cancellable = result && !["CANCELLED", "REJECTED", "COMPLETED", "CHECKED_OUT", "EXPIRED"].includes(result.status);
    return <main className="flow-page"><header className="flow-header"><Logo /><button className="icon-button" onClick={() => onNavigate("welcome")} aria-label="Close"><X size={20} /></button></header><section className="track-card glass-panel"><div className="track-icon"><Search size={26} /></div><span className="eyebrow">Appointment tracker</span><h1>Know what’s happening.</h1><p>Enter the secure reference sent to your email.</p><form onSubmit={track}><label htmlFor="reference">Tracking reference</label><div className="reference-input"><input id="reference" value={reference} onChange={(event) => setReference(event.target.value.toUpperCase())} placeholder="e.g. BSA-7M4K-26Q9" required /><button className="button button-primary" disabled={busy}>{busy ? "Checking…" : "Track"}</button></div></form>{error && <div className="login-error" role="alert">{error}</div>}{result && <div className={`tracked-result${completed ? " tracked-completed" : ""}`}><div className="tracked-head"><span><CheckCircle2 size={20} /><strong>{readableStatus}</strong></span><small>{result.referenceNumber}</small></div><h3>{result.visitorDisplayName} · BrainServe Connect appointment</h3><p>{formatOfficeDate(result.slotStart)} · {formatOfficeTime(result.slotStart)} · Hyderabad HQ</p>{completed && <div className="completion-banner"><CheckCircle2 size={18} /><span><strong>Visit completed</strong><small>The visitor has checked out and this appointment is now closed.</small></span></div>}<div className="arrival-timeline"><span className="done" /><i /><span className={result.status !== "PENDING_VERIFICATION" ? "done" : ""} /><i /><span className={result.status === "CHECKED_IN" || result.status === "IN_MEETING" || completed ? "done" : ""} /></div><div className="arrival-stages"><span>Requested</span><span>Approval</span><span>{completed ? "Completed" : "Arrival"}</span></div>{visitorPass && <div className="visitor-pass"><div className="visitor-pass-head"><span><QrCode size={18} /> SIGNED VISITOR PASS</span><strong>{visitorPass.referenceNumber}</strong></div><Image src={visitorPass.qrCodeDataUrl} width={320} height={320} unoptimized alt={`QR visitor pass for ${visitorPass.referenceNumber}`} /><p>Present this QR at reception or security. It is signed by BrainServe Connect and expires {formatOfficeDate(visitorPass.expiresAt)} at {formatOfficeTime(visitorPass.expiresAt)}.</p><a className="button button-primary" href={visitorPass.qrCodeDataUrl} download={`BrainServe-${visitorPass.referenceNumber}-pass.png`}><QrCode size={16} /> Save QR pass</a></div>}{passError && <div className="login-error" role="alert">{passError}</div>}{cancellable && !cancellationOtpRequested && <button className="button button-reject" disabled={busy} onClick={() => void requestCancellationOtp()}><X size={17} /> {busy ? "Sending code…" : "Cancel appointment"}</button>}{cancellable && cancellationOtpRequested && <form className="field-stack" onSubmit={cancel}><div className="info-banner"><ShieldCheck size={18} /><span><strong>Cancellation code sent</strong><small>Enter the six-digit code sent to the appointment email. It expires in 10 minutes.</small></span></div><label htmlFor="cancellationOtp">Cancellation code<input id="cancellationOtp" inputMode="numeric" autoComplete="one-time-code" pattern="[0-9]{6}" maxLength={6} value={cancellationOtp} onChange={(event) => setCancellationOtp(event.target.value.replace(/\D/g, "").slice(0, 6))} required /></label><div className="form-actions"><button type="button" className="button button-secondary" disabled={busy} onClick={() => { setCancellationOtpRequested(false); setCancellationOtp(""); }}>Keep appointment</button><button className="button button-reject" disabled={busy || cancellationOtp.length !== 6}><X size={17} /> {busy ? "Cancelling…" : "Confirm cancellation"}</button></div></form>}</div>}<button className="text-button back-home" onClick={() => onNavigate("welcome")}><ArrowLeft size={16} /> Back to home</button></section></main>;
}

