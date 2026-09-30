package com.brainserve.appointment.notification.api;

import java.util.UUID;

public interface InternalNotificationGateway {
    void sendResourceDiscussionUpdate(UUID senderUserId, UUID recipientUserId, String message);

    void sendWorkTaskUpdate(UUID senderUserId, UUID recipientUserId, String message);

    void notifyHrOfWorkTaskUpdate(UUID actorUserId, UUID departmentId, String message);

    void notifyManagerOfWorkInsightAudit(UUID hrUserId, UUID managerUserId, String message);

    void notifyCeoOfManagerWorkInsightApproval(UUID managerUserId, String message);

    void notifyHrOfManagerWorkInsightDecision(UUID managerUserId, UUID hrUserId, String message);

    void notifyHrOfWorkInsightDecision(UUID ceoUserId, UUID hrUserId, String message);

    void notifyWorkerOfCeoWorkInsightApproval(
            UUID ceoUserId,
            UUID recipientUserId,
            String message
    );

    void notifyCeoOfManagerVisitApproval(UUID managerUserId, String message);

    void notifyManagerOfCeoVisitDecision(UUID ceoUserId, UUID departmentId, String message);

    void notifyTeamLeadOfWorkInsightRework(
            UUID reviewerUserId,
            UUID teamLeadUserId,
            String message
    );

    void notifyRoleDepartmentChangeApprover(
            UUID requesterUserId,
            String requesterRole,
            UUID targetDepartmentId,
            String message
    );

    void notifyRoleDepartmentChangeDecision(
            UUID approverUserId,
            UUID requesterUserId,
            String message
    );

    void notifyCeoOfTerminationRequest(UUID hrUserId, String message);

    void notifyHrOfTerminationDecision(UUID ceoUserId, UUID hrUserId, String message);

    void notifyAccountClosureReview(
            UUID requesterUserId,
            String targetRole,
            UUID departmentId,
            String message
    );

    void notifySystemAdminOfAccountClosure(UUID actorUserId, String message);

    void notifyAccountClosureDecision(UUID actorUserId, UUID targetUserId, String message);

    void notifyReportExportReady(UUID userId, String message, boolean failed);
}