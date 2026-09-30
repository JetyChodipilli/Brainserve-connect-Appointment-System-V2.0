"use client";

import {
    ApiError,
    brainServeApi,
    type DepartmentHrAssignment,
    isBackendConfigured,
    type TeamLeadAssignment,
} from "../../../services/brainserve-api";
import { mergeDemoStaffAccounts, readDemoAccounts, writeDemoAccounts } from "../../../preview/accounts";
import {
    readDemoDepartmentHrAssignments,
    readDemoEmployees,
    readDemoTeamLeadAssignments,
    writeDemoDepartmentHrAssignments,
    writeDemoDepartments,
    writeDemoEmployees,
    writeDemoTeamLeadAssignments,
} from "../../../preview/directory";
import { readDemoManagerAssignments } from "../../../preview/manager-assignments";
import { demoSenderName } from "../../../preview/notifications";
import { type Department, type Employee } from "../../../types/workspace";
import { fail } from "../../../utils/errors";
import { newClientId } from "../../../utils/ids";
import { type WorkspaceState } from "../../../hooks/workspace/use-workspace-state";
import { visitorInitials } from "../../appointments/appointment-utils";
import { employeeStatusLabel } from "../../employees/employee-utils";
import { normalizeDepartmentCode, normalizeDepartmentName } from "../department-utils";

