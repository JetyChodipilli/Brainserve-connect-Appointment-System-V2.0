package com.brainserve.appointment.support.application;

import com.brainserve.appointment.audit.api.AuditService;
import com.brainserve.appointment.iam.api.CurrentAccountAuthority;
import com.brainserve.appointment.shared.application.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/** Account-scoped, bounded packages made exclusively from typed operational aggregates. */
@Service
public class SupportDiagnosticService {
    private static final int MAX_ACTIVE_PACKAGES = 20;
    private static final String PACKAGE_COLUMNS = "id,owner_id,requested_hours,case when octet_length(snapshot_json::text)<=65536 then snapshot_json::text else null end snapshot_json,size_bytes,window_start,window_end,created_at,expires_at,download_count";
    private final JdbcTemplate jdbc;
    private final CurrentAccountAuthority authority;
    private final AuditService audit;
    private final DiagnosticSnapshotCodec codec;
    private final DiagnosticSnapshot.Environment environment;
    private final String releaseVersion;
    private final String buildRevision;

    public SupportDiagnosticService(JdbcTemplate jdbc, CurrentAccountAuthority authority, AuditService audit,
            DiagnosticSnapshotCodec codec,
            @Value("${brainserve.support.environment:UNKNOWN}") String environment,
            @Value("${brainserve.support.release-version:UNKNOWN}") String releaseVersion,
            @Value("${brainserve.support.build-revision:UNKNOWN}") String buildRevision) {
        this.jdbc = jdbc; this.authority = authority; this.audit = audit; this.codec = codec;
        DiagnosticSnapshot.Environment accepted;
        try { accepted = DiagnosticSnapshot.Environment.valueOf(environment); }
        catch (IllegalArgumentException | NullPointerException exception) { accepted = DiagnosticSnapshot.Environment.UNKNOWN; }
        this.environment = accepted;
        this.releaseVersion = DiagnosticSnapshotCodec.vettedVersion(releaseVersion);
        this.buildRevision = DiagnosticSnapshotCodec.vettedRevision(buildRevision);
    }

    @Transactional(readOnly = true)
    public DiagnosticSnapshot preview(UUID actor, int hours) {
        requireAdmin(actor); checkHours(hours);
        DiagnosticSnapshot snapshot = snapshot(hours);
        codec.encode(snapshot);
        requireAdmin(actor);
        return snapshot;
    }

    @Transactional
    public Package generate(UUID actor, Generate request) {
        requireAdmin(actor);
        if (request == null || request.requestId() == null || request.hours() == null) throw invalid();
        checkHours(request.hours());
        // Serialize this owner's quota and creation without locking business rows or aggregate queries.
        jdbc.queryForObject("select id from iam_user_account where id=? for update", UUID.class, actor);
        requireAdmin(actor);
        Row existing = findRequest(request.requestId());
        if (existing != null) return replay(actor, request, existing);
        List<Receipt> receipts = jdbc.query("select owner_id,requested_hours,expires_at from support_diagnostic_request where request_id=?",
                (rs, index) -> new Receipt(rs.getObject("owner_id", UUID.class), rs.getInt("requested_hours"), rs.getTimestamp("expires_at").toInstant()), request.requestId());
        if (!receipts.isEmpty()) {
            Receipt receipt = receipts.getFirst();
            if (!actor.equals(receipt.ownerId()) || request.hours() != receipt.hours()) throw error("SUPPORT_REQUEST_CONFLICT", "This request ID was already used with a different request", HttpStatus.CONFLICT);
            throw error("SUPPORT_PACKAGE_EXPIRED", "This diagnostic package expired", HttpStatus.GONE);
        }
        Integer active = jdbc.queryForObject("select count(*) from support_diagnostic_package where owner_id=? and expires_at>clock_timestamp()", Integer.class, actor);
        if (active != null && active >= MAX_ACTIVE_PACKAGES) throw error("SUPPORT_PACKAGE_LIMIT", "Wait for an existing package to expire before generating another", HttpStatus.TOO_MANY_REQUESTS);
        DiagnosticSnapshot snapshot = snapshot(request.hours());
        byte[] bytes = codec.encode(snapshot);
        Instant created = snapshot.generatedAt(), expires = created.plus(24, ChronoUnit.HOURS);
        UUID id = UUID.randomUUID();
        int receiptCreated = jdbc.update("insert into support_diagnostic_request(request_id,owner_id,requested_hours,created_at,expires_at) values(?,?,?,?,?) on conflict do nothing",
                request.requestId(), actor, request.hours(), Timestamp.from(created), Timestamp.from(expires));
        if (receiptCreated == 0) return replay(actor, request, findRequest(request.requestId()));
        int inserted = jdbc.update("""
                insert into support_diagnostic_package(id,request_id,owner_id,requested_hours,snapshot_json,size_bytes,
                    window_start,window_end,created_at,expires_at,download_count)
                values(?,?,?,?,?::jsonb,?,?,?,?,?,0) on conflict(request_id) do nothing
                """, id, request.requestId(), actor, request.hours(), new String(bytes, StandardCharsets.UTF_8), bytes.length,
                Timestamp.from(snapshot.windowStart()), Timestamp.from(snapshot.windowEnd()), Timestamp.from(created), Timestamp.from(expires));
        if (inserted == 0) return replay(actor, request, findRequest(request.requestId()));
        requireAdmin(actor);
        audit.record("SUPPORT_DIAGNOSTIC_GENERATED", "SUPPORT_DIAGNOSTIC", id.toString(), "{\"schemaVersion\":1}");
        requireAdmin(actor);
        return new Package(id, created, expires, bytes.length, 0, snapshot.windowStart(), snapshot.windowEnd());
    }

