package com.brainserve.appointment.appointment.domain;

import com.brainserve.appointment.shared.application.BusinessException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AppointmentStateTest {
    private Appointment appointment() {
        Instant start = Instant.now().plus(1, ChronoUnit.DAYS);
        return new Appointment("BSA-7M4K-26Q9", "idempotency-key-1", AppointmentType.EMPLOYEE_VISIT,
                "Arjun Kumar", "arjun@example.com", "+919876543210", "Acme", UUID.randomUUID(),
                start, start.plus(30, ChronoUnit.MINUTES), "Product partnership");
    }

    private Appointment appointment(AppointmentType type) {
        Instant start = Instant.now().plus(1, ChronoUnit.DAYS);
        return new Appointment("BSA-4CEO-HR01", "idempotency-key-2", type,
                "Sara Mathew", "sara@example.com", "+919876543211", "Vertex", UUID.randomUUID(),
                start, start.plus(30, ChronoUnit.MINUTES), "Leadership meeting");
    }

    @Test
    void publicVisitRequiresSecurityReceptionAndHrBeforeCheckIn() {
        Appointment appointment = appointment();
        appointment.verify();
        assertThat(appointment.getStatus()).isEqualTo(AppointmentStatus.PENDING_SECURITY_INTAKE);
        appointment.recordSecurityIntake(UUID.randomUUID(), "Arjun Kumar", "Product partnership",
                "AADHAAR", "1234", "Identity matched");
        assertThat(appointment.getStatus()).isEqualTo(AppointmentStatus.PENDING_RECEPTION_VERIFICATION);
        appointment.verifyByReception(UUID.randomUUID(), "Booking details matched");
        assertThat(appointment.getStatus()).isEqualTo(AppointmentStatus.PENDING_HR_APPROVAL);
        appointment.approveByHr(UUID.randomUUID(), "Approved");
        appointment.checkIn();
        assertThat(appointment.getStatus()).isEqualTo(AppointmentStatus.CHECKED_IN);
    }

    @Test
    void employeeVisitCanRequireDepartmentTeamLeadAfterHrReview() {
        Appointment appointment = appointment();
        UUID hr = UUID.randomUUID();
        UUID teamLead = UUID.randomUUID();

        appointment.verify();
        appointment.recordSecurityIntake(UUID.randomUUID(), "Arjun Kumar", "Product partnership",
                "AADHAAR", "1234", "Identity matched");
        appointment.verifyByReception(UUID.randomUUID(), "Booking details matched");
        appointment.approveByHr(hr, "Route to the owning department", true);

        assertThat(appointment.getStatus()).isEqualTo(AppointmentStatus.PENDING_TEAM_LEAD_APPROVAL);
        assertThatThrownBy(appointment::checkIn).isInstanceOf(BusinessException.class);

        appointment.approveByTeamLead(teamLead, "Department calendar confirmed");
        assertThat(appointment.getStatus()).isEqualTo(AppointmentStatus.APPROVED);
        assertThat(appointment.getTeamLeadApprovalActorId()).isEqualTo(teamLead);
        assertThat(appointment.getTeamLeadDecisionAt()).isNotNull();
    }

    @Test
    void unverifiedAppointmentCannotBeApproved() {
        Appointment appointment = appointment();
        assertThatThrownBy(() -> appointment.approve(UUID.randomUUID(), "Approved"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("PENDING_VERIFICATION");
    }

    @Test
    void receptionistCreatedInterviewStillRequiresSecurityAndReceptionBeforeHr() {
        Appointment appointment = appointment(AppointmentType.INTERVIEW);
        UUID receptionist = UUID.randomUUID();
        UUID hr = UUID.randomUUID();

        appointment.submitByReception(receptionist);
        assertThat(appointment.getStatus()).isEqualTo(AppointmentStatus.PENDING_SECURITY_INTAKE);
        assertThat(appointment.getRegisteredByUserId()).isEqualTo(receptionist);
        appointment.recordSecurityIntake(UUID.randomUUID(), "Sara Mathew", "Candidate interview",
                "DRIVING_LICENCE", "9X2K", null);
        appointment.verifyByReception(UUID.randomUUID(), "Security data confirmed");

        appointment.approveByHr(hr, "Candidate details verified");
        assertThat(appointment.getStatus()).isEqualTo(AppointmentStatus.APPROVED);
        assertThat(appointment.getHrApprovalActorId()).isEqualTo(hr);
    }

    @Test
    void ceoVisitRequiresManagerThenCeoFinalDecision() {
        Appointment appointment = appointment(AppointmentType.CEO_VISIT);
        UUID manager = UUID.randomUUID();
        UUID ceo = UUID.randomUUID();
        appointment.submitByReception(UUID.randomUUID());
        appointment.recordSecurityIntake(UUID.randomUUID(), "Sara Mathew", "Leadership meeting",
                "PASSPORT", "A123", null);
        appointment.verifyByReception(UUID.randomUUID(), "Identity and purpose confirmed");
        assertThat(appointment.getStatus()).isEqualTo(AppointmentStatus.PENDING_MANAGER_APPROVAL);
        assertThatThrownBy(() -> appointment.approveByCeo(ceo, "Too early"))
                .isInstanceOf(BusinessException.class);
        appointment.approveByManager(manager, "Department Manager verified the CEO visitor");

        assertThat(appointment.getStatus()).isEqualTo(AppointmentStatus.PENDING_CEO_APPROVAL);
        assertThat(appointment.getHrApprovalActorId()).isNull();
        assertThat(appointment.getManagerApprovalActorId()).isEqualTo(manager);
        assertThat(appointment.getCeoApprovalActorId()).isNull();

        appointment.approveByCeo(ceo, "Final executive approval");
        assertThat(appointment.getStatus()).isEqualTo(AppointmentStatus.APPROVED);
        assertThat(appointment.getCeoApprovalActorId()).isEqualTo(ceo);
    }

    @Test
    void receptionistCannotVerifyBeforeSecurityRecordsArrival() {
        Appointment appointment = appointment();
        appointment.verify();

        assertThatThrownBy(() -> appointment.verifyByReception(UUID.randomUUID(), "Too early"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("PENDING_SECURITY_INTAKE");
    }

    @Test
    void receptionCanForwardOnlyAfterHrApproval() {
        Appointment appointment = appointment(AppointmentType.HR_VISIT);
        UUID reception = UUID.randomUUID();
        appointment.submitByReception(UUID.randomUUID());
        appointment.recordSecurityIntake(UUID.randomUUID(), "Sara Mathew", "HR discussion",
                "AADHAAR", "1234", null);
        appointment.verifyByReception(reception, "Arrival verified");

        assertThatThrownBy(() -> appointment.forwardByReception(reception, "Proceed to HR cabin"))
                .isInstanceOf(BusinessException.class);

        appointment.approveByHr(UUID.randomUUID(), "Approved");
        appointment.forwardByReception(reception, "Proceed to HR cabin");
        assertThat(appointment.getReceptionForwardActorId()).isEqualTo(reception);
        assertThat(appointment.getReceptionForwardedAt()).isNotNull();
    }
}
