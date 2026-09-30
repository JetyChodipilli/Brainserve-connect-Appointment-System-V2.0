package com.brainserve.appointment.reporting.infrastructure;

import com.brainserve.appointment.reporting.domain.ReportExportJob;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import jakarta.persistence.LockModeType;
import java.util.Optional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface ReportExportJobRepository extends JpaRepository<ReportExportJob, UUID> {
    List<ReportExportJob> findTop100ByRequestedByUserIdOrderByCreatedAtDesc(UUID requestedByUserId);
    List<ReportExportJob> findTop100ByOrderByCreatedAtDesc();
    List<ReportExportJob> findTop100ByStatusAndExpiresAtBeforeOrderByExpiresAtAsc(
            ReportExportJob.Status status, Instant expiresAt);
    List<ReportExportJob> findTop100ByStatusAndStartedAtBeforeOrderByStartedAtAsc(
            ReportExportJob.Status status, Instant startedAt);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select job from ReportExportJob job where job.id = :id")
    Optional<ReportExportJob> findByIdForUpdate(UUID id);
}
