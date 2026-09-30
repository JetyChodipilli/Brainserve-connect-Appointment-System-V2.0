package com.brainserve.appointment.availability.api;

import com.brainserve.appointment.appointment.api.AppointmentAvailability;
import com.brainserve.appointment.employee.api.EmployeeDirectory;
import com.brainserve.appointment.configuration.api.WorkspacePolicy;
import com.brainserve.appointment.appointment.domain.AppointmentType;
import com.brainserve.appointment.departmenthr.api.DepartmentHrDirectory;
import com.brainserve.appointment.organization.api.OrganizationDirectory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping({"/api/v1/public/hosts", "/api/v1/hosts"})
public class AvailabilityController {
    private final AppointmentAvailability appointments;
    private final EmployeeDirectory employees;
    private final ZoneId officeZone;
    private final int slotMinutes;
    private final WorkspacePolicy workspacePolicy;
    private final DepartmentHrDirectory departmentHrs;
    private final OrganizationDirectory organization;

    public AvailabilityController(AppointmentAvailability appointments, EmployeeDirectory employees,
                                  @Value("${brainserve.appointment.office-zone}") String officeZone,
                                  @Value("${brainserve.appointment.slot-minutes}") int slotMinutes,
                                  WorkspacePolicy workspacePolicy, DepartmentHrDirectory departmentHrs,
                                  OrganizationDirectory organization) {
        this.appointments = appointments; this.employees = employees; this.officeZone = ZoneId.of(officeZone);
        this.slotMinutes = slotMinutes; this.workspacePolicy = workspacePolicy;
        this.departmentHrs = departmentHrs; this.organization = organization;
    }

    @GetMapping
    public List<PublicHost> hosts() {
        return employees.activeHosts().stream().map(host -> {
                    if (host.category() != EmployeeDirectory.HostCategory.HR) return new PublicHost(host.id(),
                            host.displayName(), host.designation(), host.departmentId(), host.departmentName(), host.category());
                    return departmentHrs.activeForHrEmployee(host.id()).map(assignment -> {
                        var department = organization.requireActiveDepartment(assignment.departmentId());
                        return new PublicHost(host.id(), host.displayName(), host.designation(), department.id(),
                                department.name(), host.category());
                    }).orElse(null);
                }).filter(java.util.Objects::nonNull)
                .toList();
    }

    @GetMapping("/{employeeId}/available-slots")
    public List<AvailableSlot> slots(@PathVariable UUID employeeId, @RequestParam LocalDate date,
                                     @RequestParam(defaultValue = "EMPLOYEE_VISIT") AppointmentType type) {
        employees.requireActiveHost(employeeId);
        LocalDate today = LocalDate.now(officeZone);
        boolean emergencyToday = type == AppointmentType.EMERGENCY && date.equals(today);
        if (date.isBefore(today) || (!emergencyToday
                && (date.getDayOfWeek() == DayOfWeek.SATURDAY || date.getDayOfWeek() == DayOfWeek.SUNDAY))) return List.of();
        int effectiveSlotMinutes = workspacePolicy.integerValue("APPOINTMENT.SLOT_MINUTES", slotMinutes);
        int minimumLeadMinutes = Math.max(0, workspacePolicy.integerValue("APPOINTMENT.MIN_LEAD_MINUTES", 10));
        Instant earliestStart = Instant.now().plusSeconds(minimumLeadMinutes * 60L);
        List<AvailableSlot> slots = new ArrayList<>();
        ZonedDateTime cursor = ZonedDateTime.of(date, LocalTime.of(9, 30), officeZone);
        ZonedDateTime end = ZonedDateTime.of(date, LocalTime.of(17, 30), officeZone);
        while (cursor.plusMinutes(effectiveSlotMinutes).compareTo(end) <= 0) {
            Instant start = cursor.toInstant();
            Instant finish = cursor.plusMinutes(effectiveSlotMinutes).toInstant();
            if (start.isAfter(earliestStart) && !appointments.isSlotReserved(employeeId, start, finish))
                slots.add(new AvailableSlot(start, finish, officeZone.getId()));
            cursor = cursor.plusMinutes(effectiveSlotMinutes + 10L);
        }
        return slots;
    }

    public record AvailableSlot(Instant start, Instant end, String officeTimeZone) {}
    public record PublicHost(UUID id, String displayName, String designation, UUID departmentId, String departmentName,
                             EmployeeDirectory.HostCategory category) {}
}
