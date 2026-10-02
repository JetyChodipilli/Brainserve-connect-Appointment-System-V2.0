package com.brainserve.appointment.reporting.application;

import com.brainserve.appointment.iam.domain.SystemRole;
import com.brainserve.appointment.iam.api.CurrentAccountAuthority;
import com.brainserve.appointment.iam.api.CurrentAccountAuthority.Authority;
import com.brainserve.appointment.operations.api.DashboardDependencyProbe;
import com.brainserve.appointment.reporting.application.RoleDashboardQueryService.PeriodPreset;
import com.brainserve.appointment.shared.application.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.time.*;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AdministrationDashboardServiceTest {
    final UUID user = UUID.randomUUID(), employee = UUID.randomUUID();
    final Instant now = Instant.parse("2026-05-03T12:00:00Z");
    NamedParameterJdbcTemplate jdbc;
    DashboardDependencyProbe dependencies;
    CurrentAccountAuthority authorities;
    AdministrationDashboardService service;
    Authority actor;
    AdministrationDashboardService.Statistics stats;

    @BeforeEach @SuppressWarnings("unchecked") void prepare() {
        jdbc = mock(NamedParameterJdbcTemplate.class);
        dependencies = mock(DashboardDependencyProbe.class);
        authorities = mock(CurrentAccountAuthority.class);
        service = new AdministrationDashboardService(jdbc, authorities, dependencies, ZoneId.of("Asia/Kolkata"), Clock.fixed(now, ZoneOffset.UTC));
        actor = new Authority("ROLE_CEO", employee, permissions(SystemRole.ROLE_CEO));
        stats = new AdministrationDashboardService.Statistics(0, 0, null);
        when(authorities.requireActive(user)).thenAnswer(call -> actor);
        when(jdbc.query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class))).thenReturn(List.of());
        when(jdbc.queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Long.class))).thenReturn(0L);
        when(jdbc.queryForObject(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class)))
                .thenAnswer(call -> call.<String>getArgument(0).startsWith("select count(*) n") ? stats : null);
        when(dependencies.inspect()).thenReturn(new DashboardDependencyProbe.Snapshot(now,
                List.of(new DashboardDependencyProbe.Observation("PostgreSQL", false),
                        new DashboardDependencyProbe.Observation("Redis", true))));
    }

    @Test void periodUsesOfficeDatesAndNextDayExclusiveCalendarBound() {
        var today = service.range(null, null, null, Instant.parse("2026-05-02T19:00:00Z"));
        assertThat(today.from()).isEqualTo(LocalDate.of(2026, 5, 3));
        assertThat(today.to()).isEqualTo(today.from());
        assertThat(service.range(PeriodPreset.PREVIOUS_MONTH, null, null, now))
                .isEqualTo(new AdministrationDashboardService.DateRange(LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 30)));
        assertThat(service.range(PeriodPreset.CUSTOM, LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31), now).to())
                .isEqualTo(LocalDate.of(2024, 12, 31));
        for (LocalDate[] invalid : List.of(new LocalDate[]{null, LocalDate.now()},
                new LocalDate[]{LocalDate.of(2026, 5, 2), LocalDate.of(2026, 5, 1)},
                new LocalDate[]{LocalDate.of(2024, 1, 1), LocalDate.of(2025, 1, 1)})) {
            assertThatThrownBy(() -> service.range(PeriodPreset.CUSTOM, invalid[0], invalid[1], now))
                    .isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo("INVALID_DASHBOARD_RANGE");
        }
    }

    @Test void zeroCountsAreAvailableButEmptyRawDenominatorIsNotApplicable() {
        var response = service.cards(user, null, null, null);
        assertThat(response.metricVersion()).isEqualTo("sprint3.v1");
        assertThat(response.scope()).isEqualTo("COMPANY");
        assertThat(response.departmentId()).isNull();
        assertThat(response.cards()).extracting("id").containsExactly("VIS02", "VIS05", "VIS07", "VIS09", "WORK03", "WORK07");
        assertThat(card(response, "VIS02").state()).isEqualTo("AVAILABLE");
        assertThat(card(response, "VIS02").value()).isZero();
        assertThat(card(response, "VIS09").state()).isEqualTo("NOT_APPLICABLE");
        assertThat(card(response, "VIS09").value()).isNull();
        assertThat(card(response, "VIS05").freshUntil()).isEqualTo(now.plusSeconds(15));
        assertThat(response.supplementary().getFirst().freshUntil()).isEqualTo(now.plusSeconds(300));
        assertThat(response.cards()).allSatisfy(card -> assertThat(card.comparison()).isNull());
    }

    @Test void explicitNullContractFieldsSurviveApplicationNonNullSerialization() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules()
                .setSerializationInclusion(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        var json = mapper.readTree(mapper.writeValueAsString(service.cards(user, null, null, null)));
        assertThat(json.has("departmentId")).isTrue();
        assertThat(json.get("departmentId").isNull()).isTrue();
        var wait = json.get("cards").get(3);
        assertThat(wait.has("value")).isTrue();
        assertThat(wait.get("value").isNull()).isTrue();
        assertThat(wait.has("comparison")).isTrue();
        assertThat(wait.get("comparison").isNull()).isTrue();
        assertThat(json.get("asOf").asText()).isEqualTo(now.toString());
    }

    @Test void rawZeroWaitP95RemainsNumericZeroWithSampleSize() {
        stats = new AdministrationDashboardService.Statistics(10, 0, 0d);
        var wait = card(service.cards(user, null, null, null), "VIS09");
        assertThat(wait.state()).isEqualTo("AVAILABLE");
        assertThat(wait.value()).isZero();
        assertThat(wait.sampleSize()).isEqualTo(10);
    }

    @Test void fixedDueCohortKeepsUnfinishedAndLateWorkAndCurrentPeriodIsPreliminary() {
        stats = new AdministrationDashboardService.Statistics(20, 15, 0d);
        var work = card(service.cards(user, null, null, null), "WORK07");
        assertThat(work.value()).isEqualTo(75d);
        assertThat(work.eligibleCount()).isEqualTo(20);
        assertThat(work.reason()).contains("Preliminary", "unfinished", "unknown original deadlines");
    }

    @Test void missingOriginalDeadlinesCannotConfirmAnEmptyDueCohort() {
        when(jdbc.queryForObject(contains("where not exists (select 1 from work_original_commitment"), any(MapSqlParameterSource.class), eq(Long.class))).thenReturn(4L);
        var work = card(service.cards(user, null, null, null), "WORK07");
        assertThat(work.state()).isEqualTo("UNAVAILABLE");
        assertThat(work.value()).isNull();
        assertThat(work.excludedCount()).isEqualTo(4);
    }

    @Test void adminEvidenceGapsRemainUnavailableAndDependenciesAreSanitized() {
        actor = new Authority("ROLE_SYSTEM_ADMIN", null, permissions(SystemRole.ROLE_SYSTEM_ADMIN));
        var response = service.cards(user, null, null, null);
        assertThat(response.cards()).extracting("id").containsExactly("OPS01", "OPS04", "IAM03", "VIS08", "NTF03", "OPS07");
        for (String id : List.of("OPS01", "VIS08", "OPS07")) {
            assertThat(card(response, id).state()).isEqualTo("UNAVAILABLE");
            assertThat(card(response, id).value()).isNull();
        }
        assertThat(response.supplementary().getFirst().state()).isEqualTo("UNAVAILABLE");
        assertThat(card(response, "OPS04").state()).isEqualTo("UNAVAILABLE");
        assertThat(card(response, "OPS04").value()).isNull();
        assertThat(card(response, "OPS04").excludedCount()).isEqualTo(4);
        var records = service.records(user, "OPS04", null, null, null, 0, 100);
        assertThat(records.totalElements()).isEqualTo(2);
        assertThat(records.items().toString()).doesNotContain("secret", "redis://private");
        var missing = service.records(user, "VIS08", null, null, null, 0, 50);
        assertThat(missing.state()).isEqualTo("UNAVAILABLE");
        assertThat(missing.items()).isEmpty();
    }

    @Test void fullDistinctDependencyCoverageCanBeReadyButDuplicateProbesCannotFillGaps() {
        actor = new Authority("ROLE_SYSTEM_ADMIN", null, permissions(SystemRole.ROLE_SYSTEM_ADMIN));
        var names = List.of("PostgreSQL", "Redis", "Kafka", "SMTP", "Object storage", "ClamAV");
        when(dependencies.inspect()).thenReturn(new DashboardDependencyProbe.Snapshot(now,
                names.stream().map(name -> new DashboardDependencyProbe.Observation(name, true)).toList()));
        assertThat(card(service.cards(user, null, null, null), "OPS04").displayValue()).isEqualTo("READY");
        when(dependencies.inspect()).thenReturn(new DashboardDependencyProbe.Snapshot(now,
                names.stream().map(name -> new DashboardDependencyProbe.Observation("PostgreSQL", true)).toList()));
        var missing = card(service.cards(user, null, null, null), "OPS04");
        assertThat(missing.state()).isEqualTo("UNAVAILABLE");
        assertThat(missing.excludedCount()).isEqualTo(5);
    }

    @Test void currentPermissionDenialsRestrictCardsAndRejectRecords() {
        var permissions = new HashSet<>(actor.permissions());
        permissions.remove("VISITOR_OCCUPANCY_READ");
        actor = new Authority(actor.role(), employee, Set.copyOf(permissions));
        assertThat(card(service.cards(user, null, null, null), "VIS05").state()).isEqualTo("RESTRICTED");
        assertThatThrownBy(() -> service.records(user, "VIS05", null, null, null, 0, 50))
                .isInstanceOf(BusinessException.class).extracting("status").isEqualTo(org.springframework.http.HttpStatus.FORBIDDEN);
    }

    @Test void ceoCannotGuessTechnicalMetricsAndUnknownIdsAreNotFound() {
        var granted = new HashSet<>(actor.permissions());
        granted.add("SYSTEM_CONFIGURE");
        actor = new Authority(actor.role(), employee, Set.copyOf(granted));
        assertThatThrownBy(() -> service.records(user, "OPS04", null, null, null, 0, 50)).isInstanceOf(BusinessException.class)
                .extracting("status").isEqualTo(org.springframework.http.HttpStatus.FORBIDDEN);
        verifyNoInteractions(dependencies);
        assertThatThrownBy(() -> service.records(user, "NOPE", null, null, null, 0, 50)).isInstanceOf(BusinessException.class)
                .extracting("status").isEqualTo(org.springframework.http.HttpStatus.NOT_FOUND);
    }

    @Test void employeeAndInactiveAccountsAreDeniedBeforeReadingMeasurements() {
        actor = new Authority("ROLE_EMPLOYEE", employee, permissions(SystemRole.ROLE_EMPLOYEE));
        assertThatThrownBy(() -> service.cards(user, null, null, null)).isInstanceOf(BusinessException.class);
        actor = null;
        when(authorities.requireActive(user)).thenThrow(new BusinessException("DASHBOARD_ACCESS_DENIED", "Inactive", org.springframework.http.HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> service.cards(user, null, null, null)).isInstanceOf(BusinessException.class);
        verify(jdbc, never()).queryForObject(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class));
    }

    @Test @SuppressWarnings("unchecked") void roleOrPermissionMutationDuringRequestRejectsOldResponse() {
        var initial = actor;
        var changed = new Authority("ROLE_SYSTEM_ADMIN", null, permissions(SystemRole.ROLE_SYSTEM_ADMIN));
        when(authorities.requireActive(user)).thenReturn(initial, changed);
        assertThatThrownBy(() -> service.cards(user, null, null, null)).isInstanceOf(BusinessException.class)
                .hasMessageContaining("changed");
    }

    @Test void sourceMutationMarksLiveValuesStaleAndRemovesGenerationClaim() {
        when(jdbc.queryForObject(contains("select generation"), any(MapSqlParameterSource.class), eq(Long.class))).thenReturn(1L, 2L);
        var response = service.cards(user, null, null, null);
        assertThat(response.sourceGeneration()).isNull();
        assertThat(card(response, "VIS02").freshness()).isEqualTo("STALE");
    }

    @Test void drilldownRejectsUnboundedPagesAndSizes() {
        for (int[] invalid : List.of(new int[]{-1, 50}, new int[]{10001, 50}, new int[]{0, 0}, new int[]{0, 101})) {
            assertThatThrownBy(() -> service.records(user, "VIS02", null, null, null, invalid[0], invalid[1]))
                    .isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo("INVALID_DASHBOARD_PAGE");
        }
    }

    private Set<String> permissions(SystemRole role) { return role.permissions().stream().map(Enum::name).collect(java.util.stream.Collectors.toUnmodifiableSet()); }

    private AdministrationDashboardService.MetricCard card(AdministrationDashboardService.DashboardCards response, String id) {
        return response.cards().stream().filter(card -> card.id().equals(id)).findFirst().orElseThrow();
    }
}
