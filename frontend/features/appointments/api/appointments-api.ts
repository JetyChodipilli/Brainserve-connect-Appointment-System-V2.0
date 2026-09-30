import { apiRequest, requestSpringPage, allSpringPageContent } from "../../../lib/api-client";
import type { VisitorPass, PublicDirectoryEmployee, ManagedAppointment } from "../../../types/api";
import type { AvailableSlot, PublicAppointment, PublicHost } from "../../../lib/appointments";

export const appointmentsApi = {
createAppointment(payload: unknown, idempotencyKey: string) {
    return apiRequest<PublicAppointment>("/public/appointments", {
      method: "POST",
      headers: { "Idempotency-Key": idempotencyKey },
      body: JSON.stringify(payload),
    });
  },
verifyAppointment(reference: string, otp: string) {
    return apiRequest<PublicAppointment>(`/public/appointments/${reference}/verify-otp`, {
      method: "POST",
      body: JSON.stringify({ otp }),
    });
  },
trackAppointment(reference: string) {
    return apiRequest<PublicAppointment>(`/public/appointments/${encodeURIComponent(reference.toUpperCase())}`);
  },
requestAppointmentCancellationOtp(reference: string) {
    return apiRequest<void>(
        `/public/appointments/${encodeURIComponent(reference.toUpperCase())}/cancel/request-otp`,
        { method: "POST" });
  },
cancelAppointment(reference: string, otp: string) {
    return apiRequest<PublicAppointment>(`/public/appointments/${encodeURIComponent(reference.toUpperCase())}/cancel`, {
      method: "POST", body: JSON.stringify({ otp }),
    });
  },
visitorPass(reference: string) {
    return apiRequest<VisitorPass>(`/public/appointments/${encodeURIComponent(reference.toUpperCase())}/pass`);
  },
publicHosts() {
    return apiRequest<PublicHost[]>("/public/hosts");
  },
publicEmployees(departmentId: string, query = "") {
    const params = new URLSearchParams({
      departmentId, page: "0", size: "25", sort: "displayName,asc",
    });
    if (query.trim()) params.set("query", query.trim());
    return requestSpringPage<PublicDirectoryEmployee>(`/public/employees?${params}`, { cache: "no-store" });
  },
publicDepartments() {
    return apiRequest<Array<{ id: string; code: string; name: string; active: boolean; version: number }>>(
        "/public/departments", { cache: "no-store" },
    );
  },
availableSlots(employeeId: string, date: string, appointmentType: string) {
    return apiRequest<AvailableSlot[]>(`/public/hosts/${employeeId}/available-slots?date=${encodeURIComponent(date)}&type=${encodeURIComponent(appointmentType)}`);
  },
appointments() {
    // Reception and Security must never lose a newly-created walk-in behind the
    // first server page. Fetch every authorized page for the current office day.
    return allSpringPageContent<ManagedAppointment>("/appointments?sort=slotStart,asc");
  },
registerAtReception(payload: unknown, idempotencyKey: string) {
    return apiRequest<ManagedAppointment>("/appointments", {
      method: "POST",
      headers: { "Idempotency-Key": idempotencyKey },
      body: JSON.stringify(payload),
    });
  },
registerAtSecurity(payload: unknown, idempotencyKey: string) {
    return apiRequest<ManagedAppointment>("/appointments/security-walk-ins", {
      method: "POST",
      headers: { "Idempotency-Key": idempotencyKey },
      body: JSON.stringify(payload),
    });
  },
decideVisit(id: string, stage: "hr" | "team-lead" | "manager" | "ceo", decision: "approve" | "reject", remarks = "") {
    return apiRequest<ManagedAppointment>(`/appointments/${id}/${stage}-${decision}`, {
      method: "POST",
      body: JSON.stringify({ remarks }),
    });
  },
decideHostVisit(id: string, decision: "approve" | "reject", remarks = "") {
    return apiRequest<ManagedAppointment>(`/appointments/${id}/${decision}`, {
      method: "POST", body: JSON.stringify({ remarks }),
    });
  },
recordSecurityIntake(id: string, payload: { visitorName: string; purpose: string;
    identityDocumentType: string | null; identityDocumentLastFour: string | null; notes: string | null }) {
    return apiRequest<ManagedAppointment>(`/appointments/${id}/security-intake`, {
      method: "POST", body: JSON.stringify(payload),
    });
  },
decideReceptionVisit(id: string, decision: "verify" | "reject", remarks = "") {
    return apiRequest<ManagedAppointment>(`/appointments/${id}/reception-${decision}`, {
      method: "POST", body: JSON.stringify({ remarks }),
    });
  },
forwardReceptionVisit(id: string, remarks = "") {
    return apiRequest<ManagedAppointment>(`/appointments/${id}/reception-forward`, {
      method: "POST", body: JSON.stringify({ remarks }),
    });
  },
};
