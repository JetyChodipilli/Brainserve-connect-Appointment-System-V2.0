package com.brainserve.appointment.notification.application;

import com.brainserve.appointment.iam.api.EmailService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
public class SpringMailEmailService implements EmailService {
    private final JavaMailSender mailSender;
    private final String from;
    private final String supportEmail;

    public SpringMailEmailService(JavaMailSender mailSender,
                                  @Value("${brainserve.notification.from}") String from,
                                  @Value("${brainserve.identity.support-email:support@brainserve.in}") String supportEmail) {
        this.mailSender = mailSender;
        this.from = from;
        this.supportEmail = supportEmail;
    }

    @Override
    public void sendPasswordChangeOtp(String email, String fullName, String otp, Instant expiresAt) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(email);
        message.setSubject("Your BrainServe Connect Password Change OTP");
        message.setText("Hello " + fullName + ",\n\n"
                + "Use this one-time password to confirm your BrainServe Connect password change: " + otp + "\n\n"
                + "This OTP expires at " + expiresAt + ". Do not share it with anyone.\n\n"
                + "If you did not request this, keep your current password and contact support at " + supportEmail + ".\n\n"
                + "BrainServe Connect Security");
        mailSender.send(message);
    }

    @Override
    public void sendPasswordChangedConfirmation(String email, String fullName, Instant changedAt) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(email);
        message.setSubject("Your BrainServe Connect Password Was Changed");
        message.setText("Hello " + fullName + ",\n\n"
                + "Your BrainServe Connect password was changed successfully at " + changedAt + ".\n\n"
                + "If this wasn't you, contact BrainServe Connect support immediately at " + supportEmail + ".\n\n"
                + "BrainServe Connect Security");
        mailSender.send(message);
    }

    @Override
    public void sendEmailRecoveryConfirmation(String email, String fullName, Instant changedAt) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(email);
        message.setSubject("Your BrainServe Connect Login Email Was Recovered");
        message.setText("Hello " + fullName + ",\n\n"
                + "Your BrainServe Connect login email was updated successfully at " + changedAt + ".\n\n"
                + "If this wasn't you, contact BrainServe Connect support immediately at " + supportEmail + ".\n\n"
                + "BrainServe Connect Security");
        mailSender.send(message);
    }

    @Override
    public void sendPendingAccountCreated(String email, String fullName, String role, String temporaryPassword,
                                          String approvalAuthority) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(email);
        message.setSubject("Your BrainServe Connect Account Is Pending Approval");
        message.setText("Hello " + fullName + ",\n\n"
                + "A BrainServe Connect account was created for you with role " + readableRole(role) + ".\n"
                + "Temporary password: " + temporaryPassword + "\n\n"
                + "Your account cannot sign in until " + approvalAuthority + " approves it. "
                + "Keep this password private.\n\nBrainServe Connect Identity and Access");
        mailSender.send(message);
    }

    @Override
    public void sendAccountApproved(String email, String fullName, String role, String approvedByEmail) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(email);
        message.setSubject("Your BrainServe Connect Account Has Been Created Successfully");
        message.setText("Hello " + fullName + ",\n\n"
                + "Your BrainServe Connect account has been approved and activated successfully.\n"
                + "Role: " + readableRole(role) + "\n"
                + "Approved by: " + approvedByEmail + "\n\n"
                + "You can now sign in using your registered email and password.\n\n"
                + "BrainServe Connect Identity and Access");
        mailSender.send(message);
    }

    @Override
    public void sendAccountRejected(String email, String fullName, String role, String reason, String rejectedByEmail) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(email);
        message.setSubject("BrainServe Connect Account Request Update");
        message.setText("Hello " + fullName + ",\n\n"
                + "Your BrainServe Connect account request for " + readableRole(role) + " was not approved.\n"
                + "Decision by: " + rejectedByEmail + "\n"
                + (reason == null || reason.isBlank() ? "" : "Reason: " + reason + "\n")
                + "\nContact BrainServe Connect administration if you need assistance.\n\n"
                + "BrainServe Connect Identity and Access");
        mailSender.send(message);
    }

    @Override
    public void sendAccountArchiveOtp(String email, String fullName, String targetName, String targetEmail,
                                      String otp, Instant expiresAt) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(email);
        message.setSubject("Confirm BrainServe Connect Account Deactivation and Archive");
        message.setText("Hello " + fullName + ",\n\n"
                + "Use this one-time password to confirm emergency archival of " + targetName
                + " (" + targetEmail + "): " + otp + "\n\n"
                + "This OTP expires at " + expiresAt + ". The action disables login, revokes active sessions "
                + "and preserves the account lifecycle record.\n\n"
                + "If you did not request this, do not share the OTP and contact support at " + supportEmail + ".\n\n"
                + "BrainServe Connect Identity and Access");
        mailSender.send(message);
    }

    @Override
    public void sendArchivedAccountRecoveryOtp(String email, String fullName, String targetName,
                                               String targetEmail, String targetRole,
                                               String otp, Instant expiresAt) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(email);
        message.setSubject("Confirm BrainServe Connect Archived Account Recovery");
        message.setText("Hello " + fullName + ",\n\n"
                + "Use this one-time password to recover " + targetName + " (" + targetEmail + ") as "
                + readableRole(targetRole) + ": " + otp + "\n\n"
                + "This OTP expires at " + expiresAt + ". Recovery keeps the same user and employee IDs, "
                + "commits one current role, clears stale permission overrides and revokes old sessions.\n\n"
                + "If you did not request this, do not share the OTP and contact support at " + supportEmail + ".\n\n"
                + "BrainServe Connect Identity and Access");
        mailSender.send(message);
    }

    private String readableRole(String role) {
        return role.replaceFirst("^ROLE_", "").replace('_', ' ');
    }
}
