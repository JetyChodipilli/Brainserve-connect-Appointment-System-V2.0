package com.brainserve.appointment.appointment.application;

import com.brainserve.appointment.appointment.domain.AppointmentType;
import com.brainserve.appointment.appointment.infrastructure.AppointmentRepository;
import com.brainserve.appointment.employee.api.EmployeeDirectory;
import com.brainserve.appointment.shared.application.BusinessException;
import com.brainserve.appointment.teamlead.api.TeamLeadDirectory;
import com.brainserve.appointment.configuration.api.WorkspacePolicy;
import com.brainserve.appointment.audit.api.AuditService;
import com.brainserve.appointment.departmenthr.api.DepartmentHrDirectory;
import com.brainserve.appointment.manager.api.ManagerDirectory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AppointmentServiceValidationTest {
    @Mock AppointmentRepository appointments;
    @Mock EmployeeDirectory employees;
    @Mock StringRedisTemplate redis;
    @Mock ApplicationEventPublisher events;
    @Mock WorkspacePolicy workspacePolicy;
    @Mock AuditService audit;
    @Mock TeamLeadDirectory teamLeads;
    @Mock DepartmentHrDirectory departmentHrs;
    @Mock ManagerDirectory managers;
    private AppointmentService service;

    @BeforeEach
    void setUp() {
        when(workspacePolicy.integerValue("APPOINTMENT.SLOT_MINUTES", 30)).thenReturn(30);
        when(workspacePolicy.integerValue("APPOINTMENT.MAX_ADVANCE_DAYS", 90)).thenReturn(90);
        service = new AppointmentService(appointments, employees, redis, events, "Asia/Kolkata", 30, 90,
                workspacePolicy, audit, teamLeads, departmentHrs, managers);
    }

    @Test
    void rejectsAClientSuppliedDurationThatDoesNotMatchPublishedSlots() {
        String key = "duration-test-key";
        when(appointments.findByIdempotencyKey(key)).thenReturn(Optional.empty());
        ZonedDateTime start = nextBusinessDayAtNineThirty();
        var command = new AppointmentService.CreateAppointment(AppointmentType.EMPLOYEE_VISIT,
                "Visitor Name", "visitor@example.com", "+919876543210", "Example",
                UUID.randomUUID(), start.toInstant(), start.plus(Duration.ofMinutes(45)).toInstant(), "Planning meeting");

        assertThatThrownBy(() -> service.request(key, command))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("exactly 30 minutes");
    }

    @Test
    void rejectsAStartTimeThatWasNotPublishedByAvailabilityService() {
        String key = "alignment-test-key";
        when(appointments.findByIdempotencyKey(key)).thenReturn(Optional.empty());
        ZonedDateTime start = nextBusinessDayAtNineThirty().plusMinutes(5);
        var command = new AppointmentService.CreateAppointment(AppointmentType.EMPLOYEE_VISIT,
                "Visitor Name", "visitor@example.com", "+919876543210", "Example",
                UUID.randomUUID(), start.toInstant(), start.plus(Duration.ofMinutes(30)).toInstant(), "Planning meeting");

        assertThatThrownBy(() -> service.request(key, command))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("published available slots");
    }

    @Test
    void rejectsARegularEmployeeSelectedForACeoVisit() {
        String key = "ceo-host-role-test";
        when(appointments.findByIdempotencyKey(key)).thenReturn(Optional.empty());
        UUID hostId = UUID.randomUUID();
        when(employees.hostCategory(hostId)).thenReturn(EmployeeDirectory.HostCategory.EMPLOYEE);
        ZonedDateTime start = nextBusinessDayAtNineThirty();
        var command = new AppointmentService.CreateAppointment(AppointmentType.CEO_VISIT,
                "Visitor Name", "visitor@example.com", "+919876543210", "Example",
                hostId, start.toInstant(), start.plus(Duration.ofMinutes(30)).toInstant(), "Leadership meeting");

        assertThatThrownBy(() -> service.request(key, command))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("active CEO host");
    }

    @Test
    void rejectsARegularEmployeeSelectedForAnHrVisitOrInterview() {
        String key = "hr-host-role-test";
        when(appointments.findByIdempotencyKey(key)).thenReturn(Optional.empty());
        UUID hostId = UUID.randomUUID();
        when(employees.hostCategory(hostId)).thenReturn(EmployeeDirectory.HostCategory.EMPLOYEE);
        ZonedDateTime start = nextBusinessDayAtNineThirty();
        var command = new AppointmentService.CreateAppointment(AppointmentType.INTERVIEW,
                "Candidate Name", "candidate@example.com", "+919876543210", "Example",
                hostId, start.toInstant(), start.plus(Duration.ofMinutes(30)).toInstant(), "Candidate interview");

        assertThatThrownBy(() -> service.request(key, command))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("active HR host");
    }

    private ZonedDateTime nextBusinessDayAtNineThirty() {
        ZonedDateTime value = ZonedDateTime.now(ZoneId.of("Asia/Kolkata")).plusDays(1)
                .withHour(9).withMinute(30).withSecond(0).withNano(0);
        while (value.getDayOfWeek() == DayOfWeek.SATURDAY || value.getDayOfWeek() == DayOfWeek.SUNDAY) {
            value = value.plusDays(1);
        }
        return value;
    }
}
