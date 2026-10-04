package com.brainserve.appointment.reporting.application;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.brainserve.appointment.iam.api.CurrentAccountAuthority;
import com.brainserve.appointment.iam.api.CurrentAccountAuthority.Authority;
import com.brainserve.appointment.operations.api.DashboardDependencyProbe;
import com.brainserve.appointment.reporting.application.RoleDashboardQueryService.PeriodPreset;
import com.brainserve.appointment.shared.application.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.*;

@Service
public class AdministrationDashboardService {
    public static final String VERSION = "sprint3.v1";
    private static final String ADMIN = "ROLE_SYSTEM_ADMIN", CEO = "ROLE_CEO";
    private static final List<String> ADMIN_CARDS = List.of("OPS01", "OPS04", "IAM03", "VIS08", "NTF03", "OPS07");
    private static final List<String> CEO_CARDS = List.of("VIS02", "VIS05", "VIS07", "VIS09", "WORK03", "WORK07");
    private static final Set<String> SAFE_DEPENDENCIES = Set.of("PostgreSQL", "Redis", "Kafka", "SMTP", "Object storage", "ClamAV");
    private final NamedParameterJdbcTemplate jdbc;
    private final CurrentAccountAuthority authorities;
    private final DashboardDependencyProbe integrations;
    private final ZoneId officeZone;
    private final Clock clock;

    @Autowired
    public AdministrationDashboardService(NamedParameterJdbcTemplate jdbc, CurrentAccountAuthority authorities, DashboardDependencyProbe integrations,
            @Value("${brainserve.appointment.office-zone:Asia/Kolkata}") String officeZone) {
        this(jdbc, authorities, integrations, ZoneId.of(officeZone), Clock.systemUTC());
    }

    AdministrationDashboardService(NamedParameterJdbcTemplate jdbc, CurrentAccountAuthority authorities, DashboardDependencyProbe integrations,
            ZoneId officeZone, Clock clock) {
        this.jdbc = jdbc; this.authorities = authorities; this.integrations = integrations; this.officeZone = officeZone; this.clock = clock;
    }

    @Transactional(readOnly = true)
    public DashboardCards cards(UUID userId, PeriodPreset preset, LocalDate from, LocalDate to) {
        Authority actor = authorize(userId);
        Instant asOf = clock.instant();
        DateRange range = range(preset, from, to, asOf);
        Long generation = generation();
        var params = parameters(range, asOf).addValue("employeeId", actor.employeeId());
        List<String> first = actor.role().equals(ADMIN) ? ADMIN_CARDS : CEO_CARDS;
        List<String> extra = actor.role().equals(ADMIN) ? List.of("OPS08", "OPS09") : List.of("VIS14");
        List<MetricCard> cards = first.stream().map(id -> measure(metric(id), actor, range, asOf, params)).toList();
        List<MetricCard> supplementary = extra.stream().map(id -> measure(metric(id), actor, range, asOf, params)).toList();
        List<CoverageItem> coverage = coverage(actor, params);
        requireUnchanged(userId, actor);
        boolean stable = Objects.equals(generation, generation());
        if (!stable) {
            cards = cards.stream().map(MetricCard::stale).toList();
            supplementary = supplementary.stream().map(MetricCard::stale).toList();
        }
        return new DashboardCards(VERSION, actor.role(), "COMPANY", null, range.from(), range.to(),
                officeZone.getId(), asOf, stable ? generation : null, cards, supplementary, coverage);
    }

