package com.brainserve.appointment.appointment.domain;

import com.brainserve.appointment.shared.application.BusinessException;
import com.brainserve.appointment.shared.domain.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "appointment", uniqueConstraints = {
        @UniqueConstraint(name = "uk_appointment_reference", columnNames = "reference_number"),
        @UniqueConstraint(name = "uk_appointment_idempotency", columnNames = "idempotency_key")
})
public class Appointment extends AuditableEntity {
    private static final Map<AppointmentStatus, Set<AppointmentStatus>> TRANSITIONS = transitions();

    @Column(name = "reference_number", nullable = false, updatable = false, length = 40)
    private String referenceNumber;
    @Column(name = "idempotency_key", nullable = false, updatable = false, length = 100)
    private String idempotencyKey;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private AppointmentType type;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private AppointmentStatus status = AppointmentStatus.PENDING_VERIFICATION;
    @Column(name = "visitor_name", nullable = false, length = 170)
    private String visitorName;
    @Column(name = "visitor_email", nullable = false, length = 180)
    private String visitorEmail;
    @Column(name = "visitor_phone", nullable = false, length = 30)
    private String visitorPhone;
    @Column(name = "visitor_company", length = 160)
    private String visitorCompany;
    @Column(name = "host_employee_id", nullable = false)
    private UUID hostEmployeeId;
    @Column(name = "routing_department_id", nullable = false)
    private UUID routingDepartmentId;
    @Column(name = "requested_employee_id")
    private UUID requestedEmployeeId;
    @Column(name = "slot_start", nullable = false)
    private Instant slotStart;
    @Column(name = "slot_end", nullable = false)
    private Instant slotEnd;
    @Column(nullable = false, length = 1000)
    private String purpose;
    @Column(name = "approval_actor_id")
    private UUID approvalActorId;
    @Column(name = "decision_at")
    private Instant decisionAt;
    @Column(name = "decision_remarks", length = 500)
    private String decisionRemarks;
    @Column(name = "registered_by_user_id")
    private UUID registeredByUserId;
    @Column(name = "security_intake_actor_id")
    private UUID securityIntakeActorId;
    @Column(name = "security_intake_at")
    private Instant securityIntakeAt;
    @Column(name = "arrival_visitor_name", length = 170)
    private String arrivalVisitorName;
    @Column(name = "arrival_purpose", length = 1000)
    private String arrivalPurpose;
    @Column(name = "identity_document_type", length = 40)
    private String identityDocumentType;
    @Column(name = "identity_document_last_four", length = 4)
    private String identityDocumentLastFour;
    @Column(name = "security_notes", length = 500)
    private String securityNotes;
    @Column(name = "reception_verification_actor_id")
    private UUID receptionVerificationActorId;
    @Column(name = "reception_verified_at")
    private Instant receptionVerifiedAt;
    @Column(name = "reception_verification_remarks", length = 500)
    private String receptionVerificationRemarks;
    @Column(name = "hr_approval_actor_id")
    private UUID hrApprovalActorId;
    @Column(name = "hr_decision_at")
    private Instant hrDecisionAt;
    @Column(name = "hr_decision_remarks", length = 500)
    private String hrDecisionRemarks;
    @Column(name = "team_lead_approval_actor_id")
    private UUID teamLeadApprovalActorId;
    @Column(name = "team_lead_decision_at")
    private Instant teamLeadDecisionAt;
    @Column(name = "team_lead_decision_remarks", length = 500)
    private String teamLeadDecisionRemarks;
    @Column(name = "manager_approval_actor_id")
    private UUID managerApprovalActorId;
    @Column(name = "manager_decision_at")
    private Instant managerDecisionAt;
    @Column(name = "manager_decision_remarks", length = 500)
    private String managerDecisionRemarks;
    @Column(name = "ceo_approval_actor_id")
    private UUID ceoApprovalActorId;
    @Column(name = "ceo_decision_at")
    private Instant ceoDecisionAt;
    @Column(name = "ceo_decision_remarks", length = 500)
    private String ceoDecisionRemarks;
    @Column(name = "reception_forward_actor_id")
    private UUID receptionForwardActorId;
    @Column(name = "reception_forwarded_at")
    private Instant receptionForwardedAt;
    @Column(name = "reception_forward_remarks", length = 500)
    private String receptionForwardRemarks;

