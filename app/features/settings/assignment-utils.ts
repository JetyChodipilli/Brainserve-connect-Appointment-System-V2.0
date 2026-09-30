import { readDemoEmployees } from "../../preview/directory";
import { type Department } from "../../shared/types/workspace";

export function employeesDepartment(employeeId: string, departments: Department[]) {
    return readDemoEmployees().find((employee) => (employee.uuid ?? employee.id) === employeeId)?.departmentId
        ?? departments.find((department) => department.active)?.id ?? "";
}

