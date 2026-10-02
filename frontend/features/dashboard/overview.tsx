"use client";

import { PageTitle } from "../../components/ui/page-title";
import { StatusPill } from "../../components/ui/status-pill";
import { type Appointment, type DashboardMetrics, type Role, type View } from "../../types/workspace";
import { needsAppointmentAction } from "../appointments/appointment-utils";
import { DashboardFreshness } from "./dashboard-freshness-label";
import { type DashboardFreshnessMetadata } from "./dashboard-freshness";
import {
    ArrowRight,
    BriefcaseBusiness,
    CalendarDays,
    Check,
    CheckCircle2,
    ChevronRight,
    CircleUserRound,
    Clock3,
    DoorOpen,
    LogIn,
    MoreHorizontal,
    Plus,
    Users,
    X,
} from "lucide-react";

export function Overview({ role, appointments, metrics, onNavigate, onRegister, decideAppointment }: { role: Role;
    appointments: Appointment[]; metrics: DashboardMetrics & DashboardFreshnessMetadata; onNavigate: (view: View) => void; onRegister: () => void;
    decideAppointment: (id: string, decision: "approve" | "reject") => Promise<void> }) {
    const context = role === "Reception"
        ? { title: "Reception command centre", detail: "Verify Security arrivals, route approvals and coordinate check-ins." }
        : role === "Security"
            ? { title: "Security arrival desk", detail: "Capture who arrived and why, then notify Reception through BrainServe Connect." }
            : role === "Team Lead"
                ? { title: "Department delivery centre", detail: "Assign work, review completed tasks and keep HR informed of delivery performance." }
                : role === "Manager"
                    ? { title: "Department executive desk", detail: "Review CEO visitors routed by Reception and coordinate the assigned department." }
                    : role === "CEO"
                        ? { title: "Executive governance centre", detail: "Complete final CEO-visit decisions and oversee company-wide work and account approvals." }
                        : role === "HR Admin"
                            ? { title: "People operations centre", detail: "Review department visits, staff activity and governed account requests." }
                            : role === "Employee"
                                ? { title: "Your workday, clearly organized", detail: "Start assigned work, submit completion evidence and acknowledge Team Lead decisions." }
                                : { title: "Your appointment workspace", detail: "Review requests, manage your time and prepare for today’s visitors." };
    const pending = appointments.filter((item) => needsAppointmentAction(role, item));
    const queueTitle = role === "Security" ? "Security intake queue" : role === "Reception" ? "Reception verification queue"
        : role === "Manager" ? "CEO visitor approval queue" : role === "CEO" ? "CEO approval queue"
            : role === "Team Lead" ? "Department approval queue" : "HR approval queue";
    const isRoutingRole = role === "Security" || role === "Reception";
    const isWorkRole = role === "Employee" || role === "Team Lead";
    const primaryView: View = isWorkRole ? "work" : "appointments";
    return <>
        <PageTitle eyebrow={`${new Date().toLocaleDateString("en-IN", { weekday: "long", day: "numeric", month: "long", year: "numeric" }).toUpperCase()} · ${role.toUpperCase()}`}
                   title={context.title} detail={context.detail}
                   action={<button className="button button-primary" onClick={() => role === "Reception" ? onRegister() : onNavigate(primaryView)}>
                       <Plus size={17} />{role === "Reception" ? "Register walk-in" : isWorkRole ? "Open work board" : "Open appointments"}
                   </button>} />
        <DashboardFreshness summary={metrics} />
        <section className="metric-grid">
            <article className="metric-card glass-panel"><div><span>Active visits</span><strong>{metrics.activeVisits}</strong><small>Approved or currently in progress</small></div><span className="metric-icon"><CalendarDays size={22} /></span></article>
            <article className="metric-card glass-panel"><div><span>In workflow</span><strong>{metrics.awaitingApproval}</strong><small>{pending.length} require your action</small></div><span className="metric-icon"><Clock3 size={22} /></span></article>
            <article className="metric-card glass-panel"><div><span>Currently inside</span><strong>{metrics.visitorsInside}</strong><small>Live access records</small></div><span className="metric-icon"><DoorOpen size={22} /></span></article>
            <article className="metric-card glass-panel"><div><span>Active employees</span><strong>{metrics.activeEmployees}</strong><small>{metrics.totalEmployees} total profiles</small></div><span className="metric-icon"><Users size={22} /></span></article>
            <article className="metric-card glass-panel"><div><span>Arrived today</span><strong>{metrics.arrivedVisits}</strong><small>Security intake recorded</small></div><span className="metric-icon"><LogIn size={22} /></span></article>
        </section>
        <section className="dashboard-grid">
            {isWorkRole ? <article className="panel glass-panel work-overview-panel">
                <div className="panel-heading"><div><span>DEPARTMENT DELIVERY</span><h2>Your work board</h2><p>Track assigned work, completion evidence, Team Lead decisions and acknowledgement.</p></div><BriefcaseBusiness size={22} /></div>
                <div className="work-overview-flow"><span>Assigned</span><i /><span>In progress</span><i /><span>Completed</span><i /><span>Approved</span></div>
                <button className="button button-primary" onClick={() => onNavigate("work")}>Open work board <ArrowRight size={16} /></button>
            </article> : <article className="panel glass-panel schedule-panel">
                <div className="panel-heading"><div><span>LIVE SCHEDULE</span><h2>Today at BrainServe Connect</h2></div><button className="text-button" onClick={() => onNavigate("appointments")}>View queue <ChevronRight size={16} /></button></div>
                <div className="schedule-list">{appointments.slice(0, 4).map((item) => <div key={item.id} className="schedule-row"><time>{item.time.replace(" ", "\n")}</time><i className={`line status-dot-${item.status.toLowerCase().replaceAll(" ", "-")}`} /><span className="avatar">{item.initials}</span><div><strong>{item.visitor}</strong><small>{item.purpose} · with {item.host}</small></div><StatusPill status={item.status} /><button className="icon-button" onClick={() => onNavigate("appointments")} aria-label={`Open ${item.visitor}'s appointment`}><MoreHorizontal size={18} /></button></div>)}</div>
            </article>}
            <article className="panel glass-panel approval-panel">
                <div className="panel-heading"><div><span>NEEDS YOUR ATTENTION</span><h2>{queueTitle}</h2></div><b>{pending.length}</b></div>
                {pending.map((item) => <div className="approval-item" key={item.id}><div className="approval-person"><span className="avatar">{item.initials}</span><span><strong>{item.visitor}</strong><small>{item.type} · {item.company}</small></span></div><p>“{item.arrivalPurpose ?? item.purpose}”</p><div className="approval-meta"><span><Clock3 size={15} /> {item.date}, {item.time}</span><span><CircleUserRound size={15} /> {item.host}</span></div>{item.status === "Awaiting Manager" && <div className="approval-route"><CheckCircle2 size={15} /> Security and Reception verified · waiting for the assigned Manager</div>}{item.status === "Awaiting CEO" && <div className="approval-route"><CheckCircle2 size={15} /> Security and Reception verified · Manager approved · waiting for CEO final decision</div>}{item.status === "Awaiting Team Lead" && <div className="approval-route"><CheckCircle2 size={15} /> HR verified · waiting for your department decision</div>}{isRoutingRole ? <div className="approval-actions"><button className="button button-primary" onClick={() => onNavigate("appointments")}><ArrowRight size={16} /> Open {role === "Security" ? "intake" : "verification"}</button></div> : <div className="approval-actions"><button className="button button-reject" onClick={() => void decideAppointment(item.id, "reject")}><X size={16} /> Decline</button><button className="button button-approve" onClick={() => void decideAppointment(item.id, "approve")}><Check size={16} /> {role === "CEO" ? "Final CEO approval" : role === "Manager" ? "Approve & send to CEO" : role === "Team Lead" ? "Team Lead approve" : "HR approve"}</button></div>}</div>)}
                {pending.length === 0 && <div className="empty-state"><CheckCircle2 size={28} /><strong>All caught up</strong><small>No appointments require your workflow stage.</small></div>}
            </article>
        </section>
    </>;
}
