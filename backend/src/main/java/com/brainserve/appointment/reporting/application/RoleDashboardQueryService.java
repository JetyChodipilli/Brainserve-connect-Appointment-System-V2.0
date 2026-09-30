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
import java.util.List;
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

    public RoleDashboardQueryService(NamedParameterJdbcTemplate jdbc, RoleDataScopeService scopes,
                                     StringRedisTemplate redis,
                                     ObjectMapper objectMapper,
                                     @Value("${brainserve.appointment.office-zone:Asia/Kolkata}") String officeZone,
                                     @Value("${brainserve.reporting.dashboard-cache-seconds:180}") long cacheSeconds) {
        this.jdbc = jdbc;
        this.scopes = scopes;
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.officeZone = ZoneId.of(officeZone);
        this.cacheTtl = Duration.ofSeconds(Math.max(60, Math.min(cacheSeconds, 300)));
    }

    @Transactional(readOnly = true)
    public DashboardSummary summary(UUID actorUserId, PeriodPreset preset, LocalDate customFrom, LocalDate customTo) {
        var scope = scopes.resolve(actorUserId);
        if (!scope.organizationWide() && (scope.departmentId() == null || scope.employeeId() == null)) {
            throw new BusinessException("HISTORY_SCOPE_DENIED", "Your reporting department is not assigned", HttpStatus.FORBIDDEN);
        }
        DateRange range = range(preset, customFrom, customTo);
        String key = "reporting:dashboard:v3:" + actorUserId + ":" + scope.role() + ":" + scope.departmentId() + ":" + scope.employeeId() + ":" + range.from() + ":" + range.to();
        DashboardSummary cached = readCache(key);
        if (cached != null) return cached;

        DashboardSummary summary = scope.role().equals(EMPLOYEE)
                ? personalSummary(scope, range) : aggregateSummary(scope, range);
        writeCache(key, summary);
        return summary;
    }

    private DashboardSummary aggregateSummary(RoleDataScopeService.RoleDataScope scope, DateRange range) {
        UUID departmentId = scope.departmentId();
        String scopeType = departmentId == null ? "COMPANY" : "DEPARTMENT";
        String scopeKey = departmentId == null ? "GLOBAL" : departmentId.toString();
        LocalDate today = LocalDate.now(officeZone);
        if (range.from().getDayOfMonth() == 1
                && range.to().equals(range.from().with(TemporalAdjusters.lastDayOfMonth()))
                && range.to().isBefore(today.withDayOfMonth(1))) {
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
                       COALESCE(max(total_employees), 0) total_employees,
                       COALESCE(max(active_employees), 0) active_employees,
                       COALESCE(sum(assigned_work), 0) assigned_work,
                       COALESCE(sum(in_progress_work), 0) in_progress_work,
                       COALESCE(sum(completed_work), 0) completed_work,
                       COALESCE(sum(approved_work), 0) approved_work,
                       COALESCE(avg(NULLIF(average_wait_seconds, 0)), 0)::bigint average_wait_seconds
                  FROM daily_operational_summary
                 WHERE summary_date >= :from AND summary_date < :to
                   AND scope_type = :scopeType AND scope_key = :scopeKey
                """, parameters, (result, row) -> new DashboardSummary(
                result.getLong("awaiting_approval"), result.getLong("active_visits"), visitorsInside(),
                hideWorkforce(scope) ? 0 : result.getLong("total_employees"),
                hideWorkforce(scope) ? 0 : result.getLong("active_employees"),
                result.getLong("scheduled_visits"), result.getLong("arrived_visits"),
                result.getLong("completed_visits"), result.getLong("cancelled_visits"),
                result.getLong("rejected_visits"), result.getLong("assigned_work"),
                result.getLong("in_progress_work"), result.getLong("completed_work"),
                result.getLong("approved_work"), result.getLong("average_wait_seconds"),
                scope.role(), scopeType, departmentId, range.from(), range.to(), Instant.now()));
        return rows.isEmpty() ? empty(scope, range) : rows.getFirst();
    }

    private DashboardSummary aggregateMonth(RoleDataScopeService.RoleDataScope scope, DateRange range,
                                            String scopeType, String scopeKey) {
        long workforceAtMonthEnd = workforceAt(scope.departmentId(), range.to());
        MapSqlParameterSource parameters = new MapSqlParameterSource().addValue("month", range.from())
                .addValue("scopeType", scopeType).addValue("scopeKey", scopeKey);
        List<DashboardSummary> rows = jdbc.query("""
                SELECT scheduled_visits, arrived_visits, waiting_visits, approved_visits,
                       completed_visits, cancelled_visits, rejected_visits, average_wait_seconds,
                       joined_employees, relieved_employees, assigned_work, completed_work, approved_work
                  FROM monthly_operational_summary
                 WHERE summary_month = :month AND scope_type = :scopeType AND scope_key = :scopeKey
                """, parameters, (result, row) -> new DashboardSummary(
                result.getLong("waiting_visits"), result.getLong("approved_visits"), 0,
                workforceAtMonthEnd, workforceAtMonthEnd,
                result.getLong("scheduled_visits"), result.getLong("arrived_visits"),
                result.getLong("completed_visits"), result.getLong("cancelled_visits"),
                result.getLong("rejected_visits"), result.getLong("assigned_work"), 0,
                result.getLong("completed_work"), result.getLong("approved_work"),
                result.getLong("average_wait_seconds"), scope.role(), scopeType, scope.departmentId(),
                range.from(), range.to(), Instant.now()));
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
                SELECT (SELECT count(*) FROM appointment WHERE host_employee_id = :employeeId
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
                result.getLong("completed_visits"), 0, 0, result.getLong("assigned_work"),
                result.getLong("in_progress_work"), result.getLong("completed_work"),
                result.getLong("approved_work"), 0, scope.role(), "PERSONAL", scope.departmentId(),
                range.from(), range.to(), Instant.now()));
    }

    private long visitorsInside() {
        Long value = jdbc.getJdbcTemplate().queryForObject(
                "select count(*) from visit_access_record where checked_out_at is null", Long.class);
        return value == null ? 0 : value;
    }

    private boolean hideWorkforce(RoleDataScopeService.RoleDataScope scope) {
        return scope.role().equals(RECEPTIONIST) || scope.role().equals(SECURITY);
    }

    private DashboardSummary empty(RoleDataScopeService.RoleDataScope scope, DateRange range) {
        return new DashboardSummary(0, 0, visitorsInside(), 0, 0, 0, 0, 0, 0, 0,
                0, 0, 0, 0, 0, scope.role(), scope.departmentId() == null ? "COMPANY" : "DEPARTMENT",
                scope.departmentId(), range.from(), range.to(), Instant.now());
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
    private record DateRange(LocalDate from, LocalDate to) {}
    public record DashboardSummary(long awaitingApproval, long activeVisits, long visitorsInside,
                                   long totalEmployees, long activeEmployees, long scheduledVisits,
                                   long arrivedVisits, long completedVisits, long cancelledVisits,
                                   long rejectedVisits, long assignedWork, long inProgressWork,
                                   long completedWork, long approvedWork, long averageWaitSeconds,
                                   String role, String scope, UUID departmentId,
                                   LocalDate from, LocalDate to, Instant generatedAt) {}
}
