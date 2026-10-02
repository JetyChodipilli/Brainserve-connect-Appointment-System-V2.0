package com.brainserve.appointment.reporting.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.brainserve.appointment.shared.application.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
public class RoleDashboardQueryService {
    private static final String EMPLOYEE = "ROLE_EMPLOYEE";
    private static final String RECEPTIONIST = "ROLE_RECEPTIONIST";
    private static final String SECURITY = "ROLE_SECURITY";

    private final NamedParameterJdbcTemplate jdbc;
    private final RoleDataScopeService scopes;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final ZoneId officeZone;
    private final Duration cacheTtl;
    private final Duration freshnessWindow;

    public RoleDashboardQueryService(NamedParameterJdbcTemplate jdbc, RoleDataScopeService scopes,
                                     StringRedisTemplate redis,
                                     ObjectMapper objectMapper,
                                     @Value("${brainserve.appointment.office-zone:Asia/Kolkata}") String officeZone,
                                     @Value("${brainserve.reporting.dashboard-cache-seconds:180}") long cacheSeconds,
                                     @Value("${brainserve.reporting.dashboard-fresh-seconds:120}") long freshSeconds) {
        this.jdbc = jdbc;
        this.scopes = scopes;
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.officeZone = ZoneId.of(officeZone);
        this.cacheTtl = Duration.ofSeconds(Math.max(60, Math.min(cacheSeconds, 300)));
        this.freshnessWindow = Duration.ofSeconds(Math.max(30, Math.min(freshSeconds, 600)));
    }

    @Transactional(readOnly = true)
    public DashboardSummary summary(UUID actorUserId, PeriodPreset preset, LocalDate customFrom, LocalDate customTo) {
        var scope = scopes.resolve(actorUserId);
        if (!scope.organizationWide() && (scope.departmentId() == null || scope.employeeId() == null)) {
            throw new BusinessException("HISTORY_SCOPE_DENIED", "Your reporting department is not assigned", HttpStatus.FORBIDDEN);
        }
        DateRange range = range(preset, customFrom, customTo);
        SourceState source = sourceState(scope, range);
        boolean personal = scope.role().equals(EMPLOYEE);
        String key = "reporting:dashboard:v5:" + actorUserId + ":" + scope.role() + ":" + scope.departmentId()
                + ":" + scope.employeeId() + ":" + range.from() + ":" + range.to() + ":" + source.cacheVersion();
        DashboardSummary cached = source.cacheable(personal, Instant.now(), freshnessWindow) ? readCache(key) : null;
        if (cached != null && cacheMatches(cached, scope, range, source) && cached.freshUntil() != null
                && Instant.now().isBefore(cached.freshUntil())) {
            requireUnchangedScope(actorUserId, scope);
            if (source.equals(sourceState(scope, range))) return cached;
        }

        DashboardSummary summary = personal ? personalSummary(scope, range) : aggregateSummary(scope, range);
        requireUnchangedScope(actorUserId, scope);
        SourceState after = sourceState(scope, range);
        boolean stable = source.equals(after);
        Instant watermark = personal ? summary.sourceRefreshedAt() : source.oldestRefresh();
        Long generation = personal ? source.currentGeneration() : source.sourceGeneration();
        Instant freshUntil = watermark == null ? null : watermark.plus(freshnessWindow);
        Freshness freshness = source.freshness(watermark, stable, Instant.now(), freshnessWindow);
        summary = summary.withFreshness(watermark, generation, freshness, freshUntil, personal ? "LIVE" : "SUMMARY");
        // A request racing a mutation or refresh must never place its old result
        // under the new committed generation. Dirty/unknown read models bypass Redis.
        if (stable && freshness == Freshness.FRESH) writeCache(key, summary);
        return summary;
    }

    private void requireUnchangedScope(UUID actorUserId, RoleDataScopeService.RoleDataScope expected) {
        if (!expected.equals(scopes.resolve(actorUserId))) {
            throw new BusinessException("HISTORY_SCOPE_CHANGED", "Your reporting scope changed. Reload your workspace.", HttpStatus.FORBIDDEN);
        }
    }

