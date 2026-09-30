package com.brainserve.appointment.audit.api;

import com.brainserve.appointment.audit.domain.AuditEvent;
import com.brainserve.appointment.audit.infrastructure.AuditEventRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/audit-events")
public class AuditController {
    private final AuditEventRepository events;
    public AuditController(AuditEventRepository events) { this.events = events; }

    @GetMapping
    @PreAuthorize("hasAuthority('AUDIT_VIEW')")
    CursorResponse list(@RequestParam(required = false) Instant from,
                        @RequestParam(required = false) Instant to,
                        @RequestParam(required = false) String outcome,
                        @RequestParam(required = false) String query,
                        @RequestParam(required = false) String cursor,
                        @RequestParam(defaultValue = "50") int size) {
        int boundedSize = Math.max(25, Math.min(size, 100));
        CursorValue cursorValue = decode(cursor);
        String normalizedQuery = blankToNull(query);
        String normalizedOutcome = blankToNull(outcome);
        List<AuditEvent> found = events.findCursor(from, to, normalizedOutcome, normalizedQuery,
                cursorValue == null ? null : cursorValue.occurredAt(),
                cursorValue == null ? null : cursorValue.id(), PageRequest.of(0, boundedSize + 1));
        boolean hasMore = found.size() > boundedSize;
        List<AuditEventResponse> items = found.stream().limit(boundedSize).map(value ->
                new AuditEventResponse(value.getId(), value.getOccurredAt(), value.getActorId(), value.getEventType(),
                        value.getTargetType(), value.getTargetId(), value.getOutcome(), value.getCorrelationId())).toList();
        AuditEventResponse last = items.isEmpty() ? null : items.get(items.size() - 1);
        return new CursorResponse(items, hasMore && last != null ? encode(last.occurredAt(), last.id()) : null,
                hasMore, events.countFiltered(from, to, normalizedOutcome, normalizedQuery));
    }

    private static String blankToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private static String encode(Instant occurredAt, UUID id) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                (occurredAt + "|" + id).getBytes(StandardCharsets.UTF_8));
    }
    private static CursorValue decode(String cursor) {
        if (cursor == null || cursor.isBlank()) return null;
        try {
            String[] values = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split("\\|", 2);
            return new CursorValue(Instant.parse(values[0]), UUID.fromString(values[1]));
        } catch (RuntimeException ex) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "Invalid audit cursor");
        }
    }

    public record AuditEventResponse(java.util.UUID id, java.time.Instant occurredAt, String actorId, String eventType,
                                     String targetType, String targetId, String outcome, String correlationId) {}
    public record CursorResponse(List<AuditEventResponse> items, String nextCursor, boolean hasMore, long total) {}
    private record CursorValue(Instant occurredAt, UUID id) {}
}
