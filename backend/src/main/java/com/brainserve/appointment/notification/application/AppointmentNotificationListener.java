package com.brainserve.appointment.notification.application;

import com.brainserve.appointment.appointment.api.AppointmentEvents;
import com.brainserve.appointment.iam.api.StaffDirectory;
import com.brainserve.appointment.configuration.api.WorkspacePolicy;
import com.brainserve.appointment.departmenthr.api.DepartmentHrDirectory;
import com.brainserve.appointment.manager.api.ManagerDirectory;
import com.brainserve.appointment.notification.domain.OutboxMessage;
import com.brainserve.appointment.notification.infrastructure.OutboxRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Map;

@Component
public class AppointmentNotificationListener {
    private final OutboxRepository outbox;
    private final ObjectMapper mapper;
    private final StaffDirectory staffDirectory;
    private final WorkspacePolicy workspacePolicy;
    private final DepartmentHrDirectory departmentHrs;
    private final ManagerDirectory managers;
    public AppointmentNotificationListener(OutboxRepository outbox, ObjectMapper mapper, StaffDirectory staffDirectory,
                                           WorkspacePolicy workspacePolicy, DepartmentHrDirectory departmentHrs,
                                           ManagerDirectory managers) {
        this.outbox = outbox; this.mapper = mapper; this.staffDirectory = staffDirectory;
        this.workspacePolicy = workspacePolicy;
        this.departmentHrs = departmentHrs;
        this.managers = managers;
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void requested(AppointmentEvents.AppointmentRequested event) {
        if (!workspacePolicy.booleanValue("NOTIFICATION.APPOINTMENT_EMAIL_ENABLED", true)) return;
        try {
            String payload = mapper.writeValueAsString(Map.of("reference", event.reference(), "otp", event.otp()));
            outbox.save(new OutboxMessage("appointment-requested:" + event.appointmentId(), event.visitorEmail(),
                    "APPOINTMENT_OTP", payload));
        } catch (JsonProcessingException ex) { throw new IllegalStateException("Notification payload serialization failed", ex); }
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void cancellationOtpRequested(AppointmentEvents.AppointmentCancellationOtpRequested event) {
        if (!workspacePolicy.booleanValue("NOTIFICATION.APPOINTMENT_EMAIL_ENABLED", true)) return;
        try {
            String payload = mapper.writeValueAsString(Map.of("reference", event.reference(), "otp", event.otp()));
            outbox.save(new OutboxMessage("appointment-cancellation-otp:" + event.appointmentId()
                    + ":" + event.occurredAt().getEpochSecond(), event.visitorEmail(),
                    "APPOINTMENT_CANCELLATION_OTP", payload));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Cancellation OTP payload serialization failed", ex);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void approvalRequired(AppointmentEvents.AppointmentStatusChanged event) {
        if (!workspacePolicy.booleanValue("NOTIFICATION.APPROVAL_EMAIL_ENABLED", true)) return;
        java.util.List<String> recipients;
        String template;
        if (event.status().equals("PENDING_CEO_APPROVAL")) {
            recipients = staffDirectory.ceoApprovalRecipients();
            template = "CEO_VISIT_APPROVAL_REQUIRED";
        } else {
            return;
        }
        try {
            String payload = mapper.writeValueAsString(Map.of("reference", event.reference(), "status", event.status()));
            for (int index = 0; index < recipients.size(); index++) {
                outbox.save(new OutboxMessage("appointment-approval:" + event.appointmentId() + ":" + event.status() + ":" + index,
                        recipients.get(index), template, payload));
            }
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Approval notification payload serialization failed", ex);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void receptionVerified(AppointmentEvents.ReceptionVerified event) {
        if (!workspacePolicy.booleanValue("NOTIFICATION.APPROVAL_EMAIL_ENABLED", true)) return;
        if (event.routingDepartmentId() == null) return;
        boolean managerRoute = event.approvalStatus().equals("PENDING_MANAGER_APPROVAL");
        var recipients = java.util.List.of(managerRoute
                ? managers.requireForDepartment(event.routingDepartmentId()).email()
                : departmentHrs.requireForDepartment(event.routingDepartmentId()).email());
        String status = managerRoute ? "PENDING_MANAGER_APPROVAL" : "PENDING_HR_APPROVAL";
        String template = managerRoute ? "MANAGER_VISIT_APPROVAL_REQUIRED"
                : "HR_VISIT_APPROVAL_REQUIRED";
        try {
            String payload = mapper.writeValueAsString(Map.of("reference", event.reference(),
                    "status", status, "visitorName", event.visitorName(),
                    "purpose", event.purpose(), "hostEmployeeId", event.hostEmployeeId().toString()));
            for (int index = 0; index < recipients.size(); index++) {
                outbox.save(new OutboxMessage("appointment-approval:" + event.appointmentId()
                        + ":" + status + ":" + index, recipients.get(index), template, payload));
            }
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("HR approval payload serialization failed", ex);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void teamLeadApprovalRequired(AppointmentEvents.TeamLeadApprovalRequested event) {
        if (!workspacePolicy.booleanValue("NOTIFICATION.APPROVAL_EMAIL_ENABLED", true)) return;
        try {
            String payload = mapper.writeValueAsString(Map.of("reference", event.reference(),
                    "status", "PENDING_TEAM_LEAD_APPROVAL", "visitorName", event.visitorName(),
                    "purpose", event.purpose()));
            outbox.save(new OutboxMessage("appointment-approval:" + event.appointmentId()
                    + ":PENDING_TEAM_LEAD_APPROVAL", event.teamLeadEmail(),
                    "TEAM_LEAD_VISIT_APPROVAL_REQUIRED", payload));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Team Lead approval payload serialization failed", ex);
        }
    }
}
