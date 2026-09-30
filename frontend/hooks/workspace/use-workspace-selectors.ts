"use client";

import { needsAppointmentAction } from "../../features/appointments/appointment-utils";
import { rolePermissions } from "../../config/roles";
import { type Role, type View } from "../../types/workspace";
import { navItems } from "../../config/navigation";
import { type WorkspaceState } from "./use-workspace-state";
import { useMemo } from "react";

export function useWorkspaceSelectors(workspace: Pick<WorkspaceState, "role" | "employees" | "userEmail" | "departments" | "staffAccounts" | "employeeAccountId" | "appointments" | "globalSearch">) {
    const { role, employees, userEmail, departments, staffAccounts, employeeAccountId, appointments, globalSearch } = workspace;

    const permittedNav = navItems.filter((item) => rolePermissions[role].includes(item.id));
    const currentEmployee = employees.find((employee) => employee.email.toLowerCase() === userEmail.toLowerCase());
    const isDepartmentDirectoryRole = ["HR Admin", "Manager", "Team Lead", "Employee"].includes(role);
    const employeeDirectoryScopeId = isDepartmentDirectoryRole
        ? currentEmployee?.departmentId ?? (departments.length === 1 ? departments[0].id : undefined)
        : undefined;
    const searchableEmployees = useMemo(() => isDepartmentDirectoryRole
            ? employeeDirectoryScopeId
                ? employees.filter((employee) => employee.departmentId === employeeDirectoryScopeId)
                : []
            : employees,
        [employeeDirectoryScopeId, employees, isDepartmentDirectoryRole]);
    const unassignedEmployeeAccounts = staffAccounts.filter((account) => account.enabled && account.status === "ACTIVE"
        && account.roles.length === 1 && account.roles[0] === "ROLE_EMPLOYEE" && !account.employeeId
        && !employees.some((employee) => employee.email.toLowerCase() === account.email.toLowerCase()));
    const selectedEmployeeAccount = staffAccounts.find((account) => account.userId === employeeAccountId);
    const pendingAppointmentCount = appointments.filter((item) => needsAppointmentAction(role, item)).length;
    const globalSearchResults = useMemo(() => {
        const query = globalSearch.trim().toLowerCase();
        if (query.length < 2) return [];
        const appointmentResults = appointments.filter((item) =>
            `${item.visitor} ${item.company} ${item.host} ${item.purpose} ${item.referenceNumber ?? ""}`.toLowerCase().includes(query))
            .map((item) => ({ id: `appointment-${item.id}`, view: (["Employee", "Team Lead"] as Role[]).includes(role) ? "notifications" as View : "appointments" as View,
                title: item.visitor, detail: `${item.type} · ${item.referenceNumber ?? item.host}` }));
        const employeeResults = searchableEmployees.filter((item) =>
            `${item.name} ${item.email} ${item.id} ${item.role} ${item.department}`.toLowerCase().includes(query))
            .map((item) => ({ id: `employee-${item.id}`, view: "employees" as View,
                title: item.name, detail: `${item.role} · ${item.department}` }));
        return [...appointmentResults, ...employeeResults].slice(0, 8);
    }, [appointments, globalSearch, role, searchableEmployees]);
    return { permittedNav, currentEmployee, isDepartmentDirectoryRole, employeeDirectoryScopeId, searchableEmployees, unassignedEmployeeAccounts, selectedEmployeeAccount, pendingAppointmentCount, globalSearchResults };
}

export type WorkspaceSelectors = ReturnType<typeof useWorkspaceSelectors>;
