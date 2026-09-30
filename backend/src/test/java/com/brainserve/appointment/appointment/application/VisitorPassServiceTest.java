package com.brainserve.appointment.appointment.application;

import com.brainserve.appointment.appointment.domain.Appointment;
import com.brainserve.appointment.appointment.domain.AppointmentType;
import com.brainserve.appointment.appointment.infrastructure.AppointmentRepository;
import com.brainserve.appointment.configuration.api.WorkspacePolicy;
import com.brainserve.appointment.shared.application.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VisitorPassServiceTest {
    @Mock AppointmentRepository appointments;
    @Mock WorkspacePolicy policy;
    private VisitorPassService service;
    private Appointment appointment;

    @BeforeEach
    void setUp() {
        Instant start = Instant.now().plus(5, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.SECONDS);
        appointment = new Appointment("BSA-ABCD-2345", "visitor-pass-test", AppointmentType.EMPLOYEE_VISIT,
                "Asha Rao", "asha@example.com", "+919876543210", "Example Ltd", UUID.randomUUID(),
                start, start.plus(30, ChronoUnit.MINUTES), "Product meeting");
        appointment.verify();
        appointment.recordSecurityIntake(UUID.randomUUID(), "Asha Rao", "Product meeting",
                "AADHAAR", "1234", "Identity confirmed at the gate");
        appointment.verifyByReception(UUID.randomUUID(), "Arrival verified");
        appointment.approveByHr(UUID.randomUUID(), "Approved");
        when(appointments.findByReferenceNumber("BSA-ABCD-2345")).thenReturn(Optional.of(appointment));
        when(policy.integerValue("APPOINTMENT.CHECK_IN_EARLY_MINUTES", 30)).thenReturn(30);
        when(policy.integerValue("APPOINTMENT.QR_EXPIRY_MINUTES_AFTER_END", 120)).thenReturn(120);
        service = new VisitorPassService(appointments, policy,
                "test-qr-signing-secret-with-more-than-thirty-two-characters",
                "https://connect.brainserve.in");
    }

    @Test
    void issuesAndVerifiesSignedQrPass() {
        var pass = service.issue("BSA-ABCD-2345");

        assertThat(pass.qrCodeDataUrl()).startsWith("data:image/png;base64,");
        assertThat(pass.token()).contains(".");
        assertThat(service.verify(pass.token()).referenceNumber()).isEqualTo("BSA-ABCD-2345");
    }

    @Test
    void rejectsTamperedPass() {
        String token = service.issue("BSA-ABCD-2345").token();
        assertThatThrownBy(() -> service.verify(token + "tampered"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("invalid");
    }
}
