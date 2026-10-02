import { apiRequest } from "../../../lib/api-client";
import { type DashboardFreshnessMetadata } from "../dashboard-freshness";

export const dashboardApi = {
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
