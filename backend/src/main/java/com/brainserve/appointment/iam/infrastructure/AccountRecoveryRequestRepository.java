package com.brainserve.appointment.iam.infrastructure;

import com.brainserve.appointment.iam.domain.AccountRecoveryRequest;
import com.brainserve.appointment.iam.domain.AccountRecoveryStatus;
import com.brainserve.appointment.iam.domain.AccountRecoveryType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AccountRecoveryRequestRepository extends JpaRepository<AccountRecoveryRequest, UUID> {
    @EntityGraph(attributePaths = {"user", "user.roles"})
    List<AccountRecoveryRequest> findByStatusOrderByCreatedAtAsc(AccountRecoveryStatus status);
    @EntityGraph(attributePaths = {"user", "user.roles"})
    @Query("select request from AccountRecoveryRequest request where request.id = :id")
    Optional<AccountRecoveryRequest> findDetailedById(@Param("id") UUID id);
    @EntityGraph(attributePaths = {"user", "user.roles"})
    Optional<AccountRecoveryRequest> findByCodeHash(String codeHash);
    boolean existsByUser_IdAndTypeAndStatus(UUID userId, AccountRecoveryType type, AccountRecoveryStatus status);
}
