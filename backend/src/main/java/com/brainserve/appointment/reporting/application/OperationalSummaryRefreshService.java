package com.brainserve.appointment.reporting.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

import java.time.LocalDate;
import java.time.ZoneId;

@Service
public class OperationalSummaryRefreshService {
    private final JdbcTemplate jdbc;
    private final ZoneId officeZone;

    public OperationalSummaryRefreshService(JdbcTemplate jdbc,
                                            @Value("${brainserve.appointment.office-zone:Asia/Kolkata}") String officeZone) {
        this.jdbc = jdbc;
        this.officeZone = ZoneId.of(officeZone);
    }

    @Scheduled(fixedDelayString = "${brainserve.reporting.summary-refresh-ms:60000}", initialDelayString = "15000")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void refreshCurrentSummary() {
        LocalDate today = LocalDate.now(officeZone);
        refreshDay(today);
        refreshMonth(today);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void refreshCommittedChanges() {
        LocalDate today = LocalDate.now(officeZone);
        Long generation = jdbc.queryForObject("select generation from reporting_source_revision where singleton for update", Long.class);
        Boolean current = jdbc.queryForObject("""
                select exists(select 1 from daily_operational_summary
                               where summary_date = ? and scope_key = 'GLOBAL' and source_generation = ?)
                   and exists(select 1 from monthly_operational_summary
                               where summary_month = ? and scope_key = 'GLOBAL' and source_generation = ?)
                """, Boolean.class, today, generation, today.withDayOfMonth(1), generation);
        // A transaction may publish multiple audit events. The first listener
        // refreshes its committed revision; later events reuse that same refresh.
        if (Boolean.TRUE.equals(current)) return;
        refreshDay(today);
        refreshMonth(today);
    }

    @Scheduled(cron = "${brainserve.reporting.history-maintenance-cron:0 15 0 * * *}",
            zone = "${brainserve.appointment.office-zone:Asia/Kolkata}")
    @Transactional
    public void maintainHistoryReadModel() {
        LocalDate today = LocalDate.now(officeZone);
        refreshDay(today.minusDays(1));
        refreshDay(today);
        refreshMonth(today.minusMonths(1));
        refreshMonth(today);
        for (int month = 0; month <= 3; month++) {
            LocalDate target = today.plusMonths(month).withDayOfMonth(1);
            for (String parent : new String[]{"audit_event_history", "visitor_checkpoint_event", "workboard_activity_event"}) {
                jdbc.queryForList("select ensure_brainserve_history_partition(?, ?)", parent, target);
            }
        }
    }

    public void refreshDay(LocalDate date) {
        jdbc.queryForList("select refresh_daily_operational_summary(?)", date);
    }

    public void refreshMonth(LocalDate date) {
        jdbc.queryForList("select refresh_monthly_operational_summary(?)", date.withDayOfMonth(1));
    }
}
