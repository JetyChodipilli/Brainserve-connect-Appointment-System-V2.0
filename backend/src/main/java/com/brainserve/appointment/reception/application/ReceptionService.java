package com.brainserve.appointment.reception.application;

import com.brainserve.appointment.appointment.api.AppointmentAccess;
import com.brainserve.appointment.audit.api.AuditService;
import com.brainserve.appointment.reception.domain.VisitAccessRecord;
import com.brainserve.appointment.reception.api.ReceptionStatistics;
import com.brainserve.appointment.reception.api.ReceptionRecords;
import com.brainserve.appointment.reception.infrastructure.VisitAccessRecordRepository;
import com.brainserve.appointment.shared.application.BusinessException;
import jakarta.persistence.EntityManager;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class ReceptionService implements ReceptionStatistics, ReceptionRecords {
    private final VisitAccessRecordRepository records;
    private final AppointmentAccess appointments;
    private final EntityManager entityManager;
    private final AuditService audit;
    public ReceptionService(VisitAccessRecordRepository records, AppointmentAccess appointments, EntityManager entityManager, AuditService audit) {
        this.records = records; this.appointments = appointments; this.entityManager = entityManager; this.audit = audit;
    }

    @Transactional
    public VisitAccessRecord checkIn(UUID appointmentId, String actor) {
        return checkIn(appointments.requireForCheckIn(appointmentId), actor);
    }

    @Transactional
    public VisitAccessRecord checkInByReference(String referenceNumber, String actor) {
        return checkIn(appointments.requireForCheckInByReference(referenceNumber), actor);
    }

    private VisitAccessRecord checkIn(AppointmentAccess.AccessAppointment appointment, String actor) {
        UUID appointmentId = appointment.id();
        if (records.existsByAppointmentId(appointmentId)) throw new BusinessException("ALREADY_CHECKED_IN", "This appointment has already been checked in", HttpStatus.CONFLICT);
        if (!appointment.approved()) throw new BusinessException("APPOINTMENT_NOT_READY_FOR_CHECK_IN",
                "The appointment must be fully approved and routed by Reception before check-in",
                HttpStatus.UNPROCESSABLE_ENTITY);
        long badge = ((Number) entityManager.createNativeQuery("select nextval('visitor_badge_seq')").getSingleResult()).longValue();
        appointments.markCheckedIn(appointmentId);
        VisitAccessRecord record = records.save(new VisitAccessRecord(appointmentId, appointment.visitorName(), "B-" + String.format("%03d", badge), actor));
        audit.record("VISITOR_CHECK_IN", "APPOINTMENT", appointmentId.toString(), "{\"badge\":\"" + record.getBadgeNumber() + "\"}");
        return record;
    }

    @Transactional
    public VisitAccessRecord checkOut(UUID recordId) {
        VisitAccessRecord record = records.findById(recordId).orElseThrow(() -> new BusinessException("ACCESS_RECORD_NOT_FOUND", "Access record was not found", HttpStatus.NOT_FOUND));
        record.checkOut();
        appointments.markCheckedOut(record.getAppointmentId());
        audit.record("VISITOR_CHECK_OUT", "ACCESS_RECORD", recordId.toString(), "{\"badgeReleased\":true}");
        return record;
    }

    @Transactional(readOnly = true)
    public List<VisitAccessRecord> inside() { return records.findByCheckedOutAtIsNullOrderByCheckedInAtAsc(); }

    @Override
    @Transactional(readOnly = true)
    public long visitorsInside() { return records.countByCheckedOutAtIsNull(); }

    @Override
    @Transactional(readOnly = true)
    public List<AccessRecord> forAppointments(java.util.Collection<UUID> appointmentIds) {
        if (appointmentIds.isEmpty()) return List.of();
        return records.findAllByAppointmentIdIn(appointmentIds).stream()
                .map(value -> new AccessRecord(value.getAppointmentId(), value.getBadgeNumber(),
                        value.getCheckedInAt(), value.getCheckedOutAt(), value.getProcessedBy()))
                .toList();
    }
}
