"use client";

import { needsAppointmentAction } from "../../features/appointments/appointment-utils";
import { rolePermissions } from "../../config/roles";
import { navItems } from "../../config/navigation";
import { type WorkspaceState } from "./use-workspace-state";
import { useMemo } from "react";

export function useWorkspaceSelectors(workspace: Pick<WorkspaceState, "role" | "employees" | "userEmail" | "departments" | "staffAccounts" | "employeeAccountId" | "appointments">) {
    const { role, employees, userEmail, departments, staffAccounts, employeeAccountId, appointments } = workspace;

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
    return { permittedNav, currentEmployee, isDepartmentDirectoryRole, employeeDirectoryScopeId, searchableEmployees, unassignedEmployeeAccounts, selectedEmployeeAccount, pendingAppointmentCount };
}

export type WorkspaceSelectors = ReturnType<typeof useWorkspaceSelectors>;
