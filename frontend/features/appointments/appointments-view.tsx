"use client";

import { AppointmentTimelineDialog } from "../record-details/record-detail-dialog";
import { useSessionRevision } from "../setup-imports/use-operation-scope";
import { isBackendConfigured } from "../../services/brainserve-api";
import { officeToday } from "../../lib/appointments";
import { PageTitle } from "../../components/ui/page-title";
import { StatusPill } from "../../components/ui/status-pill";
import { type Appointment, type Employee, type Role } from "../../types/workspace";
import { canDecideVisit, isCeoApprovalRoute } from "./appointment-utils";
import {
    BadgeCheck,
    BriefcaseBusiness,
    CalendarDays,
    Check,
    CircleUserRound,
    IdCard,
    MessageSquare,
    Plus,
    Search,
    Send,
    ShieldCheck,
    UserCog,
    Users,
    X,
} from "lucide-react";
import { useState } from "react";

export function AppointmentsView({
                              role,
                              appointments,
                              onCreate,
                              decideAppointment,
                              onSecurityIntake,
                              decideReceptionVisit,
                              forwardReceptionVisit,
                              currentEmployee,
                          }: {
    role: Role;
    appointments: Appointment[];
    onCreate: () => void;
    currentEmployee?: Employee;
    decideAppointment: (
        id: string,
        decision: "approve" | "reject",
    ) => Promise<void>;
    onSecurityIntake: (appointment: Appointment) => void;
    decideReceptionVisit: (
        id: string,
        decision: "verify" | "reject",
    ) => Promise<void>;
    forwardReceptionVisit: (id: string) => Promise<void>;
}) {
    const sessionRevision = useSessionRevision();
    const [history, setHistory] = useState<{ id: string; title: string; session: number } | null>(null);
    const [filter, setFilter] = useState("All");
    const [query, setQuery] = useState("");
    const todayAppointments = appointments.filter((item) =>
        item.slotStart
            ? officeToday(new Date(item.slotStart)) === officeToday()
            : item.date === "Today",
    );
    const visibleAppointments =
        role === "HR Admin"
            ? todayAppointments.filter(
                (item) =>
                    item.assignedToCurrentActor !== false &&
                    (!currentEmployee?.departmentId ||
                        item.routingDepartmentId === currentEmployee.departmentId),
            )
            : role === "Employee"
                ? currentEmployee
                    ? todayAppointments.filter(
                        (item) =>
                            item.hostEmployeeId ===
                            (currentEmployee.uuid ?? currentEmployee.id) ||
                            item.host.toLowerCase() === currentEmployee.name.toLowerCase(),
                    )
                    : isBackendConfigured
                        ? appointments
                        : []
                : todayAppointments;
    const filterSource =
        filter === "Cancelled"
            ? appointments.filter((item) => item.status === "Cancelled")
            : visibleAppointments;
    const filtered = filterSource.filter(
        (item) =>
            (filter === "All" || item.status === filter) &&
            `${item.visitor} ${item.company} ${item.host} ${item.referenceNumber ?? ""}`
                .toLowerCase()
                .includes(query.toLowerCase()),
    );
    const employeeCards = role === "Employee" ? filtered : [];
    return (
        <>
            {history?.session === sessionRevision && <AppointmentTimelineDialog appointmentId={history.id} title={history.title} onClose={() => setHistory(null)} />}
            <PageTitle
                eyebrow="TODAY'S APPOINTMENTS"
                title={
                    role === "Employee"
                        ? "Your appointments today"
                        : ["Team Lead", "Manager"].includes(role)
                            ? "Today’s department calendar"
                            : "Today’s visits, clearly coordinated"
                }
                detail={
                    role === "Employee"
                        ? "Visitor details forwarded by HR appear here as read-only cards while your Team Lead completes the department decision."
                        : "This operational queue shows only today’s appointments. Use Reports → Explore Records for previous dates, monthly history, custom ranges and exports."
                }
                action={
                    [
                        "Security",
                        "Reception",
                        "HR Admin",
                        "Manager",
                        "Team Lead",
                    ].includes(role) && (
                        <button className="button button-primary" onClick={onCreate}>
                            <Plus size={17} />{" "}
                            {role === "Security"
                                ? "Create walk-in"
                                : ["Manager", "Team Lead"].includes(role)
                                    ? "Request appointment"
                                    : "Register visit"}
                        </button>
                    )
                }
            />
            <div className="toolbar glass-panel">
                <div className="tab-group">
                    {[
                        "All",
                        "Awaiting Security",
                        "Awaiting Reception",
                        "Awaiting HR",
                        "Awaiting Team Lead",
                        "Awaiting Manager",
                        "Awaiting CEO",
                        "Approved",
                        "Checked in",
                        "Cancelled",
                    ].map((item) => (
                        <button
                            key={item}
                            className={filter === item ? "active" : ""}
                            onClick={() => setFilter(item)}
                        >
                            {item}
                        </button>
                    ))}
                </div>
                <div className="toolbar-search">
                    <Search size={17} />
                    <input
                        value={query}
                        onChange={(event) => setQuery(event.target.value)}
                        placeholder="Search appointments"
                    />
                </div>
            </div>
            {role === "Employee" && (
                <section
                    className="employee-visitor-cards"
                    aria-label="Visitors coming to meet you"
                >
                    {employeeCards.map((item) => {
                        const securityComplete =
                            Boolean(item.securityIntakeAt) ||
                            !["Pending", "Awaiting Security"].includes(item.status);
                        const receptionComplete =
                            Boolean(item.receptionVerifiedAt) ||
                            !["Pending", "Awaiting Security", "Awaiting Reception"].includes(
                                item.status,
                            );
                        const hrComplete =
                            Boolean(item.hrDecisionAt) ||
                            [
                                "Awaiting Team Lead",
                                "Approved",
                                "Checked in",
                                "Completed",
                                "Rejected",
                            ].includes(item.status);
                        const teamLeadComplete =
                            Boolean(item.teamLeadDecisionAt) ||
                            ["Approved", "Checked in", "Completed", "Rejected"].includes(
                                item.status,
                            );
                        const routeMessage =
                            item.status === "Awaiting Team Lead"
                                ? "HR forwarded this visitor to you. Your department Team Lead is reviewing the request."
                                : item.status === "Approved" || item.status === "Checked in"
                                    ? "The department decision is complete. Prepare to receive this visitor."
                                    : item.status === "Rejected"
                                        ? "This visit was declined. No visitor access should be expected."
                                        : item.status === "Awaiting HR"
                                            ? "Reception verified the visitor. HR review is pending."
                                            : item.status === "Awaiting Reception"
                                                ? "Security recorded the arrival. Reception verification is pending."
                                                : "The visitor request is moving through the BrainServe arrival workflow.";
                        return (
                            <article
                                className="employee-visitor-card glass-panel"
                                key={`employee-card-${item.id}`}
                            >
                                <header>
                                    <span className="avatar">{item.initials}</span>
                                    <span>
                    <small>VISITOR COMING TO MEET YOU</small>
                    <h2>{item.arrivalVisitorName ?? item.visitor}</h2>
                    <p>{item.company}</p>
                  </span>
                                    <StatusPill status={item.status} />
                                </header>
                                <div className="employee-visitor-purpose">
                                    <BriefcaseBusiness size={18} />
                                    <span>
                    <small>Purpose of visit</small>
                    <strong>{item.arrivalPurpose ?? item.purpose}</strong>
                  </span>
                                </div>
                                <div className="employee-visitor-facts">
                  <span>
                    <CalendarDays size={15} />
                    <small>Schedule</small>
                    <strong>
                      {item.date} · {item.time}
                    </strong>
                  </span>
                                    <span>
                    <CircleUserRound size={15} />
                    <small>Contact</small>
                    <strong>
                      {item.visitorEmail || "Not provided"}
                        {item.visitorPhone ? ` · ${item.visitorPhone}` : ""}
                    </strong>
                  </span>
                                    <span>
                    <IdCard size={15} />
                    <small>Reference</small>
                    <strong>{item.referenceNumber ?? item.id}</strong>
                  </span>
                                </div>
                                <div className="employee-visitor-route">
                  <span className={securityComplete ? "done" : ""}>
                    <ShieldCheck size={14} />
                    Security
                  </span>
                                    <i />
                                    <span className={receptionComplete ? "done" : ""}>
                    <BadgeCheck size={14} />
                    Reception
                  </span>
                                    <i />
                                    <span className={hrComplete ? "done" : ""}>
                    <UserCog size={14} />
                    HR
                  </span>
                                    <i />
                                    <span className={teamLeadComplete ? "done" : ""}>
                    <Users size={14} />
                    Team Lead
                  </span>
                                </div>
                                <div
                                    className={`employee-visitor-message status-${item.status.toLowerCase().replaceAll(" ", "-")}`}
                                >
                                    <MessageSquare size={16} />
                                    <span>
                    <strong>
                      {item.status === "Awaiting Team Lead"
                          ? "Forwarded by HR"
                          : "Workflow update"}
                    </strong>
                    <small>{routeMessage}</small>
                  </span>
                                </div>
                            </article>
                        );
                    })}
                    {employeeCards.length === 0 && (
                        <div className="empty-state employee-card-empty">
                            <CalendarDays size={28} />
                            <strong>No visitors are assigned to you</strong>
                            <small>
                                Only appointments linked to your employee profile appear here.
                            </small>
                        </div>
                    )}
                </section>
            )}
            <div className="data-table glass-panel">
                <div className="table-head">
                    <span>Visitor</span>
                    <span>Visit</span>
                    <span>Host</span>
                    <span>Schedule</span>
                    <span>Status</span>
                    <span>Action</span>
                </div>
                {filtered.map((item) => (
                    <div className="table-row" key={item.id}>
                        <div className="person-cell">
                            <span className="avatar">{item.initials}</span>
                            <span>
                <strong>{item.arrivalVisitorName ?? item.visitor}</strong>
                <small>
                  {item.visitorEmail
                      ? `${item.visitorEmail} · ${item.visitorPhone}`
                      : item.arrivalVisitorName
                          ? `Booked as ${item.visitor}`
                          : item.company}
                </small>
              </span>
                        </div>
                        <div>
                            <strong>{item.type}</strong>
                            <small>
                                {item.arrivalPurpose ?? item.purpose}
                                {item.identityDocumentLastFour
                                    ? ` · ${item.identityDocumentType ?? "ID"} ••••${item.identityDocumentLastFour}`
                                    : ""}
                            </small>
                        </div>
                        <div>
                            <strong>{item.host}</strong>
                            <small>{item.referenceNumber ?? "BrainServe"}</small>
                        </div>
                        <div>
                            <strong>{item.date}</strong>
                            <small>
                                {item.time}
                                {item.createdAt
                                    ? ` · requested ${new Date(item.createdAt).toLocaleString("en-IN", { dateStyle: "short", timeStyle: "short" })}`
                                    : ""}
                            </small>
                        </div>
                        <StatusPill status={item.status} />
                        <div className="row-actions">
                            {isBackendConfigured && <button type="button" className="button" onClick={() => setHistory({ id: item.id, title: item.referenceNumber ?? item.visitor, session: sessionRevision })}>History</button>}
                            {role === "Security" && item.status === "Awaiting Security" && (
                                <button
                                    className="button button-approve"
                                    onClick={() => onSecurityIntake(item)}
                                >
                                    <IdCard size={15} /> Record arrival
                                </button>
                            )}
                            {role === "Reception" && item.status === "Awaiting Reception" && (
                                <>
                                    <button
                                        className="button button-approve"
                                        onClick={() => void decideReceptionVisit(item.id, "verify")}
                                    >
                                        <Check size={15} /> Verify & route
                                    </button>
                                    <button
                                        className="icon-button reject"
                                        onClick={() => void decideReceptionVisit(item.id, "reject")}
                                        title="Reject arrival"
                                    >
                                        <X size={16} />
                                    </button>
                                </>
                            )}
                            {role === "Reception" &&
                                item.status === "Approved" &&
                                !item.receptionForwardedAt &&
                                (["HR visit", "Interview"].includes(item.type) ||
                                    isCeoApprovalRoute(item)) && (
                                    <button
                                        className="button button-primary"
                                        onClick={() => void forwardReceptionVisit(item.id)}
                                    >
                                        <Send size={15} /> Forward to{" "}
                                        {isCeoApprovalRoute(item) ? "CEO" : "HR"} cabin
                                    </button>
                                )}
                            {role === "Reception" && item.receptionForwardedAt && (
                                <span className="status-pill status-approved">
                  <span />
                  Forwarded
                </span>
                            )}
                            {canDecideVisit(role, item) && (
                                <>
                                    <button
                                        className="icon-button approve"
                                        onClick={() => void decideAppointment(item.id, "approve")}
                                        aria-label={
                                            role === "Manager"
                                                ? "Approve and send to CEO"
                                                : role === "CEO"
                                                    ? "Give final CEO approval"
                                                    : `${role} approve`
                                        }
                                        title={
                                            role === "Manager"
                                                ? "Approve and send to CEO"
                                                : role === "CEO"
                                                    ? "Give final CEO approval"
                                                    : `${role} approve`
                                        }
                                    >
                                        <Check size={16} />
                                    </button>
                                    <button
                                        className="icon-button reject"
                                        onClick={() => void decideAppointment(item.id, "reject")}
                                        title={`${role} reject`}
                                    >
                                        <X size={16} />
                                    </button>
                                </>
                            )}
                        </div>
                    </div>
                ))}
                {filtered.length === 0 && (
                    <div className="empty-state">
                        <Search size={28} />
                        <strong>No matching appointments today</strong>
                        <small>
                            Change the status or search, or use Reports → Explore Records for
                            previous and monthly data.
                        </small>
                    </div>
                )}
            </div>
        </>
    );
}