    @Transactional(readOnly = true)
    public MetricRecords records(UUID userId, String id, PeriodPreset preset, LocalDate from, LocalDate to, int page, int size) {
        Authority actor = authorize(userId);
        Metric metric = metric(id);
        requireMetricRole(actor, metric);
        if (!actor.permissions().contains(metric.permission())) throw denied("This measurement is restricted by your current permissions");
        if (page < 0 || page > 10_000 || size < 1 || size > 100) {
            throw new BusinessException("INVALID_DASHBOARD_PAGE", "Choose page 0–10000 and size 1–100", HttpStatus.BAD_REQUEST);
        }
        Instant asOf = clock.instant();
        DateRange range = range(preset, from, to, asOf);
        var params = parameters(range, asOf).addValue("employeeId", actor.employeeId()).addValue("limit", size).addValue("offset", (long) page * size);
        MetricCard card;
        long total = 0;
        List<RecordItem> items = List.of();
        if (id.equals("OPS04")) {
            DependencySnapshot dependency = dependencies();
            card = dependencyCard(metric, dependency);
            total = dependency.rows().size();
            int start = (int) Math.min(total, (long) page * size);
            items = dependency.rows().subList(start, Math.min((int) total, start + size));
        } else {
            card = measure(metric, actor, range, asOf, params);
            QueryPlan plan = queryPlan(id);
            if (plan != null && (card.state().equals("AVAILABLE") || card.state().equals("NOT_APPLICABLE"))) {
                // The same relation drives card statistics, count and paginated records.
                var rows = jdbc.query("with observations as materialized (" + plan.rows() + ") "
                        + "select paged.*, totals.n from (select count(*) n from observations) totals left join lateral "
                        + "(select id, label, detail, status, occurred_at, kind from observations "
                        + "order by occurred_at desc nulls last, id asc limit :limit offset :offset) paged on true "
                        + "order by occurred_at desc nulls last, id asc", params, (rs, n) -> new PagedRow(rs.getLong("n"),
                        rs.getString("id") == null ? null : new RecordItem(rs.getString("id"), rs.getString("label"), rs.getString("detail"),
                                rs.getString("status"), rs.getTimestamp("occurred_at") == null ? null
                                : rs.getTimestamp("occurred_at").toInstant(), rs.getString("kind"))));
                total = rows.getFirst().total();
                items = rows.stream().map(PagedRow::item).filter(Objects::nonNull).toList();
            }
        }
        requireUnchanged(userId, actor);
        return new MetricRecords(id, VERSION, card.state(), card.reason(), asOf, range.from(), range.to(),
                page, size, total, (int) ((total + size - 1) / size), items);
    }

    private MetricCard measure(Metric metric, Authority actor, DateRange range, Instant asOf, MapSqlParameterSource params) {
        requireMetricRole(actor, metric);
        if (!actor.permissions().contains(metric.permission())) return empty(metric, "RESTRICTED", "Your current permissions restrict this measurement");
        if (metric.id().equals("OPS04")) return dependencyCard(metric, dependencies());
        if (metric.id().equals("OPS09")) {
            return value(metric, null, "PARTIAL", "Live source queries with partial coverage: workflow journeys, stage deadlines and verified backup/restore evidence are unavailable. Freshness is measured from this read; retained original deadlines and submission acceptance histories also have declared legacy gaps.",
                    asOf, 30, null, null, null, null, false);
        }
        if (metric.unavailableReason() != null) return empty(metric, "UNAVAILABLE", metric.unavailableReason());
        QueryPlan plan = queryPlan(metric.id());
        Statistics stats = statistics(plan, params, metric.id().equals("VIS09"));
        long excluded = plan.excluded() == null ? 0 : Objects.requireNonNull(jdbc.queryForObject(plan.excluded(), params, Long.class));
        Double coverage = stats.count() + excluded == 0 ? null : 100d * stats.count() / (stats.count() + excluded);
        String reason = null;
        Double numeric = (double) stats.count();
        if (metric.id().equals("VIS09")) {
            if (stats.count() == 0) return withDiagnostics(empty(metric, "NOT_APPLICABLE", "No valid raw wait observations in this period"), 0L, 0L, coverage, excluded, asOf, 60, true);
            numeric = stats.p95();
            reason = "Raw check-in minus first recorded security intake; missing or negative waits are excluded. Zero-second waits are retained.";
        } else if (metric.id().equals("WORK07")) {
            // Missing legacy originals cannot establish that an empty due cohort is real.
            if (stats.count() == 0) return withDiagnostics(empty(metric, excluded > 0 ? "UNAVAILABLE" : "NOT_APPLICABLE",
                    excluded > 0 ? "Original deadlines are unknown for retained legacy tasks; an empty due cohort cannot be confirmed"
                            : "No recorded original commitments due in this period"), 0L, 0L, coverage, excluded, asOf, 60, true);
            numeric = 100d * stats.accepted() / stats.count();
            long known = Objects.requireNonNull(jdbc.queryForObject("select count(*) from department_work_task t join work_original_commitment c on c.work_task_id = t.id", params, Long.class));
            coverage = known + excluded == 0 ? null : 100d * known / (known + excluded);
            reason = "Full original-date due cohort; late, unfinished and future due tasks remain in the denominator. Current delivery evidence must be accepted by the Lead for Employee work or HR for direct Lead work before the original office-day deadline and period/as-of cutoff; rework or handover invalidates the current acceptance. "
                    + excluded + " retained tasks have unknown original deadlines and are excluded; coverage describes all retained work.";
            if (range.to().plusDays(1).atStartOfDay(officeZone).toInstant().isAfter(asOf)) reason += " Preliminary: the selected cohort has not fully elapsed.";
        } else if (metric.id().equals("WORK03")) {
            long reviewWaiting = Objects.requireNonNull(jdbc.queryForObject("select count(*) from department_work_task t where t.created_at <= :asOf and status in ('COMPLETED','APPROVED','ACKNOWLEDGED') "
                    + "and not exists (select 1 from audit_event_history a where a.details_json->>'workTaskId' = t.id::text "
                    + "and a.event_type = 'WORK_INSIGHT_CEO_APPROVED' and a.target_type = 'WORK_TASK_AUDIT' and a.outcome = 'SUCCESS' "
                    + "and a.actor_id not like 'flyway%' and a.occurred_at <= :asOf "
                    + "and not exists (select 1 from workboard_activity_event r where r.work_task_id = t.id and r.event_type = 'STATUS_CHANGED' "
                    + "and r.current_status not in ('APPROVED','ACKNOWLEDGED') and r.occurred_at >= a.occurred_at and r.occurred_at <= :asOf))", params, Long.class));
            reason = "Execution overdue by current office due date. " + reviewWaiting + " submitted or employee-approved deliveries await final governance review and are counted separately, outside execution overdue.";
        } else if (metric.id().equals("VIS14")) {
            reason = "Cumulative retained non-draft appointments as of this instant; archived/deleted history is not claimed. History start is declared in coverage.";
        }
        return value(metric, numeric, null, reason, asOf, metric.budget(), stats.count(), stats.count(), coverage, excluded, true);
    }

