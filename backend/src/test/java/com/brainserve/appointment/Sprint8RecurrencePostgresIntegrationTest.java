package com.brainserve.appointment;

import com.brainserve.appointment.document.infrastructure.ClamAvScanner;
import com.brainserve.appointment.shared.application.BusinessException;
import com.brainserve.appointment.workroutine.application.WorkRoutineService;
import com.brainserve.appointment.worktask.application.TaskPlanningService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.services.s3.S3Client;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Independent acceptance against PostgreSQL/Redis and the production public business APIs. */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "brainserve.security.jwt-secret=test-only-secret-key-that-is-at-least-thirty-two-bytes",
        "brainserve.security.pii-encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "brainserve.bootstrap.system-admin-enabled=false", "brainserve.bootstrap.ceo-enabled=false",
        "brainserve.appointment.office-zone=Asia/Kolkata", "spring.task.scheduling.enabled=false",
        "brainserve.work-routines.enabled=false",
        "spring.kafka.listener.auto-startup=false",
        "brainserve.notification.internal-call-dispatch-ms=3600000", "brainserve.notification.poll-ms=3600000",
        "aws.s3.access-key=test-access-key", "aws.s3.secret-key=test-secret-key"
})
class Sprint8RecurrencePostgresIntegrationTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.2-alpine");
    @Container static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4.1-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired WorkRoutineService routines;
    @Autowired TaskPlanningService planning;
    @Autowired ApplicationContext application;
    @Autowired @Qualifier("notificationExecutor") Executor notificationExecutor;
    @MockitoBean S3Client s3;
    @MockitoBean ClamAvScanner scanner;

    private static final ZoneId OFFICE = ZoneId.of("Asia/Kolkata");
    private static final UUID DEPT = id(101), OTHER_DEPT = id(102);
    private static final UUID EMP = id(201), LEAD_EMP = id(202), HR_EMP = id(203), OTHER_EMP = id(204),
            OTHER_LEAD_EMP = id(205), MANAGER_EMP = id(206);
    private static final UUID USER = id(1), LEAD = id(2), HR = id(3), ADMIN = id(4), OTHER = id(5),
            OTHER_LEAD = id(6), MANAGER = id(7), RECEPTION = id(8), SECURITY = id(9), CEO = id(10);
    private LocalDate today;

    @BeforeEach
    void fixture() {
        drainNotifications();
        org.mockito.Mockito.reset(s3, scanner);
        // These containers belong only to this test class. TRUNCATE also resets retained facts
        // with no task FK; ordinary deletion would violate their intentional immutability.
        jdbc.execute("truncate work_routine_template, work_routine_schedule, work_routine_occurrence, "
                + "work_routine_template_version, work_routine_notice_receipt cascade");
        jdbc.execute("truncate stored_document, workboard_preference, work_task_audit_record, "
                + "department_work_task, work_original_commitment, internal_call_notification cascade");
        jdbc.execute("truncate department_hr_assignment, department_team_lead, department_manager_assignment, "
                + "iam_user_account, employee, org_department cascade");
        today = LocalDate.now(OFFICE);
        department(DEPT, "S8_MAIN");
        department(OTHER_DEPT, "S8_OTHER");
        employee(EMP, DEPT, "employee"); employee(LEAD_EMP, DEPT, "lead"); employee(HR_EMP, DEPT, "hr");
        employee(OTHER_EMP, OTHER_DEPT, "other"); employee(OTHER_LEAD_EMP, OTHER_DEPT, "otherlead");
        employee(MANAGER_EMP, DEPT, "manager");
        account(USER, EMP, "employee", "ROLE_EMPLOYEE"); account(LEAD, LEAD_EMP, "lead", "ROLE_TEAM_LEAD");
        account(HR, HR_EMP, "hr", "ROLE_HR_ADMIN"); account(OTHER, OTHER_EMP, "other", "ROLE_EMPLOYEE");
        account(OTHER_LEAD, OTHER_LEAD_EMP, "otherlead", "ROLE_TEAM_LEAD");
        account(MANAGER, MANAGER_EMP, "manager", "ROLE_MANAGER"); account(ADMIN, null, "admin", "ROLE_SYSTEM_ADMIN");
        account(RECEPTION, null, "reception", "ROLE_RECEPTIONIST"); account(SECURITY, null, "security", "ROLE_SECURITY");
        account(CEO, null, "ceo", "ROLE_CEO");
        assignment("department_team_lead", "team_lead", DEPT, LEAD, LEAD_EMP);
        assignment("department_team_lead", "team_lead", OTHER_DEPT, OTHER_LEAD, OTHER_LEAD_EMP);
        assignment("department_hr_assignment", "hr", DEPT, HR, HR_EMP);
    }

    @AfterEach
    void finish() { drainNotifications(); }

    @Test
    void additiveMigrationAndDatabaseGuardsRetainVersionsReceiptsAndOriginalDeadlines() {
        assertThat(POSTGRES.isRunning()).isTrue();
        assertThat(REDIS.isRunning()).isTrue();
        assertThat(count("select count(*) from flyway_schema_history where version='60' and success")).isEqualTo(1);
        assertThat(count("select count(*) from flyway_schema_history where not success")).isZero();
        var template = template(LEAD, "Migration worksheet", "Retained migration instructions", 2);
        var schedule = daily(LEAD, template.id(), EMP, today, today);
        routines.runDue(endOfDay(today), 20);
        var occurrence = occurrence(LEAD, schedule.id(), today);
        assertThat(occurrence.status()).isEqualTo("CREATED");
        assertThat(date("select original_due_date from work_original_commitment where work_task_id=?", occurrence.taskId()))
                .isEqualTo(today.plusDays(2));
        assertThat(text("select source from work_original_commitment where work_task_id=?", occurrence.taskId()))
                .isEqualTo("TASK_INSERT");
        assertThatThrownBy(() -> jdbc.update("update work_routine_template_version set snapshot_json='{}'::jsonb where template_id=?", template.id()))
                .hasMessageContaining("immutable");
        assertThatThrownBy(() -> jdbc.update("update work_routine_occurrence set snapshot_json='{}'::jsonb where schedule_id=?", schedule.id()))
                .hasMessageContaining("immutable");
        assertThatThrownBy(() -> jdbc.update("delete from work_routine_occurrence where schedule_id=?", schedule.id()))
                .hasMessageContaining("retained");
        assertThatThrownBy(() -> jdbc.update("update work_original_commitment set original_due_date=original_due_date+1 where work_task_id=?", occurrence.taskId()))
                .hasMessageContaining("immutable");
        assertOneMaterialization(schedule.id(), today, occurrence.taskId(), LEAD, USER);
    }

    @Test
    void simultaneousWorkersAndFreshServiceReplayHaveOneTaskOneDurableBusinessNotice() throws Exception {
        var template = template(LEAD, "Concurrent worksheet", "Concurrent production instructions", 1);
        var schedule = daily(LEAD, template.id(), EMP, today, today);
        Instant due = endOfDay(today);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            Callable<Void> worker = () -> { start.await(); routines.runDue(due, 20); return null; };
            var first = pool.submit(worker); var second = pool.submit(worker); start.countDown();
            first.get(20, TimeUnit.SECONDS); second.get(20, TimeUnit.SECONDS);
        }
        var created = occurrence(LEAD, schedule.id(), today);
        assertThat(created.status()).isEqualTo("CREATED");
        UUID notice = notificationId();
        assertThat(text("select delivery_status from internal_call_notification where id=?", notice)).isEqualTo("QUEUED");
        assertThat(text("select category from internal_call_notification where id=?", notice)).isEqualTo("WORK");
        assertThat(text("select message from internal_call_notification where id=?", notice)).contains("Concurrent worksheet");
        // A separately constructed production bean has no scheduler or response state from the first worker.
        // Delivery has not run: the database itself retains the queued intent across that restart boundary.
        WorkRoutineService restarted = freshService();
        assertThat(restarted).isNotSameAs(routines);
        for (int i = 0; i < 3; i++) {
            restarted.runDue(due.plusSeconds(i + 1), 20);
            assertThat(restarted.retry(LEAD, schedule.id(), today, new WorkRoutineService.Retry(created.version())))
                    .isEqualTo(created);
        }
        assertThat(notificationId()).isEqualTo(notice);
        assertOneMaterialization(schedule.id(), today, created.taskId(), LEAD, USER);
        assertThat(count("select count(*) from work_original_commitment")).isEqualTo(1);
        assertThat(text("select created_by from department_work_task where id=?", created.taskId())).isEqualTo(LEAD.toString());
        assertThat(count("select count(*) from audit_event_history where actor_id=? and target_id=?", LEAD.toString(), created.taskId().toString()))
                .isGreaterThanOrEqualTo(1);
    }

    @ParameterizedTest(name = "fresh worker authorization: {0}")
    @ValueSource(strings = {"creator-terminated", "creator-moved", "creator-disabled", "creator-role", "creator-permission",
            "creator-assignment", "department-disabled", "assignee-terminated", "assignee-moved", "assignee-disabled",
            "assignee-role", "assignee-archived"})
    void everyOccurrenceReadsCurrentCreatorAndAssigneePolicyAndRetryRecovers(String change) {
        var template = template(LEAD, "Fresh policy worksheet", "Captured before policy changes", 2);
        var schedule = daily(LEAD, template.id(), EMP, today, today);
        revoke(change);
        freshService().runDue(endOfDay(today), 20);
        assertThat(text("select status from work_routine_occurrence where schedule_id=?", schedule.id())).isEqualTo("BLOCKED");
        assertThat(text("select exception_code from work_routine_occurrence where schedule_id=?", schedule.id())).isNotBlank();
        assertThat(text("select message from work_routine_occurrence where schedule_id=?", schedule.id())).isNotBlank();
        assertThat(count("select count(*) from work_routine_occurrence where schedule_id=? and task_id is null and attempts=1", schedule.id()))
                .isEqualTo(1);
        assertNoTaskOrNotice();
        String snapshot = snapshot(schedule.id(), today);
        routines.runDue(endOfDay(today).plusSeconds(1), 20);
        assertThat(count("select count(*) from work_routine_occurrence where schedule_id=?", schedule.id())).isEqualTo(1);
        assertNoTaskOrNotice();
        restorePolicy();
        var blocked = occurrence(LEAD, schedule.id(), today);
        var created = freshService().retry(LEAD, schedule.id(), today, new WorkRoutineService.Retry(blocked.version()));
        assertThat(created.status()).isEqualTo("CREATED");
        assertThat(created.attempts()).isEqualTo(2);
        assertThat(snapshot(schedule.id(), today)).isEqualTo(snapshot);
        assertThat(routines.retry(LEAD, schedule.id(), today, new WorkRoutineService.Retry(created.version()))).isEqualTo(created);
        assertOneMaterialization(schedule.id(), today, created.taskId(), LEAD, USER);
    }

    @Test
    void concurrentBlockedRetriesUseOneRetainedOccurrenceAndDoNotDuplicateSideEffects() throws Exception {
        var template = template(LEAD, "Retry worksheet", "Retained retry instructions", 1);
        var schedule = daily(LEAD, template.id(), EMP, today, today);
        jdbc.update("update iam_user_account set enabled=false where id=?", USER);
        routines.runDue(endOfDay(today), 20);
        var blocked = occurrence(LEAD, schedule.id(), today);
        assertThat(blocked.status()).isEqualTo("BLOCKED");
        String originalSnapshot = snapshot(schedule.id(), today);
        code(() -> routines.retry(LEAD, schedule.id(), today, new WorkRoutineService.Retry(blocked.version() + 1)), "ROUTINE_VERSION_CONFLICT");
        assertNoTaskOrNotice();
        jdbc.update("update iam_user_account set enabled=true where id=?", USER);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            Callable<UUID> retry = () -> {
                start.await();
                try { return routines.retry(LEAD, schedule.id(), today, new WorkRoutineService.Retry(blocked.version())).taskId(); }
                catch (BusinessException conflict) {
                    assertThat(conflict.getErrorCode()).isEqualTo("ROUTINE_VERSION_CONFLICT");
                    return null;
                }
            };
            var first = pool.submit(retry); var second = pool.submit(retry); start.countDown();
            UUID firstTask = first.get(20, TimeUnit.SECONDS), secondTask = second.get(20, TimeUnit.SECONDS);
            var created = occurrence(LEAD, schedule.id(), today);
            assertThat(created.status()).isEqualTo("CREATED");
            assertThat(created.attempts()).isEqualTo(2);
            assertThat(firstTask != null || secondTask != null).isTrue();
            if (firstTask != null) assertThat(firstTask).isEqualTo(created.taskId());
            if (secondTask != null) assertThat(secondTask).isEqualTo(created.taskId());
            assertThat(snapshot(schedule.id(), today)).isEqualTo(originalSnapshot);
            assertThat(freshService().retry(LEAD, schedule.id(), today, new WorkRoutineService.Retry(created.version()))).isEqualTo(created);
            assertOneMaterialization(schedule.id(), today, created.taskId(), LEAD, USER);
        }
    }

    @Test
    void creatorChangingToAnotherAuthorizedRoleDoesNotChangeTheCapturedReviewRoute() {
        var template = template(LEAD, "Original lead worksheet", "Retain the creator's original role", 2);
        var schedule = daily(LEAD, template.id(), EMP, today, today);
        endAssignment("department_team_lead", "team_lead", LEAD);
        endAssignment("department_hr_assignment", "hr", HR);
        jdbc.update("update iam_user_role set role_name='ROLE_HR_ADMIN' where user_id=?", LEAD);
        jdbc.update("update iam_user_role set role_name='ROLE_TEAM_LEAD' where user_id=?", MANAGER);
        assignment("department_hr_assignment", "hr", DEPT, LEAD, LEAD_EMP);
        assignment("department_team_lead", "team_lead", DEPT, MANAGER, MANAGER_EMP);
        // The creator is still authorized in the same department, with a valid HR assignment
        // and a separate active review lead. Only the changed original creator role blocks it.
        assertThat(routines.context(LEAD).departmentId()).isEqualTo(DEPT);
        assertThat(routines.context(LEAD).eligibleAssignees()).extracting("employeeId").containsExactlyInAnyOrder(EMP, MANAGER_EMP);
        freshService().runDue(endOfDay(today), 20);
        var blocked = occurrence(LEAD, schedule.id(), today);
        assertThat(blocked.status()).isEqualTo("BLOCKED");
        assertThat(blocked.exceptionCode()).isEqualTo("ROUTINE_CREATOR_ROLE_CHANGED");
        assertNoTaskOrNotice();
        var stillBlocked = routines.retry(LEAD, schedule.id(), today, new WorkRoutineService.Retry(blocked.version()));
        assertThat(stillBlocked.status()).isEqualTo("BLOCKED");
        assertThat(stillBlocked.exceptionCode()).isEqualTo("ROUTINE_CREATOR_ROLE_CHANGED");
        assertNoTaskOrNotice();

        endAssignment("department_team_lead", "team_lead", MANAGER);
        endAssignment("department_hr_assignment", "hr", LEAD);
        jdbc.update("update iam_user_role set role_name='ROLE_TEAM_LEAD' where user_id=?", LEAD);
        jdbc.update("update iam_user_role set role_name='ROLE_MANAGER' where user_id=?", MANAGER);
        jdbc.update("update department_team_lead set active=true,ended_at=null,ended_by_user_id=null where team_lead_user_id=?", LEAD);
        jdbc.update("update department_hr_assignment set active=true,ended_at=null,ended_by_user_id=null where hr_user_id=?", HR);
        var created = freshService().retry(LEAD, schedule.id(), today, new WorkRoutineService.Retry(stillBlocked.version()));
        assertThat(created.status()).isEqualTo("CREATED");
        assertThat(text("select assigned_by_role from department_work_task where id=?", created.taskId())).isEqualTo("TEAM_LEAD");
        assertThat(text("select assignee_role from department_work_task where id=?", created.taskId())).isEqualTo("EMPLOYEE");
        assertOneMaterialization(schedule.id(), today, created.taskId(), LEAD, USER);
    }

    @Test
    void foreignAndMissingResourcesAreIndistinguishableForReadsWritesPreviewAndRetry() {
        var own = template(LEAD, "Owned worksheet", "Department scoped instructions", 1);
        var foreign = template(OTHER_LEAD, "Foreign worksheet", "Other department instructions", 1);
        var foreignSchedule = daily(OTHER_LEAD, foreign.id(), OTHER_EMP, today, today);
        assertThat(routines.templates(LEAD, 0, 50).items()).extracting(WorkRoutineService.Template::id).containsExactly(own.id());
        assertThat(routines.template(LEAD, own.id())).isEqualTo(own);
        assertThat(routines.schedules(LEAD, 0, 50).items()).isEmpty();
        for (UUID id : List.of(foreign.id(), UUID.randomUUID())) {
            code(() -> routines.template(LEAD, id), "ROUTINE_NOT_FOUND");
            code(() -> routines.updateTemplate(LEAD, id, update(foreign.version(), "Unauthorized edit", 1)), "ROUTINE_NOT_FOUND");
            code(() -> routines.preview(LEAD, definition(id, EMP, today, today)), "ROUTINE_NOT_FOUND");
            code(() -> routines.createSchedule(LEAD, write(UUID.randomUUID(), id, EMP, today, today)), "ROUTINE_NOT_FOUND");
        }
        for (UUID id : List.of(foreignSchedule.id(), UUID.randomUUID())) {
            code(() -> routines.setState(LEAD, id, new WorkRoutineService.State(foreignSchedule.version(), true)), "ROUTINE_NOT_FOUND");
            code(() -> routines.occurrences(LEAD, id, 0, 20), "ROUTINE_NOT_FOUND");
            code(() -> routines.retry(LEAD, id, today, new WorkRoutineService.Retry(0)), "ROUTINE_NOT_FOUND");
        }
        assertThat(count("select count(*) from work_routine_schedule")).isEqualTo(1);
        assertNoTaskOrNotice();
    }

    @Test
    void onlyCurrentHrOrLeadCanEnumerateAndAuthorityIsRecheckedOnEveryApi() {
        var template = template(LEAD, "Authorized worksheet", "Only current authority may read", 1);
        var schedule = daily(LEAD, template.id(), EMP, today, today);
        assertThat(routines.context(LEAD).eligibleAssignees()).extracting("employeeId").containsExactly(EMP);
        assertThat(routines.context(HR).eligibleAssignees()).extracting("employeeId").containsExactlyInAnyOrder(EMP, LEAD_EMP);
        for (UUID actor : List.of(USER, MANAGER, ADMIN, RECEPTION, SECURITY, CEO)) {
            jdbc.update("insert into iam_user_permission_grant(user_id,permission_name) values(?,'WORK_TASK_CREATE')", actor);
            assertThatThrownBy(() -> routines.context(actor)).isInstanceOf(BusinessException.class);
            assertThatThrownBy(() -> routines.templates(actor, 0, 20)).isInstanceOf(BusinessException.class);
            assertThatThrownBy(() -> routines.schedules(actor, 0, 20)).isInstanceOf(BusinessException.class);
        }
        jdbc.update("insert into iam_user_permission_deny(user_id,permission_name) values(?,'WORK_TASK_CREATE')", LEAD);
        List<Runnable> operations = List.of(
                () -> routines.context(LEAD), () -> routines.templates(LEAD, 0, 20), () -> routines.template(LEAD, template.id()),
                () -> routines.schedules(LEAD, 0, 20),
                () -> routines.createTemplate(LEAD, templateWrite(UUID.randomUUID(), "Denied creation", "Denied instructions", 1)),
                () -> routines.updateTemplate(LEAD, template.id(), update(template.version(), "Denied update", 1)),
                () -> routines.createSchedule(LEAD, write(UUID.randomUUID(), template.id(), EMP, today, today)),
                () -> routines.preview(LEAD, definition(template.id(), EMP, today, today)),
                () -> routines.setState(LEAD, schedule.id(), new WorkRoutineService.State(schedule.version(), true)),
                () -> routines.occurrences(LEAD, schedule.id(), 0, 20),
                () -> routines.retry(LEAD, schedule.id(), today, new WorkRoutineService.Retry(0)));
        operations.forEach(action -> assertThatThrownBy(action::run).isInstanceOf(BusinessException.class));
        assertThat(count("select count(*) from work_routine_template")).isEqualTo(1);
        assertThat(count("select count(*) from work_routine_schedule where not paused")).isEqualTo(1);
        assertNoTaskOrNotice();
    }

    @Test
    void hrCanAssignTheirActiveLeadWithoutWideningLeadOrSelfAssignmentRules() {
        var request = new WorkRoutineService.TemplateWrite(UUID.randomUUID(), "Lead worksheet", "Direct HR review instructions",
                checklist("Lead delivery"), "TEAM_LEAD", 1);
        code(() -> routines.createTemplate(LEAD, request), "ROUTINE_INVALID");
        var template = routines.createTemplate(HR, request);
        assertThatThrownBy(() -> routines.createSchedule(HR, write(UUID.randomUUID(), template.id(), HR_EMP, today, today)))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> routines.createSchedule(LEAD, write(UUID.randomUUID(), template.id(), LEAD_EMP, today, today)))
                .isInstanceOf(BusinessException.class);
        var schedule = daily(HR, template.id(), LEAD_EMP, today, today);
        routines.runDue(endOfDay(today), 20);
        var created = occurrence(HR, schedule.id(), today);
        assertThat(text("select assignee_role from department_work_task where id=?", created.taskId())).isEqualTo("TEAM_LEAD");
        assertThat(text("select assigned_by_role from department_work_task where id=?", created.taskId())).isEqualTo("HR_ADMIN");
        assertOneMaterialization(schedule.id(), today, created.taskId(), HR, LEAD);
    }

    @Test
    void ownerScopedRequestReceiptsAreIdempotentAndChangedPayloadsConflict() {
        UUID key = UUID.randomUUID();
        var request = templateWrite(key, "Idempotent worksheet", "Original request instructions", 1);
        var first = routines.createTemplate(LEAD, request);
        assertThat(freshService().createTemplate(LEAD, request)).isEqualTo(first);
        code(() -> routines.createTemplate(LEAD, templateWrite(key, "Changed worksheet", "Original request instructions", 1)), "ROUTINE_VERSION_CONFLICT");
        var otherOwner = routines.createTemplate(HR, request);
        assertThat(otherOwner.id()).isNotEqualTo(first.id());
        UUID scheduleKey = UUID.randomUUID();
        var scheduleRequest = write(scheduleKey, first.id(), EMP, today, today.plusDays(1));
        var schedule = routines.createSchedule(LEAD, scheduleRequest);
        assertThat(freshService().createSchedule(LEAD, scheduleRequest)).isEqualTo(schedule);
        code(() -> routines.createSchedule(LEAD, write(scheduleKey, first.id(), EMP, today, today.plusDays(2))), "ROUTINE_VERSION_CONFLICT");
        assertThat(routines.createSchedule(HR, scheduleRequest).id()).isNotEqualTo(schedule.id());
        assertThat(count("select count(*) from work_routine_template")).isEqualTo(2);
        assertThat(count("select count(*) from work_routine_schedule")).isEqualTo(2);
        assertNoTaskOrNotice();
    }

    @Test
    void lostResponseScheduleReplayStillReturnsItsReceiptAfterTheOfficeDateAdvances() {
        var template = template(LEAD, "Midnight replay worksheet", "Recover the already committed schedule", 1);
        UUID key = UUID.randomUUID();
        var created = routines.createSchedule(LEAD, write(key, template.id(), EMP, today, today.plusDays(2)));
        LocalDate yesterday = today.minusDays(1);
        // Represents the same committed definition from the previous office date. The cursor
        // already points to today's daily occurrence; only the original request is historical.
        jdbc.update("update work_routine_schedule set request_json=jsonb_set(request_json,'{startDate}',to_jsonb(?::text)), "
                        + "definition_json=jsonb_set(definition_json,'{startDate}',to_jsonb(?::text)),created_at=? where id=?",
                yesterday.toString(), yesterday.toString(), Timestamp.from(endOfDay(yesterday)), created.id());
        var retained = routines.schedules(LEAD, 0, 20).items().getFirst();
        assertThat(retained.startDate()).isEqualTo(yesterday);
        assertThat(freshService().createSchedule(LEAD, write(key, template.id(), EMP, yesterday, today.plusDays(2)))).isEqualTo(retained);
        code(() -> routines.createSchedule(LEAD, write(key, template.id(), EMP, yesterday, today.plusDays(3))), "ROUTINE_VERSION_CONFLICT");
        assertThat(count("select count(*) from work_routine_schedule")).isEqualTo(1);
        assertNoTaskOrNotice();
    }

    @Test
    void concurrentTemplateAndScheduleCompareAndSwapHaveExactlyOneWinner() throws Exception {
        var template = template(LEAD, "Versioned worksheet", "Initial template instructions", 1);
        var schedule = daily(LEAD, template.id(), EMP, today, today.plusDays(2));
        oneWinner(() -> routines.updateTemplate(LEAD, template.id(), update(template.version(), "Concurrent edit", 2)));
        assertThat(routines.templates(LEAD, 0, 20).items().getFirst().version()).isEqualTo(template.version() + 1);
        assertThat(count("select count(*) from work_routine_template_version where template_id=?", template.id())).isEqualTo(2);
        oneWinner(() -> routines.setState(LEAD, schedule.id(), new WorkRoutineService.State(schedule.version(), true)));
        var paused = routines.schedules(LEAD, 0, 20).items().getFirst();
        assertThat(paused.paused()).isTrue(); assertThat(paused.version()).isEqualTo(schedule.version() + 1);
        code(() -> routines.setState(LEAD, schedule.id(), new WorkRoutineService.State(schedule.version(), false)), "ROUTINE_VERSION_CONFLICT");
        assertNoTaskOrNotice();
    }

    @Test
    void templateEditAndSchedulerCanCommitTogetherWithoutDeadlockOrPartialSnapshot() throws Exception {
        var initial = template(LEAD, "Before concurrent edit", "Instructions before concurrent edit", 1);
        var schedule = daily(LEAD, initial.id(), EMP, today, today);
        WorkRoutineService.Template updated;
        try (var pool = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            var worker = pool.submit(() -> { start.await(); return routines.runDue(endOfDay(today), 20); });
            var editor = pool.submit(() -> { start.await(); return routines.updateTemplate(LEAD, initial.id(),
                    update(initial.version(), "After concurrent edit", 3)); });
            start.countDown();
            assertThat(worker.get(20, TimeUnit.SECONDS)).isEqualTo(1);
            updated = editor.get(20, TimeUnit.SECONDS);
        }
        var created = occurrence(LEAD, schedule.id(), today);
        assertThat(created.status()).isEqualTo("CREATED");
        assertThat(created.templateVersion()).isIn(initial.version(), updated.version());
        if (created.templateVersion() == initial.version())
            assertTask(created.taskId(), initial.title(), initial.instructions(), today.plusDays(1), "Original required item");
        else
            assertTask(created.taskId(), updated.title(), updated.instructions(), today.plusDays(3), "Updated required item");
        assertOneMaterialization(schedule.id(), today, created.taskId(), LEAD, USER);
        assertThat(count("select count(*) from work_routine_template_version where template_id=?", initial.id())).isEqualTo(2);
    }

    @Test
    void oldTasksAndBlockedSnapshotsStayPinnedWhileFutureFirstAttemptsUseLatestVersion() {
        var first = template(LEAD, "Version one worksheet", "Version one delivery instructions", 2);
        var schedule = daily(LEAD, first.id(), EMP, today, today.plusDays(2));
        routines.runDue(endOfDay(today), 20);
        var dayOne = occurrence(LEAD, schedule.id(), today);
        String originalSnapshot = snapshot(schedule.id(), today);
        String originalPlanning = text("select planning_state::text from department_work_task where id=?", dayOne.taskId());
        var second = routines.updateTemplate(LEAD, first.id(), update(first.version(), "Version two worksheet", 5));
        jdbc.update("update iam_user_account set enabled=false where id=?", USER);
        routines.runDue(endOfDay(today.plusDays(1)), 20);
        var blocked = occurrence(LEAD, schedule.id(), today.plusDays(1));
        String blockedSnapshot = snapshot(schedule.id(), today.plusDays(1));
        assertThat(blocked.status()).isEqualTo("BLOCKED"); assertThat(blocked.templateVersion()).isEqualTo(second.version());
        var third = routines.updateTemplate(LEAD, first.id(), update(second.version(), "Version three worksheet", 7));
        jdbc.update("update iam_user_account set enabled=true where id=?", USER);
        var retried = freshService().retry(LEAD, schedule.id(), today.plusDays(1), new WorkRoutineService.Retry(blocked.version()));
        assertThat(retried.templateVersion()).isEqualTo(second.version());
        assertThat(snapshot(schedule.id(), today.plusDays(1))).isEqualTo(blockedSnapshot);
        assertTask(retried.taskId(), "Version two worksheet", "Updated delivery instructions", today.plusDays(6), "Updated required item");
        routines.runDue(endOfDay(today.plusDays(2)), 20);
        var future = occurrence(LEAD, schedule.id(), today.plusDays(2));
        assertThat(future.templateVersion()).isEqualTo(third.version());
        assertTask(future.taskId(), "Version three worksheet", "Updated delivery instructions", today.plusDays(9), "Updated required item");
        assertThat(snapshot(schedule.id(), today)).isEqualTo(originalSnapshot);
        assertThat(text("select planning_state::text from department_work_task where id=?", dayOne.taskId())).isEqualTo(originalPlanning);
        assertTask(dayOne.taskId(), "Version one worksheet", "Version one delivery instructions", today.plusDays(2), "Original required item");
        assertThat(count("select count(*) from work_routine_template_version where template_id=?", first.id())).isEqualTo(3);
        assertThat(count("select count(*) from department_work_task")).isEqualTo(3);
        assertThat(count("select count(*) from work_routine_notice_receipt")).isEqualTo(3);
        assertThat(count("select count(*) from internal_call_notification")).isEqualTo(3);
    }

    @Test
    void pauseKeepsTasksChecklistsAndPrivateEvidenceAndRejectsBlockedRetry() {
        var template = template(LEAD, "Retained worksheet", "Private evidence remains attached", 2);
        var schedule = daily(LEAD, template.id(), EMP, today, today.plusDays(4));
        routines.runDue(endOfDay(today), 20);
        var created = occurrence(LEAD, schedule.id(), today);
        var before = planning.get(USER, created.taskId());
        var upload = as(USER, () -> planning.upload(USER, created.taskId(), before.taskVersion(),
                new MockMultipartFile("file", "retained.pdf", "application/pdf", "%PDF-retained evidence".getBytes(java.nio.charset.StandardCharsets.US_ASCII))));
        assertThat(upload.evidence()).hasSize(1);
        String retainedTask = text("select planning_state::text from department_work_task where id=?", created.taskId());
        String retainedSnapshot = snapshot(schedule.id(), today);
        jdbc.update("update iam_user_account set enabled=false where id=?", USER);
        routines.runDue(endOfDay(today.plusDays(1)), 20);
        var blocked = occurrence(LEAD, schedule.id(), today.plusDays(1));
        var current = routines.schedules(LEAD, 0, 20).items().getFirst();
        var paused = routines.setState(LEAD, schedule.id(), new WorkRoutineService.State(current.version(), true));
        jdbc.update("update iam_user_account set enabled=true where id=?", USER);
        assertThatThrownBy(() -> routines.retry(LEAD, schedule.id(), blocked.occurrenceDate(), new WorkRoutineService.Retry(blocked.version())))
                .isInstanceOf(BusinessException.class);
        freshService().runDue(endOfDay(today.plusDays(4)), 20);
        assertThat(routines.schedules(LEAD, 0, 20).items().getFirst()).isEqualTo(paused);
        assertThat(count("select count(*) from work_routine_occurrence where schedule_id=?", schedule.id())).isEqualTo(2);
        assertThat(text("select planning_state::text from department_work_task where id=?", created.taskId())).isEqualTo(retainedTask);
        assertThat(snapshot(schedule.id(), today)).isEqualTo(retainedSnapshot);
        assertThat(planning.get(USER, created.taskId()).evidence()).isEqualTo(upload.evidence());
        assertThat(count("select count(*) from stored_document where owner_type='WORK_TASK' and owner_id=?", created.taskId())).isEqualTo(1);
        assertOneMaterialization(schedule.id(), today, created.taskId(), LEAD, USER);
    }

    @Test
    void resumeSkipsElapsedPausedDatesAndActiveDowntimeCatchesUpInBoundedBatches() {
        var template = template(LEAD, "Catchup worksheet", "Persisted schedule recovery instructions", 14);
        var pausedSchedule = daily(LEAD, template.id(), EMP, today, today.plusDays(2));
        var paused = routines.setState(LEAD, pausedSchedule.id(), new WorkRoutineService.State(pausedSchedule.version(), true));
        rebaseToPast(paused.id(), today.minusDays(3));
        routines.runDue(endOfDay(today), 20);
        assertNoTaskOrNotice();
        var resumed = routines.setState(LEAD, paused.id(), new WorkRoutineService.State(paused.version(), false));
        assertThat(resumed.nextOccurrenceAt()).isAfter(Instant.now());
        routines.runDue(endOfDay(today), 20);
        assertThat(routines.occurrences(LEAD, resumed.id(), 0, 20).items()).isEmpty();
        assertThat(count("select count(*) from audit_event_history where target_id=? and actor_id=?", paused.id().toString(), LEAD.toString()))
                .isGreaterThanOrEqualTo(1);

        var active = daily(LEAD, template.id(), EMP, today, today.plusDays(2));
        rebaseToPast(active.id(), today.minusDays(3));
        freshService().runDue(endOfDay(today), 2);
        assertThat(count("select count(*) from work_routine_occurrence where schedule_id=?", active.id())).isBetween(1L, 2L);
        for (int i = 0; i < 4; i++) freshService().runDue(endOfDay(today), 2);
        assertThat(routines.occurrences(LEAD, active.id(), 0, 20).items()).extracting("occurrenceDate")
                .containsExactlyInAnyOrder(today.minusDays(3), today.minusDays(2), today.minusDays(1), today);
        assertThat(count("select count(*) from department_work_task")).isEqualTo(4);
        assertThat(count("select count(*) from work_routine_notice_receipt")).isEqualTo(4);
        assertThat(count("select count(*) from internal_call_notification")).isEqualTo(4);
        assertThat(routines.occurrences(LEAD, resumed.id(), 0, 20).items()).isEmpty();
    }

    @Test
    void previewAndMaterializationApplyExplicitWeekendsHolidaysAndInclusiveEnd() {
        LocalDate friday = today.with(java.time.temporal.TemporalAdjusters.nextOrSame(java.time.DayOfWeek.FRIDAY));
        LocalDate holiday = friday.plusDays(3), end = friday.plusDays(5);
        var template = template(LEAD, "Calendar worksheet", "Explicit office-calendar instructions", 2);
        var definition = new WorkRoutineService.ScheduleDefinition(template.id(), EMP, "DAILY", 1, friday, end, "00:01",
                List.of(), null, "SKIP", "SKIP", List.of(holiday));
        var preview = routines.preview(LEAD, definition);
        assertThat(preview.officeZone()).isEqualTo(OFFICE.getId());
        assertThat(preview.occurrences()).extracting("occurrenceDate").containsExactly(friday, friday.plusDays(4), end);
        var schedule = routines.createSchedule(LEAD, new WorkRoutineService.ScheduleWrite(UUID.randomUUID(), template.id(), EMP,
                "DAILY", 1, friday, end, "00:01", List.of(), null, "SKIP", "SKIP", List.of(holiday)));
        routines.runDue(endOfDay(end.plusDays(1)), 20);
        assertThat(routines.occurrences(LEAD, schedule.id(), 0, 20).items()).extracting("occurrenceDate")
                .containsExactlyInAnyOrder(friday, friday.plusDays(4), end);
        assertThat(count("select count(*) from department_work_task")).isEqualTo(3);
        assertThat(count("select count(*) from work_routine_notice_receipt")).isEqualTo(3);
        assertThat(routines.schedules(LEAD, 0, 20).items().getFirst().nextOccurrenceAt()).isNull();
    }

    @Test
    void unexpectedDatabaseFailureRollsBackTaskChecklistSnapshotReceiptAndNotificationThenCanRecover() {
        var template = template(LEAD, "Rollback worksheet", "Atomic materialization instructions", 2);
        var schedule = daily(LEAD, template.id(), EMP, today, today);
        Instant cursor = schedule.nextOccurrenceAt();
        jdbc.execute("""
                create function sprint8_reject_notice() returns trigger language plpgsql as $$
                begin raise exception 'sprint8 injected notification database outage' using errcode='08006'; end $$
                """);
        jdbc.execute("create trigger sprint8_reject_notice before insert on internal_call_notification "
                + "for each row execute function sprint8_reject_notice()");
        try {
            // A worker may propagate or log an unexpected infrastructure failure. In either case
            // the complete occurrence transaction must roll back and must remain eligible to retry.
            try { routines.runDue(endOfDay(today), 20); }
            catch (RuntimeException failure) { assertThat(failure).hasStackTraceContaining("sprint8 injected notification database outage"); }
            assertNoTaskOrNotice();
            assertThat(count("select count(*) from work_routine_occurrence")).isZero();
            assertThat(count("select count(*) from work_original_commitment")).isZero();
            assertThat(count("select count(*) from workboard_activity_event where details_json->>'title'='Rollback worksheet'")).isZero();
            assertThat(routines.schedules(LEAD, 0, 20).items().getFirst().nextOccurrenceAt()).isEqualTo(cursor);
        } finally {
            jdbc.execute("drop trigger sprint8_reject_notice on internal_call_notification");
            jdbc.execute("drop function sprint8_reject_notice()");
        }
        freshService().runDue(endOfDay(today), 20);
        var created = occurrence(LEAD, schedule.id(), today);
        assertThat(created.status()).isEqualTo("CREATED"); assertThat(created.attempts()).isEqualTo(1);
        assertTask(created.taskId(), "Rollback worksheet", "Atomic materialization instructions", today.plusDays(2), "Original required item");
        assertOneMaterialization(schedule.id(), today, created.taskId(), LEAD, USER);
    }

    private WorkRoutineService.Template template(UUID actor, String title, String instructions, int offset) {
        return routines.createTemplate(actor, templateWrite(UUID.randomUUID(), title, instructions, offset));
    }
    private WorkRoutineService.TemplateWrite templateWrite(UUID requestId, String title, String instructions, int offset) {
        return new WorkRoutineService.TemplateWrite(requestId, title, instructions, checklist("Original required item"), "EMPLOYEE", offset);
    }
    private WorkRoutineService.TemplateUpdate update(long version, String title, int offset) {
        return new WorkRoutineService.TemplateUpdate(version, title, "Updated delivery instructions", checklist("Updated required item"), "EMPLOYEE", offset);
    }
    private List<WorkRoutineService.Checklist> checklist(String required) {
        return List.of(new WorkRoutineService.Checklist(required, true), new WorkRoutineService.Checklist("Optional evidence review", false));
    }
    private WorkRoutineService.Schedule daily(UUID actor, UUID template, UUID employee, LocalDate start, LocalDate end) {
        return routines.createSchedule(actor, write(UUID.randomUUID(), template, employee, start, end));
    }
    private WorkRoutineService.ScheduleWrite write(UUID requestId, UUID template, UUID employee, LocalDate start, LocalDate end) {
        return new WorkRoutineService.ScheduleWrite(requestId, template, employee, "DAILY", 1, start, end, "00:00",
                List.of(), null, "INCLUDE", "INCLUDE", List.of());
    }
    private WorkRoutineService.ScheduleDefinition definition(UUID template, UUID employee, LocalDate start, LocalDate end) {
        return new WorkRoutineService.ScheduleDefinition(template, employee, "DAILY", 1, start, end, "00:00",
                List.of(), null, "INCLUDE", "INCLUDE", List.of());
    }
    private WorkRoutineService.Occurrence occurrence(UUID actor, UUID schedule, LocalDate date) {
        return routines.occurrences(actor, schedule, 0, 50).items().stream().filter(item -> item.occurrenceDate().equals(date))
                .findFirst().orElseThrow(() -> new AssertionError("No persisted occurrence for " + date));
    }
    private WorkRoutineService freshService() {
        return application.getAutowireCapableBeanFactory().createBean(WorkRoutineService.class);
    }
    private Instant endOfDay(LocalDate day) { return day.atTime(23, 59).atZone(OFFICE).toInstant(); }
    private String snapshot(UUID schedule, LocalDate day) {
        return text("select snapshot_json::text from work_routine_occurrence where schedule_id=? and occurrence_date=?", schedule, Date.valueOf(day));
    }
    private void assertTask(UUID task, String title, String instructions, LocalDate due, String requiredItem) {
        assertThat(text("select title from department_work_task where id=?", task)).isEqualTo(title);
        assertThat(text("select description from department_work_task where id=?", task)).isEqualTo(instructions);
        assertThat(date("select due_date from department_work_task where id=?", task)).isEqualTo(due);
        var state = planning.get(USER, task);
        assertThat(state.checklist()).extracting("title").containsExactly(requiredItem, "Optional evidence review");
        assertThat(state.checklist()).extracting("required").containsExactly(true, false);
        assertThat(state.checklist()).extracting("id").doesNotHaveDuplicates().doesNotContainNull();
        assertThat(date("select original_due_date from work_original_commitment where work_task_id=?", task)).isEqualTo(due);
    }
    private void assertOneMaterialization(UUID schedule, LocalDate day, UUID task, UUID sender, UUID recipient) {
        drainNotifications();
        assertThat(count("select count(*) from work_routine_occurrence where schedule_id=? and occurrence_date=? and status='CREATED' and task_id=?",
                schedule, Date.valueOf(day), task)).isEqualTo(1);
        assertThat(count("select count(*) from department_work_task")).isEqualTo(1);
        assertThat(count("select count(*) from work_routine_notice_receipt")).isEqualTo(1);
        assertThat(count("select count(*) from internal_call_notification")).isEqualTo(1);
        assertThat(count("select count(*) from work_routine_notice_receipt r join internal_call_notification n on n.id=r.notification_id "
                + "where n.sender_user_id=? and n.recipient_user_id=? and n.category='WORK'", sender, recipient)).isEqualTo(1);
        assertThat(count("select count(*) from notification_outbox")).isZero();
    }
    private void assertNoTaskOrNotice() {
        drainNotifications();
        assertThat(count("select count(*) from department_work_task")).isZero();
        assertThat(count("select count(*) from work_routine_notice_receipt")).isZero();
        assertThat(count("select count(*) from internal_call_notification")).isZero();
        assertThat(count("select count(*) from notification_outbox")).isZero();
    }
    private UUID notificationId() { return jdbc.queryForObject("select notification_id from work_routine_notice_receipt", UUID.class); }
    private void oneWinner(Runnable change) throws Exception {
        try (var pool = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            Callable<Boolean> attempt = () -> {
                start.await();
                try { change.run(); return true; }
                catch (BusinessException conflict) { assertThat(conflict.getErrorCode()).isEqualTo("ROUTINE_VERSION_CONFLICT"); return false; }
            };
            var first = pool.submit(attempt); var second = pool.submit(attempt); start.countDown();
            assertThat(List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS))).containsExactlyInAnyOrder(true, false);
        }
    }
    private void rebaseToPast(UUID schedule, LocalDate start) {
        // Simulates a persisted schedule across several days of process downtime without a wall-clock sleep.
        jdbc.update("update work_routine_schedule set definition_json=jsonb_set(definition_json,'{startDate}',to_jsonb(?::text)), "
                        + "next_occurrence_date=?,next_occurrence_at=? where id=?", start.toString(), Date.valueOf(start),
                Timestamp.from(start.atTime(LocalTime.MIDNIGHT).atZone(OFFICE).toInstant()), schedule);
    }
    private void revoke(String change) {
        switch (change) {
            case "creator-terminated" -> jdbc.update("update employee set status='TERMINATED' where id=?", LEAD_EMP);
            case "creator-moved" -> jdbc.update("update employee set department_id=? where id=?", OTHER_DEPT, LEAD_EMP);
            case "creator-disabled" -> jdbc.update("update iam_user_account set enabled=false where id=?", LEAD);
            case "creator-role" -> jdbc.update("update iam_user_role set role_name='ROLE_EMPLOYEE' where user_id=?", LEAD);
            case "creator-permission" -> jdbc.update("insert into iam_user_permission_deny(user_id,permission_name) values(?,'WORK_TASK_CREATE')", LEAD);
            case "creator-assignment" -> jdbc.update("update department_team_lead set active=false,ended_at=now(),ended_by_user_id=? where team_lead_user_id=?", ADMIN, LEAD);
            case "department-disabled" -> jdbc.update("update org_department set active=false where id=?", DEPT);
            case "assignee-terminated" -> jdbc.update("update employee set status='TERMINATED' where id=?", EMP);
            case "assignee-moved" -> jdbc.update("update employee set department_id=? where id=?", OTHER_DEPT, EMP);
            case "assignee-disabled" -> jdbc.update("update iam_user_account set enabled=false where id=?", USER);
            case "assignee-role" -> jdbc.update("update iam_user_role set role_name='ROLE_MANAGER' where user_id=?", USER);
            case "assignee-archived" -> jdbc.update("update iam_user_account set archived=true,archived_at=now(),enabled=false where id=?", USER);
            default -> throw new IllegalArgumentException(change);
        }
    }
    private void restorePolicy() {
        jdbc.update("update employee set status='ACTIVE',department_id=? where id in (?,?)", DEPT, LEAD_EMP, EMP);
        jdbc.update("update iam_user_account set enabled=true,archived=false,archived_at=null where id in (?,?)", LEAD, USER);
        // Preserve the database's exactly-one-role invariant during fixture recovery.
        jdbc.update("update iam_user_role set role_name='ROLE_TEAM_LEAD' where user_id=?", LEAD);
        jdbc.update("update iam_user_role set role_name='ROLE_EMPLOYEE' where user_id=?", USER);
        jdbc.update("delete from iam_user_permission_deny where user_id=?", LEAD);
        jdbc.update("update department_team_lead set active=true,ended_at=null,ended_by_user_id=null where team_lead_user_id=?", LEAD);
        jdbc.update("update org_department set active=true where id=?", DEPT);
    }
    private <T> T as(UUID actor, Supplier<T> work) {
        var previous = SecurityContextHolder.getContext();
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(actor.toString(), "", List.of()));
        SecurityContextHolder.setContext(context);
        try { return work.get(); } finally { SecurityContextHolder.setContext(previous); }
    }
    private void department(UUID id, String code) {
        jdbc.update("insert into org_department(id,code,name,active,version,created_at,created_by,updated_at,updated_by) "
                + "values(?,?,?,true,0,now(),'sprint8-test',now(),'sprint8-test')", id, code, code);
    }
    private void employee(UUID id, UUID department, String name) {
        jdbc.update("insert into employee(id,employee_number,first_name,last_name,display_name,official_email,department_id,designation,joining_date,status,version,created_at,created_by,updated_at,updated_by) "
                + "values(?,?,?,?,?,?,?,'Test engineer','2026-01-01','ACTIVE',0,now(),'sprint8-test',now(),'sprint8-test')",
                id, "S8-" + id.toString().substring(24), name, "Person", name + " Person", name + "@sprint8.test", department);
    }
    private void account(UUID id, UUID employee, String name, String role) {
        jdbc.update("insert into iam_user_account(id,email,full_name,employee_id,password_hash,enabled,force_password_change,account_status,archived,version,created_at,created_by,updated_at,updated_by) "
                + "values(?,?,?,?,'test-only-hash',true,false,'ACTIVE',false,0,now(),'sprint8-test',now(),'sprint8-test')",
                id, name + "@sprint8.test", name, employee);
        jdbc.update("insert into iam_user_role(user_id,role_name) values(?,?)", id, role);
    }
    private void assignment(String table, String prefix, UUID department, UUID user, UUID employee) {
        jdbc.update("insert into " + table + "(id,department_id," + prefix + "_user_id," + prefix + "_employee_id,active,assigned_by_user_id,assigned_at,version,created_at,created_by,updated_at,updated_by) "
                + "values(?,?,?,?,true,?,now(),0,now(),'sprint8-test',now(),'sprint8-test')", UUID.randomUUID(), department, user, employee, ADMIN);
    }
    private void endAssignment(String table, String prefix, UUID user) {
        jdbc.update("update " + table + " set active=false,ended_at=now(),ended_by_user_id=? where " + prefix + "_user_id=? and active", ADMIN, user);
    }
    private static UUID id(int value) { return UUID.fromString(String.format(Locale.ROOT, "88000000-0000-0000-0000-%012d", value)); }
    private long count(String sql, Object... arguments) { return Objects.requireNonNull(jdbc.queryForObject(sql, Long.class, arguments)); }
    private String text(String sql, Object... arguments) { return jdbc.queryForObject(sql, String.class, arguments); }
    private LocalDate date(String sql, Object... arguments) { return Objects.requireNonNull(jdbc.queryForObject(sql, Date.class, arguments)).toLocalDate(); }
    private void code(Runnable action, String expected) {
        assertThatThrownBy(action::run).isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo(expected);
    }
    private void drainNotifications() {
        if (!(notificationExecutor instanceof ThreadPoolTaskExecutor executor)) return;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (executor.getActiveCount() > 0 || !executor.getThreadPoolExecutor().getQueue().isEmpty()) {
            if (System.nanoTime() > deadline) throw new AssertionError("Notification writes did not settle before database assertions");
            try { Thread.sleep(20); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
        }
    }
}
