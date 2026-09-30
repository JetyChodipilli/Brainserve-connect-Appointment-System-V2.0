"use client";

import { ApiError, brainServeApi, type CompanyProfile, isBackendConfigured, type PublicDirectoryEmployee } from "../../lib/api";
import {
    appointmentDates,
    appointmentTypeCode,
    type AvailableSlot,
    dateCard,
    fallbackSlots,
    formatOfficeDate,
    formatOfficeTime,
    hostCategoriesForVisit,
    hostCategoryForVisit,
    newDemoReference,
    officeToday,
    type PublicAppointment,
    type PublicHost,
} from "../../lib/appointments";
import { readDemoAccounts } from "../../preview/accounts";
import { readDemoAppointments, writeDemoAppointments } from "../../preview/appointments";
import { readDemoDepartments } from "../../preview/directory";
import { initialDepartments, initialEmployees } from "../../preview/fixtures/workspace";
import { DEMO_LAST_REFERENCE_KEY } from "../../preview/storage-keys";
import { type DemoAppointment } from "../../preview/types";
import { Logo } from "../../shared/components/logo";
import { type Department, type Screen } from "../../shared/types/workspace";
import { newClientId } from "../../shared/utils/ids";
import {
    ArrowLeft,
    ArrowRight,
    Bell,
    BriefcaseBusiness,
    Building2,
    CalendarDays,
    Check,
    CircleUserRound,
    Clock3,
    FileText,
    IdCard,
    Mail,
    Search,
    ShieldCheck,
    Users,
    X,
} from "lucide-react";
import { type FormEvent, useCallback, useEffect, useMemo, useState } from "react";

