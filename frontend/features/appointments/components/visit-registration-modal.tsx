"use client";

import { DraftStatus } from "../../drafts/draft-status";
import { useFormDraft, draftBlocksSubmit, draftLocksFields } from "../../drafts/use-form-draft";
import type { DraftFields } from "../../drafts/draft-session";
import { ApiError, brainServeApi, isBackendConfigured } from "../../../services/brainserve-api";
import {
    appointmentDates,
    appointmentTypeCode,
    type AvailableSlot,
    fallbackSlots,
    formatOfficeDate,
    formatOfficeTime,
    hostCategoriesForVisit,
    hostCategoryForVisit,
    officeDateTimeToIso,
    officeToday,
    type PublicHost,
} from "../../../lib/appointments";
import { useModalDialog } from "../../../hooks/use-modal-dialog";
import { type Department, type Employee, type ReceptionVisitInput } from "../../../types/workspace";
import { visitorInitials } from "../appointment-utils";
import { ArrowRight, BadgeCheck, IdCard, MessageSquare, Search, ShieldCheck, UserCog, X } from "lucide-react";
import { type FormEvent, useCallback, useEffect, useMemo, useRef, useState } from "react";

export function VisitRegistrationModal({
                                    employees,
                                    departments,
                                    securityMode,
                                    onClose,
                                    onSubmit,
                                    accountScope = "authenticated-visit",
                                    onDraftSubmitted,
                                }: {
    accountScope?: string;
    onDraftSubmitted?: () => void;
    employees: Employee[];
    departments: Department[];
    securityMode: boolean;
    onClose: () => void;
    onSubmit: (input: ReceptionVisitInput) => Promise<void>;
}) {
    const formRef = useRef<HTMLFormElement>(null);
    const [visitorFields, setVisitorFields] = useState<DraftFields>({ visitorName: "", visitorEmail: "", visitorPhone: "", visitorCompany: "", purpose: "" });
    const [busy, setBusy] = useState(false);
    const [error, setError] = useState("");
    const [visitType, setVisitType] = useState("Interview");
    const [hostEmployeeId, setHostEmployeeId] = useState("");
    const [routingDepartmentId, setRoutingDepartmentId] = useState("");
    const [requestedEmployeeId, setRequestedEmployeeId] = useState("");
    const [directoryEmployees, setDirectoryEmployees] = useState<Employee[]>([]);
    const [employeeQuery, setEmployeeQuery] = useState("");
    const [employeeDirectoryLoading, setEmployeeDirectoryLoading] =
        useState(false);
    const dates = useMemo(
        () => appointmentDates(8, visitType === "Emergency visit"),
        [visitType],
    );
    const [visitDate, setVisitDate] = useState(
        () => appointmentDates(8, false)[0],
    );
    const [slots, setSlots] = useState<AvailableSlot[]>([]);
    const [slotStart, setSlotStart] = useState("");
    const [loadingSlots, setLoadingSlots] = useState(false);
    const draft = useFormDraft("VISIT_INTAKE", securityMode ? "security" : "reception", accountScope, true, { ...visitorFields, visitType, hostEmployeeId, routingDepartmentId, requestedEmployeeId, visitDate });
    const closeSafely = () => { if (!busy && (!["unsaved", "saving", "offline", "unknown", "conflict"].includes(draft.state.phase) || window.confirm("Close this form? Changes that are not saved remain only in this open form."))) onClose(); };
    useModalDialog(closeSafely);
    useEffect(() => {
        const changed = () => { setVisitorFields({ visitorName: "", visitorEmail: "", visitorPhone: "", visitorCompany: "", purpose: "" }); setHostEmployeeId(""); setRoutingDepartmentId(""); setRequestedEmployeeId(""); setSlots([]); setSlotStart(""); formRef.current?.reset(); };
        window.addEventListener("brainserve:auth-session-changed", changed); window.addEventListener("brainserve:auth-session-expired", changed);
        return () => { window.removeEventListener("brainserve:auth-session-changed", changed); window.removeEventListener("brainserve:auth-session-expired", changed); };
    }, []);
    useEffect(() => {
        if (!["unsaved", "saving", "offline", "unknown", "conflict"].includes(draft.state.phase)) return;
        const leaving = (event: BeforeUnloadEvent) => { event.preventDefault(); event.returnValue = ""; };
        window.addEventListener("beforeunload", leaving); return () => window.removeEventListener("beforeunload", leaving);
    }, [draft.state.phase]);
    const restoreDraft = (fields: DraftFields) => {
        setVisitorFields(Object.fromEntries(["visitorName", "visitorEmail", "visitorPhone", "visitorCompany", "purpose"].map(key => [key, fields[key] ?? ""])));
        setVisitType(fields.visitType || "Interview"); setVisitDate(fields.visitDate || appointmentDates(8, false)[0]); setRoutingDepartmentId(fields.routingDepartmentId || "");
        setRequestedEmployeeId(fields.requestedEmployeeId || ""); setHostEmployeeId(fields.hostEmployeeId || ""); setSlots([]); setSlotStart(""); setLoadingSlots(Boolean(fields.hostEmployeeId));
        // Availability is freshly loaded; identity checks and documents are intentionally entered again.
        formRef.current?.reset();
    };
    const requiredCategory = hostCategoryForVisit(visitType);
    const requiredCategories = hostCategoriesForVisit(visitType);
    const inferredCategory = (employee: Employee): PublicHost["category"] => {
        if (employee.hostCategory) return employee.hostCategory;
        const searchable = `${employee.role} ${employee.department}`.toLowerCase();
        return searchable.includes("chief executive") ||
        searchable.includes("executive office")
            ? "CEO"
            : searchable.includes("human resources") ||
            /(^|\s)hr(\s|$)/.test(searchable)
                ? "HR"
                : searchable.includes("team lead") ||
                /(^|\s)lead(\s|$)/.test(searchable)
                    ? "TEAM_LEAD"
                    : "EMPLOYEE";
    };
    const eligibleEmployees = useMemo(
        () =>
            employees.filter((employee) => {
                if (employee.status !== "Active") return false;
                if (!requiredCategories.includes(inferredCategory(employee)))
                    return false;
                return (
                    !routingDepartmentId ||
                    inferredCategory(employee) === "CEO" ||
                    employee.departmentId === routingDepartmentId
                );
            }),
        [employees, requiredCategories, routingDepartmentId],
    );
    // The CEO-managed department directory is the source of truth. A department must remain visible
    // even before an HR/Team Lead is assigned; host eligibility is validated independently below.
    const departmentOptions = useMemo(
        () =>
            departments
                .filter((department) => department.active)
                .sort((left, right) => left.name.localeCompare(right.name)),
        [departments],
    );
    const loadDepartmentEmployees = useCallback(
        async (query = "") => {
            if (!routingDepartmentId) {
                setDirectoryEmployees([]);
                return;
            }
            setEmployeeDirectoryLoading(true);
            try {
                const department = departments.find(
                    (item) => item.id === routingDepartmentId,
                );
                const next = isBackendConfigured
                    ? (
                        await brainServeApi.publicEmployees(routingDepartmentId, query)
                    ).content.map((employee) => ({
                        id: employee.id,
                        uuid: employee.id,
                        departmentId: employee.departmentId,
                        name: employee.displayName,
                        initials: visitorInitials(employee.displayName),
                        role: employee.designation,
                        department: department?.name ?? "Department",
                        email: "",
                        status: "Active" as const,
                        hostCategory: "EMPLOYEE" as const,
                    }))
                    : employees
                        .filter(
                            (employee) =>
                                employee.status === "Active" &&
                                employee.departmentId === routingDepartmentId &&
                                inferredCategory(employee) === "EMPLOYEE" &&
                                (!query ||
                                    `${employee.name} ${employee.id}`
                                        .toLowerCase()
                                        .includes(query.toLowerCase())),
                        )
                        .slice(0, 25);
                setDirectoryEmployees(next);
                setRequestedEmployeeId((current) =>
                    next.some((item) => (item.uuid ?? item.id) === current)
                        ? current
                        : "",
                );
            } catch (reason) {
                setDirectoryEmployees([]);
                setError(
                    reason instanceof Error
                        ? reason.message
                        : "The department employee directory could not be loaded.",
                );
            } finally {
                setEmployeeDirectoryLoading(false);
            }
        },
        [departments, employees, routingDepartmentId],
    );

    useEffect(() => {
        if (visitType !== "Employee meeting" || !routingDepartmentId) return;
        const timer = window.setTimeout(() => void loadDepartmentEmployees(), 0);
        return () => window.clearTimeout(timer);
    }, [loadDepartmentEmployees, routingDepartmentId, visitType]);

    useEffect(() => {
        if (!hostEmployeeId || !visitDate) return;
        let active = true;
        const load = async () => {
            try {
                const result = isBackendConfigured
                    ? await brainServeApi.availableSlots(
                        hostEmployeeId,
                        visitDate,
                        appointmentTypeCode(visitType),
                    )
                    : fallbackSlots(visitDate);
                if (!active) return;
                setSlots(result);
                setSlotStart(result[0]?.start ?? "");
            } catch (reason) {
                if (active) {
                    setSlots([]);
                    setSlotStart("");
                    setError(
                        reason instanceof ApiError
                            ? reason.message
                            : "Available appointment times could not be loaded.",
                    );
                }
            } finally {
                if (active) setLoadingSlots(false);
            }
        };
        void load();
        return () => {
            active = false;
        };
    }, [hostEmployeeId, visitDate, visitType]);

    const submit = async (event: FormEvent<HTMLFormElement>) => {
        event.preventDefault();
        setBusy(true);
        setError("");
        const data = new FormData(event.currentTarget);
        const selectedSlot = slots.find((slot) => slot.start === slotStart);
        const selectedHost = eligibleEmployees.find(
            (employee) => (employee.uuid ?? employee.id) === hostEmployeeId,
        );
        if (!selectedHost || !selectedSlot) {
            setBusy(false);
            setError("Select an eligible host and an available future time.");
            return;
        }
        try {
            const input: ReceptionVisitInput = {
                visitorName: String(data.get("visitorName")),
                visitorEmail: String(data.get("visitorEmail")),
                visitorPhone: String(data.get("visitorPhone")),
                visitorCompany: String(data.get("visitorCompany")),
                visitType,
                hostEmployeeId,
                hostCategory: inferredCategory(selectedHost),
                routingDepartmentId,
                requestedEmployeeId:
                    visitType === "Employee meeting" ? requestedEmployeeId : null,
                slotStart: selectedSlot.start,
                slotEnd: selectedSlot.end,
                purpose: String(data.get("purpose")),
                identityDocumentType: securityMode
                    ? String(data.get("identityDocumentType") || "") || null
                    : null,
                identityDocumentLastFour: securityMode
                    ? String(data.get("identityDocumentLastFour") || "") || null
                    : null,
                notes: securityMode ? String(data.get("notes") || "") || null : null,
            };
            if (isBackendConfigured) {
                if (draftBlocksSubmit(draft.state.phase)) return;
                draft.session.setFields({ ...visitorFields, visitType, hostEmployeeId, routingDepartmentId, requestedEmployeeId, visitDate });
                const finalFields = { type: appointmentTypeCode(visitType), visitorName: input.visitorName, visitorEmail: input.visitorEmail, visitorPhone: input.visitorPhone, visitorCompany: input.visitorCompany, hostEmployeeId, routingDepartmentId, requestedEmployeeId: input.requestedEmployeeId ?? "", slotStart: input.slotStart, slotEnd: input.slotEnd, purpose: input.purpose, ...(securityMode ? { identityDocumentType: input.identityDocumentType ?? "", identityDocumentLastFour: input.identityDocumentLastFour ?? "", notes: input.notes ?? "" } : {}) };
                const receipt = await draft.session.submit(finalFields);
                if (receipt) { await draft.session.discard(); (onDraftSubmitted ?? onClose)(); }
            } else await onSubmit(input);
        } catch (reason) {
            setError(
                reason instanceof ApiError
                    ? reason.message
                    : "The visit could not be submitted.",
            );
        } finally {
            setBusy(false);
        }
    };
    const selectedHost = eligibleEmployees.find(
        (employee) => (employee.uuid ?? employee.id) === hostEmployeeId,
    );
    const ceoApprovalRoute =
        visitType === "CEO visit" ||
        (visitType === "Emergency visit" &&
            Boolean(selectedHost && inferredCategory(selectedHost) === "CEO"));
    return (
        <div
            className="modal-backdrop"
            role="presentation"
            onMouseDown={(event) => {
                if (event.target === event.currentTarget) onClose();
            }}
        >
            <section
                className="modal glass-panel visit-modal walk-in-modal"
                role="dialog"
                aria-modal="true"
                aria-labelledby="visit-modal-title"
            >
                <header className="visit-modal-header">
                    <div>
            <span>
              {securityMode ? "SECURITY WALK-IN" : "VISITOR REGISTRATION"}
            </span>
                        <h2 id="visit-modal-title">
                            {securityMode
                                ? "Create walk-in appointment"
                                : "Register interview or meeting"}
                        </h2>
                        <p>
                            {securityMode
                                ? "Capture the visitor and identity details, then notify Reception for verification."
                                : "After contact verification, Security records arrival and Reception routes the approval."}
                        </p>
                    </div>
                    <button
                        type="button"
                        className="icon-button"
                        onClick={closeSafely}
                        aria-label="Close visitor form"
                    >
                        <X size={19} />
                    </button>
                </header>
                <form ref={formRef} className="visit-modal-form" onSubmit={submit} aria-busy={busy}>
                    <div className="visit-modal-body"><DraftStatus draft={draft} onRestore={restoreDraft} onConfirmed={() => (onDraftSubmitted ?? onClose)()} />
                        <section className="visit-form-section" aria-labelledby="visit-details-heading">
                            <div className="visit-form-section-heading">
                                <span>{securityMode ? "WALK-IN DETAILS" : "VISIT DETAILS"}</span>
                                <strong id="visit-details-heading">Visitor and appointment routing</strong>
                                <small>Complete the visitor details, select the department, then choose an eligible host and time.</small>
                            </div>
                            <div className="modal-form-grid">
                                <label>
                                    Visitor name
                                    <input
                                        name="visitorName" value={visitorFields.visitorName ?? ""} disabled={draft.enabled && draftLocksFields(draft.state.phase)} onChange={(event) => setVisitorFields((fields) => ({ ...fields, visitorName: event.target.value }))}
                                        required
                                        minLength={2}
                                        maxLength={170}
                                        placeholder="Full name"
                                    />
                                </label>
                                <label>
                                    Company
                                    <input
                                        name="visitorCompany" value={visitorFields.visitorCompany ?? ""} disabled={draft.enabled && draftLocksFields(draft.state.phase)} onChange={(event) => setVisitorFields((fields) => ({ ...fields, visitorCompany: event.target.value }))}
                                        maxLength={170}
                                        placeholder="Company or Independent"
                                    />
                                </label>
                                <label>
                                    Visitor email
                                    <input
                                        name="visitorEmail" value={visitorFields.visitorEmail ?? ""} disabled={draft.enabled && draftLocksFields(draft.state.phase)} onChange={(event) => setVisitorFields((fields) => ({ ...fields, visitorEmail: event.target.value }))}
                                        type="email"
                                        required
                                        placeholder="visitor@example.com"
                                    />
                                </label>
                                <label>
                                    Mobile number
                                    <input
                                        name="visitorPhone" value={visitorFields.visitorPhone ?? ""} disabled={draft.enabled && draftLocksFields(draft.state.phase)} onChange={(event) => setVisitorFields((fields) => ({ ...fields, visitorPhone: event.target.value }))}
                                        required
                                        minLength={8}
                                        maxLength={32}
                                        placeholder="+91 98765 43210"
                                    />
                                </label>
                                <label>
                                    Visit type
                                    <select
                                        value={visitType}
                                        onChange={(event) => {
                                            const next = event.target.value;
                                            setVisitType(next);
                                            setRoutingDepartmentId("");
                                            setRequestedEmployeeId("");
                                            setDirectoryEmployees([]);
                                            setEmployeeQuery("");
                                            setHostEmployeeId("");
                                            setVisitDate(
                                                appointmentDates(8, next === "Emergency visit")[0],
                                            );
                                            setSlots([]);
                                            setSlotStart("");
                                            setLoadingSlots(false);
                                            setError("");
                                        }}
                                    >
                                        <option>Interview</option>
                                        <option>Emergency visit</option>
                                        <option>Employee meeting</option>
                                        <option>HR visit</option>
                                        <option>CEO visit</option>
                                        <option>Client meeting</option>
                                    </select>
                                </label>
                                <label>
                                    Routing department
                                    <select
                                        value={routingDepartmentId}
                                        onChange={(event) => {
                                            setRoutingDepartmentId(event.target.value);
                                            setRequestedEmployeeId("");
                                            setDirectoryEmployees([]);
                                            setEmployeeQuery("");
                                            setHostEmployeeId("");
                                            setSlots([]);
                                            setSlotStart("");
                                        }}
                                        required
                                    >
                                        <option value="">Select department</option>
                                        {departmentOptions.map((department) => (
                                            <option value={department.id} key={department.id}>
                                                {department.name}
                                            </option>
                                        ))}
                                    </select>
                                    {departmentOptions.length === 0 && (
                                        <small className="field-help" role="status">
                                            No active department is currently available. Refresh the workspace or ask the CEO to activate a department.
                                        </small>
                                    )}
                                </label>
                                {visitType === "Employee meeting" && (
                                    <>
                                        <label>
                                            Find employee
                                            <div className="directory-search-row">
                                                <input
                                                    value={employeeQuery}
                                                    onChange={(event) => setEmployeeQuery(event.target.value)}
                                                    placeholder="Name or employee ID"
                                                />
                                                <button
                                                    type="button"
                                                    className="button button-secondary"
                                                    disabled={
                                                        employeeDirectoryLoading || !routingDepartmentId
                                                    }
                                                    onClick={() =>
                                                        void loadDepartmentEmployees(employeeQuery)
                                                    }
                                                >
                                                    <Search size={15} />
                                                    {employeeDirectoryLoading ? "Searching…" : "Search"}
                                                </button>
                                            </div>
                                        </label>
                                        <label>
                                            Employee to meet
                                            <select
                                                value={requestedEmployeeId}
                                                onChange={(event) =>
                                                    setRequestedEmployeeId(event.target.value)
                                                }
                                                required
                                                disabled={employeeDirectoryLoading}
                                            >
                                                <option value="">
                                                    {employeeDirectoryLoading
                                                        ? "Loading department employees…"
                                                        : "Select department employee"}
                                                </option>
                                                {directoryEmployees.map((employee) => (
                                                    <option
                                                        value={employee.uuid ?? employee.id}
                                                        key={employee.uuid ?? employee.id}
                                                    >
                                                        {employee.name} · {employee.role}
                                                    </option>
                                                ))}
                                            </select>
                                        </label>
                                    </>
                                )}
                                <label>
                                    Eligible host
                                    <select
                                        value={hostEmployeeId}
                                        onChange={(event) => {
                                            setHostEmployeeId(event.target.value);
                                            setSlots([]);
                                            setSlotStart("");
                                            setLoadingSlots(Boolean(event.target.value));
                                            setError("");
                                        }}
                                        required
                                    >
                                        <option value="">
                                            {eligibleEmployees.length
                                                ? "Select an eligible active host"
                                                : `No assigned ${requiredCategory ?? "CEO or HR"} host available`}
                                        </option>
                                        {eligibleEmployees.map((employee) => (
                                            <option
                                                value={employee.uuid ?? employee.id}
                                                key={employee.uuid ?? employee.id}
                                            >
                                                {employee.name} · {employee.role} · {employee.department}
                                            </option>
                                        ))}
                                    </select>
                                </label>
                                <label>
                                    Appointment date
                                    <select
                                        value={visitDate}
                                        onChange={(event) => {
                                            setVisitDate(event.target.value);
                                            setSlots([]);
                                            setSlotStart("");
                                            setLoadingSlots(Boolean(hostEmployeeId));
                                            setError("");
                                        }}
                                    >
                                        {dates.map((date) => (
                                            <option key={date} value={date}>
                                                {date === officeToday() ? "Today · " : ""}
                                                {formatOfficeDate(officeDateTimeToIso(date, "12:00"))}
                                            </option>
                                        ))}
                                    </select>
                                </label>
                                <label>
                                    Available time
                                    <select
                                        value={slotStart}
                                        onChange={(event) => setSlotStart(event.target.value)}
                                        required
                                        disabled={loadingSlots || !hostEmployeeId}
                                    >
                                        <option value="">
                                            {loadingSlots
                                                ? "Loading availability…"
                                                : slots.length
                                                    ? "Select a time"
                                                    : "No future slots available"}
                                        </option>
                                        {slots.map((slot) => (
                                            <option key={slot.start} value={slot.start}>
                                                {formatOfficeTime(slot.start)}
                                            </option>
                                        ))}
                                    </select>
                                </label>
                                {securityMode && (
                                    <>
                                        <label>
                                            Identity document
                                            <select name="identityDocumentType" defaultValue="AADHAAR">
                                                <option value="AADHAAR">Aadhaar</option>
                                                <option value="PASSPORT">Passport</option>
                                                <option value="DRIVING_LICENCE">Driving licence</option>
                                                <option value="OTHER">Other</option>
                                            </select>
                                        </label>
                                        <label>
                                            Document last four
                                            <input
                                                name="identityDocumentLastFour"
                                                pattern="[A-Za-z0-9]{4}"
                                                minLength={4}
                                                maxLength={4}
                                                placeholder="1234"
                                                required
                                            />
                                        </label>
                                        <label className="full-field">
                                            Security notes
                                            <textarea
                                                name="notes"
                                                maxLength={500}
                                                placeholder="Identity matched, items carried, or other arrival notes"
                                            />
                                        </label>
                                    </>
                                )}
                                <label className="full-field">
                                    Purpose
                                    <textarea
                                        name="purpose" value={visitorFields.purpose ?? ""} disabled={draft.enabled && draftLocksFields(draft.state.phase)} onChange={(event) => setVisitorFields((fields) => ({ ...fields, purpose: event.target.value }))}
                                        required
                                        minLength={5}
                                        maxLength={1000}
                                        placeholder="Reason for interview or meeting"
                                    />
                                </label>
                            </div>
                        </section>
                        <div className="visit-notification-note" role="status">
                            <MessageSquare size={18} aria-hidden="true" />
                            <span>
                                <strong>{securityMode ? "Reception notification" : "Connected workflow notification"}</strong>
                                <small>{securityMode
                                    ? "Submitting this walk-in sends Reception a BrainServe Internal Calls update for verification."
                                    : "Security and Reception receive the visit workflow updates needed for arrival and verification."}</small>
                            </span>
                        </div>
                        <div className="approval-chain">
            <span>
              <IdCard size={16} /> Security intake
            </span>
                            <i />
                            <span>
              <BadgeCheck size={16} /> Reception verify
            </span>
                            <i />
                            <span>
              <UserCog size={16} />{" "}
                                {ceoApprovalRoute ? "Department Manager" : "Department HR"}
            </span>
                            {ceoApprovalRoute && (
                                <>
                                    <i />
                                    <span>
                  <ShieldCheck size={16} /> CEO final approval
                </span>
                                </>
                            )}
                        </div>
                        {error && (
                            <div className="login-error" role="alert">
                                {error}
                            </div>
                        )}
                    </div>
                    <div className="modal-actions">
                        <button
                            type="button"
                            className="button button-secondary"
                            onClick={closeSafely}
                        >
                            Cancel
                        </button>
                        <button
                            type="submit"
                            className="button button-primary"
                            disabled={busy || loadingSlots || !slotStart || draft.enabled && draftBlocksSubmit(draft.state.phase)}
                        >
                            <ArrowRight size={17} />{" "}
                            {busy
                                ? "Submitting…"
                                : securityMode
                                    ? "Create & notify Reception"
                                    : "Submit visit"}
                        </button>
                    </div>
                </form>
            </section>
        </div>
    );
}

