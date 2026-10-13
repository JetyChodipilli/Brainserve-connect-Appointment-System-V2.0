package com.brainserve.appointment.appointment.application;

import com.brainserve.appointment.appointment.domain.*;
import com.brainserve.appointment.appointment.infrastructure.AppointmentRepository;
import com.brainserve.appointment.employee.api.EmployeeDirectory;
import com.brainserve.appointment.configuration.api.WorkspacePolicy;
import com.brainserve.appointment.audit.api.AuditService;
import com.brainserve.appointment.teamlead.api.TeamLeadDirectory;
import com.brainserve.appointment.departmenthr.api.DepartmentHrDirectory;
import com.brainserve.appointment.manager.api.ManagerDirectory;
import com.brainserve.appointment.shared.application.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.*;
import org.springframework.data.redis.core.script.RedisScript;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AppointmentOtpRegressionTest {
    private final AppointmentRepository appointments = mock(AppointmentRepository.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final ValueOperations<String,String> values = mock(ValueOperations.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final WorkspacePolicy policy = mock(WorkspacePolicy.class);
    private final AuditService audit = mock(AuditService.class);
    private final String reference = "BSA-AUDT-0001";
    private final Appointment appointment = new Appointment(reference, "synthetic-key", AppointmentType.EMPLOYEE_VISIT,
            "Synthetic", "synthetic@audit.invalid", "0000000000", "Synthetic", UUID.randomUUID(),
            UUID.randomUUID(), UUID.randomUUID(), Instant.now().plusSeconds(86400), Instant.now().plusSeconds(88200), "Synthetic");

    private AppointmentService service(Long admission) throws Exception {
        var id = com.brainserve.appointment.shared.domain.AuditableEntity.class.getDeclaredField("id");
        id.setAccessible(true); id.set(appointment, UUID.randomUUID());
        when(policy.integerValue("APPOINTMENT.SLOT_MINUTES",30)).thenReturn(30);
        when(policy.integerValue("APPOINTMENT.MAX_ADVANCE_DAYS",90)).thenReturn(90);
        when(appointments.findByReferenceNumber(reference)).thenReturn(Optional.of(appointment));
        when(appointments.findPassForUpdate(reference)).thenReturn(Optional.of(appointment));
        when(redis.opsForValue()).thenReturn(values);
        when(values.get("appointment:otp:"+reference)).thenReturn(HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest("000000".getBytes(StandardCharsets.UTF_8))));
        when(redis.execute(any(RedisScript.class),anyList())).thenReturn(admission);
        return new AppointmentService(appointments, mock(EmployeeDirectory.class),redis,events,"Asia/Kolkata",30,90,
                policy,audit,mock(TeamLeadDirectory.class),mock(DepartmentHrDirectory.class),mock(ManagerDirectory.class));
    }

    // Regression: AUD-10 — one OTP challenge's budget is shared by every source IP.
    // Found by /qa on 2026-10-13
    // Report: docs/SECURITY_FUNCTIONAL_CAPACITY_AUDIT.md
    @Test void anExhaustedChallengeRejectsEvenTheCorrectCodeWithoutChangingTheAppointment() throws Exception {
        var service=service(0L);
        assertThatThrownBy(()->service.verify(reference,"000000")).isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo("INVALID_OTP");
        assertThat(appointment.getStatus()).isEqualTo(AppointmentStatus.PENDING_VERIFICATION);
        verifyNoInteractions(audit,events);
    }
    @Test void anUnknownSecurityResultFailsClosed() throws Exception {
        var service=service(null);
        assertThatThrownBy(()->service.verify(reference,"000000")).isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo("SECURITY_STATE_UNAVAILABLE");
        assertThat(appointment.getStatus()).isEqualTo(AppointmentStatus.PENDING_VERIFICATION);
        verifyNoInteractions(audit,events);
    }
    @Test void anAdmittedChallengeWithTheCorrectCodeStillVerifies() throws Exception {
        var service=service(1L);
        assertThat(service.verify(reference.toLowerCase(Locale.ROOT),"000000").getStatus())
                .isEqualTo(AppointmentStatus.PENDING_SECURITY_INTAKE);
    }
}
