"use client";

import { playNotificationSound } from "../../lib/notification-sounds";
import { type WorkspaceState } from "./use-workspace-state";
import { useEffect } from "react";

export function useAppointmentSounds(workspace: Pick<WorkspaceState, "appointments" | "appointmentSoundSnapshotRef">) {
    const { appointments, appointmentSoundSnapshotRef } = workspace;

    useEffect(() => {
        const snapshot = new Map(appointments.map((item) => [item.id, item.status]));
        const previous = appointmentSoundSnapshotRef.current;
        appointmentSoundSnapshotRef.current = snapshot;
        if (!previous) return;
        const changed = appointments.filter((item) => previous.has(item.id) && previous.get(item.id) !== item.status);
        const added = appointments.filter((item) => !previous.has(item.id));
        if (added.some((item) => item.status === "Awaiting Reception" || item.status === "Checked in")) {
            void playNotificationSound("visitor");
        } else if (changed.some((item) => item.status.startsWith("Awaiting") || item.status === "Pending")) {
            void playNotificationSound("approval");
        } else if (changed.length > 0 || added.length > 0) {
            void playNotificationSound("appointment");
        }
    }, [appointmentSoundSnapshotRef, appointments]);
}
