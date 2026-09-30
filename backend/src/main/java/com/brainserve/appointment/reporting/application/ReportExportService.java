package com.brainserve.appointment.reporting.application;

import com.brainserve.appointment.audit.api.AuditService;
import com.brainserve.appointment.notification.api.InternalNotificationGateway;
import com.brainserve.appointment.reporting.api.HistoryDataset;
import com.brainserve.appointment.reporting.api.HistoryFilter;
import com.brainserve.appointment.reporting.api.HistoryRow;
import com.brainserve.appointment.reporting.domain.ReportExportJob;
import com.brainserve.appointment.reporting.infrastructure.ReportExportJobRepository;
import com.brainserve.appointment.shared.application.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ReportExportService {
    private static final Logger log = LoggerFactory.getLogger(ReportExportService.class);
    private static final List<String> HEADERS = List.of("id", "occurredAt", "dataset", "departmentId",
            "primaryLabel", "secondaryLabel", "status", "details");

    private final ReportExportJobRepository jobs;
    private final RoleAwareHistoryQueryService history;
    private final RoleDataScopeService scopes;
    private final ApplicationEventPublisher events;
    private final InternalNotificationGateway notifications;
    private final AuditService audit;
    private final S3Client s3;
    private final S3Presigner presigner;
    private final ObjectMapper objectMapper;
    private final ObjectStorageDeletionService objectDeletion;
    private final GovernanceLedgerService governanceLedger;
    private final DataGovernanceService dataGovernance;
    private final TransactionTemplate transactions;
    private final String bucket;
    private final int retentionDays;
    private final long maximumRows;

    public ReportExportService(ReportExportJobRepository jobs, RoleAwareHistoryQueryService history,
                               RoleDataScopeService scopes, ApplicationEventPublisher events,
                               InternalNotificationGateway notifications, AuditService audit,
                               S3Client s3, S3Presigner presigner, ObjectMapper objectMapper,
                               ObjectStorageDeletionService objectDeletion,
                               GovernanceLedgerService governanceLedger,
                               DataGovernanceService dataGovernance,
                               PlatformTransactionManager transactionManager,
                               @Value("${brainserve.document.bucket}") String bucket,
                               @Value("${brainserve.reporting.export-retention-days:7}") int retentionDays,
                               @Value("${brainserve.reporting.export-max-rows:1000000}") long maximumRows) {
        this.jobs = jobs; this.history = history; this.scopes = scopes; this.events = events;
        this.notifications = notifications; this.audit = audit; this.s3 = s3; this.presigner = presigner;
        this.objectMapper = objectMapper; this.objectDeletion = objectDeletion;
        this.governanceLedger = governanceLedger;
        this.dataGovernance = dataGovernance;
        this.transactions = new TransactionTemplate(transactionManager);
        this.bucket = bucket; this.retentionDays = Math.max(1, Math.min(retentionDays, 30));
        this.maximumRows = Math.max(1_000, maximumRows);
    }

    @Transactional
    public View request(UUID actorUserId, HistoryDataset dataset, ReportExportJob.ExportFormat format,
                        HistoryFilter filter) {
        var scope = scopes.resolve(actorUserId);
        scopes.requireDataset(scope, dataset);
        UUID departmentId = scopes.effectiveDepartment(scope, filter.departmentId());
        ReportExportJob job = jobs.saveAndFlush(new ReportExportJob(actorUserId, scope.role(), dataset, format,
                filter.from(), filter.to(), departmentId, filter.status(), filter.query()));
        audit.record("REPORT_EXPORT_REQUESTED", "REPORT_EXPORT", job.getId().toString(),
                "{\"dataset\":\"" + dataset + "\",\"format\":\"" + format + "\"}");
        events.publishEvent(new ReportExportEvents.Requested(job.getId()));
        return View.from(job);
    }

    public void process(UUID jobId) {
        ReportExportJob job = transactions.execute(status -> {
            ReportExportJob value = jobs.findByIdForUpdate(jobId).orElseThrow(() -> notFound());
            if (value.getStatus() != ReportExportJob.Status.QUEUED) return null;
            value.start();
            return jobs.saveAndFlush(value);
        });
        if (job == null) return;

        Path temporary = null;
        try {
            String extension = job.getFormat() == ReportExportJob.ExportFormat.CSV ? "csv" : "xlsx";
            temporary = Files.createTempFile("brainserve-export-", "." + extension);
            long rowCount = generate(job, temporary);
            String filename = job.getDataset().name().toLowerCase() + "-" + LocalDate.now(ZoneOffset.UTC)
                    + "-" + job.getId().toString().substring(0, 8) + "." + extension;
            String objectKey = "report-exports/" + job.getRequestedByUserId() + "/" + job.getId() + "." + extension;
            String contentType = extension.equals("csv") ? "text/csv; charset=utf-8"
                    : "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            s3.putObject(PutObjectRequest.builder().bucket(bucket).key(objectKey).contentType(contentType)
                    .metadata(java.util.Map.of("requested-by", job.getRequestedByUserId().toString(),
                            "dataset", job.getDataset().name())).build(), RequestBody.fromFile(temporary));
            long bytes = Files.size(temporary);
            Instant expiresAt = Instant.now().plus(Duration.ofDays(retentionDays));
            transactions.executeWithoutResult(status -> {
                ReportExportJob current = require(jobId);
                current.complete(objectKey, filename, rowCount, bytes, expiresAt);
                jobs.saveAndFlush(current);
            });
            audit.record("REPORT_EXPORT_COMPLETED", "REPORT_EXPORT", jobId.toString(),
                    "{\"rows\":" + rowCount + ",\"sizeBytes\":" + bytes + "}");
            notifySafely(job.getRequestedByUserId(), "Your " + job.getDataset().name().replace('_', ' ')
                    + " export is ready in Reports. It expires in " + retentionDays + " days.", false);
        } catch (Exception ex) {
            log.error("Report export failed jobId={}", jobId, ex);
            transactions.executeWithoutResult(status -> {
                ReportExportJob current = require(jobId);
                current.fail("The export could not be generated. Retry the job or contact System Admin.");
                jobs.saveAndFlush(current);
            });
            audit.record("REPORT_EXPORT_FAILED", "REPORT_EXPORT", jobId.toString(), "{\"retryable\":true}");
            notifySafely(job.getRequestedByUserId(), "Your report export could not be generated. Open Reports to retry.", true);
        } finally {
            if (temporary != null) try { Files.deleteIfExists(temporary); } catch (IOException ignored) { }
        }
    }

    @Transactional(readOnly = true)
    public List<View> list(UUID actorUserId) {
        var scope = scopes.resolve(actorUserId);
        List<ReportExportJob> values = scope.role().equals("ROLE_SYSTEM_ADMIN")
                ? jobs.findTop100ByOrderByCreatedAtDesc()
                : jobs.findTop100ByRequestedByUserIdOrderByCreatedAtDesc(actorUserId);
        return values.stream().map(View::from).toList();
    }

    @Transactional(readOnly = true)
    public DownloadView download(UUID actorUserId, UUID jobId) {
        ReportExportJob job = require(jobId);
        var scope = scopes.resolve(actorUserId);
        if (!job.getRequestedByUserId().equals(actorUserId) && !scope.role().equals("ROLE_SYSTEM_ADMIN")) {
            throw new BusinessException("REPORT_EXPORT_ACCESS_DENIED", "This export belongs to another account",
                    HttpStatus.FORBIDDEN);
        }
        if (job.getStatus() != ReportExportJob.Status.COMPLETED || job.getObjectKey() == null
                || job.getExpiresAt() == null || !job.getExpiresAt().isAfter(Instant.now())) {
            throw new BusinessException("REPORT_EXPORT_NOT_READY", "The export is not ready or has expired",
                    HttpStatus.CONFLICT);
        }
        GetObjectRequest request = GetObjectRequest.builder().bucket(bucket).key(job.getObjectKey())
                .responseContentDisposition("attachment; filename=\"" + job.getFilename() + "\"").build();
        Instant accessExpires = Instant.now().plus(Duration.ofMinutes(5));
        String url = presigner.presignGetObject(GetObjectPresignRequest.builder()
                .signatureDuration(Duration.ofMinutes(5)).getObjectRequest(request).build()).url().toString();
        return new DownloadView(url, accessExpires, job.getFilename());
    }

    @Transactional
    public View retry(UUID actorUserId, UUID jobId) {
        ReportExportJob job = jobs.findByIdForUpdate(jobId).orElseThrow(() -> notFound());
        var scope = scopes.resolve(actorUserId);
        if (!job.getRequestedByUserId().equals(actorUserId) && !scope.role().equals("ROLE_SYSTEM_ADMIN")) {
            throw new BusinessException("REPORT_EXPORT_ACCESS_DENIED", "This export belongs to another account",
                    HttpStatus.FORBIDDEN);
        }
        if (job.getStatus() != ReportExportJob.Status.FAILED) {
            throw new BusinessException("REPORT_EXPORT_NOT_RETRYABLE", "Only failed exports can be retried",
                    HttpStatus.CONFLICT);
        }
        job.retry(); jobs.saveAndFlush(job);
        audit.record("REPORT_EXPORT_RETRIED", "REPORT_EXPORT", jobId.toString(), "{\"status\":\"QUEUED\"}");
        events.publishEvent(new ReportExportEvents.Requested(jobId));
        return View.from(job);
    }

    @Scheduled(cron = "${brainserve.reporting.export-recovery-cron:0 */10 * * * *}",
            zone = "${brainserve.appointment.office-zone:Asia/Kolkata}")
    public void recoverInterruptedExports() {
        Instant staleBefore = Instant.now().minus(Duration.ofHours(2));
        jobs.findTop100ByStatusAndStartedAtBeforeOrderByStartedAtAsc(ReportExportJob.Status.RUNNING, staleBefore)
                .forEach(job -> transactions.executeWithoutResult(status -> {
                    ReportExportJob current = jobs.findByIdForUpdate(job.getId()).orElse(null);
                    if (current == null || current.getStatus() != ReportExportJob.Status.RUNNING) return;
                    current.recover(); jobs.saveAndFlush(current);
                    events.publishEvent(new ReportExportEvents.Requested(current.getId()));
                }));
    }

    @Scheduled(cron = "${brainserve.reporting.export-cleanup-cron:0 10 3 * * *}",
            zone = "${brainserve.appointment.office-zone:Asia/Kolkata}")
    public void expireExports() {
        jobs.findTop100ByStatusAndExpiresAtBeforeOrderByExpiresAtAsc(ReportExportJob.Status.COMPLETED, Instant.now())
                .forEach(job -> {
                    try {
                        if (dataGovernance.hasActiveHold("REPORT_EXPORT", job.getId().toString())) return;
                        if (job.getObjectKey() != null) {
                            objectDeletion.deleteEveryVersion(job.getObjectKey());
                            if (!objectDeletion.isGone(job.getObjectKey())) {
                                throw new IllegalStateException("Expired report export still exists in object storage");
                            }
                        }
                        transactions.executeWithoutResult(status -> { require(job.getId()).expire(); });
                        governanceLedger.record("REPORT_EXPORT_EXPIRED", "REPORT_EXPORT",
                                job.getId().toString(), "SUCCESS", Map.of(
                                        "allObjectVersionsDeleted", true,
                                        "expiredAt", Instant.now().toString()
                                ));
                    } catch (RuntimeException ex) {
                        log.warn("Could not expire report export jobId={}", job.getId(), ex);
                    }
                });
    }

    private long generate(ReportExportJob job, Path target) throws IOException {
        return job.getFormat() == ReportExportJob.ExportFormat.CSV
                ? generateCsv(job, target) : generateXlsx(job, target);
    }

    private long generateCsv(ReportExportJob job, Path target) throws IOException {
        long count = 0;
        try (BufferedWriter writer = Files.newBufferedWriter(target, StandardCharsets.UTF_8)) {
            writer.write(String.join(",", HEADERS)); writer.newLine();
            String cursor = null;
            do {
                var page = next(job, cursor);
                for (HistoryRow row : page.items()) {
                    writer.write(csv(row.id())); writer.write(','); writer.write(csv(row.occurredAt())); writer.write(',');
                    writer.write(csv(row.dataset())); writer.write(','); writer.write(csv(row.departmentId())); writer.write(',');
                    writer.write(csv(row.primaryLabel())); writer.write(','); writer.write(csv(row.secondaryLabel())); writer.write(',');
                    writer.write(csv(row.status())); writer.write(','); writer.write(csv(objectMapper.writeValueAsString(row.details())));
                    writer.newLine();
                    if (++count > maximumRows) throw new IOException("Export exceeds the configured row limit");
                }
                cursor = page.nextCursor();
            } while (cursor != null);
        }
        return count;
    }

    private long generateXlsx(ReportExportJob job, Path target) throws IOException {
        long count = 0;
        try (SXSSFWorkbook workbook = new SXSSFWorkbook(100)) {
            workbook.setCompressTempFiles(true);
            var sheet = workbook.createSheet("BrainServe " + job.getDataset().name());
            Row header = sheet.createRow(0);
            for (int i = 0; i < HEADERS.size(); i++) header.createCell(i).setCellValue(HEADERS.get(i));
            String cursor = null;
            do {
                var page = next(job, cursor);
                for (HistoryRow value : page.items()) {
                    Row row = sheet.createRow(Math.toIntExact(count + 1));
                    List<Object> cells = new ArrayList<>(List.of(value.id(), value.occurredAt(), value.dataset(),
                            value.departmentId() == null ? "" : value.departmentId(), value.primaryLabel(),
                            value.secondaryLabel(), value.status(), objectMapper.writeValueAsString(value.details())));
                    for (int i = 0; i < cells.size(); i++) setCell(row.createCell(i), cells.get(i));
                    if (++count > Math.min(maximumRows, 1_048_575L)) throw new IOException("Export exceeds the XLSX row limit");
                }
                cursor = page.nextCursor();
            } while (cursor != null);
            try (var output = Files.newOutputStream(target)) { workbook.write(output); }
            workbook.dispose();
        }
        return count;
    }

    private com.brainserve.appointment.reporting.api.CursorPage<HistoryRow> next(ReportExportJob job, String cursor) {
        HistoryFilter filter = new HistoryFilter(job.getFilterFrom(), job.getFilterTo(), job.getDepartmentId(),
                job.getStatusFilter(), job.getQueryFilter(), cursor, 100);
        return history.search(job.getRequestedByUserId(), job.getDataset(), filter);
    }

    private String csv(Object value) {
        String text = safeCell(value);
        return "\"" + text.replace("\"", "\"\"") + "\"";
    }

    private void setCell(Cell cell, Object value) { cell.setCellValue(safeCell(value)); }
    private String safeCell(Object value) {
        String text = value == null ? "" : String.valueOf(value).replaceAll("[\\r\\n]+", " ");
        return !text.isEmpty() && "=+-@".indexOf(text.charAt(0)) >= 0 ? "'" + text : text;
    }

    private ReportExportJob require(UUID id) {
        return jobs.findById(id).orElseThrow(this::notFound);
    }
    private BusinessException notFound() { return new BusinessException("REPORT_EXPORT_NOT_FOUND",
            "The report export was not found", HttpStatus.NOT_FOUND); }

    private void notifySafely(UUID userId, String message, boolean failed) {
        try { notifications.notifyReportExportReady(userId, message, failed); }
        catch (RuntimeException ex) { log.warn("Report export notification failed userId={}", userId, ex); }
    }

    public record View(UUID id, UUID requestedByUserId, String requestedRole, HistoryDataset dataset,
                       ReportExportJob.ExportFormat format, ReportExportJob.Status status, String filename,
                       long rowCount, long sizeBytes, String errorMessage, Instant expiresAt,
                       Instant startedAt, Instant completedAt, Instant createdAt) {
        static View from(ReportExportJob value) {
            return new View(value.getId(), value.getRequestedByUserId(), value.getRequestedRole(),
                    value.getDataset(), value.getFormat(), value.getStatus(), value.getFilename(),
                    value.getRowCount(), value.getSizeBytes(), value.getErrorMessage(), value.getExpiresAt(),
                    value.getStartedAt(), value.getCompletedAt(), value.getCreatedAt());
        }
    }
    public record DownloadView(String url, Instant expiresAt, String filename) {}
}
