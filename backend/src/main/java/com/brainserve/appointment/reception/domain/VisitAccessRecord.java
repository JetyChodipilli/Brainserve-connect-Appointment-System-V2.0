package com.brainserve.appointment.reception.domain;

import com.brainserve.appointment.shared.application.BusinessException;
import com.brainserve.appointment.shared.domain.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "visit_access_record")
public class VisitAccessRecord extends AuditableEntity {
    @Column(name = "appointment_id", nullable = false, unique = true)
    private UUID appointmentId;
    @Column(name = "visitor_name", nullable = false, length = 170)
    private String visitorName;
    @Column(name = "badge_number", nullable = false, length = 30)
    private String badgeNumber;
    @Column(name = "checked_in_at", nullable = false)
    private Instant checkedInAt;
    @Column(name = "checked_out_at")
    private Instant checkedOutAt;
    @Column(name = "processed_by", nullable = false, length = 120)
    private String processedBy;

    protected VisitAccessRecord() {}
    public VisitAccessRecord(UUID appointmentId, String visitorName, String badgeNumber, String processedBy) {
        this.appointmentId = appointmentId; this.visitorName = visitorName; this.badgeNumber = badgeNumber;
        this.checkedInAt = Instant.now(); this.processedBy = processedBy;
    }
    public void checkOut() {
        if (checkedOutAt != null) throw new BusinessException("ALREADY_CHECKED_OUT", "Visitor has already checked out", HttpStatus.CONFLICT);
        checkedOutAt = Instant.now();
    }
    public UUID getAppointmentId() { return appointmentId; }
    public String getVisitorName() { return visitorName; }
    public String getBadgeNumber() { return badgeNumber; }
    public Instant getCheckedInAt() { return checkedInAt; }
    public Instant getCheckedOutAt() { return checkedOutAt; }
    public String getProcessedBy() { return processedBy; }
}
