"use client";

import { ApiError, brainServeApi, isBackendConfigured } from "../../../services/brainserve-api";
import { readDemoAppointments } from "../../../preview/appointments";
import { type AccessRecord } from "../../../types/workspace";
import { fail } from "../../../utils/errors";
import { newClientId } from "../../../utils/ids";
import { type WorkspaceState } from "../../../hooks/workspace/use-workspace-state";
import { type createAppointmentActions } from "../../appointments/actions/workspace-appointment-actions";

export function createVisitorActions(workspace: Pick<WorkspaceState, "appointments" | "setOperationError" | "setAccessRecords" | "userEmail" | "setMetrics" | "accessRecords" | "setAppointments"> & Pick<ReturnType<typeof createAppointmentActions>, "updateAppointment">) {
    const { appointments, setOperationError, setAccessRecords, userEmail, updateAppointment, setMetrics, accessRecords, setAppointments } = workspace;

    const checkInAppointment = async (id: string) => {
        const appointment = appointments.find((item) => item.id === id);
        const alreadyArrived = Boolean(appointment?.securityIntakeAt || appointment?.status === "Checked in");
        setOperationError("");
        try {
            if (isBackendConfigured) {
                const record = await brainServeApi.checkIn(id);
                setAccessRecords((items) => [...items, record]);
            } else {
                setAccessRecords((items) => [...items, { id: newClientId(), appointmentId: id,
                    visitorName: appointments.find((item) => item.id === id)?.visitor ?? "Visitor",
                    badgeNumber: `B-${String(items.length + 1).padStart(3, "0")}`, checkedInAt: new Date().toISOString(),
                    checkedOutAt: null, processedBy: userEmail }]);
            }
            updateAppointment(id, "Checked in");
            setMetrics((current) => ({ ...current,
                visitorsInside: current.visitorsInside + 1,
                arrivedVisits: alreadyArrived ? current.arrivedVisits : current.arrivedVisits + 1,
            }));
        } catch (reason) { setOperationError(reason instanceof ApiError ? reason.message : "Visitor check-in failed."); }
    };

    const checkOutAppointment = async (appointmentId: string) => {
        const record = accessRecords.find((item) => item.appointmentId === appointmentId);
        if (!record) { setOperationError("The active access record was not found."); return; }
        setOperationError("");
        try {
            if (isBackendConfigured) await brainServeApi.checkOut(record.id);
            setAccessRecords((items) => items.filter((item) => item.id !== record.id));
            updateAppointment(appointmentId, "Completed");
            setMetrics((current) => ({ ...current, visitorsInside: Math.max(0, current.visitorsInside - 1),
                activeVisits: Math.max(0, current.activeVisits - 1) }));
        } catch (reason) { setOperationError(reason instanceof ApiError ? reason.message : "Visitor check-out failed."); }
    };

    const checkInByReference = async (referenceNumber: string) => {
        const normalized = referenceNumber.trim().toUpperCase();
        if (!/^BSA-[A-Z0-9]{4}-[A-Z0-9]{4}$/.test(normalized)) fail("Enter a valid BrainServe reference.");
        const existingAppointment = appointments.find((item) => item.referenceNumber === normalized);
        const alreadyArrived = Boolean(existingAppointment?.securityIntakeAt || existingAppointment?.status === "Checked in");
        let record: AccessRecord;
        if (isBackendConfigured) {
            record = await brainServeApi.checkInByReference(normalized);
        } else {
            const appointment = readDemoAppointments().find((item) => item.referenceNumber === normalized);
            if (!appointment || appointment.status !== "APPROVED") fail("Only an approved demo appointment can check in.");
            record = { id: newClientId(), appointmentId: appointment.referenceNumber,
                visitorName: appointment.visitorDisplayName, badgeNumber: `B-${String(accessRecords.length + 1).padStart(3, "0")}`,
                checkedInAt: new Date().toISOString(), checkedOutAt: null, processedBy: userEmail };
        }
        setAccessRecords((items) => [...items, record]);
        updateAppointment(normalized, "Checked in");
        setMetrics((current) => ({ ...current,
            visitorsInside: current.visitorsInside + 1,
            arrivedVisits: alreadyArrived ? current.arrivedVisits : current.arrivedVisits + 1,
        }));
    };

    const checkInByPass = async (token: string) => {
        if (!isBackendConfigured) {
            const reference = token.replace("brainserve-demo:", "").trim().toUpperCase();
            await checkInByReference(reference);
            return;
        }
        const record = await brainServeApi.checkInWithVisitorPass(token);
        const appointment = appointments.find((item) => item.id === record.appointmentId);
        const alreadyArrived = Boolean(appointment?.securityIntakeAt || appointment?.status === "Checked in");
        setAccessRecords((items) => [...items, record]);
        setAppointments((items) => items.map((item) => item.id === record.appointmentId
            ? { ...item, status: "Checked in" } : item));
        setMetrics((current) => ({ ...current,
            visitorsInside: current.visitorsInside + 1,
            arrivedVisits: alreadyArrived ? current.arrivedVisits : current.arrivedVisits + 1,
        }));
    };
    return { checkInAppointment, checkOutAppointment, checkInByReference, checkInByPass };
}