    private Statistics statistics(QueryPlan plan, MapSqlParameterSource params, boolean percentile) {
        return jdbc.queryForObject("select count(*) n, count(*) filter (where accepted) accepted, "
                + (percentile ? "percentile_cont(0.95) within group (order by measure)" : "null::double precision")
                + " p95 from (" + plan.rows() + ") observations", params,
                (rs, n) -> new Statistics(rs.getLong("n"), rs.getLong("accepted"), rs.getObject("p95", Double.class)));
    }

    // All relations expose one row per eligible record. No summary-table approximation.
    QueryPlan queryPlan(String id) {
        String visit = "a.id::text id, a.reference_number label, a.type::text detail, a.status::text status, ";
        String tail = ", 'APPOINTMENT' kind, 0::double precision measure, false accepted from appointment a ";
        return switch (id) {
            case "VIS02" -> new QueryPlan("select " + visit + "a.security_intake_at occurred_at" + tail
                    + "where a.security_intake_at >= :start and a.security_intake_at < :end and a.security_intake_at <= :asOf", null);
            case "VIS05" -> new QueryPlan("select v.id::text id, a.reference_number label, 'Access inside' detail, 'INSIDE' status, v.checked_in_at occurred_at, 'ACCESS' kind, 0::double precision measure, false accepted "
                    + "from visit_access_record v join appointment a on a.id = v.appointment_id where v.checked_out_at is null and v.checked_in_at <= :asOf", null);
            case "VIS07" -> new QueryPlan("select " + visit + "a.updated_at occurred_at" + tail
                    + "where a.status = 'PENDING_CEO_APPROVAL' and a.created_at <= :asOf "
                    + "and a.host_employee_id = :employeeId "
                    + "and (a.type = 'CEO_VISIT' or a.type = 'EMERGENCY') and a.routing_department_id is not null "
                    + "and a.manager_decision_at is not null and a.manager_approval_actor_id is not null", null);
            case "VIS09" -> new QueryPlan("select v.id::text id, a.reference_number label, "
                    + "extract(epoch from (v.checked_in_at - a.security_intake_at))::text || ' seconds' detail, a.status::text status, "
                    + "v.checked_in_at occurred_at, 'WAIT_OBSERVATION' kind, extract(epoch from (v.checked_in_at - a.security_intake_at))::double precision measure, false accepted "
                    + "from visit_access_record v join appointment a on a.id = v.appointment_id "
                    + "where v.checked_in_at >= :start and v.checked_in_at < :end and v.checked_in_at <= :asOf "
                    + "and a.security_intake_at is not null and v.checked_in_at >= a.security_intake_at",
                    "select count(*) from visit_access_record v join appointment a on a.id = v.appointment_id "
                    + "where v.checked_in_at >= :start and v.checked_in_at < :end and v.checked_in_at <= :asOf "
                    + "and (a.security_intake_at is null or v.checked_in_at < a.security_intake_at)");
            case "WORK03" -> new QueryPlan("select t.id::text id, t.title label, 'Current due date: ' || t.due_date::text detail, t.status::text status, "
                    + "(t.due_date + 1)::timestamp at time zone :officeZone occurred_at, 'WORK_TASK' kind, 0::double precision measure, false accepted "
                    + "from department_work_task t where t.status in ('ASSIGNED','IN_PROGRESS','CHANGES_REQUESTED','INSIGHT_REWORK_REQUESTED') "
                    + "and t.due_date < :today and t.created_at <= :asOf", null);
            case "WORK07" -> new QueryPlan("""
                    select t.id::text id, t.title label, 'Original due date: ' || c.original_due_date::text
                        || '; current evidence acceptance: ' || coalesce(acceptance.accepted_at::text, 'unfinished / unknown') detail,
                        t.status::text status, (c.original_due_date + 1)::timestamp at time zone :officeZone occurred_at,
                        'ORIGINAL_DUE_COHORT' kind, 0::double precision measure,
                        acceptance.accepted_at is not null
                            and acceptance.accepted_at <= :asOf and acceptance.accepted_at < :end
                            and acceptance.accepted_at < (c.original_due_date + 1)::timestamp at time zone :officeZone accepted
                    from department_work_task t join work_original_commitment c on c.work_task_id = t.id
                    left join reporting_work_current_acceptance acceptance on acceptance.work_task_id=t.id
                    where c.original_due_date >= :from and c.original_due_date <= :to and c.committed_at <= :asOf
                      and t.created_at <= :asOf
                    """, "select count(*) from department_work_task t where not exists (select 1 from work_original_commitment c where c.work_task_id = t.id)");
            case "IAM03" -> new QueryPlan("select u.id::text id, u.full_name label, 'CEO account approval' detail, u.account_status::text status, u.created_at occurred_at, 'ACCOUNT' kind, 0::double precision measure, false accepted "
                    + "from iam_user_account u join iam_user_role r on r.user_id = u.id where not u.archived and u.account_status = 'PENDING_APPROVAL' and r.role_name = 'ROLE_CEO' and u.created_at <= :asOf", null);
            case "NTF03" -> new QueryPlan("select id::text id, template label, 'Unresolved unique notification job' detail, status::text status, created_at occurred_at, 'NOTIFICATION_JOB' kind, 0::double precision measure, false accepted "
                    + "from notification_outbox where status = 'DEAD' and created_at <= :asOf", null);
            case "VIS14" -> new QueryPlan("select " + visit + "a.created_at occurred_at" + tail + "where a.status <> 'DRAFT' and a.created_at <= :asOf", null);
            default -> null;
        };
    }

