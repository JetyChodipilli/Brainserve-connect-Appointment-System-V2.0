import { appointmentStatusFromApi, visitorInitials, visitTypeLabel } from "../features/appointments/appointment-utils";
import { isBackendConfigured } from "../lib/api";
import { formatOfficeDate, formatOfficeTime, nextBusinessDays, officeDateTimeToIso } from "../lib/appointments";
import { type Appointment } from "../shared/types/workspace";
import { initialAppointments, initialEmployees } from "./fixtures/workspace";
import { DEMO_APPOINTMENTS_KEY } from "./storage-keys";
import { type DemoAppointment } from "./types";

export function readDemoAppointments(): DemoAppointment[] {
    if (typeof window === "undefined") return [];
    try {
        const value = JSON.parse(window.localStorage.getItem(DEMO_APPOINTMENTS_KEY) ?? "[]");
        if (Array.isArray(value) && value.length) return value;
        const start = officeDateTimeToIso(nextBusinessDays(2)[0], "10:10");
        return [{ referenceNumber: "BSA-DEMO-PASS", type: "CLIENT_MEETING", status: "APPROVED",
            hostReference: initialEmployees[0]?.id ?? "demo-host", slotStart: start,
            slotEnd: new Date(new Date(start).getTime() + 30 * 60 * 1000).toISOString(), visitorDisplayName: "Demo Visitor" }];
    } catch { return []; }
}

export function writeDemoAppointments(appointments: DemoAppointment[]) {
    if (typeof window !== "undefined") {
        window.localStorage.setItem(DEMO_APPOINTMENTS_KEY, JSON.stringify(appointments));
        window.dispatchEvent(new CustomEvent("brainserve:demo-appointments-updated"));
    }
}

export function demoWorkspaceAppointments() {
    return readDemoAppointments().map((item): Appointment => {
        const visitorName = item.visitorName ?? item.visitorDisplayName ?? "Visitor";
        const host = initialEmployees.find((employee) => (employee.uuid ?? employee.id)
            === (item.requestedEmployeeId ?? item.hostReference));
        return {
            id: item.id ?? item.referenceNumber, initials: visitorInitials(visitorName), visitor: visitorName,
            visitorEmail: item.visitorEmail, visitorPhone: item.visitorPhone,
            company: item.visitorCompany ?? "Independent",
            host: host?.name ?? (item.hostCategory === "CEO" ? "CEO Office" : "BrainServe host"),
            purpose: item.purpose ?? "Visitor appointment", time: formatOfficeTime(item.slotStart),
            date: formatOfficeDate(item.slotStart, { year: undefined }), status: appointmentStatusFromApi(item.status),
            type: visitTypeLabel(item.type), referenceNumber: item.referenceNumber, hostEmployeeId: item.hostReference,
            hostCategory: item.hostCategory,
            routingDepartmentId: item.routingDepartmentId, requestedEmployeeId: item.requestedEmployeeId,
            slotStart: item.slotStart, identityDocumentType: item.identityDocumentType,
            identityDocumentLastFour: item.identityDocumentLastFour, securityNotes: item.notes,
            securityIntakeAt: item.securityIntakeAt,
            receptionVerifiedAt: item.receptionVerifiedAt,
            receptionVerificationRemarks: item.receptionVerificationRemarks,
            managerApprovalActorId: item.managerApprovalActorId,
            managerDecisionAt: item.managerDecisionAt,
            managerDecisionRemarks: item.managerDecisionRemarks,
            ceoApprovalActorId: item.ceoApprovalActorId,
            ceoDecisionAt: item.ceoDecisionAt,
            ceoDecisionRemarks: item.ceoDecisionRemarks,
            receptionForwardedAt: item.receptionForwardedAt,
            createdAt: item.createdAt, assignedToCurrentActor: true,
        };
    });
}

export function readPreviewWorkspaceAppointments() {
    const persisted = demoWorkspaceAppointments();
    const persistedReferences = new Set(persisted.map((item) => item.referenceNumber ?? item.id));
    return [
        ...initialAppointments.filter((item) => !persistedReferences.has(item.referenceNumber ?? item.id)),
        ...persisted,
    ];
}

export function updateDemoAppointment(referenceNumber: string | undefined, status: string, patch: Partial<DemoAppointment> = {}) {
    if (isBackendConfigured || !referenceNumber) return;
    writeDemoAppointments(readDemoAppointments().map((item) => item.referenceNumber === referenceNumber
        ? { ...item, ...patch, status } : item));
}

