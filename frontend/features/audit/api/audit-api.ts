import { apiRequest } from "../../../lib/api-client";
import type { EssentialLogRecord, CountedCursorPage } from "../../../types/api";

export const auditApi = {
auditEvents(filters: { from?: string; to?: string; outcome?: string; query?: string;
    cursor?: string; size?: number } = {}) {
    const params = new URLSearchParams({ size: String(Math.max(25, Math.min(filters.size ?? 50, 100))) });
    Object.entries(filters).forEach(([key, value]) => {
      if (key !== "size" && value) params.set(key, String(value));
    });
    return apiRequest<CountedCursorPage<{ id: string; occurredAt: string; actorId: string; eventType: string;
      targetType: string; targetId: string; outcome: string; correlationId: string | null }>>(
        `/audit-events?${params}`, { cache: "no-store" });
  },
essentialLogs(filters: { from?: string; to?: string; category?: string; status?: string;
    query?: string; cursor?: string; size?: number } = {}) {
    const params = new URLSearchParams({ size: String(Math.max(25, Math.min(filters.size ?? 50, 100))) });
    Object.entries(filters).forEach(([key, value]) => {
      if (key !== "size" && value && value !== "All") params.set(key, String(value));
    });
    return apiRequest<CountedCursorPage<EssentialLogRecord>>(`/logs?${params}`, { cache: "no-store" });
  },
};