    Authority authorize(UUID userId) {
        Authority actor = authorities.requireActive(userId);
        if (!actor.role().equals(ADMIN) && !actor.role().equals(CEO)) {
            throw denied("An active System Admin or CEO account is required");
        }
        return actor;
    }

    private void requireUnchanged(UUID userId, Authority actor) {
        if (!actor.equals(authorize(userId))) throw denied("Your role or permissions changed. Reload your workspace.");
    }

    private void requireMetricRole(Authority actor, Metric metric) {
        if (!metric.role().equals(actor.role())) throw denied("This measurement is not available to your role");
    }

    private Long generation() { return jdbc.queryForObject("select generation from reporting_source_revision where singleton", new MapSqlParameterSource(), Long.class); }

    DateRange range(PeriodPreset preset, LocalDate from, LocalDate to, Instant asOf) {
        LocalDate today = asOf.atZone(officeZone).toLocalDate();
        DateRange range = switch (preset == null ? PeriodPreset.TODAY : preset) {
            case TODAY -> new DateRange(today, today);
            case YESTERDAY -> new DateRange(today.minusDays(1), today.minusDays(1));
            case LAST_7_DAYS -> new DateRange(today.minusDays(6), today);
            case THIS_MONTH -> new DateRange(today.withDayOfMonth(1), today);
            case PREVIOUS_MONTH -> new DateRange(today.minusMonths(1).withDayOfMonth(1), today.minusMonths(1).with(TemporalAdjusters.lastDayOfMonth()));
            case CUSTOM -> new DateRange(from, to);
        };
        if (range.from() == null || range.to() == null || range.from().isAfter(range.to())
                || ChronoUnit.DAYS.between(range.from(), range.to()) >= 366 || range.to().equals(LocalDate.MAX)) {
            throw new BusinessException("INVALID_DASHBOARD_RANGE", "Choose a dashboard range of 366 days or less", HttpStatus.BAD_REQUEST);
        }
        return range;
    }

