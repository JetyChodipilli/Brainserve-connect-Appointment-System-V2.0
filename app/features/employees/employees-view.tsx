"use client";

import { brainServeApi, isBackendConfigured, type StaffAccount } from "../../lib/api";
import { PageTitle } from "../../shared/components/page-title";
import { StatusPill } from "../../shared/components/status-pill";
import { type Department, type Employee, type Role } from "../../shared/types/workspace";
import { visitorInitials } from "../appointments/appointment-utils";
import { EmployeeServicePanel } from "./components/employee-service-panel";
import { employeeStatusLabel } from "./employee-utils";
import { Building2, CheckCircle2, FileText, Search, ShieldCheck, UserPlus, Users } from "lucide-react";
import { useEffect, useMemo, useState } from "react";

export function EmployeesView({
                           role,
                           refreshKey,
                           employees,
                           departments,
                           staffAccounts,
                           currentEmployee,
                           unassignedAccounts,
                           onAssignDepartment,
                           onAdd,
                           onStatus,
                       }: {
    role: Role;
    employees: Employee[];
    departments: Department[];
    staffAccounts: StaffAccount[];
    currentEmployee?: Employee;
    unassignedAccounts: StaffAccount[];
    onAssignDepartment: (account: StaffAccount) => void;
    onAdd: () => void;
    onStatus: (employee: Employee, status: Employee["status"]) => Promise<void>;
    refreshKey: number;
}) {
    const [query, setQuery] = useState("");
    const [departmentFilter, setDepartmentFilter] = useState("All");
    const [statusFilter, setStatusFilter] = useState("All");
    const [page, setPage] = useState(0);
    const [pageCount, setPageCount] = useState(1);
    const [totalElements, setTotalElements] = useState(
        isBackendConfigured && ["HR Admin", "Manager", "Team Lead", "Employee"].includes(role)
            ? 0
            : employees.length,
    );
    const [backendPageEmployees, setBackendPageEmployees] = useState<Employee[]>(
        [],
    );
    const [pageBusy, setPageBusy] = useState(false);
    const [pageError, setPageError] = useState("");
    const [pageRequestRevision, setPageRequestRevision] = useState(0);
    const [busyEmployeeId, setBusyEmployeeId] = useState("");
    const [recordEmployee, setRecordEmployee] = useState<Employee | null>(null);
    const departmentScoped = ["HR Admin", "Manager", "Team Lead", "Employee"].includes(role);
    const scopedDepartment = departmentScoped
        ? departments.find((department) => department.id === currentEmployee?.departmentId)
        ?? (departments.length === 1 ? departments[0] : undefined)
        : undefined;
    const scopedDepartmentId = scopedDepartment?.id ?? currentEmployee?.departmentId;
    const loadedEmployees = isBackendConfigured
        ? backendPageEmployees
        : employees;
    const visibleEmployees = useMemo(
        () =>
            departmentScoped
                ? scopedDepartmentId
                    ? loadedEmployees.filter(
                        (employee) => employee.departmentId === scopedDepartmentId,
                    )
                    : []
                : loadedEmployees,
        [departmentScoped, loadedEmployees, scopedDepartmentId],
    );
    const displayedTotalElements = isBackendConfigured ? totalElements : visibleEmployees.length;
    const departmentCount = new Set(
        visibleEmployees.map((employee) => employee.department),
    ).size;
    const filtered = useMemo(
        () =>
            visibleEmployees.filter(
                (employee) =>
                    (isBackendConfigured ||
                        `${employee.name} ${employee.department} ${employee.id}`
                            .toLowerCase()
                            .includes(query.toLowerCase())) &&
                    (departmentFilter === "All" ||
                        employee.department === departmentFilter) &&
                    (isBackendConfigured ||
                        statusFilter === "All" ||
                        employee.status === statusFilter),
            ),
        [visibleEmployees, query, departmentFilter, statusFilter],
    );
    useEffect(() => {
        if (!isBackendConfigured) return;
        let active = true;
        const controller = new AbortController();
        const timer = window.setTimeout(async () => {
            setPageBusy(true);
            setPageError("");
            if (departmentScoped && !scopedDepartmentId) {
                setBackendPageEmployees([]);
                setPageCount(1);
                setTotalElements(0);
                setPageBusy(false);
                setPageError(role === "Team Lead"
                    ? "Your Team Lead department assignment could not be resolved. Ask HR to confirm the active assignment, then sign out and sign in again."
                    : role === "HR Admin"
                        ? "Your HR department assignment could not be resolved. Ask System Admin or CEO to confirm the active assignment, then sign out and sign in again."
                        : role === "Manager"
                            ? "Your Manager department assignment could not be resolved. Ask the CEO to confirm the active assignment, then sign out and sign in again."
                            : "Your employee department assignment could not be resolved. Ask HR to complete your employee profile, then sign out and sign in again.");
                return;
            }
            try {
                const selectedDepartment = departmentScoped
                    ? scopedDepartmentId
                    : departments.find((item) => item.name === departmentFilter)?.id;
                const result = await brainServeApi.employeePage({
                    query: query.trim() || undefined,
                    departmentId: selectedDepartment,
                    status:
                        statusFilter === "All"
                            ? undefined
                            : statusFilter.toUpperCase().replaceAll(" ", "_"),
                    page,
                    size: 50,
                    sort: "displayName,asc",
                }, controller.signal);
                if (!active) return;
                const departmentNames = new Map(
                    departments.map((item) => [item.id, item.name]),
                );
                setBackendPageEmployees(
                    result.content.map((item) => ({
                        id: item.employeeNumber,
                        uuid: item.id,
                        departmentId: item.departmentId,
                        name: item.displayName,
                        initials: visitorInitials(item.displayName),
                        role: item.designation,
                        department: departmentNames.get(item.departmentId) ?? "Unassigned",
                        email: item.officialEmail,
                        lifecycleProtected: item.lifecycleProtected,
                        status: employeeStatusLabel(item.status),
                    })),
                );
                setPageCount(Math.max(1, result.totalPages ?? 1));
                setTotalElements(result.totalElements ?? result.content.length);
            } catch (reason) {
                if (!active || controller.signal.aborted) return;
                setPageError(
                    reason instanceof Error
                        ? reason.message
                        : "Employee page could not be loaded.",
                );
            } finally {
                if (active && !controller.signal.aborted) setPageBusy(false);
            }
        }, 250);
        return () => {
            active = false;
            controller.abort();
            window.clearTimeout(timer);
        };
    }, [
        departmentFilter,
        departmentScoped,
        departments,
        page,
        pageRequestRevision,
        query,
        refreshKey,
        role,
        scopedDepartmentId,
        statusFilter,
    ]);
    const employeeTransitions: Record<Employee["status"], Employee["status"][]> =
        {
            Onboarding: ["Active", "Suspended", "Inactive"],
            Active: ["On leave", "Notice period", "Suspended", "Terminated"],
            "On leave": ["Active", "Notice period", "Suspended"],
            "Notice period": ["Resigned", "Active", "Terminated"],
            Suspended: ["Active", "Terminated", "Inactive"],
            Resigned: ["Inactive"],
            Terminated: ["Inactive"],
            Inactive: [],
        };
    const transitions = (status: Employee["status"]): Employee["status"][] =>
        employeeTransitions[status];
    const directoryOnly = role === "Employee";
    const teamLeadView = role === "Team Lead";
    const applyStatus = async (
        employee: Employee,
        nextStatus: Employee["status"],
    ) => {
        if (!nextStatus) return;
        setBusyEmployeeId(employee.uuid ?? employee.id);
        try {
            await onStatus(employee, nextStatus);
        } finally {
            setBusyEmployeeId("");
        }
    };
    return (
        <>
            <PageTitle
                eyebrow="EMPLOYEE MANAGEMENT"
                title={
                    directoryOnly
                        ? "BrainServe Connect directory"
                        : teamLeadView
                            ? "Your department team"
                            : "Your people, thoughtfully managed"
                }
                detail={
                    directoryOnly
                        ? "Find public contact details for colleagues in your assigned department."
                        : teamLeadView
                            ? "A department-scoped directory for the people and appointments you lead."
                            : "Recruit, place on leave, record notice/resignation and archive access without losing history."
                }
                action={
                    ["HR Admin", "CEO"].includes(role) && (
                        <button className="button button-primary" onClick={onAdd}>
                            <UserPlus size={17} /> Add employee
                        </button>
                    )
                }
            />
            {role === "HR Admin" && (
                <section className="panel glass-panel department-assignment-queue">
                    <div className="panel-heading">
                        <div>
                            <span>APPROVED EMPLOYEE ACCOUNTS</span>
                            <h2>Assign department and create employee ID</h2>
                            <p>
                                After HR approves an Employee login, complete the employee
                                profile here. Department assignment is required before
                                activation and Team Lead promotion.
                            </p>
                        </div>
                        <b>{unassignedAccounts.length}</b>
                    </div>
                    {unassignedAccounts.length ? (
                        <div className="department-assignment-list">
                            {unassignedAccounts.map((account) => (
                                <div key={account.userId}>
                  <span className="avatar">
                    {visitorInitials(account.fullName)}
                  </span>
                                    <span>
                    <strong>{account.fullName}</strong>
                    <small>{account.email} · Approved Employee login</small>
                  </span>
                                    <button
                                        className="button button-primary"
                                        onClick={() => onAssignDepartment(account)}
                                    >
                                        <Building2 size={15} /> Assign department
                                    </button>
                                </div>
                            ))}
                        </div>
                    ) : (
                        <div className="assignment-queue-clear">
                            <CheckCircle2 size={18} />
                            <span>
                <strong>All approved Employee accounts are assigned</strong>
                <small>
                  Newly approved Employee registrations will appear here
                  automatically.
                </small>
              </span>
                        </div>
                    )}
                </section>
            )}
            <section className="employee-summary">
                <div>
                    <strong>{displayedTotalElements}</strong>
                    <span>Matching records</span>
                </div>
                <i />
                <div>
                    <strong>{departmentCount}</strong>
                    <span>
            {departmentScoped ? "Assigned department" : "Departments on page"}
          </span>
                </div>
                <i />
                <div>
                    <strong>
                        {
                            visibleEmployees.filter((item) => item.status === "Onboarding")
                                .length
                        }
                    </strong>
                    <span>Onboarding on page</span>
                </div>
                <i />
                <div>
                    <strong>
                        {visibleEmployees.filter((item) => item.status === "Active").length}
                    </strong>
                    <span>Active on page</span>
                </div>
            </section>
            <div className="toolbar glass-panel">
                <div className="toolbar-search wide">
                    <Search size={17} />
                    <input
                        value={query}
                        onChange={(e) => {
                            setPage(0);
                            setQuery(e.target.value);
                        }}
                        placeholder="Search by name or employee ID"
                    />
                </div>
                {departmentScoped ? (
                    <div
                        className="scoped-department-label"
                        aria-label="Assigned department"
                    >
                        {scopedDepartment?.name ?? currentEmployee?.department ?? "Assigned department"}
                    </div>
                ) : (
                    <select
                        value={departmentFilter}
                        onChange={(event) => {
                            setPage(0);
                            setDepartmentFilter(event.target.value);
                        }}
                    >
                        <option value="All">All departments</option>
                        {departments
                            .filter((item) => item.active)
                            .map((department) => (
                                <option key={department.id}>{department.name}</option>
                            ))}
                    </select>
                )}
                <select
                    value={statusFilter}
                    onChange={(event) => {
                        setPage(0);
                        setStatusFilter(event.target.value);
                    }}
                >
                    <option value="All">All statuses</option>
                    {[
                        "Onboarding",
                        "Active",
                        "On leave",
                        "Notice period",
                        "Suspended",
                        "Resigned",
                        "Terminated",
                        "Inactive",
                    ].map((status) => (
                        <option key={status}>{status}</option>
                    ))}
                </select>
            </div>
            {pageError && (
                <div className="login-error" role="alert">
                    <span>{pageError}</span>
                    <button
                        type="button"
                        className="button button-secondary"
                        disabled={pageBusy}
                        onClick={() => setPageRequestRevision((value) => value + 1)}
                    >
                        Retry
                    </button>
                </div>
            )}
            <div className="data-table glass-panel">
                <div className="table-head employee-table">
                    <span>Employee</span>
                    <span>Employee ID</span>
                    <span>Department</span>
                    <span>Designation</span>
                    <span>Status</span>
                    <span>Lifecycle action</span>
                </div>
                {filtered.map((item) => {
                    const busy = busyEmployeeId === (item.uuid ?? item.id);
                    const linkedAccount = staffAccounts.find((account) =>
                        (Boolean(item.uuid) && account.employeeId === item.uuid)
                        || account.email.toLowerCase() === item.email.toLowerCase());
                    const isCompanyExecutive = item.lifecycleProtected
                        || (linkedAccount?.roles.includes("ROLE_CEO") ?? false);
                    return (
                        <div className="table-row employee-table" key={item.id}>
                            <div className="person-cell">
                                <span className="avatar">{item.initials}</span>
                                <span>
                  <strong>{item.name}</strong>
                  <small>{item.email}</small>
                </span>
                            </div>
                            <div>
                                <strong className="mono-text">{item.id}</strong>
                            </div>
                            <div>
                                <strong>{item.department}</strong>
                                <small>Hyderabad HQ</small>
                            </div>
                            <div>
                                <strong>{item.role}</strong>
                                <small>{isCompanyExecutive ? "Company-wide authority · System Admin managed" : "Full time"}</small>
                            </div>
                            <StatusPill status={item.status} />
                            <div className="employee-record-actions">
                                {role === "HR Admin" && !isCompanyExecutive && transitions(item.status).length ? (
                                    <select
                                        aria-label={`Change ${item.name} status`}
                                        value=""
                                        disabled={Boolean(busyEmployeeId)}
                                        onChange={(event) =>
                                            void applyStatus(
                                                item,
                                                event.target.value as Employee["status"],
                                            )
                                        }
                                    >
                                        <option value="" disabled>
                                            {busy ? "Saving…" : "Choose action"}
                                        </option>
                                        {transitions(item.status).map((status) => (
                                            <option key={status} value={status}>
                                                {status === "Terminated"
                                                    ? "Request termination…"
                                                    : status}
                                            </option>
                                        ))}
                                    </select>
                                ) : isCompanyExecutive && role === "HR Admin" ? (
                                    <span className="protected-lifecycle-label"><ShieldCheck size={15} /> Protected</span>
                                ) : null}
                                {["HR Admin", "CEO"].includes(role) && (
                                    <button type="button" className="button button-quiet employee-record-button"
                                            onClick={() => setRecordEmployee(item)}>
                                        <FileText size={14} /> Record
                                    </button>
                                )}
                            </div>
                        </div>
                    );
                })}
                {filtered.length === 0 && (
                    <div className="empty-state">
                        <Users size={28} />
                        <strong>
                            {pageBusy
                                ? "Loading employees…"
                                : pageError
                                    ? "Employees are temporarily unavailable"
                                    : "No matching employees"}
                        </strong>
                    </div>
                )}
                <div className="bounded-pagination page-number-pagination">
                    <button
                        className="button button-secondary"
                        disabled={pageBusy || page === 0}
                        onClick={() => setPage((value) => Math.max(0, value - 1))}
                    >
                        Previous
                    </button>
                    <span>
            Page {page + 1} of {pageCount}
          </span>
                    <button
                        className="button button-secondary"
                        disabled={pageBusy || page + 1 >= pageCount}
                        onClick={() => setPage((value) => value + 1)}
                    >
                        Next
                    </button>
                </div>
            </div>
            {recordEmployee && <EmployeeServicePanel employee={recordEmployee} role={role}
                                                     onClose={() => setRecordEmployee(null)} />}
        </>
    );
}

