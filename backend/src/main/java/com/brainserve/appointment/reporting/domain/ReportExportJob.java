package com.brainserve.appointment.reporting.domain;

import com.brainserve.appointment.reporting.api.HistoryDataset;
import com.brainserve.appointment.shared.domain.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "report_export_job")
public class ReportExportJob extends AuditableEntity {
    public enum ExportFormat { CSV, XLSX }
    public enum Status { QUEUED, RUNNING, COMPLETED, FAILED, EXPIRED }

    @Column(name = "requested_by_user_id", nullable = false, updatable = false)
    private UUID requestedByUserId;
    @Column(name = "requested_role", nullable = false, updatable = false, length = 60)
    private String requestedRole;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 60)
    private HistoryDataset dataset;
    @Enumerated(EnumType.STRING)
    @Column(name = "export_format", nullable = false, updatable = false, length = 10)
    private ExportFormat format;
    @Column(name = "filter_from", nullable = false, updatable = false)
    private Instant filterFrom;
    @Column(name = "filter_to", nullable = false, updatable = false)
    private Instant filterTo;
    @Column(name = "department_id", updatable = false)
    private UUID departmentId;
    @Column(name = "status_filter", updatable = false, length = 60)
    private String statusFilter;
    @Column(name = "query_filter", updatable = false, length = 180)
    private String queryFilter;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private Status status = Status.QUEUED;
    @Column(name = "object_key", length = 400)
    private String objectKey;
    @Column(length = 220)
    private String filename;
    @Column(name = "row_count", nullable = false)
    private long rowCount;
    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;
    @Column(name = "error_message", length = 1000)
    private String errorMessage;
    @Column(name = "expires_at")
    private Instant expiresAt;
    @Column(name = "started_at")
    private Instant startedAt;
    @Column(name = "completed_at")
    private Instant completedAt;

    protected ReportExportJob() {}

    public ReportExportJob(UUID requestedByUserId, String requestedRole, HistoryDataset dataset,
                           ExportFormat format, Instant filterFrom, Instant filterTo, UUID departmentId,
                           String statusFilter, String queryFilter) {
        this.requestedByUserId = requestedByUserId;
        this.requestedRole = requestedRole;
        this.dataset = dataset;
        this.format = format;
        this.filterFrom = filterFrom;
        this.filterTo = filterTo;
        this.departmentId = departmentId;
        this.statusFilter = statusFilter;
        this.queryFilter = queryFilter;
    }

    public void start() { status = Status.RUNNING; startedAt = Instant.now(); errorMessage = null; }
    public void complete(String objectKey, String filename, long rowCount, long sizeBytes, Instant expiresAt) {
        this.status = Status.COMPLETED; this.objectKey = objectKey; this.filename = filename;
        this.rowCount = rowCount; this.sizeBytes = sizeBytes; this.expiresAt = expiresAt;
        this.completedAt = Instant.now(); this.errorMessage = null;
    }
    public void fail(String message) {
        status = Status.FAILED;
        errorMessage = message == null ? "Export failed" : message.substring(0, Math.min(message.length(), 1000));
        completedAt = Instant.now();
    }
    public void retry() {
        if (status != Status.FAILED) throw new IllegalStateException("Only failed exports can be retried");
        status = Status.QUEUED; startedAt = null; completedAt = null; errorMessage = null;
    }
    public void recover() {
        if (status != Status.RUNNING) return;
        status = Status.QUEUED; startedAt = null;
        errorMessage = "Recovered after an interrupted export worker";
    }
    public void expire() { status = Status.EXPIRED; objectKey = null; expiresAt = null; }

    public UUID getRequestedByUserId() { return requestedByUserId; }
    public String getRequestedRole() { return requestedRole; }
    public HistoryDataset getDataset() { return dataset; }
    public ExportFormat getFormat() { return format; }
    public Instant getFilterFrom() { return filterFrom; }
    public Instant getFilterTo() { return filterTo; }
    public UUID getDepartmentId() { return departmentId; }
    public String getStatusFilter() { return statusFilter; }
    public String getQueryFilter() { return queryFilter; }
    public Status getStatus() { return status; }
    public String getObjectKey() { return objectKey; }
    public String getFilename() { return filename; }
    public long getRowCount() { return rowCount; }
    public long getSizeBytes() { return sizeBytes; }
    public String getErrorMessage() { return errorMessage; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getCompletedAt() { return completedAt; }
}