    private MapSqlParameterSource parameters(DateRange range, Instant asOf) {
        Instant end = range.to().plusDays(1).atStartOfDay(officeZone).toInstant();
        return new MapSqlParameterSource().addValue("from", range.from()).addValue("to", range.to())
                .addValue("start", range.from().atStartOfDay(officeZone).toOffsetDateTime())
                .addValue("end", end.atOffset(ZoneOffset.UTC)).addValue("asOf", asOf.atOffset(ZoneOffset.UTC))
                .addValue("today", asOf.atZone(officeZone).toLocalDate()).addValue("officeZone", officeZone.getId());
    }

    private List<CoverageItem> coverage(Authority actor, MapSqlParameterSource params) {
        if (actor.role().equals(CEO) && !actor.permissions().contains("REPORT_VIEW")) return List.of();
        if (actor.role().equals(ADMIN) && !actor.permissions().contains("SYSTEM_CONFIGURE")) return List.of();
        List<CoverageItem> result = new ArrayList<>(jdbc.query("select id, since, reason from dashboard_measurement_coverage order by id", params,
                (rs, n) -> new CoverageItem(rs.getString("id"), rs.getString("id").replace('_', ' '), "PARTIAL", rs.getTimestamp("since").toInstant(), rs.getString("reason"))));
        if (actor.role().equals(CEO)) {
            Instant start = jdbc.queryForObject("select min(created_at) from appointment where status <> 'DRAFT'", params,
                    (rs, n) -> rs.getTimestamp(1) == null ? null : rs.getTimestamp(1).toInstant());
            result.add(new CoverageItem("RETAINED_VISIT_HISTORY", "Retained appointment history", start == null ? "UNAVAILABLE" : "PARTIAL", start,
                    "Earliest retained non-draft appointment creation; this is a retention boundary, not proof of all-time company history."));
        } else {
            result.add(new CoverageItem("WORKFLOW_JOURNEYS", "Workflow journey evidence", "UNAVAILABLE", null, "No persisted end-to-end journey measurements"));
            result.add(new CoverageItem("APPROVAL_DEADLINES", "Governed approval deadlines", "UNAVAILABLE", null, "No governed stage deadline evidence; task due dates cannot supply it"));
            result.add(new CoverageItem("BACKUP_RESTORE", "Verified backup and restore", "UNAVAILABLE", null, "No verified backup/restore run ledger"));
        }
        return List.copyOf(result);
    }

    private DependencySnapshot dependencies() {
        var overview = integrations.inspect();
        Map<String, Boolean> distinct = new TreeMap<>();
        overview.observations().stream().filter(s -> SAFE_DEPENDENCIES.contains(s.name()))
                .forEach(s -> distinct.merge(s.name(), s.ready(), (first, next) -> first && next));
        List<RecordItem> rows = distinct.entrySet().stream()
                .map(s -> new RecordItem(s.getKey(), s.getKey(), "Bounded dependency probe", s.getValue() ? "READY" : "DEGRADED", overview.checkedAt(), "DEPENDENCY")).toList();
        // Never return raw integration exception messages, bucket names or cluster IDs.
        return new DependencySnapshot(rows, overview.checkedAt());
    }

