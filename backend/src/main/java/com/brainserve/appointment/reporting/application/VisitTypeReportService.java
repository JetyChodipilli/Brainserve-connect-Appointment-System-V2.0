package com.brainserve.appointment.reporting.application;

import com.brainserve.appointment.reporting.api.HistoryDataset;
import com.brainserve.appointment.shared.application.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Types;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

@Service
public class VisitTypeReportService {
    private final NamedParameterJdbcTemplate jdbc;
    private final RoleDataScopeService scopes;
    private final ZoneId officeZone;

    public VisitTypeReportService(NamedParameterJdbcTemplate jdbc, RoleDataScopeService scopes,
                                  @Value("${brainserve.appointment.office-zone:Asia/Kolkata}") String officeZone) {
        this.jdbc = jdbc;
        this.scopes = scopes;
        this.officeZone = ZoneId.of(officeZone);
    }

    @Transactional(readOnly = true)
    public List<VisitTypeCount> counts(UUID actor, LocalDate from, LocalDate to) {
        var scope = scopes.resolve(actor);
        scopes.requireDataset(scope, HistoryDataset.VISITS);
        if (from == null || to == null || from.isAfter(to) || ChronoUnit.DAYS.between(from, to) >= 366) {
            throw new BusinessException("INVALID_REPORT_RANGE", "Choose a range of 366 days or less", HttpStatus.BAD_REQUEST);
        }
        // Missing department assignments must never fall back to company-wide totals.
        if (!scope.organizationWide() && (scope.departmentId() == null || scope.employeeId() == null)) {
            throw new BusinessException("HISTORY_SCOPE_DENIED", "Your reporting department is not assigned", HttpStatus.FORBIDDEN);
        }
        var parameters = new MapSqlParameterSource()
                .addValue("from", from.atStartOfDay(officeZone).toOffsetDateTime(), Types.TIMESTAMP_WITH_TIMEZONE)
                .addValue("to", to.plusDays(1).atStartOfDay(officeZone).toOffsetDateTime(), Types.TIMESTAMP_WITH_TIMEZONE);
        String sql = "SELECT type, count(*) AS total FROM appointment WHERE slot_start >= :from AND slot_start < :to";
        if (!scope.organizationWide()) {
            sql += " AND routing_department_id = :departmentId";
            parameters.addValue("departmentId", scope.departmentId(), Types.OTHER);
        }
        if (scope.role().equals("ROLE_EMPLOYEE")) {
            sql += " AND host_employee_id = :employeeId";
            parameters.addValue("employeeId", scope.employeeId(), Types.OTHER);
        }
        sql += " GROUP BY type ORDER BY type";
        return jdbc.query(sql, parameters, (row, index) -> new VisitTypeCount(row.getString("type"), row.getLong("total")));
    }

    public record VisitTypeCount(String type, long total) {}
}
