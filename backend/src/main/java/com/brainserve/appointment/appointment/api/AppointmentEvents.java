package com.brainserve.appointment.appointment.api;

import java.time.Instant;
import java.util.UUID;

public final class AppointmentEvents {
    private AppointmentEvents() {}
    public record AppointmentRequested(UUID appointmentId, String reference, String visitorEmail, String otp, Instant occurredAt) {}
    public record AppointmentCancellationOtpRequested(UUID appointmentId, String reference,
                                                      String visitorEmail, String otp,
                                                      Instant occurredAt) {}
    public record AppointmentStatusChanged(UUID appointmentId, String reference, String status, Instant occurredAt) {}
    public record TeamLeadApprovalRequested(UUID appointmentId, String reference, UUID hrUserId,
                                            UUID teamLeadUserId, String teamLeadEmail, String visitorName,
                                            String purpose, Instant occurredAt) {}
    public record ManagerApprovalRequested(UUID appointmentId, String reference, UUID managerUserId,
                                           UUID routingDepartmentId, String visitorName, String purpose,
                                           Instant occurredAt) {}
    public record CeoVisitDecisionRecorded(UUID appointmentId, String reference, UUID ceoUserId,
                                           UUID routingDepartmentId, boolean approved, String remarks,
                                           Instant occurredAt) {}
    public record EmployeeVisitCardUpdated(UUID appointmentId, String reference, UUID actorUserId,
                                           UUID hostEmployeeId, String visitorName, String visitorEmail,
                                           String visitorPhone, String visitorCompany, String purpose,
                                           Instant slotStart, String status, Instant occurredAt) {}
    public record SecurityIntakeRecorded(UUID appointmentId, String reference, UUID securityActorUserId,
                                         String visitorName, String purpose, Instant occurredAt) {}
    public record ReceptionForwarded(UUID appointmentId, String reference, UUID receptionistUserId,
                                     UUID hostEmployeeId, String appointmentType, String visitorName,
                                     String remarks, Instant occurredAt) {}
    public record ReceptionVerified(UUID appointmentId, String reference, UUID receptionistUserId,
                                    UUID hostEmployeeId, UUID routingDepartmentId, String appointmentType,
                                    String approvalStatus, String visitorName, String purpose, Instant occurredAt) {
        public ReceptionVerified(UUID appointmentId, String reference, UUID receptionistUserId,
                                 UUID hostEmployeeId, UUID routingDepartmentId, String appointmentType,
                                 String visitorName, String purpose, Instant occurredAt) {
            this(appointmentId, reference, receptionistUserId, hostEmployeeId, routingDepartmentId,
                    appointmentType, "PENDING_HR_APPROVAL", visitorName, purpose, occurredAt);
        }
    }
}
