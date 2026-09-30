"use client";

import { brainServeApi, isBackendConfigured, type MonthlyRecords } from "../../services/brainserve-api";
import { formatOfficeDate, formatOfficeTime, officeYearMonth } from "../../lib/appointments";
import { initialEmployees } from "../../preview/fixtures/workspace";
import { PageTitle } from "../../components/ui/page-title";
import { StatusPill } from "../../components/ui/status-pill";
import { type Appointment, type DashboardMetrics, type Role } from "../../types/workspace";
import { appointmentStatusFromApi } from "../appointments/appointment-utils";
import { employeeStatusLabel } from "../employees/employee-utils";
import { BadgeCheck, CalendarDays, FileText, IdCard, Search, ShieldCheck, UserCog, Users } from "lucide-react";
import { useEffect, useState } from "react";

export function LegacyReportsView({ role, metrics, appointments }: { role: Role; metrics: DashboardMetrics; appointments: Appointment[] }) {
    const now = new Date();
    const [period, setPeriod] = useState(officeYearMonth(now));
    const [records, setRecords] = useState<MonthlyRecords | null>(null);
    const [query, setQuery] = useState("");
    const [error, setError] = useState("");
    useEffect(() => {
        if (role !== "System Admin" || !isBackendConfigured) return;
        let active = true; const [year, month] = period.split("-").map(Number);
        brainServeApi.monthlyRecords(year, month).then((value) => { if (active) setRecords(value); })
            .catch((reason) => { if (active) setError(reason instanceof Error ? reason.message : "Monthly records could not be loaded."); });
        return () => { active = false; };
    }, [period, role]);
    const count = (label: string) => appointments.filter((item) => item.type === label).length;
    const total = Math.max(appointments.length, 1);
    const percentage = (label: string) => Math.round((count(label) / total) * 100);
    const demoVisitorRecords = !isBackendConfigured ? appointments.filter((item) => {
        if (!item.receptionVerifiedAt) return false;
        return officeYearMonth(item.receptionVerifiedAt) === period;
    }).map((item) => ({
        id: item.id, referenceNumber: item.referenceNumber ?? item.id,
        visitorName: item.arrivalVisitorName ?? item.visitor, visitorEmail: item.visitorEmail ?? "",
        visitorPhone: item.visitorPhone ?? "", visitorCompany: item.company, type: item.type.toUpperCase().replaceAll(" ", "_"),
        status: item.status.toUpperCase().replaceAll(" ", "_"), hostEmployeeId: item.hostEmployeeId ?? "",
        hostName: item.host, routingDepartmentId: item.routingDepartmentId ?? null,
        requestedEmployeeId: item.requestedEmployeeId ?? null,
        requestedEmployeeName: item.requestedEmployeeId ? item.host : null,
        slotStart: item.slotStart ?? item.receptionVerifiedAt!,
        purpose: item.arrivalPurpose ?? item.purpose, identityDocumentType: item.identityDocumentType ?? null,
        identityDocumentLastFour: item.identityDocumentLastFour ?? null,
        securityActorId: item.securityIntakeActorId ?? "security-preview", securityIntakeAt: item.securityIntakeAt ?? item.receptionVerifiedAt!,
        receptionActorId: item.receptionVerificationActorId ?? "reception-preview",
        receptionVerifiedAt: item.receptionVerifiedAt!, receptionRemarks: item.receptionVerificationRemarks ?? null,
        hrActorId: item.hrApprovalActorId ?? null, hrDecisionAt: item.hrDecisionAt ?? null,
        teamLeadActorId: item.teamLeadApprovalActorId ?? null,
        teamLeadDecisionAt: item.teamLeadDecisionAt ?? null,
        managerActorId: item.managerApprovalActorId ?? null,
        managerDecisionAt: item.managerDecisionAt ?? null,
        ceoActorId: item.ceoApprovalActorId ?? null, ceoDecisionAt: item.ceoDecisionAt ?? null,
        receptionForwardActorId: item.receptionForwardActorId ?? null,
        receptionForwardedAt: item.receptionForwardedAt ?? null,
        receptionForwardRemarks: item.receptionForwardRemarks ?? null,
        badgeNumber: null, checkedInAt: null, checkedOutAt: null, processedBy: null,
    })) : [];
    const visitorRecords = records?.visitors ?? demoVisitorRecords;
    const demoEmployeeRecords = !isBackendConfigured ? initialEmployees.map((item) => ({ id: item.uuid ?? item.id,
        employeeNumber: item.id, displayName: item.name, officialEmail: item.email, designation: item.role,
        status: item.status.toUpperCase().replaceAll(" ", "_"), joiningDate: "2026-01-06", relievingDate: null })) : [];
    const employeeRecords = records?.employees ?? demoEmployeeRecords;
    const leaveRecords = records?.leaveRequests ?? [];
    const normalizedQuery = query.trim().toLowerCase();
    const filteredVisitors = visitorRecords.filter((item) => !normalizedQuery
        || `${item.visitorName} ${item.visitorCompany ?? ""} ${item.referenceNumber} ${item.hostName} ${item.purpose} ${item.status}`.toLowerCase().includes(normalizedQuery));
    const filteredEmployees = employeeRecords.filter((item) => !normalizedQuery
        || `${item.displayName} ${item.employeeNumber} ${item.officialEmail} ${item.designation} ${item.status}`.toLowerCase().includes(normalizedQuery));
    const filteredLeaves = leaveRecords.filter((item) => !normalizedQuery
        || `${item.employeeId} ${item.reason} ${item.status} ${item.startDate} ${item.endDate}`.toLowerCase().includes(normalizedQuery));
    if (role === "System Admin") return <><PageTitle eyebrow="SYSTEM RECORDS" title="Monthly workforce & visitor register"
                                                     detail="Persisted appointment, employee lifecycle and leave records. Deactivated people remain in history." action={<label className="month-picker">Month<input type="month" value={period} onChange={(event) => { setRecords(null); setError(""); setPeriod(event.target.value); }} /></label>} />
        {error && <div className="login-error" role="alert">{error}</div>}
        <section className="employee-summary records-summary"><div><strong>{records?.visitorCount ?? visitorRecords.length}</strong><span>Reception arrivals</span></div><i /><div><strong>{records?.employeeCount ?? employeeRecords.length}</strong><span>Employee records</span></div><i /><div><strong>{records?.joinedEmployees ?? employeeRecords.length}</strong><span>Joined</span></div><i /><div><strong>{records?.relievedEmployees ?? employeeRecords.filter((item) => item.relievingDate).length}</strong><span>Relieved</span></div><i /><div><strong>{records?.pendingLeaveRequests ?? leaveRecords.filter((item) => item.status === "PENDING").length}</strong><span>Pending leave</span></div></section>
        <div className="toolbar records-toolbar glass-panel"><div className="toolbar-search wide"><Search size={17} /><input value={query} onChange={(event) => setQuery(event.target.value)} placeholder="Search visitor, reference, host, employee or leave reason" /></div><span><FileText size={15} /> {records?.period ?? period} retained records</span></div>
        <article className="panel glass-panel records-panel"><div className="panel-heading"><div><span>MONTHLY VISITOR REGISTER</span><h2>Reception-processed visitors</h2><p>Security, Reception, approval and access details are retained in one export-ready table.</p></div><b>{filteredVisitors.length}</b></div><div className="records-table-wrap"><table className="records-table visitor-records-table"><thead><tr><th>Visitor</th><th>Visit & purpose</th><th>Host & schedule</th><th>Workflow trail</th><th>Access</th><th>Status</th></tr></thead><tbody>{filteredVisitors.map((item) => <tr key={item.id}><td><strong>{item.visitorName}</strong><small>{item.visitorCompany ?? "Independent"}</small><small>{item.visitorEmail}{item.visitorPhone ? ` · ${item.visitorPhone}` : ""}</small><code>{item.referenceNumber}</code></td><td><strong>{item.type.replaceAll("_", " ")}</strong><small>{item.purpose}</small><small>{item.identityDocumentLastFour ? `${item.identityDocumentType ?? "ID"} ••••${item.identityDocumentLastFour}` : "No identity reference retained"}</small></td><td><strong>{item.hostName}</strong><small>{formatOfficeDate(item.slotStart)} · {formatOfficeTime(item.slotStart)}</small><small>Host ID {item.hostEmployeeId}</small></td><td><span className="trail-line"><ShieldCheck size={13} />Security {formatOfficeTime(item.securityIntakeAt)}</span><span className="trail-line"><BadgeCheck size={13} />Reception {formatOfficeTime(item.receptionVerifiedAt)}</span>{item.type === "CEO_VISIT" || item.managerDecisionAt || item.ceoDecisionAt ? <><span className="trail-line"><UserCog size={13} />{item.managerDecisionAt ? `Manager ${formatOfficeTime(item.managerDecisionAt)}` : "Manager pending"}</span><span className="trail-line"><ShieldCheck size={13} />{item.ceoDecisionAt ? `CEO ${formatOfficeTime(item.ceoDecisionAt)}` : "CEO pending"}</span></> : <><span className="trail-line"><UserCog size={13} />{item.hrDecisionAt ? `HR ${formatOfficeTime(item.hrDecisionAt)}` : "HR pending"}</span><span className="trail-line"><Users size={13} />{item.teamLeadDecisionAt ? `Team Lead ${formatOfficeTime(item.teamLeadDecisionAt)}` : "Team Lead —"}</span></>}</td><td><strong>{item.badgeNumber ? `Badge ${item.badgeNumber}` : "No badge"}</strong><small>{item.checkedInAt ? `In ${formatOfficeTime(item.checkedInAt)}` : "Not checked in"}</small><small>{item.checkedOutAt ? `Out ${formatOfficeTime(item.checkedOutAt)}` : "Not checked out"}</small></td><td><StatusPill status={appointmentStatusFromApi(item.status)} /></td></tr>)}{filteredVisitors.length === 0 && <tr><td colSpan={6}><div className="empty-state table-empty"><IdCard size={28} /><strong>No matching Reception-processed visitors</strong><small>Scheduled appointments that never reached Reception are excluded.</small></div></td></tr>}</tbody></table></div></article>
        <article className="panel glass-panel records-panel"><div className="panel-heading"><div><span>EMPLOYEE LIFECYCLE REGISTER</span><h2>Retained workforce records</h2><p>Employment rows remain available after resignation, termination or deactivation.</p></div><b>{filteredEmployees.length}</b></div><div className="records-table-wrap"><table className="records-table"><thead><tr><th>Employee</th><th>Employee ID</th><th>Designation</th><th>Joined</th><th>Relieved</th><th>Status</th></tr></thead><tbody>{filteredEmployees.map((item) => <tr key={item.id}><td><strong>{item.displayName}</strong><small>{item.officialEmail}</small></td><td><code>{item.employeeNumber}</code></td><td><strong>{item.designation}</strong></td><td>{item.joiningDate}</td><td>{item.relievingDate ?? "—"}</td><td><StatusPill status={employeeStatusLabel(item.status)} /></td></tr>)}{filteredEmployees.length === 0 && <tr><td colSpan={6}><div className="empty-state table-empty"><Users size={28} /><strong>No matching employee records</strong></div></td></tr>}</tbody></table></div></article>
        <article className="panel glass-panel records-panel"><div className="panel-heading"><div><span>LEAVE REQUEST REGISTER</span><h2>Monthly leave decisions</h2><p>Requests, decisions and reasons remain linked to the employee record.</p></div><b>{filteredLeaves.length}</b></div><div className="records-table-wrap"><table className="records-table"><thead><tr><th>Employee ID</th><th>Period</th><th>Reason</th><th>Requested</th><th>Decision</th><th>Status</th></tr></thead><tbody>{filteredLeaves.map((item) => <tr key={item.id}><td><code>{item.employeeId}</code></td><td><strong>{item.startDate}</strong><small>to {item.endDate}</small></td><td>{item.reason}</td><td>{formatOfficeDate(item.createdAt)}</td><td>{item.decisionReason ?? "—"}</td><td><StatusPill status={item.status === "PENDING" ? "Pending" : item.status === "APPROVED" ? "Approved" : item.status === "REJECTED" ? "Rejected" : "Cancelled"} /></td></tr>)}{filteredLeaves.length === 0 && <tr><td colSpan={6}><div className="empty-state table-empty"><CalendarDays size={28} /><strong>No matching leave requests</strong><small>Leave records for this month will appear here.</small></div></td></tr>}</tbody></table></div></article></>;
    return <><PageTitle eyebrow="REPORTING & INSIGHTS" title="Current operational picture" detail="Live totals from appointment, employee and reception services." /><div className="report-grid"><article className="panel glass-panel wide-report"><div className="panel-heading"><div><span>APPOINTMENT ACTIVITY</span><h2>Current service totals</h2></div></div><div className="report-number"><strong>{appointments.length}</strong><span>loaded appointments</span><b>{metrics.awaitingApproval} awaiting approval</b></div><div className="employee-summary"><div><strong>{metrics.activeVisits}</strong><span>Active visits</span></div><i /><div><strong>{metrics.visitorsInside}</strong><span>Inside</span></div><i /><div><strong>{metrics.activeEmployees}</strong><span>Active employees</span></div></div></article><article className="panel glass-panel"><div className="panel-heading"><div><span>BY VISIT TYPE</span><h2>Visit mix</h2></div></div><div className="visit-mix"><span className="mix-donut"><strong>{appointments.length}</strong><small>Total</small></span><ul><li><i />Employee visit <b>{percentage("Employee visit")}%</b></li><li><i />Interview <b>{percentage("Interview")}%</b></li><li><i />Client meeting <b>{percentage("Client meeting")}%</b></li><li><i />CEO visit <b>{percentage("CEO visit")}%</b></li></ul></div></article></div></>;
}

