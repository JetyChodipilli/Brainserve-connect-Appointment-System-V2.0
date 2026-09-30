package com.brainserve.appointment.iam.infrastructure;

import com.brainserve.appointment.iam.domain.RefreshTokenSession;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenSessionRepository extends JpaRepository<RefreshTokenSession, UUID> {
    Optional<RefreshTokenSession> findByTokenHash(String tokenHash);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from RefreshTokenSession s where s.tokenHash = :tokenHash")
    Optional<RefreshTokenSession> findByTokenHashForUpdate(@Param("tokenHash") String tokenHash);
    @Modifying
    @Query("update RefreshTokenSession s set s.revokedAt = :now where s.familyId = :familyId and s.revokedAt is null")
    int revokeFamily(UUID familyId, Instant now);
    @Modifying
    @Query("update RefreshTokenSession s set s.revokedAt = :now where s.userId = :userId and s.revokedAt is null")
    int revokeAllForUser(UUID userId, Instant now);
    @Modifying
    @Query("delete from RefreshTokenSession s where s.expiresAt < :cutoff")
    int deleteExpiredBefore(Instant cutoff);
}
