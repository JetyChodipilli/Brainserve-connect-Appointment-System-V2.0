package com.brainserve.appointment.reporting.application;

import com.brainserve.appointment.shared.application.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpStatus;
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

@SuppressWarnings("unchecked")
class RoleDashboardCacheTest {
    final UUID actor = UUID.randomUUID(), employee = UUID.randomUUID(), department = UUID.randomUUID();
    final LocalDate date = LocalDate.of(2026, 9, 15);
    final Instant refreshed = Instant.now().minusSeconds(15);
    final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    final RoleDataScopeService scopes = mock(RoleDataScopeService.class);
    final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    final ValueOperations<String, String> values = mock(ValueOperations.class);
    final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    final RoleDashboardQueryService service = new RoleDashboardQueryService(jdbc, scopes, redis, mapper, "Asia/Kolkata", 180, 120);
    final RoleDashboardQueryService.SourceState source = new RoleDashboardQueryService.SourceState(7L, 7L, refreshed, refreshed, true);

    RoleDataScopeService.RoleDataScope scopeFor(UUID dept) {
        return new RoleDataScopeService.RoleDataScope(actor, employee, dept, "ROLE_MANAGER", false);
    }
    void scope(UUID dept) {
        when(scopes.resolve(actor)).thenReturn(scopeFor(dept));
        when(redis.opsForValue()).thenReturn(values);
        source(source);
    }
    void source(RoleDashboardQueryService.SourceState value) {
        when(jdbc.queryForObject(contains("reporting_source_revision revision"), any(MapSqlParameterSource.class), any(RowMapper.class))).thenReturn(value);
    }
    String key(UUID dept, RoleDashboardQueryService.SourceState state) {
        return "reporting:dashboard:v5:" + actor + ":ROLE_MANAGER:" + dept + ":" + employee + ":" + date + ":" + date + ":" + state.cacheVersion();
    }
    RoleDashboardQueryService.DashboardSummary summary() {
        return new RoleDashboardQueryService.DashboardSummary(1, 2, 0, 4, 4, 5, 2, 1, 0, 0,
                1, 1, 1, 1, 20L, "ROLE_MANAGER", "DEPARTMENT", department, date, date, Instant.now(),
                refreshed, 7L, RoleDashboardQueryService.Freshness.FRESH, refreshed.plusSeconds(120), "SUMMARY");
    }
    void emptyDatabase() {
        when(jdbc.query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class))).thenReturn(List.of());
        when(jdbc.queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Long.class))).thenReturn(0L);
    }
    RoleDashboardQueryService.DashboardSummary load() {
        return service.summary(actor, RoleDashboardQueryService.PeriodPreset.CUSTOM, date, date);
    }

    @Test void resolvesCurrentScopeBeforeReturningCachedDataAndPreservesSourceTimestamp() throws Exception {
        scope(department);
        var expected = summary();
        when(values.get(key(department, source))).thenReturn(mapper.writeValueAsString(expected));
        assertEquals(expected, load());
        var order = inOrder(scopes, values);
        order.verify(scopes).resolve(actor);
        order.verify(values).get(key(department, source));
        order.verify(scopes).resolve(actor);
        verify(jdbc, never()).query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class));
    }
    @Test void movingDepartmentsCannotReuseThePreviousScopeCache() throws Exception {
        scope(department);
        when(values.get(key(department, source))).thenReturn(mapper.writeValueAsString(summary()));
        assertEquals(department, load().departmentId());
        UUID nextDepartment = UUID.randomUUID();
        scope(nextDepartment);
        emptyDatabase();
        assertEquals(nextDepartment, load().departmentId());
        verify(values).get(key(nextDepartment, source));
        verify(values).set(eq(key(nextDepartment, source)), anyString(), eq(Duration.ofSeconds(180)));
    }
    @Test void disabledAccountCannotUseItsExistingCache() {
        when(scopes.resolve(actor)).thenThrow(new BusinessException("DISABLED", "Account disabled", HttpStatus.FORBIDDEN));
        assertThrows(BusinessException.class, this::load);
        verifyNoInteractions(redis, jdbc);
    }
    @Test void midRequestDepartmentChangeRejectsOldScopeResult() throws Exception {
        scope(department);
        when(values.get(key(department, source))).thenReturn(mapper.writeValueAsString(summary()));
        when(scopes.resolve(actor)).thenReturn(scopeFor(department), scopeFor(UUID.randomUUID()));
        assertThrows(BusinessException.class, this::load);
        verify(values, never()).set(anyString(), anyString(), any(Duration.class));
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
        verify(values).set(eq(key(department, source)), anyString(), eq(Duration.ofSeconds(180)));
    }
    @Test void committedMutationBypassesCacheUntilTheSourceRefreshCommits() {
        scope(department);
        emptyDatabase();
        source(new RoleDashboardQueryService.SourceState(8L, 7L, refreshed, refreshed, true));
        var stale = load();
        assertEquals(RoleDashboardQueryService.Freshness.STALE, stale.freshness());
        assertEquals(refreshed, stale.sourceRefreshedAt());
        verifyNoInteractions(values);
        var updated = new RoleDashboardQueryService.SourceState(8L, 8L, refreshed.plusSeconds(1), refreshed.plusSeconds(1), true);
        source(updated);
        assertEquals(RoleDashboardQueryService.Freshness.FRESH, load().freshness());
        verify(values).get(key(department, updated));
        verify(values).set(eq(key(department, updated)), anyString(), any(Duration.class));
    }
    @Test void mutationRacingAggregateQueryCannotPopulateCacheWithAnOldReadModel() {
        scope(department);
        emptyDatabase();
        var dirty = new RoleDashboardQueryService.SourceState(8L, 7L, refreshed, refreshed, true);
        when(jdbc.queryForObject(contains("reporting_source_revision revision"), any(MapSqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(source, dirty);
        assertEquals(RoleDashboardQueryService.Freshness.STALE, load().freshness());
        verify(values, never()).set(anyString(), anyString(), any(Duration.class));
    }
    @Test void unknownSourceNeverPromotesZerosToKnownFreshness() {
        scope(department);
        emptyDatabase();
        source(new RoleDashboardQueryService.SourceState(8L, null, null, null, false));
        var unknown = load();
        assertEquals(0, unknown.activeVisits());
        assertNull(unknown.averageWaitSeconds());
        assertNull(unknown.sourceRefreshedAt());
        assertEquals(RoleDashboardQueryService.Freshness.UNKNOWN, unknown.freshness());
        verifyNoInteractions(values);
    }
    @Test void expiredSourceDoesNotBecomeFreshWhenResponseIsGeneratedNow() {
        scope(department);
        emptyDatabase();
        Instant old = Instant.now().minusSeconds(300);
        source(new RoleDashboardQueryService.SourceState(7L, 7L, old, old, true));
        var stale = load();
        assertEquals(RoleDashboardQueryService.Freshness.STALE, stale.freshness());
        assertEquals(old, stale.sourceRefreshedAt());
        assertTrue(stale.generatedAt().isAfter(stale.sourceRefreshedAt()));
        verifyNoInteractions(values);
    }
}
