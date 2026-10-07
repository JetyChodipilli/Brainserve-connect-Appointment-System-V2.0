package com.brainserve.appointment;

import com.brainserve.appointment.appointment.application.AppointmentService;
import com.brainserve.appointment.appointment.domain.AppointmentType;
import com.brainserve.appointment.document.infrastructure.ClamAvScanner;
import com.brainserve.appointment.integration.api.IntegrationModels;
import com.brainserve.appointment.integration.application.IntegrationService;
import com.brainserve.appointment.reception.application.ReceptionService;
import com.brainserve.appointment.shared.application.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionOperations;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.services.s3.S3Client;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Required release suite: real migrated PostgreSQL/Redis and production integration/business services. */
@Testcontainers(disabledWithoutDocker=true)
@SpringBootTest(properties={
        "brainserve.security.jwt-secret=test-only-secret-key-that-is-at-least-thirty-two-bytes",
        "brainserve.security.pii-encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "brainserve.bootstrap.system-admin-enabled=false","brainserve.bootstrap.ceo-enabled=false",
        "brainserve.appointment.office-zone=Asia/Kolkata","brainserve.work-routines.enabled=false",
        "brainserve.integrations.enabled=false","brainserve.approval-reminders.poll-ms=3600000",
        "spring.kafka.listener.auto-startup=false","brainserve.notification.internal-call-dispatch-ms=3600000",
        "brainserve.notification.poll-ms=3600000","aws.s3.access-key=test-access-key","aws.s3.secret-key=test-secret-key"
})
class Sprint11IntegrationPostgresIntegrationTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.2-alpine");
    @Container static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4.1-alpine").withExposedPorts(6379);
    @DynamicPropertySource static void infrastructure(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url",POSTGRES::getJdbcUrl); properties.add("spring.datasource.username",POSTGRES::getUsername);
        properties.add("spring.datasource.password",POSTGRES::getPassword); properties.add("spring.data.redis.host",REDIS::getHost);
        properties.add("spring.data.redis.port",()->REDIS.getMappedPort(6379));
    }
    @Autowired IntegrationService integrations;
    @Autowired AppointmentService visits;
    @Autowired ReceptionService reception;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionOperations transactions;
    @Autowired ObjectMapper json;
    @Autowired @Qualifier("notificationExecutor") Executor notificationExecutor;
    @MockitoBean S3Client s3;
    @MockitoBean ClamAvScanner scanner;
    private static final UUID ADMIN = UUID.fromString("b1100000-0000-0000-0000-000000000001");
    private static final UUID OTHER = UUID.fromString("b1100000-0000-0000-0000-000000000002");
    private static final UUID HR = UUID.fromString("b1100000-0000-0000-0000-000000000003");
    private static final UUID HOST = UUID.fromString("b1100000-0000-0000-0000-000000000004");
    private static final UUID DEPT = UUID.fromString("b1100000-0000-0000-0000-000000000005");
    private static final String SECRET = "s11-private-provider-credential";
    @BeforeEach void fixture() {
        drain();
        jdbc.execute("truncate integration_connection,integration_resource_revision,integration_business_event cascade");
        jdbc.execute("truncate stored_document,appointment,work_task_audit_record,department_work_task,internal_call_notification,notification_outbox cascade");
        jdbc.execute("truncate department_hr_assignment,department_team_lead,department_manager_assignment,iam_user_account,employee,org_department cascade");
        account(ADMIN,null,"admin","ROLE_SYSTEM_ADMIN"); account(OTHER,null,"other","ROLE_SYSTEM_ADMIN");
        jdbc.update("insert into org_department(id,code,name,active,version,created_at,created_by,updated_at,updated_by) values(?,'S11','Integration',true,0,now(),'s11',now(),'s11')",DEPT);
        jdbc.update("insert into employee(id,employee_number,first_name,last_name,display_name,official_email,department_id,designation,joining_date,status,version,created_at,created_by,updated_at,updated_by) values(?,'S11-HOST','HR','Person','HR Person','hr@s11.test',?,'HR','2026-01-01','ACTIVE',0,now(),'s11',now(),'s11')",HOST,DEPT);
        account(HR,HOST,"hr","ROLE_HR_ADMIN");
        jdbc.update("insert into department_hr_assignment(id,department_id,hr_user_id,hr_employee_id,active,assigned_by_user_id,assigned_at,version,created_at,created_by,updated_at,updated_by) values(?,?,?,?,true,?,now(),0,now(),'s11',now(),'s11')",UUID.randomUUID(),DEPT,HR,HOST,ADMIN);
    }
    @AfterEach void settle() { drain(); }

    @Test void credentialsAreEncryptedResponsesRedactedAndScopesFixed() throws Exception {
        var c = connection(IntegrationModels.Provider.SIMULATOR_CALENDAR);
        String ciphertext = jdbc.queryForObject("select credential_ciphertext from integration_connection where id=?",String.class,c.id());
        assertThat(ciphertext).doesNotContain(SECRET); assertThat(ciphertext).isNotBlank();
        assertThat(json.writeValueAsString(c)).doesNotContain(SECRET,"ciphertext");
        assertThat(c.minimumScopes()).containsExactly("calendar.events.write");
        assertThat(count("select count(*) from flyway_schema_history where version='65' and success")).isEqualTo(1);
    }
    @Test void maximumMultibyteCredentialIsEncryptedAndCanCompleteSimulatorDelivery() {
        String multibyte="界".repeat(4096);
        var c=integrations.create(ADMIN,new IntegrationModels.Create(UUID.randomUUID(),IntegrationModels.Provider.SIMULATOR_CALENDAR,"Multibyte boundary",multibyte,expiry()));
        String encrypted=jdbc.queryForObject("select credential_ciphertext from integration_connection where id=?",String.class,c.id());
        assertThat(encrypted).hasSizeGreaterThan(16384).hasSizeLessThanOrEqualTo(32768).doesNotContain(multibyte);
        var d=test(c,IntegrationModels.Scenario.SUCCESS);completeDue(d.id());
        assertThat(status(d.id())).isEqualTo("DELIVERED");
        assertThat(integrations.attempts(ADMIN,d.id())).extracting(IntegrationModels.Attempt::outcome).containsExactly("SUCCESS");
    }
    @Test void creationAndTestsAreIdempotentButChangedRequestPayloadsConflict() {
        var command = new IntegrationModels.Create(UUID.randomUUID(),IntegrationModels.Provider.SIMULATOR_CALENDAR,"Calendar",SECRET,expiry());
        var c = integrations.create(ADMIN,command);
        assertThat(integrations.create(ADMIN,command).id()).isEqualTo(c.id());
        assertThatThrownBy(()->integrations.create(ADMIN,new IntegrationModels.Create(command.requestId(),command.provider(),"Changed",SECRET,command.credentialExpiresAt()))).isInstanceOf(BusinessException.class);
        var test = new IntegrationModels.Test(UUID.randomUUID(),c.version(),IntegrationModels.Scenario.SUCCESS);
        var first = integrations.test(ADMIN,c.id(),test);
        assertThat(integrations.test(ADMIN,c.id(),test).id()).isEqualTo(first.id());
        assertThatThrownBy(()->integrations.test(ADMIN,c.id(),new IntegrationModels.Test(test.requestId(),c.version(),IntegrationModels.Scenario.OUTAGE))).isInstanceOf(BusinessException.class);
        assertThat(count("select count(*) from integration_delivery")).isEqualTo(1);
    }
    @Test void currentRolesPermissionsAndOwnershipProtectEveryReadAndWrite() {
        var c = connection(IntegrationModels.Provider.SIMULATOR_CALENDAR);
        var d = test(c,IntegrationModels.Scenario.SUCCESS);
        assertThat(integrations.connections(OTHER)).isEmpty();
        assertThatThrownBy(()->integrations.deliveries(OTHER,c.id(),0)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->integrations.attempts(OTHER,d.id())).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->integrations.revoke(OTHER,c.id(),c.version())).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->integrations.connections(HR)).isInstanceOf(BusinessException.class);
        jdbc.update("insert into iam_user_permission_deny(user_id,permission_name) values(?,'SYSTEM_CONFIGURE')",ADMIN);
        assertThatThrownBy(()->integrations.connections(ADMIN)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->integrations.revoke(ADMIN,c.id(),c.version())).isInstanceOf(BusinessException.class);
    }
    @Test void invalidVersionsAndCredentialExpiryNeverWrite() {
        var c = connection(IntegrationModels.Provider.SIMULATOR_CALENDAR);
        assertThatThrownBy(()->integrations.reconnect(ADMIN,c.id(),new IntegrationModels.Reconnect(42L,SECRET,expiry()))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->integrations.reconnect(ADMIN,c.id(),new IntegrationModels.Reconnect(c.version(),SECRET,Instant.now().minusSeconds(1)))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->integrations.reconnect(ADMIN,c.id(),new IntegrationModels.Reconnect(c.version(),SECRET,Instant.now().plus(91,ChronoUnit.DAYS)))).isInstanceOf(BusinessException.class);
        assertThat(integrations.connections(ADMIN).getFirst().credentialVersion()).isEqualTo(1);
    }
    @Test void realAppointmentCreationCapturesOneSafeEventAndRollbackRemovesEverything() {
        connection(IntegrationModels.Provider.SIMULATOR_CALENDAR);
        var appointment = visits.request("s11-real-request",visitCommand()); drain();
        assertThat(visits.request("s11-real-request",visitCommand()).getId()).isEqualTo(appointment.getId());
        assertThat(count("select count(*) from integration_business_event where resource_id=?",appointment.getId())).isEqualTo(1);
        assertThat(count("select count(*) from integration_delivery where resource_id=?",appointment.getId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select payload_json::text from integration_business_event where resource_id=?",String.class,appointment.getId())).doesNotContain("Sensitive Visitor","visitor@s11.test","Private purpose");
        transactions.executeWithoutResult(tx->{visits.request("s11-rolled-back",nextVisitCommand()); tx.setRollbackOnly();});
        assertThat(count("select count(*) from appointment where idempotency_key='s11-rolled-back'")).isZero();
        assertThat(count("select count(*) from integration_business_event")).isEqualTo(1);
    }
    @Test void receptionRegistrationReplaysExistingVisitWithoutDuplicatingBusinessEvents() {
        connection(IntegrationModels.Provider.SIMULATOR_CALENDAR);
        var first=visits.registerAtReception("s11-reception-replay",HR,visitCommand());drain();
        var replay=visits.registerAtReception("s11-reception-replay",HR,visitCommand());drain();
        assertThat(replay.getId()).isEqualTo(first.getId());
        assertThat(count("select count(*) from integration_business_event where resource_id=?",first.getId())).isEqualTo(1);
        assertThat(count("select count(*) from integration_delivery where resource_id=?",first.getId())).isEqualTo(1);
    }
    @Test void receptionCheckInCapturesMessagingArrivalAlongsideTheCalendarUpdate() {
        var calendar = connection(IntegrationModels.Provider.SIMULATOR_CALENDAR);
        var messaging = connection(IntegrationModels.Provider.SIMULATOR_MESSAGING);
        UUID appointment = approvedVisit();
        var access = reception.checkIn(appointment,"s11-security"); drain();
        assertThat(access.getAppointmentId()).isEqualTo(appointment);
        assertThatThrownBy(()->reception.checkIn(appointment,"s11-security")).isInstanceOf(BusinessException.class);
        assertThat(count("select count(*) from integration_delivery where connection_id=? and event_type='VISITOR_ARRIVED'",messaging.id())).isEqualTo(1);
        assertThat(count("select count(*) from integration_delivery where connection_id=? and event_type='APPOINTMENT_UPDATED'",calendar.id())).isEqualTo(1);
        assertThat(count("select count(*) from integration_simulator_receipt")).isZero();
    }
    @Test void eventCaptureWithoutAConnectionRetainsCommittedHistoryAndCancellationRevision() {
        var created = visits.request("s11-no-connection",visitCommand()); drain();
        assertThat(count("select count(*) from integration_business_event")).isEqualTo(1);
        jdbc.update("update appointment set slot_start=now()-interval '2 days',slot_end=now()-interval '2 days' + interval '30 minutes' where id=?",created.getId());
        assertThat(visits.cancelPastUnfinishedVisits()).isEqualTo(1); drain();
        assertThat(count("select count(*) from integration_business_event where event_type='APPOINTMENT_CANCELLED' and business_revision=2")).isEqualTo(1);
        assertThat(count("select count(*) from integration_delivery")).isZero();
    }
    @Test void durableSimulatorReceiptAndMappingSurviveDuplicateCompletion() {
        var c = connection(IntegrationModels.Provider.SIMULATOR_CALENDAR); UUID resource = UUID.randomUUID();
        capture(resource,"APPOINTMENT_CREATED"); var d = integrations.deliveries(ADMIN,c.id(),0).getContent().getFirst();
        var claim = integrations.claim(d.id(),Instant.now().plusSeconds(1)).orElseThrow();
        integrations.complete(claim,Instant.now().plusSeconds(2)); integrations.complete(claim,Instant.now().plusSeconds(3));
        assertThat(count("select count(*) from integration_simulator_receipt")).isEqualTo(1);
        assertThat(count("select count(*) from integration_external_mapping")).isEqualTo(1);
        assertThat(integrations.attempts(ADMIN,d.id())).hasSize(1);
        assertThat(status(d.id())).isEqualTo("DELIVERED");
        assertThatThrownBy(()->jdbc.update("delete from integration_simulator_receipt")).hasMessageContaining("immutable");
        assertThatThrownBy(()->jdbc.update("update integration_delivery_attempt set outcome='OUTAGE'")).hasMessageContaining("immutable");
    }
    @Test void newestRevisionSupersedesStaleRunningClaimAndCannotBeOverwritten() {
        var c = connection(IntegrationModels.Provider.SIMULATOR_CALENDAR); UUID resource = UUID.randomUUID();
        capture(resource,"APPOINTMENT_CREATED"); var old = integrations.deliveries(ADMIN,c.id(),0).getContent().getFirst();
        var oldClaim = integrations.claim(old.id(),Instant.now().plusSeconds(1)).orElseThrow();
        capture(resource,"APPOINTMENT_CANCELLED"); var latest = integrations.deliveries(ADMIN,c.id(),0).getContent().stream().filter(d->d.businessRevision()==2).findFirst().orElseThrow();
        var newer = integrations.claim(latest.id(),Instant.now().plusSeconds(2)).orElseThrow(); integrations.complete(newer,Instant.now().plusSeconds(3));
        integrations.complete(oldClaim,Instant.now().plusSeconds(4));
        assertThat(status(old.id())).isEqualTo("SUPERSEDED");
        assertThat(jdbc.queryForObject("select business_revision from integration_external_mapping",Long.class)).isEqualTo(2);
        assertThat(count("select count(*) from integration_simulator_receipt")).isEqualTo(1);
    }
    @Test void expiredLeasesAreRecoveredAndStaleCompletionIsFenced() {
        var c = connection(IntegrationModels.Provider.SIMULATOR_CALENDAR); var d = test(c,IntegrationModels.Scenario.SUCCESS);
        Instant now = Instant.now().plusSeconds(1); var abandoned = integrations.claim(d.id(),now).orElseThrow();
        var recovered = integrations.claim(d.id(),now.plusSeconds(61)).orElseThrow();
        integrations.complete(abandoned,now.plusSeconds(62)); assertThat(status(d.id())).isEqualTo("RUNNING");
        integrations.complete(recovered,now.plusSeconds(63));
        assertThat(integrations.attempts(ADMIN,d.id())).extracting(IntegrationModels.Attempt::outcome).containsExactly("LEASE_EXPIRED","SUCCESS");
        assertThat(count("select count(*) from integration_simulator_receipt")).isEqualTo(1);
    }
    @Test void boundedOutageRetriesAndManualReceiptsStopAtTwentyAttempts() {
        var c = connection(IntegrationModels.Provider.SIMULATOR_CALENDAR); var d = test(c,IntegrationModels.Scenario.OUTAGE);
        for (int round=0;round<4;round++) {
            for (int attempt=0;attempt<5;attempt++) completeDue(d.id());
            assertThat(status(d.id())).isEqualTo("FAILED");
            if (round<3) {
                var current = integrations.deliveries(ADMIN,c.id(),0).getContent().getFirst();
                var retry = new IntegrationModels.Retry(UUID.randomUUID(),current.version());
                integrations.retry(ADMIN,d.id(),retry); integrations.retry(ADMIN,d.id(),retry);
            }
        }
        var exhausted = integrations.deliveries(ADMIN,c.id(),0).getContent().getFirst();
        assertThat(exhausted.totalAttempts()).isEqualTo(20); assertThat(exhausted.manualRetries()).isEqualTo(3);
        assertThat(integrations.attempts(ADMIN,d.id())).hasSize(20);
        assertThatThrownBy(()->integrations.retry(ADMIN,d.id(),new IntegrationModels.Retry(UUID.randomUUID(),exhausted.version()))).isInstanceOf(BusinessException.class);
        assertThat(count("select count(*) from integration_simulator_receipt")).isZero();
    }
    @Test void rateLimitedAndPermanentOutcomesHaveSafePersistentStatus() {
        var c = connection(IntegrationModels.Provider.SIMULATOR_CALENDAR); var rate = test(c,IntegrationModels.Scenario.RATE_LIMITED);
        completeDue(rate.id()); assertThat(status(rate.id())).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("select next_attempt_at>now()+interval '59 seconds' from integration_delivery where id=?",Boolean.class,rate.id())).isTrue();
        c = integrations.connections(ADMIN).getFirst(); var permanent = test(c,IntegrationModels.Scenario.PERMANENT_FAILURE);
        completeDue(permanent.id()); assertThat(status(permanent.id())).isEqualTo("FAILED");
    }
    @Test void revokedOrReplacedCredentialsCannotCompleteClaimedDelivery() {
        var c = connection(IntegrationModels.Provider.SIMULATOR_CALENDAR); var d = test(c,IntegrationModels.Scenario.SUCCESS);
        var claim = integrations.claim(d.id(),Instant.now().plusSeconds(1)).orElseThrow();
        integrations.reconnect(ADMIN,c.id(),new IntegrationModels.Reconnect(c.version(),SECRET+"-new",expiry()));
        integrations.complete(claim,Instant.now().plusSeconds(2)); assertThat(status(d.id())).isEqualTo("NEEDS_RECONNECT");
        assertThat(count("select count(*) from integration_simulator_receipt")).isZero();
        var replacement = integrations.connections(ADMIN).getFirst(); var second = test(replacement,IntegrationModels.Scenario.SUCCESS);
        var secondClaim = integrations.claim(second.id(),Instant.now().plusSeconds(1)).orElseThrow();
        integrations.revoke(ADMIN,replacement.id(),replacement.version()); integrations.complete(secondClaim,Instant.now().plusSeconds(2));
        assertThat(status(second.id())).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("select credential_ciphertext from integration_connection where id=?",String.class,c.id())).isNull();
    }
    @Test void ownerEligibilityAndCredentialExpiryAreRecheckedAtCompletion() {
        var c = connection(IntegrationModels.Provider.SIMULATOR_CALENDAR); var d = test(c,IntegrationModels.Scenario.SUCCESS);
        var claim = integrations.claim(d.id(),Instant.now().plusSeconds(1)).orElseThrow();
        jdbc.update("update integration_connection set credential_expires_at=now()-interval '1 second' where id=?",c.id());
        integrations.complete(claim,Instant.now().plusSeconds(2)); assertThat(status(d.id())).isEqualTo("NEEDS_RECONNECT");
        c = integrations.reconnect(ADMIN,c.id(),new IntegrationModels.Reconnect(integrations.connections(ADMIN).getFirst().version(),SECRET,expiry())); var second = test(c,IntegrationModels.Scenario.SUCCESS);
        var next = integrations.claim(second.id(),Instant.now().plusSeconds(1)).orElseThrow();
        jdbc.update("update iam_user_account set enabled=false where id=?",ADMIN);
        integrations.complete(next,Instant.now().plusSeconds(2)); assertThat(status(second.id())).isEqualTo("CANCELLED");
        assertThat(count("select count(*) from integration_simulator_receipt")).isZero();
    }
    @Test void reauthRequiredStopsConnectionAndReconnectRetainsPriorAttempts() {
        var c = connection(IntegrationModels.Provider.SIMULATOR_CALENDAR); var d = test(c,IntegrationModels.Scenario.REAUTH_REQUIRED);
        completeDue(d.id()); var current = integrations.connections(ADMIN).getFirst();
        assertThat(current.status()).isEqualTo("NEEDS_RECONNECT");
        assertThat(integrations.attempts(ADMIN,d.id())).hasSize(1);
        integrations.reconnect(ADMIN,c.id(),new IntegrationModels.Reconnect(current.version(),SECRET+"-replacement",expiry()));
        assertThat(integrations.attempts(ADMIN,d.id())).hasSize(1);
        assertThat(status(d.id())).isEqualTo("NEEDS_RECONNECT");
    }
    @Test void simulatorTransactionFailureLeavesNoReceiptMappingOrFalseSuccess() {
        var c = connection(IntegrationModels.Provider.SIMULATOR_CALENDAR); UUID resource=UUID.randomUUID(); capture(resource,"APPOINTMENT_CREATED");
        var d=integrations.deliveries(ADMIN,c.id(),0).getContent().getFirst();var claim=integrations.claim(d.id(),Instant.now().plusSeconds(1)).orElseThrow();
        jdbc.execute("create function fail_s11_mapping() returns trigger language plpgsql as $$ begin raise exception 's11 mapping failure'; end $$");
        jdbc.execute("create trigger fail_s11_mapping before insert on integration_external_mapping for each row execute function fail_s11_mapping()");
        try { assertThatThrownBy(()->integrations.complete(claim,Instant.now().plusSeconds(2))).hasMessageContaining("s11 mapping failure"); }
        finally { jdbc.execute("drop trigger fail_s11_mapping on integration_external_mapping"); jdbc.execute("drop function fail_s11_mapping()"); }
        assertThat(count("select count(*) from integration_simulator_receipt")).isZero(); assertThat(status(d.id())).isEqualTo("RUNNING");
        integrations.complete(claim,Instant.now().plusSeconds(3));assertThat(status(d.id())).isEqualTo("DELIVERED");
    }
    @Test void repeatedSourceEventIdIsIdempotentAndFreeFormPayloadIsRejected() {
        connection(IntegrationModels.Provider.SIMULATOR_CALENDAR); UUID event=UUID.randomUUID(),resource=UUID.randomUUID();
        transactions.executeWithoutResult(tx->{integrations.capture(event,resource,"APPOINTMENT_CREATED",Instant.now(),snapshot("APPOINTMENT_CREATED"));integrations.capture(event,resource,"APPOINTMENT_CREATED",Instant.now(),snapshot("APPOINTMENT_CREATED"));});
        assertThat(count("select count(*) from integration_business_event")).isEqualTo(1);
        assertThat(count("select count(*) from integration_delivery")).isEqualTo(1);
        assertThatThrownBy(()->transactions.executeWithoutResult(tx->integrations.capture(resource,"APPOINTMENT_CREATED",Instant.now(),Map.of("otp","123456")))).isInstanceOf(BusinessException.class);
    }
    @Test void corruptCiphertextProducesSanitizedReconnectOutcome() {
        var c=connection(IntegrationModels.Provider.SIMULATOR_CALENDAR);var d=test(c,IntegrationModels.Scenario.SUCCESS);
        jdbc.update("update integration_connection set credential_ciphertext='poison-private-credential' where id=?",c.id());
        completeDue(d.id()); assertThat(status(d.id())).isEqualTo("NEEDS_RECONNECT");
        assertThat(integrations.attempts(ADMIN,d.id())).extracting(IntegrationModels.Attempt::outcome).containsExactly("REAUTH_REQUIRED");
        assertThat(integrations.connections(ADMIN).getFirst().status()).isEqualTo("NEEDS_RECONNECT");
        assertThat(count("select count(*) from integration_simulator_receipt")).isZero();
    }
    @Test void providerOutageDoesNotUndoCommittedAppointment() {
        var c=connection(IntegrationModels.Provider.SIMULATOR_CALENDAR);
        var appointment=visits.request("s11-provider-outage",visitCommand());drain();
        var d=integrations.deliveries(ADMIN,c.id(),0).getContent().getFirst();
        jdbc.update("update integration_delivery set scenario='OUTAGE' where id=?",d.id());completeDue(d.id());
        assertThat(jdbc.queryForObject("select status from appointment where id=?",String.class,appointment.getId())).isEqualTo("PENDING_VERIFICATION");
        assertThat(status(d.id())).isEqualTo("PENDING");
        assertThat(integrations.attempts(ADMIN,d.id())).extracting(IntegrationModels.Attempt::outcome).containsExactly("OUTAGE");
    }
    @Test void failedOutboxCaptureRollsBackAppointmentAndEventTogether() {
        connection(IntegrationModels.Provider.SIMULATOR_CALENDAR);
        jdbc.execute("create function fail_s11_delivery() returns trigger language plpgsql as $$ begin raise exception 's11 capture failure'; end $$");
        jdbc.execute("create trigger fail_s11_delivery before insert on integration_delivery for each row execute function fail_s11_delivery()");
        try { assertThatThrownBy(()->visits.request("s11-capture-failure",visitCommand())).hasMessageContaining("s11 capture failure"); }
        finally { jdbc.execute("drop trigger fail_s11_delivery on integration_delivery");jdbc.execute("drop function fail_s11_delivery()"); }
        assertThat(count("select count(*) from appointment where idempotency_key='s11-capture-failure'")).isZero();
        assertThat(count("select count(*) from integration_business_event")).isZero();
        assertThat(count("select count(*) from integration_resource_revision")).isZero();
    }
    @ParameterizedTest @ValueSource(strings={"disabled","archived","role","permission","missing-role"})
    void everyOwnerEligibilityChangeFencesAnAlreadyClaimedDelivery(String change) {
        var c=connection(IntegrationModels.Provider.SIMULATOR_CALENDAR);var d=test(c,IntegrationModels.Scenario.SUCCESS);
        var claim=integrations.claim(d.id(),Instant.now().plusSeconds(1)).orElseThrow();
        switch(change) {
            case "disabled" -> jdbc.update("update iam_user_account set enabled=false where id=?",ADMIN);
            case "archived" -> jdbc.update("update iam_user_account set archived=true,archived_at=now(),enabled=false where id=?",ADMIN);
            case "role" -> jdbc.update("update iam_user_role set role_name='ROLE_EMPLOYEE' where user_id=?",ADMIN);
            case "permission" -> jdbc.update("insert into iam_user_permission_deny(user_id,permission_name) values(?,'SYSTEM_CONFIGURE')",ADMIN);
            case "missing-role" -> jdbc.update("delete from iam_user_role where user_id=?",ADMIN);
            default -> throw new AssertionError(change);
        }
        integrations.complete(claim,Instant.now().plusSeconds(2));assertThat(status(d.id())).isEqualTo("CANCELLED");
        assertThat(count("select count(*) from integration_simulator_receipt")).isZero();
        assertThat(jdbc.queryForObject("select outcome from integration_delivery_attempt where delivery_id=?",String.class,d.id())).isEqualTo("OWNER_INELIGIBLE");
    }
    @Test void simultaneousClaimersProduceOneAttemptAndOneReceipt() throws Exception {
        var c=connection(IntegrationModels.Provider.SIMULATOR_CALENDAR);var d=test(c,IntegrationModels.Scenario.SUCCESS);
        try (var pool=Executors.newFixedThreadPool(2)) {
            var start=new CountDownLatch(1);
            var a=pool.submit(()->{start.await();return integrations.claim(d.id(),Instant.now().plusSeconds(1));});
            var b=pool.submit(()->{start.await();return integrations.claim(d.id(),Instant.now().plusSeconds(1));});start.countDown();
            var claims=List.of(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS));
            assertThat(claims.stream().filter(java.util.Optional::isPresent).count()).isEqualTo(1);
            claims.forEach(result->result.ifPresent(value->integrations.complete(value,Instant.now().plusSeconds(2))));
        }
        assertThat(integrations.attempts(ADMIN,d.id())).hasSize(1);assertThat(count("select count(*) from integration_simulator_receipt")).isEqualTo(1);
    }
    private IntegrationModels.Connection connection(IntegrationModels.Provider provider) { return integrations.create(ADMIN,new IntegrationModels.Create(UUID.randomUUID(),provider,"S11 connection",SECRET,expiry())); }
    private IntegrationModels.Delivery test(IntegrationModels.Connection c,IntegrationModels.Scenario scenario) { return integrations.test(ADMIN,c.id(),new IntegrationModels.Test(UUID.randomUUID(),c.version(),scenario)); }
    private Instant expiry() { return Instant.now().plus(1,ChronoUnit.DAYS).truncatedTo(ChronoUnit.MICROS); }
    private void capture(UUID resource,String type) { transactions.executeWithoutResult(tx->integrations.capture(resource,type,Instant.now(),snapshot(type))); }
    private Map<String,Object> snapshot(String type) { return Map.of("status",type.equals("APPOINTMENT_CANCELLED")?"CANCELLED":"APPROVED","appointmentType","HR_VISIT","slotStart","2026-10-08T09:00:00Z","slotEnd","2026-10-08T09:30:00Z"); }
    private void completeDue(UUID id) { jdbc.update("update integration_delivery set next_attempt_at=now()-interval '1 second' where id=?",id);Instant now=Instant.now().plusSeconds(1); integrations.complete(integrations.claim(id,now).orElseThrow(),now.plusSeconds(1)); }
    private String status(UUID id) { return jdbc.queryForObject("select status from integration_delivery where id=?",String.class,id); }
    private long count(String sql,Object...args) { return Objects.requireNonNull(jdbc.queryForObject(sql,Long.class,args)); }
    private UUID approvedVisit() { UUID id=UUID.randomUUID();jdbc.update("insert into appointment(id,reference_number,idempotency_key,type,status,visitor_name,visitor_email,visitor_phone,host_employee_id,routing_department_id,slot_start,slot_end,purpose,version,created_at,created_by,updated_at,updated_by) values(?,?,?,'EMPLOYEE_VISIT','APPROVED','Sensitive Visitor','visitor@s11.test','0000000000',?,?,now()+interval '1 day',now()+interval '1 day 30 minutes','Private purpose',0,now(),'s11',now(),'s11')",id,"S11-"+id,id.toString(),HOST,DEPT);return id; }
    private AppointmentService.CreateAppointment visitCommand() { return command(0); }
    private AppointmentService.CreateAppointment nextVisitCommand() { return command(40); }
    private AppointmentService.CreateAppointment command(int offset) { LocalDate day=LocalDate.now(ZoneId.of("Asia/Kolkata")).plusDays(1);while(day.getDayOfWeek()==DayOfWeek.SATURDAY||day.getDayOfWeek()==DayOfWeek.SUNDAY)day=day.plusDays(1);Instant start=day.atTime(LocalTime.of(9,30)).atZone(ZoneId.of("Asia/Kolkata")).toInstant().plusSeconds(offset*60L);return new AppointmentService.CreateAppointment(AppointmentType.HR_VISIT,"Sensitive Visitor","visitor@s11.test","0000000000",null,HOST,DEPT,null,start,start.plusSeconds(1800),"Private purpose"); }
    private void account(UUID id,UUID employee,String name,String role) { jdbc.update("insert into iam_user_account(id,email,full_name,employee_id,password_hash,enabled,force_password_change,account_status,archived,version,created_at,created_by,updated_at,updated_by) values(?,?,?,?,'test-only-hash',true,false,'ACTIVE',false,0,now(),'s11',now(),'s11')",id,name+"@s11.test",name,employee);jdbc.update("insert into iam_user_role(user_id,role_name) values(?,?)",id,role); }
    private void drain() { if (!(notificationExecutor instanceof ThreadPoolTaskExecutor executor))return;long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);while(executor.getActiveCount()>0||!executor.getThreadPoolExecutor().getQueue().isEmpty()) { if(System.nanoTime()>deadline)throw new AssertionError("Notification writes did not settle");try{Thread.sleep(20);}catch(InterruptedException exception){Thread.currentThread().interrupt();throw new AssertionError(exception);} } }
}
