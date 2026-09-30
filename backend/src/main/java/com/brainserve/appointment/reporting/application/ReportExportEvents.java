package com.brainserve.appointment.reporting.application;

import java.util.UUID;

public final class ReportExportEvents {
    private ReportExportEvents() {}
    public record Requested(UUID jobId) {}
}
