package com.brainserve.appointment.audit.infrastructure;

import com.brainserve.appointment.audit.domain.AuditEvent;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface AuditEventRepository
        extends JpaRepository<AuditEvent, UUID> {

    Page<AuditEvent> findByEventTypeContainingIgnoreCase(
            String eventType,
            Pageable pageable
    );

    default List<AuditEvent> findCursor(
            Instant from,
            Instant to,
            String outcome,
            String query,
            Instant cursorTime,
            UUID cursorId,
            Pageable pageable
    ) {
        return findCursorQuery(
                from == null,
                from,
                to == null,
                to,
                outcome == null,
                outcome,
                query == null,
                query,
                cursorTime == null,
                cursorTime,
                cursorId,
                pageable
        );
    }

    @Query("""
            select e
              from AuditEvent e
             where (:fromMissing = true
                    or e.occurredAt >= :from)
               and (:toMissing = true
                    or e.occurredAt < :to)
               and (:outcomeMissing = true
                    or upper(e.outcome) =
                       upper(cast(:outcome as String)))
               and (:queryMissing = true
                    or lower(e.eventType) like
                       lower(concat('%', cast(:query as String), '%'))
                    or lower(e.actorId) like
                       lower(concat('%', cast(:query as String), '%'))
                    or lower(e.targetType) like
                       lower(concat('%', cast(:query as String), '%'))
                    or lower(e.targetId) like
                       lower(concat('%', cast(:query as String), '%')))
               and (:cursorMissing = true
                    or e.occurredAt < :cursorTime
                    or (e.occurredAt = :cursorTime
                        and e.id < :cursorId))
             order by e.occurredAt desc, e.id desc
            """)
    List<AuditEvent> findCursorQuery(
            @Param("fromMissing") boolean fromMissing,
            @Param("from") Instant from,
            @Param("toMissing") boolean toMissing,
            @Param("to") Instant to,
            @Param("outcomeMissing") boolean outcomeMissing,
            @Param("outcome") String outcome,
            @Param("queryMissing") boolean queryMissing,
            @Param("query") String query,
            @Param("cursorMissing") boolean cursorMissing,
            @Param("cursorTime") Instant cursorTime,
            @Param("cursorId") UUID cursorId,
            Pageable pageable
    );

    default long countFiltered(
            Instant from,
            Instant to,
            String outcome,
            String query
    ) {
        return countFilteredQuery(
                from == null,
                from,
                to == null,
                to,
                outcome == null,
                outcome,
                query == null,
                query
        );
    }

    @Query("""
            select count(e)
              from AuditEvent e
             where (:fromMissing = true
                    or e.occurredAt >= :from)
               and (:toMissing = true
                    or e.occurredAt < :to)
               and (:outcomeMissing = true
                    or upper(e.outcome) =
                       upper(cast(:outcome as String)))
               and (:queryMissing = true
                    or lower(e.eventType) like
                       lower(concat('%', cast(:query as String), '%'))
                    or lower(e.actorId) like
                       lower(concat('%', cast(:query as String), '%'))
                    or lower(e.targetType) like
                       lower(concat('%', cast(:query as String), '%'))
                    or lower(e.targetId) like
                       lower(concat('%', cast(:query as String), '%')))
            """)
    long countFilteredQuery(
            @Param("fromMissing") boolean fromMissing,
            @Param("from") Instant from,
            @Param("toMissing") boolean toMissing,
            @Param("to") Instant to,
            @Param("outcomeMissing") boolean outcomeMissing,
            @Param("outcome") String outcome,
            @Param("queryMissing") boolean queryMissing,
            @Param("query") String query
    );
}