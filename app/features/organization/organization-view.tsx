"use client";

import {
    ApiError,
    brainServeApi,
    type DepartmentEmployeeSummary,
    type DepartmentHrAssignment,
    isBackendConfigured,
    type ManagerAssignment,
    type StaffAccount,
    type TeamLeadAssignment,
} from "../../lib/api";
import { officeToday } from "../../lib/appointments";
import { readDemoAccounts } from "../../preview/accounts";
import { readDemoDepartmentHrAssignments, readDemoTeamLeadAssignments } from "../../preview/directory";
import { readDemoManagerAssignments } from "../../preview/manager-assignments";
import { PageTitle } from "../../shared/components/page-title";
import { StatusPill } from "../../shared/components/status-pill";
import { type Department, type DepartmentRosterPage, type Employee, type Role } from "../../shared/types/workspace";
import { fail } from "../../shared/utils/errors";
import { visitorInitials } from "../appointments/appointment-utils";
import { belongsToDepartment, employeeStatusLabel } from "../employees/employee-utils";
import { DepartmentLogo } from "./components/department-logo";
import { normalizeDepartmentCode, normalizeDepartmentName, suggestDepartmentCode } from "./department-utils";
import {
    ArrowLeft,
    ArrowRight,
    BadgeCheck,
    Building2,
    CheckCircle2,
    ChevronRight,
    CircleUserRound,
    Plus,
    Search,
    ShieldCheck,
    UserCog,
    UserPlus,
    Users,
    X,
} from "lucide-react";
import { type FormEvent, useCallback, useEffect, useMemo, useState } from "react";