    protected Appointment() {}
    public Appointment(String referenceNumber, String idempotencyKey, AppointmentType type, String visitorName,
                       String visitorEmail, String visitorPhone, String visitorCompany, UUID hostEmployeeId,
                       Instant slotStart, Instant slotEnd, String purpose) {
        this(referenceNumber, idempotencyKey, type, visitorName, visitorEmail, visitorPhone, visitorCompany,
                hostEmployeeId, null, null, slotStart, slotEnd, purpose);
    }
    public Appointment(String referenceNumber, String idempotencyKey, AppointmentType type, String visitorName,
                       String visitorEmail, String visitorPhone, String visitorCompany, UUID hostEmployeeId,
                       UUID routingDepartmentId, UUID requestedEmployeeId,
                       Instant slotStart, Instant slotEnd, String purpose) {
        if (!slotEnd.isAfter(slotStart)) throw new BusinessException("INVALID_APPOINTMENT_SLOT", "Slot end must be after slot start", HttpStatus.UNPROCESSABLE_ENTITY);
        this.referenceNumber = referenceNumber; this.idempotencyKey = idempotencyKey; this.type = type;
        this.visitorName = visitorName.trim(); this.visitorEmail = visitorEmail.trim().toLowerCase();
        this.visitorPhone = visitorPhone.trim(); this.visitorCompany = visitorCompany;
        this.hostEmployeeId = hostEmployeeId; this.routingDepartmentId = routingDepartmentId;
        this.requestedEmployeeId = requestedEmployeeId;
        this.slotStart = slotStart; this.slotEnd = slotEnd; this.purpose = purpose.trim();
    }

