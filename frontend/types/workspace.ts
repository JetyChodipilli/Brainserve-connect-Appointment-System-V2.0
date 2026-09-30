import { type PublicHost } from "../lib/appointments";

export type Role = "HR Admin" | "Manager" | "Team Lead" | "CEO" | "Employee" | "Reception" | "Security" | "System Admin";

export type Screen = "welcome" | "book" | "track" | "login" | "register" | "forgot-password" | "forgot-email" | "app";

export type View = "overview" | "appointments" | "work" | "performance" | "insights" | "employees" | "terminations" | "account-lifecycle" | "visitors" | "notifications" | "organization" | "reports" | "audit" | "logs" | "settings" | "profile";

export type AppointmentStatus = "Approved" | "Awaiting Security" | "Awaiting Reception" | "Awaiting HR" | "Awaiting Team Lead" | "Awaiting Manager" | "Awaiting CEO" |
    "Pending" | "Checked in" | "Completed" | "Rejected" | "Cancelled" | "Expired";

export type Appointment = {
    id: string;
    initials: string;
    visitor: string;
    visitorEmail?: string;
    visitorPhone?: string;
    company: string;
    host: string;
    purpose: string;
    time: string;
    date: string;
    status: AppointmentStatus;
    type: string;
    referenceNumber?: string;
    hostEmployeeId?: string;
    hostCategory?: PublicHost["category"];
    routingDepartmentId?: string | null;
    requestedEmployeeId?: string | null;
    slotStart?: string;
    accessRecordId?: string;
    securityIntakeActorId?: string | null;
    securityIntakeAt?: string | null;
    arrivalVisitorName?: string | null;
    arrivalPurpose?: string | null;
    identityDocumentType?: string | null;
    identityDocumentLastFour?: string | null;
    securityNotes?: string | null;
    receptionVerificationActorId?: string | null;
    receptionVerifiedAt?: string | null;
    receptionVerificationRemarks?: string | null;
    hrApprovalActorId?: string | null;
    hrDecisionAt?: string | null;
    hrDecisionRemarks?: string | null;
    teamLeadApprovalActorId?: string | null;
    teamLeadDecisionAt?: string | null;
    teamLeadDecisionRemarks?: string | null;
    managerApprovalActorId?: string | null;
    managerDecisionAt?: string | null;
    managerDecisionRemarks?: string | null;
    ceoApprovalActorId?: string | null;
    ceoDecisionAt?: string | null;
    ceoDecisionRemarks?: string | null;
    receptionForwardActorId?: string | null;
    receptionForwardedAt?: string | null;
    receptionForwardRemarks?: string | null;
    createdAt?: string;
    assignedToCurrentActor?: boolean;
};

export type Employee = {
    id: string;
    uuid?: string;
    departmentId?: string;
    name: string;
    initials: string;
    role: string;
    department: string;
    email: string;
    hostCategory?: PublicHost["category"];
    lifecycleProtected?: boolean;
    status: "Active" | "On leave" | "Onboarding" | "Notice period" | "Suspended" | "Resigned" | "Terminated" | "Inactive";
};

export type DepartmentRosterPage = {
    items: Employee[];
    page: number;
    totalElements: number;
    totalPages: number;
    query: string;
};

export type Department = { id: string; code: string; name: string; active: boolean; version: number };

export type DashboardMetrics = { awaitingApproval: number; activeVisits: number; visitorsInside: number;
    totalEmployees: number; activeEmployees: number; arrivedVisits: number };

export type AccessRecord = { id: string; appointmentId: string; visitorName: string; badgeNumber: string;
    checkedInAt: string; checkedOutAt: string | null; processedBy: string };

export type ReceptionVisitInput = {
    visitorName: string;
    visitorEmail: string;
    visitorPhone: string;
    visitorCompany: string;
    visitType: string;
    hostEmployeeId: string;
    hostCategory: PublicHost["category"];
    routingDepartmentId: string;
    requestedEmployeeId?: string | null;
    slotStart: string;
    slotEnd: string;
    purpose: string;
    identityDocumentType?: string | null;
    identityDocumentLastFour?: string | null;
    notes?: string | null;
};

export type SecurityIntakeInput = {
    visitorName: string;
    purpose: string;
    identityDocumentType: string | null;
    identityDocumentLastFour: string | null;
    notes: string | null;
};

export type SettingsSection = "company" | "identity" | "roles" | "policy" | "notifications" | "privacy";

