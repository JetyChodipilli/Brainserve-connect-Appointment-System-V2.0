package com.brainserve.appointment;

import com.brainserve.appointment.configuration.api.ReleaseProfileModels;
import com.brainserve.appointment.configuration.application.ReleaseProfileService;
import com.brainserve.appointment.document.infrastructure.ClamAvScanner;
import com.brainserve.appointment.iam.application.JwtService;
import com.brainserve.appointment.iam.application.PermissionAdministrationService;
import com.brainserve.appointment.iam.domain.Permission;
import com.brainserve.appointment.iam.infrastructure.UserAccountRepository;
import com.brainserve.appointment.shared.application.BusinessException;
import com.brainserve.appointment.shared.application.SensitiveStringConverter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.services.s3.S3Client;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers(disabledWithoutDocker = true)
@AutoConfigureMockMvc
@SpringBootTest(properties = {
        "brainserve.security.jwt-secret=test-only-secret-key-that-is-at-least-thirty-two-bytes",
        "brainserve.security.pii-encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "brainserve.bootstrap.system-admin-enabled=false", "brainserve.bootstrap.ceo-enabled=false",
        "brainserve.integrations.enabled=false", "brainserve.work-routines.enabled=false", "brainserve.kiosk.enabled=false",
        "spring.kafka.listener.auto-startup=false", "brainserve.approval-reminders.poll-ms=3600000",
        "brainserve.notification.internal-call-dispatch-ms=3600000", "brainserve.notification.poll-ms=3600000",
        "aws.s3.access-key=test-access-key", "aws.s3.secret-key=test-secret-key", "brainserve.appointment.office-zone=Asia/Kolkata"
})
class Sprint16ReleasePostgresIntegrationTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.2-alpine");
    @Container static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4.1-alpine").withExposedPorts(6379);
    @DynamicPropertySource static void infrastructure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl); registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword); registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired ReleaseProfileService service;
    @Autowired ObjectMapper mapper;
    @Autowired MockMvc mvc;
    @Autowired JwtService tokens;
    @Autowired JwtDecoder decoder;
    @Autowired PermissionAdministrationService permissions;
    @Autowired UserAccountRepository users;
    @Autowired SensitiveStringConverter cipher;
    @MockitoBean S3Client s3;
    @MockitoBean ClamAvScanner scanner;
    private static final UUID ADMIN = UUID.fromString("16000000-0000-4000-a000-000000000001");
    private static final UUID CEO = UUID.fromString("16000000-0000-4000-a000-000000000002");
    private static final UUID EMPLOYEE = UUID.fromString("16000000-0000-4000-a000-000000000003");
    private static final String EMPTY = "{\"status\":\"UNCONFIGURED\",\"reference\":\"\",\"startsOn\":null,\"renewsOn\":null,\"supportOwner\":\"\",\"supportEmail\":\"\",\"supportHours\":\"\"}";
    @BeforeEach void fixture() {
        jdbc.execute("truncate iam_user_account cascade"); jdbc.execute("truncate audit_event cascade");
        jdbc.update("update release_profile set profile_json=?,version=0 where id=?", EMPTY, ReleaseProfileService.ID);
        account(ADMIN, "ROLE_SYSTEM_ADMIN"); account(CEO, "ROLE_CEO"); account(EMPLOYEE, "ROLE_EMPLOYEE");
        actor();
    }
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }

    @Test void initialHttpRecordHasExplicitNullDatesAndNoStoreWithoutInventedAgreement() throws Exception {
        var result = mvc.perform(get("/api/v1/release-profile").header("Authorization", bearer(ADMIN, Instant.now())))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.profile.status").value("UNCONFIGURED")).andExpect(jsonPath("$.profile.reference").value(""))
                .andExpect(jsonPath("$.version").value(0)).andExpect(jsonPath("$.officeZone").value("Asia/Kolkata"))
                .andReturn().getResponse().getContentAsString();
        var profile = mapper.readTree(result).get("profile");
        assertThat(profile.has("startsOn")).isTrue(); assertThat(profile.get("startsOn").isNull()).isTrue();
        assertThat(profile.has("renewsOn")).isTrue(); assertThat(profile.get("renewsOn").isNull()).isTrue();
    }
    @Test void persistedTermIsVersionedAuditedAndRenewalDueUsesTheOfficeDate() {
        var first = service.update(new ReleaseProfileModels.Write(0L, profile("AGREEMENT-1", ReleaseProfileModels.Status.ACTIVE)));
        assertThat(first.version()).isEqualTo(1); assertThat(first.renewalDue()).isTrue();
        assertThat(service.read().profile()).isEqualTo(first.profile());
        assertThat(first.officeDate()).isEqualTo(LocalDate.now(ZoneId.of("Asia/Kolkata")));
        assertThat(audits()).isEqualTo(1);
        String audit = jdbc.queryForObject("select details_json::text from audit_event where event_type='RELEASE_PROFILE_UPDATED'", String.class);
        assertThat(audit).doesNotContain("AGREEMENT-1", "Synthetic Support Owner", "support@sprint16.invalid");
        assertThatThrownBy(() -> service.update(new ReleaseProfileModels.Write(0L, profile("STALE", ReleaseProfileModels.Status.PAUSED))))
                .isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo("RELEASE_PROFILE_CHANGED");
        assertThat(audits()).isEqualTo(1); assertThat(service.read().profile().reference()).isEqualTo("AGREEMENT-1");
    }
    @Test void simultaneousAdministratorsCannotOverwriteTheSameObservedVersion() throws Exception {
        var gate = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> race(gate, "FIRST")); var second = pool.submit(() -> race(gate, "SECOND"));
            gate.countDown();
            assertThat(List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS))).containsExactlyInAnyOrder("SAVED", "RELEASE_PROFILE_CHANGED");
        }
        assertThat(service.read().version()).isEqualTo(1); assertThat(audits()).isEqualTo(1);
    }
    @Test void partialDatesEmptySupportAndInvalidEmailFailWithoutWrites() {
        var base = profile("INVALID", ReleaseProfileModels.Status.ACTIVE);
        for (var bad : List.of(
                new ReleaseProfileModels.Profile(base.status(), base.reference(), base.startsOn(), null, base.supportOwner(), base.supportEmail(), base.supportHours()),
                new ReleaseProfileModels.Profile(base.status(), base.reference(), base.renewsOn(), base.startsOn(), base.supportOwner(), base.supportEmail(), base.supportHours()),
                new ReleaseProfileModels.Profile(base.status(), base.reference(), base.startsOn(), base.renewsOn(), " ", base.supportEmail(), base.supportHours()),
                new ReleaseProfileModels.Profile(base.status(), base.reference(), base.startsOn(), base.renewsOn(), base.supportOwner(), "bad-email", base.supportHours()))) {
            assertThatThrownBy(() -> service.update(new ReleaseProfileModels.Write(0L, bad))).isInstanceOf(BusinessException.class);
        }
        assertThat(service.read().profile().status()).isEqualTo(ReleaseProfileModels.Status.UNCONFIGURED); assertThat(audits()).isZero();
    }
    @Test void roleMfaAndUnknownFieldsCannotBypassTheProtectedHttpContract() throws Exception {
        SecurityContextHolder.clearContext();
        mvc.perform(get("/api/v1/release-profile").header("Authorization", bearer(ADMIN, Instant.now().minusSeconds(3600))))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.errorCode").value("MFA_STEP_UP_REQUIRED"));
        for (UUID user : List.of(CEO, EMPLOYEE)) {
            mvc.perform(get("/api/v1/release-profile").header("Authorization", bearer(user, Instant.now()))).andExpect(status().isForbidden());
            mvc.perform(put("/api/v1/release-profile").header("Authorization", bearer(user, Instant.now())).contentType(MediaType.APPLICATION_JSON).content(body(0)))
                    .andExpect(status().isForbidden());
        }
        String token = bearer(ADMIN, Instant.now());
        for (String bad : List.of(body(0).replace("\"expectedVersion\":0", "\"expectedVersion\":0.5"),
                body(0).replace("\"expectedVersion\":0", "\"expectedVersion\":\"0\""),
                body(0) + "{}",
                body(0).replace("\"reference\":\"SYNTHETIC\"", "\"reference\":123"),
                body(0).replace("\"profile\":{", "\"profile\":{\"enabled\":true,"))) {
            mvc.perform(put("/api/v1/release-profile").header("Authorization", token).contentType(MediaType.APPLICATION_JSON).content(bad))
                    .andExpect(status().isBadRequest());
        }
        assertThat(service.read().version()).isZero(); assertThat(audits()).isZero();
    }
    @Test void genericSettingsCannotReadOrOverwriteThePrivateProfile() throws Exception {
        String token = bearer(ADMIN, Instant.now());
        mvc.perform(get("/api/v1/system-settings").header("Authorization", token)).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.key == 'RELEASE.MANUAL_PROFILE')]").isEmpty());
        mvc.perform(get("/api/v1/workspace-settings").header("Authorization", bearer(CEO, Instant.now()))).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.key == 'RELEASE.MANUAL_PROFILE')]").isEmpty());
        for (String path : List.of("/api/v1/system-settings/", "/api/v1/workspace-settings/")) {
            mvc.perform(put(path + "RELEASE.MANUAL_PROFILE").header("Authorization", token).contentType(MediaType.APPLICATION_JSON).content("{\"value\":\"bypass\"}"))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.errorCode").value("SETTING_NOT_FOUND"));
        }
        assertThat(service.read().version()).isZero();
    }

    @Test void quotedStatusOrdinalsCannotSilentlyChangeAgreementState() throws Exception {
        SecurityContextHolder.clearContext();
        String token = bearer(ADMIN, Instant.now());
        for (String ordinal : List.of("2", "4")) {
            mvc.perform(put("/api/v1/release-profile").header("Authorization", token)
                    .contentType(MediaType.APPLICATION_JSON).content(body(0).replace("\"ACTIVE\"", "\"" + ordinal + "\"")))
                    .andExpect(status().isBadRequest());
        }
        assertThat(service.read().version()).isZero(); assertThat(audits()).isZero();
    }

    @Test void apiDocumentationDeclaresTheRequiredTypedUpdateBody() throws Exception {
        SecurityContextHolder.clearContext();
        mvc.perform(get("/api-docs")).andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/release-profile'].put.requestBody.required").value(true))
                .andExpect(jsonPath("$.paths['/api/v1/release-profile'].put.requestBody.content['application/json'].schema['$ref']").value("#/components/schemas/ReleaseProfileWrite"))
                .andExpect(jsonPath("$.components.schemas.ReleaseProfileWrite.properties.expectedVersion.type").value("integer"))
                .andExpect(jsonPath("$.components.schemas.ReleaseProfileWrite.properties.profile['$ref']").value("#/components/schemas/ReleaseAgreementProfile"))
                .andExpect(jsonPath("$.components.schemas.ReleaseAgreementProfile.properties.supportEmail.type").value("string"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"grant", "deny", "clear"})
    void existingPermissionFlushLocksTheAccountBeforeChangingItsCollections(String change) throws Exception {
        UUID otherAdmin = UUID.fromString("16000000-0000-4000-a000-000000000004");
        account(otherAdmin, "ROLE_SYSTEM_ADMIN");
        if (change.equals("clear")) jdbc.update("insert into iam_user_permission_grant(user_id,permission_name) values(?,'WORK_TASK_READ')", ADMIN);
        long version = jdbc.queryForObject("select version from iam_user_account where id=?", Long.class, ADMIN);
        long originalRows = jdbc.queryForObject("select (select count(*) from iam_user_permission_grant where user_id=?) + (select count(*) from iam_user_permission_deny where user_id=?)", Long.class, ADMIN, ADMIN);
        try (var connection = jdbc.getDataSource().getConnection(); var pool = Executors.newSingleThreadExecutor()) {
            connection.setAutoCommit(false);
            try (var lock = connection.prepareStatement("select id from iam_user_account where id=? for update")) {
                lock.setObject(1, ADMIN); lock.executeQuery().close();
            }
            var result = pool.submit(() -> {
                try {
                    permissions.replaceOverrides(otherAdmin, ADMIN,
                            change.equals("grant") ? Set.of(Permission.WORK_TASK_READ) : Set.of(),
                            change.equals("deny") ? Set.of(Permission.SYSTEM_CONFIGURE) : Set.of());
                    return true;
                } finally { SecurityContextHolder.clearContext(); }
            });
            try {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                boolean waiting = false;
                while (System.nanoTime() < deadline && !result.isDone()) {
                    waiting = Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from pg_stat_activity where pid<>pg_backend_pid() and wait_event_type='Lock' and query like '%update iam_user_account%')", Boolean.class));
                    if (waiting) break;
                    Thread.sleep(25);
                }
                assertThat(waiting).as("permission flush waits on its owner UPDATE before collection actions").isTrue();
                assertThat(jdbc.queryForObject("select (select count(*) from iam_user_permission_grant where user_id=?) + (select count(*) from iam_user_permission_deny where user_id=?)", Long.class, ADMIN, ADMIN)).isEqualTo(originalRows);
            } finally { connection.rollback(); }
            assertThat(result.get(15, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(jdbc.queryForObject("select version from iam_user_account where id=?", Long.class, ADMIN)).isEqualTo(version + 1);
        assertThat(jdbc.queryForObject("select count(*) from iam_user_permission_grant where user_id=?", Long.class, ADMIN)).isEqualTo(change.equals("grant") ? 1L : 0L);
        assertThat(jdbc.queryForObject("select count(*) from iam_user_permission_deny where user_id=?", Long.class, ADMIN)).isEqualTo(change.equals("deny") ? 1L : 0L);
        assertThat(service.read().version()).isZero(); assertThat(audits()).isZero();
    }

    @Test void oversizedJsonIsRejectedForDeclaredAndUnknownBodyLengths() throws Exception {
        SecurityContextHolder.clearContext();
        String token = bearer(ADMIN, Instant.now()), oversized = body(0) + " ".repeat(8193);
        mvc.perform(put("/api/v1/release-profile").header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON).content(oversized))
                .andExpect(status().isPayloadTooLarge());
        mvc.perform(context -> {
            var request = new MockHttpServletRequest(context) {
                @Override public long getContentLengthLong() { return -1; }
                @Override public int getContentLength() { return -1; }
            };
            request.setMethod("PUT"); request.setRequestURI("/api/v1/release-profile");
            request.addHeader("Authorization", token); request.setContentType(MediaType.APPLICATION_JSON_VALUE);
            request.setContent(oversized.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return request;
        }).andExpect(status().isPayloadTooLarge());
        assertThat(service.read().version()).isZero(); assertThat(audits()).isZero();
        mvc.perform(put("/api/v1/release-profile").header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON).content(body(0))).andExpect(status().isOk());
        assertThat(service.read().version()).isEqualTo(1); assertThat(audits()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"disabled", "permissionDenied", "revoked", "proofCleared"})
    void authorizationChangedWhileWaitingForTheProfileLockCannotCommit(String change) throws Exception {
        SecurityContextHolder.clearContext();
        String token = bearer(ADMIN, Instant.now()), requestBody = body(0);
        try (var connection = jdbc.getDataSource().getConnection(); var pool = Executors.newSingleThreadExecutor()) {
            connection.setAutoCommit(false);
            try (var lock = connection.prepareStatement("select id from release_profile where id=? for update")) {
                lock.setObject(1, ReleaseProfileService.ID); lock.executeQuery().close();
            }
            var response = pool.submit(() -> {
                try {
                    return mvc.perform(put("/api/v1/release-profile").header("Authorization", token)
                            .contentType(MediaType.APPLICATION_JSON).content(requestBody)).andReturn().getResponse().getStatus();
                } finally { SecurityContextHolder.clearContext(); }
            });
            try {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                boolean waiting = false;
                while (System.nanoTime() < deadline && !response.isDone()) {
                    waiting = Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from pg_stat_activity where pid<>pg_backend_pid() and wait_event_type='Lock' and query like '%release_profile%')", Boolean.class));
                    if (waiting) break;
                    Thread.sleep(25);
                }
                assertThat(waiting).as("PUT reached the profile lock after its admission security check").isTrue();
                switch (change) {
                    case "disabled" -> jdbc.update("update iam_user_account set enabled=false where id=?", ADMIN);
                    case "permissionDenied" -> {
                        UUID otherAdmin = UUID.fromString("16000000-0000-4000-a000-000000000004");
                        account(otherAdmin, "ROLE_SYSTEM_ADMIN");
                        permissions.replaceOverrides(otherAdmin, ADMIN, Set.of(), Set.of(Permission.SYSTEM_CONFIGURE));
                    }
                    case "revoked" -> jdbc.update("update iam_refresh_token_session set revoked_at=now() where user_id=?", ADMIN);
                    case "proofCleared" -> jdbc.update("update iam_refresh_token_session set mfa_verified_at=null where user_id=?", ADMIN);
                    default -> throw new IllegalArgumentException(change);
                }
            } finally { connection.rollback(); }
            assertThat(response.get(15, TimeUnit.SECONDS)).isIn(401, 403);
        }
        assertThat(service.read().version()).isZero(); assertThat(audits()).isZero();
    }

    @Test void encodedMvcRoutesStillRequireRecentProof() throws Exception {
        SecurityContextHolder.clearContext();
        var path = java.net.URI.create("/api/v1/release%2Dprofile");
        String old = bearer(ADMIN, Instant.now().minusSeconds(3600));
        mvc.perform(get(path).header("Authorization", old)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("MFA_STEP_UP_REQUIRED"));
        mvc.perform(put(path).header("Authorization", old).contentType(MediaType.APPLICATION_JSON).content(body(0)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.errorCode").value("MFA_STEP_UP_REQUIRED"));
        mvc.perform(get(path).header("Authorization", bearer(ADMIN, Instant.now()))).andExpect(status().isOk());
        assertThat(audits()).isZero();
    }
    @Test void cancellationLeavesSessionsAndServerFeatureConfigurationIndependent() throws Exception {
        String admin = bearer(ADMIN, Instant.now()), employee = bearer(EMPLOYEE, Instant.now());
        mvc.perform(put("/api/v1/release-profile").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(new ReleaseProfileModels.Write(0L, profile("CANCELLED", ReleaseProfileModels.Status.CANCELLED)))))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store")).andExpect(jsonPath("$.profile.status").value("CANCELLED"));
        mvc.perform(get("/api/v1/auth/me").header("Authorization", employee)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/admin/kiosks/config").header("Authorization", admin)).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store")).andExpect(jsonPath("$.enabled").value(false));
        mvc.perform(get("/api/v1/admin/kiosks/config").header("Authorization", bearer(CEO, Instant.now()))).andExpect(status().isForbidden());
        var diagnostic = mvc.perform(get("/api/v1/support/diagnostics/preview").header("Authorization", admin)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(diagnostic).doesNotContain("support@sprint16.invalid", "Synthetic Support Owner", "CANCELLED");
    }
    private String race(CountDownLatch gate, String reference) throws Exception {
        actor(); gate.await(10, TimeUnit.SECONDS);
        try { service.update(new ReleaseProfileModels.Write(0L, profile(reference, ReleaseProfileModels.Status.ACTIVE))); return "SAVED"; }
        catch (BusinessException problem) { return problem.getErrorCode(); }
        finally { SecurityContextHolder.clearContext(); }
    }
    private void actor() {
        var jwt = decoder.decode(bearer(ADMIN, Instant.now()).substring(7));
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt,
                jwt.getClaimAsStringList("authorities").stream().map(SimpleGrantedAuthority::new).toList()));
    }
    private ReleaseProfileModels.Profile profile(String reference, ReleaseProfileModels.Status status) {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        return new ReleaseProfileModels.Profile(status, reference, today.minusDays(30), today.minusDays(1), "Synthetic Support Owner", "support@sprint16.invalid", "Mon–Fri 09:00–17:00 Asia/Kolkata");
    }
    private String body(long version) throws Exception { return mapper.writeValueAsString(new ReleaseProfileModels.Write(version, profile("SYNTHETIC", ReleaseProfileModels.Status.ACTIVE))); }
    private long audits() { return jdbc.queryForObject("select count(*) from audit_event where event_type='RELEASE_PROFILE_UPDATED'", Long.class); }
    private void account(UUID id, String role) {
        jdbc.update("insert into iam_user_account(id,email,full_name,password_hash,enabled,force_password_change,account_status,archived,version,created_at,created_by,updated_at,updated_by) values(?,?,?,'test-only-hash',true,false,'ACTIVE',false,0,now(),'sprint16',now(),'sprint16')", id, id + "@sprint16.invalid", "Synthetic release tester");
        jdbc.update("insert into iam_user_role(user_id,role_name) values(?,?)", id, role);
    }
    private String bearer(UUID user, Instant proof) {
        UUID family = UUID.randomUUID();
        jdbc.update("insert into iam_mfa_credential(user_id,secret_ciphertext,enrolled_at,last_accepted_step) values(?,?,now(),1) on conflict(user_id) do nothing", user, cipher.convertToDatabaseColumn("JBSWY3DPEHPK3PXP"));
        jdbc.update("insert into iam_refresh_token_session(id,user_id,token_hash,family_id,expires_at,mfa_verified_at,session_started_at,version,created_at,created_by,updated_at,updated_by) values(?,?,?,?,now()+interval '1 day',?,now(),0,now(),'sprint16',now(),'sprint16')", UUID.randomUUID(), user, UUID.randomUUID().toString().replace("-", ""), family, Timestamp.from(proof));
        return "Bearer " + tokens.issue(users.findById(user).orElseThrow(), family, proof, true, true).value();
    }
}
