package com.brainserve.appointment.document.infrastructure;

import com.brainserve.appointment.document.domain.StoredDocument;
import com.brainserve.appointment.document.domain.DocumentStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StoredDocumentRepository extends JpaRepository<StoredDocument, UUID> {
    List<StoredDocument> findByOwnerTypeAndOwnerIdOrderByCreatedAtDesc(String ownerType, UUID ownerId);
    Optional<StoredDocument> findFirstByOwnerTypeAndOwnerIdAndCategoryAndStatusOrderByCreatedAtDesc(
            String ownerType, UUID ownerId, String category, DocumentStatus status);
}
