package com.brainserve.appointment.essentiallog.infrastructure;

import com.brainserve.appointment.essentiallog.domain.EssentialLogRecord;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface EssentialLogRecordRepository
        extends JpaRepository<EssentialLogRecord, UUID> {

    @Query("""
            select e from EssentialLogRecord e
             where (cast(:from as Instant) is null or e.occurredAt >= :from)
               and (cast(:to as Instant) is null or e.occurredAt < :to)
               and (cast(:category as String) is null
                    or upper(e.category) = upper(cast(:category as String)))
               and (cast(:status as String) is null
                    or upper(e.status) = upper(cast(:status as String)))
               and (cast(:query as String) is null
                    or lower(e.title) like lower(concat('%', cast(:query as String), '%'))
                    or lower(e.detail) like lower(concat('%', cast(:query as String), '%'))
                    or lower(e.eventType) like lower(concat('%', cast(:query as String), '%'))
                    or lower(e.subjectType) like lower(concat('%', cast(:query as String), '%'))
                    or lower(e.subjectId) like lower(concat('%', cast(:query as String), '%')))
               and (cast(:cursorTime as Instant) is null
                    or e.occurredAt < :cursorTime
                    or (e.occurredAt = :cursorTime and e.id < :cursorId))
             order by e.occurredAt desc, e.id desc
            """)
    List<EssentialLogRecord> findCursor(
            @Param("from") Instant from,
            @Param("to") Instant to,
            @Param("category") String category,
            @Param("status") String status,
            @Param("query") String query,
            @Param("cursorTime") Instant cursorTime,
            @Param("cursorId") UUID cursorId,
            Pageable pageable
    );

    @Query("""
            select count(e) from EssentialLogRecord e
             where (cast(:from as Instant) is null or e.occurredAt >= :from)
               and (cast(:to as Instant) is null or e.occurredAt < :to)
               and (cast(:category as String) is null
                    or upper(e.category) = upper(cast(:category as String)))
               and (cast(:status as String) is null
                    or upper(e.status) = upper(cast(:status as String)))
               and (cast(:query as String) is null
                    or lower(e.title) like lower(concat('%', cast(:query as String), '%'))
                    or lower(e.detail) like lower(concat('%', cast(:query as String), '%'))
                    or lower(e.eventType) like lower(concat('%', cast(:query as String), '%'))
                    or lower(e.subjectType) like lower(concat('%', cast(:query as String), '%'))
                    or lower(e.subjectId) like lower(concat('%', cast(:query as String), '%')))
            """)
    long countFiltered(
            @Param("from") Instant from,
            @Param("to") Instant to,
            @Param("category") String category,
            @Param("status") String status,
            @Param("query") String query
    );
}