    @Transactional(readOnly = true)
    public List<Package> list(UUID actor) {
        requireAdmin(actor);
        List<Package> packages = jdbc.query("select id,created_at,expires_at,size_bytes,download_count,window_start,window_end from support_diagnostic_package where owner_id=? and expires_at>clock_timestamp() order by created_at desc,id limit 20",
                (rs, index) -> new Package(rs.getObject("id", UUID.class), rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("expires_at").toInstant(), rs.getInt("size_bytes"), rs.getInt("download_count"),
                        rs.getTimestamp("window_start").toInstant(), rs.getTimestamp("window_end").toInstant()), actor);
        requireAdmin(actor);
        return packages;
    }

    @Transactional
    public Download download(UUID actor, UUID id) {
        requireAdmin(actor);
        if (id == null) throw missing();
        List<Row> rows = jdbc.query("select " + PACKAGE_COLUMNS + " from support_diagnostic_package where id=? and owner_id=? for update", this::row, id, actor);
        if (rows.isEmpty()) throw missing();
        Row row = rows.getFirst();
        requireFresh(row);
        DiagnosticSnapshot snapshot = codec.decode(row.snapshot());
        if (!snapshot.windowStart().equals(row.windowStart()) || !snapshot.windowEnd().equals(row.windowEnd())
                || !snapshot.generatedAt().equals(row.createdAt())) throw error("SUPPORT_SNAPSHOT_INVALID", "The diagnostic snapshot failed export validation", HttpStatus.CONFLICT);
        byte[] bytes = codec.encode(snapshot);
        if (row.downloadCount() >= DiagnosticSnapshotCodec.MAX_COUNT) throw error("SUPPORT_DOWNLOAD_LIMIT", "This package reached its download limit", HttpStatus.TOO_MANY_REQUESTS);
        requireAdmin(actor);
        jdbc.update("update support_diagnostic_package set download_count=download_count+1 where id=?", id);
        audit.record("SUPPORT_DIAGNOSTIC_DOWNLOADED", "SUPPORT_DIAGNOSTIC", id.toString(), "{\"schemaVersion\":1}");
        requireAdmin(actor); requireFresh(row);
        return new Download(id, bytes);
    }

    @Scheduled(fixedDelayString = "${brainserve.support.cleanup-ms:3600000}")
    public int cleanupExpired() {
        return jdbc.update("delete from support_diagnostic_package where id in(select id from support_diagnostic_package where expires_at<=clock_timestamp() order by expires_at,id limit 5000)");
    }

    private Package replay(UUID actor, Generate request, Row row) {
        if (row == null || !actor.equals(row.ownerId()) || request.hours() != row.hours()) throw error("SUPPORT_REQUEST_CONFLICT", "This request ID was already used with a different request", HttpStatus.CONFLICT);
        requireAdmin(actor); requireFresh(row);
        // Retained snapshots also pass validation before an idempotent receipt is returned.
        codec.decode(row.snapshot());
        requireAdmin(actor);
        return row.view();
    }

