package com.brainserve.appointment.reporting.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    @Transactional
    public void refreshCurrentSummary() {
        LocalDate today = LocalDate.now(officeZone);
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