    private MetricCard dependencyCard(Metric metric, DependencySnapshot dependency) {
        if (dependency.rows().size() < SAFE_DEPENDENCIES.size() || dependency.checkedAt() == null) {
            MetricCard missing = empty(metric, "UNAVAILABLE", "Critical dependency coverage is incomplete: "
                    + (SAFE_DEPENDENCIES.size() - dependency.rows().size()) + " required probes are missing, or the source observation time is unknown");
            return dependency.checkedAt() == null ? missing : withDiagnostics(missing, (long) dependency.rows().size(),
                    (long) SAFE_DEPENDENCIES.size(), 100d * dependency.rows().size() / SAFE_DEPENDENCIES.size(),
                    (long) SAFE_DEPENDENCIES.size() - dependency.rows().size(), dependency.checkedAt(), 30, !dependency.rows().isEmpty());
        }
        long degraded = dependency.rows().stream().filter(row -> !row.status().equals("READY")).count();
        return value(metric, (double) degraded, degraded == 0 ? "READY" : "DEGRADED", "Observed dependency readiness only; this does not prove end-to-end workflow availability.",
                dependency.checkedAt(), 30, (long) dependency.rows().size(), (long) dependency.rows().size(), null, null, true);
    }

    private MetricCard empty(Metric metric, String state, String reason) {
        return new MetricCard(metric.id(), metric.title(), metric.definition(), metric.kind(), metric.clock(), metric.unit(), state,
                null, null, reason, null, null, "UNKNOWN", null, null, null, null, false, null);
    }

    private MetricCard value(Metric metric, Double value, String display, String reason, Instant refreshed, int budget,
            Long sample, Long eligible, Double coverage, Long excluded, boolean drill) {
        return new MetricCard(metric.id(), metric.title(), metric.definition(), metric.kind(), metric.clock(), metric.unit(), "AVAILABLE",
                value, display, reason, refreshed, refreshed.plusSeconds(budget), "FRESH", sample, eligible, coverage, excluded, drill, null);
    }

    private MetricCard withDiagnostics(MetricCard card, Long sample, Long eligible, Double coverage, Long excluded, Instant refreshed, int budget, boolean drill) {
        return new MetricCard(card.id(), card.title(), card.definition(), card.kind(), card.clock(), card.unit(), card.state(), card.value(), card.displayValue(),
                card.reason(), refreshed, refreshed.plusSeconds(budget), "FRESH", sample, eligible, coverage, excluded, drill, null);
    }

