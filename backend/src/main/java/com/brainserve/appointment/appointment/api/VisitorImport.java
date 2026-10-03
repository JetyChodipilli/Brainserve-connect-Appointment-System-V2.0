package com.brainserve.appointment.appointment.api;

import java.time.Instant;
import java.util.UUID;

/** Imports use the existing reception registration and retain every approval stage. */
public interface VisitorImport {
    void validateImport(Visit visit);
    UUID importPendingVisit(UUID actorId, String idempotencyKey, Visit visit);
    record Visit(String type, String visitorName, String visitorEmail, String visitorPhone,
                 String visitorCompany, UUID hostEmployeeId, UUID departmentId, UUID requestedEmployeeId,
                 Instant slotStart, Instant slotEnd, String purpose) {}
}
