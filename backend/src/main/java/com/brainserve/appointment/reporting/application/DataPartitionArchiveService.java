package com.brainserve.appointment.reporting.application;

import com.brainserve.appointment.audit.api.AuditService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.CipherOutputStream;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.security.DigestInputStream;
import java.security.DigestOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

@Service
@ConditionalOnProperty(name = "brainserve.reporting.partition-archive-enabled",
        havingValue = "true", matchIfMissing = true)
public class DataPartitionArchiveService {
    private static final Logger log = LoggerFactory.getLogger(DataPartitionArchiveService.class);
    private static final byte[] ARCHIVE_MAGIC = "BSAR1".getBytes(StandardCharsets.US_ASCII);
    private static final int GCM_IV_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final String SAFE_PARTITION = """
            ^(audit_event_history|visitor_checkpoint_event|workboard_activity_event|employee_history_event|\
            visitor_history_event|appointment_history_event|essential_log_history)_\\d{4}_\\d{2}$
            """.replaceAll("\\s+", "");

    private final JdbcTemplate jdbc;
    private final DataSource dataSource;
    private final S3Client s3;
    private final AuditService audit;
    private final GovernanceLedgerService ledger;
    private final DataGovernanceService governance;
    private final ObjectMapper objectMapper;
    private final ObjectStorageDeletionService objectDeletion;
    private final SecureRandom secureRandom = new SecureRandom();
    private final String bucket;
    private final Map<String, SecretKeySpec> encryptionKeys;
    private final String activeKeyVersion;
    private final boolean partitionCleanupEnabled;
    private final int backupRetentionDays;

    public DataPartitionArchiveService(
            JdbcTemplate jdbc,
            DataSource dataSource,
            S3Client s3,
            AuditService audit,
            GovernanceLedgerService ledger,
            DataGovernanceService governance,
            ObjectMapper objectMapper,
            ObjectStorageDeletionService objectDeletion,
            @Value("${brainserve.document.bucket}") String bucket,
            @Value("${brainserve.reporting.archive-encryption-keys:}") String encryptionKeyring,
            @Value("${brainserve.reporting.archive-encryption-active-key-version:v1}") String activeKeyVersion,
            @Value("${brainserve.reporting.partition-cleanup-enabled:true}") boolean partitionCleanupEnabled,
            @Value("${brainserve.backup.lifecycle-retention-days:35}") int backupRetentionDays) {
        this.jdbc = jdbc;
        this.dataSource = dataSource;
        this.s3 = s3;
        this.audit = audit;
        this.ledger = ledger;
        this.governance = governance;
        this.objectMapper = objectMapper;
        this.objectDeletion = objectDeletion;
        this.bucket = bucket;
        this.encryptionKeys = parseKeyring(encryptionKeyring);
        this.activeKeyVersion = activeKeyVersion.trim();
        this.partitionCleanupEnabled = partitionCleanupEnabled;
        this.backupRetentionDays = Math.max(14, Math.min(backupRetentionDays, 3650));
    }

    @Scheduled(cron = "${brainserve.reporting.partition-archive-cron:0 20 4 * * 0}",
            zone = "${brainserve.appointment.office-zone:Asia/Kolkata}")
    public void archiveEligiblePartitions() {
        List<Candidate> candidates = jdbc.query("""
                select id, dataset, partition_name, period_start::text, row_count
                  from data_archive_manifest manifest
                 where status in ('ARCHIVE_ELIGIBLE', 'FAILED')
                   and object_key is null
                   and not exists (
                       select 1 from data_legal_hold hold
                        where hold.released_at is null and hold.dataset = manifest.dataset
                          and (hold.scope_type in ('DATASET', 'SUBJECT')
                               or (hold.scope_type = 'PARTITION'
                                   and hold.scope_ref = manifest.partition_name))
                   )
                 order by period_start asc
                 limit 2
                """, (result, row) -> new Candidate(
                result.getObject("id", UUID.class),
                result.getString("dataset"),
                result.getString("partition_name"),
                result.getString("period_start"),
                result.getLong("row_count")
        ));
        candidates.forEach(this::archiveSafely);
    }

