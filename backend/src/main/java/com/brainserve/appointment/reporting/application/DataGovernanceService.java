package com.brainserve.appointment.reporting.application;

import com.brainserve.appointment.audit.api.AuditService;
import com.brainserve.appointment.shared.application.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class DataGovernanceService {
    private static final DateTimeFormatter PARTITION_MONTH = DateTimeFormatter.ofPattern("yyyy_MM");
    private static final Set<String> DISPOSAL_ACTIONS = Set.of("DELETE", "ANONYMIZE");
    private static final Set<String> HOLD_KINDS = Set.of("LEGAL_HOLD", "ACTIVE_INVESTIGATION");
    private static final Set<String> HOLD_SCOPES = Set.of("DATASET", "PARTITION", "SUBJECT");
    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final GovernanceLedgerService ledger;
    private final ZoneId officeZone;

    public DataGovernanceService(JdbcTemplate jdbc, AuditService audit, GovernanceLedgerService ledger,
                                 @Value("${brainserve.appointment.office-zone:Asia/Kolkata}") String officeZone) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.ledger = ledger;
        this.officeZone = ZoneId.of(officeZone);
    }

    @Transactional(readOnly = true)
    public List<RetentionPolicy> policies() {
        return jdbc.query("""
                select dataset, hot_days, warm_months, archive_years, disposal_action,
                       enabled, updated_at, updated_by
                  from data_retention_policy order by dataset
                """, (result, row) -> new RetentionPolicy(result.getString("dataset"), result.getInt("hot_days"),
                result.getInt("warm_months"), result.getInt("archive_years"),
                result.getString("disposal_action"), result.getBoolean("enabled"),
                result.getTimestamp("updated_at").toInstant(), result.getString("updated_by")));
    }

    @Transactional
    public RetentionPolicy update(String dataset, int hotDays, int warmMonths, int archiveYears,
                                  String disposalAction, boolean enabled, String actor) {
        String normalizedDataset = normalize(dataset);
        String normalizedDisposal = normalize(disposalAction);
        if (hotDays < 1 || hotDays > 3650 || warmMonths < 1 || warmMonths > 240
                || archiveYears < 1 || archiveYears > 25) {
            throw new BusinessException("INVALID_RETENTION_POLICY",
                    "Retention values are outside the supported safety limits", HttpStatus.BAD_REQUEST);
        }
        if (hotDays > warmMonths * 31L || warmMonths > archiveYears * 12L) {
            throw new BusinessException("INVALID_RETENTION_SEQUENCE",
                    "Hot storage must end before warm storage, and warm storage before archive expiry",
                    HttpStatus.BAD_REQUEST);
        }
        if (!DISPOSAL_ACTIONS.contains(normalizedDisposal)) {
            throw new BusinessException("INVALID_DISPOSAL_ACTION",
                    "Disposal action must be DELETE or ANONYMIZE", HttpStatus.BAD_REQUEST);
        }
        int changed = jdbc.update("""
                update data_retention_policy set hot_days = ?, warm_months = ?, archive_years = ?,
                       disposal_action = ?, enabled = ?, updated_at = now(), updated_by = ?
                 where dataset = ?
                """, hotDays, warmMonths, archiveYears, normalizedDisposal, enabled, actor, normalizedDataset);
        if (changed == 0) throw new BusinessException("RETENTION_POLICY_NOT_FOUND",
                "The retention policy was not found", HttpStatus.NOT_FOUND);
        audit.record("RETENTION_POLICY_UPDATED", "DATASET", normalizedDataset,
                "{\"hotDays\":" + hotDays + ",\"warmMonths\":" + warmMonths
                        + ",\"archiveYears\":" + archiveYears + ",\"enabled\":" + enabled + "}");
        ledger.record("RETENTION_POLICY_UPDATED", normalizedDataset, normalizedDataset, "SUCCESS", Map.of(
                "hotDays", hotDays, "warmMonths", warmMonths, "archiveYears", archiveYears,
                "disposalAction", normalizedDisposal, "enabled", enabled
        ));
        return policies().stream().filter(value -> value.dataset().equals(normalizedDataset))
                .findFirst().orElseThrow();
    }

    @Transactional(readOnly = true)
    public List<ArchiveManifest> manifests() {
        return jdbc.query("""
                select dataset, partition_name, period_start, period_end, row_count, status,
                       object_key, checksum_sha256, encryption_algorithm, encryption_key_version,
                       object_size_bytes, verified_at, restore_tested_at, verified_row_count,
                       database_removed_at, disposed_at, backup_expires_at, last_error,
                       discovered_at, archived_at,
                       exists (
                           select 1 from data_legal_hold hold
                            where hold.released_at is null
                              and hold.dataset = data_archive_manifest.dataset
                              and (hold.scope_type in ('DATASET', 'SUBJECT')
                                   or (hold.scope_type = 'PARTITION'
                                       and hold.scope_ref = data_archive_manifest.partition_name))
                       ) as hold_blocked
                  from data_archive_manifest
                 order by period_start desc, dataset
                 limit 500
                """, (result, row) -> new ArchiveManifest(result.getString("dataset"),
                result.getString("partition_name"), result.getObject("period_start", LocalDate.class),
                result.getObject("period_end", LocalDate.class), result.getLong("row_count"),
                result.getString("status"), result.getString("object_key"), result.getString("checksum_sha256"),
                result.getString("encryption_algorithm"), result.getString("encryption_key_version"),
                result.getLong("object_size_bytes"),
                instant(result, "verified_at"), instant(result, "restore_tested_at"),
                (Long) result.getObject("verified_row_count"), instant(result, "database_removed_at"),
                instant(result, "disposed_at"), instant(result, "backup_expires_at"),
                result.getString("last_error"), result.getBoolean("hold_blocked"),
                result.getTimestamp("discovered_at").toInstant(),
                instant(result, "archived_at")));
    }

    @Transactional(readOnly = true)
    public List<LegalHold> legalHolds() {
        return jdbc.query("""
                select id, dataset, hold_kind, scope_type, scope_ref, case_reference, reason,
                       review_on, placed_by, placed_at, released_by, released_at, release_reason
                  from data_legal_hold
                 order by (released_at is null) desc, placed_at desc
                 limit 500
                """, (result, row) -> new LegalHold(
                result.getObject("id", UUID.class), result.getString("dataset"),
                result.getString("hold_kind"), result.getString("scope_type"),
                result.getString("scope_ref"), result.getString("case_reference"),
                result.getString("reason"), result.getObject("review_on", LocalDate.class),
                result.getString("placed_by"), result.getTimestamp("placed_at").toInstant(),
                result.getString("released_by"), instant(result, "released_at"),
                result.getString("release_reason")
        ));
    }

    @Transactional
    public LegalHold placeHold(String dataset, String holdKind, String scopeType, String scopeRef,
                               String caseReference, String reason, LocalDate reviewOn, String actor) {
        String normalizedDataset = normalize(dataset);
        String normalizedKind = normalize(holdKind);
        String normalizedScope = normalize(scopeType);
        String normalizedRef = blankToNull(scopeRef);
        if (!HOLD_KINDS.contains(normalizedKind) || !HOLD_SCOPES.contains(normalizedScope)) {
            throw new BusinessException("INVALID_LEGAL_HOLD",
                    "Hold kind or scope is not supported", HttpStatus.BAD_REQUEST);
        }
        Boolean governedDataset = jdbc.queryForObject(
                "select exists (select 1 from data_retention_policy where dataset = ?)",
                Boolean.class, normalizedDataset);
        if (!Boolean.TRUE.equals(governedDataset)) {
            throw new BusinessException("RETENTION_POLICY_NOT_FOUND",
                    "A legal hold can be placed only on a governed dataset", HttpStatus.NOT_FOUND);
        }
        if (normalizedScope.equals("DATASET")) normalizedRef = null;
        if (!normalizedScope.equals("DATASET") && normalizedRef == null) {
            throw new BusinessException("LEGAL_HOLD_SCOPE_REQUIRED",
                    "Partition and subject holds require a scope reference", HttpStatus.BAD_REQUEST);
        }
        if (normalizedScope.equals("PARTITION")) {
            Boolean knownPartition = jdbc.queryForObject("""
                    select exists (
                        select 1 from data_archive_manifest
                         where dataset = ? and partition_name = ?
                    )
                    """, Boolean.class, normalizedDataset, normalizedRef);
            if (!Boolean.TRUE.equals(knownPartition)) {
                throw new BusinessException("ARCHIVE_PARTITION_NOT_FOUND",
                        "The archive partition does not exist for this dataset", HttpStatus.NOT_FOUND);
            }
        }
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into data_legal_hold(id, dataset, hold_kind, scope_type, scope_ref,
                                            case_reference, reason, review_on, placed_by)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, normalizedDataset, normalizedKind, normalizedScope, normalizedRef,
                required(caseReference, "Case reference"), required(reason, "Hold reason"), reviewOn, actor);
        jdbc.update("""
                update data_archive_manifest set status = 'HOLD_BLOCKED'
                 where dataset = ?
                   and status in ('ARCHIVE_ELIGIBLE', 'FAILED')
                   and (? in ('DATASET', 'SUBJECT') or partition_name = ?)
                """, normalizedDataset, normalizedScope, normalizedRef);
        ledger.record("DATA_HOLD_PLACED", normalizedDataset,
                normalizedRef == null ? normalizedDataset : normalizedRef, "SUCCESS", Map.of(
                        "holdId", id, "holdKind", normalizedKind, "scopeType", normalizedScope,
                        "caseReference", caseReference, "reviewOn", reviewOn == null ? "" : reviewOn.toString()
                ));
        audit.record("DATA_HOLD_PLACED", "DATASET", normalizedDataset,
                "{\"holdId\":\"" + id + "\",\"caseReference\":\"" + escape(caseReference) + "\"}");
        return legalHolds().stream().filter(value -> value.id().equals(id)).findFirst().orElseThrow();
    }

    @Transactional
    public LegalHold releaseHold(UUID holdId, String releaseReason, String actor) {
        LegalHold existing = legalHolds().stream().filter(value -> value.id().equals(holdId))
                .findFirst().orElseThrow(() -> new BusinessException("LEGAL_HOLD_NOT_FOUND",
                        "The legal hold was not found", HttpStatus.NOT_FOUND));
        if (existing.releasedAt() != null) {
            throw new BusinessException("LEGAL_HOLD_ALREADY_RELEASED",
                    "The legal hold was already released", HttpStatus.CONFLICT);
        }
        jdbc.update("""
                update data_legal_hold
                   set released_by = ?, released_at = now(), release_reason = ?
                 where id = ? and released_at is null
                """, actor, required(releaseReason, "Release reason"), holdId);
        jdbc.update("""
                update data_archive_manifest set status = 'ARCHIVE_ELIGIBLE', last_error = null
                 where dataset = ? and status = 'HOLD_BLOCKED'
                   and not exists (
                       select 1 from data_legal_hold active
                        where active.released_at is null
                          and active.dataset = data_archive_manifest.dataset
                          and (active.scope_type in ('DATASET', 'SUBJECT')
                               or (active.scope_type = 'PARTITION'
                                   and active.scope_ref = data_archive_manifest.partition_name))
                   )
                """, existing.dataset());
        ledger.record("DATA_HOLD_RELEASED", existing.dataset(),
                existing.scopeRef() == null ? existing.dataset() : existing.scopeRef(), "SUCCESS", Map.of(
                        "holdId", holdId, "releaseReason", releaseReason,
                        "caseReference", existing.caseReference()
                ));
        audit.record("DATA_HOLD_RELEASED", "DATASET", existing.dataset(),
                "{\"holdId\":\"" + holdId + "\"}");
        return legalHolds().stream().filter(value -> value.id().equals(holdId)).findFirst().orElseThrow();
    }

    @Transactional(readOnly = true)
    public GovernanceOverview overview() {
        Map<String, Long> statuses = jdbc.query("""
                select status, count(*) as total from data_archive_manifest group by status
                """, result -> {
            java.util.LinkedHashMap<String, Long> values = new java.util.LinkedHashMap<>();
            while (result.next()) values.put(result.getString("status"), result.getLong("total"));
            return values;
        });
        long activeHolds = jdbc.queryForObject(
                "select count(*) from data_legal_hold where released_at is null", Long.class);
        long backupPending = jdbc.queryForObject(
                "select count(*) from backup_expiry_register where status = 'PENDING'", Long.class);
        GovernanceLedgerService.LedgerIntegrity integrity = ledger.verifyIntegrity();
        return new GovernanceOverview(statuses, activeHolds, backupPending,
                integrity.valid(), integrity.entriesChecked());
    }

    @Transactional(readOnly = true)
    public boolean hasActiveHold(String dataset, String partitionOrSubjectRef) {
        Boolean held = jdbc.queryForObject("""
                select exists (
                    select 1 from data_legal_hold
                     where released_at is null and dataset = ?
                       and (scope_type in ('DATASET', 'SUBJECT')
                            or (scope_type = 'PARTITION' and scope_ref = ?))
                )
                """, Boolean.class, normalize(dataset), partitionOrSubjectRef);
        return Boolean.TRUE.equals(held);
    }

    @Scheduled(cron = "${brainserve.reporting.retention-cron:0 40 2 * * *}",
            zone = "${brainserve.appointment.office-zone:Asia/Kolkata}")
    @Transactional
    public void discoverArchiveEligiblePartitions() {
        inspect("AUDIT", "audit_event_history");
        inspect("EMPLOYEE", "employee_history_event");
        inspect("VISITOR", "visitor_history_event");
        inspect("APPOINTMENT", "appointment_history_event");
        inspect("ESSENTIAL_LOG", "essential_log_history");
        inspect("VISITOR_CHECKPOINT", "visitor_checkpoint_event");
        inspect("WORKBOARD_ACTIVITY", "workboard_activity_event");
        applyHotRetention();
    }

    private void applyHotRetention() {
        Integer auditDays = hotDays("AUDIT");
        if (auditDays != null) {
            List<UUID> deleted = jdbc.query("""
                    delete from audit_event source where source.id in (
                      select hot.id from audit_event hot
                       where hot.occurred_at < now() - (? * interval '1 day')
                         and exists (select 1 from audit_event_history history
                                      where history.id = hot.id and history.occurred_at = hot.occurred_at)
                         and not exists (
                             select 1 from data_legal_hold hold
                              where hold.released_at is null and hold.dataset = 'AUDIT'
                                and (hold.scope_type = 'DATASET'
                                     or (hold.scope_type = 'SUBJECT' and hold.scope_ref = hot.id::text)
                                     or (hold.scope_type = 'PARTITION'
                                         and hold.scope_ref = 'audit_event_history_'
                                             || to_char(hot.occurred_at, 'YYYY_MM')))
                         )
                       order by hot.occurred_at limit 10000)
                    returning id
                    """, (result, row) -> result.getObject(1, UUID.class), auditDays);
            for (UUID id : deleted) {
                ledger.record("HOT_RECORD_REMOVED", "AUDIT", id.toString(), "SUCCESS",
                        Map.of("historyPreserved", true, "hotDays", auditDays));
            }
            if (!deleted.isEmpty()) audit.record("HOT_AUDIT_RETENTION_APPLIED", "DATASET", "AUDIT",
                    "{\"deletedHotRows\":" + deleted.size() + ",\"historyPreserved\":true}");
        }
        Integer checkpointDays = hotDays("VISITOR_CHECKPOINT");
        if (checkpointDays != null) {
            List<UUID> deleted = jdbc.query("""
                    delete from visit_access_record source where source.id in (
                      select access.id from visit_access_record access
                       where access.checked_out_at < now() - (? * interval '1 day')
                         and exists (select 1 from visitor_checkpoint_event history
                                      where history.access_record_id = access.id and history.event_type = 'CHECKED_OUT')
                         and not exists (
                             select 1 from data_legal_hold hold
                              where hold.released_at is null and hold.dataset = 'VISITOR_CHECKPOINT'
                                and (hold.scope_type = 'DATASET'
                                     or (hold.scope_type = 'SUBJECT' and hold.scope_ref = access.id::text)
                                     or (hold.scope_type = 'PARTITION'
                                         and hold.scope_ref = 'visitor_checkpoint_event_'
                                             || to_char(access.checked_out_at, 'YYYY_MM')))
                         )
                       order by access.checked_out_at limit 10000)
                    returning id
                    """, (result, row) -> result.getObject(1, UUID.class), checkpointDays);
            for (UUID id : deleted) {
                ledger.record("HOT_RECORD_REMOVED", "VISITOR_CHECKPOINT", id.toString(), "SUCCESS",
                        Map.of("historyPreserved", true, "hotDays", checkpointDays));
            }
            if (!deleted.isEmpty()) audit.record("HOT_CHECKPOINT_RETENTION_APPLIED",
                    "DATASET", "VISITOR_CHECKPOINT",
                    "{\"deletedHotRows\":" + deleted.size() + ",\"historyPreserved\":true}");
        }
        Integer essentialDays = hotDays("ESSENTIAL_LOG");
        if (essentialDays != null) {
            List<UUID> deleted = jdbc.query("""
                    delete from essential_log_record source where source.id in (
                      select hot.id from essential_log_record hot
                       where hot.occurred_at < now() - (? * interval '1 day')
                         and exists (select 1 from essential_log_history history
                                      where history.subject_id = hot.id)
                         and not exists (
                             select 1 from data_legal_hold hold
                              where hold.released_at is null and hold.dataset = 'ESSENTIAL_LOG'
                                and (hold.scope_type = 'DATASET'
                                     or (hold.scope_type = 'SUBJECT' and hold.scope_ref = hot.id::text)
                                     or (hold.scope_type = 'PARTITION'
                                         and hold.scope_ref = 'essential_log_history_'
                                             || to_char(hot.occurred_at, 'YYYY_MM')))
                         )
                       order by hot.occurred_at limit 10000)
                    returning id
                    """, (result, row) -> result.getObject(1, UUID.class), essentialDays);
            for (UUID id : deleted) {
                ledger.record("HOT_RECORD_REMOVED", "ESSENTIAL_LOG", id.toString(), "SUCCESS",
                        Map.of("historyPreserved", true, "hotDays", essentialDays));
            }
            if (!deleted.isEmpty()) audit.record("HOT_ESSENTIAL_LOG_RETENTION_APPLIED",
                    "DATASET", "ESSENTIAL_LOG",
                    "{\"deletedHotRows\":" + deleted.size() + ",\"historyPreserved\":true}");
        }
    }

    private Integer hotDays(String dataset) {
        return jdbc.query("select hot_days from data_retention_policy where dataset = ? and enabled",
                result -> result.next() ? result.getInt(1) : null, dataset);
    }

    private void inspect(String dataset, String parent) {
        Integer warmMonths = jdbc.query("select warm_months from data_retention_policy where dataset = ? and enabled",
                result -> result.next() ? result.getInt(1) : null, dataset);
        if (warmMonths == null) return;
        List<String> partitions = jdbc.query("""
                select child.relname
                  from pg_inherits
                  join pg_class parent on pg_inherits.inhparent = parent.oid
                  join pg_class child on pg_inherits.inhrelid = child.oid
                 where parent.relname = ?
                """, (result, row) -> result.getString(1), parent);
        for (String partition : partitions) {
            YearMonth month = partitionMonth(parent, partition);
            if (month == null) continue;
            LocalDate from = month.atDay(1);
            LocalDate to = month.plusMonths(1).atDay(1);
            boolean eligible = !to.isAfter(LocalDate.now(officeZone).minusMonths(warmMonths));
            String status = eligible
                    ? (hasActiveHold(dataset, partition) ? "HOLD_BLOCKED" : "ARCHIVE_ELIGIBLE")
                    : "WARM";
            long count = ((Number) jdbc.queryForObject("select count(*) from " + partition, Long.class)).longValue();
            if (count == 0) continue;
            jdbc.update("""
                    insert into data_archive_manifest(dataset, partition_name, period_start, period_end, row_count, status)
                    values (?, ?, ?, ?, ?, ?)
                    on conflict (partition_name) do update set row_count = excluded.row_count,
                        status = case when data_archive_manifest.status in (
                            'ARCHIVING', 'ARCHIVED', 'VERIFYING', 'VERIFIED',
                            'DATABASE_REMOVED', 'DISPOSED'
                        ) then data_archive_manifest.status else excluded.status end
                    """, dataset, partition, from, to, count, status);
        }
    }

    private YearMonth partitionMonth(String parent, String partition) {
        String prefix = parent + "_";
        if (!partition.startsWith(prefix)) return null;
        String suffix = partition.substring(prefix.length());
        if (!suffix.matches("\\d{4}_\\d{2}")) return null;
        try { return YearMonth.parse(suffix, PARTITION_MONTH); }
        catch (DateTimeParseException ignored) { return null; }
    }

    private static Instant instant(java.sql.ResultSet result, String column) throws java.sql.SQLException {
        java.sql.Timestamp value = result.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(java.util.Locale.ROOT);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String required(String value, String label) {
        if (value == null || value.isBlank()) throw new BusinessException(
                "GOVERNANCE_VALUE_REQUIRED", label + " is required", HttpStatus.BAD_REQUEST);
        return value.trim();
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    public record RetentionPolicy(String dataset, int hotDays, int warmMonths, int archiveYears,
                                  String disposalAction,
                                  boolean enabled, java.time.Instant updatedAt, String updatedBy) {}
    public record ArchiveManifest(String dataset, String partitionName, LocalDate periodStart,
                                  LocalDate periodEnd, long rowCount, String status, String objectKey,
                                  String checksumSha256, String encryptionAlgorithm,
                                  String encryptionKeyVersion, long objectSizeBytes,
                                  Instant verifiedAt, Instant restoreTestedAt, Long verifiedRowCount,
                                  Instant databaseRemovedAt, Instant disposedAt, Instant backupExpiresAt,
                                  String lastError, boolean holdBlocked, java.time.Instant discoveredAt,
                                  java.time.Instant archivedAt) {}
    public record LegalHold(UUID id, String dataset, String holdKind, String scopeType,
                            String scopeRef, String caseReference, String reason, LocalDate reviewOn,
                            String placedBy, Instant placedAt, String releasedBy, Instant releasedAt,
                            String releaseReason) {}
    public record GovernanceOverview(Map<String, Long> archiveStatuses, long activeHolds,
                                     long pendingBackupExpiries, boolean ledgerIntegrityValid,
                                     long ledgerEntriesChecked) {}
}
