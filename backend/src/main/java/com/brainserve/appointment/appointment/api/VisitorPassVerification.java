package com.brainserve.appointment.appointment.api;

import java.time.Instant;
import java.util.UUID;

public interface VisitorPassVerification {
    VerifiedPass verify(String token);

    record VerifiedPass(UUID appointmentId, String referenceNumber, String visitorName, String visitorCompany,
                        String appointmentStatus, Instant slotStart, Instant slotEnd, Instant validUntil) {}
}
