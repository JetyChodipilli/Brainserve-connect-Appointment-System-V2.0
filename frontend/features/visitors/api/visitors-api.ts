import { apiRequest, requestSpringPage } from "../../../lib/api-client";
import type { VisitorIdentity } from "../../../types/api";

export const visitorsApi = {
registerVisitor(payload: { name: string; email: string; phone: string; company: string | null;
    governmentId: string | null; consentVersion: string }, idempotencyKey: string) {
    return apiRequest<VisitorIdentity>("/public/visitors", {
      method: "POST", headers: { "Idempotency-Key": idempotencyKey }, body: JSON.stringify(payload),
    });
  },
visitor(id: string) {
    return apiRequest<VisitorIdentity>(`/visitors/${id}`);
  },
searchVisitors(query: string, page = 0, size = 25) {
    const params = new URLSearchParams({ query, page: String(page), size: String(size), sort: "name,asc" });
    return requestSpringPage<VisitorIdentity>(`/visitors/search?${params}`, { cache: "no-store" });
  },
verifyVisitor(id: string) {
    return apiRequest<VisitorIdentity>(`/visitors/${id}/verify`, { method: "POST" });
  },
checkIn(appointmentId: string) {
    return apiRequest<{ id: string; appointmentId: string; visitorName: string; badgeNumber: string;
      checkedInAt: string; checkedOutAt: string | null; processedBy: string }>(`/reception/appointments/${appointmentId}/check-in`, {
      method: "POST",
    });
  },
checkInByReference(referenceNumber: string) {
    return apiRequest<{ id: string; appointmentId: string; visitorName: string; badgeNumber: string;
      checkedInAt: string; checkedOutAt: string | null; processedBy: string }>(
        `/reception/appointments/reference/${encodeURIComponent(referenceNumber.toUpperCase())}/check-in`, { method: "POST" });
  },
visitorsInside() {
    return apiRequest<Array<{ id: string; appointmentId: string; visitorName: string; badgeNumber: string;
      checkedInAt: string; checkedOutAt: string | null; processedBy: string }>>("/reception/visitors-inside");
  },
checkOut(accessRecordId: string) {
    return apiRequest(`/reception/access-records/${accessRecordId}/check-out`, { method: "POST" });
  },
verifyVisitorPass(token: string) {
    return apiRequest<{ appointmentId: string; referenceNumber: string; visitorName: string; visitorCompany: string | null;
      appointmentStatus: string; slotStart: string; slotEnd: string; validUntil: string }>("/reception/passes/verify", {
      method: "POST", body: JSON.stringify({ token }),
    });
  },
checkInWithVisitorPass(token: string) {
    return apiRequest<{ id: string; appointmentId: string; visitorName: string; badgeNumber: string;
      checkedInAt: string; checkedOutAt: string | null; processedBy: string }>("/reception/passes/check-in", {
      method: "POST", body: JSON.stringify({ token }),
    });
  },
};
