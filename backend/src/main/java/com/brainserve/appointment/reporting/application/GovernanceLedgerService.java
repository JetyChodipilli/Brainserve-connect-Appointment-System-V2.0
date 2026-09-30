package com.brainserve.appointment.reporting.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class GovernanceLedgerService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public GovernanceLedgerService(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void record(String actionType, String dataset, String targetRef, String outcome,
                       Map<String, ?> details) {
        jdbc.update("""
                insert into data_governance_log(action_type, dataset, target_ref, actor, outcome, details_json)
                values (?, ?, ?, ?, ?, cast(? as jsonb))
                """, normalize(actionType), normalize(dataset), normalize(targetRef), currentActor(),
                normalize(outcome), json(details));
    }

    @Transactional(readOnly = true)
    public LedgerPage recent(int requestedSize) {
        int size = Math.max(25, Math.min(requestedSize, 100));
        List<LedgerEntry> entries = jdbc.query("""
                select id, ledger_sequence, action_type, dataset, target_ref, actor, outcome,
                       details_json::text, occurred_at, previous_hash, entry_hash
                  from data_governance_log
                 order by ledger_sequence desc
                 limit ?
                """, (result, row) -> new LedgerEntry(
                result.getObject("id", UUID.class),
                result.getLong("ledger_sequence"),
                result.getString("action_type"),
                result.getString("dataset"),
                result.getString("target_ref"),
                result.getString("actor"),
                result.getString("outcome"),
                result.getString("details_json"),
                result.getTimestamp("occurred_at").toInstant(),
                result.getString("previous_hash"),
                result.getString("entry_hash")
        ), size);
        LedgerIntegrity integrity = verifyIntegrity();
        return new LedgerPage(entries, integrity.valid(), integrity.entriesChecked());
    }

    @Transactional(readOnly = true)
    public LedgerIntegrity verifyIntegrity() {
        return jdbc.query("""
                with chain as (
                    select ledger_sequence, previous_hash, entry_hash, canonical_payload,
                           lag(entry_hash) over (order by ledger_sequence) as expected_previous_hash
                      from data_governance_log
                )
                select count(*) as entries_checked,
                       coalesce(bool_and(
                           previous_hash = coalesce(expected_previous_hash, repeat('0', 64))
                           and entry_hash = encode(digest(previous_hash || '|' || canonical_payload, 'sha256'), 'hex')
                       ), true) as valid
                  from chain
                """, result -> {
            result.next();
            return new LedgerIntegrity(result.getBoolean("valid"), result.getLong("entries_checked"));
        });
    }

    private String currentActor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()
                && authentication.getName() != null && !authentication.getName().isBlank()) {
            return authentication.getName();
        }
        String correlationId = MDC.get("correlationId");
        return correlationId == null ? "system-retention-worker" : "system-retention-worker:" + correlationId;
    }

    private String json(Map<String, ?> details) {
        try {
            return objectMapper.writeValueAsString(details == null ? Map.of() : details);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Governance details must be JSON serializable", ex);
        }
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? "UNKNOWN" : value.trim();
    }

    public record LedgerEntry(UUID id, long sequence, String actionType, String dataset,
                              String targetRef, String actor, String outcome, String detailsJson,
                              Instant occurredAt, String previousHash, String entryHash) {}

    public record LedgerPage(List<LedgerEntry> items, boolean integrityValid, long entriesChecked) {}

    public record LedgerIntegrity(boolean valid, long entriesChecked) {}
}
