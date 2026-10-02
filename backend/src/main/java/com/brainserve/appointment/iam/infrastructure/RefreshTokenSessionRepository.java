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
import java.util.List;
import org.springframework.data.domain.Pageable;
import java.util.UUID;

public interface RefreshTokenSessionRepository extends JpaRepository<RefreshTokenSession, UUID> {
    Optional<RefreshTokenSession> findByTokenHash(String tokenHash);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from RefreshTokenSession s where s.tokenHash = :tokenHash")
    Optional<RefreshTokenSession> findByTokenHashForUpdate(@Param("tokenHash") String tokenHash);
    @Query("select s from RefreshTokenSession s where s.userId = :userId and s.familyId = :familyId and s.revokedAt is null and s.expiresAt > :now")
    Optional<RefreshTokenSession> findActiveFamily(UUID userId, UUID familyId, Instant now);
    @Query("select s from RefreshTokenSession s where s.userId = :userId and s.revokedAt is null and s.expiresAt > :now order by s.sessionStartedAt desc, s.id")
    List<RefreshTokenSession> findActiveForUser(UUID userId, Instant now, Pageable page);
    @Query(value = "select id from iam_user_account where id = :userId for update", nativeQuery = true)
    UUID lockSessionOwner(UUID userId);
    @Query(value = "select id from iam_user_account where id = (select user_id from iam_refresh_token_session where family_id = :familyId limit 1) for update", nativeQuery = true)
    UUID lockFamilyOwner(UUID familyId);
    @Modifying(flushAutomatically = true)
    @Query("update RefreshTokenSession s set s.revokedAt = :now where s.familyId = :familyId and s.revokedAt is null")
    int revokeFamilyLocked(UUID familyId, Instant now);
    @Modifying(flushAutomatically = true)
    @Query("update RefreshTokenSession s set s.revokedAt = :now where s.userId = :userId and s.revokedAt is null")
    int revokeAllForUserLocked(UUID userId, Instant now);
    // Use the same owner lock as rotation, including callers in account administration.
    default int revokeFamily(UUID familyId, Instant now) {
        lockFamilyOwner(familyId);
        return revokeFamilyLocked(familyId, now);
    }
    default int revokeAllForUser(UUID userId, Instant now) {
        lockSessionOwner(userId);
        return revokeAllForUserLocked(userId, now);
    }
    @Modifying
    @Query("delete from RefreshTokenSession s where s.expiresAt < :cutoff")
    int deleteExpiredBefore(Instant cutoff);
}
