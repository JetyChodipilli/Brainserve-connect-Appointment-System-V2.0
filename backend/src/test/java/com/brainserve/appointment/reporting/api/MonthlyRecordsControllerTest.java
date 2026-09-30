package com.brainserve.appointment.reporting.api;

import com.brainserve.appointment.appointment.api.AppointmentRecords;
import com.brainserve.appointment.employee.api.EmployeeRecords;
import com.brainserve.appointment.reception.api.ReceptionRecords;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MonthlyRecordsControllerTest {

    private final AppointmentRecords appointments =
            mock(AppointmentRecords.class);

    private final EmployeeRecords employees =
            mock(EmployeeRecords.class);

    private final ReceptionRecords reception =
            mock(ReceptionRecords.class);

    private final MonthlyRecordsController controller =
            new MonthlyRecordsController(
                    appointments,
                    employees,
                    reception,
                    "Asia/Kolkata"
            );

    @Test
    @SuppressWarnings("removal")
    void monthlyRegisterContainsOnlyReceptionProcessedArrivalsWithTheirFullTrail() {

        UUID appointmentId = UUID.randomUUID();

        UUID hrEmployeeId = UUID.randomUUID();
        UUID routingDepartmentId = UUID.randomUUID();
        UUID requestedEmployeeId = hrEmployeeId;

        UUID securityUserId = UUID.randomUUID();
        UUID receptionUserId = UUID.randomUUID();

        UUID hrApprovalUserId = UUID.randomUUID();
        UUID teamLeadApprovalUserId = UUID.randomUUID();
        UUID managerApprovalUserId = UUID.randomUUID();
        UUID ceoApprovalUserId = UUID.randomUUID();

        UUID receptionForwardUserId = UUID.randomUUID();

        Instant slotStart =
                Instant.parse("2026-07-14T05:00:00Z");

        Instant slotEnd =
                Instant.parse("2026-07-14T05:30:00Z");

        Instant securityAt =
                Instant.parse("2026-07-14T04:10:00Z");

        Instant receptionAt =
                Instant.parse("2026-07-14T04:15:00Z");

        Instant hrDecisionAt =
                Instant.parse("2026-07-14T04:20:00Z");

        Instant teamLeadDecisionAt =
                Instant.parse("2026-07-14T04:25:00Z");

        Instant managerDecisionAt =
                Instant.parse("2026-07-14T04:28:00Z");

        Instant ceoDecisionAt =
                Instant.parse("2026-07-14T04:30:00Z");

        Instant receptionForwardedAt =
                Instant.parse("2026-07-14T04:35:00Z");

        Instant checkedInAt =
                Instant.parse("2026-07-14T04:40:00Z");

        AppointmentRecords.VisitRecord visit =
                new AppointmentRecords.VisitRecord(
                        // 1–8: Basic visitor information
                        appointmentId,
                        "BSA-TEST-2026",
                        "HR_VISIT",
                        "APPROVED",
                        "Booked Visitor",
                        "visitor@example.com",
                        "+919999999999",
                        "Visitor Co",

                        // 9–11: Host and routing information
                        hrEmployeeId,
                        routingDepartmentId,
                        requestedEmployeeId,

                        // 12–14: Appointment schedule
                        slotStart,
                        slotEnd,
                        "Original purpose",

                        // 15–21: Security intake
                        securityUserId,
                        securityAt,
                        "Arrived Visitor",
                        "Confirmed HR meeting",
                        "AADHAAR",
                        "1234",
                        "Identity matched",

                        // 22–24: Reception verification
                        receptionUserId,
                        receptionAt,
                        "Reception verified identity",

                        // 25–26: HR approval
                        hrApprovalUserId,
                        hrDecisionAt,

                        // 27–28: Team Lead approval
                        teamLeadApprovalUserId,
                        teamLeadDecisionAt,

                        // 29–30: Manager approval
                        managerApprovalUserId,
                        managerDecisionAt,

                        // 31–32: CEO approval
                        ceoApprovalUserId,
                        ceoDecisionAt,

                        // 33–35: Reception forwarding
                        receptionForwardUserId,
                        receptionForwardedAt,
                        "Sent to HR cabin"
                );

        when(
                appointments.receptionVisitsBetween(
                        any(),
                        any()
                )
        ).thenReturn(List.of(visit));

        when(employees.allEmployees()).thenReturn(
                List.of(
                        new EmployeeRecords.EmployeeRecord(
                                hrEmployeeId,
                                "BS-HR-001",
                                "Requested HR",
                                "hr@brainserve.in",
                                "HR Admin",
                                "ACTIVE",
                                LocalDate.of(2025, 1, 1),
                                null
                        )
                )
        );

        when(
                employees.leavesOverlapping(
                        any(),
                        any()
                )
        ).thenReturn(List.of());

        when(
                reception.forAppointments(
                        List.of(appointmentId)
                )
        ).thenReturn(
                List.of(
                        new ReceptionRecords.AccessRecord(
                                appointmentId,
                                "B-101",
                                checkedInAt,
                                null,
                                "Reception Desk"
                        )
                )
        );

        MonthlyRecordsController.MonthlyRecords result =
                controller.monthly(2026, 7);

        assertThat(result.visitorCount())
                .isEqualTo(1);

        assertThat(result.visitors())
                .singleElement()
                .satisfies(record -> {
                    assertThat(record.id())
                            .isEqualTo(appointmentId);

                    assertThat(record.referenceNumber())
                            .isEqualTo("BSA-TEST-2026");

                    assertThat(record.visitorName())
                            .isEqualTo("Arrived Visitor");

                    assertThat(record.purpose())
                            .isEqualTo("Confirmed HR meeting");

                    assertThat(record.hostEmployeeId())
                            .isEqualTo(hrEmployeeId);

                    assertThat(record.hostName())
                            .isEqualTo("Requested HR");

                    assertThat(record.routingDepartmentId())
                            .isEqualTo(routingDepartmentId);

                    assertThat(record.requestedEmployeeId())
                            .isEqualTo(requestedEmployeeId);

                    assertThat(record.requestedEmployeeName())
                            .isEqualTo("Requested HR");

                    assertThat(record.securityActorId())
                            .isEqualTo(securityUserId);

                    assertThat(record.securityIntakeAt())
                            .isEqualTo(securityAt);

                    assertThat(record.receptionActorId())
                            .isEqualTo(receptionUserId);

                    assertThat(record.receptionVerifiedAt())
                            .isEqualTo(receptionAt);

                    assertThat(record.hrActorId())
                            .isEqualTo(hrApprovalUserId);

                    assertThat(record.hrDecisionAt())
                            .isEqualTo(hrDecisionAt);

                    assertThat(record.teamLeadActorId())
                            .isEqualTo(teamLeadApprovalUserId);

                    assertThat(record.teamLeadDecisionAt())
                            .isEqualTo(teamLeadDecisionAt);

                    assertThat(record.managerActorId())
                            .isEqualTo(managerApprovalUserId);

                    assertThat(record.managerDecisionAt())
                            .isEqualTo(managerDecisionAt);

                    assertThat(record.ceoActorId())
                            .isEqualTo(ceoApprovalUserId);

                    assertThat(record.ceoDecisionAt())
                            .isEqualTo(ceoDecisionAt);

                    assertThat(record.receptionForwardActorId())
                            .isEqualTo(receptionForwardUserId);

                    assertThat(record.receptionForwardedAt())
                            .isEqualTo(receptionForwardedAt);

                    assertThat(record.receptionForwardRemarks())
                            .isEqualTo("Sent to HR cabin");

                    assertThat(record.badgeNumber())
                            .isEqualTo("B-101");

                    assertThat(record.checkedInAt())
                            .isEqualTo(checkedInAt);

                    assertThat(record.processedBy())
                            .isEqualTo("Reception Desk");
                });

        verify(appointments).receptionVisitsBetween(
                Instant.parse("2026-06-30T18:30:00Z"),
                Instant.parse("2026-07-31T18:30:00Z")
        );
    }
}