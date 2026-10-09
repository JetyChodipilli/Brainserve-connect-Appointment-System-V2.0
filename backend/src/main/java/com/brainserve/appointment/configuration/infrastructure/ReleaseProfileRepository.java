package com.brainserve.appointment.configuration.infrastructure;

import com.brainserve.appointment.configuration.domain.ReleaseProfile;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Optional;
import java.util.UUID;

public interface ReleaseProfileRepository extends JpaRepository<ReleaseProfile, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from ReleaseProfile r where r.id = :id")
    Optional<ReleaseProfile> findForUpdate(@Param("id") UUID id);
}
