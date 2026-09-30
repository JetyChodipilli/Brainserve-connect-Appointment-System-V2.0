package com.brainserve.appointment.iam.api;

import java.time.Instant;

public interface EmailService {
    void sendPasswordChangeOtp(String email, String fullName, String otp, Instant expiresAt);
    void sendPasswordChangedConfirmation(String email, String fullName, Instant changedAt);
    void sendEmailRecoveryConfirmation(String email, String fullName, Instant changedAt);
    void sendPendingAccountCreated(String email, String fullName, String role, String temporaryPassword,
                                   String approvalAuthority);
    void sendAccountApproved(String email, String fullName, String role, String approvedByEmail);
    void sendAccountRejected(String email, String fullName, String role, String reason, String rejectedByEmail);
    void sendAccountArchiveOtp(String email, String fullName, String targetName, String targetEmail,
                               String otp, Instant expiresAt);
    void sendArchivedAccountRecoveryOtp(String email, String fullName, String targetName, String targetEmail,
                                        String targetRole, String otp, Instant expiresAt);
}
