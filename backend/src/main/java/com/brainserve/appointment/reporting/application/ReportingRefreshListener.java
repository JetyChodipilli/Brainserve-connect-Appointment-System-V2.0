package com.brainserve.appointment.reporting.application;

import com.brainserve.appointment.realtime.api.WorkspaceChangeEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.TransactionException;

import java.util.Set;

@Component
public class ReportingRefreshListener {
    private static final Logger log = LoggerFactory.getLogger(ReportingRefreshListener.class);
    private static final Set<String> REPORTING_TARGETS = Set.of("APPOINTMENT", "ACCESS_RECORD", "EMPLOYEE",
            "EMPLOYEE_TERMINATION", "WORK_TASK", "WORK_TASK_AUDIT", "DEPARTMENT", "USER_ACCOUNT",
            "ROLE_DEPARTMENT_CHANGE");
    private final OperationalSummaryRefreshService summaries;

    public ReportingRefreshListener(OperationalSummaryRefreshService summaries) { this.summaries = summaries; }

    @Order(Ordered.HIGHEST_PRECEDENCE)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onWorkspaceChange(WorkspaceChangeEvent event) {
        if (!REPORTING_TARGETS.contains(event.targetType()) || event.eventType().startsWith("DOCUMENT_")) return;
        try {
            // Refresh commits before the existing realtime listener tells browsers
            // to reload. SQL revision guards still protect requests during refresh.
            summaries.refreshCommittedChanges();
        } catch (DataAccessException | TransactionException failure) {
            // The business action is already committed. Keep its response successful;
            // the persisted revision exposes STALE until the scheduled retry succeeds.
            log.warn("Dashboard source refresh deferred until the next scheduled attempt", failure);
        }
    }
}
