package com.brainserve.appointment.employee.domain;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EmployeeTerminationRequestTest {
    @Test
    void remainsPendingUntilCeoDecides() {
        var request = new EmployeeTerminationRequest(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "Documented policy breach", LocalDate.now());
        assertThat(request.getStatus()).isEqualTo(EmployeeTerminationRequest.Status.PENDING_CEO_APPROVAL);

        UUID ceo = UUID.randomUUID();
        request.approve(ceo, "Evidence verified");

        assertThat(request.getStatus()).isEqualTo(EmployeeTerminationRequest.Status.APPROVED);
        assertThat(request.getDecidedByCeoUserId()).isEqualTo(ceo);
        assertThatThrownBy(() -> request.reject(ceo, "Cannot decide twice"))
                .isInstanceOf(IllegalStateException.class);
    }
}
