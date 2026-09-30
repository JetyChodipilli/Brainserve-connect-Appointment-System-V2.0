package com.brainserve.appointment.rolechange.domain;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RoleDepartmentChangeRequestTest {
    @Test
    void approveCapturesResolutionAndDecisionAudit() {
        UUID actor = UUID.randomUUID();
        RoleDepartmentChangeRequest request = request();

        request.approve(actor, RoleDepartmentChangeRequest.Resolution.SWAP, "Approved by CEO");

        assertThat(request.getStatus()).isEqualTo(RoleDepartmentChangeRequest.Status.APPROVED);
        assertThat(request.getResolution()).isEqualTo(RoleDepartmentChangeRequest.Resolution.SWAP);
        assertThat(request.getDecidedByUserId()).isEqualTo(actor);
        assertThat(request.getDecidedAt()).isNotNull();
        assertThat(request.getDecisionNote()).isEqualTo("Approved by CEO");
    }

    @Test
    void decidedRequestCannotTransitionTwice() {
        RoleDepartmentChangeRequest request = request();
        request.reject(UUID.randomUUID(), "Department capacity conflict");

        assertThatThrownBy(() -> request.approve(UUID.randomUUID(),
                RoleDepartmentChangeRequest.Resolution.MOVE, null))
                .isInstanceOf(IllegalStateException.class);
    }

    private RoleDepartmentChangeRequest request() {
        return new RoleDepartmentChangeRequest(UUID.randomUUID(), UUID.randomUUID(),
                RoleDepartmentChangeRequest.RoleType.HR_ADMIN, UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), "Moving to support a new business unit",
                "+91 99999 99999", "HR Business Partner", LocalDate.now());
    }
}
