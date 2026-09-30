import { type Appointment, type AppointmentStatus, type Role } from "../../types/workspace";

export function appointmentStatusFromApi(status: string): AppointmentStatus {
    const values: Record<string, AppointmentStatus> = {
        PENDING_HR_APPROVAL: "Awaiting HR",
        PENDING_TEAM_LEAD_APPROVAL: "Awaiting Team Lead",
        PENDING_MANAGER_APPROVAL: "Awaiting Manager",
        PENDING_CEO_APPROVAL: "Awaiting CEO",
        PENDING_SECURITY_INTAKE: "Awaiting Security",
        PENDING_RECEPTION_VERIFICATION: "Awaiting Reception",
        PENDING_APPROVAL: "Pending",
        PENDING_VERIFICATION: "Pending",
        APPROVED: "Approved",
        CHECKED_IN: "Checked in",
        IN_MEETING: "Checked in",
        CHECKED_OUT: "Completed",
        COMPLETED: "Completed",
        REJECTED: "Rejected",
        CANCELLED: "Cancelled",
        EXPIRED: "Expired",
    };
    return values[status] ?? "Pending";
}

export function visitTypeLabel(type: string) {
    const values: Record<string, string> = {
        INTERVIEW: "Interview", CEO_VISIT: "CEO visit", HR_VISIT: "HR visit", EMERGENCY: "Emergency visit",
        EMPLOYEE_VISIT: "Employee visit", CLIENT_MEETING: "Client meeting", VENDOR_VISIT: "Vendor visit",
    };
    return values[type] ?? type.replaceAll("_", " ").toLowerCase();
}

export function visitorInitials(name: string) {
    return name.split(" ").filter(Boolean).map((part) => part[0]).join("").slice(0, 2).toUpperCase() || "VI";
}

export function canDecideVisit(role: Role, appointment: Appointment) {
    return (role === "HR Admin" && appointment.status === "Awaiting HR"
            && appointment.assignedToCurrentActor !== false) ||
        (role === "Team Lead" && appointment.status === "Awaiting Team Lead") ||
        (role === "Manager" && appointment.status === "Awaiting Manager") ||
        (role === "CEO" && appointment.status === "Awaiting CEO");
}

export function isCeoApprovalRoute(appointment: Appointment) {
    return appointment.type === "CEO visit"
        || appointment.type === "Emergency visit"
        && (appointment.hostCategory === "CEO" || appointment.host === "CEO Office");
}

export function needsAppointmentAction(role: Role, appointment: Appointment) {
    return (canDecideVisit(role, appointment)
            && !(role === "HR Admin" && appointment.assignedToCurrentActor === false))
        || (role === "Security" && appointment.status === "Awaiting Security")
        || (role === "Reception" && appointment.status === "Awaiting Reception");
}

