package com.brainserve.appointment.appointment.application;

import com.brainserve.appointment.appointment.api.VisitorPassVerification;
import com.brainserve.appointment.appointment.domain.Appointment;
import com.brainserve.appointment.appointment.domain.AppointmentStatus;
import com.brainserve.appointment.appointment.infrastructure.AppointmentRepository;
import com.brainserve.appointment.configuration.api.WorkspacePolicy;
import com.brainserve.appointment.shared.application.BusinessException;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Map;
import java.util.Set;

@Service
public class VisitorPassService implements VisitorPassVerification {
    private static final Set<AppointmentStatus> PASS_STATUSES = Set.of(AppointmentStatus.APPROVED, AppointmentStatus.CHECKED_IN);
    private final AppointmentRepository appointments;
    private final WorkspacePolicy workspacePolicy;
    private final byte[] signingSecret;
    private final String publicFrontendUrl;

    public VisitorPassService(AppointmentRepository appointments, WorkspacePolicy workspacePolicy,
                              @Value("${brainserve.appointment.qr-signing-secret}") String signingSecret,
                              @Value("${brainserve.frontend.public-url}") String publicFrontendUrl) {
        if (signingSecret == null || signingSecret.length() < 32) {
            throw new IllegalStateException("QR_PASS_SIGNING_SECRET must contain at least 32 characters");
        }
        if (publicFrontendUrl == null || publicFrontendUrl.isBlank()
                || !(publicFrontendUrl.startsWith("https://") || publicFrontendUrl.startsWith("http://localhost"))) {
            throw new IllegalStateException("FRONTEND_PUBLIC_URL must be an HTTPS URL or localhost");
        }
        this.appointments = appointments;
        this.workspacePolicy = workspacePolicy;
        this.signingSecret = signingSecret.getBytes(StandardCharsets.UTF_8);
        this.publicFrontendUrl = publicFrontendUrl.replaceFirst("/+$", "");
    }

    @Transactional(readOnly = true)
    public VisitorPass issue(String reference) {
        Appointment appointment = requireAppointment(reference);
        requirePassStatus(appointment);
        Instant validFrom = appointment.getSlotStart().minus(earlyMinutes(), ChronoUnit.MINUTES);
        Instant expiresAt = appointment.getSlotEnd().plus(expiryMinutes(), ChronoUnit.MINUTES);
        if (Instant.now().isAfter(expiresAt)) {
            throw new BusinessException("VISITOR_PASS_EXPIRED", "This visitor pass has expired", HttpStatus.GONE);
        }
        String token = tokenFor(appointment);
        String qrPayload = publicFrontendUrl + "/visitor-pass?token=" + token;
        return new VisitorPass(appointment.getReferenceNumber(), mask(appointment.getVisitorName()),
                appointment.getStatus().name(), validFrom, expiresAt, token, qrPngDataUrl(qrPayload));
    }

    @Override
    @Transactional(readOnly = true)
    public VerifiedPass verify(String presentedToken) {
        String token = normalizeToken(presentedToken);
        String[] pieces = token.split("\\.", -1);
        if (pieces.length != 2 || !constantTimeEquals(sign(pieces[0]), pieces[1])) invalidPass();
        String payload;
        try { payload = new String(Base64.getUrlDecoder().decode(pieces[0]), StandardCharsets.UTF_8); }
        catch (IllegalArgumentException ex) { return invalidPass(); }
        String[] fields = payload.split("\\|", -1);
        if (fields.length != 3) return invalidPass();
        Appointment appointment = requireAppointment(fields[0]);
        requirePassStatus(appointment);
        if (!Long.toString(appointment.getSlotStart().getEpochSecond()).equals(fields[1])
                || !Long.toString(appointment.getSlotEnd().getEpochSecond()).equals(fields[2])) return invalidPass();
        Instant validFrom = appointment.getSlotStart().minus(earlyMinutes(), ChronoUnit.MINUTES);
        Instant expiresAt = appointment.getSlotEnd().plus(expiryMinutes(), ChronoUnit.MINUTES);
        Instant now = Instant.now();
        if (now.isBefore(validFrom)) {
            throw new BusinessException("VISITOR_PASS_NOT_YET_VALID", "This visitor pass is not valid yet", HttpStatus.UNPROCESSABLE_ENTITY);
        }
        if (now.isAfter(expiresAt)) {
            throw new BusinessException("VISITOR_PASS_EXPIRED", "This visitor pass has expired", HttpStatus.GONE);
        }
        return new VerifiedPass(appointment.getId(), appointment.getReferenceNumber(), appointment.getVisitorName(),
                appointment.getVisitorCompany(), appointment.getStatus().name(), appointment.getSlotStart(),
                appointment.getSlotEnd(), expiresAt);
    }

    private Appointment requireAppointment(String reference) {
        String normalized = reference == null ? "" : reference.trim().toUpperCase(java.util.Locale.ROOT);
        return appointments.findByReferenceNumber(normalized).orElseThrow(() -> new BusinessException(
                "APPOINTMENT_NOT_FOUND", "Appointment was not found", HttpStatus.NOT_FOUND));
    }

    private void requirePassStatus(Appointment appointment) {
        if (!PASS_STATUSES.contains(appointment.getStatus())) {
            throw new BusinessException("VISITOR_PASS_NOT_AVAILABLE",
                    "A QR visitor pass is available only after all required approvals", HttpStatus.CONFLICT);
        }
    }

    private String tokenFor(Appointment appointment) {
        String payload = appointment.getReferenceNumber() + "|" + appointment.getSlotStart().getEpochSecond()
                + "|" + appointment.getSlotEnd().getEpochSecond();
        String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        return encoded + "." + sign(encoded);
    }

    private String sign(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signingSecret, "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException ex) { throw new IllegalStateException("QR pass signing failed", ex); }
    }

    private String normalizeToken(String presentedToken) {
        if (presentedToken == null || presentedToken.isBlank()) return invalidPass();
        String token = presentedToken.trim();
        int marker = token.indexOf("token=");
        if (marker >= 0) token = token.substring(marker + 6).split("[&#]", 2)[0];
        if (token.startsWith("brainserve-pass:")) token = token.substring("brainserve-pass:".length());
        if (token.length() > 500) return invalidPass();
        return token;
    }

    private boolean constantTimeEquals(String expected, String actual) {
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII), actual.getBytes(StandardCharsets.US_ASCII));
    }

    private String qrPngDataUrl(String payload) {
        try {
            var matrix = new QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, 320, 320,
                    Map.of(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M, EncodeHintType.MARGIN, 1));
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            MatrixToImageWriter.writeToStream(matrix, "PNG", output);
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(output.toByteArray());
        } catch (Exception ex) { throw new IllegalStateException("QR pass generation failed", ex); }
    }

    private int earlyMinutes() { return Math.max(0, workspacePolicy.integerValue("APPOINTMENT.CHECK_IN_EARLY_MINUTES", 30)); }
    private int expiryMinutes() { return Math.max(0, workspacePolicy.integerValue("APPOINTMENT.QR_EXPIRY_MINUTES_AFTER_END", 120)); }
    private String mask(String name) { return name == null || name.isBlank() ? "Visitor" : name.charAt(0) + "***"; }
    private <T> T invalidPass() { throw new BusinessException("INVALID_VISITOR_PASS", "Visitor pass is invalid", HttpStatus.UNAUTHORIZED); }

    public record VisitorPass(String referenceNumber, String visitorDisplayName, String status,
                              Instant validFrom, Instant expiresAt, String token, String qrCodeDataUrl) {}
}
