package com.brainserve.appointment.employee.api;

import java.time.LocalDate;
import java.util.UUID;

public final class EmployeeLeaveEvents {
    private EmployeeLeaveEvents() {}
    public record LeaveRequested(UUID requestId, UUID employeeUserId, UUID departmentId, String employeeName,
                                 LocalDate startDate, LocalDate endDate, String reason) {}
    public record LeaveDecided(UUID requestId, UUID decidedByUserId, UUID employeeUserId, String decision,
                               LocalDate startDate, LocalDate endDate) {}
}
