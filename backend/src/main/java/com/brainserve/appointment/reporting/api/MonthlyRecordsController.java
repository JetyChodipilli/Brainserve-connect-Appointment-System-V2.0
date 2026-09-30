package com.brainserve.appointment.reporting.api;

import com.brainserve.appointment.appointment.api.AppointmentRecords;
import com.brainserve.appointment.employee.api.EmployeeRecords;
import com.brainserve.appointment.reception.api.ReceptionRecords;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.*;
import java.util.*;

@RestController
@RequestMapping("/api/v1/admin/records")
public class MonthlyRecordsController {
    private final AppointmentRecords appointments; private final EmployeeRecords employees;
    private final ReceptionRecords reception; private final ZoneId officeZone;
    public MonthlyRecordsController(AppointmentRecords appointments, EmployeeRecords employees,
                                    ReceptionRecords reception,
                                    @Value("${brainserve.appointment.office-zone:Asia/Kolkata}") String officeZone) {
        this.appointments = appointments; this.employees = employees; this.reception = reception;
        this.officeZone = ZoneId.of(officeZone);
    }
    @GetMapping("/monthly")
    @Deprecated(forRemoval = true)
    @PreAuthorize("hasAuthority('WORKFORCE_RECORD_VIEW')")
    MonthlyRecords monthly(@RequestParam int year, @RequestParam int month) {
        YearMonth period = YearMonth.of(year, month);
        LocalDate fromDate = period.atDay(1), toDate = period.plusMonths(1).atDay(1);
        Instant from = fromDate.atStartOfDay(officeZone).toInstant(), to = toDate.atStartOfDay(officeZone).toInstant();
        var staff = employees.allEmployees();
        Map<UUID, String> names = new HashMap<>(); staff.forEach(value -> names.put(value.id(), value.displayName()));
        var arrivedVisits = appointments.receptionVisitsBetween(from, to);
        Map<UUID, ReceptionRecords.AccessRecord> accessByAppointment = new HashMap<>();
        reception.forAppointments(arrivedVisits.stream().map(AppointmentRecords.VisitRecord::id).toList())
                .forEach(value -> accessByAppointment.put(value.appointmentId(), value));
        var visits = arrivedVisits.stream().map(value -> {
            var access = accessByAppointment.get(value.id());
            return new VisitorRecord(value.id(), value.referenceNumber(),
                    value.arrivalVisitorName() == null ? value.visitorName() : value.arrivalVisitorName(),
                    value.visitorEmail(), value.visitorPhone(), value.visitorCompany(), value.type(), value.status(),
                    value.hostEmployeeId(), names.getOrDefault(value.hostEmployeeId(), "BrainServe host"),
                    value.routingDepartmentId(), value.requestedEmployeeId(),
                    value.requestedEmployeeId() == null ? null : names.getOrDefault(value.requestedEmployeeId(), "Employee"),
                    value.slotStart(),
                    value.arrivalPurpose() == null ? value.purpose() : value.arrivalPurpose(),
                    value.identityDocumentType(), value.identityDocumentLastFour(), value.securityIntakeActorId(),
                    value.securityIntakeAt(), value.receptionVerificationActorId(), value.receptionVerifiedAt(),
                    value.receptionVerificationRemarks(), value.hrApprovalActorId(), value.hrDecisionAt(),
                    value.teamLeadApprovalActorId(), value.teamLeadDecisionAt(),
                    value.managerApprovalActorId(), value.managerDecisionAt(),
                    value.ceoApprovalActorId(), value.ceoDecisionAt(), value.receptionForwardActorId(),
                    value.receptionForwardedAt(), value.receptionForwardRemarks(),
                    access == null ? null : access.badgeNumber(), access == null ? null : access.checkedInAt(),
                    access == null ? null : access.checkedOutAt(), access == null ? null : access.processedBy());
        }).toList();
        var leaves = employees.leavesOverlapping(fromDate, toDate.minusDays(1));
        long joined = staff.stream().filter(value -> !value.joiningDate().isBefore(fromDate) && value.joiningDate().isBefore(toDate)).count();
        long relieved = staff.stream().filter(value -> value.relievingDate() != null && !value.relievingDate().isBefore(fromDate)
                && value.relievingDate().isBefore(toDate)).count();
        return new MonthlyRecords(period.toString(), Instant.now(), visits.size(), staff.size(), joined, relieved,
                leaves.stream().filter(value -> "PENDING".equals(value.status())).count(), visits, staff, leaves);
    }
    public record MonthlyRecords(String period, Instant generatedAt, int visitorCount, int employeeCount,
                                 long joinedEmployees, long relievedEmployees, long pendingLeaveRequests,
                                 List<VisitorRecord> visitors, List<EmployeeRecords.EmployeeRecord> employees,
                                 List<EmployeeRecords.LeaveRecord> leaveRequests) {}
    public record VisitorRecord(UUID id, String referenceNumber, String visitorName, String visitorEmail,
                                String visitorPhone, String visitorCompany, String type, String status,
                                UUID hostEmployeeId, String hostName, UUID routingDepartmentId,
                                UUID requestedEmployeeId, String requestedEmployeeName, Instant slotStart, String purpose,
                                String identityDocumentType, String identityDocumentLastFour,
                                UUID securityActorId, Instant securityIntakeAt,
                                UUID receptionActorId, Instant receptionVerifiedAt, String receptionRemarks,
                                UUID hrActorId, Instant hrDecisionAt,
                                UUID teamLeadActorId, Instant teamLeadDecisionAt,
                                UUID managerActorId, Instant managerDecisionAt,
                                UUID ceoActorId, Instant ceoDecisionAt,
                                UUID receptionForwardActorId, Instant receptionForwardedAt,
                                String receptionForwardRemarks, String badgeNumber, Instant checkedInAt,
                                Instant checkedOutAt, String processedBy) {}
}