    private void archiveSafely(Candidate candidate) {
        if (!safePartition(candidate.partitionName())) {
            markFailed(candidate, "Unsafe partition name was rejected");
            return;
        }
        SecretKeySpec key;
        try {
            key = encryptionKey(activeKeyVersion);
        } catch (IllegalStateException ex) {
            markFailed(candidate, ex.getMessage());
            return;
        }
        int claimed = jdbc.update("""
                update data_archive_manifest
                   set status = 'ARCHIVING', last_error = null
                 where id = ? and status in ('ARCHIVE_ELIGIBLE', 'FAILED') and object_key is null
                """, candidate.id());
        if (claimed == 0) return;

        Path temporary = null;
        String objectKey = null;
        String versionId = null;
        try {
            temporary = Files.createTempFile("brainserve-cold-archive-", ".jsonl.gz.enc");
            ArchiveFile archive = copyEncryptedJsonLines(candidate.partitionName(), temporary, key);
            if (archive.rows() != candidate.manifestRows()) {
                throw new IllegalStateException("Partition row count changed during archive preparation");
            }
            objectKey = "database-archive/" + candidate.dataset().toLowerCase() + "/"
                    + candidate.periodStart() + "/" + candidate.partitionName() + ".jsonl.gz.enc";
            PutObjectResponse uploaded = s3.putObject(PutObjectRequest.builder()
                    .bucket(bucket)
                    .key(objectKey)
                    .contentType("application/octet-stream")
                    .metadata(Map.of(
                            "sha256", archive.checksum(),
                            "rows", String.valueOf(archive.rows()),
                            "source-partition", candidate.partitionName(),
                            "client-encryption", "AES-256-GCM",
                            "key-version", activeKeyVersion
                    ))
                    .build(), RequestBody.fromFile(temporary));
            versionId = uploaded.versionId();
            jdbc.update("""
                    update data_archive_manifest
                       set status = 'ARCHIVED', object_key = ?, object_version_id = ?,
                           checksum_sha256 = ?, row_count = ?, object_size_bytes = ?,
                           encryption_algorithm = 'AES-256-GCM',
                           encryption_key_version = ?, archived_at = now(), last_error = null
                     where id = ? and status = 'ARCHIVING'
                    """, objectKey, versionId, archive.checksum(), archive.rows(), archive.sizeBytes(),
                    activeKeyVersion, candidate.id());
            audit.record("DATA_PARTITION_ARCHIVED", "DATABASE_PARTITION", candidate.partitionName(),
                    "{\"dataset\":\"" + candidate.dataset() + "\",\"rows\":" + archive.rows()
                            + ",\"checksum\":\"" + archive.checksum() + "\",\"encrypted\":true}");
            ledger.record("COLD_ARCHIVE_CREATED", candidate.dataset(), candidate.partitionName(),
                    "SUCCESS", Map.of(
                            "rows", archive.rows(),
                            "checksumSha256", archive.checksum(),
                            "encryptionAlgorithm", "AES-256-GCM",
                            "encryptionKeyVersion", activeKeyVersion,
                            "objectSizeBytes", archive.sizeBytes()
                    ));
            verifyUploadedArchive(candidate.id(), candidate.dataset(), candidate.partitionName(),
                    objectKey, versionId, archive.checksum(), archive.rows(), activeKeyVersion);
        } catch (Exception ex) {
            log.error("Partition archive failed partition={}", candidate.partitionName(), ex);
            if (objectKey != null) {
                try {
                    objectDeletion.deleteEveryVersion(objectKey);
                } catch (Exception cleanupFailure) {
                    log.error("Failed to remove invalid archive object key={}", objectKey, cleanupFailure);
                }
            }
            jdbc.update("""
                    update data_archive_manifest
                       set status = 'FAILED', object_key = null, object_version_id = null,
                           last_error = ?, verification_attempts = verification_attempts + 1
                     where id = ?
                    """, boundedError(ex), candidate.id());
            ledger.record("COLD_ARCHIVE_FAILED", candidate.dataset(), candidate.partitionName(),
                    "FAILED", Map.of("error", boundedError(ex)));
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    log.warn("Temporary archive file could not be deleted path={}", temporary);
                }
            }
        }
    }

    private void verifyUploadedArchive(UUID manifestId, String dataset, String partitionName,
                                       String objectKey, String versionId, String expectedChecksum,
                                       long expectedRows, String keyVersion) throws Exception {
        jdbc.update("""
                update data_archive_manifest
                   set status = 'VERIFYING', verification_attempts = verification_attempts + 1
                 where id = ? and status = 'ARCHIVED'
                """, manifestId);
        Verification verification = readAndVerify(objectKey, versionId, expectedChecksum,
                expectedRows, encryptionKey(keyVersion));
        jdbc.update("""
                update data_archive_manifest
                   set status = 'VERIFIED', verified_at = now(), restore_tested_at = now(),
                       verified_row_count = ?, last_error = null
                 where id = ? and status = 'VERIFYING'
                """, verification.rows(), manifestId);
        audit.record("DATA_ARCHIVE_RESTORE_TEST_PASSED", "DATABASE_PARTITION", partitionName,
                "{\"dataset\":\"" + dataset + "\",\"rows\":" + verification.rows()
                        + ",\"checksum\":\"" + verification.checksum() + "\"}");
        ledger.record("ARCHIVE_RESTORE_TEST_PASSED", dataset, partitionName, "SUCCESS", Map.of(
                "rows", verification.rows(),
                "checksumSha256", verification.checksum(),
                "jsonRecordsValidated", verification.rows()
        ));
    }

    @Scheduled(cron = "${brainserve.reporting.partition-cleanup-cron:0 35 5 * * 0}",
            zone = "${brainserve.appointment.office-zone:Asia/Kolkata}")
    @Transactional
    public void removeVerifiedPartitions() {
        if (!partitionCleanupEnabled) return;
        List<Candidate> candidates = jdbc.query("""
                select id, dataset, partition_name, period_start::text, row_count
                  from data_archive_manifest
                 where status = 'VERIFIED' and restore_tested_at is not null
                 order by period_start asc
                 limit 4
                """, (result, row) -> new Candidate(
                result.getObject("id", UUID.class), result.getString("dataset"),
                result.getString("partition_name"), result.getString("period_start"),
                result.getLong("row_count")
        ));
        for (Candidate candidate : candidates) {
            if (governance.hasActiveHold(candidate.dataset(), candidate.partitionName())) continue;
            if (!safePartition(candidate.partitionName())) {
                markFailed(candidate, "Unsafe partition name was rejected during database cleanup");
                continue;
            }
            String parent = parentFor(candidate.partitionName());
            Boolean exists = jdbc.queryForObject("select to_regclass(?) is not null",
                    Boolean.class, candidate.partitionName());
            if (Boolean.TRUE.equals(exists)) {
                jdbc.execute("alter table " + parent + " detach partition " + candidate.partitionName());
                jdbc.execute("drop table " + candidate.partitionName());
            }
            jdbc.update("""
                    update data_archive_manifest
                       set status = 'DATABASE_REMOVED', database_removed_at = now(), last_error = null
                     where id = ? and status = 'VERIFIED'
                    """, candidate.id());
            ledger.record("VERIFIED_PARTITION_REMOVED", candidate.dataset(),
                    candidate.partitionName(), "SUCCESS", Map.of(
                            "restoreTestRequired", true,
                            "rowsPreservedInEncryptedArchive", candidate.manifestRows()
                    ));
            audit.record("VERIFIED_PARTITION_REMOVED", "DATABASE_PARTITION", candidate.partitionName(),
                    "{\"dataset\":\"" + candidate.dataset() + "\",\"archiveVerified\":true}");
        }
    }

    @Scheduled(cron = "${brainserve.reporting.archive-disposal-cron:0 10 6 * * 0}",
            zone = "${brainserve.appointment.office-zone:Asia/Kolkata}")
    @Transactional
    public void disposeExpiredArchives() {
        List<DisposableArchive> archives = jdbc.query("""
                select manifest.id, manifest.dataset, manifest.partition_name, manifest.object_key
                  from data_archive_manifest manifest
                  join data_retention_policy policy on policy.dataset = manifest.dataset and policy.enabled
                 where manifest.status = 'DATABASE_REMOVED'
                   and manifest.period_end <= current_date - make_interval(years => policy.archive_years)
                   and not exists (
                       select 1 from data_legal_hold hold
                        where hold.released_at is null and hold.dataset = manifest.dataset
                          and (hold.scope_type in ('DATASET', 'SUBJECT')
                               or (hold.scope_type = 'PARTITION'
                                   and hold.scope_ref = manifest.partition_name))
                   )
                 order by manifest.period_end asc
                 limit 4
                """, (result, row) -> new DisposableArchive(
                result.getObject("id", UUID.class), result.getString("dataset"),
                result.getString("partition_name"), result.getString("object_key")
        ));
        for (DisposableArchive archive : archives) {
            try {
                objectDeletion.deleteEveryVersion(archive.objectKey());
                if (!objectDeletion.isGone(archive.objectKey())) {
                    throw new IllegalStateException("Archive object still exists after disposal");
                }
                Instant backupExpiresAt = Instant.now().plus(Duration.ofDays(backupRetentionDays));
                jdbc.update("""
                        update data_archive_manifest
                           set status = 'DISPOSED', disposed_at = now(), backup_expires_at = ?,
                               last_error = null
                         where id = ? and status = 'DATABASE_REMOVED'
                        """, backupExpiresAt, archive.id());
                jdbc.update("""
                        insert into backup_expiry_register(dataset, target_ref, disposed_at, backup_expires_at)
                        values (?, ?, now(), ?)
                        """, archive.dataset(), archive.partitionName(), backupExpiresAt);
                ledger.record("COLD_ARCHIVE_SECURELY_DISPOSED", archive.dataset(),
                        archive.partitionName(), "SUCCESS", Map.of(
                                "allObjectVersionsDeleted", true,
                                "backupExpiresAt", backupExpiresAt.toString(),
                                "backupLifecycleDays", backupRetentionDays
                        ));
                audit.record("COLD_ARCHIVE_SECURELY_DISPOSED", "DATABASE_PARTITION",
                        archive.partitionName(), "{\"dataset\":\"" + archive.dataset()
                                + "\",\"backupExpiresAt\":\"" + backupExpiresAt + "\"}");
            } catch (Exception ex) {
                jdbc.update("update data_archive_manifest set last_error = ? where id = ?",
                        boundedError(ex), archive.id());
                ledger.record("COLD_ARCHIVE_DISPOSAL_FAILED", archive.dataset(),
                        archive.partitionName(), "FAILED", Map.of("error", boundedError(ex)));
            }
        }
    }

    @Scheduled(cron = "${brainserve.backup.expiry-confirmation-cron:0 45 6 * * *}",
            zone = "${brainserve.appointment.office-zone:Asia/Kolkata}")
    @Transactional
    public void confirmBackupLifecycleExpiry() {
        List<BackupExpiry> expired = jdbc.query("""
                update backup_expiry_register
                   set status = 'CONFIRMED', confirmed_at = now()
                 where id in (
                     select id from backup_expiry_register
                      where status = 'PENDING' and backup_expires_at <= now()
                      order by backup_expires_at
                      limit 500
                 )
                returning id, dataset, target_ref, backup_expires_at
                """, (result, row) -> new BackupExpiry(
                result.getObject("id", UUID.class), result.getString("dataset"),
                result.getString("target_ref"), result.getTimestamp("backup_expires_at").toInstant()
        ));
        for (BackupExpiry expiry : expired) {
            ledger.record("BACKUP_LIFECYCLE_EXPIRY_CONFIRMED", expiry.dataset(),
                    expiry.targetRef(), "SUCCESS", Map.of(
                            "backupExpiresAt", expiry.backupExpiresAt().toString(),
                            "policy", "Expired physical backups and WAL are removed by the backup lifecycle worker"
                    ));
        }
    }

    private ArchiveFile copyEncryptedJsonLines(String partition, Path target, SecretKeySpec key) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] iv = new byte[GCM_IV_BYTES];
        secureRandom.nextBytes(iv);
        long rows = 0;
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            connection.setReadOnly(true);
            try (Statement statement = connection.createStatement(ResultSet.TYPE_FORWARD_ONLY,
                    ResultSet.CONCUR_READ_ONLY)) {
                statement.setFetchSize(2_000);
                try (ResultSet result = statement.executeQuery(
                        "select row_to_json(history)::text from " + partition
                                + " history order by occurred_at, id");
                     OutputStream file = Files.newOutputStream(target);
                     DigestOutputStream checked = new DigestOutputStream(file, digest)) {
                    checked.write(ARCHIVE_MAGIC);
                    checked.write(iv);
                    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                    cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
                    try (CipherOutputStream encrypted = new CipherOutputStream(checked, cipher);
                         GZIPOutputStream gzip = new GZIPOutputStream(encrypted, 64 * 1024);
                         BufferedWriter writer = new BufferedWriter(
                                 new OutputStreamWriter(gzip, StandardCharsets.UTF_8), 64 * 1024)) {
                        while (result.next()) {
                            writer.write(result.getString(1));
                            writer.newLine();
                            rows++;
                        }
                    }
                }
            } finally {
                connection.rollback();
            }
        }
        return new ArchiveFile(rows, HexFormat.of().formatHex(digest.digest()), Files.size(target));
    }

    private Verification readAndVerify(String objectKey, String versionId, String expectedChecksum,
                                       long expectedRows, SecretKeySpec key) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        GetObjectRequest.Builder request = GetObjectRequest.builder().bucket(bucket).key(objectKey);
        if (versionId != null && !versionId.isBlank()) request.versionId(versionId);
        long rows = 0;
        try (ResponseInputStream<GetObjectResponse> object = s3.getObject(request.build());
             DigestInputStream checked = new DigestInputStream(object, digest)) {
            byte[] magic = checked.readNBytes(ARCHIVE_MAGIC.length);
            if (!java.util.Arrays.equals(magic, ARCHIVE_MAGIC)) {
                throw new IllegalStateException("Archive header is not recognized");
            }
            byte[] iv = checked.readNBytes(GCM_IV_BYTES);
            if (iv.length != GCM_IV_BYTES) throw new IllegalStateException("Archive IV is incomplete");
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            try (CipherInputStream decrypted = new CipherInputStream(checked, cipher);
                 GZIPInputStream gzip = new GZIPInputStream(decrypted, 64 * 1024);
                 BufferedReader reader = new BufferedReader(
                         new InputStreamReader(gzip, StandardCharsets.UTF_8), 64 * 1024)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    objectMapper.readTree(line);
                    rows++;
                }
            }
        }
        String checksum = HexFormat.of().formatHex(digest.digest());
        if (!MessageDigest.isEqual(
                checksum.getBytes(StandardCharsets.US_ASCII),
                expectedChecksum.getBytes(StandardCharsets.US_ASCII))) {
            throw new IllegalStateException("Downloaded archive checksum does not match its manifest");
        }
        if (rows != expectedRows) {
            throw new IllegalStateException("Restored archive row count does not match its manifest");
        }
        return new Verification(rows, checksum);
    }

    private void markFailed(Candidate candidate, String error) {
        jdbc.update("""
                update data_archive_manifest
                   set status = 'FAILED', last_error = ?, verification_attempts = verification_attempts + 1
                 where id = ?
                """, bounded(error), candidate.id());
        ledger.record("COLD_ARCHIVE_FAILED", candidate.dataset(), candidate.partitionName(),
                "FAILED", Map.of("error", bounded(error)));
    }

    private SecretKeySpec encryptionKey(String version) {
        SecretKeySpec key = encryptionKeys.get(version);
        if (key == null) {
            throw new IllegalStateException("Archive encryption key version " + version
                    + " is not configured; set ARCHIVE_ENCRYPTION_KEYS before archiving");
        }
        return key;
    }

    private static Map<String, SecretKeySpec> parseKeyring(String specification) {
        Map<String, SecretKeySpec> keys = new LinkedHashMap<>();
        if (specification == null || specification.isBlank()) return Map.of();
        for (String entry : specification.split(",")) {
            int separator = entry.indexOf('=');
            if (separator < 1 || separator == entry.length() - 1) {
                throw new IllegalArgumentException(
                        "Archive keyring entries must use version=base64-key");
            }
            String version = entry.substring(0, separator).trim();
            byte[] decoded = Base64.getDecoder().decode(entry.substring(separator + 1).trim());
            if (decoded.length != 32) {
                throw new IllegalArgumentException(
                        "Archive key " + version + " must decode to exactly 32 bytes");
            }
            keys.put(version, new SecretKeySpec(decoded, "AES"));
        }
        return Map.copyOf(keys);
    }

    private static boolean safePartition(String partition) {
        return partition != null && partition.matches(SAFE_PARTITION);
    }

    private static String parentFor(String partition) {
        return partition.substring(0, partition.length() - "_yyyy_mm".length());
    }

    private static String boundedError(Exception error) {
        return bounded(error.getClass().getSimpleName() + ": "
                + (error.getMessage() == null ? "Archive operation failed" : error.getMessage()));
    }

    private static String bounded(String value) {
        String normalized = value == null ? "Archive operation failed" : value.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 1600 ? normalized : normalized.substring(0, 1600);
    }

    private record Candidate(UUID id, String dataset, String partitionName,
                             String periodStart, long manifestRows) {}
    private record ArchiveFile(long rows, String checksum, long sizeBytes) {}
    private record Verification(long rows, String checksum) {}
    private record DisposableArchive(UUID id, String dataset, String partitionName, String objectKey) {}
    private record BackupExpiry(UUID id, String dataset, String targetRef, Instant backupExpiresAt) {}
}
