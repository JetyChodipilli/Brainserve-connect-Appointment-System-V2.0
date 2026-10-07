package com.brainserve.appointment;

import com.brainserve.appointment.appointment.application.AppointmentService;
import com.brainserve.appointment.document.infrastructure.ClamAvScanner;
import com.brainserve.appointment.iam.application.JwtService;
import com.brainserve.appointment.iam.infrastructure.UserAccountRepository;
import com.brainserve.appointment.integration.api.IntegrationModels;
import com.brainserve.appointment.integration.application.CalendarProjection;
import com.brainserve.appointment.integration.application.IntegrationService;
import com.brainserve.appointment.integration.google.GoogleCalendarAdapter;
import com.brainserve.appointment.shared.application.BusinessException;
import com.brainserve.appointment.shared.application.SensitiveStringConverter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionOperations;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.services.s3.S3Client;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Migrated PostgreSQL/Redis, real source/outbox/worker/authorization services; only provider HTTP seam is stubbed. */
@Testcontainers(disabledWithoutDocker = true)
@AutoConfigureMockMvc
@SpringBootTest(properties = {
        "brainserve.security.jwt-secret=test-only-secret-key-that-is-at-least-thirty-two-bytes",
        "brainserve.security.pii-encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "brainserve.bootstrap.system-admin-enabled=false", "brainserve.bootstrap.ceo-enabled=false",
        "brainserve.integrations.enabled=false", "brainserve.work-routines.enabled=false",
        "spring.kafka.listener.auto-startup=false", "brainserve.approval-reminders.poll-ms=3600000",
        "brainserve.notification.internal-call-dispatch-ms=3600000", "brainserve.notification.poll-ms=3600000",
        "aws.s3.access-key=test-access-key", "aws.s3.secret-key=test-secret-key"
})
class Sprint12CalendarPostgresIntegrationTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.2-alpine");
    @Container static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4.1-alpine").withExposedPorts(6379);
    @DynamicPropertySource static void infrastructure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl); registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword); registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }
    @Autowired IntegrationService integrations;
    @Autowired AppointmentService visits;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionOperations transactions;
    @Autowired ObjectMapper json;
    @Autowired SensitiveStringConverter cipher;
    @Autowired MockMvc mvc;
    @Autowired JwtService tokens;
    @Autowired UserAccountRepository users;
    @Autowired @Qualifier("notificationExecutor") Executor notificationExecutor;
    @MockitoBean GoogleCalendarAdapter google;
    @MockitoBean S3Client s3;
    @MockitoBean ClamAvScanner scanner;
    private static final UUID ADMIN = UUID.fromString("b1200000-0000-0000-0000-000000000001");
    private static final UUID OTHER = UUID.fromString("b1200000-0000-0000-0000-000000000002");
    private static final UUID EMPLOYEE = UUID.fromString("b1200000-0000-0000-0000-000000000003");
    private static final UUID HOST = UUID.fromString("b1200000-0000-0000-0000-000000000004");
    private static final UUID DEPT = UUID.fromString("b1200000-0000-0000-0000-000000000005");
    private static final Instant SLOT = Instant.parse("2026-10-08T09:00:00Z");
    private static final String PRIVATE = "Private Person visitor@s12.test Private purpose otp-123456";

    @BeforeEach void fixture() {
        drain();
        reset(google);
        jdbc.execute("truncate integration_connection,integration_resource_revision,integration_business_event,appointment,iam_user_account,employee,org_department,audit_event cascade");
        jdbc.update("insert into org_department(id,code,name,active,version,created_at,created_by,updated_at,updated_by) values(?,'S12','Calendar',true,0,now(),'s12',now(),'s12')", DEPT);
        jdbc.update("insert into employee(id,employee_number,first_name,last_name,display_name,official_email,department_id,designation,joining_date,status,version,created_at,created_by,updated_at,updated_by) values(?,'S12-HOST','HR','Person','HR Person','hr@s12.test',?,'HR','2026-01-01','ACTIVE',0,now(),'s12',now(),'s12')", HOST, DEPT);
        account(ADMIN, "ROLE_SYSTEM_ADMIN"); account(OTHER, "ROLE_SYSTEM_ADMIN"); account(EMPLOYEE, "ROLE_EMPLOYEE");
        when(google.deliver(any(UUID.class), anyLong(), anyString(), anyLong(), anyString(), any(Instant.class), any(Instant.class)))
                .thenAnswer(call -> new GoogleCalendarAdapter.Result("SUCCESS", 0, call.getArgument(2)));
    }

    @AfterEach void settle() { drain(); }

    @Test void onlyConsentedGoogleConnectionsAcceptCalendarDeliveryAndManualCredentialRoutesReject() {
        assertThat(IntegrationModels.Provider.GOOGLE_CALENDAR.scopes()).containsExactly("https://www.googleapis.com/auth/calendar.app.created");
        code(() -> integrations.create(ADMIN, new IntegrationModels.Create(UUID.randomUUID(), IntegrationModels.Provider.GOOGLE_CALENDAR, "Google", "manual-secret-credential", Instant.now().plusSeconds(3600))), "GOOGLE_CALENDAR_CONSENT_REQUIRED");
        var c = googleConnection();
        code(() -> integrations.reconnect(ADMIN, c.id(), new IntegrationModels.Reconnect(c.version(), "manual-secret-credential", Instant.now().plusSeconds(3600))), "GOOGLE_CALENDAR_CONSENT_REQUIRED");
        code(() -> integrations.test(ADMIN, c.id(), new IntegrationModels.Test(UUID.randomUUID(), c.version(), IntegrationModels.Scenario.SUCCESS)), "GOOGLE_CALENDAR_CONSENT_REQUIRED");
        assertThat(count("select count(*) from integration_delivery")).isZero();
        verifyNoInteractions(google);
    }

    @Test void committedBusinessCaptureNeverCallsProviderRollbackRemovesEventAndOutbox() {
        googleConnection(); UUID resource = UUID.randomUUID();
        transactions.executeWithoutResult(tx -> { integrations.capture(resource, "APPOINTMENT_CREATED", Instant.now(), snapshot("APPROVED", SLOT)); tx.setRollbackOnly(); });
        assertThat(count("select count(*) from integration_business_event")).isZero();
        assertThat(count("select count(*) from integration_delivery")).isZero();
        capture(resource, "APPROVED", SLOT);
        verifyNoInteractions(google);
        assertThat(count("select count(*) from integration_business_event")).isEqualTo(1);
        assertThat(jdbc.queryForObject("select payload_json::text from integration_delivery", String.class)).doesNotContain("visitor", "purpose", "otp", "email", "credential");
    }

    @Test void duplicateCompletionPersistsOneOpaqueMappingAndCallsOnlyGenericSlotAdapter() {
        var c = googleConnection(); UUID resource = UUID.randomUUID(); capture(resource, "APPROVED", SLOT);
        UUID id = latestDelivery(c.id(), resource);
        var claim = integrations.claim(id, Instant.now().plusSeconds(1)).orElseThrow();
        integrations.complete(claim, Instant.now().plusSeconds(2)); integrations.complete(claim, Instant.now().plusSeconds(3));
        verify(google, times(1)).deliver(c.id(), 1, CalendarProjection.eventId(c.id(), resource), 1, "UPSERT", SLOT, SLOT.plusSeconds(1800));
        assertThat(deliveryStatus(id)).isEqualTo("DELIVERED");
        assertThat(count("select count(*) from integration_external_mapping")).isEqualTo(1);
        assertThat(integrations.attempts(ADMIN, id)).extracting(IntegrationModels.Attempt::outcome).containsExactly("SUCCESS");
        assertThat(count("select count(*) from integration_simulator_receipt")).isZero();
        assertThat(jdbc.queryForObject("select external_id from integration_external_mapping", String.class)).matches("[0-9a-v]{66}");
    }

    @ParameterizedTest @ValueSource(strings={"RESCHEDULE_REQUESTED", "RESCHEDULED", "PENDING_APPROVAL", "DRAFT", "COMPLETED", "CHECKED_OUT"})
    void unapprovedChangesAndCompletedHistoryKeepLastApprovedExternalTime(String state) {
        var c = googleConnection(); UUID resource = UUID.randomUUID(); capture(resource, "APPROVED", SLOT); complete(latestDelivery(c.id(), resource));
        clearInvocations(google); capture(resource, state, SLOT.plusSeconds(7200)); complete(latestDelivery(c.id(), resource));
        verifyNoInteractions(google);
        assertThat(jdbc.queryForObject("select business_revision from integration_external_mapping", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select last_result_code from integration_delivery where id=?", String.class, latestDelivery(c.id(), resource))).isEqualTo("SKIPPED_STATE");
    }

    @ParameterizedTest @ValueSource(strings={"APPROVED", "CHECKED_IN", "IN_MEETING"})
    void approvedStateUpdatesAreProjectedAtCurrentRevision(String state) {
        var c = googleConnection(); UUID resource = UUID.randomUUID(); capture(resource, "APPROVED", SLOT); complete(latestDelivery(c.id(), resource));
        capture(resource, state, SLOT.plusSeconds(7200)); complete(latestDelivery(c.id(), resource));
        verify(google).deliver(c.id(), 1, CalendarProjection.eventId(c.id(), resource), 2, "UPSERT", SLOT.plusSeconds(7200), SLOT.plusSeconds(9000));
        assertThat(jdbc.queryForObject("select business_revision from integration_external_mapping", Long.class)).isEqualTo(2);
    }

    @ParameterizedTest @ValueSource(strings={"CANCELLED", "REJECTED", "NO_SHOW", "EXPIRED"})
    void terminalStatesDeleteTheManagedEvent(String state) {
        var c = googleConnection(); UUID resource = UUID.randomUUID(); capture(resource, "APPROVED", SLOT); complete(latestDelivery(c.id(), resource));
        capture(resource, state, SLOT); complete(latestDelivery(c.id(), resource));
        verify(google).deliver(c.id(), 1, CalendarProjection.eventId(c.id(), resource), 2, "DELETE", SLOT, SLOT.plusSeconds(1800));
        assertThat(jdbc.queryForObject("select business_revision from integration_external_mapping", Long.class)).isEqualTo(2);
    }

    @Test void reorderingFencesAnOldClaimEvenWhenNewerSourceWasCapturedWhileDisconnected() {
        var c = googleConnection(); UUID resource = UUID.randomUUID(); capture(resource, "APPROVED", SLOT);
        UUID old = latestDelivery(c.id(), resource); var claim = integrations.claim(old, Instant.now().plusSeconds(1)).orElseThrow();
        jdbc.update("update integration_connection set status='NEEDS_RECONNECT' where id=?", c.id());
        capture(resource, "CANCELLED", SLOT);
        jdbc.update("update integration_connection set status='ACTIVE' where id=?", c.id());
        integrations.complete(claim, Instant.now().plusSeconds(2));
        assertThat(deliveryStatus(old)).isEqualTo("SUPERSEDED"); verifyNoInteractions(google);
        var job = reconcile(c.id()); integrations.reconcileBatch(job.id());
        UUID repair = repairDelivery(job.id()); complete(repair);
        verify(google).deliver(c.id(), 1, CalendarProjection.eventId(c.id(), resource), 2, "DELETE", SLOT, SLOT.plusSeconds(1800));
    }

    @Test void unknownNetworkOutcomeRetriesSameDeterministicIdAndRateLimitHonorsRetryAfter() {
        var c = googleConnection(); UUID resource = UUID.randomUUID(); capture(resource, "APPROVED", SLOT); UUID id = latestDelivery(c.id(), resource);
        when(google.deliver(any(), anyLong(), anyString(), anyLong(), anyString(), any(), any()))
                .thenThrow(new IllegalStateException(PRIVATE)).thenReturn(new GoogleCalendarAdapter.Result("RATE_LIMITED", 900, null))
                .thenReturn(new GoogleCalendarAdapter.Result("SUCCESS", 0, CalendarProjection.eventId(c.id(), resource)));
        complete(id); assertThat(deliveryStatus(id)).isEqualTo("PENDING"); assertThat(count("select count(*) from integration_external_mapping")).isZero();
        complete(id); assertThat(jdbc.queryForObject("select next_attempt_at>now()+interval '899 seconds' from integration_delivery where id=?", Boolean.class, id)).isTrue();
        complete(id); verify(google, times(3)).deliver(c.id(), 1, CalendarProjection.eventId(c.id(), resource), 1, "UPSERT", SLOT, SLOT.plusSeconds(1800));
        assertThat(integrations.attempts(ADMIN, id)).extracting(IntegrationModels.Attempt::outcome).containsExactly("OUTAGE", "RATE_LIMITED", "SUCCESS");
    }

    @Test void dbFailureAfterProviderSuccessRetainsLeaseAndCanRecoverUnknownRemoteOutcome() {
        var c = googleConnection(); UUID resource = UUID.randomUUID(); capture(resource, "APPROVED", SLOT);
        UUID id = latestDelivery(c.id(), resource); Instant now = Instant.now().plusSeconds(1); var claim = integrations.claim(id, now).orElseThrow();
        jdbc.execute("create function fail_s12_mapping() returns trigger language plpgsql as $$ begin raise exception 'mapping failed after provider'; end $$");
        jdbc.execute("create trigger fail_s12_mapping before insert on integration_external_mapping for each row execute function fail_s12_mapping()");
        try { assertThatThrownBy(() -> integrations.complete(claim, now.plusSeconds(1))).hasMessageContaining("mapping failed after provider"); }
        finally { jdbc.execute("drop trigger fail_s12_mapping on integration_external_mapping"); jdbc.execute("drop function fail_s12_mapping()"); }
        assertThat(deliveryStatus(id)).isEqualTo("RUNNING"); assertThat(count("select count(*) from integration_delivery_attempt")).isZero();
        // Persisted lease recovery, not an in-memory provider receipt, makes the unknown outcome retryable.
        jdbc.update("update integration_delivery set lease_until=now()-interval '1 second' where id=?", id);
        complete(id); assertThat(deliveryStatus(id)).isEqualTo("DELIVERED");
        verify(google, times(2)).deliver(c.id(), 1, CalendarProjection.eventId(c.id(), resource), 1, "UPSERT", SLOT, SLOT.plusSeconds(1800));
        assertThat(integrations.attempts(ADMIN, id)).extracting(IntegrationModels.Attempt::outcome).containsExactly("LEASE_EXPIRED", "SUCCESS");
    }

    @Test void expiredOrReplacedLeaseCannotCallProviderAndOwnerRemovalStopsWork() {
        var c = googleConnection(); UUID resource = UUID.randomUUID(); capture(resource, "APPROVED", SLOT); UUID id = latestDelivery(c.id(), resource);
        var abandoned = integrations.claim(id, Instant.now().plusSeconds(1)).orElseThrow();
        jdbc.update("update integration_delivery set lease_until=now()-interval '1 second' where id=?", id);
        var recovered = integrations.claim(id, Instant.now().plusSeconds(2)).orElseThrow();
        integrations.complete(abandoned, Instant.now().plusSeconds(3)); verifyNoInteractions(google);
        jdbc.update("update iam_user_account set enabled=false where id=?", ADMIN);
        integrations.complete(recovered, Instant.now().plusSeconds(4)); assertThat(deliveryStatus(id)).isEqualTo("CANCELLED"); verifyNoInteractions(google);
    }

    @Test void revocationSerializesWithBoundedProviderWorkAndStopsAlreadyClaimedFollowup() throws Exception {
        var c = googleConnection(); UUID resource = UUID.randomUUID(); capture(resource, "APPROVED", SLOT); UUID id = latestDelivery(c.id(), resource);
        var claim = integrations.claim(id, Instant.now().plusSeconds(1)).orElseThrow();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        when(google.deliver(any(), anyLong(), anyString(), anyLong(), anyString(), any(), any())).thenAnswer(call -> {
            entered.countDown(); if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test provider bound exceeded");
            return new GoogleCalendarAdapter.Result("SUCCESS", 0, call.getArgument(2));
        });
        try (var pool = Executors.newFixedThreadPool(2)) {
            var network = pool.submit(() -> integrations.complete(claim, Instant.now().plusSeconds(2)));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            // Observe version inside the revoke transaction after acquiring account/connection locks.
            var revoked = pool.submit(() -> { var version = jdbc.queryForObject("select version from integration_connection where id=?", Long.class, c.id());
                try { return integrations.revoke(ADMIN, c.id(), version); }
                catch (BusinessException changed) { return integrations.revoke(ADMIN, c.id(), integrations.connections(ADMIN).getFirst().version()); } });
            assertThatThrownBy(() -> revoked.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            release.countDown(); network.get(10, TimeUnit.SECONDS); assertThat(revoked.get(10, TimeUnit.SECONDS).status()).isEqualTo("REVOKED");
        } finally { release.countDown(); }
        verify(google).disconnect(c.id(), 1); assertThat(jdbc.queryForObject("select credential_ciphertext from integration_connection where id=?", String.class, c.id())).isNull();
        integrations.complete(claim, Instant.now().plusSeconds(3)); verify(google, times(1)).deliver(any(), anyLong(), anyString(), anyLong(), anyString(), any(), any());
    }

    @ParameterizedTest @ValueSource(strings={"REAUTH_REQUIRED", "PERMANENT_FAILURE", "NEWER_REVISION"})
    void providerRefusalCannotCreateASuccessMappingAndReauthStopsConnection(String result) {
        var c = googleConnection(); UUID resource = UUID.randomUUID(); capture(resource, "APPROVED", SLOT); UUID id = latestDelivery(c.id(), resource);
        when(google.deliver(any(), anyLong(), anyString(), anyLong(), anyString(), any(), any())).thenReturn(new GoogleCalendarAdapter.Result(result, 0, null));
        complete(id);
        assertThat(count("select count(*) from integration_external_mapping")).isZero();
        assertThat(deliveryStatus(id)).isEqualTo(result.equals("REAUTH_REQUIRED") ? "NEEDS_RECONNECT" : result.equals("NEWER_REVISION") ? "SUPERSEDED" : "FAILED");
        assertThat(integrations.attempts(ADMIN, id)).extracting(IntegrationModels.Attempt::outcome).containsExactly(result);
        if (result.equals("REAUTH_REQUIRED")) assertThat(integrations.connections(ADMIN).getFirst().status()).isEqualTo("NEEDS_RECONNECT");
    }

    @Test void credentialGenerationAndRevokeFenceAnAlreadyClaimedDeliveryBeforeAnyHttp() {
        var c = googleConnection(); UUID resource = UUID.randomUUID(); capture(resource, "APPROVED", SLOT); UUID id = latestDelivery(c.id(), resource);
        var claim = integrations.claim(id, Instant.now().plusSeconds(1)).orElseThrow();
        jdbc.update("update integration_connection set credential_version=credential_version+1,version=version+1 where id=?", c.id());
        integrations.complete(claim, Instant.now().plusSeconds(2)); assertThat(deliveryStatus(id)).isEqualTo("NEEDS_RECONNECT"); verifyNoInteractions(google);
        capture(resource, "APPROVED", SLOT.plusSeconds(3600)); UUID next = latestDelivery(c.id(), resource);
        var nextClaim = integrations.claim(next, Instant.now().plusSeconds(1)).orElseThrow();
        integrations.revoke(ADMIN, c.id(), integrations.connections(ADMIN).getFirst().version());
        integrations.complete(nextClaim, Instant.now().plusSeconds(2)); assertThat(deliveryStatus(next)).isEqualTo("CANCELLED");
        verify(google).disconnect(c.id(), 2);
        verify(google, times(0)).deliver(any(), anyLong(), anyString(), anyLong(), anyString(), any(), any());
    }

    @Test void simultaneousGoogleClaimersHaveOneLeaseAndOneProviderCall() throws Exception {
        var c = googleConnection(); UUID resource = UUID.randomUUID(); capture(resource, "APPROVED", SLOT); UUID id = latestDelivery(c.id(), resource);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            var a = pool.submit(() -> { start.await(); return integrations.claim(id, Instant.now().plusSeconds(1)); });
            var b = pool.submit(() -> { start.await(); return integrations.claim(id, Instant.now().plusSeconds(1)); }); start.countDown();
            var claims = List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS));
            assertThat(claims.stream().filter(java.util.Optional::isPresent).count()).isEqualTo(1);
            claims.forEach(value -> value.ifPresent(claim -> integrations.complete(claim, Instant.now().plusSeconds(2))));
        }
        verify(google, times(1)).deliver(any(), anyLong(), anyString(), anyLong(), anyString(), any(), any());
        assertThat(integrations.attempts(ADMIN, id)).hasSize(1);
    }

    @Test void reconciliationBackfillsCommittedSourceAndRereadsLatestStateInsteadOfQueuedSnapshot() {
        UUID resource = UUID.randomUUID(); capture(resource, "APPROVED", SLOT); var c = googleConnection();
        var job = reconcile(c.id()); integrations.reconcileBatch(job.id()); integrations.reconcileBatch(job.id());
        UUID repair = repairDelivery(job.id()); assertThat(count("select count(*) from integration_delivery where reconciliation_id=?", job.id())).isEqualTo(1);
        capture(resource, "CANCELLED", SLOT.plusSeconds(7200)); complete(repair);
        verify(google).deliver(c.id(), 1, CalendarProjection.eventId(c.id(), resource), 2, "DELETE", SLOT.plusSeconds(7200), SLOT.plusSeconds(9000));
        integrations.reconcileBatch(job.id()); assertThat(integrations.latestReconciliation(ADMIN, c.id()).status()).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("select business_revision from integration_external_mapping", Long.class)).isEqualTo(2);
    }

    @Test void sameRevisionRepairCanRestoreMissingOrDriftedEventAndJobReceiptsBoundFloods() {
        var c = googleConnection(); UUID resource = UUID.randomUUID(); capture(resource, "APPROVED", SLOT); complete(latestDelivery(c.id(), resource));
        c = integrations.connections(ADMIN).getFirst();
        var command = new IntegrationModels.Reconcile(UUID.randomUUID(), c.version());
        var job = integrations.reconcile(ADMIN, c.id(), command);
        assertThat(integrations.reconcile(ADMIN, c.id(), command).id()).isEqualTo(job.id());
        UUID connectionId = c.id();
        code(() -> integrations.reconcile(ADMIN, connectionId, new IntegrationModels.Reconcile(command.requestId(), command.expectedVersion()+1)), "INTEGRATION_VERSION_CONFLICT");
        code(() -> reconcile(connectionId), "CALENDAR_RECONCILE_LIMIT");
        integrations.reconcileBatch(job.id()); complete(repairDelivery(job.id())); integrations.reconcileBatch(job.id());
        verify(google, times(2)).deliver(c.id(), 1, CalendarProjection.eventId(c.id(), resource), 1, "UPSERT", SLOT, SLOT.plusSeconds(1800));
        assertThat(integrations.latestReconciliation(ADMIN, c.id()).processed()).isEqualTo(1);
        assertThat(count("select count(*) from integration_external_mapping")).isEqualTo(1);
    }

    @Test void repairBatchesStayAtTwentyFiveAndRevokeCancelsDurableGeneration() {
        for (int i=0; i<26; i++) capture(UUID.randomUUID(), "APPROVED", SLOT);
        var c = googleConnection(); var job = reconcile(c.id()); integrations.reconcileBatch(job.id());
        assertThat(integrations.latestReconciliation(ADMIN, c.id()).processed()).isEqualTo(25);
        integrations.reconcileBatch(job.id()); assertThat(integrations.latestReconciliation(ADMIN, c.id()).processed()).isEqualTo(26);
        var current = integrations.connections(ADMIN).getFirst(); integrations.revoke(ADMIN, c.id(), current.version());
        integrations.reconcileBatch(job.id()); assertThat(integrations.latestReconciliation(ADMIN, c.id()).status()).isEqualTo("CANCELLED");
        assertThat(count("select count(*) from integration_delivery where status='CANCELLED'")).isEqualTo(26);
        verify(google).disconnect(c.id(), 1);
    }

    @Test void repairOverflowRejectsBeforeAnyJobIsCreated() {
        transactions.executeWithoutResult(tx -> { for (int i=0; i<501; i++) integrations.capture(UUID.randomUUID(), "APPOINTMENT_UPDATED", Instant.now(), snapshot("APPROVED", SLOT)); });
        var c = googleConnection(); code(() -> reconcile(c.id()), "CALENDAR_RECONCILE_LIMIT");
        assertThat(count("select count(*) from integration_calendar_reconciliation")).isZero(); verifyNoInteractions(google);
    }

    @Test void icsIsApprovedFutureGenericUtcAuditedAndOverflowFailsExplicitly() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        UUID included = appointment("APPROVED", now.plusSeconds(3600));
        appointment("PENDING_APPROVAL", now.plusSeconds(7200)); appointment("RESCHEDULED", now.plusSeconds(10800));
        appointment("APPROVED", now.minusSeconds(7200)); appointment("APPROVED", now.plus(31, ChronoUnit.DAYS));
        String file = new String(integrations.calendarFile(ADMIN, now), StandardCharsets.UTF_8);
        assertThat(file).contains(included.toString(), "SUMMARY:BrainServe appointment", "BEGIN:VCALENDAR\r\n", "DTSTART:").doesNotContain(PRIVATE, "visitor@s12.test", "ATTENDEE", "purpose");
        assertThat(file.split("BEGIN:VEVENT", -1)).hasSize(2);
        assertThat(count("select count(*) from audit_event where event_type='CALENDAR_FILE_EXPORTED'")).isEqualTo(1);
        jdbc.execute("truncate appointment cascade");
        for (int i=0; i<501; i++) appointment("APPROVED", now.plusSeconds(60L*(i+1)));
        code(() -> integrations.calendarFile(ADMIN, now), "CALENDAR_EXPORT_LIMIT");
        assertThat(count("select count(*) from audit_event where event_type='CALENDAR_FILE_EXPORTED'")).isEqualTo(1);
    }

    @Test void currentOwnershipRolePermissionAndMfaProtectRepairAndExportHttp() throws Exception {
        var c = googleConnection(); UUID resource = UUID.randomUUID(); capture(resource, "APPROVED", SLOT);
        code(() -> integrations.latestReconciliation(OTHER, c.id()), "INTEGRATION_NOT_FOUND");
        code(() -> integrations.reconcile(OTHER, c.id(), new IntegrationModels.Reconcile(UUID.randomUUID(), c.version())), "INTEGRATION_NOT_FOUND");
        code(() -> integrations.calendarFile(EMPLOYEE, Instant.now()), "INTEGRATION_ADMIN_REQUIRED");
        mvc.perform(get("/api/v1/integrations/google-calendar/calendar.ics").header("Authorization", bearer(ADMIN, Instant.now().minusSeconds(3600))))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.errorCode").value("MFA_STEP_UP_REQUIRED"));
        String token = bearer(ADMIN, Instant.now());
        mvc.perform(get("/api/v1/integrations/google-calendar/calendar.ics").header("Authorization", token))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"brainserve-calendar.ics\""));
        mvc.perform(post("/api/v1/integrations/connections/"+c.id()+"/reconcile").header("Authorization", bearer(ADMIN, Instant.now().minusSeconds(3600)))
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(new IntegrationModels.Reconcile(UUID.randomUUID(), c.version()))))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.errorCode").value("MFA_STEP_UP_REQUIRED"));
        mvc.perform(post("/api/v1/integrations/connections/"+c.id()+"/reconcile").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(new IntegrationModels.Reconcile(UUID.randomUUID(), c.version()))))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store")).andExpect(jsonPath("$.status").value("QUEUED"));
        jdbc.update("insert into iam_user_permission_deny(user_id,permission_name) values(?,'SYSTEM_CONFIGURE')", ADMIN);
        code(() -> integrations.latestReconciliation(ADMIN, c.id()), "INTEGRATION_ADMIN_REQUIRED");
        code(() -> integrations.calendarFile(ADMIN, Instant.now()), "INTEGRATION_ADMIN_REQUIRED");
    }

    @Test void actualAppointmentCancellationCapturesCommittedSourceAndProviderFailureCannotUndoBusinessState() {
        var c = googleConnection(); UUID resource = appointment("APPROVED", Instant.now().minus(2, ChronoUnit.DAYS));
        assertThat(visits.cancelPastUnfinishedVisits()).isEqualTo(1);
        UUID id = latestDelivery(c.id(), resource);
        when(google.deliver(any(), anyLong(), anyString(), anyLong(), anyString(), any(), any())).thenReturn(new GoogleCalendarAdapter.Result("OUTAGE", 0, null));
        complete(id);
        assertThat(jdbc.queryForObject("select status from appointment where id=?", String.class, resource)).isEqualTo("CANCELLED");
        assertThat(deliveryStatus(id)).isEqualTo("PENDING");
        verify(google).deliver(eq(c.id()), eq(1L), eq(CalendarProjection.eventId(c.id(), resource)), eq(1L), eq("DELETE"), any(), any());
    }

    private IntegrationModels.Connection googleConnection() {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into integration_connection(id,request_id,provider,kind,label,owner_id,minimum_scopes,status,credential_ciphertext,credential_expires_at) values(?,?,'GOOGLE_CALENDAR','CALENDAR','Google',?,?::jsonb,'ACTIVE',?,now()+interval '1 day')",
                id, UUID.randomUUID(), ADMIN, "[\"https://www.googleapis.com/auth/calendar.app.created\"]", cipher.convertToDatabaseColumn("encrypted-test-refresh-credential"));
        return integrations.connections(ADMIN).stream().filter(c -> c.id().equals(id)).findFirst().orElseThrow();
    }
    private void capture(UUID resource, String state, Instant slot) { transactions.executeWithoutResult(tx -> integrations.capture(resource, "APPOINTMENT_UPDATED", Instant.now(), snapshot(state, slot))); }
    private Map<String,Object> snapshot(String state, Instant start) { return Map.of("status", state, "appointmentType", "HR_VISIT", "slotStart", start.toString(), "slotEnd", start.plusSeconds(1800).toString()); }
    private UUID latestDelivery(UUID connection, UUID resource) { return jdbc.queryForObject("select id from integration_delivery where connection_id=? and resource_id=? and event_type<>'CALENDAR_RECONCILE' order by business_revision desc limit 1", UUID.class, connection, resource); }
    private UUID repairDelivery(UUID job) { return jdbc.queryForObject("select id from integration_delivery where reconciliation_id=? limit 1", UUID.class, job); }
    private void complete(UUID id) { jdbc.update("update integration_delivery set next_attempt_at=now()-interval '1 second' where id=?", id); Instant now = Instant.now().plusSeconds(1); integrations.complete(integrations.claim(id, now).orElseThrow(), now.plusSeconds(1)); }
    private String deliveryStatus(UUID id) { return jdbc.queryForObject("select status from integration_delivery where id=?", String.class, id); }
    private long count(String sql, Object... args) { return jdbc.queryForObject(sql, Long.class, args); }
    private IntegrationModels.Reconciliation reconcile(UUID connection) { var c = integrations.connections(ADMIN).stream().filter(row -> row.id().equals(connection)).findFirst().orElseThrow(); return integrations.reconcile(ADMIN, connection, new IntegrationModels.Reconcile(UUID.randomUUID(), c.version())); }
    private void code(Runnable action, String code) { assertThatThrownBy(action::run).isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo(code); }
    private void account(UUID id, String role) { jdbc.update("insert into iam_user_account(id,email,full_name,password_hash,enabled,force_password_change,account_status,archived,version,created_at,created_by,updated_at,updated_by) values(?,?,?,'test-only-hash',true,false,'ACTIVE',false,0,now(),'s12',now(),'s12')", id, id+"@s12.test", PRIVATE); jdbc.update("insert into iam_user_role(user_id,role_name) values(?,?)", id, role); }
    private UUID appointment(String state, Instant start) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into appointment(id,reference_number,idempotency_key,type,status,visitor_name,visitor_email,visitor_phone,host_employee_id,routing_department_id,slot_start,slot_end,purpose,version,created_at,created_by,updated_at,updated_by) values(?,?,?,'HR_VISIT',?,?, 'visitor@s12.test','0000000000',?,?,?,?,?,0,now(),'s12',now(),'s12')",
                id, "S12-"+id.toString().substring(0,30), id.toString(), state, PRIVATE, HOST, DEPT, Timestamp.from(start), Timestamp.from(start.plusSeconds(30)), PRIVATE);
        return id;
    }
    private void drain() {
        if (!(notificationExecutor instanceof ThreadPoolTaskExecutor executor)) return;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (executor.getActiveCount() > 0 || !executor.getThreadPoolExecutor().getQueue().isEmpty()) {
            if (System.nanoTime() > deadline) throw new AssertionError("Notification writes did not settle");
            try { Thread.sleep(20); } catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new AssertionError(exception); }
        }
    }
    private String bearer(UUID actor, Instant proof) {
        UUID family = UUID.randomUUID();
        jdbc.update("insert into iam_mfa_credential(user_id,secret_ciphertext,enrolled_at,last_accepted_step) values(?,?,now(),1) on conflict(user_id) do nothing", actor, cipher.convertToDatabaseColumn("JBSWY3DPEHPK3PXP"));
        jdbc.update("insert into iam_refresh_token_session(id,user_id,token_hash,family_id,expires_at,mfa_verified_at,session_started_at,version,created_at,created_by,updated_at,updated_by) values(?,?,?,?,now()+interval '1 day',?,now(),0,now(),'s12',now(),'s12')", UUID.randomUUID(), actor, UUID.randomUUID().toString().replace("-", ""), family, Timestamp.from(proof));
        return "Bearer " + tokens.issue(users.findById(actor).orElseThrow(), family, proof, true, true).value();
    }
}