    public void verify() {
        transitionTo(AppointmentStatus.PENDING_SECURITY_INTAKE);
    }
    public void submitByReception(UUID actorUserId) {
        transitionTo(AppointmentStatus.PENDING_SECURITY_INTAKE);
        registeredByUserId = actorUserId;
    }
    public void recordSecurityIntake(UUID actorUserId, String actualVisitorName, String actualPurpose,
                                     String documentType, String documentLastFour, String notes) {
        if (status != AppointmentStatus.PENDING_SECURITY_INTAKE) invalidApproval("SECURITY_INTAKE");
        securityIntakeActorId = actorUserId;
        securityIntakeAt = Instant.now();
        arrivalVisitorName = actualVisitorName.trim();
        arrivalPurpose = actualPurpose.trim();
        identityDocumentType = normalize(documentType);
        identityDocumentLastFour = normalize(documentLastFour);
        securityNotes = normalize(notes);
        transitionTo(AppointmentStatus.PENDING_RECEPTION_VERIFICATION);
    }
    public void verifyByReception(UUID actorUserId, String remarks) {
        verifyByReception(actorUserId, remarks, type == AppointmentType.CEO_VISIT);
    }
    public void verifyByReception(UUID actorUserId, String remarks, boolean routeToManager) {
        if (status != AppointmentStatus.PENDING_RECEPTION_VERIFICATION) invalidApproval("RECEPTION");
        receptionVerificationActorId = actorUserId;
        receptionVerifiedAt = Instant.now();
        receptionVerificationRemarks = normalize(remarks);
        transitionTo(routeToManager ? AppointmentStatus.PENDING_MANAGER_APPROVAL
                : AppointmentStatus.PENDING_HR_APPROVAL);
    }
    public void rejectByReception(UUID actorUserId, String remarks) {
        if (status != AppointmentStatus.PENDING_RECEPTION_VERIFICATION) invalidApproval("RECEPTION");
        receptionVerificationActorId = actorUserId;
        receptionVerifiedAt = Instant.now();
        receptionVerificationRemarks = normalize(remarks);
        transitionTo(AppointmentStatus.REJECTED);
        recordDecision(actorUserId, remarks);
    }
    public void approveByHr(UUID actorUserId, String remarks) { approveByHr(actorUserId, remarks, false, false); }
    public void approveByHr(UUID actorUserId, String remarks, boolean requiresTeamLeadApproval) {
        approveByHr(actorUserId, remarks, requiresTeamLeadApproval, false);
    }
    public void approveByHr(UUID actorUserId, String remarks, boolean requiresTeamLeadApproval,
                            boolean requiresManagerApproval) {
        if (status != AppointmentStatus.PENDING_HR_APPROVAL) invalidApproval("HR");
        hrApprovalActorId = actorUserId;
        hrDecisionAt = Instant.now();
        hrDecisionRemarks = normalize(remarks);
        if (type == AppointmentType.CEO_VISIT || requiresManagerApproval) {
            transitionTo(AppointmentStatus.PENDING_MANAGER_APPROVAL);
        } else if (requiresTeamLeadApproval) {
            transitionTo(AppointmentStatus.PENDING_TEAM_LEAD_APPROVAL);
        } else {
            transitionTo(AppointmentStatus.APPROVED);
            recordDecision(actorUserId, remarks);
        }
    }
    public void rejectByHr(UUID actorUserId, String remarks) {
        if (status != AppointmentStatus.PENDING_HR_APPROVAL) invalidApproval("HR");
        hrApprovalActorId = actorUserId;
        hrDecisionAt = Instant.now();
        hrDecisionRemarks = remarks;
        transitionTo(AppointmentStatus.REJECTED);
        recordDecision(actorUserId, remarks);
    }
    public void approveByTeamLead(UUID actorUserId, String remarks) {
        if (status != AppointmentStatus.PENDING_TEAM_LEAD_APPROVAL) invalidApproval("TEAM_LEAD");
        teamLeadApprovalActorId = actorUserId; teamLeadDecisionAt = Instant.now();
        teamLeadDecisionRemarks = normalize(remarks);
        transitionTo(AppointmentStatus.APPROVED); recordDecision(actorUserId, remarks);
    }
    public void rejectByTeamLead(UUID actorUserId, String remarks) {
        if (status != AppointmentStatus.PENDING_TEAM_LEAD_APPROVAL) invalidApproval("TEAM_LEAD");
        teamLeadApprovalActorId = actorUserId; teamLeadDecisionAt = Instant.now();
        teamLeadDecisionRemarks = normalize(remarks);
        transitionTo(AppointmentStatus.REJECTED); recordDecision(actorUserId, remarks);
    }
    public void approveByManager(UUID actorUserId, String remarks) {
        if (status != AppointmentStatus.PENDING_MANAGER_APPROVAL) invalidApproval("MANAGER");
        managerApprovalActorId = actorUserId;
        managerDecisionAt = Instant.now();
        managerDecisionRemarks = normalize(remarks);
        if (type == AppointmentType.CEO_VISIT || type == AppointmentType.EMERGENCY) {
            transitionTo(AppointmentStatus.PENDING_CEO_APPROVAL);
        } else {
            transitionTo(AppointmentStatus.APPROVED);
            recordDecision(actorUserId, remarks);
        }
    }
    public void rejectByManager(UUID actorUserId, String remarks) {
        if (status != AppointmentStatus.PENDING_MANAGER_APPROVAL) invalidApproval("MANAGER");
        managerApprovalActorId = actorUserId;
        managerDecisionAt = Instant.now();
        managerDecisionRemarks = normalize(remarks);
        transitionTo(AppointmentStatus.REJECTED);
        recordDecision(actorUserId, remarks);
    }
    public void approveByCeo(UUID actorUserId, String remarks) {
        if ((type != AppointmentType.CEO_VISIT && type != AppointmentType.EMERGENCY)
                || status != AppointmentStatus.PENDING_CEO_APPROVAL) invalidApproval("CEO");
        ceoApprovalActorId = actorUserId;
        ceoDecisionAt = Instant.now();
        ceoDecisionRemarks = remarks;
        transitionTo(AppointmentStatus.APPROVED);
        recordDecision(actorUserId, remarks);
    }
    public void rejectByCeo(UUID actorUserId, String remarks) {
        if ((type != AppointmentType.CEO_VISIT && type != AppointmentType.EMERGENCY)
                || status != AppointmentStatus.PENDING_CEO_APPROVAL) invalidApproval("CEO");
        ceoApprovalActorId = actorUserId;
        ceoDecisionAt = Instant.now();
        ceoDecisionRemarks = remarks;
        transitionTo(AppointmentStatus.REJECTED);
        recordDecision(actorUserId, remarks);
    }
    public void forwardByReception(UUID actorUserId, String remarks) {
        if (status != AppointmentStatus.APPROVED) invalidApproval("RECEPTION_FORWARD");
        if (type != AppointmentType.HR_VISIT && type != AppointmentType.INTERVIEW
                && type != AppointmentType.CEO_VISIT && type != AppointmentType.EMERGENCY) {
            throw new BusinessException("CABIN_FORWARD_NOT_SUPPORTED",
                    "Reception cabin forwarding is available only for HR, interview, CEO and emergency visits",
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }
        if (receptionForwardedAt != null) {
            throw new BusinessException("VISITOR_ALREADY_FORWARDED",
                    "Reception has already forwarded this visitor", HttpStatus.CONFLICT);
        }
        receptionForwardActorId = actorUserId;
        receptionForwardedAt = Instant.now();
        receptionForwardRemarks = normalize(remarks);
    }
    public void approve(UUID actor, String remarks) { transitionTo(AppointmentStatus.APPROVED); recordDecision(actor, remarks); }
    public void reject(UUID actor, String remarks) { transitionTo(AppointmentStatus.REJECTED); recordDecision(actor, remarks); }
    public void cancel() { transitionTo(AppointmentStatus.CANCELLED); }
    public void checkIn() { transitionTo(AppointmentStatus.CHECKED_IN); }
    public void checkOut() { transitionTo(AppointmentStatus.CHECKED_OUT); }
    public void complete() { transitionTo(AppointmentStatus.COMPLETED); }

    private void recordDecision(UUID actor, String remarks) { approvalActorId = actor; decisionAt = Instant.now(); decisionRemarks = remarks; }
    private String normalize(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private void invalidApproval(String stage) {
        throw new BusinessException("INVALID_" + stage + "_APPROVAL_STAGE",
                stage + " approval is not allowed while appointment is " + status, HttpStatus.UNPROCESSABLE_ENTITY);
    }
    private void transitionTo(AppointmentStatus next) {
        if (!TRANSITIONS.getOrDefault(status, Set.of()).contains(next))
            throw new BusinessException("INVALID_APPOINTMENT_TRANSITION", "Cannot change appointment from " + status + " to " + next, HttpStatus.UNPROCESSABLE_ENTITY);
        status = next;
    }

    public String getReferenceNumber() { return referenceNumber; }
    public AppointmentType getType() { return type; }
    public AppointmentStatus getStatus() { return status; }
    public String getVisitorName() { return visitorName; }
    public String getVisitorEmail() { return visitorEmail; }
    public String getVisitorPhone() { return visitorPhone; }
    public String getVisitorCompany() { return visitorCompany; }
    public UUID getHostEmployeeId() { return hostEmployeeId; }
    public UUID getRoutingDepartmentId() { return routingDepartmentId; }
    public UUID getRequestedEmployeeId() { return requestedEmployeeId; }
    public Instant getSlotStart() { return slotStart; }
    public Instant getSlotEnd() { return slotEnd; }
    public String getPurpose() { return purpose; }
    public UUID getApprovalActorId() { return approvalActorId; }
    public Instant getDecisionAt() { return decisionAt; }
    public String getDecisionRemarks() { return decisionRemarks; }
    public UUID getRegisteredByUserId() { return registeredByUserId; }
    public UUID getSecurityIntakeActorId() { return securityIntakeActorId; }
    public Instant getSecurityIntakeAt() { return securityIntakeAt; }
    public String getArrivalVisitorName() { return arrivalVisitorName; }
    public String getArrivalPurpose() { return arrivalPurpose; }
    public String getIdentityDocumentType() { return identityDocumentType; }
    public String getIdentityDocumentLastFour() { return identityDocumentLastFour; }
    public String getSecurityNotes() { return securityNotes; }
    public UUID getReceptionVerificationActorId() { return receptionVerificationActorId; }
    public Instant getReceptionVerifiedAt() { return receptionVerifiedAt; }
    public String getReceptionVerificationRemarks() { return receptionVerificationRemarks; }
    public UUID getHrApprovalActorId() { return hrApprovalActorId; }
    public Instant getHrDecisionAt() { return hrDecisionAt; }
    public String getHrDecisionRemarks() { return hrDecisionRemarks; }
    public UUID getTeamLeadApprovalActorId() { return teamLeadApprovalActorId; }
    public Instant getTeamLeadDecisionAt() { return teamLeadDecisionAt; }
    public String getTeamLeadDecisionRemarks() { return teamLeadDecisionRemarks; }
    public UUID getManagerApprovalActorId() { return managerApprovalActorId; }
    public Instant getManagerDecisionAt() { return managerDecisionAt; }
    public String getManagerDecisionRemarks() { return managerDecisionRemarks; }
    public UUID getCeoApprovalActorId() { return ceoApprovalActorId; }
    public Instant getCeoDecisionAt() { return ceoDecisionAt; }
    public String getCeoDecisionRemarks() { return ceoDecisionRemarks; }
    public UUID getReceptionForwardActorId() { return receptionForwardActorId; }
    public Instant getReceptionForwardedAt() { return receptionForwardedAt; }
    public String getReceptionForwardRemarks() { return receptionForwardRemarks; }

    private static Map<AppointmentStatus, Set<AppointmentStatus>> transitions() {
        Map<AppointmentStatus, Set<AppointmentStatus>> map = new EnumMap<>(AppointmentStatus.class);
        map.put(AppointmentStatus.DRAFT, EnumSet.of(AppointmentStatus.PENDING_VERIFICATION, AppointmentStatus.CANCELLED));
        map.put(AppointmentStatus.PENDING_VERIFICATION, EnumSet.of(AppointmentStatus.PENDING_SECURITY_INTAKE,
                AppointmentStatus.CANCELLED, AppointmentStatus.EXPIRED));
        map.put(AppointmentStatus.PENDING_SECURITY_INTAKE, EnumSet.of(AppointmentStatus.PENDING_RECEPTION_VERIFICATION,
                AppointmentStatus.CANCELLED, AppointmentStatus.EXPIRED));
        map.put(AppointmentStatus.PENDING_RECEPTION_VERIFICATION, EnumSet.of(AppointmentStatus.PENDING_HR_APPROVAL,
                AppointmentStatus.PENDING_MANAGER_APPROVAL, AppointmentStatus.PENDING_CEO_APPROVAL,
                AppointmentStatus.REJECTED,
                AppointmentStatus.CANCELLED, AppointmentStatus.EXPIRED));
        map.put(AppointmentStatus.PENDING_APPROVAL, EnumSet.of(AppointmentStatus.APPROVED, AppointmentStatus.REJECTED, AppointmentStatus.RESCHEDULE_REQUESTED, AppointmentStatus.CANCELLED, AppointmentStatus.EXPIRED));
        map.put(AppointmentStatus.PENDING_HR_APPROVAL, EnumSet.of(AppointmentStatus.PENDING_TEAM_LEAD_APPROVAL,
                AppointmentStatus.PENDING_MANAGER_APPROVAL,
                AppointmentStatus.APPROVED, AppointmentStatus.REJECTED, AppointmentStatus.CANCELLED, AppointmentStatus.EXPIRED));
        map.put(AppointmentStatus.PENDING_TEAM_LEAD_APPROVAL, EnumSet.of(AppointmentStatus.APPROVED,
                AppointmentStatus.REJECTED, AppointmentStatus.RESCHEDULE_REQUESTED,
                AppointmentStatus.CANCELLED, AppointmentStatus.EXPIRED));
        map.put(AppointmentStatus.PENDING_MANAGER_APPROVAL, EnumSet.of(AppointmentStatus.PENDING_CEO_APPROVAL,
                AppointmentStatus.APPROVED, AppointmentStatus.REJECTED,
                AppointmentStatus.CANCELLED, AppointmentStatus.EXPIRED));
        map.put(AppointmentStatus.PENDING_CEO_APPROVAL, EnumSet.of(AppointmentStatus.APPROVED,
                AppointmentStatus.REJECTED, AppointmentStatus.CANCELLED, AppointmentStatus.EXPIRED));
        map.put(AppointmentStatus.APPROVED, EnumSet.of(AppointmentStatus.CHECKED_IN, AppointmentStatus.CANCELLED, AppointmentStatus.NO_SHOW, AppointmentStatus.RESCHEDULE_REQUESTED));
        map.put(AppointmentStatus.RESCHEDULE_REQUESTED, EnumSet.of(AppointmentStatus.RESCHEDULED, AppointmentStatus.REJECTED, AppointmentStatus.CANCELLED));
        map.put(AppointmentStatus.RESCHEDULED, EnumSet.of(AppointmentStatus.APPROVED, AppointmentStatus.CANCELLED));
        map.put(AppointmentStatus.CHECKED_IN, EnumSet.of(AppointmentStatus.IN_MEETING, AppointmentStatus.CHECKED_OUT));
        map.put(AppointmentStatus.IN_MEETING, EnumSet.of(AppointmentStatus.CHECKED_OUT));
        map.put(AppointmentStatus.CHECKED_OUT, EnumSet.of(AppointmentStatus.COMPLETED));
        for (AppointmentStatus terminal : EnumSet.of(AppointmentStatus.REJECTED, AppointmentStatus.CANCELLED, AppointmentStatus.COMPLETED, AppointmentStatus.NO_SHOW, AppointmentStatus.EXPIRED)) map.put(terminal, EnumSet.noneOf(AppointmentStatus.class));
        return Map.copyOf(map);
    }
}
