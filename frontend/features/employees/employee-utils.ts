import { type Department, type Employee } from "../../types/workspace";

export function employeeStatusLabel(status: string): Employee["status"] {
    const labels: Record<string, Employee["status"]> = { ACTIVE: "Active", ON_LEAVE: "On leave", ONBOARDING: "Onboarding",
        NOTICE_PERIOD: "Notice period", SUSPENDED: "Suspended", RESIGNED: "Resigned", TERMINATED: "Terminated", INACTIVE: "Inactive" };
    return labels[status] ?? "Onboarding";
}

export const employeeStatusCode: Record<Employee["status"], string> = { Active: "ACTIVE", "On leave": "ON_LEAVE",
    Onboarding: "ONBOARDING", "Notice period": "NOTICE_PERIOD", Suspended: "SUSPENDED", Resigned: "RESIGNED",
    Terminated: "TERMINATED", Inactive: "INACTIVE" };

export function belongsToDepartment(employee: Employee, department: Department) {
    return employee.departmentId ? employee.departmentId === department.id : employee.department === department.name;
}

