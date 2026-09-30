package com.brainserve.appointment.notification.infrastructure;

import com.brainserve.appointment.notification.domain.OutboxMessage;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public interface OutboxRepository extends JpaRepository<OutboxMessage, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from OutboxMessage m where m.status in :statuses and m.nextAttemptAt <= :now order by m.nextAttemptAt")
    List<OutboxMessage> lockReady(Set<OutboxMessage.Status> statuses, Instant now, Pageable pageable);
}
