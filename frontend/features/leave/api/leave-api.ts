import { apiRequest } from "../../../lib/api-client";
import type { LeaveRequest } from "../../../types/api";

export const leaveApi = {
createLeaveRequest(startDate: string, endDate: string, reason: string) {
    return apiRequest<LeaveRequest>("/leave-requests", { method: "POST", body: JSON.stringify({ startDate, endDate, reason }) });
  },
myLeaveRequests() { return apiRequest<LeaveRequest[]>("/leave-requests/me"); },
pendingLeaveRequests() { return apiRequest<LeaveRequest[]>("/leave-requests/pending"); },
decideLeaveRequest(id: string, decision: "approve" | "reject", remarks = "") {
    return apiRequest<LeaveRequest>(`/leave-requests/${id}/${decision}`, { method: "POST", body: JSON.stringify({ remarks }) });
  },
};
