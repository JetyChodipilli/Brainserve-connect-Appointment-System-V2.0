package com.brainserve.appointment.reporting.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@Testcontainers(disabledWithoutDocker = true)
class KpiReconciliationIntegrationTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.2-alpine");
    static JdbcTemplate jdbc;
    final UUID actor = UUID.fromString("30000000-0000-0000-0000-000000000001");
    final UUID department = UUID.fromString("10000000-0000-0000-0000-000000000001");
    final UUID employee = UUID.fromString("20000000-0000-0000-0000-000000000001");
    RoleDashboardQueryService service;
    RoleDataScopeService scopes;

    @BeforeAll static void migrateAndSeed() throws Exception {
        // Exercise an upgrade with existing V37 data as well as a clean install.
        var flyway = Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        flyway.target("37").load().migrate();
        var ds = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = new JdbcTemplate(ds);
        jdbc.update("insert into daily_operational_summary(summary_date, scope_type, scope_key, refreshed_at) values ('2020-01-01', 'COMPANY', 'GLOBAL', now())");
        flyway.target("latest").load().migrate();
        try (var connection = ds.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("reporting/kpi-reconciliation.sql"));
        }
    }

    @BeforeEach @SuppressWarnings("unchecked") void prepare() {
        scopes = mock(RoleDataScopeService.class);
        scope("ROLE_MANAGER", department);
        var redis = mock(StringRedisTemplate.class);
        when(redis.opsForValue()).thenReturn(mock(ValueOperations.class));
        service = new RoleDashboardQueryService(new NamedParameterJdbcTemplate(jdbc), scopes, redis,
                new ObjectMapper().findAndRegisterModules(), "Asia/Kolkata", 180);
        jdbc.execute("select refresh_daily_operational_summary('2026-05-01')");
        jdbc.execute("select refresh_daily_operational_summary('2026-05-02')");
        jdbc.execute("select refresh_monthly_operational_summary('2026-05-01')");
    }
    void scope(String role, UUID dept) {
        when(scopes.resolve(actor)).thenReturn(new RoleDataScopeService.RoleDataScope(actor, employee, dept, role, dept == null));
    }
    RoleDashboardQueryService.DashboardSummary range(String from, String to) {
        return service.summary(actor, RoleDashboardQueryService.PeriodPreset.CUSTOM, LocalDate.parse(from), LocalDate.parse(to));
    }

    @Test void unequalDaysIncludeValidZeroExcludeNegativeAndNextDayBoundary() {
        assertThat(range("2026-05-01", "2026-05-02").averageWaitSeconds()).isEqualTo(80L);
        assertThat(range("2026-05-01", "2026-05-01").averageWaitSeconds()).isZero();
        assertThat(jdbc.queryForObject("select wait_sample_count from daily_operational_summary where summary_date='2026-05-02' and scope_key=?", Long.class, department.toString())).isEqualTo(2L);
    }
    @Test void companyUsesSamplesRatherThanArrivalsOrDepartmentAverages() {
        scope("ROLE_CEO", null);
        assertThat(range("2026-05-01", "2026-05-02").averageWaitSeconds()).isEqualTo(285L);
        assertThat(range("2026-05-01", "2026-05-31").averageWaitSeconds()).isEqualTo(252L);
    }
    @Test void departmentInsideCountDoesNotLeakCompanyAccessRecords() {
        assertThat(range("2026-05-01", "2026-05-02").visitorsInside()).isEqualTo(1L);
        scope("ROLE_CEO", null);
        assertThat(range("2026-05-01", "2026-05-02").visitorsInside()).isEqualTo(2L);
    }
    @Test void visitorOnlyRoleCannotReadCompanyWorkOrWorkforceTotals() {
        jdbc.update("update daily_operational_summary set assigned_work=12, completed_work=7, approved_work=4 where summary_date='2026-05-02' and scope_key='GLOBAL'");
        scope("ROLE_RECEPTIONIST", null);
        var summary = range("2026-05-01", "2026-05-02");
        assertThat(summary.totalEmployees()).isZero();
        assertThat(summary.activeEmployees()).isZero();
        assertThat(summary.assignedWork()).isZero();
        assertThat(summary.completedWork()).isZero();
        assertThat(summary.approvedWork()).isZero();
        assertThat(range("2026-05-01", "2026-05-31").totalEmployees()).isZero();
    }
    @Test void workforceUsesLatestSnapshotRatherThanPeakAcrossDays() {
        jdbc.update("update daily_operational_summary set total_employees=10, active_employees=9 where summary_date='2026-05-01' and scope_key=?", department.toString());
        jdbc.update("update daily_operational_summary set total_employees=4, active_employees=3 where summary_date='2026-05-02' and scope_key=?", department.toString());
        var summary = range("2026-05-01", "2026-05-02");
        assertThat(summary.totalEmployees()).isEqualTo(4L);
        assertThat(summary.activeEmployees()).isEqualTo(3L);
    }
    @Test void unavailableHistoricalDenominatorAndEmptyPeriodStayUnknown() {
        scope("ROLE_CEO", null);
        assertThat(range("2020-01-01", "2020-01-02").averageWaitSeconds()).isNull();
        assertThat(range("2020-02-01", "2020-02-02").averageWaitSeconds()).isNull();
        jdbc.update("update daily_operational_summary set wait_sample_count=null where summary_date='2026-05-01' and scope_key='GLOBAL'");
        assertThat(range("2026-05-01", "2026-05-02").averageWaitSeconds()).isNull();
    }
}