    private boolean cacheMatches(DashboardSummary cached, RoleDataScopeService.RoleDataScope scope,
                                 DateRange range, SourceState source) {
        return scope.role().equals(cached.role()) && Objects.equals(scope.departmentId(), cached.departmentId())
                && range.from().equals(cached.from()) && range.to().equals(cached.to())
                && Objects.equals(source.currentGeneration(), cached.sourceGeneration())
                && cached.freshness() == Freshness.FRESH;
    }

    private SourceState sourceState(RoleDataScopeService.RoleDataScope scope, DateRange range) {
        if (scope.role().equals(EMPLOYEE)) {
            Long generation = jdbc.queryForObject("select generation from reporting_source_revision where singleton",
                    new MapSqlParameterSource(), Long.class);
            return new SourceState(generation, generation, null, null, generation != null);
        }
        boolean month = usesMonthlySummary(range);
        String table = month ? "monthly_operational_summary" : "daily_operational_summary";
        String column = month ? "summary_month" : "summary_date";
        var parameters = new MapSqlParameterSource().addValue("from", range.from()).addValue("to", range.to())
                .addValue("scopeType", scope.departmentId() == null ? "COMPANY" : "DEPARTMENT")
                .addValue("scopeKey", scope.departmentId() == null ? "GLOBAL" : scope.departmentId().toString())
                .addValue("expected", month ? 1L : ChronoUnit.DAYS.between(range.from(), range.to()) + 1);
        return jdbc.queryForObject("""
                select revision.generation, snapshot.* from reporting_source_revision revision
                cross join (select min(source_generation) source_generation, min(refreshed_at) oldest_refresh,
                                   max(refreshed_at) newest_refresh,
                                   count(*) = :expected and count(source_generation) = :expected complete
                              from %s where %s >= :from and %s <= :to
                               and scope_type = :scopeType and scope_key = :scopeKey) snapshot
                where revision.singleton
                """.formatted(table, column, column), parameters, (result, row) -> new SourceState(
                result.getLong("generation"), result.getObject("source_generation", Long.class),
                result.getTimestamp("oldest_refresh") == null ? null : result.getTimestamp("oldest_refresh").toInstant(),
                result.getTimestamp("newest_refresh") == null ? null : result.getTimestamp("newest_refresh").toInstant(),
                result.getBoolean("complete")));
    }

    private boolean usesMonthlySummary(DateRange range) {
        return range.from().getDayOfMonth() == 1
                && range.to().equals(range.from().with(TemporalAdjusters.lastDayOfMonth()))
                && range.to().isBefore(LocalDate.now(officeZone).withDayOfMonth(1));
    }

