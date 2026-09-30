"use client";

import { brainServeApi, isBackendConfigured } from "../../lib/api";
import { formatOfficeTime, officeToday } from "../../lib/appointments";
import { PageTitle } from "../../shared/components/page-title";
import { StatusPill } from "../../shared/components/status-pill";
import { type AccessRecord, type Appointment, type Role } from "../../shared/types/workspace";
import { fail } from "../../shared/utils/errors";
import { visitorInitials } from "../appointments/appointment-utils";
import { VisitorIdentityRegistry } from "./visitor-identity-registry";
import { ArrowRight, CalendarDays, Check, CheckCircle2, DoorOpen, LogIn, LogOut, QrCode, UserPlus, X } from "lucide-react";
import { type FormEvent, useState } from "react";

export function VisitorsView({ role, appointments, accessRecords, onCheckIn, onReferenceCheckIn, onPassCheckIn, onCheckOut,
                          decideReceptionVisit, onRegister }: {
    role: Role; appointments: Appointment[]; accessRecords: AccessRecord[];
    onCheckIn: (id: string) => Promise<void>; onReferenceCheckIn: (reference: string) => Promise<void>;
    onPassCheckIn: (token: string) => Promise<void>; onCheckOut: (id: string) => Promise<void>;
    decideReceptionVisit: (id: string, decision: "verify" | "reject") => Promise<void>; onRegister: () => void;
}) {
    const canProcessAccess = ["Reception", "Security"].includes(role);
    const approved = appointments.filter((item) => item.status === "Approved");
    const readyForCheckIn = (item: Appointment) => !["HR visit", "Interview", "CEO visit"].includes(item.type)
        || Boolean(item.receptionForwardedAt);
    const receptionQueue = appointments.filter((item) => item.status === "Awaiting Reception"
        && (item.slotStart ? officeToday(new Date(item.slotStart)) === officeToday() : item.date === "Today"));
    const [renderedAt] = useState(() => Date.now());
    const [reference, setReference] = useState("");
    const [passToken, setPassToken] = useState("");
    const [verifiedPass, setVerifiedPass] = useState<{ referenceNumber: string; visitorName: string; appointmentStatus: string; validUntil: string } | null>(null);
    const [error, setError] = useState("");
    const checkInReference = async (event: FormEvent<HTMLFormElement>) => {
        event.preventDefault(); setError("");
        try { await onReferenceCheckIn(reference); setReference(""); }
        catch (reason) { setError(reason instanceof Error ? reason.message : "Check-in failed."); }
    };
    const verifyPass = async (event: FormEvent<HTMLFormElement>) => {
        event.preventDefault(); setError(""); setVerifiedPass(null);
        try {
            if (isBackendConfigured) {
                setVerifiedPass(await brainServeApi.verifyVisitorPass(passToken));
            } else {
                const referenceNumber = passToken.replace("brainserve-demo:", "").trim().toUpperCase();
                if (!/^BSA-[A-Z0-9]{4}-[A-Z0-9]{4}$/.test(referenceNumber)) fail("This QR pass is invalid.");
                setVerifiedPass({ referenceNumber, visitorName: "Demo visitor", appointmentStatus: "APPROVED",
                    validUntil: new Date(Date.now() + 2 * 60 * 60 * 1000).toISOString() });
            }
        } catch (reason) { setError(reason instanceof Error ? reason.message : "The QR pass could not be verified."); }
    };
    const checkInPass = async () => {
        setError("");
        try { await onPassCheckIn(passToken); setPassToken(""); setVerifiedPass(null); }
        catch (reason) { setError(reason instanceof Error ? reason.message : "QR pass check-in failed."); }
    };
    return <>
        <PageTitle eyebrow={role === "Security" ? "PHYSICAL ACCESS" : "RECEPTION & VISITORS"}
                   title={role === "Security" ? "Everyone inside, accounted for" : "A calm, confident arrival"}
                   detail="Security captures each arrival, Reception verifies it, and CEO visits go directly to the assigned department Manager for approval."
                   action={<button className="button button-primary" onClick={onRegister}><UserPlus size={17} /> {role === "Security" ? "Create walk-in" : "Register visitor"}</button>} />
        <div className="reception-actions">
            <button className="action-tile glass-panel" onClick={onRegister}><span><UserPlus size={24} /></span><div><strong>{role === "Security" ? "Create walk-in appointment" : "Register interview or meeting"}</strong><small>{role === "Security" ? "Capture arrival and notify Reception immediately" : "Start the Security → Reception → approval workflow"}</small></div><ArrowRight size={18} /></button>
            <button className="action-tile glass-panel" onClick={() => document.getElementById("live-occupancy")?.scrollIntoView({ behavior: "smooth", block: "center" })}><span><DoorOpen size={24} /></span><div><strong>Emergency list</strong><small>{accessRecords.length} people currently inside</small></div><ArrowRight size={18} /></button>
        </div>
        {isBackendConfigured && ["Security", "Reception"].includes(role) && <VisitorIdentityRegistry />}
        {role === "Reception" && <article className="panel glass-panel reception-verification-queue"><div className="panel-heading"><div><span>FROM SECURITY</span><h2>Visitors awaiting Reception</h2><p>Review the gate intake and route the verified visitor to the assigned department reviewer.</p></div><b>{receptionQueue.length}</b></div><div className="staff-account-list">{receptionQueue.map((item) => <div className="staff-account-row" key={item.id}><div className="staff-account-head"><span className="avatar">{item.initials}</span><span><strong>{item.arrivalVisitorName ?? item.visitor}</strong><small>{item.referenceNumber} · requested {item.host}</small></span><StatusPill status={item.status} /></div><div className="visitor-route-details"><span><strong>Purpose</strong><small>{item.arrivalPurpose ?? item.purpose}</small></span><span><strong>Received</strong><small>{item.createdAt ? new Date(item.createdAt).toLocaleString("en-IN", { dateStyle: "medium", timeStyle: "short" }) : `${item.date}, ${item.time}`}</small></span><span><strong>Identity</strong><small>{item.identityDocumentLastFour ? `${item.identityDocumentType ?? "ID"} ••••${item.identityDocumentLastFour}` : "Not recorded"}</small></span></div><div className="approval-actions"><button className="button button-reject" onClick={() => void decideReceptionVisit(item.id, "reject")}><X size={15} /> Reject</button><button className="button button-approve" onClick={() => void decideReceptionVisit(item.id, "verify")}><Check size={15} /> {item.type === "CEO visit" ? "Verify & route to Manager" : "Verify & route to HR"}</button></div></div>)}{receptionQueue.length === 0 && <div className="empty-state"><CheckCircle2 size={28} /><strong>No visitors waiting</strong><small>Security-created arrivals will appear here automatically.</small></div>}</div></article>}
        {canProcessAccess && <div className="access-tools"><form className="inline-account-form panel glass-panel" onSubmit={checkInReference}><label>Appointment reference<input value={reference} onChange={(event) => setReference(event.target.value.toUpperCase())} placeholder="BSA-XXXX-XXXX" required /></label><button className="button button-approve"><DoorOpen size={16} /> Check in by reference</button></form><form className="pass-verify-form panel glass-panel" onSubmit={verifyPass}><label>Scanned QR content<input value={passToken} onChange={(event) => setPassToken(event.target.value)} placeholder="Paste the scanned BrainServe pass token" required /></label><button className="button button-secondary"><QrCode size={16} /> Verify signed pass</button>{verifiedPass && <div className="verified-pass"><CheckCircle2 size={19} /><span><strong>{verifiedPass.visitorName}</strong><small>{verifiedPass.referenceNumber} · valid until {formatOfficeTime(verifiedPass.validUntil)}</small></span><button type="button" className="button button-approve" onClick={() => void checkInPass()}>Check in</button></div>}</form>{error && <div className="login-error" role="alert">{error}</div>}</div>}
        <section className="dashboard-grid visitors-grid">
            <article className="panel glass-panel"><div className="panel-heading"><div><span>EXPECTED</span><h2>Approved arrivals</h2></div><b>{approved.length}</b></div><div className="compact-list">{approved.map((item) => <div key={item.id}><time>{item.time}</time><span className="avatar">{item.initials}</span><span><strong>{item.visitor}</strong><small>Meeting {item.host}</small></span>{canProcessAccess && (readyForCheckIn(item) ? <button className="button button-approve" onClick={() => void onCheckIn(item.id)}><LogIn size={15} /> Check in</button> : <span className="status-pill status-pending"><span />Route from Reception first</span>)}</div>)}{approved.length === 0 && <div className="empty-state"><CalendarDays size={26} /><strong>No approved arrivals</strong><small>Approved appointments will appear here.</small></div>}</div></article>
            <article className="panel glass-panel" id="live-occupancy"><div className="panel-heading"><div><span>LIVE OCCUPANCY</span><h2>Currently inside</h2></div><span className="live-badge"><i /> LIVE</span></div>{accessRecords.length ? <div className="inside-list">{accessRecords.map((record) => { const minutes = Math.max(1, Math.floor((renderedAt - new Date(record.checkedInAt).getTime()) / 60000)); return <div key={record.id}><span className="avatar">{visitorInitials(record.visitorName)}</span><span><strong>{record.visitorName}</strong><small>Badge {record.badgeNumber} · in for {minutes} min</small></span>{canProcessAccess && <button className="button button-reject" onClick={() => void onCheckOut(record.appointmentId)}><LogOut size={15} /> Check out</button>}</div>; })}</div> : <div className="empty-state"><DoorOpen size={28} /><strong>No active visitors</strong><small>Checked-in visitors will appear here.</small></div>}</article>
        </section>
    </>;
}

