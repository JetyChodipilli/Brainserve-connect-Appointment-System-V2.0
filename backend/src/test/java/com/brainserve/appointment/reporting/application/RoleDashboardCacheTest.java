package com.brainserve.appointment.reporting.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RoleDashboardCacheTest {
    final UUID actor = UUID.randomUUID(), employee = UUID.randomUUID(), department = UUID.randomUUID();
    final LocalDate date = LocalDate.of(2026, 9, 15);
    final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    final JdbcTemplate plainJdbc = mock(JdbcTemplate.class);
    final RoleDataScopeService scopes = mock(RoleDataScopeService.class);
    final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    final ValueOperations<String, String> values = mock(ValueOperations.class);
    final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    final RoleDashboardQueryService service = new RoleDashboardQueryService(jdbc, scopes, redis, mapper, "Asia/Kolkata", 180);

    void scope(UUID dept) {
        when(scopes.resolve(actor)).thenReturn(new RoleDataScopeService.RoleDataScope(actor, employee, dept, "ROLE_MANAGER", false));
        when(redis.opsForValue()).thenReturn(values);
    }
    String key(UUID dept) {
        return "reporting:dashboard:v4:" + actor + ":ROLE_MANAGER:" + dept + ":" + employee + ":" + date + ":" + date;
    }
    RoleDashboardQueryService.DashboardSummary summary() {
        return new RoleDashboardQueryService.DashboardSummary(1, 2, 0, 4, 4, 5, 2, 1, 0, 0,
                1, 1, 1, 1, 20L, "ROLE_MANAGER", "DEPARTMENT", department, date, date, Instant.parse("2026-09-15T10:00:00Z"));
    }
    void emptyDatabase() {
        when(jdbc.query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class))).thenReturn(List.of());
        when(jdbc.getJdbcTemplate()).thenReturn(plainJdbc);
        when(jdbc.queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Long.class))).thenReturn(0L);
    }
    RoleDashboardQueryService.DashboardSummary load() {
        return service.summary(actor, RoleDashboardQueryService.PeriodPreset.CUSTOM, date, date);
    }

    @Test void resolvesCurrentScopeBeforeReturningCachedData() throws Exception {
        scope(department);
        when(values.get(key(department))).thenReturn(mapper.writeValueAsString(summary()));
        assertEquals(summary(), load());
        var order = inOrder(scopes, values);
        order.verify(scopes).resolve(actor);
        order.verify(values).get(key(department));
        verifyNoInteractions(jdbc);
    }
    @Test void movingDepartmentsCannotReuseThePreviousScopeCache() throws Exception {
        scope(department);
        when(values.get(key(department))).thenReturn(mapper.writeValueAsString(summary()));
        assertEquals(department, load().departmentId());
        UUID nextDepartment = UUID.randomUUID();
        scope(nextDepartment);
        emptyDatabase();
        assertEquals(nextDepartment, load().departmentId());
        verify(values).get(key(nextDepartment));
        verify(values).set(eq(key(nextDepartment)), anyString(), eq(Duration.ofSeconds(180)));
    }
    @Test void optionalCacheTimeoutsStillReturnDatabaseResults() {
        scope(department);
        emptyDatabase();
        when(values.get(anyString())).thenThrow(new QueryTimeoutException("cache unavailable"));
        doThrow(new QueryTimeoutException("cache unavailable")).when(values).set(anyString(), anyString(), any(Duration.class));
        assertEquals(department, load().departmentId());
        verify(jdbc).query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class));
    }
    @Test void corruptCacheEntryIsReplacedWithABoundedTtl() {
        scope(department);
        emptyDatabase();
        when(values.get(anyString())).thenReturn("{invalid-json");
        assertEquals(0, load().scheduledVisits());
        verify(values).set(eq(key(department)), anyString(), eq(Duration.ofSeconds(180)));
    }
}
