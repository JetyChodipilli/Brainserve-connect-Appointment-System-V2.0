package com.brainserve.appointment;

import com.brainserve.appointment.audit.api.AuditService;
import com.brainserve.appointment.document.infrastructure.ClamAvScanner;
import com.brainserve.appointment.iam.api.CurrentAccountAuthority;
import com.brainserve.appointment.iam.application.JwtService;
import com.brainserve.appointment.iam.infrastructure.UserAccountRepository;
import com.brainserve.appointment.shared.application.BusinessException;
import com.brainserve.appointment.shared.application.SensitiveStringConverter;
import com.brainserve.appointment.support.application.DiagnosticSnapshot;
import com.brainserve.appointment.support.application.DiagnosticSnapshotCodec;
import com.brainserve.appointment.support.application.SupportDiagnosticService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.services.s3.S3Client;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Real PostgreSQL packages, current account authority, strict allowlist, audit and real JWT HTTP boundary. */
@Testcontainers(disabledWithoutDocker = true)
@AutoConfigureMockMvc
@SpringBootTest(properties = {
        "brainserve.security.jwt-secret=test-only-secret-key-that-is-at-least-thirty-two-bytes",
        "brainserve.security.pii-encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "brainserve.bootstrap.system-admin-enabled=false", "brainserve.bootstrap.ceo-enabled=false",
        "brainserve.integrations.enabled=false", "brainserve.work-routines.enabled=false",
        "spring.kafka.listener.auto-startup=false", "brainserve.support.cleanup-ms=3600000",
        "brainserve.approval-reminders.poll-ms=3600000", "brainserve.notification.internal-call-dispatch-ms=3600000",
        "brainserve.notification.poll-ms=3600000", "aws.s3.access-key=test-access-key", "aws.s3.secret-key=test-secret-key",
        "brainserve.support.environment=STAGING", "brainserve.support.release-version=1.2.3",
        "brainserve.support.build-revision=0123456789abcdef0123456789abcdef01234567"
})
class Sprint11SupportPostgresIntegrationTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.2-alpine");
    @Container static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4.1-alpine").withExposedPorts(6379);
    @DynamicPropertySource static void infrastructure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl); registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword); registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired SupportDiagnosticService support;
    @Autowired DiagnosticSnapshotCodec codec;
    @Autowired CurrentAccountAuthority authority;
    @Autowired AuditService audit;
    @Autowired ObjectMapper mapper;
    @Autowired MockMvc mvc;
    @Autowired JwtService tokens;
    @Autowired UserAccountRepository users;
    @Autowired SensitiveStringConverter cipher;
    @MockitoBean S3Client s3;
    @MockitoBean ClamAvScanner scanner;
    private static final UUID ADMIN = UUID.fromString("11000000-0000-4000-a000-000000000001");
    private static final UUID OTHER = UUID.fromString("11000000-0000-4000-a000-000000000002");
    private static final UUID EMPLOYEE = UUID.fromString("11000000-0000-4000-a000-000000000003");
    private static final String PRIVATE = "Private-Person";
    private static final String EMAIL = "private.person@sprint11support.test";
    private static final String SECRET = "Bearer-live-secret-0123456789";
    private static final String URL = "https://private-credential@example.test";

    @BeforeEach void fixture() {
        jdbc.execute("truncate support_diagnostic_package,integration_connection,iam_user_account cascade");
        jdbc.execute("truncate audit_event cascade");
        account(ADMIN, "ROLE_SYSTEM_ADMIN"); account(OTHER, "ROLE_SYSTEM_ADMIN"); account(EMPLOYEE, "ROLE_EMPLOYEE");
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(ADMIN.toString(), null, List.of()));
        MDC.put("correlationId", "Private-Person-private.person@sprint11support.test");
    }
    @AfterEach void clearActor() { SecurityContextHolder.clearContext(); MDC.clear(); }

    @Test void previewUsesTypedMetadataAndCurrentStatusCountsWithinBoundedWindow() {
        UUID connection = connection("REVOKED");
        delivery(connection, "FAILED", Instant.now().minusSeconds(1800));
        delivery(connection, "DELIVERED", Instant.now().minusSeconds(2 * 3600));
        delivery(connection, "FAILED", Instant.now().minusSeconds(25 * 3600));
        var value = support.preview(ADMIN, 1);
        assertThat(value.environment()).isEqualTo(DiagnosticSnapshot.Environment.STAGING);
        assertThat(value.releaseVersion()).isEqualTo("1.2.3");
        assertThat(value.buildRevision()).matches("[a-f0-9]{40}");
        assertThat(value.migrationVersion()).isGreaterThanOrEqualTo(66);
        assertThat(value.connections().revoked()).isEqualTo(1);
        assertThat(value.deliveries().failed()).isEqualTo(1); assertThat(value.deliveries().delivered()).isZero();
        assertThat(Duration.between(value.windowStart(), value.windowEnd())).isEqualTo(Duration.ofHours(1));
        assertThat(count("select count(*) from support_diagnostic_package")).isZero();
    }

    @Test void seededPiiCredentialsRawConfigurationAndCorrelationStringsNeverEnterPreviewOrExport() {
        UUID connection = connection("ACTIVE"); delivery(connection, "FAILED", Instant.now().minusSeconds(30));
        String encrypted = jdbc.queryForObject("select credential_ciphertext from integration_connection where id=?", String.class, connection);
        assertThat(encrypted).doesNotContain(SECRET);
        jdbc.update("update system_setting set setting_value=? where setting_key='COMPANY.NAME'", PRIVATE + EMAIL + SECRET + URL);
        jdbc.update("insert into audit_event(id,occurred_at,actor_id,event_type,target_type,target_id,outcome,correlation_id,details_json) values(?,now(),?,'PRIVATE_EVENT','PRIVATE',?,'FAILURE',?,?::jsonb)", UUID.randomUUID(), EMAIL, PRIVATE, PRIVATE + EMAIL, "{\"password\":\"" + SECRET + "\",\"message\":\"" + URL + "\"}");
        String preview = new String(codec.encode(support.preview(ADMIN, 24)), StandardCharsets.UTF_8);
        var created = support.generate(ADMIN, request(24));
        String retained = jdbc.queryForObject("select snapshot_json::text from support_diagnostic_package where id=?", String.class, created.id());
        String downloaded = new String(support.download(ADMIN, created.id()).content(), StandardCharsets.UTF_8);
        for (String value : List.of(preview, retained, downloaded)) assertThat(value).doesNotContain(PRIVATE, EMAIL, SECRET, encrypted, URL, "label", "correlationId", "details_json", "credential", "ownerId", "payload_json", "message");
        assertThat(codec.decode(downloaded).supportReference().version()).isEqualTo(4);
        assertThat(downloaded.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(65_536);
    }

    @Test void poisonedAllowlistedRuntimeMetadataFallsBackToSafeUnknown() {
        var poisoned = new SupportDiagnosticService(jdbc, authority, audit, codec, "STAGING " + SECRET,
                "1.2.3-" + EMAIL, "a".repeat(40) + URL);
        var value = poisoned.preview(ADMIN, 24);
        assertThat(value.environment()).isEqualTo(DiagnosticSnapshot.Environment.UNKNOWN);
        assertThat(value.releaseVersion()).isEqualTo("UNKNOWN"); assertThat(value.buildRevision()).isEqualTo("UNKNOWN");
        assertThat(new String(codec.encode(value), StandardCharsets.UTF_8)).doesNotContain(SECRET, EMAIL, URL);
    }

    @Test void expiredCredentialsCountAsReconnectWhileRevokedConnectionsRemainRevoked() {
        connection("ACTIVE"); connection("NEEDS_RECONNECT");
        UUID expired = connection("ACTIVE"), revoked = connection("REVOKED");
        jdbc.update("update integration_connection set credential_expires_at=now()-interval '1 hour' where id in(?,?)", expired, revoked);
        var current = support.preview(ADMIN, 24).connections();
        assertThat(current.active()).isEqualTo(1); assertThat(current.needsReconnect()).isEqualTo(2); assertThat(current.revoked()).isEqualTo(1);
    }

    @Test void generationIsIdempotentAuditedOnceAndDownloadIsAuditedForEachSuccessfulAccess() throws Exception {
        var request = request(24); var first = support.generate(ADMIN, request);
        assertThat(support.generate(ADMIN, request)).isEqualTo(first);
        assertThat(count("select count(*) from support_diagnostic_package")).isEqualTo(1);
        assertThat(auditCount(first.id(), "SUPPORT_DIAGNOSTIC_GENERATED")).isEqualTo(1);
        support.download(ADMIN, first.id()); support.download(ADMIN, first.id());
        assertThat(support.list(ADMIN).getFirst().downloadCount()).isEqualTo(2);
        assertThat(auditCount(first.id(), "SUPPORT_DIAGNOSTIC_DOWNLOADED")).isEqualTo(2);
        // V57 preserves the actor in the internal audit envelope; diagnostics never export that envelope.
        for (String operation : jdbc.queryForList("select (details_json-'_activity')::text from audit_event where target_id=?", String.class, first.id().toString()))
            assertThat(mapper.readTree(operation)).isEqualTo(mapper.createObjectNode().put("schemaVersion", 1));
        assertThat(jdbc.queryForList("select details_json #>> '{_activity,actor,name}' from audit_event where target_id=?", String.class, first.id().toString())).containsOnly(PRIVATE);
        assertThat(jdbc.queryForList("select actor_id from audit_event where target_id=?", String.class, first.id().toString())).containsOnly(ADMIN.toString());
        code(() -> support.generate(ADMIN, new SupportDiagnosticService.Generate(request.requestId(), 1)), "SUPPORT_REQUEST_CONFLICT");
        code(() -> support.generate(OTHER, request), "SUPPORT_REQUEST_CONFLICT");
    }

    @Test void concurrentLostResponseRetriesCreateOnePackageAndOneAuditRecord() throws Exception {
        var request = request(24); var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<SupportDiagnosticService.Package> generate = () -> {
                start.await();
                SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(ADMIN.toString(), null, List.of()));
                try { return support.generate(ADMIN, request); } finally { SecurityContextHolder.clearContext(); }
            };
            var first = executor.submit(generate); var second = executor.submit(generate); start.countDown();
            var result = first.get(20, TimeUnit.SECONDS);
            assertThat(second.get(20, TimeUnit.SECONDS)).isEqualTo(result);
            assertThat(auditCount(result.id(), "SUPPORT_DIAGNOSTIC_GENERATED")).isEqualTo(1);
            assertThat(count("select count(*) from support_diagnostic_package")).isEqualTo(1);
        }
    }

    @Test void onlyCreatorWithCurrentActiveSystemAdminRoleCanListOrDownload() {
        var created = support.generate(ADMIN, request(24));
        assertThat(support.list(OTHER)).isEmpty();
        code(() -> support.download(OTHER, created.id()), "SUPPORT_PACKAGE_NOT_FOUND");
        code(() -> support.preview(EMPLOYEE, 24), "SUPPORT_SCOPE_DENIED");
        jdbc.update("update iam_user_role set role_name='ROLE_EMPLOYEE' where user_id=?", ADMIN);
        code(() -> support.download(ADMIN, created.id()), "SUPPORT_SCOPE_DENIED");
        code(() -> support.list(ADMIN), "SUPPORT_SCOPE_DENIED");
        assertThat(auditCount(created.id(), "SUPPORT_DIAGNOSTIC_DOWNLOADED")).isZero();
        assertThat(jdbc.queryForObject("select download_count from support_diagnostic_package where id=?", Integer.class, created.id())).isZero();
    }

    @Test void permissionRevocationAndDisabledOrArchivedCreatorFailClosed() {
        var created = support.generate(ADMIN, request(24));
        jdbc.update("insert into iam_user_permission_deny(user_id,permission_name) values(?,'SYSTEM_CONFIGURE')", ADMIN);
        code(() -> support.download(ADMIN, created.id()), "SUPPORT_SCOPE_DENIED");
        code(() -> support.generate(ADMIN, request(24)), "SUPPORT_SCOPE_DENIED");
        jdbc.update("delete from iam_user_permission_deny where user_id=?", ADMIN);
        jdbc.update("update iam_user_account set enabled=false where id=?", ADMIN);
        code(() -> support.download(ADMIN, created.id()), "ACCOUNT_INACTIVE");
        jdbc.update("update iam_user_account set enabled=false,archived=true,archived_at=now() where id=?", ADMIN);
        code(() -> support.preview(ADMIN, 24), "ACCOUNT_INACTIVE");
    }

    @Test void expiredPackagesCannotBeDownloadedOrRenewedByReplayingRequestAndCleanupIsBounded() {
        var request = request(24); var created = support.generate(ADMIN, request);
        assertThat(Duration.between(created.createdAt(), created.expiresAt())).isEqualTo(Duration.ofHours(24));
        jdbc.update("update support_diagnostic_package set created_at=now()-interval '25 hours',expires_at=now()-interval '1 hour' where id=?", created.id());
        assertThat(support.list(ADMIN)).isEmpty();
        code(() -> support.download(ADMIN, created.id()), "SUPPORT_PACKAGE_EXPIRED");
        code(() -> support.generate(ADMIN, request), "SUPPORT_PACKAGE_EXPIRED");
        assertThat(auditCount(created.id(), "SUPPORT_DIAGNOSTIC_DOWNLOADED")).isZero();
        assertThat(support.cleanupExpired()).isEqualTo(1); assertThat(support.cleanupExpired()).isZero();
        code(() -> support.generate(ADMIN, request), "SUPPORT_PACKAGE_EXPIRED");
        assertThat(count("select count(*) from support_diagnostic_package")).isZero();
    }

    @Test void poisonedRetainedJsonIsRejectedBeforeDownloadCountAndAudit() {
        var created = support.generate(ADMIN, request(24));
        jdbc.update("update support_diagnostic_package set snapshot_json=snapshot_json || ?::jsonb where id=?", "{\"details_json\":\"" + PRIVATE + SECRET + "\"}", created.id());
        code(() -> support.download(ADMIN, created.id()), "SUPPORT_SNAPSHOT_INVALID");
        assertThat(auditCount(created.id(), "SUPPORT_DIAGNOSTIC_DOWNLOADED")).isZero();
        assertThat(jdbc.queryForObject("select download_count from support_diagnostic_package where id=?", Integer.class, created.id())).isZero();
        jdbc.update("update support_diagnostic_package set snapshot_json=snapshot_json-'details_json' || '{\"releaseVersion\":\"private.person@example.test\"}'::jsonb where id=?", created.id());
        code(() -> support.download(ADMIN, created.id()), "SUPPORT_SNAPSHOT_INVALID");
    }

    @Test void timeWindowValidationAndActivePackageQuotaPreventUnboundedGeneration() {
        for (int hours : List.of(0, -1, 25, Integer.MAX_VALUE)) code(() -> support.preview(ADMIN, hours), "SUPPORT_REQUEST_INVALID");
        code(() -> support.generate(ADMIN, new SupportDiagnosticService.Generate(null, 24)), "SUPPORT_REQUEST_INVALID");
        code(() -> support.generate(ADMIN, new SupportDiagnosticService.Generate(UUID.randomUUID(), null)), "SUPPORT_REQUEST_INVALID");
        for (int i = 0; i < 20; i++) support.generate(ADMIN, request(24));
        assertThat(support.list(ADMIN)).hasSize(20);
        code(() -> support.generate(ADMIN, request(24)), "SUPPORT_PACKAGE_LIMIT");
        assertThat(count("select count(*) from support_diagnostic_package")).isEqualTo(20);
    }

    @Test void authenticatedHttpPreviewGenerationAndAttachmentHaveNoStoreAndCurrentMfa() throws Exception {
        String token = bearer(ADMIN, Instant.now());
        mvc.perform(get("/api/v1/support/diagnostics/preview").header("Authorization", token))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.schemaVersion").value(1)).andExpect(jsonPath("$.environment").value("STAGING"));
        String metadata = mvc.perform(post("/api/v1/support/diagnostics").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(request(24))))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store")).andReturn().getResponse().getContentAsString();
        String id = mapper.readTree(metadata).get("id").asText();
        mvc.perform(get("/api/v1/support/diagnostics/" + id + "/download").header("Authorization", token))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"brainserve-diagnostics-" + id + ".json\""))
                .andExpect(jsonPath("$.supportReference").isString()).andExpect(jsonPath("$.credentials").doesNotExist());
    }

    @Test void httpRequiresCurrentMfaAndRejectsRoleAndRequestTypeForgery() throws Exception {
        SecurityContextHolder.clearContext();
        // Method authorization uses the application's safe ACCESS_DENIED problem contract.
        mvc.perform(get("/api/v1/support/diagnostics/preview"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.errorCode").value("ACCESS_DENIED"))
                .andExpect(jsonPath("$.supportReference").doesNotExist()).andExpect(jsonPath("$.environment").doesNotExist())
                .andExpect(jsonPath("$.id").doesNotExist()).andExpect(jsonPath("$.sizeBytes").doesNotExist());
        assertThat(count("select count(*) from audit_event where event_type='SUPPORT_DIAGNOSTIC_GENERATED'")).isZero();
        mvc.perform(get("/api/v1/support/diagnostics/preview").header("Authorization", bearer(ADMIN, Instant.now().minusSeconds(3600))))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.errorCode").value("MFA_STEP_UP_REQUIRED"));
        String employeeToken = bearer(EMPLOYEE, Instant.now());
        mvc.perform(get("/api/v1/support/diagnostics/preview").header("Authorization", employeeToken)).andExpect(status().isForbidden());
        String token = bearer(ADMIN, Instant.now());
        mvc.perform(post("/api/v1/support/diagnostics").header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + UUID.randomUUID() + "\",\"hours\":1.5}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/support/diagnostics").header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + UUID.randomUUID() + "\",\"hours\":24,\"ownerId\":\"" + OTHER + "\"}"))
                .andExpect(status().isBadRequest());
        assertThat(count("select count(*) from support_diagnostic_package")).isZero();
    }

    private void account(UUID id, String role) {
        jdbc.update("insert into iam_user_account(id,email,full_name,password_hash,enabled,force_password_change,account_status,archived,version,created_at,created_by,updated_at,updated_by) values(?,?,?,'test-only-hash',true,false,'ACTIVE',false,0,now(),'sprint11-support',now(),'sprint11-support')", id, id + "@sprint11support.test", PRIVATE);
        jdbc.update("insert into iam_user_role(user_id,role_name) values(?,?)", id, role);
    }
    private UUID connection(String status) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into integration_connection(id,request_id,provider,kind,label,owner_id,minimum_scopes,status,credential_ciphertext,credential_expires_at,last_result_code) values(?,?,'SIMULATOR_CALENDAR','CALENDAR',?,?,'[]'::jsonb,?,?,now()+interval '1 day',?)", id, UUID.randomUUID(), PRIVATE + " " + EMAIL, ADMIN, status, "REVOKED".equals(status) ? null : cipher.convertToDatabaseColumn(SECRET), SECRET);
        return id;
    }
    private void delivery(UUID connection, String status, Instant occurredAt) {
        jdbc.update("insert into integration_delivery(id,connection_id,business_event_id,event_type,resource_id,business_revision,occurred_at,payload_json,status,last_result_code) values(?,?,?,'CONNECTION_TEST',?,0,?,?::jsonb,?,?)", UUID.randomUUID(), connection, UUID.randomUUID(), UUID.randomUUID(), Timestamp.from(occurredAt), "{\"visitorName\":\"" + PRIVATE + "\",\"email\":\"" + EMAIL + "\",\"credential\":\"" + SECRET + "\",\"url\":\"" + URL + "\"}", status, SECRET);
    }
    private String bearer(UUID user, Instant proof) {
        UUID family = UUID.randomUUID();
        jdbc.update("insert into iam_mfa_credential(user_id,secret_ciphertext,enrolled_at,last_accepted_step) values(?,?,now(),1) on conflict(user_id) do nothing", user, cipher.convertToDatabaseColumn("JBSWY3DPEHPK3PXP"));
        jdbc.update("insert into iam_refresh_token_session(id,user_id,token_hash,family_id,expires_at,mfa_verified_at,session_started_at,version,created_at,created_by,updated_at,updated_by) values(?,?,?,?,now()+interval '1 day',?,now(),0,now(),'sprint11-support',now(),'sprint11-support')", UUID.randomUUID(), user, UUID.randomUUID().toString().replace("-", ""), family, Timestamp.from(proof));
        return "Bearer " + tokens.issue(users.findById(user).orElseThrow(), family, proof, true, true).value();
    }
    private SupportDiagnosticService.Generate request(int hours) { return new SupportDiagnosticService.Generate(UUID.randomUUID(), hours); }
    private long count(String sql) { return jdbc.queryForObject(sql, Long.class); }
    private long auditCount(UUID id, String event) { return jdbc.queryForObject("select count(*) from audit_event where target_id=? and event_type=?", Long.class, id.toString(), event); }
    private void code(Runnable action, String expected) { assertThatThrownBy(action::run).isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo(expected); }
}
