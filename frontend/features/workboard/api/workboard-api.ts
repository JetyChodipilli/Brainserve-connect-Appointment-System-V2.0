import { apiRequest } from "../../../lib/api-client";
import type { WorkTask, WorkTaskWorkspace, TeamLeadPerformance, WorkInsight, WorkTaskWorkflowState, WorkboardCriteria, WorkboardPage, WorkboardDetail, WorkboardPreferences } from "../types/workboard";

export const workboardApi = {
workboard(criteria: WorkboardCriteria, page: number, size: number, signal?: AbortSignal) {
    const query = new URLSearchParams({ ...criteria, page: String(page), size: String(size) });
    return apiRequest<WorkboardPage>(`/workboard?${query}`, { signal, cache: "no-store" });
  },
workboardDetail(id: string, signal?: AbortSignal) {
    return apiRequest<WorkboardDetail>(`/workboard/${encodeURIComponent(id)}`, { signal, cache: "no-store" });
  },
workboardPreferences(signal?: AbortSignal) {
    return apiRequest<WorkboardPreferences>("/workboard/preferences", { signal, cache: "no-store" });
  },
saveWorkboardPreferences(preferences: WorkboardPreferences, signal?: AbortSignal) {
    const { revision, ...values } = preferences;
    return apiRequest<WorkboardPreferences>("/workboard/preferences", { method: "PUT", signal,
      body: JSON.stringify({ expectedRevision: revision, ...values }) });
  },
workTasks() { return apiRequest<WorkTask[]>("/work-tasks"); },
workTaskWorkspace() { return apiRequest<WorkTaskWorkspace>("/work-tasks/workspace"); },
createWorkTask(payload: { employeeId: string; title: string; description: string; dueDate: string }) {
    return apiRequest<WorkTask>("/work-tasks", { method: "POST", body: JSON.stringify(payload) });
  },
updateWorkTask(id: string, action: "start" | "complete" | "approve" | "request-changes", note = "", expectedVersion?: number) {
    return apiRequest<WorkTask>(`/work-tasks/${id}/${action}`, {
      method: "POST", body: JSON.stringify({ note, expectedVersion }),
    });
  },
reviseEmployeeWorkTaskRework(id: string, update: string, expectedVersion?: number) {
    return apiRequest<WorkTask>(`/work-tasks/${id}/revise-rework`, {
      method: "POST", body: JSON.stringify({ note: update, expectedVersion }),
    });
  },
acknowledgeWorkTask(id: string, expectedVersion?: number) {
    return apiRequest<WorkTask>(`/work-tasks/${id}/acknowledge`, { method: "POST", body: JSON.stringify({ expectedVersion }) });
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
auditWorkInsight(taskId: string, expectedTaskVersion?: number) {
    return apiRequest<WorkInsight>(`/work-insights/tasks/${taskId}/audit`, { method: "POST", body: JSON.stringify({ expectedTaskVersion }) });
  },
requestWorkInsightRework(taskId: string, reason: string, expectedTaskVersion?: number) {
    return apiRequest<WorkInsight>(`/work-insights/tasks/${taskId}/request-rework`, {
      method: "POST", body: JSON.stringify({ reason, expectedTaskVersion }),
    });
  },
assignWorkInsightRework(taskId: string, guidance: string, expectedTaskVersion?: number) {
    return apiRequest<WorkInsight>(`/work-insights/tasks/${taskId}/assign-rework`, {
      method: "POST", body: JSON.stringify({ guidance, expectedTaskVersion }),
    });
  },
reviseWorkInsightRework(taskId: string, update: string, expectedTaskVersion?: number) {
    return apiRequest<WorkInsight>(`/work-insights/tasks/${taskId}/revise-rework`, {
      method: "POST", body: JSON.stringify({ update, expectedTaskVersion }),
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
