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
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.HashMap;
import java.util.Map;
import java.time.Duration;

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
    ValueOperations<String, String> cache;
    final Map<String, String> entries = new HashMap<>();

    @BeforeAll static void migrateAndSeed() throws Exception {
        // Exercise an upgrade with existing V49 data as well as a clean install.
        var flyway = Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        flyway.target("49").load().migrate();
        var ds = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = new JdbcTemplate(ds);
        jdbc.update("insert into daily_operational_summary(summary_date, scope_type, scope_key, refreshed_at) values ('2020-01-01', 'COMPANY', 'GLOBAL', now())");
        flyway.target("latest").load().migrate();
        try (var connection = ds.getConnection(); var statement = connection.createStatement()) {
            // PostgreSQL parses the dollar-quoted DO block; a generic semicolon
            // splitter would split its PL/pgSQL body into invalid statements.
            statement.execute(new ClassPathResource("reporting/kpi-reconciliation.sql").getContentAsString(StandardCharsets.UTF_8));
        }
    }

    @BeforeEach @SuppressWarnings("unchecked") void prepare() {
        scopes = mock(RoleDataScopeService.class);
        scope("ROLE_MANAGER", department);
        var redis = mock(StringRedisTemplate.class);
        cache = mock(ValueOperations.class);
        entries.clear();
        when(redis.opsForValue()).thenReturn(cache);
        when(cache.get(anyString())).thenAnswer(call -> entries.get(call.getArgument(0)));
        doAnswer(call -> { entries.put(call.getArgument(0), call.getArgument(1)); return null; })
                .when(cache).set(anyString(), anyString(), any(Duration.class));
        service = new RoleDashboardQueryService(new NamedParameterJdbcTemplate(jdbc), scopes, redis,
                new ObjectMapper().findAndRegisterModules(), "Asia/Kolkata", 180, 120);
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

    @Test void upgradeLeavesUnrefreshedLegacyRowsUnknownAndPreservesTheirValues() {
        assertThat(jdbc.queryForObject("select source_generation from daily_operational_summary where summary_date='2020-01-01' and scope_key='GLOBAL'", Long.class)).isNull();
        scope("ROLE_CEO", null);
        var legacy = range("2020-01-01", "2020-01-01");
        assertThat(legacy.freshness()).isEqualTo(RoleDashboardQueryService.Freshness.UNKNOWN);
        assertThat(legacy.scheduledVisits()).isZero();
        assertThat(legacy.averageWaitSeconds()).isNull();
    }

    @Test void rolledBackMutationDoesNotAdvanceTheCommittedSourceGeneration() {
        long before = revision();
        new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource())).executeWithoutResult(status -> {
            jdbc.update("update employee set designation = designation where id = ?", employee);
            assertThat(revision()).isGreaterThan(before);
            status.setRollbackOnly();
        });
        assertThat(revision()).isEqualTo(before);
    }

    @Test void sourceMutationBypassesCachedSummaryUntilRefreshedGenerationCommits() {
        var before = range("2026-05-01", "2026-05-02");
        assertThat(before.freshness()).isEqualTo(RoleDashboardQueryService.Freshness.FRESH);
        int cachedEntries = entries.size();
        Long original = jdbc.queryForObject("select count(*) from employee where department_id=? and status in ('ACTIVE','ON_LEAVE','NOTICE_PERIOD')", Long.class, department);
        try {
            jdbc.update("update employee set status = 'INACTIVE' where id = ?", employee);
            var dirty = range("2026-05-01", "2026-05-02");
            assertThat(dirty.freshness()).isEqualTo(RoleDashboardQueryService.Freshness.STALE);
            assertThat(dirty.sourceGeneration()).isEqualTo(before.sourceGeneration());
            assertThat(entries).hasSize(cachedEntries);
            jdbc.execute("select refresh_daily_operational_summary('2026-05-01')");
            jdbc.execute("select refresh_daily_operational_summary('2026-05-02')");
            var refreshed = range("2026-05-01", "2026-05-02");
            assertThat(refreshed.freshness()).isEqualTo(RoleDashboardQueryService.Freshness.FRESH);
            assertThat(refreshed.sourceGeneration()).isGreaterThan(before.sourceGeneration());
            assertThat(refreshed.activeEmployees()).isEqualTo(original - 1);
            assertThat(refreshed.averageWaitSeconds()).isEqualTo(80L);
            assertThat(refreshed.sourceRefreshedAt()).isAfterOrEqualTo(before.sourceRefreshedAt());
        } finally {
            jdbc.update("update employee set status = 'ACTIVE' where id = ?", employee);
        }
    }

    @Test void rolledBackRefreshCannotStampAnOldSummaryWithTheNewGeneration() {
        long before = revision();
        jdbc.update("update employee set designation=designation where id = ?", employee);
        long changed = revision();
        new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource())).executeWithoutResult(status -> {
            jdbc.execute("select refresh_daily_operational_summary('2026-05-01')");
            assertThat(jdbc.queryForObject("select source_generation from daily_operational_summary where summary_date='2026-05-01' and scope_key='GLOBAL'", Long.class)).isEqualTo(changed);
            status.setRollbackOnly();
        });
        assertThat(jdbc.queryForObject("select source_generation from daily_operational_summary where summary_date='2026-05-01' and scope_key='GLOBAL'", Long.class)).isEqualTo(before);
        assertThat(range("2026-05-01", "2026-05-02").freshness()).isEqualTo(RoleDashboardQueryService.Freshness.STALE);
    }

    private long revision() {
        return jdbc.queryForObject("select generation from reporting_source_revision where singleton", Long.class);
    }
}
