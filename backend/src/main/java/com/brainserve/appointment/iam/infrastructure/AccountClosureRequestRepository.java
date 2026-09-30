package com.brainserve.appointment.iam.infrastructure;

import com.brainserve.appointment.iam.domain.AccountClosureRequest;
import com.brainserve.appointment.iam.domain.AccountClosureStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import jakarta.persistence.LockModeType;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface AccountClosureRequestRepository extends JpaRepository<AccountClosureRequest, UUID> {
    boolean existsByTargetUserIdAndStatusIn(UUID targetUserId, Collection<AccountClosureStatus> statuses);
    List<AccountClosureRequest> findTop100ByRequesterUserIdOrderByRequestedAtDesc(UUID requesterUserId);
    List<AccountClosureRequest> findTop200ByStatusInOrderByRequestedAtAsc(Collection<AccountClosureStatus> statuses);
    List<AccountClosureRequest> findTop500ByOrderByRequestedAtDesc();
    List<AccountClosureRequest> findAllByStatusAndRequestedEffectiveDateLessThanEqual(
            AccountClosureStatus status, LocalDate requestedEffectiveDate);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select request from AccountClosureRequest request where request.id = :id")
    java.util.Optional<AccountClosureRequest> findForUpdateById(UUID id);
}
