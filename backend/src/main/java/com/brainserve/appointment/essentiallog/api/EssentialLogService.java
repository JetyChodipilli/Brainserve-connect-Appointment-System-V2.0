package com.brainserve.appointment.essentiallog.api;

import com.brainserve.appointment.essentiallog.domain.EssentialLogRecord;
import com.brainserve.appointment.essentiallog.infrastructure.EssentialLogRecordRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;
import java.time.Instant;
import java.util.List;

@Service
public class EssentialLogService {
    private final EssentialLogRecordRepository records;

    public EssentialLogService(EssentialLogRecordRepository records) { this.records = records; }

    @Transactional
    public void record(String category, String eventType, String subjectType, String subjectId,
                       String referenceId, UUID actorUserId, UUID approverUserId, String status,
                       String title, String detail) {
        records.save(new EssentialLogRecord(category, eventType, subjectType, subjectId, referenceId,
                actorUserId, approverUserId, status, title, detail));
    }

    @Transactional(readOnly = true)
    public CursorPage list(Instant from, Instant to, String category, String status, String query,
                           Instant cursorTime, UUID cursorId, int size) {
        int boundedSize = Math.max(25, Math.min(size, 100));
        String normalizedCategory = blankToNull(category);
        String normalizedStatus = blankToNull(status);
        String normalizedQuery = blankToNull(query);
        List<EssentialLogRecord> found = records.findCursor(from, to, normalizedCategory, normalizedStatus,
                normalizedQuery, cursorTime, cursorId, PageRequest.of(0, boundedSize + 1));
        boolean hasMore = found.size() > boundedSize;
        List<View> items = found.stream().limit(boundedSize).map(View::from).toList();
        View last = items.isEmpty() ? null : items.get(items.size() - 1);
        return new CursorPage(items, last, hasMore,
                records.countFiltered(from, to, normalizedCategory, normalizedStatus, normalizedQuery));
    }

    private static String blankToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    public record CursorPage(List<View> items, View last, boolean hasMore, long total) {}

    public record View(UUID id, String category, String eventType, String subjectType, String subjectId,
                       String referenceId, UUID actorUserId, UUID approverUserId, String status,
                       String title, String detail, java.time.Instant occurredAt) {
        static View from(EssentialLogRecord value) {
            return new View(value.getId(), value.getCategory(), value.getEventType(), value.getSubjectType(),
                    value.getSubjectId(), value.getReferenceId(), value.getActorUserId(), value.getApproverUserId(),
                    value.getStatus(), value.getTitle(), value.getDetail(), value.getOccurredAt());
        }
    }
}
