"use client";

import { ApiError, brainServeApi, isBackendConfigured, type ManagedAppointment } from "../../../services/brainserve-api";
import { appointmentTypeCode, formatOfficeDate, formatOfficeTime } from "../../../lib/appointments";
import { readDemoAccounts } from "../../../preview/accounts";
import { readDemoAppointments, updateDemoAppointment, writeDemoAppointments } from "../../../preview/appointments";
import { readDemoManagerAssignments } from "../../../preview/manager-assignments";
import { demoSenderName, readDemoInternalNotifications, writeDemoInternalNotifications } from "../../../preview/notifications";
import { type DemoAppointment, type DemoInternalNotification } from "../../../preview/types";
import {
    type Appointment,
    type AppointmentStatus,
    type ReceptionVisitInput,
    type SecurityIntakeInput,
} from "../../../types/workspace";
import { rethrow } from "../../../utils/errors";
import { newClientId } from "../../../utils/ids";
import { type WorkspaceState } from "../../../hooks/workspace/use-workspace-state";
import { appointmentStatusFromApi, isCeoApprovalRoute, visitorInitials } from "../appointment-utils";

export function createAppointmentActions(workspace: Pick<WorkspaceState, "setAppointments" | "appointments" | "setOperationError" | "userEmail" | "role" | "employees" | "setMetrics" | "setSecurityIntakeAppointment" | "setVisitModal">) {
    const { setAppointments, appointments, setOperationError, userEmail, role, employees, setMetrics, setSecurityIntakeAppointment, setVisitModal } = workspace;
    const updateAppointment = (id: string, status: AppointmentStatus, patch: Partial<Pick<Appointment,
        "managerApprovalActorId" | "managerDecisionAt" | "managerDecisionRemarks"
        | "ceoApprovalActorId" | "ceoDecisionAt" | "ceoDecisionRemarks">> = {}) =>
        setAppointments((items) => items.map((item) => {
            if (item.id !== id && item.referenceNumber !== id) return item;
            updateDemoAppointment(item.referenceNumber, {
                "Awaiting Security": "PENDING_SECURITY_INTAKE", "Awaiting Reception": "PENDING_RECEPTION_VERIFICATION",
                "Awaiting HR": "PENDING_HR_APPROVAL", "Awaiting Team Lead": "PENDING_TEAM_LEAD_APPROVAL",
                "Awaiting Manager": "PENDING_MANAGER_APPROVAL",
                "Awaiting CEO": "PENDING_CEO_APPROVAL", Approved: "APPROVED",
                Rejected: "REJECTED", "Checked in": "CHECKED_IN", Completed: "COMPLETED",
                Pending: "PENDING_VERIFICATION", Cancelled: "CANCELLED", Expired: "EXPIRED",
            }[status] ?? status, patch);
            return { ...item, ...patch, status };
        }));

    const decideAppointment = async (id: string, decision: "approve" | "reject") => {
        const appointment = appointments.find((item) => item.id === id);
        if (!appointment) return;
        const stage = appointment.status === "Awaiting HR" ? "hr" : appointment.status === "Awaiting Team Lead" ? "team-lead"
            : appointment.status === "Awaiting Manager" ? "manager"
                : appointment.status === "Awaiting CEO" ? "ceo"
                    : appointment.status === "Pending" ? "host" : null;
        if (!stage) return;
        const remarks = decision === "reject"
            ? window.prompt("Enter the rejection reason", "Visitor request does not meet the approval requirements")
            : "";
        if (decision === "reject" && (remarks === null || remarks.trim().length < 5)) return;
        setOperationError("");
        try {
            let backendDecision: ManagedAppointment | null = null;
            if (isBackendConfigured) {
                if (stage === "host") backendDecision = await brainServeApi.decideHostVisit(id, decision, remarks?.trim() ?? "");
                else backendDecision = await brainServeApi.decideVisit(id, stage, decision, remarks?.trim() ?? "");
            }
            const nextStatus: AppointmentStatus = backendDecision
                ? appointmentStatusFromApi(backendDecision.status)
                : decision === "reject" ? "Rejected"
                    : stage === "manager" && isCeoApprovalRoute(appointment) ? "Awaiting CEO"
                        : stage === "hr" && isCeoApprovalRoute(appointment) ? "Awaiting Manager"
                            : stage === "hr" && appointment.type === "Employee visit" ? "Awaiting Team Lead" : "Approved";
            const decidedAt = new Date().toISOString();
            const auditPatch = stage === "manager"
                ? { managerApprovalActorId: backendDecision?.managerApprovalActorId ?? userEmail,
                    managerDecisionAt: backendDecision?.managerDecisionAt ?? decidedAt,
                    managerDecisionRemarks: backendDecision?.managerDecisionRemarks ?? remarks?.trim() ?? null }
                : stage === "ceo"
                    ? { ceoApprovalActorId: backendDecision?.ceoApprovalActorId ?? userEmail,
                        ceoDecisionAt: backendDecision?.ceoDecisionAt ?? decidedAt,
                        ceoDecisionRemarks: backendDecision?.ceoDecisionRemarks ?? remarks?.trim() ?? null }
                    : {};
            updateAppointment(id, nextStatus, auditPatch);
            if (!isBackendConfigured && isCeoApprovalRoute(appointment)
                && (stage === "manager" || stage === "ceo")) {
                const accounts = readDemoAccounts();
                const sender = accounts.find((item) => item.email.toLowerCase() === userEmail.toLowerCase());
                const managerAssignment = readDemoManagerAssignments().find((item) =>
                    item.active && item.departmentId === appointment.routingDepartmentId);
                const recipient = stage === "manager"
                    ? accounts.find((item) => item.role === "ROLE_CEO" && item.status === "ACTIVE")
                    : accounts.find((item) => item.id === managerAssignment?.managerUserId && item.status === "ACTIVE");
                if (recipient) {
                    const message = stage === "manager"
                        ? `Manager approved CEO visit ${appointment.referenceNumber ?? appointment.id} for ${appointment.visitor}. CEO final approval is required.`
                        : `CEO ${decision === "approve" ? "approved" : "rejected"} visit ${appointment.referenceNumber ?? appointment.id} for ${appointment.visitor}.${remarks?.trim() ? ` Remarks: ${remarks.trim()}` : ""}`;
                    writeDemoInternalNotifications([{ id: newClientId(), senderUserId: sender?.id ?? userEmail,
                        recipientUserId: recipient.id, senderName: sender?.fullName ?? demoSenderName(role, userEmail),
                        recipientName: recipient.fullName, message, deliveryStatus: "DELIVERED", sentAt: decidedAt,
                        deliveredAt: decidedAt, readAt: null, senderEmail: sender?.email ?? userEmail,
                        recipientEmail: recipient.email }, ...readDemoInternalNotifications()]);
                }
            }
            if (!isBackendConfigured && appointment.type === "Employee visit"
                && ((stage === "hr" && decision === "approve") || stage === "team-lead")) {
                const host = employees.find((employee) => (employee.uuid ?? employee.id) === appointment.hostEmployeeId
                    || employee.name === appointment.host);
                if (host) {
                    const account = readDemoAccounts().find((item) => item.email === host.email && item.status === "ACTIVE");
                    const now = new Date().toISOString();
                    const message = stage === "hr"
                        ? `HR forwarded visitor ${appointment.arrivalVisitorName ?? appointment.visitor} (${appointment.referenceNumber ?? appointment.id}) to you for ${appointment.arrivalPurpose ?? appointment.purpose}. Team Lead approval is pending.`
                        : `Team Lead ${decision === "approve" ? "approved" : "rejected"} visitor ${appointment.arrivalVisitorName ?? appointment.visitor} (${appointment.referenceNumber ?? appointment.id}) for ${appointment.arrivalPurpose ?? appointment.purpose}.`;
                    writeDemoInternalNotifications([{ id: newClientId(), senderUserId: userEmail,
                        recipientUserId: account?.id ?? `employee-${host.id}`, senderName: demoSenderName(role, userEmail),
                        recipientName: host.name, message, deliveryStatus: "DELIVERED", sentAt: now, deliveredAt: now,
                        readAt: null, senderEmail: userEmail, recipientEmail: host.email }, ...readDemoInternalNotifications()]);
                }
            }
            if (!["Awaiting CEO", "Awaiting Manager", "Awaiting Team Lead"].includes(nextStatus)) {
                setMetrics((current) => ({ ...current,
                    awaitingApproval: Math.max(0, current.awaitingApproval - 1),
                    activeVisits: decision === "approve" ? current.activeVisits + 1 : current.activeVisits,
                }));
            }
        } catch (reason) {
            setOperationError(reason instanceof ApiError ? reason.message : "The approval action failed.");
        }
    };

    const recordSecurityIntake = async (id: string, input: SecurityIntakeInput) => {
        const appointment = appointments.find((item) => item.id === id);
        if (!appointment) return;
        setOperationError("");
        try {
            if (isBackendConfigured) await brainServeApi.recordSecurityIntake(id, input);
            const now = new Date().toISOString();
            setAppointments((items) => items.map((item) => item.id === id ? {
                ...item, status: "Awaiting Reception", arrivalVisitorName: input.visitorName,
                arrivalPurpose: input.purpose, identityDocumentType: input.identityDocumentType,
                identityDocumentLastFour: input.identityDocumentLastFour, securityNotes: input.notes,
                securityIntakeAt: now,
            } : item));
            updateDemoAppointment(appointment.referenceNumber, "PENDING_RECEPTION_VERIFICATION", {
                visitorName: input.visitorName, purpose: input.purpose,
                identityDocumentType: input.identityDocumentType,
                identityDocumentLastFour: input.identityDocumentLastFour, notes: input.notes, securityIntakeAt: now,
            });
            if (!isBackendConfigured) {
                const notification: DemoInternalNotification = {
                    id: newClientId(), senderUserId: userEmail, recipientUserId: "reception-preview",
                    senderName: "Security Desk", recipientName: "Reception Desk",
                    message: `${input.visitorName} arrived for ${appointment.host}. ${input.purpose} · ${appointment.referenceNumber ?? appointment.id}`,
                    deliveryStatus: "DELIVERED", sentAt: now, deliveredAt: now, readAt: null,
                    senderEmail: userEmail, recipientEmail: "reception@brainserve.in",
                };
                writeDemoInternalNotifications([notification, ...readDemoInternalNotifications()]);
            }
            // Count an arrival once, at the first security intake. Repeated edits to
            // the same intake must not inflate the dashboard total.
            if (!appointment.securityIntakeAt) {
                setMetrics((current) => ({ ...current, arrivedVisits: current.arrivedVisits + 1 }));
            }
            setSecurityIntakeAppointment(null);
        } catch (reason) {
            setOperationError(reason instanceof ApiError ? reason.message : "Security intake could not be recorded.");
            rethrow(reason);
        }
    };

    const decideReceptionVisit = async (id: string, decision: "verify" | "reject") => {
        const appointment = appointments.find((item) => item.id === id);
        if (!appointment) return;
        const defaultRemarks = decision === "verify" ? "Arrival, contact details, purpose and identity verified by Reception"
            : "Reception rejected the visitor after reviewing arrival details";
        const remarks = window.prompt(decision === "verify" ? "Enter Reception verification details" : "Enter rejection reason", defaultRemarks);
        if (remarks === null || remarks.trim().length < 2) return;
        setOperationError("");
        try {
            const backendDecision = isBackendConfigured
                ? await brainServeApi.decideReceptionVisit(id, decision, remarks.trim()) : null;
            const managerRoute = isCeoApprovalRoute(appointment);
            const nextStatus: AppointmentStatus = backendDecision
                ? appointmentStatusFromApi(backendDecision.status)
                : decision === "reject" ? "Rejected" : managerRoute ? "Awaiting Manager" : "Awaiting HR";
            setAppointments((items) => items.map((item) => item.id === id ? {
                ...item, status: nextStatus, receptionVerifiedAt: new Date().toISOString(),
                receptionVerificationRemarks: remarks.trim(),
            } : item));
            const verifiedAt = new Date().toISOString();
            updateDemoAppointment(appointment.referenceNumber, backendDecision?.status
                ?? (decision === "reject" ? "REJECTED"
                    : managerRoute ? "PENDING_MANAGER_APPROVAL" : "PENDING_HR_APPROVAL"),
                { receptionVerifiedAt: verifiedAt, receptionVerificationRemarks: remarks.trim() });
        } catch (reason) {
            setOperationError(reason instanceof ApiError ? reason.message : "Reception verification failed.");
        }
    };

    const forwardReceptionVisit = async (id: string) => {
        const appointment = appointments.find((item) => item.id === id);
        if (!appointment) return;
        const destination = isCeoApprovalRoute(appointment) ? "CEO cabin" : "HR cabin";
        const remarks = window.prompt(`Message for the ${destination}`, `Visitor is being sent to the ${destination}`);
        if (remarks === null || remarks.trim().length < 2) return;
        setOperationError("");
        try {
            const now = new Date().toISOString();
            if (isBackendConfigured) await brainServeApi.forwardReceptionVisit(id, remarks.trim());
            setAppointments((items) => items.map((item) => item.id === id ? {
                ...item, receptionForwardedAt: now, receptionForwardRemarks: remarks.trim(),
            } : item));
            updateDemoAppointment(appointment.referenceNumber, "APPROVED", { receptionForwardedAt: now });
        } catch (reason) {
            setOperationError(reason instanceof ApiError ? reason.message : "The visitor could not be forwarded.");
        }
    };

    const registerVisit = async (input: ReceptionVisitInput) => {
        const host = employees.find((item) => (item.uuid ?? item.id) === input.hostEmployeeId) ?? employees[0];
        const requestedEmployee = employees.find((item) => (item.uuid ?? item.id) === input.requestedEmployeeId);
        setOperationError("");
        try {
            let id = String(Date.now());
            let referenceNumber = `LOCAL-${id}`;
            const startIso = input.slotStart;
            if (isBackendConfigured) {
                const payload = {
                    type: appointmentTypeCode(input.visitType), visitorName: input.visitorName, visitorEmail: input.visitorEmail,
                    visitorPhone: input.visitorPhone, visitorCompany: input.visitorCompany,
                    hostEmployeeId: input.hostEmployeeId, routingDepartmentId: input.routingDepartmentId,
                    requestedEmployeeId: input.requestedEmployeeId ?? null, slotStart: startIso,
                    slotEnd: input.slotEnd, purpose: input.purpose,
                    identityDocumentType: input.identityDocumentType,
                    identityDocumentLastFour: input.identityDocumentLastFour, notes: input.notes,
                    createdAt: new Date().toISOString(),
                };
                const created = role === "Security"
                    ? await brainServeApi.registerAtSecurity(payload, newClientId())
                    : await brainServeApi.registerAtReception(payload, newClientId());
                id = created.id; referenceNumber = created.referenceNumber;
            } else {
                const demo: DemoAppointment = {
                    id, referenceNumber, type: appointmentTypeCode(input.visitType),
                    status: role === "Security" ? "PENDING_RECEPTION_VERIFICATION" : "PENDING_SECURITY_INTAKE",
                    hostReference: input.hostEmployeeId, slotStart: startIso, slotEnd: input.slotEnd,
                    hostCategory: input.hostCategory,
                    visitorDisplayName: input.visitorName, visitorName: input.visitorName,
                    visitorEmail: input.visitorEmail, visitorPhone: input.visitorPhone,
                    visitorCompany: input.visitorCompany || null, purpose: input.purpose,
                    routingDepartmentId: input.routingDepartmentId, requestedEmployeeId: input.requestedEmployeeId ?? null,
                    identityDocumentType: input.identityDocumentType,
                    identityDocumentLastFour: input.identityDocumentLastFour, notes: input.notes,
                };
                writeDemoAppointments([...readDemoAppointments(), demo]);
            }
            setAppointments((items) => [...items, {
                id, initials: visitorInitials(input.visitorName), visitor: input.visitorName,
                company: input.visitorCompany || "Independent", host: requestedEmployee?.name ?? host?.name ?? "BrainServe host",
                purpose: input.purpose, time: formatOfficeTime(startIso), date: formatOfficeDate(startIso, { year: undefined }),
                status: role === "Security" ? "Awaiting Reception" : "Awaiting Security",
                type: input.visitType, referenceNumber, hostEmployeeId: input.hostEmployeeId,
                hostCategory: input.hostCategory,
                routingDepartmentId: input.routingDepartmentId, requestedEmployeeId: input.requestedEmployeeId ?? null,
                slotStart: startIso,
                arrivalVisitorName: role === "Security" ? input.visitorName : null,
                arrivalPurpose: role === "Security" ? input.purpose : null,
                identityDocumentType: input.identityDocumentType,
                identityDocumentLastFour: input.identityDocumentLastFour, securityNotes: input.notes,
            }]);
            if (!isBackendConfigured && role === "Security") {
                const now = new Date().toISOString();
                writeDemoInternalNotifications([{ id: newClientId(), senderUserId: userEmail,
                    recipientUserId: "reception-preview", senderName: "Security Desk", recipientName: "Reception Desk",
                    message: `${input.visitorName} arrived for ${host?.name ?? "BrainServe host"}. ${input.purpose} · ${referenceNumber}`,
                    deliveryStatus: "DELIVERED", sentAt: now, deliveredAt: now, readAt: null,
                    senderEmail: userEmail, recipientEmail: "reception@brainserve.in" }, ...readDemoInternalNotifications()]);
            }
            setMetrics((current) => ({ ...current, awaitingApproval: current.awaitingApproval + 1 }));
            setVisitModal(false);
        } catch (reason) {
            setOperationError(reason instanceof ApiError ? reason.message : "The visitor request could not be created.");
            rethrow(reason);
        }
    };
    return { updateAppointment, decideAppointment, recordSecurityIntake, decideReceptionVisit, forwardReceptionVisit, registerVisit };
}
