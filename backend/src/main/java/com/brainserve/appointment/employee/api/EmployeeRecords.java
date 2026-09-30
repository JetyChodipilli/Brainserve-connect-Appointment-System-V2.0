package com.brainserve.appointment.employee.api;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface EmployeeRecords {
    List<EmployeeRecord> allEmployees();
    List<LeaveRecord> leavesOverlapping(LocalDate from, LocalDate to);

    record EmployeeRecord(UUID id, String employeeNumber, String displayName, String officialEmail,
                          String designation, String status, LocalDate joiningDate, LocalDate relievingDate) {}
    record LeaveRecord(UUID id, UUID employeeId, UUID requesterUserId, LocalDate startDate, LocalDate endDate,
                       String reason, String status, UUID decidedByUserId, Instant decidedAt, String decisionReason) {}
}
