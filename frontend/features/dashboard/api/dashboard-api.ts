import { apiRequest } from "../../../lib/api-client";
import { type DashboardFreshnessMetadata } from "../dashboard-freshness";
import { dashboardQuery, type DashboardCards, type DashboardRange, type DashboardRecords } from "../administration-dashboard-model";

export const dashboardApi = {
  cards(range: DashboardRange, signal?: AbortSignal) {
    return apiRequest<DashboardCards>(`/dashboard/cards?${dashboardQuery(range)}`, { cache: "no-store", signal });
  },
  cardRecords(metricId: string, range: DashboardRange, page = 0, size = 50, signal?: AbortSignal) {
    if (!Number.isInteger(page) || page < 0 || page > 10_000 || !Number.isInteger(size) || size < 1 || size > 100) {
      throw new Error("Dashboard record pagination is outside the supported bounds.");
    }
    const query = dashboardQuery(range);
    query.set("page", String(page)); query.set("size", String(size));
    return apiRequest<DashboardRecords>(`/dashboard/cards/${encodeURIComponent(metricId)}/records?${query}`, { cache: "no-store", signal });
  },
visitTypeCounts(from: string, to: string) {
    return apiRequest<Array<{ type: string; total: number }>>(`/dashboard/visit-types?${new URLSearchParams({ from, to })}`);
  },
dashboard(range?: { from: string; to: string }) {
    return apiRequest<DashboardFreshnessMetadata & { awaitingApproval: number; activeVisits: number; visitorsInside: number;
      totalEmployees: number; activeEmployees: number; scheduledVisits: number; arrivedVisits: number;
      completedVisits: number; cancelledVisits: number; rejectedVisits: number; assignedWork: number;
      inProgressWork: number; completedWork: number; approvedWork: number; averageWaitSeconds: number | null;
      role: string; scope: string; departmentId: string | null; from: string; to: string; generatedAt: string }>(`/dashboard/summary${range ? `?${new URLSearchParams({ period: "CUSTOM", ...range })}` : ""}`);
  },
};