    private Metric metric(String id) {
        return switch (id) {
            case "OPS01" -> new Metric(id, ADMIN, "SYSTEM_CONFIGURE", "Core workflow availability", "Successful end-to-end workflow journeys divided by attempted journeys in the selected period", "RATE", "PERIOD", "PERCENT", 30, "No persisted end-to-end workflow journey evidence; dependency readiness cannot establish availability");
            case "OPS04" -> new Metric(id, ADMIN, "SYSTEM_CONFIGURE", "Critical dependencies", "Current bounded probes of critical dependencies, with sanitized names and states", "STOCK", "NOW", "STATUS", 30, null);
            case "IAM03" -> new Metric(id, ADMIN, "ROLE_MANAGE", "Pending account approvals", "Non-archived pending CEO accounts eligible for the System Admin approval action", "STOCK", "NOW", "COUNT", 60, null);
            case "VIS08" -> new Metric(id, ADMIN, "REPORT_VIEW", "Overdue approval stages", "Pending approval stages beyond their governed stage deadline", "STOCK", "NOW", "COUNT", 60, "No governed approval-stage deadline evidence; captured stage entries and task due dates cannot establish overdue approvals");
            case "NTF03" -> new Metric(id, ADMIN, "SYSTEM_CONFIGURE", "Unresolved dead-letter jobs", "Distinct notification outbox jobs currently DEAD, excluding sent and retried jobs", "STOCK", "NOW", "COUNT", 30, null);
            case "OPS07" -> new Metric(id, ADMIN, "SYSTEM_CONFIGURE", "Latest verified backup age", "Elapsed seconds since the most recent independently verified backup success", "STOCK", "NOW", "SECONDS", 30, "No verified backup-run evidence; configured jobs do not establish successful backups");
            case "OPS08" -> new Metric(id, ADMIN, "SYSTEM_CONFIGURE", "Verified restore", "Latest independently verified restore exercise", "STOCK", "NOW", "STATUS", 30, "No verified restore-exercise evidence");
            case "OPS09" -> new Metric(id, ADMIN, "SYSTEM_CONFIGURE", "Freshness and coverage", "Live source read freshness and explicitly declared instrumentation coverage", "STOCK", "NOW", "STATUS", 30, null);
            case "VIS02" -> new Metric(id, CEO, "REPORT_VIEW", "Security arrivals", "Distinct retained appointments whose first recorded security intake falls within the selected office dates", "FLOW", "PERIOD", "COUNT", 60, null);
            case "VIS05" -> new Metric(id, CEO, "VISITOR_OCCUPANCY_READ", "Visitors inside", "Company-scoped access records checked in and not checked out as of this instant; independent of selected period", "STOCK", "NOW", "COUNT", 15, null);
            case "VIS07" -> new Metric(id, CEO, "CEO_VISIT_APPROVE", "CEO approval queue", "Current CEO-hosted visits at the CEO stage after a recorded Manager decision; independent of selected period", "STOCK", "NOW", "COUNT", 60, null);
            case "VIS09" -> new Metric(id, CEO, "REPORT_VIEW", "Check-in wait p95", "Continuous 95th percentile of raw non-negative security-intake-to-check-in seconds for check-ins within selected office dates", "DISTRIBUTION", "PERIOD", "SECONDS", 60, null);
            case "WORK03" -> new Metric(id, CEO, "WORK_INSIGHT_READ", "Overdue execution delivery", "Retained work in execution/rework with current due date before the as-of office date; completed review-waiting deliveries remain separate", "STOCK", "NOW", "COUNT", 60, null);
            case "WORK07" -> new Metric(id, CEO, "WORK_INSIGHT_READ", "On-time accepted work", "Full cohort of immutable original due calendar dates in the period, accepted before original next-office-midnight deadline and period/as-of cutoff without later rework; unfinished and late deliveries remain denominator", "COHORT", "PERIOD", "PERCENT", 60, null);
            case "VIS14" -> new Metric(id, CEO, "REPORT_VIEW", "Retained appointments", "Cumulative non-draft retained appointments; independent of selected period, with declared retained-history start", "CUMULATIVE", "HISTORY", "COUNT", 300, null);
            default -> throw new BusinessException("DASHBOARD_METRIC_NOT_FOUND", "Unknown dashboard measurement", HttpStatus.NOT_FOUND);
        };
    }

    private static BusinessException denied(String message) { return new BusinessException("DASHBOARD_ACCESS_DENIED", message, HttpStatus.FORBIDDEN); }
    record DateRange(LocalDate from, LocalDate to) {}
    record QueryPlan(String rows, String excluded) {}
    record Statistics(long count, long accepted, Double p95) {}
    record Metric(String id, String role, String permission, String title, String definition, String kind, String clock, String unit, int budget, String unavailableReason) {}
    private record DependencySnapshot(List<RecordItem> rows, Instant checkedAt) {}
    private record PagedRow(long total, RecordItem item) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record DashboardCards(String metricVersion, String role, String scope, UUID departmentId, LocalDate from, LocalDate to,
            String officeZone, Instant asOf, Long sourceGeneration, List<MetricCard> cards, List<MetricCard> supplementary, List<CoverageItem> coverage) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record MetricCard(String id, String title, String definition, String kind, String clock, String unit, String state,
            Double value, String displayValue, String reason, Instant sourceRefreshedAt, Instant freshUntil, String freshness,
            Long sampleSize, Long eligibleCount, Double coveragePercent, Long excludedCount, boolean drillDownAvailable, Object comparison) {
        MetricCard stale() { return sourceRefreshedAt == null ? this : new MetricCard(id, title, definition, kind, clock, unit, state, value, displayValue,
                reason, sourceRefreshedAt, freshUntil, "STALE", sampleSize, eligibleCount, coveragePercent, excludedCount, drillDownAvailable, null); }
    }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record CoverageItem(String id, String title, String state, Instant since, String reason) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record MetricRecords(String metricId, String metricVersion, String state, String reason, Instant asOf, LocalDate from, LocalDate to,
            int page, int size, long totalElements, int totalPages, List<RecordItem> items) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record RecordItem(String id, String label, String detail, String status, Instant occurredAt, String kind) {}
}