export function createOrganizationActions(workspace: Pick<WorkspaceState, "departments" | "setDepartments" | "setOperationError" | "setEmployees" | "employees" | "userEmail" | "teamLeadAssignments" | "setTeamLeadAssignments" | "setStaffAccounts" | "staffAccounts" | "setDepartmentHrAssignments" | "setDepartmentSummaries" | "setManagerAssignments" | "role" | "managerAssignments">) {
    const { departments, setDepartments, setOperationError, setEmployees, employees, userEmail, teamLeadAssignments, setTeamLeadAssignments, setStaffAccounts, staffAccounts, setDepartmentHrAssignments, setDepartmentSummaries, setManagerAssignments, role, managerAssignments } = workspace;

    const createDepartment = async (code: string, name: string) => {
        const normalizedCode = normalizeDepartmentCode(code);
        const normalizedName = normalizeDepartmentName(name);
        if (normalizedCode.length < 2) fail("Department code must contain at least two letters or numbers.");
        if (normalizedName.length < 2) fail("Department name must contain at least two characters.");
        if (departments.some((item) => item.code.toUpperCase() === normalizedCode)) {
            fail(`Department code ${normalizedCode} is already in use.`);
        }
        if (departments.some((item) => item.name.trim().toLowerCase() === normalizedName.toLowerCase())) {
            fail(`A department named ${normalizedName} already exists.`);
        }
        const created = isBackendConfigured
            ? await brainServeApi.createDepartment(normalizedCode, normalizedName)
            : { id: newClientId(), code: normalizedCode, name: normalizedName, active: true, version: 0 };
        setDepartments((items) => {
            const updated = [...items, created];
            if (!isBackendConfigured) writeDemoDepartments(updated);
            return updated;
        });
        return created;
    };

    const joinExecutiveDepartment = async (payload: { departmentId: string; phoneNumber: string;
        designation: string; joiningDate: string }) => {
        setOperationError("");
        try {
            if (isBackendConfigured) {
                const profile = await brainServeApi.upsertExecutiveProfile(payload);
                const department = departments.find((item) => item.id === profile.departmentId);
                const mapped: Employee = { id: profile.employeeNumber, uuid: profile.id, departmentId: profile.departmentId,
                    name: profile.displayName, initials: visitorInitials(profile.displayName), role: profile.designation,
                    department: department?.name ?? "Executive department", email: profile.officialEmail,
                    status: employeeStatusLabel(profile.status) };
                setEmployees((items) => [mapped, ...items.filter((item) => (item.uuid ?? item.id) !== profile.id
                    && item.email.toLowerCase() !== profile.officialEmail.toLowerCase())]);
            } else {
                const department = departments.find((item) => item.id === payload.departmentId);
                const existing = employees.find((item) => item.email.toLowerCase() === userEmail.toLowerCase());
                const name = existing?.name ?? demoSenderName("CEO", userEmail);
                const mapped: Employee = { id: existing?.id ?? `BSPL-${department?.code ?? "EXEC"}-${String(Date.now()).slice(-4)}`,
                    uuid: existing?.uuid ?? newClientId(), departmentId: payload.departmentId,
                    name, initials: visitorInitials(name), role: payload.designation,
                    department: department?.name ?? "Executive department", email: userEmail, status: "Active" };
                setEmployees((items) => {
                    const updated = [mapped, ...items.filter((item) => item.email.toLowerCase() !== userEmail.toLowerCase())];
                    writeDemoEmployees(updated); return updated;
                });
                writeDemoAccounts(readDemoAccounts().map((account) => account.email.toLowerCase() === userEmail.toLowerCase()
                    ? { ...account, employeeId: mapped.uuid } : account));
            }
            return true;
        } catch (reason) {
            setOperationError(reason instanceof Error ? reason.message : "The CEO department profile could not be updated.");
            return false;
        }
    };

    const toggleDepartment = async (department: Department) => {
        setOperationError("");
        try {
            const updated = isBackendConfigured
                ? await brainServeApi.changeDepartmentStatus(department.id, !department.active)
                : { ...department, active: !department.active, version: department.version + 1 };
            setDepartments((items) => {
                const values = items.map((item) => item.id === department.id ? updated : item);
                if (!isBackendConfigured) writeDemoDepartments(values);
                return values;
            });
        } catch (reason) { setOperationError(reason instanceof Error ? reason.message : "Department status could not be changed."); }
    };

    const assignTeamLead = async (departmentId: string, employeeId: string) => {
        setOperationError("");
        try {
            const previousAssignment = teamLeadAssignments.find((item) => item.departmentId === departmentId && item.active);
            const promotedEmployee = employees.find((item) => (item.uuid ?? item.id) === employeeId);
            const promotedAccount = !isBackendConfigured ? readDemoAccounts().find((account) =>
                account.employeeId === employeeId || account.email.toLowerCase() === promotedEmployee?.email.toLowerCase()) : undefined;
            const created = isBackendConfigured ? await brainServeApi.assignTeamLead(departmentId, employeeId) : {
                id: newClientId(), departmentId, teamLeadUserId: promotedAccount?.id ?? `demo-tl-${employeeId}`,
                teamLeadEmployeeId: employeeId, active: true, assignedByUserId: "demo-hr-admin",
                assignedAt: new Date().toISOString(), endedByUserId: null, endedAt: null,
            };
            setTeamLeadAssignments((items) => {
                const updated = [created, ...items.map((item) => item.departmentId === departmentId && item.active
                    ? { ...item, active: false, endedAt: new Date().toISOString() } : item)];
                if (!isBackendConfigured) writeDemoTeamLeadAssignments(updated);
                return updated;
            });
            if (isBackendConfigured) {
                setStaffAccounts(await brainServeApi.staffAccounts());
            } else {
                const previousEmployee = employees.find((item) => (item.uuid ?? item.id) === previousAssignment?.teamLeadEmployeeId);
                setStaffAccounts((items) => items.map((account) => {
                    if (account.email.toLowerCase() === promotedEmployee?.email.toLowerCase()) return {
                        ...account,
                        roles: ["ROLE_TEAM_LEAD"],
                        effectivePermissions: ["TEAM_LEAD_DIRECTORY_VIEW", "TEAM_LEAD_VISIT_APPROVE", "APPOINTMENT_REQUEST", "INTERNAL_NOTIFICATION_READ", "INTERNAL_NOTIFICATION_SEND"],
                    };
                    if (previousEmployee && account.email.toLowerCase() === previousEmployee.email.toLowerCase()) return {
                        ...account,
                        roles: ["ROLE_EMPLOYEE"],
                        effectivePermissions: ["EMPLOYEE_READ", "APPOINTMENT_REQUEST", "APPOINTMENT_APPROVE", "APPOINTMENT_REJECT", "INTERNAL_NOTIFICATION_READ", "INTERNAL_NOTIFICATION_SEND"],
                    };
                    return account;
                }));
                writeDemoAccounts(readDemoAccounts().map((account) => {
                    if (account.email.toLowerCase() === promotedEmployee?.email.toLowerCase()) return { ...account, role: "ROLE_TEAM_LEAD" };
                    if (previousEmployee && account.email.toLowerCase() === previousEmployee.email.toLowerCase()) return { ...account, role: "ROLE_EMPLOYEE" };
                    return account;
                }));
            }
            return true;
        } catch (reason) {
            setOperationError(reason instanceof ApiError ? reason.message : "Team Lead assignment failed.");
            return false;
        }
    };

    const endTeamLeadAssignment = async (assignment: TeamLeadAssignment) => {
        setOperationError("");
        try {
            const ended = isBackendConfigured ? await brainServeApi.endTeamLeadAssignment(assignment.id)
                : { ...assignment, active: false, endedAt: new Date().toISOString() };
            setTeamLeadAssignments((items) => {
                const updated = items.map((item) => item.id === assignment.id ? ended : item);
                if (!isBackendConfigured) writeDemoTeamLeadAssignments(updated);
                return updated;
            });
            if (isBackendConfigured) {
                setStaffAccounts(await brainServeApi.staffAccounts());
            } else {
                const formerLead = employees.find((item) => (item.uuid ?? item.id) === assignment.teamLeadEmployeeId);
                setStaffAccounts((items) => items.map((account) => formerLead && account.email.toLowerCase() === formerLead.email.toLowerCase()
                    ? {
                        ...account,
                        roles: ["ROLE_EMPLOYEE"],
                        effectivePermissions: ["EMPLOYEE_READ", "APPOINTMENT_REQUEST", "APPOINTMENT_APPROVE", "APPOINTMENT_REJECT", "INTERNAL_NOTIFICATION_READ", "INTERNAL_NOTIFICATION_SEND"],
                    }
                    : account));
                if (formerLead) writeDemoAccounts(readDemoAccounts().map((account) => account.email.toLowerCase() === formerLead.email.toLowerCase()
                    ? { ...account, role: "ROLE_EMPLOYEE" }
                    : account));
            }
        } catch (reason) { setOperationError(reason instanceof ApiError ? reason.message : "Team Lead assignment could not be ended."); }
    };

    const assignDepartmentHr = async (departmentId: string, hrUserId: string) => {
        setOperationError("");
        try {
            const created = isBackendConfigured ? await brainServeApi.assignDepartmentHr(departmentId, hrUserId) : (() => {
                const account = staffAccounts.find((item) => item.userId === hrUserId);
                if (!account?.employeeId) fail("Select an active HR account linked to an employee profile.");
                return { id: newClientId(), departmentId, hrUserId, hrEmployeeId: account.employeeId,
                    active: true, assignedByUserId: "demo-ceo", assignedAt: new Date().toISOString(),
                    endedByUserId: null, endedAt: null } satisfies DepartmentHrAssignment;
            })();
            setDepartmentHrAssignments((items) => {
                const updated = [created, ...items.map((item) => item.active
                && (item.departmentId === departmentId || item.hrUserId === hrUserId)
                    ? { ...item, active: false, endedAt: new Date().toISOString(), endedByUserId: "demo-ceo" } : item)];
                if (!isBackendConfigured) writeDemoDepartmentHrAssignments(updated);
                return updated;
            });
            const department = departments.find((item) => item.id === departmentId);
            setEmployees((items) => {
                const updated = items.map((employee) => (employee.uuid ?? employee.id) === created.hrEmployeeId
                    ? { ...employee, departmentId, department: department?.name ?? employee.department }
                    : employee);
                if (!isBackendConfigured) writeDemoEmployees(updated);
                return updated;
            });
            if (isBackendConfigured) setDepartmentSummaries(await brainServeApi.departmentEmployeeSummary());
            return true;
        } catch (reason) {
            setOperationError(reason instanceof Error ? reason.message : "Department HR assignment failed.");
            return false;
        }
    };

    const endDepartmentHr = async (assignment: DepartmentHrAssignment) => {
        setOperationError("");
        try {
            const ended = isBackendConfigured ? await brainServeApi.endDepartmentHrAssignment(assignment.id)
                : { ...assignment, active: false, endedAt: new Date().toISOString(), endedByUserId: "demo-ceo" };
            setDepartmentHrAssignments((items) => {
                const updated = items.map((item) => item.id === assignment.id ? ended : item);
                if (!isBackendConfigured) writeDemoDepartmentHrAssignments(updated);
                return updated;
            });
        } catch (reason) { setOperationError(reason instanceof Error ? reason.message : "Department HR assignment could not be ended."); }
    };

    const refreshRoleAssignments = async () => {
        if (!isBackendConfigured) {
            setTeamLeadAssignments(readDemoTeamLeadAssignments());
            setDepartmentHrAssignments(readDemoDepartmentHrAssignments());
            setManagerAssignments(readDemoManagerAssignments());
            setEmployees(readDemoEmployees());
            setStaffAccounts(mergeDemoStaffAccounts);
            return;
        }
        const [leadAssignments, hrAssignments, managerAssignmentList, employeePage, summaries] = await Promise.all([
            brainServeApi.teamLeadAssignments(), brainServeApi.departmentHrAssignments(),
            ["CEO", "System Admin"].includes(role) ? brainServeApi.managerAssignments()
                : Promise.resolve(managerAssignments),
            brainServeApi.employees(), brainServeApi.departmentEmployeeSummary(),
        ]);
        setTeamLeadAssignments(leadAssignments); setDepartmentHrAssignments(hrAssignments);
        setManagerAssignments(managerAssignmentList);
        setDepartmentSummaries(summaries);
        setEmployees(employeePage.content.map((item) => { const department = departments.find((value) => value.id === item.departmentId);
            return { id: item.employeeNumber, uuid: item.id, departmentId: item.departmentId,
                name: item.displayName, initials: visitorInitials(item.displayName), role: item.designation,
                department: department?.name ?? "Department", email: item.officialEmail,
                status: employeeStatusLabel(item.status) }; }));
    };
    return { createDepartment, joinExecutiveDepartment, toggleDepartment, assignTeamLead, endTeamLeadAssignment, assignDepartmentHr, endDepartmentHr, refreshRoleAssignments };
}