    private DiagnosticSnapshot snapshot(int hours) {
        Instant end = Instant.now().truncatedTo(ChronoUnit.MICROS), start = end.minus(hours, ChronoUnit.HOURS);
        var connections = jdbc.queryForObject("""
                select least(count(*) filter(where status='ACTIVE' and credential_expires_at>?),1000000) active,
                    least(count(*) filter(where status='NEEDS_RECONNECT' or (status='ACTIVE' and credential_expires_at<=?)),1000000) reconnect,
                    least(count(*) filter(where status='REVOKED'),1000000) revoked from integration_connection
                """, (rs, index) -> new DiagnosticSnapshot.ConnectionCounts(rs.getInt("active"), rs.getInt("reconnect"), rs.getInt("revoked")), Timestamp.from(end), Timestamp.from(end));
        var deliveries = jdbc.queryForObject("""
                select least(count(*) filter(where status='PENDING'),1000000) pending,
                    least(count(*) filter(where status='RUNNING'),1000000) running,
                    least(count(*) filter(where status='DELIVERED'),1000000) delivered,
                    least(count(*) filter(where status='FAILED'),1000000) failed,
                    least(count(*) filter(where status='NEEDS_RECONNECT'),1000000) reconnect,
                    least(count(*) filter(where status='CANCELLED'),1000000) cancelled,
                    least(count(*) filter(where status='SUPERSEDED'),1000000) superseded
                from integration_delivery where occurred_at>=? and occurred_at<?
                """, (rs, index) -> new DiagnosticSnapshot.DeliveryCounts(rs.getInt("pending"), rs.getInt("running"), rs.getInt("delivered"), rs.getInt("failed"), rs.getInt("reconnect"), rs.getInt("cancelled"), rs.getInt("superseded")), Timestamp.from(start), Timestamp.from(end));
        List<Integer> versions = jdbc.query("select version::integer from flyway_schema_history where success and version ~ '^[0-9]{1,4}$' order by installed_rank desc limit 1", (rs, index) -> rs.getInt(1));
        return new DiagnosticSnapshot(1, UUID.randomUUID(), end, start, end, environment, releaseVersion,
                buildRevision, DiagnosticSnapshot.DatabaseStatus.AVAILABLE, versions.isEmpty() ? 0 : versions.getFirst(), connections, deliveries);
    }

    private Row findRequest(UUID requestId) {
        List<Row> rows = jdbc.query("select " + PACKAGE_COLUMNS + " from support_diagnostic_package where request_id=? for update", this::row, requestId);
        return rows.isEmpty() ? null : rows.getFirst();
    }
    private Row row(java.sql.ResultSet rs, int index) throws java.sql.SQLException {
        return new Row(rs.getObject("id", UUID.class), rs.getObject("owner_id", UUID.class), rs.getInt("requested_hours"),
                rs.getString("snapshot_json"), rs.getInt("size_bytes"), rs.getTimestamp("window_start").toInstant(),
                rs.getTimestamp("window_end").toInstant(), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("expires_at").toInstant(), rs.getInt("download_count"));
    }
    private void requireAdmin(UUID actor) {
        var current = authority.requireActive(actor);
        if (!"ROLE_SYSTEM_ADMIN".equals(current.role()) || !current.permissions().contains("SYSTEM_CONFIGURE"))
            throw error("SUPPORT_SCOPE_DENIED", "An active System Admin with configuration permission is required", HttpStatus.FORBIDDEN);
    }
    private static void requireFresh(Row row) { if (!row.expiresAt().isAfter(Instant.now())) throw error("SUPPORT_PACKAGE_EXPIRED", "This diagnostic package expired", HttpStatus.GONE); }
    private static void checkHours(int hours) { if (hours < 1 || hours > 24) throw invalid(); }
    private static BusinessException invalid() { return error("SUPPORT_REQUEST_INVALID", "A request UUID and a whole-number window of 1 to 24 hours are required", HttpStatus.BAD_REQUEST); }
    private static BusinessException missing() { return error("SUPPORT_PACKAGE_NOT_FOUND", "The diagnostic package is unavailable", HttpStatus.NOT_FOUND); }
    private static BusinessException error(String code, String message, HttpStatus status) { return new BusinessException(code, message, status); }
    private record Row(UUID id, UUID ownerId, int hours, String snapshot, int sizeBytes, Instant windowStart,
                       Instant windowEnd, Instant createdAt, Instant expiresAt, int downloadCount) {
        Package view() { return new Package(id, createdAt, expiresAt, sizeBytes, downloadCount, windowStart, windowEnd); }
    }
    private record Receipt(UUID ownerId, int hours, Instant expiresAt) {}
    @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using = DiagnosticGenerateDeserializer.class)
    public record Generate(UUID requestId, Integer hours) {}
    public record Package(UUID id, Instant createdAt, Instant expiresAt, int sizeBytes, int downloadCount,
                          Instant windowStart, Instant windowEnd) {}
    public record Download(UUID id, byte[] content) {}
}
