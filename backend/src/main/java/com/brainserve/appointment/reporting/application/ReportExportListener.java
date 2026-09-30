package com.brainserve.appointment.reporting.application;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class ReportExportListener {
    private final ReportExportService exports;
    public ReportExportListener(ReportExportService exports) { this.exports = exports; }

    @Async("reportExportExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void generate(ReportExportEvents.Requested event) { exports.process(event.jobId()); }
}
