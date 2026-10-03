package com.brainserve.appointment.workinsight.application;

import com.brainserve.appointment.iam.api.CurrentAccountAuthority;
import com.brainserve.appointment.shared.application.BusinessException;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.*;
import java.util.*;

/** One scoped SQL relation supplies pagination, all counts and action eligibility. */
@Service
public class WorkboardQueryService {
    public enum Period { TODAY, CARRY_FORWARD, HISTORY, ALL }
    public enum QuickFilter { ALL, MY_ACTIONS, DUE_TODAY, OVERDUE_DELIVERY, AWAITING_MY_REVIEW, RETURNED_FOR_REWORK }
    public enum Sort { DUE_DATE, UPDATED_AT, TITLE, PRIORITY }
    public enum Layout { LIST, BOARD }
    public enum Density { COMPACT, COMFORTABLE }
    private static final Set<String> STATUSES = Set.of("ALL", "ASSIGNED", "IN_PROGRESS", "COMPLETED", "CHANGES_REQUESTED", "APPROVED", "ACKNOWLEDGED", "INSIGHT_REWORK_REQUESTED");
    private static final Map<String, String> HISTORY_TITLES = Map.ofEntries(
            Map.entry("WORK_TASK_ASSIGNED", "Worksheet assigned"), Map.entry("WORK_TASK_IN_PROGRESS", "Delivery started"),
            Map.entry("WORK_TASK_COMPLETED", "Delivery submitted"), Map.entry("WORK_TASK_APPROVED", "Delivery approved by Team Lead"),
            Map.entry("WORK_TASK_CHANGES_REQUESTED", "Delivery returned for changes"), Map.entry("WORK_TASK_ACKNOWLEDGED", "Employee acknowledged approval"),
            Map.entry("WORK_TASK_REWORK_RESUBMITTED", "Revised delivery submitted"), Map.entry("WORK_TASK_GOVERNANCE_APPROVED", "Governance approval recorded"),
            Map.entry("WORK_INSIGHT_HR_AUDITED", "HR audit submitted"), Map.entry("WORK_INSIGHT_HR_REWORK_REQUESTED", "HR requested rework"),
            Map.entry("WORK_INSIGHT_REWORK_ASSIGNED", "Team Lead assigned rework"), Map.entry("WORK_INSIGHT_REWORK_RESUBMITTED", "Revised delivery submitted"),
            Map.entry("WORK_INSIGHT_MANAGER_APPROVED", "Manager verified audit"), Map.entry("WORK_INSIGHT_MANAGER_REWORK_REQUESTED", "Manager requested rework"),
            Map.entry("WORK_INSIGHT_CEO_APPROVED", "CEO gave final approval"), Map.entry("WORK_INSIGHT_CEO_REWORK_REQUESTED", "CEO requested rework"));
    private final NamedParameterJdbcTemplate jdbc;
    private final CurrentAccountAuthority authority;
    private final ObjectMapper mapper;
    private final ZoneId officeZone;
    private final Clock clock;

