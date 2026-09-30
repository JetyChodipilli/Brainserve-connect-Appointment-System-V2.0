import { apiRequest } from "../../../lib/api-client";
import type { MonthlyRecords, HistoryDataset, HistoryRow, CursorPage, ReportExportJob } from "../../../types/api";

export const reportsApi = {
history(filters: { dataset: HistoryDataset; from: string; to: string; departmentId?: string;
    status?: string; query?: string; cursor?: string; size?: number }) {
    const params = new URLSearchParams({ dataset: filters.dataset, from: filters.from, to: filters.to,
      size: String(filters.size ?? 50) });
    if (filters.departmentId) params.set("departmentId", filters.departmentId);
    if (filters.status) params.set("status", filters.status);
    if (filters.query) params.set("query", filters.query);
    if (filters.cursor) params.set("cursor", filters.cursor);
    return apiRequest<CursorPage<HistoryRow>>(`/history?${params.toString()}`);
  },
requestReportExport(payload: { dataset: HistoryDataset; format: "CSV" | "XLSX"; from: string; to: string;
    departmentId?: string; status?: string; query?: string }) {
    return apiRequest<ReportExportJob>("/report-exports", { method: "POST", body: JSON.stringify(payload) });
  },
reportExports() { return apiRequest<ReportExportJob[]>("/report-exports"); },
reportExportDownload(id: string) {
    return apiRequest<{ url: string; expiresAt: string; filename: string }>(`/report-exports/${id}/download-url`);
  },
retryReportExport(id: string) {
    return apiRequest<ReportExportJob>(`/report-exports/${id}/retry`, { method: "POST" });
  },
monthlyRecords(year: number, month: number) {
    return apiRequest<MonthlyRecords>(`/admin/records/monthly?year=${year}&month=${month}`);
  },
};