export function BookingFlow({ onNavigate }: { onNavigate: (screen: Screen) => void }) {
    const [step, setStep] = useState(1);
    const [submission, setSubmission] = useState<PublicAppointment | null>(null);
    const [visitType, setVisitType] = useState("Employee visit");
    const dates = useMemo(() => appointmentDates(8, visitType === "Emergency visit"), [visitType]);
    const [visitDate, setVisitDate] = useState(() => appointmentDates(8, false)[0]);
    const [hosts, setHosts] = useState<PublicHost[]>([]);
    const [publicDepartments, setPublicDepartments] = useState<Department[]>(() =>
        isBackendConfigured ? [] : initialDepartments,
    );
    const [hostId, setHostId] = useState("");
    const [routingDepartmentId, setRoutingDepartmentId] = useState("");
    const [requestedEmployeeId, setRequestedEmployeeId] = useState("");
    const [requestedEmployees, setRequestedEmployees] = useState<PublicDirectoryEmployee[]>([]);
    const [employeeQuery, setEmployeeQuery] = useState("");
    const [employeesLoading, setEmployeesLoading] = useState(false);
    const [slots, setSlots] = useState<AvailableSlot[]>([]);
    const [slot, setSlot] = useState<AvailableSlot | null>(null);
    const [visitor, setVisitor] = useState({ name: "", email: "", phone: "", company: "", purpose: "" });
    const [consentVersion, setConsentVersion] = useState(() => isBackendConfigured ? "" : "2026.1");
    const [consent, setConsent] = useState(false);
    const [busy, setBusy] = useState(false);
    const [error, setError] = useState("");
    const steps = ["Visit", "Schedule", "Your details", "Review"];
    const requiredHostCategory = hostCategoryForVisit(visitType);
    const requiredHostCategories = useMemo(() => hostCategoriesForVisit(visitType), [visitType]);
    const eligibleHosts = useMemo(() => hosts.filter((host) => requiredHostCategories.includes(host.category)
            && (!routingDepartmentId || host.category === "CEO" || host.departmentId === routingDepartmentId)),
        [hosts, requiredHostCategories, routingDepartmentId]);
    const routingDepartments = useMemo(() => publicDepartments.filter((department) => department.active)
        .sort((left, right) => left.name.localeCompare(right.name))
        .map((department) => [department.id, department.name] as const), [publicDepartments]);
    const selectedHost = hosts.find((host) => host.id === hostId);

    const loadRequestedEmployees = useCallback(async (departmentId: string, query = "") => {
        if (!departmentId) { setRequestedEmployees([]); return; }
        setEmployeesLoading(true);
        try {
            const next = isBackendConfigured
                ? (await brainServeApi.publicEmployees(departmentId, query)).content
                : initialEmployees.filter((employee) => employee.status === "Active"
                    && (employee.departmentId ?? employee.department) === departmentId
                    && (!query || `${employee.name} ${employee.id}`.toLowerCase().includes(query.toLowerCase())))
                    .slice(0, 25).map((employee) => ({
                        id: employee.uuid ?? employee.id, displayName: employee.name,
                        designation: employee.role, departmentId,
                    }));
            setRequestedEmployees(next);
            setRequestedEmployeeId((current) => next.some((item) => item.id === current) ? current : "");
        } catch (reason) {
            setRequestedEmployees([]);
            setError(reason instanceof Error ? reason.message : "The employee directory could not be loaded.");
        } finally { setEmployeesLoading(false); }
    }, []);

    useEffect(() => {
        let active = true;
        const load = async () => {
            try {
                const [result, departmentResult, companyProfile]: [PublicHost[], Department[], CompanyProfile] = isBackendConfigured
                    ? await Promise.all([brainServeApi.publicHosts(), brainServeApi.publicDepartments(), brainServeApi.companyProfile()])
                    : [(() => {
                        const employeeHosts: PublicHost[] = initialEmployees.filter((employee) => employee.status === "Active").map((employee) => ({
                            id: employee.id, displayName: employee.name, designation: employee.role,
                            departmentId: employee.department === "Human Resources" ? "TECH" : employee.departmentId ?? employee.department,
                            departmentName: employee.department === "Human Resources" ? "Technology" : employee.department,
                            category: employee.id === "BSPL-IT-0042" ? "TEAM_LEAD"
                                : employee.department === "Human Resources" ? "HR" : "EMPLOYEE",
                        }));
                        const accountChiefExecutives: PublicHost[] = readDemoAccounts()
                            .filter((account) => account.status === "ACTIVE" && account.role === "ROLE_CEO")
                            .map((account) => ({ id: account.id, displayName: account.fullName,
                                designation: "Chief Executive Officer", departmentId: "EXEC", departmentName: "Executive Office", category: "CEO" }));
                        const chiefExecutives = accountChiefExecutives.length ? accountChiefExecutives : [{
                            id: "00000000-0000-0000-0000-00000000ce00", displayName: "BrainServe CEO",
                            designation: "Chief Executive Officer", departmentId: "EXEC", departmentName: "Executive Office", category: "CEO" as const,
                        }];
                        return [...employeeHosts, ...chiefExecutives];
                    })(), readDemoDepartments(), { name: "BrainServe Connect", emailDomain: "brainserve.in",
                        hqAddress: "Hyderabad, Telangana, India", supportEmail: "support@brainserve.in",
                        consentVersion: "2026.1" }];
                if (!active) return;
                setHosts(result);
                setPublicDepartments(departmentResult);
                setConsentVersion(companyProfile.consentVersion);
                const categories = hostCategoriesForVisit(visitType);
                const initialEligible = result.filter((host) => categories.includes(host.category));
                const initialDepartment = visitType === "CEO visit"
                    ? departmentResult.find((department) => department.active)?.id ?? ""
                    : result.find((host) => host.category === "HR")?.departmentId
                    ?? departmentResult.find((department) => department.active)?.id ?? "";
                setRoutingDepartmentId(initialDepartment);
                if (visitType === "Employee visit") {
                    setRequestedEmployeeId("");
                    setHostId(result.find((host) => host.category === "HR" && host.departmentId === initialDepartment)?.id ?? "");
                } else setHostId(initialEligible[0]?.id ?? "");
            } catch (reason) {
                if (active) setError(reason instanceof ApiError ? reason.message : "Available hosts could not be loaded.");
            }
        };
        void load();
        return () => { active = false; };
    }, [visitType]);

    useEffect(() => {
        if (visitType !== "Employee visit" || !routingDepartmentId) return;
        const timer = window.setTimeout(() => void loadRequestedEmployees(routingDepartmentId), 0);
        return () => window.clearTimeout(timer);
    }, [loadRequestedEmployees, routingDepartmentId, visitType]);

    useEffect(() => {
        if (!hostId || !visitDate) return;
        let active = true;
        const load = async () => {
            try {
                const result = isBackendConfigured
                    ? await brainServeApi.availableSlots(hostId, visitDate, appointmentTypeCode(visitType))
                    : fallbackSlots(visitDate);
                if (!active) return;
                setSlots(result);
                setSlot(result[0] ?? null);
            } catch (reason) {
                if (active) { setSlots([]); setError(reason instanceof ApiError ? reason.message : "Available slots could not be loaded."); }
            }
        };
        void load();
        return () => { active = false; };
    }, [hostId, visitDate, visitType]);

    const chooseVisitType = (nextType: string) => {
        setVisitType(nextType);
        const categories = hostCategoriesForVisit(nextType);
        const matchingHosts = hosts.filter((host) => categories.includes(host.category));
        const department = nextType === "CEO visit"
            ? routingDepartments[0]?.[0] ?? ""
            : hosts.find((host) => host.category === "HR")?.departmentId ?? routingDepartments[0]?.[0] ?? "";
        setRoutingDepartmentId(department);
        if (nextType === "Employee visit") {
            setRequestedEmployeeId("");
            setEmployeeQuery("");
            setHostId(hosts.find((host) => host.category === "HR" && host.departmentId === department)?.id ?? "");
        } else {
            setRequestedEmployeeId(""); setRequestedEmployees([]); setEmployeeQuery("");
            setHostId(matchingHosts[0]?.id ?? "");
        }
        setVisitDate(appointmentDates(8, nextType === "Emergency visit")[0]);
        setSlot(null); setSlots([]); setError("");
    };

    const continueFlow = async () => {
        setError("");
        if (step === 1 && (!hostId || !routingDepartmentId || visitor.purpose.trim().length < 5
            || (visitType === "Employee visit" && (!routingDepartmentId || !requestedEmployeeId)))) {
            setError("Select an available host and enter a clear purpose of at least 5 characters."); return;
        }
        if (step === 2 && !slot) { setError("Select an available appointment slot."); return; }
        if (step === 3) {
            if (visitor.name.trim().length < 2 || !/^\S+@\S+\.\S+$/.test(visitor.email)
                || visitor.phone.replace(/\D/g, "").length < 8 || !consent) {
                setError("Enter a valid name, email and mobile number, then accept the privacy notice."); return;
            }
        }
        if (step < 4) { setStep(step + 1); return; }
        if (!slot || !selectedHost) return;
        setBusy(true);
        try {
            const payload = {
                type: appointmentTypeCode(visitType), visitorName: visitor.name.trim(), visitorEmail: visitor.email.trim(),
                visitorPhone: visitor.phone.trim(), visitorCompany: visitor.company.trim() || null,
                hostEmployeeId: selectedHost.id,
                // Leadership visits belong to the department whose assigned Manager reviews them.
                routingDepartmentId: routingDepartmentId || selectedHost.departmentId,
                requestedEmployeeId: visitType === "Employee visit" ? requestedEmployeeId : null,
                slotStart: slot.start, slotEnd: slot.end, purpose: visitor.purpose.trim(),
            };
            let result: PublicAppointment;
            if (isBackendConfigured) {
                const idempotencyKey = newClientId();
                await brainServeApi.registerVisitor({
                    name: visitor.name.trim(), email: visitor.email.trim(), phone: visitor.phone.trim(),
                    company: visitor.company.trim() || null, governmentId: null, consentVersion,
                }, idempotencyKey);
                result = await brainServeApi.createAppointment(payload, idempotencyKey);
            } else {
                const demoResult: DemoAppointment = {
                    id: newClientId(),
                    referenceNumber: newDemoReference(), type: payload.type, status: "PENDING_SECURITY_INTAKE",
                    hostReference: selectedHost.id, slotStart: slot.start, slotEnd: slot.end,
                    hostCategory: selectedHost.category,
                    visitorDisplayName: visitor.name.trim(), visitorName: visitor.name.trim(),
                    visitorEmail: visitor.email.trim(), visitorPhone: visitor.phone.trim(),
                    visitorCompany: visitor.company.trim() || null, purpose: visitor.purpose.trim(),
                    routingDepartmentId: payload.routingDepartmentId,
                    requestedEmployeeId: payload.requestedEmployeeId,
                    createdAt: new Date().toISOString(),
                };
                writeDemoAppointments([...readDemoAppointments(), demoResult]);
                result = demoResult;
            }
            if (!isBackendConfigured) window.localStorage.setItem(DEMO_LAST_REFERENCE_KEY, result.referenceNumber);
            setSubmission(result);
        } catch (reason) {
            setError(reason instanceof ApiError ? reason.message : "Your appointment request could not be submitted.");
        } finally { setBusy(false); }
    };

    const verifyOtp = async (event: FormEvent<HTMLFormElement>) => {
        event.preventDefault();
        if (!submission) return;
        setBusy(true); setError("");
        const data = new FormData(event.currentTarget);
        try {
            setSubmission(await brainServeApi.verifyAppointment(submission.referenceNumber, String(data.get("otp"))));
        } catch (reason) { setError(reason instanceof ApiError ? reason.message : "The OTP could not be verified."); }
        finally { setBusy(false); }
    };

    if (submission) {
        const awaitingVerification = submission.status === "PENDING_VERIFICATION";

        return (
            <main className="flow-page">
                <header className="flow-header">
                    <Logo />

                    <button
                        type="button"
                        className="icon-button"
                        onClick={() => onNavigate("welcome")}
                        aria-label="Close appointment confirmation"
                    >
                        <X size={20} />
                    </button>
                </header>

                <section
                    className={`confirmation-card glass-panel ${
                        awaitingVerification ? "is-pending" : "is-verified"
                    }`}
                    aria-labelledby="appointment-confirmation-title"
                >
                    <div className="confirmation-status-icon" aria-hidden="true">
                        {awaitingVerification
                            ? <Mail size={29} />
                            : <Check size={31} />
                        }
                    </div>

                    <span className="eyebrow">
                    {awaitingVerification
                        ? "Email verification"
                        : "Request verified"
                    }
                </span>

                    <h1 id="appointment-confirmation-title">
                        {awaitingVerification
                            ? "Verify your appointment"
                            : "Your request is ready for review"
                        }
                    </h1>

                    <p className="confirmation-lead" role="status">
                        {awaitingVerification
                            ? "Enter the six-digit code sent to your email. Approval begins after verification."
                            : "Your appointment has been verified. We’ll notify you when its approval status changes."
                        }
                    </p>

                    <div className="confirmation-reference">
                        <span>Tracking reference</span>
                        <strong>{submission.referenceNumber}</strong>
                    </div>

                    <div
                        className="confirmation-grid"
                        aria-label="Appointment summary"
                    >
                        <div>
                            <CalendarDays size={20} aria-hidden="true" />
                            <span>
                            <small>Date</small>
                            <strong>{formatOfficeDate(submission.slotStart)}</strong>
                        </span>
                        </div>

                        <div>
                            <Clock3 size={20} aria-hidden="true" />
                            <span>
                            <small>Time</small>
                            <strong>{formatOfficeTime(submission.slotStart)}</strong>
                        </span>
                        </div>

                        <div>
                            <CircleUserRound size={20} aria-hidden="true" />
                            <span>
                            <small>Host</small>
                            <strong>
                                {selectedHost?.displayName ?? "BrainServe host"}
                            </strong>
                        </span>
                        </div>

                        <div>
                            <Building2 size={20} aria-hidden="true" />
                            <span>
                            <small>Location</small>
                            <strong>Hyderabad HQ</strong>
                        </span>
                        </div>
                    </div>

                    {awaitingVerification ? (
                        <>
                            <form
                                className="confirmation-otp-form"
                                onSubmit={verifyOtp}
                            >
                                <div className="confirmation-otp-heading">
                                    <label htmlFor="appointment-verification-otp">
                                        Verification code
                                    </label>

                                    <small id="appointment-verification-help">
                                        Enter the six-digit code from your email.
                                    </small>
                                </div>

                                <div className="confirmation-otp-controls">
                                    <input
                                        id="appointment-verification-otp"
                                        name="otp"
                                        type="text"
                                        inputMode="numeric"
                                        autoComplete="one-time-code"
                                        enterKeyHint="done"
                                        pattern="[0-9]{6}"
                                        minLength={6}
                                        maxLength={6}
                                        aria-describedby="appointment-verification-help"
                                        aria-invalid={Boolean(error)}
                                        autoFocus
                                        required
                                    />

                                    <button
                                        type="submit"
                                        className="button button-primary"
                                        disabled={busy}
                                    >
                                        {busy ? "Verifying…" : "Verify appointment"}
                                    </button>
                                </div>

                                {error && (
                                    <div
                                        className="confirmation-form-error"
                                        role="alert"
                                    >
                                        {error}
                                    </div>
                                )}
                            </form>

                            <button
                                type="button"
                                className="confirmation-home-link"
                                onClick={() => onNavigate("welcome")}
                            >
                                Return to home
                            </button>
                        </>
                    ) : (
                        <div className="confirmation-actions">
                            <button
                                type="button"
                                className="button button-primary button-large"
                                onClick={() => onNavigate("track")}
                            >
                                Track appointment
                                <ArrowRight size={17} aria-hidden="true" />
                            </button>

                            <button
                                type="button"
                                className="button button-quiet button-large"
                                onClick={() => onNavigate("welcome")}
                            >
                                Return to home
                            </button>
                        </div>
                    )}
                </section>
            </main>
        );
    }

    return (
        <main className="flow-page">
            <header className="flow-header"><Logo /><button className="icon-button" onClick={() => onNavigate("welcome")} aria-label="Close"><X size={20} /></button></header>
            <div className="flow-shell">
                <aside className="stepper glass-panel">
                    <span className="eyebrow">Book an appointment</span>
                    <h2>Plan your visit</h2>
                    <p>Complete these four simple steps. Your information remains protected.</p>
                    <div className="step-list">
                        {steps.map((label, index) => <div key={label} className={step === index + 1 ? "active" : step > index + 1 ? "complete" : ""}><span>{step > index + 1 ? <Check size={15} /> : index + 1}</span><div><strong>{label}</strong><small>{["Who would you like to meet?", "Choose an available time", "Tell us who you are", "Confirm your request"][index]}</small></div></div>)}
                    </div>
                    <div className="privacy-note"><ShieldCheck size={19} /><span><strong>Your privacy matters</strong><small>Data is encrypted and used only to coordinate your visit.</small></span></div>
                </aside>

                <section className="form-card glass-panel">
                    <div className="form-heading"><span>STEP {step} OF 4</span><h1>{["Who are you visiting?", "Choose your arrival time", "Tell us about yourself", "Review your request"][step - 1]}</h1><p>{["Select a visit type and the right BrainServe host.", "Available slots are shown in India Standard Time.", "We’ll use these details for verification and visit updates.", "Check everything before sending it for approval."][step - 1]}</p></div>

                    {step === 1 && <div className="field-stack">
                        <label>Visit type</label>
                        <div className="choice-grid">{["Employee visit", "HR visit", "CEO visit", "Interview", "Client meeting", "Emergency visit"].map((type) => <button type="button" key={type} className={visitType === type ? "choice active" : "choice"} onClick={() => chooseVisitType(type)}><span>{type === "Interview" || type === "Client meeting" ? <BriefcaseBusiness size={20} /> : type === "CEO visit" ? <ShieldCheck size={20} /> : type === "Emergency visit" ? <Bell size={20} /> : <Users size={20} />}</span><strong>{type}</strong><small>{type === "Interview" ? "Candidate meeting" : type === "Client meeting" ? "Meet a department Team Lead" : type === "Emergency visit" ? "Request the next available time today" : `Meet our ${type.replace(" visit", "").toLowerCase()} team`}</small></button>)}</div>
                        <><label htmlFor="department">Routing department</label><select id="department" value={routingDepartmentId} onChange={(event) => { const department = event.target.value; setRoutingDepartmentId(department); setRequestedEmployeeId(""); setRequestedEmployees([]); setEmployeeQuery(""); const category = visitType === "Employee visit" ? "HR" : requiredHostCategories[0]; if (category !== "CEO") setHostId(hosts.find((host) => host.departmentId === department && host.category === category)?.id ?? ""); setSlot(null); setSlots([]); }}><option value="">Select a department</option>{routingDepartments.map(([id, name]) => <option key={id} value={id}>{name}</option>)}</select></>
                        {visitType === "Employee visit" && <><label htmlFor="employeeSearch">Find employee</label><div className="directory-search-row"><input id="employeeSearch" value={employeeQuery}
                                                                                                                                                               onChange={(event) => setEmployeeQuery(event.target.value)} placeholder="Name or employee ID" /><button type="button"
                                                                                                                                                                                                                                                                      className="button button-secondary" disabled={employeesLoading || !routingDepartmentId}
                                                                                                                                                                                                                                                                      onClick={() => void loadRequestedEmployees(routingDepartmentId, employeeQuery)}><Search size={15} />{employeesLoading ? "Searching…" : "Search"}</button></div>
                            <label htmlFor="requestedEmployee">Employee to meet</label><select id="requestedEmployee" value={requestedEmployeeId}
                                                                                               onChange={(event) => setRequestedEmployeeId(event.target.value)} required disabled={employeesLoading}>
                                <option value="">{employeesLoading ? "Loading department employees…" : "Select an active employee"}</option>
                                {requestedEmployees.map((employee) => <option key={employee.id} value={employee.id}>{employee.displayName} · {employee.designation}</option>)}</select></>}
                        <label htmlFor="host">{requiredHostCategory === "CEO" ? "CEO host" : "Select host"}</label>
                        <select id="host" value={hostId} onChange={(event) => { const next = hosts.find((host) => host.id === event.target.value); setHostId(event.target.value); if (next?.category !== "CEO") setRoutingDepartmentId(next?.departmentId ?? ""); setSlot(null); setSlots([]); }} disabled={!eligibleHosts.length}><option value="">{eligibleHosts.length ? "Select an active host" : `No eligible ${requiredHostCategory ?? "host"} assigned`}</option>{eligibleHosts.map((host) => <option key={host.id} value={host.id}>{host.displayName} · {host.designation}{host.category === "CEO" ? "" : ` · ${host.departmentName}`}</option>)}</select>
                        {requiredHostCategory === "CEO" && <small className="field-help">The CEO is the meeting host. The Manager assigned to the routing department verifies the request before the CEO gives final approval.</small>}
                        <label htmlFor="purpose">Purpose of visit</label><textarea id="purpose" value={visitor.purpose} onChange={(e) => setVisitor({ ...visitor, purpose: e.target.value })} placeholder="Briefly describe what you’d like to discuss" />
                    </div>}

                    {step === 2 && <div className="field-stack">
                        <label>Visit date</label>
                        <div className="date-strip">{dates.map((date) => { const card = dateCard(date); return <button type="button" className={visitDate === date ? "active" : ""} onClick={() => { setVisitDate(date); setSlot(null); setSlots([]); }} key={date}><small>{date === officeToday() ? "TODAY" : card.day}</small><strong>{card.date}</strong><small>{card.month}</small></button>; })}</div>
                        <label>Available slots</label>
                        <div className="slot-grid">{slots.map((available) => <button type="button" key={available.start} className={slot?.start === available.start ? "active" : ""} onClick={() => setSlot(available)}>{formatOfficeTime(available.start)}</button>)}</div>
                        {!slots.length && <div className="empty-state"><Clock3 size={24} /><strong>No available slots</strong><small>{visitType === "Emergency visit" ? "No future office-hour slots remain today. Choose the next working day." : "Choose another business day or host."}</small></div>}
                        <div className="info-banner"><Clock3 size={18} /><span><strong>30 minute appointment</strong><small>Past times are removed automatically. A 10 minute arrival buffer is included.</small></span></div>
                    </div>}

                    {step === 3 && <div className="field-stack two-column-fields">
                        <label>Full name<input required value={visitor.name} onChange={(e) => setVisitor({ ...visitor, name: e.target.value })} placeholder="Your full name" /></label>
                        <label>Company<input value={visitor.company} onChange={(e) => setVisitor({ ...visitor, company: e.target.value })} placeholder="Company or organization" /></label>
                        <label>Email address<input required type="email" value={visitor.email} onChange={(e) => setVisitor({ ...visitor, email: e.target.value })} placeholder="name@example.com" /></label>
                        <label>Mobile number<input required value={visitor.phone} onChange={(e) => setVisitor({ ...visitor, phone: e.target.value })} placeholder="+91 98765 43210" /></label>
                        <label className="checkbox-label full-field"><input type="checkbox" checked={consent} onChange={(event) => setConsent(event.target.checked)} /><span>I agree to the visitor privacy notice and consent to identity verification at reception.</span></label>
                    </div>}

                    {step === 4 && <div className="review-list">
                        <div><CircleUserRound size={20} /><span><small>Host</small><strong>{selectedHost?.displayName} · {selectedHost?.designation}</strong></span><button onClick={() => setStep(1)}>Edit</button></div>
                        <div><CalendarDays size={20} /><span><small>Date and time</small><strong>{slot ? `${formatOfficeDate(slot.start)} · ${formatOfficeTime(slot.start)}` : "No slot selected"}</strong></span><button onClick={() => setStep(2)}>Edit</button></div>
                        <div><IdCard size={20} /><span><small>Visitor</small><strong>{visitor.name} · {visitor.email}</strong></span><button onClick={() => setStep(3)}>Edit</button></div>
                        <div><FileText size={20} /><span><small>Visit</small><strong>{visitType} · {visitor.purpose}</strong></span></div>
                        <div className="approval-note"><Bell size={19} /><span><strong>What happens next?</strong><small>Security and Reception verify the visit, then the assigned department approver completes the review. Once approved, we’ll send your secure visitor QR pass by email.</small></span></div>
                    </div>}

                    {error && <div className="login-error" role="alert">{error}</div>}
                    <div className="form-actions"><button type="button" className="button button-secondary" onClick={() => step === 1 ? onNavigate("welcome") : setStep(step - 1)}><ArrowLeft size={17} /> Back</button><button type="button" className="button button-primary" disabled={busy} onClick={() => void continueFlow()}>{busy ? "Submitting…" : step === 4 ? "Submit request" : "Continue"}<ArrowRight size={17} /></button></div>
                </section>
            </div>
        </main>
    );
}

