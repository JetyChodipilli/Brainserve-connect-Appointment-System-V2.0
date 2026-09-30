package com.brainserve.appointment.document.application;

import com.brainserve.appointment.audit.api.AuditService;
import com.brainserve.appointment.audit.api.RejectedSecurityAuditService;
import com.brainserve.appointment.departmenthr.api.DepartmentHrDirectory;
import com.brainserve.appointment.document.api.DocumentEvents;
import com.brainserve.appointment.document.api.ProfilePhotoStore;
import com.brainserve.appointment.document.domain.DocumentStatus;
import com.brainserve.appointment.document.domain.StoredDocument;
import com.brainserve.appointment.document.infrastructure.ClamAvScanner;
import com.brainserve.appointment.document.infrastructure.StoredDocumentRepository;
import com.brainserve.appointment.employee.api.EmployeeDirectory;
import com.brainserve.appointment.iam.api.StaffCommunicationDirectory;
import com.brainserve.appointment.shared.application.BusinessException;
import com.brainserve.appointment.visitor.api.VisitorDirectory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class DocumentService implements ProfilePhotoStore {
    private static final Set<String> ALLOWED_TYPES = Set.of("image/jpeg", "image/png", "application/pdf");
    private static final String CEO = "ROLE_CEO";
    private static final String HR = "ROLE_HR_ADMIN";
    private final StoredDocumentRepository documents;
    private final ClamAvScanner scanner;
    private final S3Client s3;
    private final S3Presigner presigner;
    private final AuditService audit;
    private final ApplicationEventPublisher events;
    private final StaffCommunicationDirectory staff;
    private final EmployeeDirectory employees;
    private final DepartmentHrDirectory departmentHrs;
    private final VisitorDirectory visitors;
    private final RejectedSecurityAuditService rejectedAudit;
    private final String bucket;
    private final long maxBytes;
    private final long urlMinutes;

    public DocumentService(StoredDocumentRepository documents, ClamAvScanner scanner, S3Client s3, S3Presigner presigner,
                           AuditService audit, ApplicationEventPublisher events,
                           StaffCommunicationDirectory staff, EmployeeDirectory employees,
                           DepartmentHrDirectory departmentHrs, VisitorDirectory visitors,
                           RejectedSecurityAuditService rejectedAudit,
                           @Value("${brainserve.document.bucket}") String bucket,
                           @Value("${brainserve.document.max-bytes}") long maxBytes,
                           @Value("${brainserve.document.download-url-minutes}") long urlMinutes) {
        this.documents = documents; this.scanner = scanner; this.s3 = s3; this.presigner = presigner;
        this.audit = audit; this.events = events; this.staff = staff; this.employees = employees;
        this.departmentHrs = departmentHrs; this.visitors = visitors; this.rejectedAudit = rejectedAudit;
        this.bucket = bucket; this.maxBytes = maxBytes;
        this.urlMinutes = Math.max(1, Math.min(urlMinutes, 15));
    }

    @Transactional
    public StoredDocument upload(UUID actorUserId, String ownerType, UUID ownerId, String category, MultipartFile file) {
        requireOwnerAccess(actorUserId, ownerType, ownerId, true);
        return upload(ownerType, ownerId, category, file);
    }

    private StoredDocument upload(String ownerType, UUID ownerId, String category, MultipartFile file) {
        validate(file);
        try {
            byte[] bytes = file.getBytes();
            validateSignature(file.getContentType(), bytes);
            scanner.assertClean(bytes);
            String objectKey = ownerType.toLowerCase() + "/" + ownerId + "/" + UUID.randomUUID();
            String digest = sha256(bytes);
            s3.putObject(PutObjectRequest.builder().bucket(bucket).key(objectKey).contentType(file.getContentType())
                    .metadata(java.util.Map.of("sha256", digest)).build(), RequestBody.fromBytes(bytes));
            StoredDocument stored;
            try {
                stored = documents.saveAndFlush(new StoredDocument(ownerType.toUpperCase(), ownerId, category.toUpperCase(),
                        objectKey, safeFilename(file.getOriginalFilename()), file.getContentType(), bytes.length,
                        digest, DocumentStatus.CLEAN));
            } catch (RuntimeException ex) {
                s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(objectKey).build());
                throw ex;
            }
            audit.record("DOCUMENT_UPLOAD", ownerType.toUpperCase(), ownerId.toString(), "{\"category\":\"" + category.toUpperCase() + "\"}");
            events.publishEvent(new DocumentEvents.DocumentUploaded(stored.getId(), ownerType.toUpperCase(), ownerId, category.toUpperCase(), Instant.now()));
            return stored;
        } catch (IOException ex) {
            throw new BusinessException("DOCUMENT_READ_FAILED", "The uploaded file could not be read", HttpStatus.BAD_REQUEST);
        }
    }

    @Transactional(readOnly = true)
    private StoredDocument get(UUID id) {
        return documents.findById(id).filter(value -> value.getStatus() == DocumentStatus.CLEAN)
                .orElseThrow(() -> new BusinessException("DOCUMENT_NOT_FOUND", "Document was not found", HttpStatus.NOT_FOUND));
    }

    @Transactional(readOnly = true)
    public List<StoredDocument> list(UUID actorUserId, String ownerType, UUID ownerId) {
        requireOwnerAccess(actorUserId, ownerType, ownerId, false);
        return documents.findByOwnerTypeAndOwnerIdOrderByCreatedAtDesc(ownerType.toUpperCase(), ownerId).stream()
                .filter(value -> value.getStatus() == DocumentStatus.CLEAN).toList();
    }

    public String createDownloadUrl(UUID actorUserId, UUID id) {
        StoredDocument document = get(id);
        requireOwnerAccess(actorUserId, document.getOwnerType(), document.getOwnerId(), false);
        var request = GetObjectRequest.builder().bucket(bucket).key(document.getObjectKey())
                .responseContentDisposition("attachment; filename=\"" + safeFilename(document.getOriginalFilename()) + "\"").build();
        String url = presigner.presignGetObject(GetObjectPresignRequest.builder().signatureDuration(Duration.ofMinutes(urlMinutes))
                .getObjectRequest(request).build()).url().toString();
        audit.record("DOCUMENT_READ", document.getOwnerType(), document.getOwnerId().toString(), "{\"documentId\":\"" + id + "\"}");
        return url;
    }

    @Override
    @Transactional
    public ProfilePhoto save(UUID userId, MultipartFile file) {
        if (file.getContentType() == null || !Set.of("image/jpeg", "image/png").contains(file.getContentType())) {
            throw new BusinessException("PROFILE_PHOTO_TYPE_NOT_ALLOWED",
                    "Profile photos must be JPEG or PNG images", HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        }
        StoredDocument stored = upload("ACCOUNT", userId, "PROFILE_PHOTO", file);
        documents.findByOwnerTypeAndOwnerIdOrderByCreatedAtDesc("ACCOUNT", userId).stream()
                .filter(value -> !value.getId().equals(stored.getId()))
                .filter(value -> value.getStatus() == DocumentStatus.CLEAN)
                .filter(value -> "PROFILE_PHOTO".equals(value.getCategory()))
                .forEach(value -> {
                    try { s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(value.getObjectKey()).build()); }
                    catch (RuntimeException ignored) { /* the database still retires the old photo */ }
                    value.delete();
                });
        return toProfilePhoto(stored);
    }

    @Override
    @Transactional(readOnly = true)
    public java.util.Optional<ProfilePhoto> current(UUID userId) {
        return documents.findFirstByOwnerTypeAndOwnerIdAndCategoryAndStatusOrderByCreatedAtDesc(
                "ACCOUNT", userId, "PROFILE_PHOTO", DocumentStatus.CLEAN).map(this::toProfilePhoto);
    }

    @Override
    @Transactional(readOnly = true)
    public ProfilePhotoAccess createAccess(UUID documentId) {
        StoredDocument document = get(documentId);
        if (!"ACCOUNT".equals(document.getOwnerType()) || !"PROFILE_PHOTO".equals(document.getCategory())) {
            throw new BusinessException("PROFILE_PHOTO_NOT_FOUND", "Profile photo was not found", HttpStatus.NOT_FOUND);
        }
        var request = GetObjectRequest.builder().bucket(bucket).key(document.getObjectKey())
                .responseContentDisposition("inline; filename=\"" + safeFilename(document.getOriginalFilename()) + "\"").build();
        String url = presigner.presignGetObject(GetObjectPresignRequest.builder()
                .signatureDuration(Duration.ofMinutes(urlMinutes)).getObjectRequest(request).build()).url().toString();
        return new ProfilePhotoAccess(url, Instant.now().plus(Duration.ofMinutes(urlMinutes)));
    }

    @Transactional
    public void delete(UUID actorUserId, UUID id) {
        StoredDocument document = get(id);
        requireOwnerAccess(actorUserId, document.getOwnerType(), document.getOwnerId(), true);
        s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(document.getObjectKey()).build());
        document.delete();
        audit.record("DOCUMENT_DELETE", document.getOwnerType(), document.getOwnerId().toString(), "{\"documentId\":\"" + id + "\"}");
    }

    private void validate(MultipartFile file) {
        if (file.isEmpty()) throw new BusinessException("EMPTY_DOCUMENT", "A non-empty file is required", HttpStatus.BAD_REQUEST);
        if (file.getSize() > maxBytes) throw new BusinessException("DOCUMENT_TOO_LARGE", "File exceeds the configured size limit", HttpStatus.PAYLOAD_TOO_LARGE);
        if (file.getContentType() == null || !ALLOWED_TYPES.contains(file.getContentType()))
            throw new BusinessException("DOCUMENT_TYPE_NOT_ALLOWED", "Only JPEG, PNG and PDF files are accepted", HttpStatus.UNSUPPORTED_MEDIA_TYPE);
    }

    private void validateSignature(String contentType, byte[] bytes) {
        boolean matches = switch (contentType) {
            case "image/jpeg" -> startsWith(bytes, new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff});
            case "image/png" -> startsWith(bytes,
                    new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a});
            case "application/pdf" -> startsWith(bytes, new byte[]{0x25, 0x50, 0x44, 0x46, 0x2d});
            default -> false;
        };
        if (!matches) {
            throw new BusinessException("DOCUMENT_CONTENT_MISMATCH",
                    "The file content does not match its declared type", HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        }
    }

    private boolean startsWith(byte[] value, byte[] prefix) {
        if (value.length < prefix.length) return false;
        for (int index = 0; index < prefix.length; index++) {
            if (value[index] != prefix[index]) return false;
        }
        return true;
    }

    private void requireOwnerAccess(UUID actorUserId, String ownerType, UUID ownerId, boolean write) {
        String normalizedOwnerType = ownerType.toUpperCase();
        var actor = staff.requireActive(actorUserId);
        boolean permitted;
        try {
            permitted = switch (normalizedOwnerType) {
                case "EMPLOYEE" -> employeeAccessPermitted(actor, ownerId, write);
                case "VISITOR" -> visitorAccessPermitted(actor, ownerId, write);
                default -> false;
            };
        } catch (BusinessException ignored) {
            permitted = false;
        }
        if (!permitted) denyAccess(normalizedOwnerType, ownerId, write);
    }

    private boolean employeeAccessPermitted(StaffCommunicationDirectory.StaffMember actor,
                                            UUID employeeId, boolean write) {
        employees.requireEmployee(employeeId);
        UUID departmentId = employees.departmentIdForEmployee(employeeId);
        if (actor.roles().contains(HR)) {
            return departmentHrs.activeForUser(actor.userId())
                    .map(assignment -> assignment.departmentId().equals(departmentId))
                    .orElse(false);
        }
        return !write && actor.roles().contains(CEO);
    }

    private boolean visitorAccessPermitted(StaffCommunicationDirectory.StaffMember actor,
                                           UUID visitorId, boolean write) {
        visitors.requireVisitor(visitorId);
        // Visitor identities currently have no trustworthy department owner.
        // Keep HR mutations and reads closed until that relationship exists;
        // the company CEO may read existing evidence but may not modify it.
        return !write && actor.roles().contains(CEO);
    }

    private void denyAccess(String ownerType, UUID ownerId, boolean write) {
        try {
            rejectedAudit.record("DOCUMENT_ACCESS_REJECTED", ownerType, ownerId.toString(),
                    "{\"operation\":\"" + (write ? "WRITE" : "READ") + "\"}");
        } catch (RuntimeException ignored) {
            // Access still fails closed if the secondary rejected-event audit is unavailable.
        }
        throw new BusinessException("DOCUMENT_NOT_FOUND", "Document was not found", HttpStatus.NOT_FOUND);
    }
    private String safeFilename(String value) {
        String name = value == null ? "document" : value.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).replaceAll("[^A-Za-z0-9._-]", "_");
        return name.isBlank() ? "document" : name.substring(0, Math.min(name.length(), 180));
    }
    private String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }

    private ProfilePhoto toProfilePhoto(StoredDocument value) {
        return new ProfilePhoto(value.getId(), value.getOriginalFilename(), value.getContentType(),
                value.getSizeBytes(), value.getCreatedAt());
    }
}
