package com.brainserve.appointment.iam.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccountClosureRequestTest {
    @Test
    void businessAndSystemAdminApprovalsProduceAnImmutableArchiveDecision() {
        UUID target = UUID.randomUUID();
        UUID requester = UUID.randomUUID();
        UUID businessApprover = UUID.randomUUID();
        UUID systemAdmin = UUID.randomUUID();
        UUID replacement = UUID.randomUUID();
        var request = new AccountClosureRequest(target, SystemRole.ROLE_HR_ADMIN, UUID.randomUUID(), requester,
                AccountClosureRequest.Origin.SELF_SERVICE, "Leaving the organization", LocalDate.now(), replacement);

        request.businessApprove(businessApprover, "Department handover reviewed");
        assertThat(request.getStatus()).isEqualTo(AccountClosureStatus.BUSINESS_APPROVED);
        assertThat(request.getBusinessApproverUserId()).isEqualTo(businessApprover);
        request.forwardToSystemAdmin();
        request.systemAdminApprove(systemAdmin, "Compliance review complete");
        request.archive(Instant.now());

        assertThat(request.getStatus()).isEqualTo(AccountClosureStatus.ARCHIVED);
        assertThat(request.getSystemAdminApproverUserId()).isEqualTo(systemAdmin);
        assertThat(request.getArchivedAt()).isNotNull();
        assertThatThrownBy(() -> request.archive(Instant.now()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectionRecordsTheCorrectApprovalStageAndCannotBeRepeated() {
        UUID ceo = UUID.randomUUID();
        var request = new AccountClosureRequest(UUID.randomUUID(), SystemRole.ROLE_HR_ADMIN, UUID.randomUUID(),
                UUID.randomUUID(), AccountClosureRequest.Origin.SELF_SERVICE, "Role owner has resigned",
                LocalDate.now(), UUID.randomUUID());

        request.rejectByBusiness(ceo, "Handover is incomplete");

        assertThat(request.getStatus()).isEqualTo(AccountClosureStatus.REJECTED);
        assertThat(request.getBusinessApproverUserId()).isEqualTo(ceo);
        assertThat(request.getBusinessApprovedAt()).isNull();
        assertThatThrownBy(() -> request.rejectBySystemAdmin(UUID.randomUUID(), "Cannot decide twice"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void targetCannotBeItsOwnReplacement() {
        UUID target = UUID.randomUUID();
        assertThatThrownBy(() -> new AccountClosureRequest(target, SystemRole.ROLE_CEO, null,
                target, AccountClosureRequest.Origin.SELF_SERVICE, "Executive transition", LocalDate.now(), target))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must differ");
    }
}
