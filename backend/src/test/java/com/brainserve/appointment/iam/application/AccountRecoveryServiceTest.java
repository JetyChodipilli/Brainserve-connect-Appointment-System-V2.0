package com.brainserve.appointment.iam.application;

import com.brainserve.appointment.audit.api.AuditService;
import com.brainserve.appointment.iam.api.EmailService;
import com.brainserve.appointment.iam.domain.AccountRecoveryRequest;
import com.brainserve.appointment.iam.domain.AccountRecoveryStatus;
import com.brainserve.appointment.iam.domain.AccountRecoveryType;
import com.brainserve.appointment.iam.domain.AccountStatus;
import com.brainserve.appointment.iam.domain.SystemRole;
import com.brainserve.appointment.iam.domain.UserAccount;
import com.brainserve.appointment.iam.infrastructure.AccountRecoveryRequestRepository;
import com.brainserve.appointment.iam.infrastructure.RefreshTokenSessionRepository;
import com.brainserve.appointment.iam.infrastructure.UserAccountRepository;
import com.brainserve.appointment.shared.application.BusinessException;
import com.brainserve.appointment.shared.domain.AuditableEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AccountRecoveryServiceTest {
    private final AccountRecoveryRequestRepository requests = mock(AccountRecoveryRequestRepository.class);
    private final UserAccountRepository users = mock(UserAccountRepository.class);
    private final RefreshTokenSessionRepository sessions = mock(RefreshTokenSessionRepository.class);
    private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder(4);
    private final CompanyEmailPolicy emailPolicy = mock(CompanyEmailPolicy.class);
    private final EmailService emailService = mock(EmailService.class);
    private final AuditService audit = mock(AuditService.class);
    private final AccountRecoveryRequestWriter requestWriter = mock(AccountRecoveryRequestWriter.class);
    private AccountRecoveryService service;

    @BeforeEach
    void setUp() {
        service = new AccountRecoveryService(requests, users, sessions, passwords, emailPolicy,
                emailService, audit, requestWriter, 30);
    }

    @Test
    void approvedPasswordCodeChangesHashRevokesSessionsAndCanOnlyBeUsedOnce() {
        UUID adminId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        UserAccount admin = account(adminId, "jetychodipilli@gmail.com", "Jety Chodipilli",
                "Admin@Existing#06", SystemRole.ROLE_SYSTEM_ADMIN);
        UserAccount ceo = account(userId, "ceo@brainserve.in", "BrainServe CEO",
                "CEO@Existing#06", SystemRole.ROLE_CEO);
        AccountRecoveryRequest recovery = recovery(requestId, ceo, AccountRecoveryType.PASSWORD);
        when(users.findById(adminId)).thenReturn(Optional.of(admin));
        when(requests.findDetailedById(requestId)).thenReturn(Optional.of(recovery));

        AccountRecoveryService.Approval approval = service.approve(adminId, requestId);
        when(requests.findByCodeHash(anyString())).thenReturn(Optional.of(recovery));
        service.recoverPassword(approval.recoveryCode(), "CEO@Recovered#2026", "CEO@Recovered#2026");

        assertThat(approval.recoveryCode()).matches("BSR-[A-Z2-9]{4}-[A-Z2-9]{4}-[A-Z2-9]{4}");
        assertThat(recovery.getStatus()).isEqualTo(AccountRecoveryStatus.USED);
        assertThat(passwords.matches("CEO@Recovered#2026", ceo.getPasswordHash())).isTrue();
        assertThat(ceo.isForcePasswordChange()).isFalse();
        verify(sessions).revokeAllForUser(any(UUID.class), any(Instant.class));
        verify(emailService).sendPasswordChangedConfirmation(anyString(), anyString(), any(Instant.class));
        assertThatThrownBy(() -> service.recoverPassword(approval.recoveryCode(),
                "CEO@Another#2026", "CEO@Another#2026"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("invalid, expired or already used");
    }

    @Test
    void approvedEmailCodeAppliesCompanyPolicyAndChangesTheLoginEmail() {
        UUID adminId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        UserAccount admin = account(adminId, "jetychodipilli@gmail.com", "Jety Chodipilli",
                "Admin@Existing#06", SystemRole.ROLE_SYSTEM_ADMIN);
        UserAccount ceo = account(userId, "old.ceo@brainserve.in", "BrainServe CEO",
                "CEO@Existing#06", SystemRole.ROLE_CEO);
        AccountRecoveryRequest recovery = recovery(requestId, ceo, AccountRecoveryType.EMAIL);
        when(users.findById(adminId)).thenReturn(Optional.of(admin));
        when(requests.findDetailedById(requestId)).thenReturn(Optional.of(recovery));
        when(emailPolicy.requireCompanyEmail("new.ceo@brainserve.in")).thenReturn("new.ceo@brainserve.in");
        when(users.existsByEmailIgnoreCaseAndIdNot("new.ceo@brainserve.in", userId)).thenReturn(false);

        AccountRecoveryService.Approval approval = service.approve(adminId, requestId);
        when(requests.findByCodeHash(anyString())).thenReturn(Optional.of(recovery));
        service.recoverEmail(approval.recoveryCode(), "new.ceo@brainserve.in", "new.ceo@brainserve.in");

        assertThat(ceo.getEmail()).isEqualTo("new.ceo@brainserve.in");
        assertThat(recovery.getStatus()).isEqualTo(AccountRecoveryStatus.USED);
        verify(sessions).revokeAllForUser(any(UUID.class), any(Instant.class));
        verify(emailService).sendEmailRecoveryConfirmation(anyString(), anyString(), any(Instant.class));
    }

    @Test
    void publicRequestCreatesOnlyAHashedCodeFreePendingRecord() {
        UUID userId = UUID.randomUUID();
        UserAccount ceo = account(userId, "ceo@brainserve.in", "BrainServe CEO",
                "CEO@Existing#06", SystemRole.ROLE_CEO);
        when(users.findByEmailIgnoreCase("CEO@brainserve.in")).thenReturn(Optional.of(ceo));
        UUID requestId = UUID.randomUUID();
        when(requestWriter.createIfAbsent(userId, AccountRecoveryType.PASSWORD))
                .thenReturn(Optional.of(requestId));

        service.request(" CEO@brainserve.in ", SystemRole.ROLE_CEO, AccountRecoveryType.PASSWORD);

        verify(requestWriter).createIfAbsent(userId, AccountRecoveryType.PASSWORD);
        verify(audit).record("ACCOUNT_RECOVERY_REQUESTED", "ACCOUNT_RECOVERY", requestId.toString(),
                "{\"type\":\"PASSWORD\"}");
    }

    @Test
    void publicRequestAcceptsAnActivePromotedTeamLeadAccount() {
        UUID userId = UUID.randomUUID();
        UserAccount teamLead = new UserAccount("lead@brainserve.in", "Promoted Team Lead", null,
                passwords.encode("Lead@Existing#06"), false, AccountStatus.ACTIVE,
                Set.of(SystemRole.ROLE_TEAM_LEAD), null);
        assignId(teamLead, userId);
        when(users.findByEmailIgnoreCase("lead@brainserve.in")).thenReturn(Optional.of(teamLead));
        when(requestWriter.createIfAbsent(userId, AccountRecoveryType.PASSWORD))
                .thenReturn(Optional.of(UUID.randomUUID()));

        service.request("lead@brainserve.in", SystemRole.ROLE_TEAM_LEAD, AccountRecoveryType.PASSWORD);

        verify(requestWriter).createIfAbsent(userId, AccountRecoveryType.PASSWORD);
    }

    @Test
    void exactEmailStillCreatesRequestWhenSelectedRoleIsStaleAfterRoleChange() {
        UUID userId = UUID.randomUUID();
        UserAccount teamLead = account(userId, "lead@brainserve.in", "Promoted Team Lead",
                "Lead@Existing#06", SystemRole.ROLE_TEAM_LEAD);
        when(users.findByEmailIgnoreCase("lead@brainserve.in")).thenReturn(Optional.of(teamLead));
        when(requestWriter.createIfAbsent(userId, AccountRecoveryType.PASSWORD))
                .thenReturn(Optional.of(UUID.randomUUID()));

        service.request("lead@brainserve.in", SystemRole.ROLE_EMPLOYEE, AccountRecoveryType.PASSWORD);

        verify(requestWriter).createIfAbsent(userId, AccountRecoveryType.PASSWORD);
    }

    @Test
    void exactActiveCeoEmailQueuesAlthufPasswordRecoveryForSystemAdmin() {
        UUID userId = UUID.randomUUID();
        UserAccount ceo = account(userId, "althuf@brainserve.in", "Althuf",
                "CEO@Existing#06", SystemRole.ROLE_CEO);
        when(users.findByEmailIgnoreCase("althuf@brainserve.in")).thenReturn(Optional.of(ceo));
        when(requestWriter.createIfAbsent(userId, AccountRecoveryType.PASSWORD))
                .thenReturn(Optional.of(UUID.randomUUID()));

        service.request(" althuf@brainserve.in ", SystemRole.ROLE_CEO, AccountRecoveryType.PASSWORD);

        verify(requestWriter).createIfAbsent(userId, AccountRecoveryType.PASSWORD);
    }

    @Test
    void auditFailureCannotRollBackAnAlreadyCommittedPublicRecoveryRequest() {
        UUID userId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        UserAccount employee = account(userId, "employee@brainserve.in", "Employee",
                "Employee@Existing#06", SystemRole.ROLE_EMPLOYEE);
        when(users.findByEmailIgnoreCase("employee@brainserve.in")).thenReturn(Optional.of(employee));
        when(requestWriter.createIfAbsent(userId, AccountRecoveryType.PASSWORD))
                .thenReturn(Optional.of(requestId));
        doThrow(new IllegalStateException("audit unavailable")).when(audit)
                .record(anyString(), anyString(), anyString(), anyString());

        service.request("employee@brainserve.in", SystemRole.ROLE_EMPLOYEE, AccountRecoveryType.PASSWORD);

        verify(requestWriter).createIfAbsent(userId, AccountRecoveryType.PASSWORD);
    }

    private UserAccount account(UUID id, String email, String name, String rawPassword, SystemRole role) {
        UserAccount account = new UserAccount(email, name, null, passwords.encode(rawPassword), false,
                AccountStatus.ACTIVE, Set.of(role), null);
        assignId(account, id);
        return account;
    }

    private AccountRecoveryRequest recovery(UUID id, UserAccount user, AccountRecoveryType type) {
        AccountRecoveryRequest request = new AccountRecoveryRequest(user, type);
        assignId(request, id);
        return request;
    }

    private static void assignId(AuditableEntity entity, UUID id) {
        try {
            Field field = AuditableEntity.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(entity, id);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }
}