export function OrganizationView({ role, userEmail, departments, employees, staffAccounts, summaries, teamLeadAssignments,
                              departmentHrAssignments, managerAssignments, onCreate, onToggle, onAddEmployee, onAssignTeamLead, onEndTeamLead,
                              onAssignDepartmentHr, onEndDepartmentHr, onJoinExecutiveDepartment }: {
    role: Role; userEmail: string; departments: Department[]; employees: Employee[]; staffAccounts: StaffAccount[];
    summaries: DepartmentEmployeeSummary[];
    teamLeadAssignments: TeamLeadAssignment[];
    departmentHrAssignments: DepartmentHrAssignment[];
    managerAssignments: ManagerAssignment[];
    onCreate: (code: string, name: string) => Promise<Department>; onToggle: (department: Department) => Promise<void>;
    onAddEmployee: (departmentId: string) => void;
    onAssignTeamLead: (departmentId: string, employeeId: string) => Promise<boolean>;
    onEndTeamLead: (assignment: TeamLeadAssignment) => Promise<void>;
    onAssignDepartmentHr: (departmentId: string, hrUserId: string) => Promise<boolean>;
    onEndDepartmentHr: (assignment: DepartmentHrAssignment) => Promise<void>;
    onJoinExecutiveDepartment: (payload: { departmentId: string; phoneNumber: string;
        designation: string; joiningDate: string }) => Promise<boolean>;
}) {
    const [showForm, setShowForm] = useState(false);
    const [error, setError] = useState("");
    const [expandedDepartment, setExpandedDepartment] = useState<string | null>();
    const [loadingDepartment, setLoadingDepartment] = useState<string>();
    const [rosters, setRosters] = useState<Record<string, DepartmentRosterPage>>({});
    const [backendVisibleDepartments, setBackendVisibleDepartments] = useState<Department[]>([]);
    const [leadership, setLeadership] = useState<Awaited<ReturnType<typeof brainServeApi.departmentLeadership>>>([]);
    const [leadershipStatus, setLeadershipStatus] = useState<"loading" | "ready" | "error">("loading");
    useEffect(() => {
        if (!isBackendConfigured) return;
        let active = true;
        void Promise.resolve().then(() => {
            if (!active) return;
            setLeadershipStatus("loading");
            return brainServeApi.departmentLeadership();
        }).then((items) => {
            if (active && items) { setLeadership(items); setLeadershipStatus("ready"); }
        }).catch(() => { if (active) setLeadershipStatus("error"); });
        return () => { active = false; };
    }, [role, userEmail, departments, teamLeadAssignments, departmentHrAssignments, managerAssignments]);
    const [executiveDepartmentId, setExecutiveDepartmentId] = useState("");
    const [executiveBusy, setExecutiveBusy] = useState(false);
    const [message, setMessage] = useState("");
    const [departmentDraftName, setDepartmentDraftName] = useState("");
    const [departmentDraftCode, setDepartmentDraftCode] = useState("");
    const [departmentCodeEdited, setDepartmentCodeEdited] = useState(false);
    const [departmentCreateBusy, setDepartmentCreateBusy] = useState(false);
    const [departmentFieldsTouched, setDepartmentFieldsTouched] = useState({ name: false, code: false });
    const demoVisibleDepartments = useMemo(() => {
        if (isBackendConfigured) return [];
        if (role === "CEO") return departments;
        const account = [...readDemoAccounts(), ...staffAccounts].find((item) => item.email.toLowerCase() === userEmail.toLowerCase());
        const accountId = account && "id" in account ? account.id : account?.userId;
        const employeeDepartmentId = employees.find((item) =>
            item.email.toLowerCase() === userEmail.toLowerCase())?.departmentId;
        const assignmentDepartmentId = role === "HR Admin"
            ? readDemoDepartmentHrAssignments().find((item) => item.active && item.hrUserId === accountId)?.departmentId
            : role === "Manager"
                ? readDemoManagerAssignments().find((item) => item.active && item.managerUserId === accountId)?.departmentId
                : readDemoTeamLeadAssignments().find((item) => item.active && item.teamLeadUserId === accountId)?.departmentId;
        return departments.filter((item) => item.id === (assignmentDepartmentId ?? employeeDepartmentId));
    }, [departments, employees, role, staffAccounts, userEmail]);
    const visibleDepartments = isBackendConfigured
        ? role === "CEO" ? departments : backendVisibleDepartments
        : demoVisibleDepartments;
    const executiveEmployee = role === "CEO" ? employees.find((item) => item.email.toLowerCase() === userEmail.toLowerCase()) : undefined;
    useEffect(() => {
        let active = true;
        if (!isBackendConfigured || role === "CEO") return () => { active = false; };
        brainServeApi.visibleDepartments().then((items) => { if (active) setBackendVisibleDepartments(items); })
            .catch((reason) => { if (active) { setBackendVisibleDepartments([]); setError(reason instanceof Error ? reason.message : "Your department scope could not be loaded."); } });
        return () => { active = false; };
    }, [role]);
    const selectedExecutiveDepartmentId = executiveDepartmentId || executiveEmployee?.departmentId
        || visibleDepartments.find((item) => item.active)?.id || "";
    const effectiveExpandedDepartment = expandedDepartment === undefined && role !== "CEO" && visibleDepartments.length === 1
        ? visibleDepartments[0].id : expandedDepartment;
    const visibleDepartmentIds = useMemo(() => new Set(visibleDepartments.map((item) => item.id)), [visibleDepartments]);
    const scopedSummaries = useMemo(() => summaries.filter((item) => visibleDepartmentIds.has(item.departmentId)), [summaries, visibleDepartmentIds]);
    const scopedEmployees = useMemo(() => employees.filter((item) => item.departmentId && visibleDepartmentIds.has(item.departmentId)), [employees, visibleDepartmentIds]);
    const summaryByDepartment = useMemo(() => new Map(scopedSummaries.map((item) => [item.departmentId, item])), [scopedSummaries]);
    const totalEmployees = scopedSummaries.length ? scopedSummaries.reduce((total, item) => total + item.totalEmployees, 0) : scopedEmployees.length;
    const activeEmployees = scopedSummaries.length ? scopedSummaries.reduce((total, item) => total + item.activeEmployees, 0)
        : scopedEmployees.filter((item) => item.status === "Active").length;
    const canCreateDepartment = role === "CEO";
    const canAddEmployee = role === "HR Admin" || role === "CEO";
    const canToggleDepartment = role === "CEO";
    const canManageLead = role === "HR Admin";
    const canManageHr = role === "CEO";
    const normalizedDraftName = normalizeDepartmentName(departmentDraftName);
    const normalizedDraftCode = normalizeDepartmentCode(departmentDraftCode);
    const departmentNameError = normalizedDraftName.length < 2
        ? "Enter a department name with at least two characters."
        : departments.some((item) => item.name.trim().toLowerCase() === normalizedDraftName.toLowerCase())
            ? "A department with this name already exists."
            : "";
    const departmentCodeError = normalizedDraftCode.length < 2
        ? "Use at least two uppercase letters or numbers."
        : departments.some((item) => item.code.toUpperCase() === normalizedDraftCode)
            ? `The code ${normalizedDraftCode} is already in use.`
            : "";
    const departmentPreview: Department = {
        id: "department-logo-preview",
        code: normalizedDraftCode || suggestDepartmentCode(normalizedDraftName) || "DEPT",
        name: normalizedDraftName || "New department",
        active: true,
        version: 0,
    };
    const loadDepartmentPage = useCallback(async (department: Department, pageNumber = 0, query = "") => {
        if (!isBackendConfigured) {
            const matching = employees.filter((item) => belongsToDepartment(item, department)
                && (!query || `${item.name} ${item.id} ${item.email}`.toLowerCase().includes(query.toLowerCase())));
            const pageSize = 50;
            const pageItems = matching.slice(pageNumber * pageSize, (pageNumber + 1) * pageSize);
            setRosters((current) => ({ ...current, [department.id]: {
                    items: pageItems, page: pageNumber, totalElements: matching.length,
                    totalPages: Math.max(1, Math.ceil(matching.length / pageSize)), query,
                } }));
            return;
        }
        setLoadingDepartment(department.id); setError("");
        try {
            const page = await brainServeApi.employeePage({
                departmentId: department.id, query, page: pageNumber, size: 50, sort: "displayName,asc",
            });
            const items = page.content.map((item) => ({
                id: item.employeeNumber, uuid: item.id, departmentId: item.departmentId,
                name: item.displayName, initials: visitorInitials(item.displayName),
                role: item.designation, department: department.name, email: item.officialEmail,
                status: employeeStatusLabel(item.status),
            }));
            setRosters((current) => ({ ...current, [department.id]: {
                    items, page: page.number ?? pageNumber,
                    totalElements: page.totalElements ?? items.length,
                    totalPages: page.totalPages ?? Math.max(1, Math.ceil(items.length / 50)),
                    query,
                } }));
        } catch (reason) { setError(reason instanceof ApiError ? reason.message : "The department roster could not be loaded."); }
        finally { setLoadingDepartment(undefined); }
    }, [employees]);
    const openDepartment = async (department: Department) => {
        if (effectiveExpandedDepartment === department.id) { setExpandedDepartment(null); return; }
        setExpandedDepartment(department.id);
        if (!rosters[department.id]) await loadDepartmentPage(department);
    };
    useEffect(() => {
        if (!isBackendConfigured || !effectiveExpandedDepartment || rosters[effectiveExpandedDepartment]) return;
        const department = visibleDepartments.find((item) => item.id === effectiveExpandedDepartment);
        if (!department) return;
        const timer = window.setTimeout(() => void loadDepartmentPage(department), 0);
        return () => window.clearTimeout(timer);
    }, [effectiveExpandedDepartment, loadDepartmentPage, rosters, visibleDepartments]);
    const create = async (event: FormEvent<HTMLFormElement>) => {
        event.preventDefault(); setError(""); setMessage("");
        setDepartmentFieldsTouched({ name: true, code: true });
        const form = event.currentTarget;
        const data = new FormData(form);
        if (departmentNameError || departmentCodeError) {
            setError(departmentNameError || departmentCodeError);
            return;
        }
        setDepartmentCreateBusy(true);
        try {
            const created = await onCreate(normalizedDraftCode, normalizedDraftName);
            if (role === "CEO" && data.get("joinExecutive") === "yes") {
                const joined = await onJoinExecutiveDepartment({ departmentId: created.id, phoneNumber: "",
                    designation: "Chief Executive Officer", joiningDate: officeToday() });
                if (!joined) fail("The department was created, but the CEO profile could not be registered to it.");
                setExecutiveDepartmentId(created.id);
            }
            form.reset();
            setDepartmentDraftName("");
            setDepartmentDraftCode("");
            setDepartmentCodeEdited(false);
            setDepartmentFieldsTouched({ name: false, code: false });
            setShowForm(false); setMessage(data.get("joinExecutive") === "yes"
                ? "Department created and registered as your CEO department."
                : "Department created successfully.");
        }
        catch (reason) { setError(reason instanceof Error ? reason.message : "The department could not be created."); }
        finally { setDepartmentCreateBusy(false); }
    };
    const joinExecutive = async (event: FormEvent<HTMLFormElement>) => {
        event.preventDefault(); setExecutiveBusy(true); setError(""); setMessage("");
        const data = new FormData(event.currentTarget);
        const ok = await onJoinExecutiveDepartment({ departmentId: String(data.get("departmentId")),
            phoneNumber: String(data.get("phoneNumber") ?? ""), designation: String(data.get("designation")),
            joiningDate: String(data.get("joiningDate")) });
        if (ok) setMessage("Your CEO profile is now registered to the selected department.");
        else setError("The CEO department profile could not be updated.");
        setExecutiveBusy(false);
    };
    return <>
        <PageTitle eyebrow="ORGANIZATION INTELLIGENCE" title="Your company, clearly connected"
                   detail={role === "CEO" ? "Open any department to see its live PostgreSQL-backed team directory and take role-controlled actions." : "Your directory is restricted to the department assigned to your account."}
                   action={canCreateDepartment && <button type="button" className="button button-primary"
                                                          aria-expanded={showForm} aria-controls="create-department-form"
                                                          onClick={() => { setShowForm((value) => !value); setError(""); setMessage(""); }}>
                       {showForm ? <X size={17} /> : <Plus size={17} />} {showForm ? "Close form" : "Add department"}
                   </button>} />
        <section className="org-overview glass-panel">
            <div><span>Departments</span><strong>{visibleDepartments.length}</strong><small>{visibleDepartments.filter((item) => item.active).length} visible active units</small></div>
            <i /><div><span>Total workforce</span><strong>{totalEmployees}</strong><small>{role === "CEO" ? "Across the organization" : "Within your assigned department"}</small></div>
            <i /><div><span>Active employees</span><strong>{activeEmployees}</strong><small>Available today</small></div>
            <i /><div><span>Leadership access</span><strong>{role === "CEO" ? "CEO" : role === "Manager" ? "MG" : role === "Team Lead" ? "TL" : "HR"}</strong><small>{role === "CEO" ? "Organization-wide access" : "Department-scoped access"}</small></div>
        </section>
        {role === "CEO" && <form className="executive-membership glass-panel" onSubmit={joinExecutive}>
            <div className="executive-membership-copy"><span className="dept-icon"><CircleUserRound size={21} /></span><span><small>MY EXECUTIVE DEPARTMENT</small><strong>{executiveEmployee?.department ?? "Choose your primary department"}</strong><p>Your CEO profile can join or move to any active department without changing organization-wide access.</p></span></div>
            <label>Department<select name="departmentId" value={selectedExecutiveDepartmentId} onChange={(event) => setExecutiveDepartmentId(event.target.value)} required>
                <option value="">Select an active department</option>{visibleDepartments.filter((item) => item.active).map((department) => <option key={department.id} value={department.id}>{department.name} · {department.code}</option>)}
            </select></label>
            <label>Designation<input name="designation" defaultValue={executiveEmployee?.role ?? "Chief Executive Officer"} minLength={2} maxLength={120} required /></label>
            <label>Phone<input name="phoneNumber" maxLength={30} placeholder="Optional contact" /></label>
            <input type="hidden" name="joiningDate" value={officeToday()} />
            <button className="button button-primary" disabled={executiveBusy || !selectedExecutiveDepartmentId}><BadgeCheck size={16} />{executiveBusy ? "Saving…" : executiveEmployee ? "Move my profile" : "Register my profile"}</button>
        </form>}
        {showForm && <form id="create-department-form" className="staff-create-form panel glass-panel org-create-form" onSubmit={create}>
            <label className="field-stack">Department name
                <input id="new-department-name" name="name" value={departmentDraftName} minLength={2} maxLength={120}
                       placeholder="e.g. Product Development" autoComplete="organization-title" required
                       aria-invalid={departmentFieldsTouched.name && Boolean(departmentNameError)}
                       aria-describedby="new-department-name-help"
                       onBlur={() => setDepartmentFieldsTouched((current) => ({
                           ...current, name: true, code: departmentCodeEdited ? current.code : true,
                       }))}
                       onChange={(event) => {
                           const value = event.target.value;
                           setDepartmentDraftName(value);
                           if (!departmentCodeEdited) setDepartmentDraftCode(suggestDepartmentCode(value));
                       }} />
                <small id="new-department-name-help">Use the full business name shown across Organization and employee records.</small>
                {departmentFieldsTouched.name && departmentNameError && <small role="alert">{departmentNameError}</small>}
            </label>
            <label className="field-stack">Department code
                <input id="new-department-code" name="code" value={departmentDraftCode} minLength={2} maxLength={20}
                       pattern="[A-Z0-9_]+" placeholder="e.g. PRODUCT" autoCapitalize="characters" spellCheck={false} required
                       aria-invalid={departmentFieldsTouched.code && Boolean(departmentCodeError)}
                       aria-describedby="new-department-code-help"
                       onBlur={() => {
                           setDepartmentDraftCode(normalizeDepartmentCode(departmentDraftCode));
                           setDepartmentFieldsTouched((current) => ({ ...current, code: true }));
                       }}
                       onChange={(event) => {
                           setDepartmentCodeEdited(true);
                           setDepartmentDraftCode(event.target.value.toUpperCase().replace(/[^A-Z0-9_]/g, "").slice(0, 20));
                       }} />
                <small id="new-department-code-help">Stable internal identifier. Names such as Production suggest PRO; Product Development suggests PRODUCT.</small>
                {departmentFieldsTouched.code && departmentCodeError && <small role="alert">{departmentCodeError}</small>}
            </label>
            {departmentFieldsTouched.name && !departmentNameError && !departmentCodeError && <div className="team-lead-badge">
                <DepartmentLogo department={departmentPreview} size={28} />
                <span><small>LOGO PREVIEW</small><strong>{departmentPreview.name}</strong></span>
            </div>}
            <label className="executive-create-option"><input type="checkbox" name="joinExecutive" value="yes" /> Create and register this as my CEO department</label>
            <div className="form-actions">
                <button type="button" className="button button-secondary" onClick={() => {
                    setShowForm(false); setError(""); setDepartmentDraftName(""); setDepartmentDraftCode("");
                    setDepartmentCodeEdited(false); setDepartmentFieldsTouched({ name: false, code: false });
                }}>Cancel</button>
                <button type="submit" className="button button-primary"
                        disabled={departmentCreateBusy || Boolean(departmentNameError) || Boolean(departmentCodeError)}>
                    {departmentCreateBusy ? "Creating…" : "Create department"}
                </button>
            </div>
        </form>}
        {message && <div className="success-banner" role="status"><CheckCircle2 size={17} />{message}</div>}
        {error && <div className="login-error" role="alert">{error}</div>}
        {visibleDepartments.length === 0 && <div className="empty-state org-scope-empty"><Building2 size={28} /><strong>No department is assigned</strong><small>Ask the CEO or System Admin to complete your role and department assignment.</small></div>}
        <div className={`org-grid${role === "CEO" ? "" : " org-grid-focused"}`}>{visibleDepartments.map((department) => {
            const fallbackMembers = employees.filter((employee) => belongsToDepartment(employee, department));
            const summary = summaryByDepartment.get(department.id);
            const memberCount = summary?.totalEmployees ?? fallbackMembers.length;
            const activeCount = summary?.activeEmployees ?? fallbackMembers.filter((item) => item.status === "Active").length;
            const routingDepartment = ["EXEC", "HR"].includes(department.code.toUpperCase());
            const expanded = effectiveExpandedDepartment === department.id;
            const rosterId = `department-roster-${department.id}`;
            const loadedRoster = rosters[department.id];
            const liveMemberById = new Map(fallbackMembers.map((employee) => [employee.uuid ?? employee.id, employee]));
            const roster = loadedRoster
                ? loadedRoster.items.map((employee) => liveMemberById.get(employee.uuid ?? employee.id) ?? employee)
                : fallbackMembers;
            const leadAssignment = teamLeadAssignments.find((item) => item.departmentId === department.id && item.active);
            const leadEmployee = leadAssignment ? employees.find((item) => (item.uuid ?? item.id) === leadAssignment.teamLeadEmployeeId) : undefined;
            const hrAssignment = departmentHrAssignments.find((item) => item.departmentId === department.id && item.active);
            const hrAccount = hrAssignment ? staffAccounts.find((item) => item.userId === hrAssignment.hrUserId) : undefined;
            const managerAssignment = managerAssignments.find((item) => item.departmentId === department.id && item.active);
            const managerAccount = managerAssignment ? staffAccounts.find((item) => item.userId === managerAssignment.managerUserId) : undefined;
            const departmentLeadership = leadership.find((item) => item.departmentId === department.id);
            const leaderLabel = (key: "teamLead" | "hr" | "manager", fallback: string) => {
                if (!isBackendConfigured) return fallback;
                if (leadershipStatus === "loading") return "Loading…";
                if (leadershipStatus === "error" || !departmentLeadership) return "Unavailable";
                const leader = departmentLeadership[key];
                return leader ? leader.fullName || "Assigned" : "Not assigned";
            };
            const activeLeadEmployeeIds = new Set(teamLeadAssignments.filter((item) => item.active)
                .map((item) => item.teamLeadEmployeeId));
            const eligibleLeadEmployees = roster.filter((employee) => {
                const employeeId = employee.uuid ?? employee.id;
                const account = staffAccounts.find((item) => item.employeeId === employeeId
                    || item.email.toLowerCase() === employee.email.toLowerCase());
                return department.active
                    && employee.status === "Active"
                    && !activeLeadEmployeeIds.has(employeeId)
                    && account?.enabled
                    && account.status === "ACTIVE"
                    && account.roles.length === 1
                    && account.roles[0] === "ROLE_EMPLOYEE";
            });
            return <article className={`org-card glass-panel${expanded ? " expanded" : ""}`} key={department.id}>
                <div className="org-card-head"><span className="dept-icon"><DepartmentLogo department={department} /></span>
                    <span className="org-status"><i className={department.active ? "active" : ""} />{department.active ? "Active" : "Inactive"}</span></div>
                <small>{department.code}</small><h3>{department.name}</h3>
                <p><CircleUserRound size={15} /> {routingDepartment ? "Protected appointment-routing department" : "BrainServe operating department"}</p>
                <div className="team-lead-badge"><BadgeCheck size={16} /><span><small>TEAM LEAD</small><strong>{leaderLabel("teamLead", leadEmployee?.name ?? (leadAssignment ? "Assigned Team Lead" : "Not assigned"))}</strong></span></div>
                <div className="team-lead-badge"><UserCog size={16} /><span><small>DEPARTMENT HR</small><strong>{leaderLabel("hr", hrAccount?.fullName ?? (hrAssignment ? "Assigned HR Admin" : "Not assigned"))}</strong></span></div>
                <div className="team-lead-badge"><ShieldCheck size={16} /><span><small>DEPARTMENT MANAGER</small><strong>{leaderLabel("manager", managerAccount?.fullName ?? (managerAssignment ? "Assigned Manager" : "Not assigned"))}</strong></span></div>
                <div className="org-card-metrics"><span><strong>{memberCount}</strong><small>People</small></span><span><strong>{activeCount}</strong><small>Active</small></span><span><strong>{summary?.onLeaveEmployees ?? fallbackMembers.filter((item) => item.status === "On leave").length}</strong><small>On leave</small></span></div>
                <div className="org-card-actions">
                    <button type="button" className="button button-secondary" onClick={() => void openDepartment(department)}
                            aria-expanded={expanded} aria-controls={rosterId}><Users size={15} /> {expanded ? "Close team" : "View team"}<ChevronRight size={14} /></button>
                    {canAddEmployee && <button type="button" className="button button-secondary" disabled={!department.active} onClick={() => onAddEmployee(department.id)}><UserPlus size={15} /> Add employee</button>}
                    {canToggleDepartment && <button type="button" className={department.active ? "button button-reject" : "button button-approve"} disabled={routingDepartment}
                                                    title={routingDepartment ? "Required for CEO and HR appointment routing" : `${department.active ? "Deactivate" : "Activate"} ${department.name}`}
                                                    onClick={() => void onToggle(department)}>{department.active ? "Deactivate" : "Activate"}</button>}
                </div>
                {expanded && <section id={rosterId} className="department-roster">
                    <header><div><span>DEPARTMENT DIRECTORY</span><h4>{department.name} team</h4></div><b>{memberCount} people</b></header>
                    <form className="inline-account-form" onSubmit={(event) => {
                        event.preventDefault();
                        const query = String(new FormData(event.currentTarget).get("rosterQuery") ?? "").trim();
                        void loadDepartmentPage(department, 0, query);
                    }}><label>Find a team member<input name="rosterQuery" defaultValue={loadedRoster?.query ?? ""}
                                                       placeholder="Name, employee ID or company email" /></label><button className="button button-secondary"
                                                                                                                          disabled={loadingDepartment === department.id}><Search size={16} /> Search</button></form>
                    {loadingDepartment === department.id ? <div className="org-roster-state"><span className="loading-dot" />Loading live team…</div>
                        : roster.length ? <div className="department-people">{roster.map((employee) => <div key={employee.uuid ?? employee.id}>
                            <span className="avatar">{employee.initials}</span><span><strong>{employee.name}</strong><small>{employee.role} · {employee.email}</small></span>
                            <span className="mono-text">{employee.id}</span><StatusPill status={employee.status} />
                        </div>)}</div> : <div className="org-roster-state"><Users size={24} />No employees are assigned yet.</div>}
                    {loadedRoster && loadedRoster.totalPages > 1 && <div className="table-pagination">
                        <button type="button" className="button button-secondary" disabled={loadedRoster.page === 0
                            || loadingDepartment === department.id}
                                onClick={() => void loadDepartmentPage(department, loadedRoster.page - 1, loadedRoster.query)}>
                            <ArrowLeft size={15} /> Previous
                        </button>
                        <span>Page {loadedRoster.page + 1} of {loadedRoster.totalPages} · {loadedRoster.totalElements.toLocaleString("en-IN")} employees</span>
                        <button type="button" className="button button-secondary"
                                disabled={loadedRoster.page + 1 >= loadedRoster.totalPages || loadingDepartment === department.id}
                                onClick={() => void loadDepartmentPage(department, loadedRoster.page + 1, loadedRoster.query)}>
                            Next <ArrowRight size={15} />
                        </button>
                    </div>}
                    {canManageLead && department.active && <form className="team-lead-assignment" onSubmit={(event) => {
                        event.preventDefault(); const form = event.currentTarget; const data = new FormData(form);
                        const employeeId = String(data.get("employeeId"));
                        const employee = eligibleLeadEmployees.find((item) => (item.uuid ?? item.id) === employeeId);
                        void onAssignTeamLead(department.id, employeeId).then((assigned) => {
                            if (!assigned) return;
                            setMessage(`${employee?.name ?? "Employee"} now has Team Lead access for ${department.name}. Their existing login credentials remain unchanged.`);
                            form.reset();
                        });
                    }}><label>{leadAssignment ? "Replace Team Lead" : "Assign Team Lead"}<select name="employeeId" required defaultValue="">
                        <option value="" disabled>{eligibleLeadEmployees.length ? "Select an approved Employee account" : "No eligible Employee accounts"}</option>
                        {eligibleLeadEmployees
                            .map((item) => <option key={item.uuid ?? item.id} value={item.uuid ?? item.id}>{item.name} · {item.role}</option>)}
                    </select><small>Only enabled, approved Employee accounts are eligible. Search by name, employee ID or email to find another employee.</small></label><button className="button button-primary" disabled={eligibleLeadEmployees.length === 0}><BadgeCheck size={15} /> {leadAssignment ? "Replace lead" : "Assign lead"}</button>
                        {leadAssignment && <button type="button" className="button button-reject" onClick={() => void onEndTeamLead(leadAssignment)}>End assignment</button>}</form>}
                    {canManageHr && <form className="team-lead-assignment" onSubmit={(event) => {
                        event.preventDefault(); const data = new FormData(event.currentTarget);
                        void onAssignDepartmentHr(department.id, String(data.get("hrUserId")));
                    }}><label>{hrAssignment ? "Replace department HR" : "Assign department HR"}<select name="hrUserId" required defaultValue="">
                        <option value="" disabled>Select an active HR Admin</option>
                        {staffAccounts.filter((account) => account.enabled && account.status === "ACTIVE"
                            && account.roles.includes("ROLE_HR_ADMIN") && account.employeeId
                            && account.userId !== hrAssignment?.hrUserId)
                            .map((account) => {
                                const current = departmentHrAssignments.find((assignment) => assignment.active && assignment.hrUserId === account.userId);
                                const currentDepartment = departments.find((item) => item.id === current?.departmentId);
                                return <option key={account.userId} value={account.userId}>{account.fullName} · {currentDepartment ? `currently ${currentDepartment.name}` : "unassigned"}</option>;
                            })}
                    </select><small>Assigned HRs remain selectable. Choosing one transfers their employee profile, visitor queue and work-audit scope to this department.</small></label><button className="button button-primary"><UserCog size={15} /> {hrAssignment ? "Replace / transfer HR" : "Assign / transfer HR"}</button>
                        {hrAssignment && <button type="button" className="button button-reject" onClick={() => void onEndDepartmentHr(hrAssignment)}>End HR assignment</button>}</form>}
                </section>}
            </article>;
        })}</div>
    </>;
}

