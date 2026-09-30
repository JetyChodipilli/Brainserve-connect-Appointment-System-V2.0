"use client";

import { type WorkTask } from "../../../lib/api";
import { workTaskStatusLabel } from "../work-utils";

export function WorkTaskPill({ status, label }: { status: WorkTask["status"]; label?: string }) {
    return <span className={`work-task-status work-task-${status.toLowerCase().replaceAll("_", "-")}`}><i />{label ?? workTaskStatusLabel(status)}</span>;
}

