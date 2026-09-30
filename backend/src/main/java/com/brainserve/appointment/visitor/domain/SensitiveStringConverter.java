package com.brainserve.appointment.visitor.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

@Converter
@Component
public class SensitiveStringConverter implements AttributeConverter<String, String> {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int IV_LENGTH = 12;
    private static volatile SecretKey encryptionKey;

    @Value("${brainserve.security.pii-encryption-key}")
    void configureEncryptionKey(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            throw new IllegalStateException("brainserve.security.pii-encryption-key is required for sensitive identifiers");
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("brainserve.security.pii-encryption-key must be valid Base64", exception);
        }
        if (bytes.length != 32) {
            throw new IllegalStateException("brainserve.security.pii-encryption-key must decode to 32 bytes");
        }
        encryptionKey = new SecretKeySpec(bytes, "AES");
    }

    @Override
    public String convertToDatabaseColumn(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            byte[] iv = new byte[IV_LENGTH]; RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(128, iv));
            byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.length + encrypted.length).put(iv).put(encrypted).array());
        } catch (GeneralSecurityException ex) { throw new IllegalStateException("Sensitive value encryption failed", ex); }
    }

    @Override
    public String convertToEntityAttribute(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            byte[] decoded = Base64.getDecoder().decode(value);
            ByteBuffer buffer = ByteBuffer.wrap(decoded);
            byte[] iv = new byte[IV_LENGTH]; buffer.get(iv);
            byte[] encrypted = new byte[buffer.remaining()]; buffer.get(encrypted);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException ex) { throw new IllegalStateException("Sensitive value decryption failed", ex); }
    }

    private SecretKey key() {
        SecretKey configuredKey = encryptionKey;
        if (configuredKey == null) throw new IllegalStateException("Sensitive data encryption key has not been initialized");
        return configuredKey;
    }
}
