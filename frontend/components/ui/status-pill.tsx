"use client";

import { type Appointment, type Employee } from "../../types/workspace";

export function StatusPill({ status }: {
    status: Appointment["status"] | Employee["status"] | "Verified" | "Pending verification";
}) {
    const key = status.toLowerCase().replace(" ", "-");
    return <span className={`status-pill status-${key}`}><span />{status}</span>;
}