    @Autowired
    public WorkboardQueryService(NamedParameterJdbcTemplate jdbc, CurrentAccountAuthority authority, ObjectMapper mapper,
            @Value("${brainserve.appointment.office-zone:Asia/Kolkata}") String officeZone) {
        this(jdbc, authority, mapper, ZoneId.of(officeZone), Clock.systemUTC());
    }
    WorkboardQueryService(NamedParameterJdbcTemplate jdbc, CurrentAccountAuthority authority, ObjectMapper mapper, ZoneId officeZone, Clock clock) {
        this.jdbc = jdbc; this.authority = authority; this.mapper = mapper; this.officeZone = officeZone; this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Page list(UUID actor, Criteria criteria, int page, int size) {
        validate(criteria);
        if (page < 0 || page > 10000 || size < 1 || size > 100) throw invalid("Choose page 0–10000 and size 1–100");
        Access access = authorize(actor);
        Instant now = clock.instant();
        LocalDate today = now.atZone(officeZone).toLocalDate();
        var params = parameters(actor, access, criteria, today).addValue("limit", size).addValue("offset", (long) page * size);
        String relation = relation(access, false);
        // Counts and page share a single database statement snapshot, even when other writers commit.
        String sql = relation + ", period_rows as materialized (select * from scoped where " + period(criteria.scope()) + "), "
                + "selected as materialized (select * from period_rows where " + quick(criteria.quickFilter()) + "), "
                + "totals as (select count(*) total, " + laneCountsSql() + " from selected), "
                + "scope_counts as (select " + scopeCountsSql() + " from scoped), "
                + "quick_counts as (select " + quickCountsSql() + " from period_rows) "
                + "select totals.*, scope_counts.*, quick_counts.*, paged.* from totals cross join scope_counts cross join quick_counts "
                + "left join lateral (select * from selected order by " + order(criteria.sort()) + " limit :limit offset :offset) paged on true "
                + "order by " + order(criteria.sort());
        List<PageRow> rows = jdbc.query(sql, params, (rs, n) -> new PageRow(rs.getLong("total"), counts(rs, "s_", Period.values()),
                counts(rs, "q_", QuickFilter.values()), counts(rs, "l_", new String[]{"DELIVERY", "REVIEW", "REWORK", "CLOSED"}),
                rs.getObject("id") == null ? null : item(rs)));
        requireUnchanged(actor, access);
        PageRow first = rows.getFirst();
        return new Page("workboard.v1", now, officeZone.getId(), today, access.own() ? "OWN" : "DEPARTMENT",
                access.own() ? null : access.departmentId(), page, size, first.total(), (int) ((first.total() + size - 1) / size),
                new Counts(first.scopes(), first.quickFilters()), first.lanes(), rows.stream().map(PageRow::item).filter(Objects::nonNull).toList());
    }

    @Transactional(readOnly = true)
    public Detail detail(UUID actor, UUID taskId) {
        Access access = authorize(actor);
        Criteria criteria = new Criteria(Period.ALL, QuickFilter.ALL, "", "ALL", "", Sort.DUE_DATE);
        var params = parameters(actor, access, criteria, clock.instant().atZone(officeZone).toLocalDate()).addValue("taskId", taskId);
        List<Item> items = jdbc.query(relation(access, true) + " select * from scoped", params, (rs, n) -> item(rs));
        if (items.isEmpty()) throw notFound();
        Item item = items.getFirst();
        params.addValue("taskTarget", taskId.toString()).addValue("auditTarget", item.auditRecordId() == null ? "" : item.auditRecordId().toString())
                .addValue("events", HISTORY_TITLES.keySet());
        // Only safe event names/time/id cross this boundary. Raw audit payloads and actor identifiers never do.
        List<History> retained = jdbc.query("""
                with retained as (
                    select id, occurred_at, event_type from audit_event
                    where outcome='SUCCESS' and event_type in (:events)
                      and ((target_type='WORK_TASK' and target_id=:taskTarget)
                        or (target_type='WORK_TASK_AUDIT' and target_id=:auditTarget))
                    union
                    select id, occurred_at, event_type from audit_event_history
                    where outcome='SUCCESS' and event_type in (:events)
                      and ((target_type='WORK_TASK' and target_id=:taskTarget)
                        or (target_type='WORK_TASK_AUDIT' and target_id=:auditTarget))
                ) select * from retained order by occurred_at desc, id desc limit 201
                """, params, (rs, n) -> new History(rs.getObject("id", UUID.class).toString(), HISTORY_TITLES.get(rs.getString("event_type")),
                instant(rs, "occurred_at"), historyRole(rs.getString("event_type")), null));
        requireUnchanged(actor, access);
        // Recheck the individual record as well: reassignment during a drawer read cannot leak it.
        if (jdbc.queryForObject(relation(access, true) + " select count(*) from scoped", params, Long.class) != 1L) throw notFound();
        requireUnchanged(actor, access);
        List<History> chronological = new ArrayList<>(retained.subList(0, Math.min(200, retained.size())));
        Collections.reverse(chronological);
        return new Detail(item, List.copyOf(chronological), retained.size() > 200);
    }

    /** Legacy status response uses this same read scope, including current task assignment. */
    @Transactional(readOnly = true)
    public List<WorkInsightService.TaskWorkflowState> workflowStates(UUID actor) {
        Access access = authorize(actor);
        if (!Set.of("ROLE_EMPLOYEE", "ROLE_TEAM_LEAD").contains(access.authority().role())) throw denied();
        Criteria criteria = new Criteria(Period.ALL, QuickFilter.ALL, "", "ALL", "", Sort.UPDATED_AT);
        var params = parameters(actor, access, criteria, clock.instant().atZone(officeZone).toLocalDate());
        List<WorkInsightService.TaskWorkflowState> result = jdbc.query(relation(access, false)
                + " select id,audit_status from scoped where audit_record_id is not null order by workboard_updated_at desc,id limit 500",
                params, (rs,n) -> new WorkInsightService.TaskWorkflowState(rs.getObject("id", UUID.class), rs.getString("audit_status")));
        requireUnchanged(actor, access);
        return result;
    }

    @Transactional(readOnly = true)
    public Preferences preferences(UUID actor) {
        Access access = authorize(actor);
        Preferences preferences = readPreferences(actor);
        requireUnchanged(actor, access);
        return preferences;
    }

    @Transactional
    public Preferences savePreferences(UUID actor, PreferencesUpdate update) {
        validatePreferences(update);
        Access access = authorize(actor);
        String filters;
        try { filters = mapper.writeValueAsString(update.savedFilters()); }
        catch (Exception ex) { throw invalid("Saved filters are invalid"); }
        int changed;
        if (update.expectedRevision() == 0) {
            changed = jdbc.update("insert into workboard_preference(owner_id,revision,layout,density,saved_filters,updated_at) "
                    + "values(:owner,1,:layout,:density,cast(:filters as jsonb),:now) on conflict(owner_id) do nothing", preferenceParams(actor, update, filters));
        } else {
            changed = jdbc.update("update workboard_preference set revision=revision+1,layout=:layout,density=:density," 
                    + "saved_filters=cast(:filters as jsonb),updated_at=:now where owner_id=:owner and revision=:expected", preferenceParams(actor, update, filters));
        }
        if (changed != 1) throw new BusinessException("WORKBOARD_PREFERENCE_CONFLICT", "Preferences changed. Reload them before saving", HttpStatus.CONFLICT);
        Preferences result = readPreferences(actor);
        requireUnchanged(actor, access); // Revocation rolls back the write too.
        return result;
    }

    private MapSqlParameterSource preferenceParams(UUID actor, PreferencesUpdate update, String filters) {
        return new MapSqlParameterSource("owner", actor).addValue("layout", update.layout().name()).addValue("density", update.density().name())
                .addValue("filters", filters).addValue("expected", update.expectedRevision()).addValue("now", java.sql.Timestamp.from(clock.instant()));
    }
    private Preferences readPreferences(UUID actor) {
        List<Preferences> rows = jdbc.query("select revision,layout,density,saved_filters::text from workboard_preference where owner_id=:owner",
                new MapSqlParameterSource("owner", actor), (rs, n) -> {
                    try { return new Preferences(rs.getLong("revision"), Layout.valueOf(rs.getString("layout")), Density.valueOf(rs.getString("density")),
                            mapper.readValue(rs.getString("saved_filters"), new TypeReference<List<SavedFilter>>() {})); }
                    catch (Exception ex) { throw new IllegalStateException("Stored Workboard preferences are invalid", ex); }
                });
        return rows.isEmpty() ? new Preferences(0, Layout.LIST, Density.COMPACT, List.of()) : rows.getFirst();
    }

    private Access authorize(UUID actor) {
        var scope = authority.requireWorkScope(actor);
        if (!scope.authority().permissions().contains("WORK_TASK_READ")) throw denied();
        return new Access(scope.authority(), scope.departmentId(), scope.assignmentId(), scope.assignmentVersion(), scope.employeeVersion(), scope.departmentVersion());
    }
    private void requireUnchanged(UUID actor, Access before) {
        if (!before.equals(authorize(actor))) throw denied();
    }

    private String relation(Access access, boolean singleTask) {
        var current = access.authority();
        boolean progress = current.permissions().contains("WORK_TASK_PROGRESS");
        boolean review = current.permissions().contains("WORK_TASK_REVIEW");
        boolean audit = current.permissions().contains("WORK_INSIGHT_AUDIT");
        boolean manager = current.permissions().contains("WORK_INSIGHT_MANAGER_APPROVE");
        String owner = access.own() ? "t.assignee_role='EMPLOYEE' and t.employee_id=:employeeId" :
                current.role().equals("ROLE_TEAM_LEAD") ? "t.assignee_role='TEAM_LEAD' and t.employee_id=:employeeId and t.team_lead_user_id=:actor" : "false";
        String leadReview = current.role().equals("ROLE_TEAM_LEAD") && review ? "t.team_lead_user_id=:actor and t.assignee_role='EMPLOYEE' and t.employee_id<>:employeeId" : "false";
        String hrReady = current.role().equals("ROLE_HR_ADMIN") && audit ? "((t.assignee_role='TEAM_LEAD' and t.status='COMPLETED') or (t.assignee_role='EMPLOYEE' and t.status in ('APPROVED','ACKNOWLEDGED')))" : "false";
        String reworkRequested = "a.audit_status in ('HR_REWORK_REQUESTED','MANAGER_REWORK_REQUESTED','CEO_REWORK_REQUESTED')";
        String closed = "coalesce(a.audit_status='CEO_APPROVED',false) and (t.assignee_role='TEAM_LEAD' or t.status='ACKNOWLEDGED')";
        String delivery = "t.status in ('ASSIGNED','IN_PROGRESS','CHANGES_REQUESTED','INSIGHT_REWORK_REQUESTED')";
        LinkedHashMap<String, String> actions = new LinkedHashMap<>();
        actions.put("start", "(" + owner + ") and " + progress + " and t.status in ('ASSIGNED','CHANGES_REQUESTED')");
        actions.put("complete", "(" + owner + ") and " + progress + " and t.status in ('ASSIGNED','IN_PROGRESS','CHANGES_REQUESTED')");
        actions.put("approve", "(" + leadReview + ") and t.status='COMPLETED'");
        actions.put("request-changes", actions.get("approve"));
        actions.put("acknowledge", access.own() && progress ? "t.status='APPROVED'" : "false");
        actions.put("insight-rework", current.role().equals("ROLE_TEAM_LEAD") && review ? "t.team_lead_user_id=:actor and a.team_lead_user_id=:actor and t.status='INSIGHT_REWORK_REQUESTED' and " + reworkRequested : "false");
        actions.put("revise-rework", "(" + owner + ") and " + (access.own() ? progress : review) + " and t.status='COMPLETED' and "
                + (access.own() ? "t.team_lead_review is not null" : "t.rework_cycle>0 and t.insight_review_reason is not null and a.team_lead_user_id=:actor and a.audit_status='REWORK_ASSIGNED'"));
        actions.put("hr-rework", "(" + hrReady + ") and (a.id is null or a.audit_status in ('PENDING_MANAGER_APPROVAL','PENDING_CEO_APPROVAL','REWORK_ASSIGNED'))");
        actions.put("hr-audit", "(" + hrReady + ") and (a.id is null or a.audit_status='REWORK_ASSIGNED') and exists (select 1 from department_manager_assignment m join iam_user_account ma on ma.id=m.manager_user_id where m.department_id=t.department_id and m.active and ma.enabled and ma.account_status='ACTIVE' and not ma.archived)");
        actions.put("open-oversight", current.role().equals("ROLE_MANAGER") && manager ? "a.audit_status='PENDING_MANAGER_APPROVAL'" : "false");
        String actionSql = actions.entrySet().stream().map(entry -> "case when " + entry.getValue() + " then '" + entry.getKey() + "' end").collect(java.util.stream.Collectors.joining(","));
        String scope = access.own() ? "t.employee_id=:employeeId and t.assignee_role='EMPLOYEE' and t.department_id=:departmentId" : "t.department_id=:departmentId";
        String filter = " and (:status='ALL' or t.status=:status) and (:branch='' or t.department_branch=:branch) and (:query='' or "
                + "t.title ilike :literal escape '!' or t.description ilike :literal escape '!' or e.display_name ilike :literal escape '!' or t.department_branch ilike :literal escape '!')";
        return "with base as materialized (select t.*, e.display_name assignee_name, coalesce(a.audit_status,'NOT_AUDITED') audit_status, a.id audit_record_id,a.version audit_version,"
                + "greatest(t.updated_at,a.updated_at) workboard_updated_at, array_remove(array[" + actionSql + "]::text[],null) allowed_actions,"
                + "(" + closed + ") is_closed, (" + delivery + ") is_delivery, "
                + "case when " + closed + " then 'CLOSED' when t.status in ('CHANGES_REQUESTED','INSIGHT_REWORK_REQUESTED') or (t.status='IN_PROGRESS' and (t.team_lead_review is not null or t.insight_review_reason is not null or t.rework_cycle>0)) then 'REWORK' when t.status in ('ASSIGNED','IN_PROGRESS') then 'DELIVERY' else 'REVIEW' end lane "
                + "from department_work_task t join employee e on e.id=t.employee_id left join work_task_audit_record a on a.work_task_id=t.id where " + scope + filter + (singleTask ? " and t.id=:taskId" : "") + "), "
                + "scoped as materialized (select base.*, cardinality(allowed_actions)>0 my_actions, "
                + "allowed_actions && array['approve','request-changes','insight-rework','hr-rework','hr-audit','open-oversight']::text[] awaiting_my_review "
                + "from base)";
    }
    private MapSqlParameterSource parameters(UUID actor, Access access, Criteria criteria, LocalDate today) {
        return new MapSqlParameterSource("actor", actor).addValue("employeeId", access.authority().employeeId()).addValue("departmentId", access.departmentId())
                .addValue("start", java.sql.Timestamp.from(today.atStartOfDay(officeZone).toInstant()))
                .addValue("end", java.sql.Timestamp.from(today.plusDays(1).atStartOfDay(officeZone).toInstant())).addValue("today", today)
                .addValue("status", criteria.status()).addValue("branch", criteria.branch()).addValue("query", criteria.query())
                .addValue("literal", "%" + escapeLiteral(criteria.query()) + "%");
    }
    static String escapeLiteral(String query) { return query.replace("!", "!!").replace("%", "!%").replace("_", "!_"); }
    static void validate(Criteria criteria) {
        if (criteria == null || criteria.scope() == null || criteria.quickFilter() == null || criteria.sort() == null
                || criteria.query() == null || criteria.query().length() > 120 || criteria.branch() == null || criteria.branch().length() > 170
                || criteria.status() == null || !STATUSES.contains(criteria.status()) || hasControls(criteria.query()) || hasControls(criteria.branch())) throw invalid("Workboard filters are invalid");
    }
    static void validatePreferences(PreferencesUpdate update) {
        if (update == null || update.expectedRevision() == null || update.expectedRevision() < 0 || update.layout() == null || update.density() == null
                || update.savedFilters() == null || update.savedFilters().size() > 10) throw invalid("Workboard preferences are invalid");
        Set<String> ids = new HashSet<>();
        for (SavedFilter filter : update.savedFilters()) {
            if (filter == null || filter.id() == null || !filter.id().matches("[A-Za-z0-9_-]{1,64}") || !ids.add(filter.id()) || filter.name() == null
                    || filter.name().isBlank() || filter.name().length() > 60 || hasControls(filter.name())) throw invalid("Saved filter names and identifiers are invalid");
            validate(new Criteria(filter.scope(), filter.quickFilter(), filter.query(), filter.status(), filter.branch(), filter.sort()));
        }
    }
    private static boolean hasControls(String value) { return value.chars().anyMatch(Character::isISOControl); }
    static String period(Period period) {
        return switch (period) {
            case TODAY -> "created_at>=:start and created_at<:end";
            case CARRY_FORWARD -> "created_at<:start and not is_closed";
            case HISTORY -> "created_at<:start and is_closed";
            case ALL -> "true";
        };
    }
    static String quick(QuickFilter quick) {
        return switch (quick) {
            case ALL -> "true";
            case MY_ACTIONS -> "my_actions";
            case DUE_TODAY -> "due_date=:today";
            case OVERDUE_DELIVERY -> "due_date<:today and is_delivery";
            case AWAITING_MY_REVIEW -> "awaiting_my_review";
            case RETURNED_FOR_REWORK -> "lane='REWORK'";
        };
    }
    private static String order(Sort sort) { return switch (sort) {
        case DUE_DATE -> "due_date asc, id asc";
        case UPDATED_AT -> "workboard_updated_at desc, id asc";
        case TITLE -> "lower(title) asc, id asc";
        case PRIORITY -> "id asc"; // Priority metadata starts in S6. Missing values are all equal.
    }; }
    private static String scopeCountsSql() { return Arrays.stream(Period.values()).map(p -> "count(*) filter(where " + period(p) + ") s_" + p).collect(java.util.stream.Collectors.joining(",")); }
    private static String quickCountsSql() { return Arrays.stream(QuickFilter.values()).map(q -> "count(*) filter(where " + quick(q) + ") q_" + q).collect(java.util.stream.Collectors.joining(",")); }
    private static String laneCountsSql() { return Arrays.stream(new String[]{"DELIVERY", "REVIEW", "REWORK", "CLOSED"}).map(l -> "count(*) filter(where lane='" + l + "') l_" + l).collect(java.util.stream.Collectors.joining(",")); }
    private static Map<String, Long> counts(ResultSet rs, String prefix, Object[] keys) throws SQLException {
        Map<String, Long> result = new LinkedHashMap<>();
        for (Object key : keys) result.put(key.toString(), rs.getLong(prefix + key));
        return Collections.unmodifiableMap(result);
    }
    private static Item item(ResultSet rs) throws SQLException {
        var actions = List.of((String[]) rs.getArray("allowed_actions").getArray());
        return new Item(rs.getObject("id", UUID.class), rs.getObject("department_id", UUID.class), rs.getObject("employee_id", UUID.class),
                rs.getObject("team_lead_user_id", UUID.class), rs.getObject("assigned_by_user_id", UUID.class), rs.getString("assigned_by_role"), rs.getString("assignee_role"),
                rs.getString("title"), rs.getString("description"), rs.getString("department_branch"), rs.getObject("due_date", LocalDate.class), rs.getString("status"),
                rs.getString("employee_update"), rs.getString("team_lead_review"), rs.getString("insight_review_source"), rs.getString("insight_review_reason"), instant(rs,"insight_review_requested_at"),
                rs.getInt("rework_cycle"), instant(rs,"started_at"), instant(rs,"completed_at"), instant(rs,"approved_at"), instant(rs,"acknowledged_at"), instant(rs,"created_at"), rs.getLong("version"),
                rs.getString("assignee_name"), rs.getString("audit_status"), rs.getObject("audit_record_id", UUID.class), (Long)rs.getObject("audit_version"), instant(rs,"workboard_updated_at"),
                (Long)rs.getObject("submission_version"), null, null, actions, nextActor(rs), rs.getString("lane"));
    }
    private static String nextActor(ResultSet rs) throws SQLException {
        String status = rs.getString("status"), audit = rs.getString("audit_status");
        if (rs.getBoolean("is_closed")) return null;
        if (status.equals("INSIGHT_REWORK_REQUESTED")) return "TEAM_LEAD";
        if (rs.getBoolean("is_delivery")) return rs.getString("assignee_role");
        if (status.equals("COMPLETED") && rs.getString("assignee_role").equals("EMPLOYEE")) return "TEAM_LEAD";
        if (audit.equals("PENDING_MANAGER_APPROVAL")) return "MANAGER";
        if (audit.equals("PENDING_CEO_APPROVAL")) return "CEO";
        if (audit.equals("CEO_APPROVED") && status.equals("APPROVED")) return "EMPLOYEE";
        return "HR_ADMIN";
    }
    private static Instant instant(ResultSet rs, String field) throws SQLException { return rs.getTimestamp(field) == null ? null : rs.getTimestamp(field).toInstant(); }
    private static String historyRole(String event) {
        if (event.contains("_CEO_")) return "CEO";
        if (event.contains("_MANAGER_")) return "MANAGER";
        if (event.contains("_HR_")) return "HR_ADMIN";
        if (Set.of("WORK_TASK_APPROVED", "WORK_TASK_CHANGES_REQUESTED", "WORK_INSIGHT_REWORK_ASSIGNED").contains(event)) return "TEAM_LEAD";
        if (event.equals("WORK_TASK_ACKNOWLEDGED")) return "EMPLOYEE";
        return null; // Historical actor roles were not recorded for every event.
    }
    private static BusinessException invalid(String message) { return new BusinessException("INVALID_WORKBOARD_REQUEST", message, HttpStatus.BAD_REQUEST); }
    private static BusinessException denied() { return new BusinessException("WORKBOARD_ACCESS_DENIED", "Your current role, permissions or department assignment do not allow this Workboard", HttpStatus.FORBIDDEN); }
    private static BusinessException notFound() { return new BusinessException("WORK_TASK_NOT_FOUND", "The work task was not found", HttpStatus.NOT_FOUND); }
    private record Access(CurrentAccountAuthority.Authority authority, UUID departmentId, UUID assignmentId, long assignmentVersion, long employeeVersion, long departmentVersion) {
        boolean own() { return authority.role().equals("ROLE_EMPLOYEE"); }
    }
    private record PageRow(long total, Map<String, Long> scopes, Map<String, Long> quickFilters, Map<String, Long> lanes, Item item) {}
    public record Criteria(Period scope, QuickFilter quickFilter, String query, String status, String branch, Sort sort) {}
    public record Counts(Map<String, Long> scopes, Map<String, Long> quickFilters) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Page(String policyVersion, Instant generatedAt, String officeZone, LocalDate officeDate, String scope, UUID departmentId,
                       int number, int size, long totalElements, int totalPages, Counts counts, Map<String, Long> laneCounts, List<Item> items) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Item(UUID id, UUID departmentId, UUID employeeId, UUID teamLeadUserId, UUID assignedByUserId, String assignedByRole, String assigneeRole,
                       String title, String description, String departmentBranch, LocalDate dueDate, String status, String employeeUpdate, String teamLeadReview,
                       String insightReviewSource, String insightReviewReason, Instant insightReviewRequestedAt, int reworkCycle, Instant startedAt, Instant completedAt,
                       Instant approvedAt, Instant acknowledgedAt, Instant createdAt, long version, String assigneeName, String auditStatus, UUID auditRecordId,
                       Long auditVersion, Instant updatedAt, Long submissionVersion, String priority, Boolean blocked, List<String> allowedActions, String nextActor, String lane) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record History(String id, String title, Instant occurredAt, String actorRole, String note) {}
    public record Detail(Item item, List<History> history, boolean historyTruncated) {}
    public record SavedFilter(String id, String name, Period scope, QuickFilter quickFilter, String query, String status, String branch, Sort sort) {}
    public record Preferences(long revision, Layout layout, Density density, List<SavedFilter> savedFilters) {}
    public record PreferencesUpdate(Long expectedRevision, Layout layout, Density density, List<SavedFilter> savedFilters) {}
}
