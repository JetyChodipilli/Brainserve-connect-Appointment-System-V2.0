package com.brainserve.appointment.iam.application;

import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Locale;
import java.util.OptionalLong;

/** RFC 6238, SHA-1, six digits and a 30-second step. No external authenticator dependency. */
@Service
public class TotpService {
    private static final char[] BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".toCharArray();
    private final SecureRandom random = new SecureRandom();

    public String newSecret() {
        byte[] bytes = new byte[20];
        random.nextBytes(bytes);
        StringBuilder result = new StringBuilder(32);
        int buffer = 0, bits = 0;
        for (byte value : bytes) {
            buffer = (buffer << 8) | (value & 0xff);
            bits += 8;
            while (bits >= 5) { bits -= 5; result.append(BASE32[(buffer >>> bits) & 31]); }
        }
        return result.toString();
    }

    public String enrollmentUri(String email, String secret) {
        String issuer = "BrainServe Connect";
        return "otpauth://totp/" + encode(issuer + ":" + email) + "?secret=" + secret
                + "&issuer=" + encode(issuer) + "&algorithm=SHA1&digits=6&period=30";
    }

    public OptionalLong matchingStep(String secret, String code, Instant now, Long lastAcceptedStep) {
        if (code == null || !code.matches("[0-9]{6}")) return OptionalLong.empty();
        long step = now.getEpochSecond() / 30;
        long matched = -1;
        for (long candidate = step - 1; candidate <= step + 1; candidate++) {
            if (candidate >= 0 && MessageDigest.isEqual(codeForStep(secret, candidate).getBytes(StandardCharsets.US_ASCII),
                    code.getBytes(StandardCharsets.US_ASCII))) matched = candidate;
        }
        return matched >= 0 && (lastAcceptedStep == null || matched > lastAcceptedStep)
                ? OptionalLong.of(matched) : OptionalLong.empty();
    }

    public String codeAt(String secret, Instant when) { return codeForStep(secret, when.getEpochSecond() / 30); }

    private String codeForStep(String secret, long step) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(decode(secret), "HmacSHA1"));
            byte[] digest = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
            int offset = digest[digest.length - 1] & 15;
            int number = ((digest[offset] & 127) << 24) | ((digest[offset + 1] & 255) << 16)
                    | ((digest[offset + 2] & 255) << 8) | (digest[offset + 3] & 255);
            return String.format(Locale.ROOT, "%06d", number % 1_000_000);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("TOTP algorithm is unavailable", exception);
        }
    }

    private byte[] decode(String value) {
        if (value == null || !value.matches("[A-Z2-7]{32}")) throw new IllegalArgumentException("Invalid TOTP secret");
        byte[] result = new byte[20];
        int buffer = 0, bits = 0, offset = 0;
        for (char character : value.toCharArray()) {
            int number = character <= 'Z' && character >= 'A' ? character - 'A' : character - '2' + 26;
            buffer = (buffer << 5) | number;
            bits += 5;
            if (bits >= 8) { bits -= 8; result[offset++] = (byte) (buffer >>> bits); }
        }
        return result;
    }

    private String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20"); }
}
