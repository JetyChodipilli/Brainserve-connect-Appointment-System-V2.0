package com.brainserve.appointment.appointment.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface AppointmentRecords {

    List<VisitRecord> receptionVisitsBetween(
            Instant fromInclusive,
            Instant toExclusive
    );

    record VisitRecord(
            UUID id,
            String referenceNumber,
            String type,
            String status,
            String visitorName,
            String visitorEmail,
            String visitorPhone,
            String visitorCompany,

            /*
             * Appointment routing
             */
            UUID hostEmployeeId,
            UUID routingDepartmentId,
            UUID requestedEmployeeId,

            /*
             * Appointment schedule
             */
            Instant slotStart,
            Instant slotEnd,
            String purpose,

            /*
             * Security intake
             */
            UUID securityIntakeActorId,
            Instant securityIntakeAt,
            String arrivalVisitorName,
            String arrivalPurpose,
            String identityDocumentType,
            String identityDocumentLastFour,
            String securityNotes,

            /*
             * Reception verification
             */
            UUID receptionVerificationActorId,
            Instant receptionVerifiedAt,
            String receptionVerificationRemarks,

            /*
             * HR approval
             */
            UUID hrApprovalActorId,
            Instant hrDecisionAt,

            /*
             * Team Lead approval
             */
            UUID teamLeadApprovalActorId,
            Instant teamLeadDecisionAt,

            /*
             * Manager approval
             */
            UUID managerApprovalActorId,
            Instant managerDecisionAt,

            /*
             * CEO approval
             */
            UUID ceoApprovalActorId,
            Instant ceoDecisionAt,

            /*
             * Reception forwarding
             */
            UUID receptionForwardActorId,
            Instant receptionForwardedAt,
            String receptionForwardRemarks
    ) {
    }
}