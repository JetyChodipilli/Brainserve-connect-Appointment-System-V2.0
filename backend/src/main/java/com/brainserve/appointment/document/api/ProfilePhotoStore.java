package com.brainserve.appointment.document.api;

import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface ProfilePhotoStore {
    ProfilePhoto save(UUID userId, MultipartFile file);
    Optional<ProfilePhoto> current(UUID userId);
    ProfilePhotoAccess createAccess(UUID documentId);

    record ProfilePhoto(UUID documentId, String filename, String contentType, long sizeBytes, Instant createdAt) {}
    record ProfilePhotoAccess(String url, Instant expiresAt) {}
}