    private DashboardSummary aggregateSummary(RoleDataScopeService.RoleDataScope scope, DateRange range) {
        UUID departmentId = scope.departmentId();
        String scopeType = departmentId == null ? "COMPANY" : "DEPARTMENT";
        String scopeKey = departmentId == null ? "GLOBAL" : departmentId.toString();
        if (usesMonthlySummary(range)) {
            return aggregateMonth(scope, range, scopeType, scopeKey);
        }
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("from", range.from()).addValue("to", range.to().plusDays(1))
                .addValue("scopeType", scopeType).addValue("scopeKey", scopeKey);
        List<DashboardSummary> rows = jdbc.query("""
                SELECT COALESCE(sum(waiting_visits), 0) awaiting_approval,
                       COALESCE(sum(approved_visits), 0) active_visits,
                       COALESCE(sum(scheduled_visits), 0) scheduled_visits,
                       COALESCE(sum(arrived_visits), 0) arrived_visits,
                       COALESCE(sum(completed_visits), 0) completed_visits,
                       COALESCE(sum(cancelled_visits), 0) cancelled_visits,
                       COALESCE(sum(rejected_visits), 0) rejected_visits,
                       COALESCE((array_agg(total_employees ORDER BY summary_date DESC))[1], 0) total_employees,
                       COALESCE((array_agg(active_employees ORDER BY summary_date DESC))[1], 0) active_employees,
                       COALESCE(sum(assigned_work), 0) assigned_work,
                       COALESCE(sum(in_progress_work), 0) in_progress_work,
                       COALESCE(sum(completed_work), 0) completed_work,
                       COALESCE(sum(approved_work), 0) approved_work,
                       CASE WHEN count(wait_sample_count) = count(*)
                            THEN round(sum(wait_seconds_total) / NULLIF(sum(wait_sample_count), 0))::bigint
                            ELSE NULL END average_wait_seconds
                  FROM daily_operational_summary
                 WHERE summary_date >= :from AND summary_date < :to
                   AND scope_type = :scopeType AND scope_key = :scopeKey
                """, parameters, (result, row) -> new DashboardSummary(
                result.getLong("awaiting_approval"), result.getLong("active_visits"), visitorsInside(scope.departmentId()),
                hideWorkforce(scope) ? 0 : result.getLong("total_employees"),
                hideWorkforce(scope) ? 0 : result.getLong("active_employees"),
                result.getLong("scheduled_visits"), result.getLong("arrived_visits"),
                result.getLong("completed_visits"), result.getLong("cancelled_visits"),
                result.getLong("rejected_visits"), hideWorkforce(scope) ? 0 : result.getLong("assigned_work"),
                hideWorkforce(scope) ? 0 : result.getLong("in_progress_work"), hideWorkforce(scope) ? 0 : result.getLong("completed_work"),
                hideWorkforce(scope) ? 0 : result.getLong("approved_work"), result.getObject("average_wait_seconds", Long.class),
                scope.role(), scopeType, departmentId, range.from(), range.to(), Instant.now(), null, null, null, null, null));
        return rows.isEmpty() ? empty(scope, range) : rows.getFirst();
    }

    private DashboardSummary aggregateMonth(RoleDataScopeService.RoleDataScope scope, DateRange range,
                                            String scopeType, String scopeKey) {
        long workforceAtMonthEnd = workforceAt(scope.departmentId(), range.to());
        MapSqlParameterSource parameters = new MapSqlParameterSource().addValue("month", range.from())
                .addValue("scopeType", scopeType).addValue("scopeKey", scopeKey);
        List<DashboardSummary> rows = jdbc.query("""
                SELECT scheduled_visits, arrived_visits, waiting_visits, approved_visits,
                       completed_visits, cancelled_visits, rejected_visits,
                       round(wait_seconds_total / NULLIF(wait_sample_count, 0))::bigint average_wait_seconds,
                       joined_employees, relieved_employees, assigned_work, completed_work, approved_work
                  FROM monthly_operational_summary
                 WHERE summary_month = :month AND scope_type = :scopeType AND scope_key = :scopeKey
                """, parameters, (result, row) -> new DashboardSummary(
                result.getLong("waiting_visits"), result.getLong("approved_visits"), 0,
                hideWorkforce(scope) ? 0 : workforceAtMonthEnd, hideWorkforce(scope) ? 0 : workforceAtMonthEnd,
                result.getLong("scheduled_visits"), result.getLong("arrived_visits"),
                result.getLong("completed_visits"), result.getLong("cancelled_visits"),
                result.getLong("rejected_visits"), hideWorkforce(scope) ? 0 : result.getLong("assigned_work"), 0,
                hideWorkforce(scope) ? 0 : result.getLong("completed_work"), hideWorkforce(scope) ? 0 : result.getLong("approved_work"),
                result.getObject("average_wait_seconds", Long.class), scope.role(), scopeType, scope.departmentId(),
                range.from(), range.to(), Instant.now(), null, null, null, null, null));
        return rows.isEmpty() ? empty(scope, range) : rows.getFirst();
    }

    private long workforceAt(UUID departmentId, LocalDate date) {
        MapSqlParameterSource parameters = new MapSqlParameterSource().addValue("date", date)
                .addValue("departmentId", departmentId);
        Long count = jdbc.queryForObject("""
                select count(*) from employee
                 where joining_date <= :date and (relieving_date is null or relieving_date > :date)
                   and (CAST(:departmentId AS uuid) is null or department_id = :departmentId)
                """, parameters, Long.class);
        return count == null ? 0 : count;
    }

