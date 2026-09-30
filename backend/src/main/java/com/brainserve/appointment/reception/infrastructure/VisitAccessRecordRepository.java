package com.brainserve.appointment.reception.infrastructure;

import com.brainserve.appointment.reception.domain.VisitAccessRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Collection;
import java.util.UUID;

public interface VisitAccessRecordRepository extends JpaRepository<VisitAccessRecord, UUID> {
    boolean existsByAppointmentId(UUID appointmentId);
    List<VisitAccessRecord> findByCheckedOutAtIsNullOrderByCheckedInAtAsc();
    List<VisitAccessRecord> findAllByAppointmentIdIn(Collection<UUID> appointmentIds);
    long countByCheckedOutAtIsNull();
}
