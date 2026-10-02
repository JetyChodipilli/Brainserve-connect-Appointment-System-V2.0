package com.brainserve.appointment.reporting.application;

import com.brainserve.appointment.realtime.api.WorkspaceChangeEvent;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionalEventListenerFactory;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;

import static com.brainserve.appointment.reporting.application.RoleDashboardQueryService.Freshness.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DashboardFreshnessTest {
    final Instant now = Instant.parse("2026-09-30T12:00:00Z");
    final Duration window = Duration.ofSeconds(120);
    final WorkspaceChangeEvent changed = new WorkspaceChangeEvent("VISITOR_CHECK_OUT", "ACCESS_RECORD", "record");

    @Test void sourceAgeDirtyRevisionAndMissingCohortDetermineFreshness() {
        var fresh = new RoleDashboardQueryService.SourceState(0L, 0L, now.minusSeconds(119), now, true);
        assertEquals(FRESH, fresh.freshness(fresh.oldestRefresh(), true, now, window));
        assertEquals(STALE, fresh.freshness(now.minusSeconds(120), true, now, window));
        assertEquals(STALE, fresh.freshness(fresh.oldestRefresh(), false, now, window));
        assertEquals(STALE, new RoleDashboardQueryService.SourceState(1L, 0L, now, now, true)
                .freshness(now, true, now, window));
        assertEquals(UNKNOWN, new RoleDashboardQueryService.SourceState(1L, 1L, now, now, false)
                .freshness(now, true, now, window));
        assertEquals(UNKNOWN, fresh.freshness(null, true, now, window));
        assertEquals(STALE, fresh.freshness(now.plusSeconds(1), true, now, window));
    }

    @Test void refreshOnlyRunsAfterCommitAndNeverAfterRollback() {
        var service = mock(OperationalSummaryRefreshService.class);
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(TransactionalEventListenerFactory.class);
            context.registerBean(OperationalSummaryRefreshService.class, () -> service);
            context.registerBean(ReportingRefreshListener.class);
            context.refresh();
            var tx = new TransactionTemplate(new TestTransactions());
            tx.executeWithoutResult(status -> {
                context.publishEvent(changed);
                verifyNoInteractions(service);
            });
            verify(service).refreshCommittedChanges();
            clearInvocations(service);
            tx.executeWithoutResult(status -> {
                context.publishEvent(changed);
                status.setRollbackOnly();
            });
            verifyNoInteractions(service);
        }
    }

    @Test void committedActionRemainsSuccessfulWhenRefreshMustRetry() {
        var service = mock(OperationalSummaryRefreshService.class);
        doThrow(new QueryTimeoutException("database refresh unavailable")).when(service).refreshCommittedChanges();
        assertDoesNotThrow(() -> new ReportingRefreshListener(service).onWorkspaceChange(changed));
        verify(service).refreshCommittedChanges();
    }

    @Test void nonReportingEventsDoNotRunOperationalRefresh() {
        var service = mock(OperationalSummaryRefreshService.class);
        var listener = new ReportingRefreshListener(service);
        listener.onWorkspaceChange(new WorkspaceChangeEvent("DOCUMENT_READ", "EMPLOYEE", "employee"));
        listener.onWorkspaceChange(new WorkspaceChangeEvent("MESSAGE_READ", "NOTIFICATION", "message"));
        verifyNoInteractions(service);
    }

    @Test void repeatedEventsReuseOneCommittedRefresh() {
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(contains("reporting_source_revision"), eq(Long.class))).thenReturn(3L);
        when(jdbc.queryForObject(contains("select exists"), eq(Boolean.class), any(LocalDate.class), eq(3L), any(LocalDate.class), eq(3L)))
                .thenReturn(false, true);
        var service = new OperationalSummaryRefreshService(jdbc, "Asia/Kolkata");
        service.refreshCommittedChanges();
        service.refreshCommittedChanges();
        verify(jdbc).queryForList(eq("select refresh_daily_operational_summary(?)"), any(LocalDate.class));
        verify(jdbc).queryForList(eq("select refresh_monthly_operational_summary(?)"), any(LocalDate.class));
    }

    private static final class TestTransactions extends AbstractPlatformTransactionManager {
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object tx, TransactionDefinition definition) { }
        @Override protected void doCommit(DefaultTransactionStatus status) { }
        @Override protected void doRollback(DefaultTransactionStatus status) { }
    }
}
