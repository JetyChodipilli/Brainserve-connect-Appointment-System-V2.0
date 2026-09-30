import { apiRequest } from "../../../lib/api-client";
import type { WorkTask, WorkTaskWorkspace, TeamLeadPerformance, WorkInsight, WorkTaskWorkflowState } from "../types/workboard";

export const workboardApi = {
workTasks() { return apiRequest<WorkTask[]>("/work-tasks"); },
workTaskWorkspace() { return apiRequest<WorkTaskWorkspace>("/work-tasks/workspace"); },
createWorkTask(payload: { employeeId: string; title: string; description: string; dueDate: string }) {
    return apiRequest<WorkTask>("/work-tasks", { method: "POST", body: JSON.stringify(payload) });
  },
updateWorkTask(id: string, action: "start" | "complete" | "approve" | "request-changes", note = "") {
    return apiRequest<WorkTask>(`/work-tasks/${id}/${action}`, {
      method: "POST", body: JSON.stringify({ note }),
    });
  },
reviseEmployeeWorkTaskRework(id: string, update: string) {
    return apiRequest<WorkTask>(`/work-tasks/${id}/revise-rework`, {
      method: "POST", body: JSON.stringify({ note: update }),
    });
  },
acknowledgeWorkTask(id: string) {
    return apiRequest<WorkTask>(`/work-tasks/${id}/acknowledge`, { method: "POST" });
  },
teamLeadPerformance() { return apiRequest<TeamLeadPerformance[]>("/work-tasks/performance"); },
workInsights(weekStart: string) {
    return apiRequest<WorkInsight[]>(`/work-insights?weekStart=${encodeURIComponent(weekStart)}`);
  },
pendingHrWorkInsights() {
    return apiRequest<WorkInsight[]>("/work-insights/pending-hr-audit");
  },
workTaskWorkflowStates() {
    return apiRequest<WorkTaskWorkflowState[]>("/work-insights/task-workflow-states");
  },
auditWorkInsight(taskId: string) {
    return apiRequest<WorkInsight>(`/work-insights/tasks/${taskId}/audit`, { method: "POST" });
  },
requestWorkInsightRework(taskId: string, reason: string) {
    return apiRequest<WorkInsight>(`/work-insights/tasks/${taskId}/request-rework`, {
      method: "POST", body: JSON.stringify({ reason }),
    });
  },
assignWorkInsightRework(taskId: string, guidance: string) {
    return apiRequest<WorkInsight>(`/work-insights/tasks/${taskId}/assign-rework`, {
      method: "POST", body: JSON.stringify({ guidance }),
    });
  },
reviseWorkInsightRework(taskId: string, update: string) {
    return apiRequest<WorkInsight>(`/work-insights/tasks/${taskId}/revise-rework`, {
      method: "POST", body: JSON.stringify({ update }),
    });
  },
decideWorkInsight(recordId: string, approved: boolean, remarks = "") {
    return apiRequest<WorkInsight>(`/work-insights/${recordId}/ceo-decision`, {
      method: "POST", body: JSON.stringify({ approved, remarks }),
    });
  },
decideManagerWorkInsight(recordId: string, approved: boolean, remarks = "") {
    return apiRequest<WorkInsight>(`/work-insights/${recordId}/manager-decision`, {
      method: "POST", body: JSON.stringify({ approved, remarks }),
    });
  },
};
