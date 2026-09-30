import { normalizeSpringPage, apiRequest, requestSpringPage } from "../../../lib/api-client";
import type { DepartmentEmployee, TeamLeadAssignment, DepartmentHrAssignment, ManagerAssignment, RoleDepartmentChangeRequest, SpringPage } from "../../../types/api";

export const organizationApi = {
teamLeadAssignments() {
    return apiRequest<TeamLeadAssignment[]>("/team-leads/assignments");
  },
departmentHrAssignments() {
    return apiRequest<DepartmentHrAssignment[]>("/department-hrs/assignments");
  },
managerAssignments() {
    return apiRequest<ManagerAssignment[]>("/managers/assignments");
  },
managerCandidates() {
    return apiRequest<Array<{ userId: string; employeeId: string; fullName: string; email: string;
      currentDepartmentId: string | null; currentDepartmentCode: string | null;
      currentDepartmentName: string | null }>>("/managers/candidates");
  },
assignManager(departmentId: string, managerUserId: string) {
    return apiRequest<ManagerAssignment>("/managers/assignments", {
      method: "POST", body: JSON.stringify({ departmentId, managerUserId }),
    });
  },
endManagerAssignment(id: string) {
    return apiRequest<ManagerAssignment>(`/managers/assignments/${id}/end`, { method: "POST" });
  },
myManagerAssignment() {
    return apiRequest<{ assignmentId: string; departmentId: string; managerUserId: string;
      managerEmployeeId: string; fullName: string; email: string }>("/managers/me/assignment");
  },
departmentHrCandidates() {
    return apiRequest<Array<{ userId: string; employeeId: string; fullName: string; email: string;
      currentDepartmentId: string | null; currentDepartmentCode: string | null; currentDepartmentName: string | null }>>(
        "/department-hrs/candidates");
  },
assignDepartmentHr(departmentId: string, hrUserId: string) {
    return apiRequest<DepartmentHrAssignment>("/department-hrs/assignments", {
      method: "POST", body: JSON.stringify({ departmentId, hrUserId }),
    });
  },
endDepartmentHrAssignment(id: string) {
    return apiRequest<DepartmentHrAssignment>(`/department-hrs/assignments/${id}/end`, { method: "POST" });
  },
assignTeamLead(departmentId: string, employeeId: string) {
    return apiRequest<TeamLeadAssignment>("/team-leads/assignments", {
      method: "POST", body: JSON.stringify({ departmentId, employeeId }),
    });
  },
endTeamLeadAssignment(id: string) {
    return apiRequest<TeamLeadAssignment>(`/team-leads/assignments/${id}/end`, { method: "POST" });
  },
myTeamLeadAssignment() {
    return apiRequest<{ assignmentId: string; departmentId: string; teamLeadUserId: string;
      teamLeadEmployeeId: string; fullName: string; email: string }>("/team-leads/me/assignment");
  },
myTeam() {
    return requestSpringPage<DepartmentEmployee>("/team-leads/me/team?page=0&size=50&sort=displayName,asc");
  },
myTeamLeadWorkspace() {
    return apiRequest<{ assignment: { assignmentId: string; departmentId: string; teamLeadUserId: string;
        teamLeadEmployeeId: string; fullName: string; email: string };
      department: { id: string; code: string; name: string };
      employees: SpringPage<DepartmentEmployee> }>("/team-leads/me/workspace?page=0&size=50&sort=displayName,asc")
        .then((workspace) => ({ ...workspace, employees: normalizeSpringPage(workspace.employees) }));
  },
departments() {
    return apiRequest<Array<{ id: string; code: string; name: string; active: boolean; version: number }>>("/departments");
  },
visibleDepartments() {
    return apiRequest<Array<{ id: string; code: string; name: string; active: boolean; version: number }>>("/departments/visible");
  },
departmentLeadership() {
    return apiRequest<Array<{ departmentId: string;
      teamLead: { fullName: string } | null;
      hr: { fullName: string } | null;
      manager: { fullName: string } | null }>>("/departments/leadership");
  },
myRoleDepartmentChanges() {
    return apiRequest<RoleDepartmentChangeRequest[]>("/role-department-changes/me");
  },
pendingRoleDepartmentChanges() {
    return apiRequest<RoleDepartmentChangeRequest[]>("/role-department-changes/pending");
  },
requestRoleDepartmentChange(payload: { targetDepartmentId: string; reason: string;
    phoneNumber?: string | null; designation?: string | null; joiningDate?: string | null }) {
    return apiRequest<RoleDepartmentChangeRequest>("/role-department-changes", {
      method: "POST", body: JSON.stringify(payload),
    });
  },
approveRoleDepartmentChange(id: string, resolution: "MOVE" | "REPLACE" | "SWAP", note: string) {
    return apiRequest<RoleDepartmentChangeRequest>(`/role-department-changes/${id}/approve`, {
      method: "POST", body: JSON.stringify({ resolution, note }),
    });
  },
rejectRoleDepartmentChange(id: string, note: string) {
    return apiRequest<RoleDepartmentChangeRequest>(`/role-department-changes/${id}/reject`, {
      method: "POST", body: JSON.stringify({ note }),
    });
  },
cancelRoleDepartmentChange(id: string) {
    return apiRequest<RoleDepartmentChangeRequest>(`/role-department-changes/${id}/cancel`, { method: "POST" });
  },
createDepartment(code: string, name: string) {
    return apiRequest<{ id: string; code: string; name: string; active: boolean; version: number }>("/departments", {
      method: "POST", body: JSON.stringify({ code, name }),
    });
  },
changeDepartmentStatus(id: string, active: boolean) {
    return apiRequest<{ id: string; code: string; name: string; active: boolean; version: number }>(`/departments/${id}/status`, {
      method: "PATCH", body: JSON.stringify({ active }),
    });
  },
};
