package com.brainserve.appointment.support.application;

import com.brainserve.appointment.audit.api.AuditService;
import com.brainserve.appointment.iam.api.CurrentAccountAuthority;
import com.brainserve.appointment.shared.application.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SupportDiagnosticServiceTest {
    private final UUID actor = UUID.randomUUID();
    private final CurrentAccountAuthority authority = mock(CurrentAccountAuthority.class);
    private final AuditService audit = mock(AuditService.class);
    private final CurrentAccountAuthority.Authority admin = new CurrentAccountAuthority.Authority("ROLE_SYSTEM_ADMIN", null, Set.of("SYSTEM_CONFIGURE"));
    private final CurrentAccountAuthority.Authority revoked = new CurrentAccountAuthority.Authority("ROLE_EMPLOYEE", null, Set.of());
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class, invocation -> {
        String method = invocation.getMethod().getName();
        if (method.equals("update")) return 1;
        if (method.equals("query")) {
            String sql = invocation.getArgument(0);
            return sql.contains("flyway_schema_history") ? List.of(66) : List.of();
        }
        if (method.equals("queryForObject")) {
            String sql = invocation.getArgument(0);
            if (sql.contains("select id from iam_user_account")) return actor;
            if (sql.contains("from integration_connection")) return new DiagnosticSnapshot.ConnectionCounts(0, 0, 0);
            if (sql.contains("from integration_delivery")) return new DiagnosticSnapshot.DeliveryCounts(0, 0, 0, 0, 0, 0, 0);
            return 0;
        }
        return org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation);
    });

    @Test void accountRoleChangedDuringAggregationIsRecheckedBeforeReturningPreview() {
        when(authority.requireActive(actor)).thenReturn(admin, revoked);
        assertThatThrownBy(() -> service().preview(actor, 24)).isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo("SUPPORT_SCOPE_DENIED");
        verify(authority, times(2)).requireActive(actor);
    }

    @Test void accountRoleChangedDuringAuditPreventsReturningGenerationReceipt() {
        AtomicReference<CurrentAccountAuthority.Authority> current = new AtomicReference<>(admin);
        when(authority.requireActive(actor)).thenAnswer(invocation -> current.get());
        doAnswer(invocation -> { current.set(revoked); return null; }).when(audit)
                .record(eq("SUPPORT_DIAGNOSTIC_GENERATED"), eq("SUPPORT_DIAGNOSTIC"), anyString(), anyString());
        assertThatThrownBy(() -> service().generate(actor, new SupportDiagnosticService.Generate(UUID.randomUUID(), 24)))
                .isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo("SUPPORT_SCOPE_DENIED");
        verify(authority, times(4)).requireActive(actor);
    }

    private SupportDiagnosticService service() {
        return new SupportDiagnosticService(jdbc, authority, audit, new DiagnosticSnapshotCodec(mapper), "TEST", "1.0.0", "a".repeat(40));
    }
}
