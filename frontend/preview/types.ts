import { type AccountRecoveryRequest, type InternalNotification, type ProvisioningAccount } from "../services/brainserve-api";
import { type PublicAppointment, type PublicHost } from "../lib/appointments";

export type DemoProvisioningAccount = ProvisioningAccount & {
    passwordHash: string;
    employeeId?: string | null;
    forcePasswordChange?: boolean;
};

export type DemoRecoveryRequest = AccountRecoveryRequest & { recoveryCode?: string | null };

export type DemoInternalNotification = InternalNotification & { senderEmail: string; recipientEmail: string };

export type DemoAppointment = PublicAppointment & {
    id?: string;
    visitorName?: string;
    visitorEmail?: string;
    visitorPhone?: string;
    visitorCompany?: string | null;
    purpose?: string;
    routingDepartmentId?: string | null;
    requestedEmployeeId?: string | null;
    identityDocumentType?: string | null;
    identityDocumentLastFour?: string | null;
    notes?: string | null;
    securityIntakeAt?: string | null;
    receptionVerifiedAt?: string | null;
    receptionVerificationRemarks?: string | null;
    receptionForwardedAt?: string | null;
    createdAt?: string;
    hostCategory?: PublicHost["category"];
    managerApprovalActorId?: string | null;
    managerDecisionAt?: string | null;
    managerDecisionRemarks?: string | null;
    ceoApprovalActorId?: string | null;
    ceoDecisionAt?: string | null;
    ceoDecisionRemarks?: string | null;
};

