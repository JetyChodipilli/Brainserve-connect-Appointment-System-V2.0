package com.brainserve.appointment.visitor.infrastructure;

import com.brainserve.appointment.visitor.domain.Visitor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;
import java.time.Instant;

public interface VisitorRepository extends JpaRepository<Visitor, UUID> {
    Optional<Visitor> findByIdempotencyKey(String idempotencyKey);
    Optional<Visitor> findFirstByEmailIgnoreCaseAndPhone(String email, String phone);
    Page<Visitor> findByNameContainingIgnoreCaseOrEmailContainingIgnoreCase(String name, String email, Pageable pageable);
    long deleteByConsentedAtBeforeAndRestrictedFalse(Instant cutoff);
}
