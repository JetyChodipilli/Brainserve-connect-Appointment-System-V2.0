import type { WorkInsight, WorkTask } from "../types/workboard";
import { OFFICE_TIME_ZONE, officeToday } from "../../../lib/appointments";

export function officeDateFromInstant(value: string) {
    const parts = new Intl.DateTimeFormat("en-GB", { timeZone: OFFICE_TIME_ZONE, year: "numeric",
        month: "2-digit", day: "2-digit" }).formatToParts(new Date(value));
    const part = (type: string) => parts.find((item) => item.type === type)?.value ?? "";
    return `${part("year")}-${part("month")}-${part("day")}`;
}

export function workWeekStart(value = officeToday()) {
    const [year, month, day] = value.split("-").map(Number);
    const date = new Date(Date.UTC(year, month - 1, day));
    const offset = (date.getUTCDay() + 6) % 7;
    date.setUTCDate(date.getUTCDate() - offset);
    return date.toISOString().slice(0, 10);
}

export function workTaskStatusLabel(status: WorkTask["status"]) {
    return { ASSIGNED: "Assigned", IN_PROGRESS: "In progress", COMPLETED: "Awaiting approval",
        CHANGES_REQUESTED: "Rework in progress", INSIGHT_REWORK_REQUESTED: "Returned by Insights",
        APPROVED: "Approved", ACKNOWLEDGED: "Acknowledged" }[status];
}

export function insightStatusLabel(status: WorkInsight["auditStatus"]) {
    return { NOT_AUDITED: "Not audited", HR_REWORK_REQUESTED: "Returned by HR",
        PENDING_MANAGER_APPROVAL: "Awaiting Manager", MANAGER_REWORK_REQUESTED: "Returned by Manager",
        PENDING_CEO_APPROVAL: "Awaiting CEO", CEO_APPROVED: "CEO approved",
        CEO_REWORK_REQUESTED: "Returned by CEO", REWORK_ASSIGNED: "Rework assigned" }[status];
}

