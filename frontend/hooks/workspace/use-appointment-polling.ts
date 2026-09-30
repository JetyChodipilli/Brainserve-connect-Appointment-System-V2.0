"use client";

import { appointmentStatusFromApi, visitorInitials, visitTypeLabel } from "../../features/appointments/appointment-utils";
import { brainServeApi, isBackendConfigured, isWorkspaceUpdateLeader } from "../../services/brainserve-api";
import { formatOfficeDate, formatOfficeTime } from "../../lib/appointments";
import { type WorkspaceState } from "./use-workspace-state";
import { useEffect } from "react";

export function useAppointmentPolling(workspace: Pick<WorkspaceState, "role" | "setAppointments" | "employees">) {
    const { role, setAppointments, employees } = workspace;

    useEffect(() => {
        if (!isBackendConfigured || role === "System Admin") return;
        let active = true;
        let requestInFlight = false;
        const refreshAppointments = async () => {
            if (requestInFlight || document.visibilityState !== "visible") return;
            requestInFlight = true;
            try {
                const appointmentPage = await brainServeApi.appointments();
                if (!active) return;
                setAppointments((current) => appointmentPage.content.map((item) => {
                    const previous = current.find((value) => value.id === item.id);
                    const host = employees.find((employee) => (employee.uuid ?? employee.id)
                        === (item.requestedEmployeeId ?? item.hostEmployeeId));
                    return {
                        id: item.id, initials: visitorInitials(item.visitorName), visitor: item.visitorName,
                        visitorEmail: item.visitorEmail, visitorPhone: item.visitorPhone,
                        company: item.visitorCompany ?? "Independent", host: host?.name ?? previous?.host ?? "BrainServe host",
                        purpose: item.purpose, time: formatOfficeTime(item.slotStart),
                        date: formatOfficeDate(item.slotStart, { year: undefined }),
                        status: appointmentStatusFromApi(item.status), type: visitTypeLabel(item.type),
                        referenceNumber: item.referenceNumber, hostEmployeeId: item.hostEmployeeId,
                        hostCategory: host?.hostCategory ?? previous?.hostCategory,
                        routingDepartmentId: item.routingDepartmentId, requestedEmployeeId: item.requestedEmployeeId,
                        slotStart: item.slotStart,
                        securityIntakeActorId: item.securityIntakeActorId, securityIntakeAt: item.securityIntakeAt,
                        arrivalVisitorName: item.arrivalVisitorName, arrivalPurpose: item.arrivalPurpose,
                        identityDocumentType: item.identityDocumentType,
                        identityDocumentLastFour: item.identityDocumentLastFour, securityNotes: item.securityNotes,
                        receptionVerificationActorId: item.receptionVerificationActorId,
                        receptionVerifiedAt: item.receptionVerifiedAt,
                        receptionVerificationRemarks: item.receptionVerificationRemarks,
                        hrApprovalActorId: item.hrApprovalActorId, hrDecisionAt: item.hrDecisionAt,
                        hrDecisionRemarks: item.hrDecisionRemarks,
                        teamLeadApprovalActorId: item.teamLeadApprovalActorId,
                        teamLeadDecisionAt: item.teamLeadDecisionAt,
                        teamLeadDecisionRemarks: item.teamLeadDecisionRemarks,
                        managerApprovalActorId: item.managerApprovalActorId,
                        managerDecisionAt: item.managerDecisionAt,
                        managerDecisionRemarks: item.managerDecisionRemarks,
                        ceoApprovalActorId: item.ceoApprovalActorId,
                        ceoDecisionAt: item.ceoDecisionAt,
                        ceoDecisionRemarks: item.ceoDecisionRemarks,
                        receptionForwardActorId: item.receptionForwardActorId,
                        receptionForwardedAt: item.receptionForwardedAt,
                        receptionForwardRemarks: item.receptionForwardRemarks,
                        createdAt: item.createdAt, assignedToCurrentActor: item.assignedToCurrentActor,
                    };
                }));
            } catch { /* The initial loader displays actionable API errors. */ }
            finally { requestInFlight = false; }
        };
        const timer = window.setInterval(() => {
            if (isWorkspaceUpdateLeader()) void refreshAppointments();
        }, 60000);
        return () => { active = false; window.clearInterval(timer); };
    }, [employees, role, setAppointments]);
}