    private DashboardSummary personalSummary(RoleDataScopeService.RoleDataScope scope, DateRange range) {
        OffsetDateTime from = range.from().atStartOfDay(officeZone).toOffsetDateTime();
        OffsetDateTime to = range.to().plusDays(1).atStartOfDay(officeZone).toOffsetDateTime();
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("employeeId", scope.employeeId())
                .addValue("from", from)
                .addValue("to", to);
        return jdbc.queryForObject("""
                SELECT statement_timestamp() source_checked_at,
                       (SELECT count(*) FROM appointment WHERE host_employee_id = :employeeId
                         AND slot_start >= :from AND slot_start < :to AND status LIKE 'PENDING_%') awaiting_approval,
                       (SELECT count(*) FROM appointment WHERE host_employee_id = :employeeId
                         AND slot_start >= :from AND slot_start < :to AND status IN ('APPROVED','CHECKED_IN')) active_visits,
                       (SELECT count(*) FROM appointment WHERE host_employee_id = :employeeId
                         AND slot_start >= :from AND slot_start < :to) scheduled_visits,
                       (SELECT count(*) FROM appointment WHERE host_employee_id = :employeeId
                         AND slot_start >= :from AND slot_start < :to AND security_intake_at IS NOT NULL) arrived_visits,
                       (SELECT count(*) FROM appointment WHERE host_employee_id = :employeeId
                         AND slot_start >= :from AND slot_start < :to AND status = 'COMPLETED') completed_visits,
                       (SELECT count(*) FROM department_work_task WHERE employee_id = :employeeId
                         AND created_at >= :from AND created_at < :to) assigned_work,
                       (SELECT count(*) FROM department_work_task WHERE employee_id = :employeeId
                         AND status = 'IN_PROGRESS') in_progress_work,
                       (SELECT count(*) FROM department_work_task WHERE employee_id = :employeeId
                         AND status = 'COMPLETED') completed_work,
                       (SELECT count(*) FROM department_work_task WHERE employee_id = :employeeId
                         AND status IN ('APPROVED','ACKNOWLEDGED')) approved_work
                """, parameters, (result, row) -> new DashboardSummary(
                result.getLong("awaiting_approval"), result.getLong("active_visits"), 0, 1, 1,
                result.getLong("scheduled_visits"), result.getLong("arrived_visits"),
                result.getLong("completed_visits"), 0, 0, hideWorkforce(scope) ? 0 : result.getLong("assigned_work"),
                hideWorkforce(scope) ? 0 : result.getLong("in_progress_work"), hideWorkforce(scope) ? 0 : result.getLong("completed_work"),
                hideWorkforce(scope) ? 0 : result.getLong("approved_work"), null, scope.role(), "PERSONAL", scope.departmentId(),
                range.from(), range.to(), Instant.now(), result.getTimestamp("source_checked_at").toInstant(), null, null, null, null));
    }

    private long visitorsInside(UUID departmentId) {
        Long value = jdbc.queryForObject("""
                select count(*) from visit_access_record access
                  join appointment on appointment.id = access.appointment_id
                 where access.checked_out_at is null
                   and (CAST(:departmentId AS uuid) is null or appointment.routing_department_id = :departmentId)
                """, new MapSqlParameterSource("departmentId", departmentId), Long.class);
        return value == null ? 0 : value;
    }

    private boolean hideWorkforce(RoleDataScopeService.RoleDataScope scope) {
        return scope.role().equals(RECEPTIONIST) || scope.role().equals(SECURITY);
    }

    private DashboardSummary empty(RoleDataScopeService.RoleDataScope scope, DateRange range) {
        return new DashboardSummary(0, 0, visitorsInside(scope.departmentId()), 0, 0, 0, 0, 0, 0, 0,
                0, 0, 0, 0, null, scope.role(), scope.departmentId() == null ? "COMPANY" : "DEPARTMENT",
                scope.departmentId(), range.from(), range.to(), Instant.now(), null, null, null, null, null);
    }

