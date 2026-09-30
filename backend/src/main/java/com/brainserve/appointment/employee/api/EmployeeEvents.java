package com.brainserve.appointment.employee.api;

import java.time.Instant;
import java.util.UUID;

public final class EmployeeEvents {
    private EmployeeEvents() {}
    public record EmployeeCreated(UUID employeeId, String employeeNumber, String officialEmail, Instant occurredAt) {}
    public record EmployeeStatusChanged(UUID employeeId, String previous, String current, Instant occurredAt) {}
}
