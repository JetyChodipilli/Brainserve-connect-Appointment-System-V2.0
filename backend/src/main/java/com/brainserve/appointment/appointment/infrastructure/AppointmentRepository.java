package com.brainserve.appointment.appointment.infrastructure;

import com.brainserve.appointment.appointment.domain.Appointment;
import com.brainserve.appointment.appointment.domain.AppointmentStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AppointmentRepository extends JpaRepository<Appointment, UUID> {
    Optional<Appointment> findByReferenceNumber(String referenceNumber);
    Optional<Appointment> findByIdempotencyKey(String idempotencyKey);
    boolean existsByHostEmployeeIdAndSlotStartLessThanAndSlotEndGreaterThanAndStatusIn(
            UUID hostEmployeeId,
            Instant candidateSlotEnd,
            Instant candidateSlotStart,
            Collection<AppointmentStatus> statuses
    );
    Page<Appointment> findByHostEmployeeId(UUID hostEmployeeId, Pageable pageable);
    Page<Appointment> findByHostEmployeeIdAndSlotStartGreaterThanEqualAndSlotStartLessThan(
            UUID hostEmployeeId, Instant from, Instant to, Pageable pageable);
    Page<Appointment> findByHostEmployeeIdIn(Collection<UUID> hostEmployeeIds, Pageable pageable);
    Page<Appointment> findByRoutingDepartmentId(UUID departmentId, Pageable pageable);
    Page<Appointment> findByRoutingDepartmentIdAndSlotStartGreaterThanEqualAndSlotStartLessThan(
            UUID departmentId, Instant from, Instant to, Pageable pageable);
    Page<Appointment> findBySlotStartGreaterThanEqualAndSlotStartLessThan(
            Instant from, Instant to, Pageable pageable);
    List<Appointment> findAllBySlotEndLessThanAndStatusIn(Instant before, Collection<AppointmentStatus> statuses);
    long countByStatusIn(Collection<AppointmentStatus> statuses);
    List<Appointment> findAllBySlotStartGreaterThanEqualAndSlotStartLessThanOrderBySlotStartAsc(Instant from, Instant to);
    List<Appointment> findAllByReceptionVerifiedAtGreaterThanEqualAndReceptionVerifiedAtLessThanOrderByReceptionVerifiedAtAsc(
            Instant from, Instant to);
}
