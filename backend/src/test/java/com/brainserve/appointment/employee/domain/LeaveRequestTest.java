package com.brainserve.appointment.employee.domain;

import com.brainserve.appointment.shared.application.BusinessException;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

class LeaveRequestTest {
    @Test void pendingRequestCanBeApprovedOnce() {
        var request = new LeaveRequest(UUID.randomUUID(), UUID.randomUUID(), LocalDate.now().plusDays(1),
                LocalDate.now().plusDays(2), "Family commitment");
        request.decide(LeaveRequestStatus.APPROVED, UUID.randomUUID(), "Approved");
        assertThat(request.getStatus()).isEqualTo(LeaveRequestStatus.APPROVED);
        assertThatThrownBy(() -> request.decide(LeaveRequestStatus.REJECTED, UUID.randomUUID(), "Changed"))
                .isInstanceOf(BusinessException.class);
    }

    @Test void invalidDateRangeIsRejected() {
        assertThatThrownBy(() -> new LeaveRequest(UUID.randomUUID(), UUID.randomUUID(), LocalDate.now().plusDays(3),
                LocalDate.now().plusDays(1), "Invalid range")).isInstanceOf(BusinessException.class);
    }
}
