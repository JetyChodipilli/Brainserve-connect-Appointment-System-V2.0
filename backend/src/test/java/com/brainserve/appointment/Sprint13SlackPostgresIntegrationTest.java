package com.brainserve.appointment;

import com.brainserve.appointment.document.infrastructure.ClamAvScanner;
import com.brainserve.appointment.iam.application.JwtService;
import com.brainserve.appointment.iam.infrastructure.UserAccountRepository;
import com.brainserve.appointment.integration.api.IntegrationModels;
import com.brainserve.appointment.integration.application.IntegrationService;
import com.brainserve.appointment.integration.slack.*;
import com.brainserve.appointment.shared.application.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import com.brainserve.appointment.shared.application.SensitiveStringConverter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionOperations;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.services.s3.S3Client;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real PostgreSQL transactions and authority locks; only Slack HTTP is replaced. */
@Testcontainers(disabledWithoutDocker=true)
@AutoConfigureMockMvc
@SpringBootTest(properties={
    "brainserve.security.jwt-secret=test-only-secret-key-that-is-at-least-thirty-two-bytes",
    "brainserve.security.pii-encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
    "brainserve.bootstrap.system-admin-enabled=false","brainserve.bootstrap.ceo-enabled=false",
    "brainserve.work-routines.enabled=false","brainserve.integrations.enabled=false",
    "brainserve.integrations.slack.app-base-url=https://brainserve.test",
    "brainserve.approval-reminders.poll-ms=3600000","spring.kafka.listener.auto-startup=false",
    "brainserve.notification.internal-call-dispatch-ms=3600000","brainserve.notification.poll-ms=3600000",
    "aws.s3.access-key=test-access-key","aws.s3.secret-key=test-secret-key"
})
class Sprint13SlackPostgresIntegrationTest {
    @Container static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>("postgres:17.2-alpine");
    @Container static final GenericContainer<?> REDIS=new GenericContainer<>("redis:7.4.1-alpine").withExposedPorts(6379);
    @DynamicPropertySource static void infrastructure(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url",POSTGRES::getJdbcUrl);p.add("spring.datasource.username",POSTGRES::getUsername);p.add("spring.datasource.password",POSTGRES::getPassword);
        p.add("spring.data.redis.host",REDIS::getHost);p.add("spring.data.redis.port",()->REDIS.getMappedPort(6379));
    }
    @Autowired IntegrationService service;
    @Autowired SlackRevocationWorker revocations;
    @Autowired SlackAdapter adapter;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionOperations transactions;
    @Autowired ObjectMapper json;
    @Autowired MockMvc mvc;
    @Autowired JwtService jwt;
    @Autowired UserAccountRepository users;
    @Autowired SensitiveStringConverter secrets;
    @Autowired StringRedisTemplate redis;
    @MockitoBean SlackHttpTransport http;
    @MockitoBean S3Client s3;
    @MockitoBean ClamAvScanner scanner;
    static final UUID ADMIN=UUID.fromString("b1300000-0000-0000-0000-000000000001"),OTHER=UUID.fromString("b1300000-0000-0000-0000-000000000002");
    static final String TOKEN="xoxb-private-provider-token",NEW="xoxb-private-replacement-token",CHANNEL="C12345678";
    @BeforeEach void fixture() throws Exception {
        try(var connection=redis.getConnectionFactory().getConnection()) {connection.serverCommands().flushDb();}
        jdbc.execute("truncate integration_connection,integration_resource_revision,integration_business_event,integration_slack_rate_limit cascade");
        jdbc.execute("truncate iam_user_account cascade");
        account(ADMIN,"admin");account(OTHER,"other");reset(http);
        when(http.auth(anyString())).thenReturn(auth("B12345678",Set.of("chat:write")));
        when(http.post(anyString(),anyString(),anyString(),anyBoolean())).thenReturn(reply(200,"{\"ok\":true,\"channel\":\"C12345678\",\"ts\":\"1710000000.000100\"}",null,0));
    }
    @Test void installationIsEncryptedRedactedAndCommandReplayIsExact() throws Exception {
        UUID request=UUID.randomUUID();Instant expiry=expiry();var command=new SlackModels.Create(request," Slack arrivals ",CHANNEL,TOKEN,expiry);
        var c=service.createSlack(ADMIN,command);assertThat(service.createSlack(ADMIN,command).id()).isEqualTo(c.id());
        verify(http,times(1)).auth(TOKEN);
        assertThat(c.label()).isEqualTo("Slack arrivals");assertThat(c.minimumScopes()).containsExactly("chat:write");
        assertThat(json.writeValueAsString(c)).doesNotContain(TOKEN,"fingerprint","ciphertext");
        assertThat(jdbc.queryForObject("select credential_ciphertext from integration_connection where id=?",String.class,c.id())).doesNotContain(TOKEN);
        assertThat(command.toString()).doesNotContain(TOKEN);
        assertThatThrownBy(()->service.createSlack(ADMIN,new SlackModels.Create(request,"Slack arrivals","G12345678",TOKEN,expiry))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->service.createSlack(OTHER,command)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->service.create(ADMIN,new IntegrationModels.Create(UUID.randomUUID(),IntegrationModels.Provider.SLACK_MESSAGING,"bypass",TOKEN,expiry))).isInstanceOf(BusinessException.class);
    }
    @Test void actualSlackRoutesRequireCurrentOwnerAndFreshMfa() throws Exception {
        String fresh=bearer(ADMIN,Instant.now()),old=bearer(ADMIN,Instant.now().minusSeconds(3600));
        String command=json.writeValueAsString(Map.of("requestId",UUID.randomUUID(),"label","Slack arrivals","channelId",CHANNEL,"credential",TOKEN,"credentialExpiresAt",expiry().toString()));
        mvc.perform(post("/api/v1/integrations/slack/connections").header("Authorization",old).contentType("application/json").content(command)).andExpect(status().isForbidden());
        verify(http,never()).auth(anyString());
        mvc.perform(get("/api/v1/integrations/slack/config").header("Authorization",fresh)).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"));
        mvc.perform(post("/api/v1/integrations/slack/connections").header("Authorization",fresh).contentType("application/json").content(command)).andExpect(status().isOk()).andExpect(jsonPath("provider").value("SLACK_MESSAGING")).andExpect(jsonPath("credential").doesNotExist());
        var c=service.connections(ADMIN).getFirst();
        mvc.perform(get("/api/v1/integrations/slack/connections/"+c.id()).header("Authorization",bearer(OTHER,Instant.now()))).andExpect(status().isNotFound());
        jdbc.update("update iam_user_role set role_name='ROLE_EMPLOYEE' where user_id=?",ADMIN);
        mvc.perform(get("/api/v1/integrations/slack/config").header("Authorization",fresh)).andExpect(status().isForbidden());
    }
    @Test void missingOrExcessScopesAndNonBotCredentialsAreRejected() throws Exception {
        for(Set<String> scopes:List.of(Set.<String>of(),Set.of("chat:write","users:read"),Set.of("chat:write.public"))) {
            when(http.auth(TOKEN)).thenReturn(auth("B12345678",scopes));assertThatThrownBy(this::connect).isInstanceOf(BusinessException.class);
        }
        when(http.auth(TOKEN)).thenReturn(reply(200,"{\"ok\":true,\"team_id\":\"T12345678\"}",null,0));
        assertThatThrownBy(this::connect).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->service.createSlack(ADMIN,new SlackModels.Create(UUID.randomUUID(),"private","D12345678",TOKEN,expiry()))).isInstanceOf(BusinessException.class);
        assertThat(count("select count(*) from integration_connection")).isZero();
    }
    @Test void dedicatedBotCannotBeSharedAcrossConnections() {
        connect();assertThatThrownBy(this::connect).isInstanceOf(BusinessException.class);
        assertThat(count("select count(*) from integration_connection")).isEqualTo(1);
    }
    @Test void committedCaptureDeduplicatesAndRollbackNeverCallsSlack() {
        var c=connect();UUID event=UUID.randomUUID(),resource=UUID.randomUUID();
        transactions.executeWithoutResult(tx->{service.capture(event,resource,"VISITOR_ARRIVED",Instant.now(),Map.of("arrived",true));service.capture(event,resource,"VISITOR_ARRIVED",Instant.now(),Map.of("arrived",true));});
        assertThat(count("select count(*) from integration_delivery")).isEqualTo(1);verify(http,never()).post(anyString(),anyString(),anyString(),anyBoolean());
        transactions.executeWithoutResult(tx->{service.capture(UUID.randomUUID(),"VISITOR_ARRIVED",Instant.now(),Map.of("arrived",true));tx.setRollbackOnly();});
        assertThat(count("select count(*) from integration_delivery")).isEqualTo(1);
        UUID d=service.deliveries(ADMIN,c.id(),0).getContent().getFirst().id();complete(d);completeIfDue(d);
        assertThat(deliveryStatus(d)).isEqualTo("DELIVERED");verify(http,times(1)).post(TOKEN,CHANNEL,"https://brainserve.test/",false);
        assertThat(count("select count(*) from integration_external_mapping")).isEqualTo(1);
        assertThat(service.attempts(ADMIN,d)).hasSize(1);
    }
    @Test void laterAppointmentRevisionDoesNotSuppressArrival() {
        var c=connect();UUID resource=UUID.randomUUID();transactions.executeWithoutResult(tx->{
            service.capture(resource,"VISITOR_ARRIVED",Instant.now(),Map.of("arrived",true));
            service.capture(resource,"APPOINTMENT_UPDATED",Instant.now(),Map.of("status","APPROVED","appointmentType","HR_VISIT","slotStart","2026-10-08T09:00:00Z","slotEnd","2026-10-08T09:30:00Z"));
        });
        var d=service.deliveries(ADMIN,c.id(),0).getContent().getFirst();complete(d.id());assertThat(deliveryStatus(d.id())).isEqualTo("DELIVERED");
    }
    @Test void workspace429FencePreventsAnotherChannelFromPostingEarly() throws Exception {
        var c=connect();var d=test(c);when(http.post(anyString(),anyString(),anyString(),anyBoolean())).thenReturn(reply(429,null,"rate_limited",120));
        complete(d.id());Instant fence=jdbc.queryForObject("select next_attempt_at from integration_slack_rate_limit where channel_id=''",Timestamp.class).toInstant();
        assertThat(deliveryStatus(d.id())).isEqualTo("PENDING");assertThat(next(d.id())).isAfterOrEqualTo(fence);
        when(http.auth(NEW)).thenReturn(auth("B87654321",Set.of("chat:write")));
        var second=service.createSlack(ADMIN,new SlackModels.Create(UUID.randomUUID(),"second","G12345678",NEW,expiry()));var secondDelivery=test(second);
        complete(secondDelivery.id());verify(http,times(1)).post(anyString(),anyString(),anyString(),anyBoolean());
        assertThat(next(secondDelivery.id())).isAfterOrEqualTo(fence);assertThat(deliveryStatus(secondDelivery.id())).isEqualTo("PENDING");
    }
    @Test void providerDelayBeyondHorizonIsTerminalWithoutEarlyRetry() throws Exception {
        var d=test(connect());when(http.post(anyString(),anyString(),anyString(),anyBoolean())).thenReturn(reply(429,null,"rate_limited",100_000));complete(d.id());
        assertThat(deliveryStatus(d.id())).isEqualTo("FAILED");assertThat(jdbc.queryForObject("select next_attempt_at from integration_slack_rate_limit where channel_id=''",Timestamp.class).toInstant()).isAfter(Instant.now().plusSeconds(99_000));
    }
    @Test void channelPacingDefersAnotherNoticeWithoutConsumingSendBudget() {
        var c=connect();var first=test(c);complete(first.id());c=service.connections(ADMIN).getFirst();var second=test(c);complete(second.id());
        verify(http,times(1)).post(anyString(),anyString(),anyString(),anyBoolean());assertThat(deliveryStatus(second.id())).isEqualTo("PENDING");
        assertThat(service.attempts(ADMIN,second.id())).isEmpty();
        assertThat(jdbc.queryForObject("select total_attempts from integration_delivery where id=?",Integer.class,second.id())).isZero();
        assertThat(jdbc.queryForObject("select last_result_code from integration_delivery where id=?",String.class,second.id())).isEqualTo("RATE_WAIT");
    }
    @Test void moreThanFiveQueuedNoticesAllDrainAfterPacingDeferrals() {
        var c=connect();List<UUID> ids=new ArrayList<>();for(int n=0;n<6;n++)ids.add(test(c).id());
        for(int poll=0;poll<6;poll++) {
            // Advance the durable clock fences between poll rounds; every round still permits only one post.
            jdbc.update("update integration_slack_rate_limit set next_attempt_at=now()-interval '1 second'");
            jdbc.update("update integration_delivery set next_attempt_at=now()-interval '1 second' where status='PENDING'");
            for(UUID id:ids) if(deliveryStatus(id).equals("PENDING")) complete(id);
        }
        for(UUID id:ids) {assertThat(deliveryStatus(id)).isEqualTo("DELIVERED");assertThat(service.attempts(ADMIN,id)).hasSize(1);}
        verify(http,times(6)).post(anyString(),anyString(),anyString(),anyBoolean());
    }
    @Test void nearExpiredArrivalDoesNotStartHttpBeyondItsHorizon() {
        var d=test(connect());jdbc.update("update integration_delivery set occurred_at=now()-interval '24 hours'+interval '3 seconds' where id=?",d.id());
        complete(d.id());assertThat(deliveryStatus(d.id())).isEqualTo("FAILED");verify(http,never()).post(anyString(),anyString(),anyString(),anyBoolean());
    }
    @Test void workspaceLockWaitRechecksTheDeadlineBeforePosting() throws Exception {
        var c=connect();var held=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var holder=pool.submit(()->transactions.executeWithoutResult(tx->{
                jdbc.queryForObject("select pg_advisory_xact_lock(hashtextextended(?,13070))",Object.class,"T12345678");held.countDown();
                try {if(!release.await(10,TimeUnit.SECONDS))throw new AssertionError("Workspace lock was not released");}
                catch(InterruptedException interrupted) {Thread.currentThread().interrupt();throw new AssertionError(interrupted);}
            }));
            assertThat(held.await(10,TimeUnit.SECONDS)).isTrue();Instant start=Instant.now();
            var posting=pool.submit(()->transactions.execute(tx->adapter.deliver(c.id(),true,start,start.plusSeconds(6),start.plusSeconds(60))));
            try {assertThatThrownBy(()->posting.get(2,TimeUnit.SECONDS)).isInstanceOf(TimeoutException.class);}
            finally {release.countDown();}
            assertThat(posting.get(10,TimeUnit.SECONDS).code()).isEqualTo("DELIVERY_EXPIRED");holder.get(10,TimeUnit.SECONDS);
        } finally {release.countDown();}
        verify(http,never()).post(anyString(),anyString(),anyString(),anyBoolean());
    }
    @Test void uncertainAckRequiresExplicitDuplicateRiskAndExactReplay() throws Exception {
        var d=test(connect());when(http.post(anyString(),anyString(),anyString(),anyBoolean())).thenReturn(reply(0,null,"TRANSPORT_UNKNOWN",0));complete(d.id());
        var current=service.deliveries(ADMIN,d.connectionId(),0).getContent().getFirst();assertThat(current.status()).isEqualTo("UNKNOWN");
        assertThatThrownBy(()->service.retry(ADMIN,d.id(),new IntegrationModels.Retry(UUID.randomUUID(),current.version()))).isInstanceOf(BusinessException.class);
        UUID request=UUID.randomUUID();var accepted=new IntegrationModels.Retry(request,current.version(),true);var queued=service.retry(ADMIN,d.id(),accepted);
        assertThat(queued.status()).isEqualTo("PENDING");assertThat(service.retry(ADMIN,d.id(),accepted).version()).isEqualTo(queued.version());
        assertThatThrownBy(()->service.retry(ADMIN,d.id(),new IntegrationModels.Retry(request,current.version(),false))).isInstanceOf(BusinessException.class);
        assertThat(count("select count(*) from integration_external_mapping")).isZero();
    }
    @Test void incompleteSuccessAcknowledgementNeverFabricatesDelivered() throws Exception {
        var d=test(connect());when(http.post(anyString(),anyString(),anyString(),anyBoolean())).thenReturn(reply(200,"{\"ok\":true,\"channel\":\"C12345678\"}",null,0));complete(d.id());assertThat(deliveryStatus(d.id())).isEqualTo("UNKNOWN");
    }
    @Test void expiredWorkerLeaseIsUnknownAndNeverAutomaticallyReplayed() {
        var d=test(connect());Instant now=Instant.now().plusSeconds(1);var claim=service.claim(d.id(),now).orElseThrow();
        assertThat(service.claim(d.id(),now.plusSeconds(61))).isEmpty();service.complete(claim,now.plusSeconds(62));
        assertThat(deliveryStatus(d.id())).isEqualTo("UNKNOWN");verify(http,never()).post(anyString(),anyString(),anyString(),anyBoolean());
    }
    @ParameterizedTest @ValueSource(strings={"disabled","archived","role","permission","pending"})
    void accountAuthorityIsRecheckedBeforeHttp(String change) {
        var d=test(connect());var claim=service.claim(d.id(),Instant.now().plusSeconds(1)).orElseThrow();
        switch(change) {
            case "disabled" -> jdbc.update("update iam_user_account set enabled=false where id=?",ADMIN);
            case "archived" -> jdbc.update("update iam_user_account set archived=true where id=?",ADMIN);
            case "role" -> jdbc.update("update iam_user_role set role_name='ROLE_EMPLOYEE' where user_id=?",ADMIN);
            case "permission" -> jdbc.update("insert into iam_user_permission_deny(user_id,permission_name) values(?,'SYSTEM_CONFIGURE')",ADMIN);
            default -> jdbc.update("update iam_user_account set account_status='PENDING_APPROVAL' where id=?",ADMIN);
        }
        service.complete(claim,Instant.now().plusSeconds(2));assertThat(deliveryStatus(d.id())).isEqualTo("CANCELLED");verify(http,never()).post(anyString(),anyString(),anyString(),anyBoolean());
    }
    @Test void foreignAdminCannotInspectRenewOrRevoke() {
        var c=connect();assertThatThrownBy(()->service.slackMetadata(OTHER,c.id())).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->service.renewSlack(OTHER,c.id(),new IntegrationModels.Reconnect(c.version(),NEW,expiry()))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->service.revoke(OTHER,c.id(),c.version())).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->service.revoke(ADMIN,c.id(),c.version()+1)).isInstanceOf(BusinessException.class);
    }
    @Test void renewalRequiresInvalidOldTokenAndFencesStaleWorker() throws Exception {
        var c=connect();var d=test(c);var claim=service.claim(d.id(),Instant.now().plusSeconds(1)).orElseThrow();
        assertThatThrownBy(()->service.renewSlack(ADMIN,c.id(),new IntegrationModels.Reconnect(c.version(),NEW,expiry()))).isInstanceOf(BusinessException.class);
        when(http.auth(TOKEN)).thenReturn(reply(200,"{\"ok\":false}","token_revoked",0));
        var renewed=service.renewSlack(ADMIN,c.id(),new IntegrationModels.Reconnect(c.version(),NEW,expiry()));assertThat(renewed.credentialVersion()).isEqualTo(2);
        service.complete(claim,Instant.now().plusSeconds(2));assertThat(deliveryStatus(d.id())).isEqualTo("UNKNOWN");verify(http,never()).post(anyString(),anyString(),anyString(),anyBoolean());
    }
    @Test void revocationStopsDeliveriesAndWipesOnlyAfterExplicitRemoteProof() throws Exception {
        var c=connect();var d=test(c);service.revoke(ADMIN,c.id(),c.version());assertThat(deliveryStatus(d.id())).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("select credential_ciphertext from integration_connection where id=?",String.class,c.id())).isNull();
        UUID job=job(c.id());assertThat(jdbc.queryForObject("select token_ciphertext from integration_slack_revocation where id=?",String.class,job)).isNotBlank().doesNotContain(TOKEN);
        when(http.revoke(TOKEN)).thenReturn(reply(200,"{\"ok\":true}",null,0));revocations.process(job);assertThat(service.slackMetadata(ADMIN,c.id()).revocationStatus()).isEqualTo("RETRYING");
        dueJob(job);jdbc.update("update iam_user_account set enabled=false where id=?",ADMIN);
        when(http.revoke(TOKEN)).thenReturn(reply(200,"{\"ok\":true,\"revoked\":true}",null,0));revocations.process(job);
        assertThat(jdbc.queryForObject("select token_ciphertext from integration_slack_revocation where id=?",String.class,job)).isEmpty();
        assertThat(jdbc.queryForObject("select status from integration_slack_revocation where id=?",String.class,job)).isEqualTo("COMPLETE");
    }
    @Test void remote429AndTerminalRecoveryRespectBounds() throws Exception {
        var c=connect();service.revoke(ADMIN,c.id(),c.version());UUID job=job(c.id());when(http.revoke(TOKEN)).thenReturn(reply(429,null,"rate_limited",3600));
        revocations.process(job);revocations.process(job);verify(http,times(1)).revoke(TOKEN);
        assertThat(jdbc.queryForObject("select next_attempt_at from integration_slack_revocation where id=?",Timestamp.class,job).toInstant()).isAfter(Instant.now().plusSeconds(3590));
        jdbc.update("delete from integration_slack_rate_limit");dueJob(job);jdbc.update("update integration_slack_revocation set attempts=9 where id=?",job);
        when(http.revoke(TOKEN)).thenReturn(reply(0,null,"TRANSPORT_UNKNOWN",0));revocations.process(job);
        assertThat(service.slackMetadata(ADMIN,c.id()).revocationStatus()).isEqualTo("FAILED");
        c=service.connections(ADMIN).getFirst();service.retrySlackRevocation(ADMIN,c.id(),c.version());
        assertThat(jdbc.queryForObject("select manual_retries from integration_slack_revocation where id=?",Integer.class,job)).isEqualTo(1);
    }
    @Test void simultaneousClaimersProduceOneRemotePost() throws Exception {
        var d=test(connect());try(var pool=Executors.newFixedThreadPool(2)) {
            var start=new CountDownLatch(1);var a=pool.submit(()->{start.await();return service.claim(d.id(),Instant.now().plusSeconds(1));});
            var b=pool.submit(()->{start.await();return service.claim(d.id(),Instant.now().plusSeconds(1));});start.countDown();
            var claims=List.of(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS));assertThat(claims.stream().filter(Optional::isPresent).count()).isEqualTo(1);
            claims.forEach(claim->claim.ifPresent(value->service.complete(value,Instant.now().plusSeconds(2))));
        }
        verify(http,times(1)).post(anyString(),anyString(),anyString(),anyBoolean());assertThat(service.attempts(ADMIN,d.id())).hasSize(1);
    }
    @Test void oldArrivalCannotBeDeliveredOrManuallyRetried() {
        var d=test(connect());jdbc.update("update integration_delivery set occurred_at=now()-interval '25 hours' where id=?",d.id());
        assertThat(service.claim(d.id(),Instant.now().plusSeconds(1))).isEmpty();assertThat(deliveryStatus(d.id())).isEqualTo("FAILED");
        var observed=service.deliveries(ADMIN,d.connectionId(),0).getContent().getFirst();
        assertThatThrownBy(()->service.retry(ADMIN,d.id(),new IntegrationModels.Retry(UUID.randomUUID(),observed.version()))).isInstanceOf(BusinessException.class);
        verify(http,never()).post(anyString(),anyString(),anyString(),anyBoolean());
    }
    private IntegrationModels.Connection connect() {return service.createSlack(ADMIN,new SlackModels.Create(UUID.randomUUID(),"Slack arrivals",CHANNEL,TOKEN,expiry()));}
    private IntegrationModels.Delivery test(IntegrationModels.Connection c) {return service.test(ADMIN,c.id(),new IntegrationModels.Test(UUID.randomUUID(),c.version(),IntegrationModels.Scenario.SUCCESS));}
    private Instant expiry() {return Instant.now().plusSeconds(86400).truncatedTo(ChronoUnit.MICROS);}
    private void complete(UUID id) {Instant now=Instant.now().plusSeconds(1);service.complete(service.claim(id,now).orElseThrow(),now);}
    private void completeIfDue(UUID id) {service.claim(id,Instant.now().plusSeconds(2)).ifPresent(c->service.complete(c,Instant.now().plusSeconds(2)));}
    private String deliveryStatus(UUID id) {return jdbc.queryForObject("select status from integration_delivery where id=?",String.class,id);}
    private Instant next(UUID id) {return jdbc.queryForObject("select next_attempt_at from integration_delivery where id=?",Timestamp.class,id).toInstant();}
    private UUID job(UUID id) {return jdbc.queryForObject("select id from integration_slack_revocation where connection_id=?",UUID.class,id);}
    private void dueJob(UUID id) {jdbc.update("update integration_slack_revocation set next_attempt_at=now()-interval '1 second' where id=?",id);}
    private long count(String sql) {return Objects.requireNonNull(jdbc.queryForObject(sql,Long.class));}
    private SlackHttpTransport.Reply auth(String bot,Set<String> scopes) throws Exception {return new SlackHttpTransport.Reply(200,json.readTree("{\"ok\":true,\"team_id\":\"T12345678\",\"bot_id\":\""+bot+"\"}"),scopes,0,null);}
    private SlackHttpTransport.Reply reply(int status,String body,String error,int retry) throws Exception {return new SlackHttpTransport.Reply(status,body==null?null:json.readTree(body),Set.of("chat:write"),retry,error);}
    private void account(UUID id,String name) {jdbc.update("insert into iam_user_account(id,email,full_name,password_hash,enabled,force_password_change,account_status,archived,version,created_at,created_by,updated_at,updated_by) values(?,?,?,'test-only-hash',true,false,'ACTIVE',false,0,now(),'s13',now(),'s13')",id,name+"@s13.test",name);jdbc.update("insert into iam_user_role(user_id,role_name) values(?,'ROLE_SYSTEM_ADMIN')",id);jdbc.update("insert into iam_mfa_credential(user_id,secret_ciphertext,enrolled_at,last_accepted_step) values(?,?,now(),0)",id,secrets.convertToDatabaseColumn("S13TESTMFASECRET"));}
    private String bearer(UUID actor,Instant proof) {
        UUID family=UUID.randomUUID();
        jdbc.update("insert into iam_refresh_token_session(id,user_id,token_hash,family_id,expires_at,session_started_at,mfa_verified_at,version,created_at,created_by,updated_at,updated_by) values(?,?,?,?,now()+interval '1 day',now(),?,0,now(),'s13',now(),'s13')",UUID.randomUUID(),actor,UUID.randomUUID().toString().replace("-","")+UUID.randomUUID().toString().replace("-",""),family,Timestamp.from(proof));
        return "Bearer "+jwt.issue(users.findById(actor).orElseThrow(),family,proof,true,true).value();
    }
}
