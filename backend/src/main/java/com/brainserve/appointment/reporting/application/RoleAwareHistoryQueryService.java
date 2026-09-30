package com.brainserve.appointment.reporting.application;

import com.brainserve.appointment.reporting.api.CursorPage;
import com.brainserve.appointment.reporting.api.HistoryDataset;
import com.brainserve.appointment.reporting.api.HistoryFilter;
import com.brainserve.appointment.reporting.api.HistoryRow;
import com.brainserve.appointment.shared.application.BusinessException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Types;
import java.time.ZoneOffset;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class RoleAwareHistoryQueryService {
    private static final String HR = "ROLE_HR_ADMIN";
    private static final String TEAM_LEAD = "ROLE_TEAM_LEAD";
    private static final String EMPLOYEE = "ROLE_EMPLOYEE";

    private final NamedParameterJdbcTemplate jdbc;
    private final RoleDataScopeService scopes;
    private final ObjectMapper objectMapper;
    private final String officeZone;

    public RoleAwareHistoryQueryService(NamedParameterJdbcTemplate jdbc, RoleDataScopeService scopes,
                                        ObjectMapper objectMapper,
                                        @Value("${brainserve.appointment.office-zone:Asia/Kolkata}") String officeZone) {
        this.jdbc = jdbc;
        this.scopes = scopes;
        this.objectMapper = objectMapper;
        this.officeZone = officeZone;
    }

    @Transactional(readOnly = true)
    public CursorPage<HistoryRow> search(UUID actorUserId, HistoryDataset dataset, HistoryFilter filter) {
        RoleDataScopeService.RoleDataScope scope = scopes.resolve(actorUserId);
        scopes.requireDataset(scope, dataset);
        UUID departmentId = scopes.effectiveDepartment(scope, filter.departmentId());
        Cursor cursor = decode(filter.cursor());
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue(
                        "from",
                        filter.from().atOffset(ZoneOffset.UTC),
                        Types.TIMESTAMP_WITH_TIMEZONE
                )
                .addValue(
                        "to",
                        filter.to().atOffset(ZoneOffset.UTC),
                        Types.TIMESTAMP_WITH_TIMEZONE
                )
                .addValue(
                        "departmentId",
                        departmentId,
                        Types.OTHER
                )
                .addValue(
                        "status",
                        filter.status(),
                        Types.VARCHAR
                )
                .addValue(
                        "query",
                        filter.query() == null
                                ? null
                                : "%" + escapeLike(filter.query()) + "%",
                        Types.VARCHAR
                )
                .addValue(
                        "cursorTime",
                        cursor == null
                                ? null
                                : cursor.occurredAt().atOffset(ZoneOffset.UTC),
                        Types.TIMESTAMP_WITH_TIMEZONE
                )
                .addValue(
                        "cursorId",
                        cursor == null ? null : cursor.id(),
                        Types.OTHER
                )
                .addValue(
                        "employeeId",
                        scope.employeeId(),
                        Types.OTHER
                )
                .addValue(
                        "userId",
                        actorUserId,
                        Types.OTHER
                )
                .addValue(
                        "officeZone",
                        officeZone,
                        Types.VARCHAR
                )
                .addValue(
                        "limit",
                        filter.size() + 1,
                        Types.INTEGER
                );
        String sql = sql(dataset, scope, departmentId, filter, cursor);
        List<HistoryRow> loaded = jdbc.query(sql, parameters, (rs, rowNumber) -> map(rs, dataset));
        boolean hasMore = loaded.size() > filter.size();
        List<HistoryRow> items = hasMore ? new ArrayList<>(loaded.subList(0, filter.size())) : loaded;
        String nextCursor = hasMore && !items.isEmpty() ? encode(items.get(items.size() - 1)) : null;
        return new CursorPage<>(List.copyOf(items), nextCursor, hasMore, filter.size());
    }

    private String sql(HistoryDataset dataset, RoleDataScopeService.RoleDataScope scope, UUID departmentId,
                       HistoryFilter filter, Cursor cursor) {
        String base = switch (dataset) {
            case VISITS -> """
                    SELECT appointment.id, appointment.slot_start AS occurred_at,
                           appointment.routing_department_id AS department_id,
                           appointment.reference_number AS primary_label,
                           appointment.visitor_name || ' · ' || host.display_name AS secondary_label,
                           appointment.status,
                           jsonb_build_object('visitorName', appointment.visitor_name,
                             'visitorCompany', appointment.visitor_company, 'visitType', appointment.type,
                             'hostName', host.display_name, 'purpose', appointment.purpose,
                             'checkedInAt', access.checked_in_at, 'checkedOutAt', access.checked_out_at)::text AS details_json
                      FROM appointment
                      JOIN employee host ON host.id = appointment.host_employee_id
                      LEFT JOIN visit_access_record access ON access.appointment_id = appointment.id
                     WHERE appointment.slot_start >= :from AND appointment.slot_start < :to
                    """;
            case EMPLOYEES -> """
                    SELECT employee.id, employee.joining_date::timestamp AT TIME ZONE :officeZone AS occurred_at,
                           employee.department_id, employee.employee_number AS primary_label,
                           employee.display_name || ' · ' || employee.designation AS secondary_label,
                           employee.status,
                           jsonb_build_object('displayName', employee.display_name,
                             'officialEmail', employee.official_email, 'designation', employee.designation,
                             'joiningDate', employee.joining_date, 'relievingDate', employee.relieving_date)::text AS details_json
                      FROM employee
                     WHERE employee.joining_date >= timezone(:officeZone, :from)::date
                       AND employee.joining_date < timezone(:officeZone, :to)::date
                    """;
            case TERMINATIONS -> """
                    SELECT termination.id, termination.requested_at AS occurred_at, termination.department_id,
                           employee.employee_number AS primary_label,
                           employee.display_name || ' · termination request' AS secondary_label,
                           termination.status,
                           jsonb_build_object('employeeName', employee.display_name, 'effectiveDate', termination.effective_date,
                             'reason', termination.reason, 'decisionNote', termination.decision_note,
                             'decidedAt', termination.decided_at)::text AS details_json
                      FROM employee_termination_request termination
                      JOIN employee ON employee.id = termination.employee_id
                     WHERE termination.requested_at >= :from AND termination.requested_at < :to
                    """;
            case WORKBOARD -> """
                    SELECT activity.id, activity.occurred_at, activity.department_id,
                           activity.work_task_id::text AS primary_label,
                           activity.event_type || ' · ' || activity.current_status AS secondary_label,
                           activity.current_status AS status,
                           activity.details_json::text AS details_json
                      FROM workboard_activity_event activity
                     WHERE activity.occurred_at >= :from AND activity.occurred_at < :to
                    """;
            case CHECKPOINTS -> """
                    SELECT checkpoint.id, checkpoint.occurred_at, checkpoint.department_id,
                           checkpoint.badge_number AS primary_label,
                           checkpoint.visitor_name || ' · ' || checkpoint.event_type AS secondary_label,
                           checkpoint.event_type AS status,
                           (checkpoint.details_json || jsonb_build_object('appointmentId', checkpoint.appointment_id,
                             'visitorName', checkpoint.visitor_name, 'badgeNumber', checkpoint.badge_number))::text AS details_json
                      FROM visitor_checkpoint_event checkpoint
                     WHERE checkpoint.occurred_at >= :from AND checkpoint.occurred_at < :to
                    """;
            case AUDIT -> """
                    SELECT audit.id, audit.occurred_at,
                           COALESCE((CASE WHEN audit.details_json->>'departmentId' ~
                             '^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$'
                             THEN audit.details_json->>'departmentId' END)::uuid,
                             appointment.routing_department_id, task.department_id) AS department_id,
                           audit.event_type AS primary_label,
                           audit.target_type || ' · ' || audit.target_id AS secondary_label,
                           audit.outcome AS status,
                           jsonb_build_object('actorId', audit.actor_id, 'targetType', audit.target_type,
                             'targetId', audit.target_id, 'correlationId', audit.correlation_id,
                             'details', audit.details_json)::text AS details_json
                      FROM audit_event_history audit
                      LEFT JOIN appointment ON audit.target_type = 'APPOINTMENT'
                        AND audit.target_id ~ '^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$'
                        AND appointment.id = audit.target_id::uuid
                      LEFT JOIN department_work_task task ON audit.target_type = 'WORK_TASK'
                        AND audit.target_id ~ '^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$'
                        AND task.id = audit.target_id::uuid
                     WHERE audit.occurred_at >= :from AND audit.occurred_at < :to
                    """;
            case ESSENTIAL_LOGS -> """
                    SELECT log.id, log.occurred_at, NULL::uuid AS department_id,
                           log.event_type AS primary_label, log.title AS secondary_label, log.status,
                           jsonb_build_object('category', log.category, 'subjectType', log.subject_type,
                             'subjectId', log.subject_id, 'referenceId', log.reference_id,
                             'detail', log.detail, 'actorUserId', log.actor_user_id,
                             'approverUserId', log.approver_user_id)::text AS details_json
                      FROM essential_log_record log
                     WHERE log.occurred_at >= :from AND log.occurred_at < :to
                    """;
        };
        String alias = alias(dataset);
        StringBuilder sql = new StringBuilder(base);
        if (departmentId != null && dataset != HistoryDataset.ESSENTIAL_LOGS) {
            sql.append(" AND ").append(departmentExpression(dataset)).append(" = :departmentId");
        }
        appendPersonalScope(sql, dataset, scope);
        if (filter.status() != null) sql.append(" AND ").append(statusExpression(dataset)).append(" = :status");
        if (filter.query() != null) sql.append(" AND (").append(searchExpression(dataset)).append(")");
        if (cursor != null) sql.append(" AND (").append(timeExpression(dataset)).append(" < :cursorTime OR (")
                .append(timeExpression(dataset)).append(" = :cursorTime AND ").append(alias).append(".id < :cursorId))");
        sql.append(" ORDER BY ").append(timeExpression(dataset)).append(" DESC, ").append(alias)
                .append(".id DESC LIMIT :limit");
        return sql.toString();
    }

    private void appendPersonalScope(StringBuilder sql, HistoryDataset dataset,
                                     RoleDataScopeService.RoleDataScope scope) {
        if (scope.role().equals(EMPLOYEE)) {
            if (dataset == HistoryDataset.VISITS) sql.append(" AND appointment.host_employee_id = :employeeId");
            else if (dataset == HistoryDataset.WORKBOARD) sql.append(" AND activity.employee_id = :employeeId");
        }
        if (scope.role().equals(HR) && dataset == HistoryDataset.AUDIT) {
            sql.append(" AND (COALESCE((CASE WHEN audit.details_json->>'departmentId' ~ '^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$' ")
                    .append("THEN audit.details_json->>'departmentId' END)::uuid, appointment.routing_department_id, ")
                    .append("task.department_id) = :departmentId OR audit.actor_id = CAST(:userId AS text))");
        }
        if (scope.role().equals(TEAM_LEAD) && dataset == HistoryDataset.WORKBOARD) {
            sql.append(" AND activity.team_lead_user_id = :userId");
        }
    }

    private String alias(HistoryDataset dataset) {
        return switch (dataset) {
            case VISITS -> "appointment";
            case EMPLOYEES -> "employee";
            case TERMINATIONS -> "termination";
            case WORKBOARD -> "activity";
            case AUDIT -> "audit";
            case CHECKPOINTS -> "checkpoint";
            case ESSENTIAL_LOGS -> "log";
        };
    }

    private String timeExpression(HistoryDataset dataset) {
        return switch (dataset) {
            case VISITS -> "appointment.slot_start";
            case EMPLOYEES -> "employee.joining_date::timestamp AT TIME ZONE :officeZone";
            case TERMINATIONS -> "termination.requested_at";
            case WORKBOARD -> "activity.occurred_at";
            case AUDIT -> "audit.occurred_at";
            case CHECKPOINTS -> "checkpoint.occurred_at";
            case ESSENTIAL_LOGS -> "log.occurred_at";
        };
    }

    private String departmentExpression(HistoryDataset dataset) {
        return switch (dataset) {
            case VISITS -> "appointment.routing_department_id";
            case EMPLOYEES -> "employee.department_id";
            case TERMINATIONS -> "termination.department_id";
            case WORKBOARD -> "activity.department_id";
            case AUDIT -> "COALESCE((CASE WHEN audit.details_json->>'departmentId' ~ '^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$' "
                    + "THEN audit.details_json->>'departmentId' END)::uuid, appointment.routing_department_id, task.department_id)";
            case CHECKPOINTS -> "checkpoint.department_id";
            case ESSENTIAL_LOGS -> "NULL::uuid";
        };
    }

    private String statusExpression(HistoryDataset dataset) {
        return switch (dataset) {
            case VISITS -> "appointment.status";
            case EMPLOYEES -> "employee.status";
            case TERMINATIONS -> "termination.status";
            case WORKBOARD -> "activity.current_status";
            case AUDIT -> "audit.outcome";
            case CHECKPOINTS -> "checkpoint.event_type";
            case ESSENTIAL_LOGS -> "log.status";
        };
    }

    private String searchExpression(HistoryDataset dataset) {
        return switch (dataset) {
            case VISITS -> "appointment.reference_number ILIKE :query ESCAPE '\\\\' OR appointment.visitor_name ILIKE :query ESCAPE '\\\\' OR host.display_name ILIKE :query ESCAPE '\\\\'";
            case EMPLOYEES -> "employee.employee_number ILIKE :query ESCAPE '\\\\' OR employee.display_name ILIKE :query ESCAPE '\\\\' OR employee.official_email ILIKE :query ESCAPE '\\\\'";
            case TERMINATIONS -> "employee.employee_number ILIKE :query ESCAPE '\\\\' OR employee.display_name ILIKE :query ESCAPE '\\\\' OR termination.reason ILIKE :query ESCAPE '\\\\'";
            case WORKBOARD -> "activity.work_task_id::text ILIKE :query ESCAPE '\\\\' OR activity.event_type ILIKE :query ESCAPE '\\\\' OR activity.details_json::text ILIKE :query ESCAPE '\\\\'";
            case AUDIT -> "audit.event_type ILIKE :query ESCAPE '\\\\' OR audit.actor_id ILIKE :query ESCAPE '\\\\' OR audit.target_id ILIKE :query ESCAPE '\\\\'";
            case CHECKPOINTS -> "checkpoint.visitor_name ILIKE :query ESCAPE '\\\\' OR checkpoint.badge_number ILIKE :query ESCAPE '\\\\'";
            case ESSENTIAL_LOGS -> "log.event_type ILIKE :query ESCAPE '\\\\' OR log.title ILIKE :query ESCAPE '\\\\' OR log.detail ILIKE :query ESCAPE '\\\\'";
        };
    }

    private HistoryRow map(ResultSet rs, HistoryDataset dataset) throws SQLException {
        String json = rs.getString("details_json");
        Map<String, Object> details;
        try {
            details = json == null ? Map.of() : objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception ex) {
            details = Map.of("detail", "Historical detail could not be decoded");
        }
        return new HistoryRow(rs.getObject("id", UUID.class), rs.getTimestamp("occurred_at").toInstant(), dataset,
                rs.getObject("department_id", UUID.class), rs.getString("primary_label"),
                rs.getString("secondary_label"), rs.getString("status"), details);
    }

    private String encode(HistoryRow row) {
        String raw = row.occurredAt() + "|" + row.id();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private Cursor decode(String value) {
        if (value == null) return null;
        try {
            String[] parts = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8).split("\\|", 2);
            return new Cursor(Instant.parse(parts[0]), UUID.fromString(parts[1]));
        } catch (RuntimeException ex) {
            throw new BusinessException("INVALID_HISTORY_CURSOR", "The history cursor is invalid",
                    HttpStatus.BAD_REQUEST);
        }
    }

    private String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private record Cursor(Instant occurredAt, UUID id) {}
}
