package com.brainserve.appointment.document.api;

import java.time.Instant;
import java.util.UUID;

public final class DocumentEvents {
    private DocumentEvents() {}
    public record DocumentUploaded(UUID documentId, String ownerType, UUID ownerId, String category, Instant occurredAt) {}
}
