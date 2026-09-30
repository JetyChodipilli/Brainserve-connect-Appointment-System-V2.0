package com.brainserve.appointment.document.domain;

import com.brainserve.appointment.shared.domain.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.util.UUID;

@Entity
@Table(name = "stored_document")
public class StoredDocument extends AuditableEntity {
    @Column(name = "owner_type", nullable = false, length = 40)
    private String ownerType;
    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;
    @Column(nullable = false, length = 50)
    private String category;
    @Column(name = "object_key", nullable = false, unique = true, length = 300)
    private String objectKey;
    @Column(name = "original_filename", nullable = false, length = 255)
    private String originalFilename;
    @Column(name = "content_type", nullable = false, length = 100)
    private String contentType;
    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;
    @Column(name = "sha256", nullable = false, length = 64)
    private String sha256;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DocumentStatus status;

    protected StoredDocument() {}
    public StoredDocument(String ownerType, UUID ownerId, String category, String objectKey, String originalFilename,
                          String contentType, long sizeBytes, String sha256, DocumentStatus status) {
        this.ownerType = ownerType; this.ownerId = ownerId; this.category = category; this.objectKey = objectKey;
        this.originalFilename = originalFilename; this.contentType = contentType; this.sizeBytes = sizeBytes;
        this.sha256 = sha256; this.status = status;
    }
    public String getOwnerType() { return ownerType; }
    public UUID getOwnerId() { return ownerId; }
    public String getCategory() { return category; }
    public String getObjectKey() { return objectKey; }
    public String getOriginalFilename() { return originalFilename; }
    public String getContentType() { return contentType; }
    public long getSizeBytes() { return sizeBytes; }
    public String getSha256() { return sha256; }
    public DocumentStatus getStatus() { return status; }
    public void delete() { status = DocumentStatus.DELETED; }
}
