package com.brainserve.appointment;

import com.brainserve.appointment.document.infrastructure.ClamAvScanner;
import com.brainserve.appointment.iam.application.JwtService;
import com.brainserve.appointment.iam.infrastructure.UserAccountRepository;
import com.brainserve.appointment.integration.api.IntegrationModels;
import com.brainserve.appointment.integration.application.IntegrationService;
import com.brainserve.appointment.integration.google.GoogleCalendarAdapter;
import com.brainserve.appointment.integration.google.GoogleCalendarConfiguration;
import com.brainserve.appointment.integration.google.GoogleCalendarTestServer;
import com.brainserve.appointment.integration.google.GoogleHttpTransport;
import com.brainserve.appointment.integration.google.GoogleRevocationWorker;
import com.brainserve.appointment.shared.application.SensitiveStringConverter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionOperations;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.services.s3.S3Client;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real migrated PostgreSQL/Redis, signed JWTs, production consent/worker and native HTTP.
 * This is automated transport coverage; live Google consent/UAT is a separate acceptance gate.
 */
@Testcontainers(disabledWithoutDocker=true)
@SpringBootTest(properties={
        "brainserve.security.jwt-secret=test-only-secret-key-that-is-at-least-thirty-two-bytes",
        "brainserve.security.pii-encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "brainserve.bootstrap.system-admin-enabled=false","brainserve.bootstrap.ceo-enabled=false",
        "brainserve.integrations.enabled=false","brainserve.work-routines.enabled=false",
        "brainserve.integrations.google-calendar.client-id=s12-client.apps.googleusercontent.com",
        "brainserve.integrations.google-calendar.client-secret=s12-private-client-secret",
        "brainserve.integrations.google-calendar.redirect-uri=https://brainserve.test/api/v1/integrations/google-calendar/callback",
        "brainserve.approval-reminders.poll-ms=3600000","spring.kafka.listener.auto-startup=false",
        "brainserve.notification.internal-call-dispatch-ms=3600000","brainserve.notification.poll-ms=3600000",
        "aws.s3.access-key=test-access-key","aws.s3.secret-key=test-secret-key"
})
@AutoConfigureMockMvc
@Import(Sprint12GoogleConsentPostgresIntegrationTest.NativeHttp.class)
class Sprint12GoogleConsentPostgresIntegrationTest {
    @Container static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>("postgres:17.2-alpine");
    @Container static final GenericContainer<?> REDIS=new GenericContainer<>("redis:7.4.1-alpine").withExposedPorts(6379);
    static final GoogleCalendarTestServer GOOGLE=new GoogleCalendarTestServer();
    @DynamicPropertySource static void infrastructure(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url",POSTGRES::getJdbcUrl);properties.add("spring.datasource.username",POSTGRES::getUsername);
        properties.add("spring.datasource.password",POSTGRES::getPassword);properties.add("spring.data.redis.host",REDIS::getHost);
        properties.add("spring.data.redis.port",()->REDIS.getMappedPort(6379));
    }
    @TestConfiguration static class NativeHttp {
        @Bean @Primary GoogleHttpTransport testGoogleTransport(ObjectMapper json) {return GOOGLE.transport(json);}
    }
    @AfterAll static void stopServer() {GOOGLE.close();}
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired ObjectMapper json;
    @Autowired JwtService jwt;
    @Autowired UserAccountRepository users;
    @Autowired IntegrationService integrations;
    @Autowired GoogleCalendarAdapter adapter;
    @Autowired GoogleRevocationWorker revocations;
    @Autowired SensitiveStringConverter secrets;
    @Autowired TransactionOperations transactions;
    @MockitoBean S3Client s3;
    @MockitoBean ClamAvScanner scanner;
    static final String ROOT=GoogleCalendarConfiguration.ROOT;
    static final UUID ADMIN=UUID.fromString("b1200000-0000-0000-0000-000000000001"),OTHER=UUID.fromString("b1200000-0000-0000-0000-000000000002");
    private UUID sid,otherSid;
    private String bearer,otherBearer;
    private Instant proof;
    @BeforeEach void fixture() {
        GOOGLE.reset();
        try(var connection=redis.getConnectionFactory().getConnection()) {connection.serverCommands().flushDb();}
        jdbc.execute("truncate integration_connection,iam_user_account cascade");
        proof=Instant.now().truncatedTo(ChronoUnit.SECONDS);sid=UUID.randomUUID();otherSid=UUID.randomUUID();
        account(ADMIN,"admin");account(OTHER,"other");session(ADMIN,sid,proof);session(OTHER,otherSid,proof);
        bearer=token(ADMIN,sid,proof);otherBearer=token(OTHER,otherSid,proof);
    }
    @Test void fixedLeastScopeOneUseTicketPkceBrowserBindingAndEncryptedCallback() throws Exception {
        mvc.perform(get(ROOT+"/config").header("Authorization",bearer)).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.scope").value(GoogleCalendarConfiguration.SCOPE)).andExpect(jsonPath("$.usesDedicatedCalendar").value(true));
        var start=start(null,null);var navigation=authorize(start);
        assertThat(navigation.url()).startsWith("https://accounts.google.com/o/oauth2/v2/auth?");
        assertThat(query(navigation.url(),"scope")).isEqualTo(GoogleCalendarConfiguration.SCOPE);
        assertThat(query(navigation.url(),"code_challenge_method")).isEqualTo("S256");assertThat(query(navigation.url(),"code_challenge")).hasSize(43);
        assertThat(query(navigation.url(),"include_granted_scopes")).isEqualTo("false");
        mvc.perform(get(ROOT+"/authorize").param("ticket",query(start.path("authorizationUrl").asText(),"ticket"))).andExpect(status().isConflict());
        mvc.perform(get(ROOT+"/callback").param("state",navigation.state()).param("code","private-code")).andExpect(status().isBadRequest());
        callback(navigation,"private-code");
        assertThat(count("select count(*) from integration_connection where status='ACTIVE'")).isZero();
        String cipher=jdbc.queryForObject("select code_ciphertext from integration_google_consent where id=?",String.class,UUID.fromString(start.path("id").asText()));
        assertThat(cipher).isNotBlank().doesNotContain("private-code");
        assertThat(jdbc.queryForObject("select state_hash from integration_google_consent where id=?",String.class,UUID.fromString(start.path("id").asText()))).isNull();
        mvc.perform(get(ROOT+"/callback").cookie(navigation.cookie()).param("state",navigation.state()).param("code","another-code")).andExpect(status().isConflict());
        var listed=mvc.perform(get(ROOT+"/consents").header("Authorization",bearer)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(listed).doesNotContain("private-code",navigation.state(),query(navigation.url(),"code_challenge"),"ticket=");
    }
    @Test void sameOriginalSignedJwtSessionFinishesAndCreatesDedicatedCalendarOnlyOnce() throws Exception {
        var start=start(null,null);var navigation=authorize(start);callback(navigation,"private-code");
        UUID second=UUID.randomUUID();session(ADMIN,second,proof);
        mvc.perform(post(ROOT+"/consents/"+start.path("id").asText()+"/complete").header("Authorization",token(ADMIN,second,proof))).andExpect(status().isForbidden()).andExpect(jsonPath("$.errorCode").value("CONSENT_SESSION_MISMATCH"));
        GOOGLE.reply(200,tokens(true,GoogleCalendarConfiguration.SCOPE));GOOGLE.reply(200,calendar(connection(start),"app@group.calendar.google.com"));
        var result=complete(start,200);
        assertThat(result.path("status").asText()).isEqualTo("ACTIVE");assertThat(result.path("provider").asText()).isEqualTo("GOOGLE_CALENDAR");
        assertThat(Instant.parse(result.path("credentialExpiresAt").asText())).isAfter(Instant.now().plusSeconds(89*86400L));
        assertThat(result.toString()).doesNotContain("private-access","private-refresh","ciphertext");
        assertThat(GOOGLE.requests()).hasSize(2);assertThat(GOOGLE.requests().getFirst().body()).contains("code=private-code","code_verifier=").doesNotContain("scope=");
        assertThat(GOOGLE.requests().get(1).body()).contains("BrainServe application calendar "+connection(start)).doesNotContain("admin@","visitor","purpose","attendees");
        String cipher=jdbc.queryForObject("select credential_ciphertext from integration_connection where id=?",String.class,connection(start));assertThat(cipher).doesNotContain("private-access","private-refresh");
        assertThat(secrets.convertToEntityAttribute(cipher)).contains("private-refresh");
        complete(start,409);assertThat(GOOGLE.requests()).hasSize(2);
        assertThat(count("select count(*) from flyway_schema_history where version='67' and success")).isEqualTo(1);
    }
    @Test void currentOwnerMfaSessionAndOwnershipFenceEveryConsentReadAndMutation() throws Exception {
        var start=start(null,null);var navigation=authorize(start);callback(navigation,"private-code");
        mvc.perform(get(ROOT+"/connections/"+connection(start)).header("Authorization",otherBearer)).andExpect(status().isNotFound());
        mvc.perform(post(ROOT+"/consents/"+start.path("id").asText()+"/complete").header("Authorization",otherBearer)).andExpect(status().isNotFound());
        String stale=token(ADMIN,sid,proof.minusSeconds(600));
        mvc.perform(post(ROOT+"/consents/"+start.path("id").asText()+"/complete").header("Authorization",stale)).andExpect(status().isForbidden()).andExpect(jsonPath("$.errorCode").value("MFA_STEP_UP_REQUIRED"));
        jdbc.update("update iam_refresh_token_session set revoked_at=now() where family_id=?",sid);
        complete(start,401);assertThat(GOOGLE.requests()).isEmpty();
        mvc.perform(get(ROOT+"/config")).andExpect(status().isUnauthorized());mvc.perform(post(ROOT+"/callback")).andExpect(status().isUnauthorized());
    }
    @ParameterizedTest @ValueSource(strings={"disabled","archived","role","permission","pending"})
    void currentEligibilityChangesStopFinalizationBeforeAnyProviderIo(String change) throws Exception {
        var start=start(null,null);var navigation=authorize(start);callback(navigation,"private-code");
        switch(change) {
            case "disabled" -> jdbc.update("update iam_user_account set enabled=false where id=?",ADMIN);
            case "archived" -> jdbc.update("update iam_user_account set archived=true,enabled=false,archived_at=now() where id=?",ADMIN);
            case "role" -> jdbc.update("update iam_user_role set role_name='ROLE_EMPLOYEE' where user_id=?",ADMIN);
            case "permission" -> jdbc.update("insert into iam_user_permission_deny(user_id,permission_name) values(?,'SYSTEM_CONFIGURE')",ADMIN);
            case "pending" -> jdbc.update("update iam_user_account set account_status='PENDING_APPROVAL' where id=?",ADMIN);
        }
        int status=mvc.perform(post(ROOT+"/consents/"+start.path("id").asText()+"/complete").header("Authorization",bearer)).andReturn().getResponse().getStatus();
        assertThat(status).isIn(401,403);assertThat(GOOGLE.requests()).isEmpty();
    }
    @Test void deniedAndExpiredCallbacksNeverActivateOrExchange() throws Exception {
        var denied=start(null,null);var navigation=authorize(denied);
        mvc.perform(get(ROOT+"/callback").cookie(navigation.cookie()).param("state",navigation.state()).param("error","access_denied private-provider-detail"))
                .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private-provider-detail"))));
        complete(denied,409);
        var expired=start(null,null);var expNav=authorize(expired);jdbc.update("update integration_google_consent set expires_at=now()-interval '1 second' where id=?",UUID.fromString(expired.path("id").asText()));
        mvc.perform(get(ROOT+"/callback").cookie(expNav.cookie()).param("state",expNav.state()).param("code","private-code")).andExpect(status().isConflict());
        assertThat(GOOGLE.requests()).isEmpty();assertThat(count("select count(*) from integration_connection where status='ACTIVE'")).isZero();
    }
    @Test void partialGrantMissingRefreshAndBroaderScopeNeverActivateAndDurablyRevoke() throws Exception {
        var start=ready();GOOGLE.reply(200,tokens(false,GoogleCalendarConfiguration.SCOPE));
        var result=complete(start,200);assertThat(result.path("status").asText()).isEqualTo("NEEDS_RECONNECT");assertThat(result.path("lastResultCode").asText()).isEqualTo("MISSING_REFRESH_TOKEN");
        assertThat(count("select count(*) from integration_google_revocation where status='PENDING'")).isEqualTo(1);assertThat(GOOGLE.requests()).hasSize(1);
        GOOGLE.reply(200,"");revocations.process(jdbc.queryForObject("select id from integration_google_revocation",UUID.class));
        assertThat(jdbc.queryForObject("select token_ciphertext from integration_google_revocation",String.class)).isEmpty();
        var broad=ready();GOOGLE.reply(200,tokens(true,"https://www.googleapis.com/auth/calendar"));
        var rejected=complete(broad,200);assertThat(rejected.path("lastResultCode").asText()).isEqualTo("SCOPE_UNVERIFIED");
        assertThat(count("select count(*) from integration_connection where status='ACTIVE'")).isZero();
    }
    @Test void unknownTokenExchangeIsCommittedConsumedAndCannotReplayTheCode() throws Exception {
        var start=ready();GOOGLE.reply(503,"private-error-token-detail");var result=complete(start,200);
        assertThat(result.path("status").asText()).isEqualTo("NEEDS_RECONNECT");assertThat(result.path("lastResultCode").asText()).isEqualTo("EXCHANGE_UNKNOWN");
        assertThat(jdbc.queryForObject("select status from integration_google_consent where id=?",String.class,UUID.fromString(start.path("id").asText()))).isEqualTo("EXCHANGE_UNKNOWN");
        assertThat(jdbc.queryForObject("select code_ciphertext from integration_google_consent where id=?",String.class,UUID.fromString(start.path("id").asText()))).isNull();
        complete(start,409);assertThat(GOOGLE.requests()).hasSize(1);assertThat(result.toString()).doesNotContain("private-error");
    }
    @Test void unknownCalendarInsertRequiresMarkerCheckedOperatorRecoveryAndNeverListsCalendars() throws Exception {
        var start=ready();GOOGLE.reply(200,tokens(true,GoogleCalendarConfiguration.SCOPE));GOOGLE.reply(503,"uncertain creation private-error");
        var result=complete(start,200);assertThat(result.path("lastResultCode").asText()).isEqualTo("PROVISIONING_UNKNOWN");
        UUID connection=connection(start);long version=result.path("version").asLong();
        GOOGLE.reply(200,calendar(UUID.randomUUID(),"foreign@group.calendar.google.com"));
        recover(connection,version,"foreign@group.calendar.google.com",409);
        assertThat(GOOGLE.requests()).hasSize(3);
        recover(connection,version,"https://evil.test/calendar",400);assertThat(GOOGLE.requests()).hasSize(3);
        GOOGLE.reply(200,calendar(connection,"app@group.calendar.google.com"));var recovered=recover(connection,version,"app@group.calendar.google.com",200);
        assertThat(recovered.path("status").asText()).isEqualTo("ACTIVE");assertThat(GOOGLE.requests()).extracting(GoogleCalendarTestServer.Request::path).noneMatch(path->path.contains("calendarList"));
    }
    @Test void remoteCalendarSuccessThenDatabaseFailureRetainsUnknownIntentAndPreventsDuplicateCreation() throws Exception {
        var start=ready();GOOGLE.reply(200,tokens(true,GoogleCalendarConfiguration.SCOPE));GOOGLE.reply(200,calendar(connection(start),"app@group.calendar.google.com"));
        jdbc.execute("create function fail_s12_calendar() returns trigger language plpgsql as $$ begin if new.provisioning_status='READY' then raise exception 's12 activation failure'; end if; return new; end $$");
        jdbc.execute("create trigger fail_s12_calendar before update on integration_google_calendar for each row execute function fail_s12_calendar()");
        try {complete(start,500);}finally {jdbc.execute("drop trigger fail_s12_calendar on integration_google_calendar");jdbc.execute("drop function fail_s12_calendar()");}
        assertThat(jdbc.queryForObject("select provisioning_status from integration_google_calendar where connection_id=?",String.class,connection(start))).isEqualTo("PROVISIONING_UNKNOWN");
        assertThat(jdbc.queryForObject("select credential_ciphertext is not null from integration_connection where id=?",Boolean.class,connection(start))).isTrue();
        complete(start,409);assertThat(GOOGLE.requests()).hasSize(2);
        long version=jdbc.queryForObject("select version from integration_connection where id=?",Long.class,connection(start));
        var retry=start(connection(start),version);var navigation=authorize(retry);callback(navigation,"new-code");GOOGLE.reply(200,tokens(true,GoogleCalendarConfiguration.SCOPE));
        complete(retry,200);assertThat(GOOGLE.requests()).hasSize(3);assertThat(GOOGLE.requests().stream().filter(request->request.method().equals("POST")&&request.path().equals("/calendar/v3/calendars")).count()).isEqualTo(1);
        long latest=jdbc.queryForObject("select version from integration_connection where id=?",Long.class,connection(start));
        recover(connection(start),latest,"app@group.calendar.google.com",429);assertThat(GOOGLE.requests()).hasSize(3);
        // Model the next account write window without waiting a minute or disabling the real filter.
        redis.delete("rate:account:{"+ADMIN+"}:integration-write");
        GOOGLE.reply(200,calendar(connection(start),"app@group.calendar.google.com"));recover(connection(start),latest,"app@group.calendar.google.com",200);
    }
    @Test void reconsentVerifiesRetainedCalendarAndRotatesCredentialGeneration() throws Exception {
        var connected=connected();UUID id=UUID.fromString(connected.path("id").asText());long generation=connected.path("credentialVersion").asLong();
        var consent=start(id,connected.path("version").asLong());var navigation=authorize(consent);callback(navigation,"new-code");
        GOOGLE.reply(200,tokens(true,GoogleCalendarConfiguration.SCOPE));GOOGLE.reply(200,calendar(id,"app@group.calendar.google.com"));var replacement=complete(consent,200);
        assertThat(replacement.path("credentialVersion").asLong()).isEqualTo(generation+1);
        assertThat(GOOGLE.requests().stream().filter(request->request.method().equals("POST")&&request.path().equals("/calendar/v3/calendars")).count()).isEqualTo(1);
        assertThat(GOOGLE.requests().getLast().method()).isEqualTo("GET");
    }
    @Test void workerCreatesDeterministicPrivateEventsRepairsSameRevisionDriftAndUsesEtags() throws Exception {
        var connected=connected();UUID id=UUID.fromString(connected.path("id").asText());long generation=connected.path("credentialVersion").asLong();String event="bs"+id.toString().replace("-","")+UUID.randomUUID().toString().replace("-","");
        Instant start=Instant.parse("2026-10-09T09:00:00Z"),end=start.plusSeconds(1800);
        GOOGLE.reply(404,"private-not-found");GOOGLE.reply(200,"{\"id\":\""+event+"\"}");
        assertThat(deliver(id,generation,event,1,"UPSERT",start,end).code()).isEqualTo("SUCCESS");
        var created=GOOGLE.requests().getLast();assertThat(created.body()).contains("BrainServe appointment","brainserveRevision","brainserveEvent").doesNotContain("visitor","purpose","attendees","email","private-refresh");
        GOOGLE.reply(200,eventBody(id,event,1,"Drifted remote summary",start,end),Map.of("ETag","\"etag-1\""));GOOGLE.reply(200,"{\"id\":\""+event+"\"}");
        assertThat(deliver(id,generation,event,1,"UPSERT",start,end).code()).isEqualTo("SUCCESS");assertThat(GOOGLE.requests().getLast().method()).isEqualTo("PUT");assertThat(GOOGLE.requests().getLast().ifMatch()).isEqualTo("\"etag-1\"");
        GOOGLE.reply(200,eventBody(id,event,2,"BrainServe appointment",start,end),Map.of("ETag","\"etag-2\""));int before=GOOGLE.requests().size();
        assertThat(deliver(id,generation,event,1,"DELETE",null,null).code()).isEqualTo("NEWER_REVISION");assertThat(GOOGLE.requests()).hasSize(before+1);
        GOOGLE.reply(200,eventBody(id,event,2,"BrainServe appointment",start,end),Map.of("ETag","\"etag-2\""));GOOGLE.reply(204,"");
        assertThat(deliver(id,generation,event,3,"DELETE",null,null).code()).isEqualTo("SUCCESS");assertThat(GOOGLE.requests().getLast().ifMatch()).isEqualTo("\"etag-2\"");
    }
    @Test void foreignUnmanagedEventAndStaleCredentialNeverMutateAndProviderErrorsAreSanitized() throws Exception {
        var connected=connected();UUID id=UUID.fromString(connected.path("id").asText());long generation=connected.path("credentialVersion").asLong();
        String event="bs123456789";Instant start=Instant.now(),end=start.plusSeconds(1800);
        GOOGLE.reply(200,"{\"id\":\"bs123456789\",\"summary\":\"Private unmanaged event\",\"etag\":\"etag\"}");
        assertThat(deliver(id,generation,event,1,"UPSERT",start,end).code()).isEqualTo("PERMANENT_FAILURE");int before=GOOGLE.requests().size();
        assertThat(deliver(id,generation-1,event,1,"UPSERT",start,end).code()).isEqualTo("REAUTH_REQUIRED");assertThat(GOOGLE.requests()).hasSize(before);
        GOOGLE.reply(429,"{\"error\":\"private error name token\"}",Map.of("Retry-After","120"));var rate=deliver(id,generation,event,1,"UPSERT",start,end);
        assertThat(rate.code()).isEqualTo("RATE_LIMITED");assertThat(rate.retryAfterSeconds()).isEqualTo(120);assertThat(rate.toString()).doesNotContain("private error");
    }
    @Test void expiredAccessTokenRefreshesWithoutChangingGenerationAndPersistsRotationEncrypted() throws Exception {
        var connected=connected();UUID id=UUID.fromString(connected.path("id").asText());long generation=connected.path("credentialVersion").asLong();
        var retained=json.readTree(secrets.convertToEntityAttribute(jdbc.queryForObject("select credential_ciphertext from integration_connection where id=?",String.class,id)));
        ((com.fasterxml.jackson.databind.node.ObjectNode)retained).put("expiresAt",Instant.now().minusSeconds(30).toString());
        jdbc.update("update integration_connection set credential_ciphertext=? where id=?",secrets.convertToDatabaseColumn(retained.toString()),id);
        GOOGLE.reply(200,tokens(true,GoogleCalendarConfiguration.SCOPE).replace("private-refresh","rotated-refresh"));GOOGLE.reply(404,"{}");
        assertThat(deliver(id,generation,"bs123456789",1,"DELETE",null,null).code()).isEqualTo("SUCCESS");
        assertThat(GOOGLE.requests().get(GOOGLE.requests().size()-2).body()).contains("grant_type=refresh_token","refresh_token=private-refresh");
        assertThat(jdbc.queryForObject("select credential_version from integration_connection where id=?",Long.class,id)).isEqualTo(generation);
        String encrypted=jdbc.queryForObject("select credential_ciphertext from integration_connection where id=?",String.class,id);assertThat(encrypted).doesNotContain("rotated-refresh");assertThat(secrets.convertToEntityAttribute(encrypted)).contains("rotated-refresh");
    }
    @Test void localDisconnectStopsWorkImmediatelyAndRemoteRevocationIsDurableBoundedAndRecoverable() throws Exception {
        var connected=connected();UUID id=UUID.fromString(connected.path("id").asText());long generation=connected.path("credentialVersion").asLong();
        int before=GOOGLE.requests().size();var revoked=integrations.revoke(ADMIN,id,connected.path("version").asLong());
        assertThat(revoked.status()).isEqualTo("REVOKED");assertThat(GOOGLE.requests()).hasSize(before);
        assertThat(jdbc.queryForObject("select credential_ciphertext from integration_connection where id=?",String.class,id)).isNull();
        assertThat(deliver(id,generation,"bs123456789",1,"DELETE",null,null).code()).isEqualTo("REAUTH_REQUIRED");assertThat(GOOGLE.requests()).hasSize(before);
        UUID job=jdbc.queryForObject("select id from integration_google_revocation where connection_id=?",UUID.class,id);
        for(int attempt=0;attempt<10;attempt++) {jdbc.update("update integration_google_revocation set next_attempt_at=now()-interval '1 second' where id=?",job);GOOGLE.reply(503,"private-revoke-error");revocations.process(job);}
        assertThat(jdbc.queryForObject("select status from integration_google_revocation where id=?",String.class,job)).isEqualTo("FAILED");
        mvc.perform(post(ROOT+"/connections/"+id+"/revocation/retry").header("Authorization",bearer).contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":"+revoked.version()+"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.revocationStatus").value("PENDING"));
        GOOGLE.reply(200,"");revocations.process(job);
        assertThat(jdbc.queryForObject("select token_ciphertext from integration_google_revocation where id=?",String.class,job)).isEmpty();
        assertThat(jdbc.queryForObject("select revocation_status from integration_google_calendar where connection_id=?",String.class,id)).isEqualTo("COMPLETE");
    }
    @Test void competingCompletionRequestsConsumeOnlyOneExchange() throws Exception {
        var start=ready();GOOGLE.reply(200,tokens(true,GoogleCalendarConfiguration.SCOPE));GOOGLE.reply(200,calendar(connection(start),"app@group.calendar.google.com"));
        try(var pool=Executors.newFixedThreadPool(2)) {
            CountDownLatch began=new CountDownLatch(1);
            var first=pool.submit(()->{began.await();return mvc.perform(post(ROOT+"/consents/"+start.path("id").asText()+"/complete").header("Authorization",bearer)).andReturn().getResponse().getStatus();});
            var second=pool.submit(()->{began.await();return mvc.perform(post(ROOT+"/consents/"+start.path("id").asText()+"/complete").header("Authorization",bearer)).andReturn().getResponse().getStatus();});began.countDown();
            assertThat(List.of(first.get(20,TimeUnit.SECONDS),second.get(20,TimeUnit.SECONDS))).containsExactlyInAnyOrder(200,409);
        }
        assertThat(GOOGLE.requests().stream().filter(request->request.path().equals("/token")).count()).isEqualTo(1);
    }
    @Test void realCaptureClaimCompletePipelinePersistsMappingAndOnlyWritesLatestApprovedState() throws Exception {
        var connected=connected();UUID id=UUID.fromString(connected.path("id").asText()),resource=UUID.randomUUID();
        String event="bs"+id.toString().replace("-","")+resource.toString().replace("-","");Instant start=Instant.parse("2026-10-09T09:00:00Z"),end=start.plusSeconds(1800);
        transactions.executeWithoutResult(tx->integrations.capture(resource,"APPOINTMENT_UPDATED",Instant.now(),Map.of("status","APPROVED","appointmentType","HR_VISIT","slotStart",start.toString(),"slotEnd",end.toString())));
        UUID delivery=jdbc.queryForObject("select id from integration_delivery where resource_id=?",UUID.class,resource);
        GOOGLE.reply(404,"{}");GOOGLE.reply(200,"{\"id\":\""+event+"\"}");var claim=integrations.claim(delivery,Instant.now()).orElseThrow();integrations.complete(claim,Instant.now());
        assertThat(jdbc.queryForObject("select status from integration_delivery where id=?",String.class,delivery)).isEqualTo("DELIVERED");
        assertThat(jdbc.queryForObject("select external_id from integration_external_mapping where connection_id=? and resource_id=?",String.class,id,resource)).isEqualTo(event);
        int before=GOOGLE.requests().size();integrations.complete(claim,Instant.now());assertThat(GOOGLE.requests()).hasSize(before);
        transactions.executeWithoutResult(tx->integrations.capture(resource,"APPOINTMENT_CANCELLED",Instant.now(),Map.of("status","CANCELLED","appointmentType","HR_VISIT","slotStart",start.toString(),"slotEnd",end.toString())));
        UUID cancellation=jdbc.queryForObject("select id from integration_delivery where resource_id=? and business_revision=2",UUID.class,resource);
        GOOGLE.reply(200,eventBody(id,event,1,"BrainServe appointment",start,end),Map.of("ETag","\"v1\""));GOOGLE.reply(204,"");integrations.complete(integrations.claim(cancellation,Instant.now()).orElseThrow(),Instant.now());
        assertThat(jdbc.queryForObject("select business_revision from integration_external_mapping where connection_id=? and resource_id=?",Long.class,id,resource)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select status from integration_delivery where id=?",String.class,cancellation)).isEqualTo("DELIVERED");
        assertThat(count("select count(*) from integration_simulator_receipt")).isZero();assertThat(count("select count(*) from integration_delivery_attempt where outcome='SUCCESS'")).isEqualTo(2);
        assertThat(GOOGLE.requests().getLast().path()).contains("sendUpdates=none");assertThat(GOOGLE.requests().getLast().ifMatch()).isEqualTo("\"v1\"");
    }
    @Test void managedCancelledEventsRestoreWithConfirmedStatusAndMinimalTombstonesRequireMappingAndEtag() throws Exception {
        var connected=connected();UUID id=UUID.fromString(connected.path("id").asText());long generation=connected.path("credentialVersion").asLong();String event="bs123456789";
        Instant start=Instant.parse("2026-10-09T09:00:00Z"),end=start.plusSeconds(1800);
        var cancelled=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(eventBody(id,event,1,"BrainServe appointment",start,end));cancelled.put("status","cancelled");
        GOOGLE.reply(200,cancelled.toString(),Map.of("ETag","\"deleted-v1\""));GOOGLE.reply(200,"{\"id\":\""+event+"\"}");
        assertThat(deliver(id,generation,event,1,"UPSERT",start,end).code()).isEqualTo("SUCCESS");assertThat(GOOGLE.requests().getLast().body()).contains("\"status\":\"confirmed\"");assertThat(GOOGLE.requests().getLast().ifMatch()).isEqualTo("\"deleted-v1\"");
        String tombstone="{\"id\":\""+event+"\",\"status\":\"cancelled\"}";GOOGLE.reply(200,tombstone,Map.of("ETag","\"deleted-v2\""));
        assertThat(deliver(id,generation,event,1,"UPSERT",start,end).code()).isEqualTo("PERMANENT_FAILURE");
        jdbc.update("insert into integration_external_mapping(connection_id,resource_id,external_id,business_revision,business_event_id,last_event_type) values(?,?,?,2,?,'APPOINTMENT_UPDATED')",id,UUID.randomUUID(),event,UUID.randomUUID());
        GOOGLE.reply(200,tombstone,Map.of("ETag","\"deleted-v2\""));assertThat(deliver(id,generation,event,1,"UPSERT",start,end).code()).isEqualTo("NEWER_REVISION");
        GOOGLE.reply(200,tombstone,Map.of("ETag","\"deleted-v2\""));GOOGLE.reply(200,"{\"id\":\""+event+"\"}");assertThat(deliver(id,generation,event,2,"UPSERT",start,end).code()).isEqualTo("SUCCESS");
        GOOGLE.reply(410,"{\"error\":{\"errors\":[{\"reason\":\"deleted\"}]}}");assertThat(deliver(id,generation,event,2,"UPSERT",start,end).code()).isEqualTo("PERMANENT_FAILURE");
    }
    @Test void quota403RetriesAndArbitraryRevocation400CannotClaimRemoteSuccess() throws Exception {
        var connected=connected();UUID id=UUID.fromString(connected.path("id").asText());long generation=connected.path("credentialVersion").asLong();
        GOOGLE.reply(403,"{\"error\":{\"message\":\"private name token\",\"errors\":[{\"reason\":\"userRateLimitExceeded\",\"message\":\"private provider detail\"}]}}",Map.of("Retry-After","90"));
        var quota=deliver(id,generation,"bs123456789",1,"DELETE",null,null);assertThat(quota.code()).isEqualTo("RATE_LIMITED");assertThat(quota.retryAfterSeconds()).isEqualTo(90);assertThat(quota.toString()).doesNotContain("private");
        integrations.revoke(ADMIN,id,connected.path("version").asLong());UUID job=jdbc.queryForObject("select id from integration_google_revocation where connection_id=?",UUID.class,id);
        GOOGLE.reply(400,"{\"error\":\"invalid_request\",\"error_description\":\"private token detail\"}");revocations.process(job);
        assertThat(jdbc.queryForObject("select status from integration_google_revocation where id=?",String.class,job)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("select token_ciphertext from integration_google_revocation where id=?",String.class,job)).isNotBlank().doesNotContain("private-refresh");
        jdbc.update("update integration_google_revocation set status='PENDING',next_attempt_at=now()-interval '1 second' where id=?",job);
        GOOGLE.reply(400,"{\"error\":\"invalid_token\",\"error_description\":\"private token detail\"}");revocations.process(job);
        assertThat(jdbc.queryForObject("select status from integration_google_revocation where id=?",String.class,job)).isEqualTo("COMPLETE");assertThat(jdbc.queryForObject("select token_ciphertext from integration_google_revocation where id=?",String.class,job)).isEmpty();
    }
    @Test void recoveryKeepsRotatedRefreshEvenWhenOperatorCalendarVerificationFails() throws Exception {
        var consent=ready();GOOGLE.reply(200,tokens(true,GoogleCalendarConfiguration.SCOPE));GOOGLE.reply(503,"{}");var unknown=complete(consent,200);
        UUID id=connection(consent);long version=unknown.path("version").asLong();
        var retained=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(secrets.convertToEntityAttribute(jdbc.queryForObject("select credential_ciphertext from integration_connection where id=?",String.class,id)));
        retained.put("expiresAt",Instant.now().minusSeconds(30).toString());jdbc.update("update integration_connection set credential_ciphertext=? where id=?",secrets.convertToDatabaseColumn(retained.toString()),id);
        GOOGLE.reply(200,tokens(true,GoogleCalendarConfiguration.SCOPE).replace("private-refresh","rotated-refresh"));GOOGLE.reply(200,calendar(UUID.randomUUID(),"foreign@group.calendar.google.com"));
        recover(id,version,"foreign@group.calendar.google.com",409);
        assertThat(secrets.convertToEntityAttribute(jdbc.queryForObject("select credential_ciphertext from integration_connection where id=?",String.class,id))).contains("rotated-refresh");
        assertThat(jdbc.queryForObject("select provisioning_status from integration_google_calendar where connection_id=?",String.class,id)).isEqualTo("PROVISIONING_UNKNOWN");
        GOOGLE.reply(200,calendar(id,"app@group.calendar.google.com"));var recovered=recover(id,version,"app@group.calendar.google.com",200);assertThat(recovered.path("status").asText()).isEqualTo("ACTIVE");
        assertThat(GOOGLE.requests().stream().filter(request->request.body().contains("grant_type=refresh_token")).count()).isEqualTo(1);
    }
    @Test void expiredConsentSecretsAreCleanedWhileNetworkWorkersAreDisabled() throws Exception {
        var consent=ready();UUID id=UUID.fromString(consent.path("id").asText());jdbc.update("update integration_google_consent set expires_at=now()-interval '1 second' where id=?",id);
        revocations.tick();
        assertThat(jdbc.queryForObject("select status from integration_google_consent where id=?",String.class,id)).isEqualTo("EXPIRED");
        assertThat(jdbc.queryForObject("select code_ciphertext from integration_google_consent where id=?",String.class,id)).isNull();
        assertThat(jdbc.queryForObject("select verifier_ciphertext from integration_google_consent where id=?",String.class,id)).isNull();assertThat(GOOGLE.requests()).isEmpty();
    }
    @Test void requestReceiptsRejectChangedConsentPayloadAndReplacementCancelsOriginalBrowserFlow() throws Exception {
        UUID request=UUID.randomUUID();String body=json.writeValueAsString(Map.of("requestId",request,"label","S12 Google calendar"));
        var original=json.readTree(mvc.perform(post(ROOT+"/consents").header("Authorization",bearer).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        var replay=json.readTree(mvc.perform(post(ROOT+"/consents").header("Authorization",bearer).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());assertThat(replay).isEqualTo(original);
        mvc.perform(post(ROOT+"/consents").header("Authorization",bearer).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("requestId",request,"label","Changed")))).andExpect(status().isConflict());
        var navigation=authorize(original);callback(navigation,"private-code");long version=jdbc.queryForObject("select version from integration_connection where id=?",Long.class,connection(original));
        start(connection(original),version);complete(original,409);assertThat(GOOGLE.requests()).isEmpty();
    }
    @Test void nullRevocationRetryPayloadIsRejectedBeforeProviderWork() throws Exception {
        var original=start(null,null);
        mvc.perform(post(ROOT+"/connections/"+connection(original)+"/revocation/retry").header("Authorization",bearer).contentType(MediaType.APPLICATION_JSON).content("null")).andExpect(status().isBadRequest());
        assertThat(GOOGLE.requests()).isEmpty();
    }
    private JsonNode start(UUID id,Long version) throws Exception {
        var command=new java.util.HashMap<String,Object>();command.put("requestId",UUID.randomUUID());command.put("label","S12 Google calendar");if(id!=null){command.put("connectionId",id);command.put("expectedVersion",version);}
        return json.readTree(mvc.perform(post(ROOT+"/consents").header("Authorization",bearer).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(command))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
    private Navigation authorize(JsonNode start) throws Exception {
        MvcResult result=mvc.perform(get(ROOT+"/authorize").param("ticket",query(start.path("authorizationUrl").asText(),"ticket"))).andExpect(status().isFound())
                .andExpect(header().string("Cache-Control","no-store")).andExpect(header().string("Referrer-Policy","no-referrer")).andReturn();
        String cookie=result.getResponse().getHeader("Set-Cookie");assertThat(cookie).contains("HttpOnly","Secure","SameSite=Lax","Path="+ROOT);
        String[] parts=cookie.split(";",2)[0].split("=",2);String url=result.getResponse().getHeader("Location");return new Navigation(url,query(url,"state"),new Cookie(parts[0],parts[1]));
    }
    private void callback(Navigation navigation,String code) throws Exception {
        mvc.perform(get(ROOT+"/callback").cookie(navigation.cookie()).param("state",navigation.state()).param("code",code)).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control","no-store")).andExpect(header().string("Referrer-Policy","no-referrer"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(code))));
    }
    private JsonNode ready() throws Exception {var start=start(null,null);var navigation=authorize(start);callback(navigation,"private-code");return start;}
    private JsonNode connected() throws Exception {var start=ready();GOOGLE.reply(200,tokens(true,GoogleCalendarConfiguration.SCOPE));GOOGLE.reply(200,calendar(connection(start),"app@group.calendar.google.com"));return complete(start,200);}
    private JsonNode complete(JsonNode start,int statusCode) throws Exception {return json.readTree(mvc.perform(post(ROOT+"/consents/"+start.path("id").asText()+"/complete").header("Authorization",bearer)).andExpect(status().is(statusCode)).andReturn().getResponse().getContentAsString());}
    private JsonNode recover(UUID id,long version,String calendar,int statusCode) throws Exception {return json.readTree(mvc.perform(post(ROOT+"/connections/"+id+"/recover").header("Authorization",bearer).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("expectedVersion",version,"calendarId",calendar)))).andExpect(status().is(statusCode)).andReturn().getResponse().getContentAsString());}
    private GoogleCalendarAdapter.Result deliver(UUID id,long generation,String event,long revision,String action,Instant start,Instant end) {
        return transactions.execute(tx->{jdbc.query("select id from iam_user_account where id=? for update",(rs,n)->rs.getObject(1),ADMIN);jdbc.query("select id from integration_connection where id=? for update",(rs,n)->rs.getObject(1),id);return adapter.deliver(id,generation,event,revision,action,start,end);});
    }
    private static UUID connection(JsonNode start) {return UUID.fromString(start.path("connectionId").asText());}
    private String calendar(UUID id,String calendar) throws Exception {return json.writeValueAsString(Map.of("id",calendar,"description","BrainServe application calendar "+id));}
    private String eventBody(UUID id,String event,long revision,String summary,Instant start,Instant end) throws Exception {return json.writeValueAsString(Map.of("id",event,"summary",summary,"start",Map.of("dateTime",start.toString()),"end",Map.of("dateTime",end.toString()),"extendedProperties",Map.of("private",Map.of("brainserveConnection",id.toString(),"brainserveEvent",event,"brainserveRevision",Long.toString(revision)))));}
    private static String tokens(boolean refresh,String scope) {return "{\"access_token\":\"private-access\",\"token_type\":\"Bearer\",\"expires_in\":3600,\"scope\":\""+scope+"\""+(refresh?",\"refresh_token\":\"private-refresh\"":"")+"}";}
    private static String query(String url,String name) {for(String part:URI.create(url).getRawQuery().split("&")){var pair=part.split("=",2);if(pair[0].equals(name))return URLDecoder.decode(pair[1],StandardCharsets.UTF_8);}throw new AssertionError("Missing query field "+name);}
    private long count(String sql,Object...args) {return jdbc.queryForObject(sql,Long.class,args);}
    private String token(UUID actor,UUID family,Instant verified) {return "Bearer "+jwt.issue(users.findById(actor).orElseThrow(),family,verified,true,true).value();}
    private void account(UUID id,String label) {
        jdbc.update("insert into iam_user_account(id,email,full_name,password_hash,enabled,force_password_change,account_status,archived,version,created_at,created_by,updated_at,updated_by) values(?,?,?,'test-only-hash',true,false,'ACTIVE',false,0,now(),'s12',now(),'s12')",id,label+"@s12.test",label);
        jdbc.update("insert into iam_user_role(user_id,role_name) values(?,'ROLE_SYSTEM_ADMIN')",id);
        jdbc.update("insert into iam_mfa_credential(user_id,secret_ciphertext,enrolled_at,last_accepted_step) values(?,?,now(),0)",id,secrets.convertToDatabaseColumn("S12TESTMFASECRET"));
    }
    private void session(UUID actor,UUID family,Instant verified) {jdbc.update("insert into iam_refresh_token_session(id,user_id,token_hash,family_id,expires_at,session_started_at,mfa_verified_at,version,created_at,created_by,updated_at,updated_by) values(?,?,?,?,now()+interval '1 day',now(),?,0,now(),'s12',now(),'s12')",UUID.randomUUID(),actor,UUID.randomUUID().toString().replace("-","")+UUID.randomUUID().toString().replace("-",""),family,Timestamp.from(verified));}
    private record Navigation(String url,String state,Cookie cookie) { @Override public String toString(){return "Navigation[REDACTED]";} }
}
