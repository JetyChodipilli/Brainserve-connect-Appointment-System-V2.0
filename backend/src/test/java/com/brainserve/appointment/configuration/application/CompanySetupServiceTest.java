package com.brainserve.appointment.configuration.application;

import com.brainserve.appointment.audit.api.AuditService;
import com.brainserve.appointment.iam.api.CurrentAccountAuthority;
import com.brainserve.appointment.shared.application.BusinessException;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CompanySetupServiceTest {
    @Test void requiresCurrentActiveSystemAdminWithConfigurePermission() {
        UUID actor=UUID.randomUUID();var authority=mock(CurrentAccountAuthority.class);var jdbc=mock(JdbcTemplate.class);
        var service=new CompanySetupService(jdbc,authority,mock(AuditService.class),"Asia/Kolkata");
        when(authority.requireActive(actor)).thenReturn(new CurrentAccountAuthority.Authority("ROLE_SYSTEM_ADMIN",null,Set.of()));
        assertThatThrownBy(()->service.state(actor)).isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo("SETUP_SCOPE_DENIED");
        when(authority.requireActive(actor)).thenReturn(new CurrentAccountAuthority.Authority("ROLE_CEO",null,Set.of("SYSTEM_CONFIGURE")));
        assertThatThrownBy(()->service.state(actor)).isInstanceOf(BusinessException.class);verifyNoInteractions(jdbc);
    }
    @Test void rejectsUnknownStepBeforeTouchingProgress() {
        UUID actor=UUID.randomUUID();var authority=mock(CurrentAccountAuthority.class);var jdbc=mock(JdbcTemplate.class);
        when(authority.requireActive(actor)).thenReturn(new CurrentAccountAuthority.Authority("ROLE_SYSTEM_ADMIN",null,Set.of("SYSTEM_CONFIGURE")));
        var service=new CompanySetupService(jdbc,authority,mock(AuditService.class),"Asia/Kolkata");
        assertThatThrownBy(()->service.progress(actor,0,"provision-CEO")).isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo("SETUP_STEP_INVALID");verifyNoInteractions(jdbc);
    }
    @Test void officeRuntimeZoneMustBeAnActualValidZone() {assertThatThrownBy(()->new CompanySetupService(mock(JdbcTemplate.class),null,null,"Fake/Zone")).isInstanceOf(DateTimeException.class);}
    @Test void hashIsStableAndSensitiveToPrerequisiteChanges() {assertThat(CompanySetupService.hash("A")).hasSize(64).isEqualTo(CompanySetupService.hash("A")).isNotEqualTo(CompanySetupService.hash("B"));}
    @Test void nullCompletionTimestampAlwaysSerializes() throws Exception {
        var mapper=new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL);
        assertThat(mapper.writeValueAsString(new CompanySetupService.SetupState("FC03.v1",1,"IN_PROGRESS","company",null,"Asia/Kolkata",List.of()))).contains("\"completedAt\":null");
    }

    @Test void readinessUsesActualPolicyRetentionAndLeadershipRatherThanProgressMarkers() throws Exception {
        var fixture=new Fixture();
        fixture.counts.put("CEO",0L); fixture.counts.put("HR",1L);
        var state=fixture.service.state(fixture.actor);
        assertThat(state.status()).isEqualTo("IN_PROGRESS");
        assertThat(state.steps().stream().filter(x->x.id().equals("roles")).findFirst().orElseThrow().issues()).hasSize(2);
        assertThat(state.steps().stream().filter(x->x.id().equals("review")).findFirst().orElseThrow().complete()).isFalse();
    }
    @Test void officeMismatchBadLeadTimeAndMissingRetentionAreExplicitBlockers() throws Exception {
        var fixture=new Fixture(); fixture.settings.put("COMPANY.OFFICE_ZONE","UTC"); fixture.settings.put("APPOINTMENT.MIN_LEAD_MINUTES","-1"); fixture.counts.put("RETENTION",0L);
        var state=fixture.service.state(fixture.actor);
        assertThat(state.officeZone()).isEqualTo("Asia/Kolkata");
        assertThat(state.steps().stream().filter(x->x.id().equals("policy")).findFirst().orElseThrow().issues()).anyMatch(x->x.contains("minimum booking lead time")).anyMatch(x->x.contains("running deployment"));
        assertThat(state.steps().stream().filter(x->x.id().equals("privacy")).findFirst().orElseThrow().issues()).hasSize(4);
    }
    @Test void validPrerequisitesAreReadyButDoNotAutomaticallyCompleteSetup() throws Exception {
        var fixture=new Fixture();var state=fixture.service.state(fixture.actor);
        assertThat(state.steps()).allMatch(CompanySetupService.SetupStep::complete);assertThat(state.status()).isEqualTo("IN_PROGRESS");assertThat(state.completedAt()).isNull();
    }
    static class Fixture {
        final UUID actor=UUID.randomUUID();final JdbcTemplate jdbc=mock(JdbcTemplate.class);final CurrentAccountAuthority authority=mock(CurrentAccountAuthority.class);
        final Map<String,String> settings=new LinkedHashMap<>();final Map<String,Long> counts=new HashMap<>();final CompanySetupService service;
        Fixture() throws Exception {
            settings.putAll(Map.of("COMPANY.NAME","Company","COMPANY.EMAIL_DOMAIN","company.test","COMPANY.HQ_ADDRESS","Office address","COMPANY.SUPPORT_EMAIL","support@company.test","COMPANY.OFFICE_ZONE","Asia/Kolkata","PRIVACY.CONSENT_VERSION","2026.1"));
            settings.putAll(Map.of("APPOINTMENT.SLOT_MINUTES","30","APPOINTMENT.MAX_ADVANCE_DAYS","90","APPOINTMENT.MIN_LEAD_MINUTES","10","APPOINTMENT.CHECK_IN_EARLY_MINUTES","30","APPOINTMENT.QR_EXPIRY_MINUTES_AFTER_END","120","APPROVAL.INTERVIEW.REQUIRES_HR","true"));
            for(String key:List.of("NOTIFICATION.APPOINTMENT_EMAIL_ENABLED","NOTIFICATION.APPROVAL_EMAIL_ENABLED","NOTIFICATION.SECURITY_ALERT_EMAIL_ENABLED")) settings.put(key,"false");
            when(authority.requireActive(actor)).thenReturn(new CurrentAccountAuthority.Authority("ROLE_SYSTEM_ADMIN",null,Set.of("SYSTEM_CONFIGURE")));
            Map<String,Object> progress=new HashMap<>();progress.put("revision",0L);progress.put("current_step","company");progress.put("readiness_fingerprint","");progress.put("completed_at",null);
            when(jdbc.queryForMap(anyString())).thenReturn(progress);
            doAnswer(invocation->{org.springframework.jdbc.core.RowCallbackHandler handler=invocation.getArgument(1);for(var e:settings.entrySet()) {var rs=mock(java.sql.ResultSet.class);when(rs.getString(1)).thenReturn(e.getKey());when(rs.getString(2)).thenReturn(e.getValue());handler.processRow(rs);}return null;}).when(jdbc).query(anyString(),any(org.springframework.jdbc.core.RowCallbackHandler.class));
            when(jdbc.queryForList(anyString(),eq(String.class))).thenReturn(List.of("readiness-reference"));
            when(jdbc.queryForObject(anyString(),eq(Long.class),any(Object[].class))).thenAnswer(invocation->{String sql=invocation.getArgument(0);String key=sql.contains("data_retention_policy")?"RETENTION":sql.contains("ROLE_CEO")?"CEO":sql.contains("department_hr_assignment")?"HR":sql.contains("department_manager_assignment")?"MANAGER":"DEPARTMENT";return counts.getOrDefault(key,Set.of("HR","MANAGER").contains(key)?0L:1L);});
            service=new CompanySetupService(jdbc,authority,mock(AuditService.class),"Asia/Kolkata");
        }
    }
}
