package com.brainserve.appointment.workinsight.api;

import java.util.UUID;

public final class WorkInsightEvents {
    private WorkInsightEvents() {}

    public record HrAuditSubmitted(
            UUID hrUserId,
            UUID managerUserId,
            String message
    ) {}

    public record ManagerDecisionRecorded(
            UUID managerUserId,
            UUID hrUserId,
            boolean approved,
            String message
    ) {}

    public record CeoDecisionRecorded(
            UUID ceoUserId,
            UUID hrUserId,
            String message
    ) {}

    public record FinalApprovalRecipient(
            UUID ceoUserId,
            UUID recipientUserId,
            String message
    ) {}

    public record ReworkRequested(
            UUID reviewerUserId,
            UUID teamLeadUserId,
            String message
    ) {}

    public record ReworkAssigned(
            UUID teamLeadUserId,
            UUID employeeUserId,
            String message
    ) {}
}