package com.brainserve.appointment.appointment.api;
import java.time.Instant;
import java.util.UUID;
public interface VisitorBadgePass {
    BadgePass badgePass(UUID appointmentId);
    record BadgePass(String referenceNumber,String visitorName,Instant expiresAt,String qrCodeDataUrl) {}
}