    private DateRange range(PeriodPreset preset, LocalDate customFrom, LocalDate customTo) {
        LocalDate today = LocalDate.now(officeZone);
        PeriodPreset selected = preset == null ? PeriodPreset.TODAY : preset;
        DateRange value = switch (selected) {
            case TODAY -> new DateRange(today, today);
            case YESTERDAY -> new DateRange(today.minusDays(1), today.minusDays(1));
            case LAST_7_DAYS -> new DateRange(today.minusDays(6), today);
            case THIS_MONTH -> new DateRange(today.withDayOfMonth(1), today);
            case PREVIOUS_MONTH -> {
                LocalDate previous = today.minusMonths(1);
                yield new DateRange(previous.withDayOfMonth(1), previous.with(TemporalAdjusters.lastDayOfMonth()));
            }
            case CUSTOM -> new DateRange(customFrom, customTo);
        };
        if (value.from() == null || value.to() == null || value.from().isAfter(value.to())
                || Duration.between(value.from().atStartOfDay(officeZone),
                value.to().plusDays(1).atStartOfDay(officeZone)).toDays() > 366) {
            throw new com.brainserve.appointment.shared.application.BusinessException("INVALID_DASHBOARD_RANGE",
                    "Choose a dashboard range of 366 days or less",
                    org.springframework.http.HttpStatus.BAD_REQUEST);
        }
        return value;
    }

    private DashboardSummary readCache(String key) {
        try {
            String json = redis.opsForValue().get(key);
            return json == null ? null : objectMapper.readValue(json, DashboardSummary.class);
        } catch (DataAccessException | com.fasterxml.jackson.core.JsonProcessingException ignored) {
            return null;
        }
    }

    private void writeCache(String key, DashboardSummary summary) {
        try { redis.opsForValue().set(key, objectMapper.writeValueAsString(summary), cacheTtl); }
        catch (DataAccessException | com.fasterxml.jackson.core.JsonProcessingException ignored) {
            // Dashboard queries deliberately fail open when Redis is unavailable.
        }
    }

    public enum PeriodPreset { TODAY, YESTERDAY, LAST_7_DAYS, THIS_MONTH, PREVIOUS_MONTH, CUSTOM }
    public enum Freshness { FRESH, STALE, UNKNOWN }
    record SourceState(Long currentGeneration, Long sourceGeneration, Instant oldestRefresh,
                       Instant newestRefresh, boolean complete) {
        String cacheVersion() { return currentGeneration + ":" + sourceGeneration + ":" + oldestRefresh + ":" + newestRefresh; }
        boolean cacheable(boolean personal, Instant now, Duration window) {
            return personal ? complete : freshness(oldestRefresh, true, now, window) == Freshness.FRESH;
        }
        Freshness freshness(Instant watermark, boolean stable, Instant now, Duration window) {
            if (!complete || watermark == null || sourceGeneration == null || currentGeneration == null) return Freshness.UNKNOWN;
            if (!stable || !Objects.equals(sourceGeneration, currentGeneration)
                    || watermark.isAfter(now) || !now.isBefore(watermark.plus(window))) return Freshness.STALE;
            return Freshness.FRESH;
        }
    }
    private record DateRange(LocalDate from, LocalDate to) {}
    public record DashboardSummary(long awaitingApproval, long activeVisits, long visitorsInside,
                                   long totalEmployees, long activeEmployees, long scheduledVisits,
                                   long arrivedVisits, long completedVisits, long cancelledVisits,
                                   long rejectedVisits, long assignedWork, long inProgressWork,
                                   long completedWork, long approvedWork, Long averageWaitSeconds,
                                   String role, String scope, UUID departmentId,
                                   LocalDate from, LocalDate to, Instant generatedAt,
                                   Instant sourceRefreshedAt, Long sourceGeneration, Freshness freshness,
                                   Instant freshUntil, String sourceType) {
        DashboardSummary withFreshness(Instant watermark, Long generation, Freshness state, Instant expiresAt, String type) {
            return new DashboardSummary(awaitingApproval, activeVisits, visitorsInside, totalEmployees, activeEmployees,
                    scheduledVisits, arrivedVisits, completedVisits, cancelledVisits, rejectedVisits, assignedWork,
                    inProgressWork, completedWork, approvedWork, averageWaitSeconds, role, scope, departmentId,
                    from, to, generatedAt, watermark, generation, state, expiresAt, type);
        }
    }
}
