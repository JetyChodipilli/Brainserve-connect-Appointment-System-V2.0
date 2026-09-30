"use client";

import { ApiError, brainServeApi, isBackendConfigured } from "../../../services/brainserve-api";
import { readDemoAccounts, writeDemoAccounts } from "../../../preview/accounts";
import { writeDemoEmployees } from "../../../preview/directory";
import { type Employee } from "../../../types/workspace";
import { fail } from "../../../utils/errors";
import { type WorkspaceState } from "../../../hooks/workspace/use-workspace-state";
import { visitorInitials } from "../../appointments/appointment-utils";
import { employeeStatusCode } from "../employee-utils";
import { type FormEvent } from "react";

export function createEmployeeActions(workspace: Pick<WorkspaceState, "setOperationError" | "departments" | "employees" | "setEmployees" | "setStaffAccounts" | "setDepartmentSummaries" | "setMetrics" | "setEmployeeModal" | "setEmployeeDepartmentId" | "setEmployeeAccountId" | "setTerminationEmployee">) {
    const { setOperationError, departments, employees, setEmployees, setStaffAccounts, setDepartmentSummaries, setMetrics, setEmployeeModal, setEmployeeDepartmentId, setEmployeeAccountId, setTerminationEmployee } = workspace;

    const addEmployee = async (event: FormEvent<HTMLFormElement>) => {
        event.preventDefault();
        const data = new FormData(event.currentTarget);
        const name = String(data.get("name")).trim().replace(/\s+/g, " ");
        const officialEmail = String(data.get("email")).trim().toLowerCase();
        if (!isBackendConfigured && !officialEmail.endsWith("@brainserve.in")) {
            setOperationError("Use an approved @brainserve.in Employee login before assigning a department."); return;
        }
        if (name.length < 2) { setOperationError("Enter the employee's full name."); return; }
        const nameParts = name.split(" ");
        const firstName = nameParts.shift() ?? "";
        const lastName = nameParts.join(" ");
        const departmentId = String(data.get("departmentId"));
        const department = departments.find((item) => item.id === departmentId);
        setOperationError("");
        try {
            let created: Employee;
            if (isBackendConfigured) {
                const response = await brainServeApi.createEmployee({
                    firstName, lastName, officialEmail, phoneNumber: String(data.get("phone")) || null,
                    departmentId, designation: String(data.get("designation")),
                    joiningDate: String(data.get("joiningDate")),
                });
                created = {
                    id: response.employee.employeeNumber, uuid: response.employee.id, departmentId,
                    name: response.employee.displayName,
                    initials: visitorInitials(response.employee.displayName), role: response.employee.designation,
                    department: department?.name ?? "Unassigned", email: response.employee.officialEmail, status: "Onboarding",
                };
            } else {
                created = { id: `BSPL-${department?.code ?? "EMP"}-${String(employees.length + 70).padStart(4, "0")}`, departmentId,
                    name, initials: visitorInitials(name), role: String(data.get("designation")),
                    department: department?.name ?? "Unassigned", email: officialEmail, status: "Onboarding" };
            }
            setEmployees((items) => {
                const updated = [...items, created];
                if (!isBackendConfigured) writeDemoEmployees(updated);
                return updated;
            });
            const linkedEmployeeId = created.uuid ?? created.id;
            setStaffAccounts((items) => items.map((account) => account.email.toLowerCase() === created.email.toLowerCase()
                ? { ...account, employeeId: linkedEmployeeId }
                : account));
            if (!isBackendConfigured) writeDemoAccounts(readDemoAccounts().map((account) =>
                account.email.toLowerCase() === created.email.toLowerCase() ? { ...account, employeeId: linkedEmployeeId } : account));
            setDepartmentSummaries((items) => {
                const existing = items.find((item) => item.departmentId === departmentId);
                if (!existing) return [...items, { departmentId, totalEmployees: 1, activeEmployees: 0, onLeaveEmployees: 0, onboardingEmployees: 1 }];
                return items.map((item) => item.departmentId === departmentId
                    ? { ...item, totalEmployees: item.totalEmployees + 1, onboardingEmployees: item.onboardingEmployees + 1 }
                    : item);
            });
            if (isBackendConfigured) {
                try { setStaffAccounts(await brainServeApi.staffAccounts()); }
                catch { setOperationError("Employee created, but the pending login list could not be refreshed. Reload the workspace before approval."); }
            }
            setMetrics((current) => ({ ...current, totalEmployees: current.totalEmployees + 1 }));
            setEmployeeModal(false);
            setEmployeeDepartmentId(undefined);
            setEmployeeAccountId(undefined);
        } catch (reason) { setOperationError(reason instanceof ApiError ? reason.message : "The employee could not be created."); }
    };

    const changeEmployeeLifecycle = async (employee: Employee, nextStatus: Employee["status"]) => {
        setOperationError("");
        if (nextStatus === "Terminated") {
            setTerminationEmployee(employee);
            return;
        }
        try {
            if (isBackendConfigured) {
                if (!employee.uuid) fail("This employee record is missing its database identifier. Refresh the directory and try again.");
                await brainServeApi.changeEmployeeStatus(employee.uuid, employeeStatusCode[nextStatus]);
                setDepartmentSummaries(await brainServeApi.departmentEmployeeSummary());
            }
            setEmployees((items) => {
                const updated = items.map((item) => item.id === employee.id ? { ...item, status: nextStatus } : item);
                if (!isBackendConfigured) writeDemoEmployees(updated);
                return updated;
            });
            setMetrics((current) => ({ ...current, activeEmployees: current.activeEmployees
                    + (employee.status !== "Active" && nextStatus === "Active" ? 1 : 0)
                    - (employee.status === "Active" && nextStatus !== "Active" ? 1 : 0) }));
        } catch (reason) { setOperationError(reason instanceof Error ? reason.message : "Employee lifecycle update failed."); }
    };
    return { addEmployee, changeEmployeeLifecycle };
}
