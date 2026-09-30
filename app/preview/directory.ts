import { type DepartmentHrAssignment, type TeamLeadAssignment } from "../lib/api";
import { type Department, type Employee } from "../shared/types/workspace";
import { newClientId } from "../shared/utils/ids";
import { readDemoAccounts } from "./accounts";
import {
    initialDepartmentHrAssignments,
    initialDepartments,
    initialEmployees,
    initialTeamLeadAssignments,
} from "./fixtures/workspace";
import { readDemoManagerAssignments } from "./manager-assignments";
import {
    DEMO_DEPARTMENT_HR_ASSIGNMENTS_KEY,
    DEMO_DEPARTMENTS_KEY,
    DEMO_EMPLOYEES_KEY,
    DEMO_TEAM_LEAD_ASSIGNMENTS_KEY,
} from "./storage-keys";

export function readDemoEmployees(): Employee[] {
    if (typeof window === "undefined") return initialEmployees;
    try {
        const value = JSON.parse(window.localStorage.getItem(DEMO_EMPLOYEES_KEY) ?? "[]");
        const employees: Employee[] = Array.isArray(value) && value.length ? value : initialEmployees;
        const departments = readDemoDepartments();
        let migrated = false;
        const activeManagers = readDemoManagerAssignments();
        const normalized = employees.map((employee) => {
            const managerAssignment = activeManagers.find((assignment) => assignment.active
                && assignment.managerEmployeeId === (employee.uuid ?? employee.id));
            const assignedDepartment = managerAssignment
                ? departments.find((item) => item.id === managerAssignment.departmentId)
                : undefined;
            if (managerAssignment && assignedDepartment
                && (employee.departmentId !== assignedDepartment.id
                    || employee.department !== assignedDepartment.name
                    || employee.role !== "Department Manager"
                    || employee.status !== "Active")) {
                migrated = true;
                return { ...employee, departmentId: assignedDepartment.id,
                    department: assignedDepartment.name, role: "Department Manager", status: "Active" as const };
            }
            if (employee.departmentId && departments.some((department) => department.id === employee.departmentId)) {
                return employee;
            }
            const department = departments.find((item) => item.name.toLowerCase() === employee.department?.toLowerCase()
                || item.code.toLowerCase() === employee.department?.toLowerCase()
                || item.id === employee.departmentId);
            if (!department) return employee;
            migrated = true;
            return { ...employee, departmentId: department.id, department: department.name };
        });
        if (migrated) writeDemoEmployees(normalized);
        return normalized;
    } catch { return initialEmployees; }
}

export function writeDemoEmployees(items: Employee[]) {
    if (typeof window !== "undefined") window.localStorage.setItem(DEMO_EMPLOYEES_KEY, JSON.stringify(items));
}

export function readDemoDepartments(): Department[] {
    if (typeof window === "undefined") return initialDepartments;
    try {
        const value = JSON.parse(window.localStorage.getItem(DEMO_DEPARTMENTS_KEY) ?? "[]");
        return Array.isArray(value) && value.length ? value : initialDepartments;
    } catch { return initialDepartments; }
}

export function writeDemoDepartments(items: Department[]) {
    if (typeof window !== "undefined") window.localStorage.setItem(DEMO_DEPARTMENTS_KEY, JSON.stringify(items));
}

export function readDemoTeamLeadAssignments(): TeamLeadAssignment[] {
    if (typeof window === "undefined") return initialTeamLeadAssignments;
    try {
        const value = JSON.parse(window.localStorage.getItem(DEMO_TEAM_LEAD_ASSIGNMENTS_KEY) ?? "[]");
        let assignments: TeamLeadAssignment[] = Array.isArray(value) && value.length
            ? value : initialTeamLeadAssignments;
        let migrated = false;
        const employees = readDemoEmployees();
        const departments = readDemoDepartments();
        assignments = assignments.map((assignment) => {
            if (departments.some((department) => department.id === assignment.departmentId)) return assignment;
            const department = departments.find((item) => item.code.toLowerCase() === assignment.departmentId.toLowerCase()
                || item.name.toLowerCase() === assignment.departmentId.toLowerCase());
            if (!department) return assignment;
            migrated = true;
            return { ...assignment, departmentId: department.id };
        });
        const legacyTeamLeads = readDemoAccounts().filter((account) => account.status === "ACTIVE"
            && account.role === "ROLE_TEAM_LEAD");
        for (const account of legacyTeamLeads) {
            const employee = employees.find((item) => (account.employeeId && (item.uuid ?? item.id) === account.employeeId)
                || item.email.toLowerCase() === account.email.toLowerCase()
                || item.name.trim().toLowerCase() === account.fullName.trim().toLowerCase());
            if (!employee?.departmentId || assignments.some((assignment) => assignment.active
                && (assignment.teamLeadUserId === account.id
                    || assignment.teamLeadEmployeeId === (employee.uuid ?? employee.id)))) continue;
            const now = new Date().toISOString();
            assignments = [{ id: newClientId(), departmentId: employee.departmentId,
                teamLeadUserId: account.id, teamLeadEmployeeId: employee.uuid ?? employee.id,
                active: true, assignedByUserId: "demo-legacy-migration", assignedAt: now,
                endedByUserId: null, endedAt: null }, ...assignments.map((assignment) =>
                assignment.active && assignment.departmentId === employee.departmentId
                    ? { ...assignment, active: false, endedByUserId: "demo-legacy-migration", endedAt: now }
                    : assignment)];
            migrated = true;
        }
        if (migrated) writeDemoTeamLeadAssignments(assignments);
        return assignments;
    } catch { return initialTeamLeadAssignments; }
}

export function writeDemoTeamLeadAssignments(items: TeamLeadAssignment[]) {
    if (typeof window !== "undefined") window.localStorage.setItem(DEMO_TEAM_LEAD_ASSIGNMENTS_KEY, JSON.stringify(items));
}

export function readDemoDepartmentHrAssignments(): DepartmentHrAssignment[] {
    if (typeof window === "undefined") return initialDepartmentHrAssignments;
    try {
        const value = JSON.parse(window.localStorage.getItem(DEMO_DEPARTMENT_HR_ASSIGNMENTS_KEY) ?? "[]");
        return Array.isArray(value) && value.length ? value : initialDepartmentHrAssignments;
    } catch { return initialDepartmentHrAssignments; }
}

export function writeDemoDepartmentHrAssignments(items: DepartmentHrAssignment[]) {
    if (typeof window !== "undefined") window.localStorage.setItem(DEMO_DEPARTMENT_HR_ASSIGNMENTS_KEY, JSON.stringify(items));
}

