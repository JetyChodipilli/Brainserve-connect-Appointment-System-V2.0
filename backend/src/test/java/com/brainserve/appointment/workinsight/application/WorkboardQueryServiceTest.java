package com.brainserve.appointment.workinsight.application;

import com.brainserve.appointment.iam.api.CurrentAccountAuthority;
import com.brainserve.appointment.shared.application.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.*;
import java.sql.ResultSet;
import java.time.*;
import com.brainserve.appointment.workinsight.application.WorkboardQueryService.Period;
import java.util.*;
import static com.brainserve.appointment.workinsight.application.WorkboardQueryService.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WorkboardQueryServiceTest {
    final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    final CurrentAccountAuthority authority = mock(CurrentAccountAuthority.class);
    final UUID actor = UUID.randomUUID(), employee = UUID.randomUUID(), department = UUID.randomUUID();
    final List<String> sql = new ArrayList<>();
    final List<SqlParameterSource> parameters = new ArrayList<>();
    WorkboardQueryService service;
    @BeforeEach void setup() throws Exception {
        service = new WorkboardQueryService(jdbc, authority, new ObjectMapper(), ZoneId.of("America/New_York"), Clock.fixed(Instant.parse("2026-03-08T12:00:00Z"), ZoneOffset.UTC));
        when(authority.requireActive(actor)).thenReturn(new CurrentAccountAuthority.Authority("ROLE_EMPLOYEE", employee, Set.of("WORK_TASK_READ", "WORK_TASK_PROGRESS")));
        when(authority.requireWorkScope(actor)).thenAnswer(invocation -> new CurrentAccountAuthority.WorkScope(authority.requireActive(actor),department,null,0,0,0));
        ResultSet scope = mock(ResultSet.class);
        when(scope.getObject("department_id", UUID.class)).thenReturn(department);
        ResultSet counts = mock(ResultSet.class);
        when(counts.getLong("total")).thenReturn(725L);
        when(counts.getLong("s_ALL")).thenReturn(900L);
        when(counts.getLong("s_TODAY")).thenReturn(725L);
        when(counts.getLong("q_ALL")).thenReturn(725L);
        when(counts.getLong("l_DELIVERY")).thenReturn(725L);
        doAnswer(invocation -> {
            String statement = invocation.getArgument(0);
            sql.add(statement); parameters.add(invocation.getArgument(1));
            RowMapper<?> map = invocation.getArgument(2);
            return List.of(map.mapRow(statement.startsWith("select e.department_id") ? scope : counts, 0));
        }).when(jdbc).query(anyString(), any(SqlParameterSource.class), any(RowMapper.class));
    }
    @Test void exactCountsAndPageUseOneScopedStatementWithBoundedStablePagination() {
        var result = service.list(actor, criteria("%_!"), 7, 100);
        assertThat(result.totalElements()).isEqualTo(725);
        assertThat(result.totalPages()).isEqualTo(8);
        assertThat(result.counts().scopes().get("ALL")).isEqualTo(900);
        assertThat(result.laneCounts().get("DELIVERY")).isEqualTo(725);
        assertThat(result.scope()).isEqualTo("OWN"); assertThat(result.departmentId()).isNull();
        String query = sql.getFirst();
        assertThat(query).contains("scoped as materialized", "selected as materialized", "scope_counts", "quick_counts", "left join lateral", "due_date asc, id asc", "t.assignee_role='EMPLOYEE'", "t.employee_id=:employeeId");
        assertThat(parameters.getFirst().getValue("literal")).isEqualTo("%!%!_!!%");
        assertThat(parameters.getFirst().getValue("offset")).isEqualTo(700L);
    }
    @Test void runtimeOfficeDayUsesExclusiveCalendarEndAcrossSpringDst() {
        service.list(actor, criteria(""), 0, 20);
        var p = parameters.getFirst();
        assertThat(p.getValue("start")).isEqualTo(java.sql.Timestamp.from(Instant.parse("2026-03-08T05:00:00Z")));
        assertThat(p.getValue("end")).isEqualTo(java.sql.Timestamp.from(Instant.parse("2026-03-09T04:00:00Z")));
        assertThat(sql.getFirst()).contains("created_at>=:start and created_at<:end");
    }
    @Test void changedEmployeeLinkDuringReadRejectsNamesAndCounts() {
        when(authority.requireActive(actor)).thenReturn(new CurrentAccountAuthority.Authority("ROLE_EMPLOYEE", employee, Set.of("WORK_TASK_READ")),
                new CurrentAccountAuthority.Authority("ROLE_EMPLOYEE", UUID.randomUUID(), Set.of("WORK_TASK_READ")));
        assertThatThrownBy(() -> service.list(actor, criteria(""), 0, 20)).isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo("WORKBOARD_ACCESS_DENIED");
    }
    @Test void reassignedDepartmentDuringQueryCannotReturnOldScopedCounts() {
        var actorAuthority=new CurrentAccountAuthority.Authority("ROLE_TEAM_LEAD",employee,Set.of("WORK_TASK_READ"));
        doReturn(new CurrentAccountAuthority.WorkScope(actorAuthority,department,UUID.randomUUID(),1,0,0),
                new CurrentAccountAuthority.WorkScope(actorAuthority,UUID.randomUUID(),UUID.randomUUID(),2,1,0)).when(authority).requireWorkScope(actor);
        assertThatThrownBy(()->service.list(actor,criteria(""),0,20)).isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo("WORKBOARD_ACCESS_DENIED");
    }
    @Test void revokedPermissionOrUnassignedRoleCannotReadOrSavePreferences() {
        for (String role : List.of("ROLE_EMPLOYEE", "ROLE_CEO", "ROLE_SYSTEM_ADMIN")) {
            when(authority.requireActive(actor)).thenReturn(new CurrentAccountAuthority.Authority(role, employee, Set.of()));
            assertThatThrownBy(() -> service.preferences(actor)).isInstanceOf(BusinessException.class);
            assertThatThrownBy(() -> service.savePreferences(actor, new PreferencesUpdate(0L, Layout.BOARD, Density.COMPACT, List.of()))).isInstanceOf(BusinessException.class);
        }
        verify(jdbc, never()).update(anyString(), any(SqlParameterSource.class));
    }
    @Test void emptyResultsKeepAuthoritativeZeroCountsAndPagination() throws Exception {
        ResultSet empty = mock(ResultSet.class);
        doAnswer(invocation -> {String statement=invocation.getArgument(0); RowMapper<?> map=invocation.getArgument(2);
            if(statement.startsWith("select e.department_id")) { ResultSet scope=mock(ResultSet.class); when(scope.getObject("department_id",UUID.class)).thenReturn(department); return List.of(map.mapRow(scope,0)); }
            return List.of(map.mapRow(empty,0));
        }).when(jdbc).query(anyString(), any(SqlParameterSource.class), any(RowMapper.class));
        var result = service.list(actor, criteria("missing"), 99, 20);
        assertThat(result.items()).isEmpty(); assertThat(result.totalElements()).isZero(); assertThat(result.totalPages()).isZero();
        assertThat(result.counts().scopes()).containsEntry("TODAY", 0L).containsEntry("ALL", 0L);
        assertThat(result.counts().quickFilters().values()).containsOnly(0L);
    }
    @Test void literalEscapingAndFilterBoundsRejectMalformedCriteriaBeforeReading() {
        assertThat(escapeLiteral("a!_%\\b")).isEqualTo("a!!!_!%\\b");
        assertThatThrownBy(() -> service.list(actor, criteria("x".repeat(121)), 0, 20)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.list(actor, criteria(""), -1, 20)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.list(actor, criteria(""), 0, 101)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> validate(new Criteria(Period.ALL, QuickFilter.ALL, "", "invented", "", Sort.PRIORITY))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> validate(criteria("line\nbreak"))).isInstanceOf(BusinessException.class);
        assertThat(sql).isEmpty();
    }
    @Test void contractNullsSurviveTheApplicationsNonNullJacksonDefault() throws Exception {
        var jsonMapper=new ObjectMapper().findAndRegisterModules().setSerializationInclusion(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL);
        var page=jsonMapper.valueToTree(service.list(actor,criteria(""),0,20));
        assertThat(page.has("departmentId")).isTrue();assertThat(page.get("departmentId").isNull()).isTrue();
        var history=jsonMapper.valueToTree(new History("safe-event","Delivery submitted",Instant.parse("2026-03-08T12:00:00Z"),null,null));
        assertThat(history.has("actorRole")).isTrue();assertThat(history.get("actorRole").isNull()).isTrue();
        assertThat(history.has("note")).isTrue();assertThat(history.get("note").isNull()).isTrue();
    }
    @Test void savedFiltersStayBoundedAndHaveNoScopeIdentityFields() {
        SavedFilter filter = new SavedFilter("mine", "My actions", Period.ALL, QuickFilter.MY_ACTIONS, "literal%", "ALL", "", Sort.PRIORITY);
        validatePreferences(new PreferencesUpdate(0L, Layout.LIST, Density.COMPACT, List.of(filter)));
        assertThatThrownBy(() -> validatePreferences(new PreferencesUpdate(0L, Layout.BOARD, Density.COMPACT, Collections.nCopies(11, filter)))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> validatePreferences(new PreferencesUpdate(0L, Layout.BOARD, Density.COMPACT, List.of(filter,filter)))).isInstanceOf(BusinessException.class);
        assertThat(Arrays.stream(SavedFilter.class.getRecordComponents()).map(java.lang.reflect.RecordComponent::getName)).doesNotContain("employeeId", "departmentId", "actorId");
    }
    @Test void reviewFilterExcludesDeliveryAndAcknowledgementAndPriorityNeverInventsValues() {
        service.list(actor, new Criteria(Period.CARRY_FORWARD,QuickFilter.AWAITING_MY_REVIEW,"","ALL","",Sort.PRIORITY),0,20);
        assertThat(sql.getFirst()).contains("created_at<:start and not is_closed", "array['approve','request-changes','insight-rework','hr-rework','hr-audit','open-oversight']", "where awaiting_my_review", "order by id asc");
        assertThat(quick(QuickFilter.OVERDUE_DELIVERY)).isEqualTo("due_date<:today and is_delivery");
    }
    private Criteria criteria(String query) { return new Criteria(Period.TODAY,QuickFilter.ALL,query,"ALL","",Sort.DUE_DATE); }
}
