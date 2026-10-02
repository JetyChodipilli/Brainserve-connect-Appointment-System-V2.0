package com.brainserve.appointment.reporting.application;

import com.brainserve.appointment.shared.application.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class VisitTypeReportServiceTest {
    final UUID actor = UUID.randomUUID(), employee = UUID.randomUUID(), department = UUID.randomUUID();
    final LocalDate date = LocalDate.of(2026, 9, 15);
    final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    final RoleDataScopeService scopes = mock(RoleDataScopeService.class);
    final VisitTypeReportService service = new VisitTypeReportService(jdbc, scopes, "Asia/Kolkata");

    void scope(String role, boolean wide, UUID dept) {
        when(scopes.resolve(actor)).thenReturn(new RoleDataScopeService.RoleDataScope(actor, employee, dept, role, wide));
    }
    @ParameterizedTest
    @ValueSource(strings = {"ROLE_CEO", "ROLE_SYSTEM_ADMIN", "ROLE_HR_ADMIN", "ROLE_MANAGER", "ROLE_TEAM_LEAD", "ROLE_RECEPTIONIST", "ROLE_SECURITY", "ROLE_EMPLOYEE"})
    void appliesRoleScopeAndOfficeDayBounds(String role) {
        boolean wide = List.of("ROLE_CEO", "ROLE_SYSTEM_ADMIN", "ROLE_RECEPTIONIST", "ROLE_SECURITY").contains(role);
        scope(role, wide, wide ? null : department);
        when(jdbc.query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class))).thenAnswer(call -> {
            String sql = call.getArgument(0);
            MapSqlParameterSource params = call.getArgument(1);
            assertEquals(!wide, sql.contains("routing_department_id = :departmentId"));
            assertEquals(role.equals("ROLE_EMPLOYEE"), sql.contains("host_employee_id = :employeeId"));
            if (!wide) assertEquals(department, params.getValue("departmentId"));
            if (role.equals("ROLE_EMPLOYEE")) assertEquals(employee, params.getValue("employeeId"));
            assertEquals(OffsetDateTime.parse("2026-09-15T00:00:00+05:30"), params.getValue("from"));
            assertEquals(OffsetDateTime.parse("2026-09-16T00:00:00+05:30"), params.getValue("to"));
            assertTrue(sql.contains("GROUP BY type"));
            return List.of(new VisitTypeReportService.VisitTypeCount("EMPLOYEE_VISIT", 8));
        });
        assertEquals(8, service.counts(actor, date, date).getFirst().total());
    }
    @Test void rejectsUnboundedAndReversedRangesBeforeQuerying() {
        scope("ROLE_CEO", true, null);
        assertThrows(BusinessException.class, () -> service.counts(actor, date, date.plusDays(366)));
        assertThrows(BusinessException.class, () -> service.counts(actor, date, date.minusDays(1)));
        assertThrows(BusinessException.class, () -> service.counts(actor, null, date));
        verifyNoInteractions(jdbc);
    }
    @Test void missingDepartmentCannotReadCompanyChartOrSummaryOrCache() {
        scope("ROLE_HR_ADMIN", false, null);
        var redis = mock(StringRedisTemplate.class);
        var dashboard = new RoleDashboardQueryService(jdbc, scopes, redis, new ObjectMapper(), "Asia/Kolkata", 180, 120);
        assertThrows(BusinessException.class, () -> service.counts(actor, date, date));
        assertThrows(BusinessException.class, () -> dashboard.summary(actor, RoleDashboardQueryService.PeriodPreset.TODAY, null, null));
        verifyNoInteractions(jdbc, redis);
    }
}
