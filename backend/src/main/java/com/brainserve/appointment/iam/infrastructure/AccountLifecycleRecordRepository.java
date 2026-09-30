package com.brainserve.appointment.iam.infrastructure;

import com.brainserve.appointment.iam.domain.AccountLifecycleRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AccountLifecycleRecordRepository extends JpaRepository<AccountLifecycleRecord, UUID> {
    List<AccountLifecycleRecord> findAllByClosureRequestIdOrderByOccurredAtAsc(UUID closureRequestId);
}
