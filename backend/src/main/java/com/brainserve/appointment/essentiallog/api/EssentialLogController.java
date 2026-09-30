package com.brainserve.appointment.essentiallog.api;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/logs")
public class EssentialLogController {
    private final EssentialLogService service;
    public EssentialLogController(EssentialLogService service) { this.service = service; }

    @GetMapping
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    CursorResponse list(@RequestParam(required = false) Instant from,
                        @RequestParam(required = false) Instant to,
                        @RequestParam(required = false) String category,
                        @RequestParam(required = false) String status,
                        @RequestParam(required = false) String query,
                        @RequestParam(required = false) String cursor,
                        @RequestParam(defaultValue = "50") int size) {
        CursorValue cursorValue = decode(cursor);
        EssentialLogService.CursorPage result = service.list(from, to, category, status, query,
                cursorValue == null ? null : cursorValue.occurredAt(),
                cursorValue == null ? null : cursorValue.id(), size);
        String nextCursor = result.hasMore() && result.last() != null
                ? encode(result.last().occurredAt(), result.last().id()) : null;
        return new CursorResponse(result.items(), nextCursor, result.hasMore(), result.total());
    }

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
                    org.springframework.http.HttpStatus.BAD_REQUEST, "Invalid essential-log cursor");
        }
    }
    public record CursorResponse(List<EssentialLogService.View> items, String nextCursor,
                                 boolean hasMore, long total) {}
    private record CursorValue(Instant occurredAt, UUID id) {}
}
