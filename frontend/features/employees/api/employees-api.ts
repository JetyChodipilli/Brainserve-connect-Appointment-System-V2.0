import { apiRequest, requestSpringPage } from "../../../lib/api-client";
import type { EmployeeTerminationRequest, DepartmentEmployeeSummary, DepartmentEmployee, CompensationRecord, EmployeeDocument } from "../../../types/api";

export const employeesApi = {
employees(departmentId?: string) {
    return this.employeePage({ departmentId, page: 0, size: 100 });
  },
employeePage(filters: { query?: string; departmentId?: string; status?: string;
    page?: number; size?: number; sort?: string } = {}, signal?: AbortSignal) {
    const params = new URLSearchParams({
      page: String(filters.page ?? 0),
      size: String(Math.max(25, Math.min(filters.size ?? 50, 100))),
      sort: filters.sort ?? "displayName,asc",
    });
    if (filters.query?.trim()) params.set("query", filters.query.trim());
    if (filters.departmentId) params.set("departmentId", filters.departmentId);
    if (filters.status && filters.status !== "All") params.set("status", filters.status);
    return requestSpringPage<DepartmentEmployee>(`/employees?${params}`, {
      cache: "no-store",
      signal,
    });
  },
departmentEmployeeSummary() {
    return apiRequest<DepartmentEmployeeSummary[]>("/employees/department-summary");
  },
createEmployee(payload: unknown) {
    return apiRequest<{ employee: { id: string; employeeNumber: string; displayName: string;
        officialEmail: string; departmentId: string; designation: string; status: string } }>("/employees", {
      method: "POST", body: JSON.stringify(payload),
    });
  },
upsertExecutiveProfile(payload: { departmentId: string; phoneNumber: string; designation: string; joiningDate: string }) {
    return apiRequest<{ id: string; employeeNumber: string; displayName: string; officialEmail: string;
      phoneNumber: string | null; departmentId: string; designation: string; joiningDate: string;
      status: string; version: number }>("/employees/me/executive-profile", {
      method: "PUT", body: JSON.stringify(payload),
    });
  },
changeEmployeeStatus(id: string, status: string) {
    return apiRequest(`/employees/${id}/status`, { method: "PATCH", body: JSON.stringify({ status }) });
  },
requestEmployeeTermination(employeeId: string, reason: string, effectiveDate: string) {
    return apiRequest<EmployeeTerminationRequest>("/employee-terminations", {
      method: "POST", body: JSON.stringify({ employeeId, reason, effectiveDate }),
    });
  },
myEmployeeTerminations() {
    return apiRequest<EmployeeTerminationRequest[]>("/employee-terminations/mine");
  },
pendingEmployeeTerminations() {
    return apiRequest<EmployeeTerminationRequest[]>("/employee-terminations/pending");
  },
employeeTerminationHistory() {
    return apiRequest<EmployeeTerminationRequest[]>("/employee-terminations/history");
  },
decideEmployeeTermination(id: string, decision: "approve" | "reject", note: string) {
    return apiRequest<EmployeeTerminationRequest>(`/employee-terminations/${id}/${decision}`, {
      method: "POST", body: JSON.stringify({ note }),
    });
  },
currentCompensation(employeeId: string) {
    return apiRequest<CompensationRecord>(`/employees/${employeeId}/compensation/current`, { cache: "no-store" });
  },
compensationHistory(employeeId: string) {
    return apiRequest<CompensationRecord[]>(`/employees/${employeeId}/compensation/history`, { cache: "no-store" });
  },
createCompensation(employeeId: string, payload: {
    components: { basicSalary: number; hra: number; transportAllowance: number; medicalAllowance: number;
      specialAllowance: number; otherAllowance: number; providentFundDeduction: number;
      professionalTax: number; incomeTaxEstimate: number; otherDeductions: number };
    currency: string; effectiveFrom: string; effectiveTo: string | null;
  }) {
    return apiRequest<CompensationRecord>(`/employees/${employeeId}/compensation`, {
      method: "POST", body: JSON.stringify(payload),
    });
  },
employeeDocuments(employeeId: string) {
    return apiRequest<EmployeeDocument[]>(
        `/documents?ownerType=EMPLOYEE&ownerId=${encodeURIComponent(employeeId)}`, { cache: "no-store" });
  },
uploadEmployeeDocument(employeeId: string, category: EmployeeDocument["category"], file: File) {
    const body = new FormData();
    body.append("ownerType", "EMPLOYEE");
    body.append("ownerId", employeeId);
    body.append("category", category);
    body.append("file", file);
    return apiRequest<EmployeeDocument>("/documents", { method: "POST", body });
  },
employeeDocumentDownload(documentId: string) {
    return apiRequest<{ url: string; expiresAt: string }>(`/documents/${documentId}/download-url`);
  },
deleteEmployeeDocument(documentId: string) {
    return apiRequest<void>(`/documents/${documentId}`, { method: "DELETE" });
  },
};
