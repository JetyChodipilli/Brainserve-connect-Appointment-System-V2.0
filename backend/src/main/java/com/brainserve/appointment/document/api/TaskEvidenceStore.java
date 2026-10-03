package com.brainserve.appointment.document.api;

import java.time.Instant;
import java.util.UUID;
import org.springframework.web.multipart.MultipartFile;

/** Private evidence storage. Caller must check current task authority before and after storage. */
public interface TaskEvidenceStore {
    EvidenceDocument store(UUID taskId, MultipartFile file);
    Download download(UUID taskId, UUID documentId);
    record EvidenceDocument(UUID id, String filename, String contentType, long sizeBytes, String sha256, Instant createdAt) {}
    record Download(EvidenceDocument document, byte[] bytes) {}
}
