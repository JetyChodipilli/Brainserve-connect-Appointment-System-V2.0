import { type HistoryDataset, type HistoryRow } from "../../services/brainserve-api";
import { officeDateTimeToIso, officeToday } from "../../lib/appointments";
import { readDemoEmployees } from "../../preview/directory";
import { readDemoAccountLifecycle, readDemoEssentialLogs, readDemoTerminations } from "../../preview/governance";
import { readDemoWorkTasks } from "../workboard/preview";
import { type AccessRecord, type Appointment, type Role } from "../../types/workspace";

export const historyDatasetsByRole: Record<Role, HistoryDataset[]> = {
    "System Admin": ["VISITS", "EMPLOYEES", "TERMINATIONS", "WORKBOARD", "AUDIT", "CHECKPOINTS", "ESSENTIAL_LOGS"],
    CEO: ["VISITS", "EMPLOYEES", "TERMINATIONS", "WORKBOARD", "AUDIT", "CHECKPOINTS"],
    Manager: ["VISITS", "EMPLOYEES", "WORKBOARD", "CHECKPOINTS"],
    "HR Admin": ["VISITS", "EMPLOYEES", "TERMINATIONS", "WORKBOARD", "AUDIT", "CHECKPOINTS"],
    "Team Lead": ["VISITS", "EMPLOYEES", "WORKBOARD"],
    Reception: ["VISITS", "CHECKPOINTS"],
    Security: ["VISITS", "CHECKPOINTS"],
    Employee: ["VISITS", "WORKBOARD"],
};

export const historyDatasetLabels: Record<HistoryDataset, string> = {
    VISITS: "Visits & appointments",
    EMPLOYEES: "Employee records",
    TERMINATIONS: "Termination records",
    WORKBOARD: "Work board activity",
    AUDIT: "Audit trail",
    CHECKPOINTS: "Visitor checkpoints",
    ESSENTIAL_LOGS: "Essential business logs",
};

export function previewAppointmentInstant(appointment: Appointment) {
    const persisted = appointment.slotStart ?? appointment.receptionVerifiedAt
        ?? appointment.securityIntakeAt ?? appointment.createdAt;
    if (persisted && !Number.isNaN(new Date(persisted).getTime())) return persisted;
    const match = appointment.time.match(/^(\d{1,2}):(\d{2})\s*(AM|PM)$/i);
    if (!match) return officeDateTimeToIso(officeToday(), "12:00");
    let hour = Number(match[1]) % 12;
    if (match[3].toUpperCase() === "PM") hour += 12;
    return officeDateTimeToIso(officeToday(), `${String(hour).padStart(2, "0")}:${match[2]}`);
}

export function previewHistoryRows(dataset: HistoryDataset, appointments: Appointment[],
                            accessRecords: AccessRecord[]): HistoryRow[] {
    const monthStart = `${officeToday().slice(0, 8)}01`;
    const rows: HistoryRow[] = dataset === "VISITS"
        ? appointments.map((appointment) => ({
            id: appointment.id, occurredAt: previewAppointmentInstant(appointment), dataset,
            departmentId: appointment.routingDepartmentId ?? null,
            primaryLabel: appointment.referenceNumber ?? appointment.id,
            secondaryLabel: `${appointment.visitor} · ${appointment.host}`,
            status: appointment.status,
            details: { visitType: appointment.type, company: appointment.company, purpose: appointment.purpose },
        }))
        : dataset === "EMPLOYEES"
            ? readDemoEmployees().map((employee, index) => ({
                id: employee.uuid ?? employee.id,
                occurredAt: officeDateTimeToIso(
                    `${monthStart.slice(0, 8)}${String(Math.min(index + 1, 28)).padStart(2, "0")}`, "09:00"),
                dataset, departmentId: employee.departmentId ?? null, primaryLabel: employee.id,
                secondaryLabel: `${employee.name} · ${employee.role}`, status: employee.status,
                details: { email: employee.email, department: employee.department },
            }))
            : dataset === "TERMINATIONS"
                ? readDemoTerminations().map((termination) => ({
                    id: termination.id, occurredAt: termination.requestedAt, dataset,
                    departmentId: termination.departmentId, primaryLabel: termination.employeeNumber,
                    secondaryLabel: `${termination.employeeName} · termination request`, status: termination.status,
                    details: { reason: termination.reason, effectiveDate: termination.effectiveDate,
                        decisionNote: termination.decisionNote },
                }))
                : dataset === "WORKBOARD"
                    ? readDemoWorkTasks().map((task) => ({
                        id: task.id, occurredAt: task.createdAt, dataset, departmentId: task.departmentId,
                        primaryLabel: task.title, secondaryLabel: `${task.departmentBranch} · ${task.employeeId}`,
                        status: task.status, details: { dueDate: task.dueDate, description: task.description },
                    }))
                    : dataset === "AUDIT"
                        ? readDemoAccountLifecycle().map((event) => ({
                            id: event.id, occurredAt: event.occurredAt, dataset, departmentId: null,
                            primaryLabel: event.eventType, secondaryLabel: `${event.targetUserId} · ${event.toStatus}`,
                            status: event.toStatus, details: { fromStatus: event.fromStatus, detail: event.detail },
                        }))
                        : dataset === "CHECKPOINTS"
                            ? accessRecords.map((record) => ({
                                id: record.id, occurredAt: record.checkedOutAt ?? record.checkedInAt, dataset,
                                departmentId: null, primaryLabel: record.badgeNumber,
                                secondaryLabel: `${record.visitorName} · access checkpoint`,
                                status: record.checkedOutAt ? "CHECKED_OUT" : "CHECKED_IN",
                                details: { appointmentId: record.appointmentId, processedBy: record.processedBy,
                                    checkedInAt: record.checkedInAt, checkedOutAt: record.checkedOutAt },
                            }))
                            : readDemoEssentialLogs().map((record) => ({
                                id: record.id, occurredAt: record.occurredAt, dataset, departmentId: null,
                                primaryLabel: record.eventType, secondaryLabel: record.title, status: record.status,
                                details: { category: record.category, subjectType: record.subjectType,
                                    subjectId: record.subjectId, detail: record.detail },
                            }));
    return rows.sort((left, right) =>
        new Date(right.occurredAt).getTime() - new Date(left.occurredAt).getTime());
}

export function nextIsoDay(date: string) {
    const value = new Date(`${date}T00:00:00.000Z`); value.setUTCDate(value.getUTCDate() + 1);
    return value.toISOString().slice(0, 10);
}

