package com.brainserve.appointment;

import com.brainserve.appointment.document.infrastructure.ClamAvScanner;
import com.brainserve.appointment.shared.application.BusinessException;
import com.brainserve.appointment.workinsight.application.WorkInsightService;
import com.brainserve.appointment.workinsight.application.WorkboardQueryService;
import com.brainserve.appointment.worktask.application.*;
import com.brainserve.appointment.worktask.domain.DepartmentWorkTask;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
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

import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.*;

/** Actual production handover/workflow services, PostgreSQL policies and Redis; only external file I/O is mocked. */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "brainserve.security.jwt-secret=test-only-secret-key-that-is-at-least-thirty-two-bytes",
        "brainserve.security.pii-encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "brainserve.bootstrap.system-admin-enabled=false", "brainserve.bootstrap.ceo-enabled=false",
        "brainserve.appointment.office-zone=Asia/Kolkata", "spring.task.scheduling.enabled=false",
        "brainserve.work-routines.enabled=false", "spring.kafka.listener.auto-startup=false",
        "brainserve.notification.internal-call-dispatch-ms=3600000", "brainserve.notification.poll-ms=3600000",
        "aws.s3.access-key=test-access-key", "aws.s3.secret-key=test-secret-key"
})
class Sprint9HandoverPostgresIntegrationTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.2-alpine");
    @Container static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4.1-alpine").withExposedPorts(6379);
    @DynamicPropertySource static void infrastructure(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl); r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword); r.add("spring.data.redis.host", REDIS::getHost);
        r.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired WorkTaskHandoverService handovers;
    @Autowired DepartmentWorkTaskService tasks;
    @Autowired TaskPlanningService planning;
    @Autowired WorkInsightService insights;
    @Autowired WorkboardQueryService board;
    @Autowired WorkTaskSearchService search;
    @Autowired TaskActivityService activity;
    @Autowired @Qualifier("notificationExecutor") Executor notificationExecutor;
    @MockitoBean S3Client s3;
    @MockitoBean ClamAvScanner scanner;
    static final ZoneId OFFICE = ZoneId.of("Asia/Kolkata");
    static final UUID DEPT=id(101), OTHER_DEPT=id(102), EMP=id(201), NEW_EMP=id(202), THIRD_EMP=id(203),
            LEAD_EMP=id(204), HR_EMP=id(205), MANAGER_EMP=id(206), OTHER_EMP=id(207), SECOND_LEAD_EMP=id(208), CEO_EMP=id(209);
    static final UUID USER=id(1), NEW_USER=id(2), THIRD_USER=id(3), LEAD=id(4), HR=id(5), MANAGER=id(6),
            OTHER=id(7), ADMIN=id(8), CEO=id(9), SECOND_LEAD=id(10);
    @BeforeEach void fixture() {
        drain(); org.mockito.Mockito.reset(s3, scanner);
        // Class-owned containers: truncate intentionally immutable retained facts with the full aggregate.
        jdbc.execute("truncate stored_document, workboard_preference, work_task_audit_record, department_work_task, work_original_commitment, internal_call_notification cascade");
        jdbc.execute("truncate department_hr_assignment, department_team_lead, department_manager_assignment, iam_user_account, employee, org_department cascade");
        department(DEPT,"S9H_MAIN"); department(OTHER_DEPT,"S9H_OTHER");
        employee(EMP,DEPT,"original"); employee(NEW_EMP,DEPT,"incoming"); employee(THIRD_EMP,DEPT,"third");
        employee(LEAD_EMP,DEPT,"lead"); employee(HR_EMP,DEPT,"hr"); employee(MANAGER_EMP,DEPT,"manager");
        employee(OTHER_EMP,OTHER_DEPT,"foreign"); employee(SECOND_LEAD_EMP,DEPT,"secondlead"); employee(CEO_EMP,DEPT,"ceo");
        account(USER,EMP,"original","ROLE_EMPLOYEE"); account(NEW_USER,NEW_EMP,"incoming","ROLE_EMPLOYEE");
        account(THIRD_USER,THIRD_EMP,"third","ROLE_EMPLOYEE"); account(LEAD,LEAD_EMP,"lead","ROLE_TEAM_LEAD");
        account(HR,HR_EMP,"hr","ROLE_HR_ADMIN"); account(MANAGER,MANAGER_EMP,"manager","ROLE_MANAGER");
        account(OTHER,OTHER_EMP,"foreign","ROLE_EMPLOYEE"); account(ADMIN,null,"admin","ROLE_SYSTEM_ADMIN");
        account(CEO,CEO_EMP,"ceo","ROLE_CEO"); account(SECOND_LEAD,SECOND_LEAD_EMP,"secondlead","ROLE_TEAM_LEAD");
        assignment("department_team_lead","team_lead",LEAD,LEAD_EMP);
        assignment("department_hr_assignment","hr",HR,HR_EMP);
        assignment("department_manager_assignment","manager",MANAGER,MANAGER_EMP);
        assertThat(count("select count(*) from iam_user_account u where (select count(*) from iam_user_role r where r.user_id=u.id)<>1")).isZero();
    }
    @AfterEach void settled() { drain(); }

    @Test void additiveMigrationEnforcesOriginalIdentityAndRetainedHandoverHistory() {
        assertThat(POSTGRES.isRunning()).isTrue(); assertThat(REDIS.isRunning()).isTrue();
        assertThat(count("select count(*) from flyway_schema_history where version='61' and success")).isEqualTo(1);
        var task=create("Retained original commitment"); LocalDate original=today();
        var before=handovers.get(LEAD,task.getId());
        assertThat(before.originalEmployeeId()).isEqualTo(EMP); assertThat(before.currentEmployeeId()).isEqualTo(EMP);
        assertThat(before.eligibleAssignees()).extracting("employeeId").contains(NEW_EMP,THIRD_EMP).doesNotContain(EMP,OTHER_EMP,LEAD_EMP);
        var after=transfer(LEAD,task.getId(),NEW_EMP);
        assertThat(after.currentEmployeeId()).isEqualTo(NEW_EMP); assertThat(after.originalEmployeeId()).isEqualTo(EMP);
        assertThat(after.history()).hasSize(1); var history=after.history().getFirst();
        assertThat(history.fromEmployeeId()).isEqualTo(EMP); assertThat(history.toEmployeeId()).isEqualTo(NEW_EMP);
        assertThat(history.actorUserId()).isEqualTo(LEAD); assertThat(history.reason()).isEqualTo("Controlled assignment change");
        assertThat(history.assignmentRevision()).isEqualTo(1); assertThat(history.occurredAt()).isNotNull();
        assertThat(history.effectiveAt()).isEqualTo(history.occurredAt());
        assertThat(history.effectiveAt().isAfter(Instant.now())).isFalse();
        assertThat(date("select original_due_date from work_original_commitment where work_task_id=?",task.getId())).isEqualTo(original);
        assertThatThrownBy(()->jdbc.update("update department_work_task set original_employee_id=? where id=?",NEW_EMP,task.getId())).hasMessageContaining("immutable");
        assertThatThrownBy(()->jdbc.update("update work_original_commitment set original_due_date=original_due_date+1 where work_task_id=?",task.getId())).hasMessageContaining("immutable");
        assertThatThrownBy(()->jdbc.update("update work_task_handover set reason='Rewritten' where task_id=?",task.getId())).hasMessageContaining("immutable");
        assertThatThrownBy(()->jdbc.update("delete from work_task_handover where task_id=?",task.getId())).hasMessageContaining("retained");
        notices(task.getId(),USER,NEW_USER);
        assertThatThrownBy(()->jdbc.update("delete from work_handover_notice_receipt")).hasMessageContaining("immutable");
        jdbc.update("update employee set display_name='Renamed current assignee' where id=?",NEW_EMP);
        assertThat(handovers.get(LEAD,task.getId()).history().getFirst().toName()).isEqualTo("incoming Person");
    }

    @Test void competingDifferentTargetsHaveOneWinnerOneRevisionAndTwoDurableParticipantNotices() throws Exception {
        var task=create("Concurrent handover"); long version=handovers.get(LEAD,task.getId()).taskVersion();
        try(var pool=Executors.newFixedThreadPool(2)) {
            var start=new CountDownLatch(1);
            Callable<Boolean> a=()->attempt(start,task.getId(),version,NEW_EMP);
            Callable<Boolean> b=()->attempt(start,task.getId(),version,THIRD_EMP);
            var first=pool.submit(a);var second=pool.submit(b);start.countDown();
            assertThat(List.of(first.get(20,TimeUnit.SECONDS),second.get(20,TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
        }
        var result=handovers.get(LEAD,task.getId()); assertThat(result.history()).hasSize(1);
        assertThat(result.currentEmployeeId()).isIn(NEW_EMP,THIRD_EMP);
        assertThat(result.history().getFirst().assignmentRevision()).isEqualTo(1);
        assertThat(result.taskVersion()).isGreaterThan(version);
        notices(task.getId(),USER,result.currentEmployeeId().equals(NEW_EMP)?NEW_USER:THIRD_USER);
        code(()->handovers.handover(LEAD,task.getId(),new WorkTaskHandoverService.Change(version,THIRD_EMP,"Stale retry",null)),"WORK_TASK_VERSION_CONFLICT");
        assertThat(count("select count(*) from work_task_handover where task_id=?",task.getId())).isEqualTo(1);
        assertThat(count("select count(*) from work_handover_notice_receipt")).isEqualTo(2);
    }

    @Test void submittedEvidenceRetainsItsTrueAuthorWhileRemovedParticipantLosesAllCurrentAccess() {
        var task=create("Private authored handover"); byte[] bytes="%PDF-authored evidence".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        var uploaded=as(USER,()->planning.upload(USER,task.getId(),planning.get(USER,task.getId()).taskVersion(),
                new MockMultipartFile("file","authored.pdf","application/pdf",bytes)));
        var evidence=uploaded.evidence().getFirst();
        var comment=as(USER,()->activity.create(USER,task.getId(),new TaskActivityService.Create(UUID.randomUUID(),"Original authored comment",List.of(),List.of(evidence.id()))));
        as(USER,()->tasks.complete(USER,EMP,task.getId(),"Original authored delivery",uploaded.taskVersion()));
        var frozen=planning.get(LEAD,task.getId()).submissions().getFirst();
        assertThat(frozen.authorEmployeeId()).isEqualTo(EMP);assertThat(frozen.authorUserId()).isEqualTo(USER);
        assertThat(frozen.authorshipKnown()).isTrue();assertThat(frozen.assignmentRevision()).isZero();
        transfer(LEAD,task.getId(),NEW_EMP);
        var incoming=planning.get(NEW_USER,task.getId());
        assertThat(incoming.evidence()).isEmpty();assertThat(incoming.submissions()).containsExactly(frozen);
        assertThat(incoming.submissions().getFirst().authorName()).isEqualTo("original");
        assertThat(board.detail(NEW_USER,task.getId()).item().status()).isEqualTo("ASSIGNED");
        assertThat(activity.comments(NEW_USER,task.getId(),0,20).comments()).extracting("id").contains(comment.id());
        code(()->planning.get(USER,task.getId()),"WORK_TASK_NOT_FOUND");code(()->planning.download(USER,task.getId(),evidence.id()),"WORK_TASK_NOT_FOUND");
        code(()->activity.comments(USER,task.getId(),0,20),"WORK_TASK_NOT_FOUND");
        code(()->activity.download(USER,task.getId(),comment.id(),evidence.id()),"WORK_TASK_NOT_FOUND");
        code(()->activity.create(USER,task.getId(),new TaskActivityService.Create(UUID.randomUUID(),"No longer assigned",List.of(),List.of())),"WORK_TASK_NOT_FOUND");
        assertThat(search.search(USER,"Private authored handover",0,20).items()).isEmpty();
        assertThatThrownBy(()->search.open(USER,task.getId())).isInstanceOf(BusinessException.class);
        assertThat(search.search(NEW_USER,"Private authored handover",0,20).items()).extracting("id").contains(task.getId());
        org.mockito.Mockito.when(s3.getObject(org.mockito.ArgumentMatchers.any(software.amazon.awssdk.services.s3.model.GetObjectRequest.class)))
                .thenAnswer(call->new software.amazon.awssdk.core.ResponseInputStream<>(software.amazon.awssdk.services.s3.model.GetObjectResponse.builder().build(),
                        software.amazon.awssdk.http.AbortableInputStream.create(new java.io.ByteArrayInputStream(bytes))));
        assertThat(planning.download(NEW_USER,task.getId(),evidence.id()).bytes()).isEqualTo(bytes);
        assertThat(activity.download(NEW_USER,task.getId(),comment.id(),evidence.id()).bytes()).isEqualTo(bytes);
        assertThat(count("select count(*) from stored_document where id=? and owner_id=?",evidence.documentId(),task.getId())).isEqualTo(1);
    }

    @Test void acceptedAuditedDeliveryRequiresNewAuthoredSubmissionAndNewReviewAfterHandover() {
        var task=create("Acceptance invalidation");as(USER,()->tasks.complete(USER,EMP,task.getId(),"First delivery"));
        as(LEAD,()->tasks.approve(LEAD,task.getId(),"First evidence accepted"));
        var audited=as(HR,()->insights.markAudited(HR,task.getId()));
        as(MANAGER,()->insights.decideByManager(MANAGER,audited.auditRecordId(),true,"Prior manager decision"));
        var old=planning.get(HR,task.getId()).submissions().getFirst();
        assertThat(old.acceptedAt()).isNotNull();assertThat(old.acceptedByRole()).isEqualTo("TEAM_LEAD");
        long approvals=count("select count(*) from audit_event_history where target_id=? and event_type='WORK_TASK_APPROVED'",task.getId().toString());
        transfer(HR,task.getId(),NEW_EMP);
        assertThat(planning.get(NEW_USER,task.getId()).submissions()).containsExactly(old.withCurrentAcceptance(false));
        assertThat(count("select count(*) from work_task_audit_record where work_task_id=?",task.getId())).isZero();
        assertThat(text("select previous_task_snapshot::text from work_task_handover where task_id=?",task.getId())).contains("APPROVED","First delivery");
        assertThat(text("select previous_audit_snapshot::text from work_task_handover where task_id=?",task.getId())).contains("Prior manager decision");
        assertThat(count("select count(*) from audit_event_history where target_id=? and event_type='WORK_TASK_APPROVED'",task.getId().toString())).isEqualTo(approvals);
        assertThatThrownBy(()->tasks.approve(LEAD,task.getId(),"Cannot reuse old acceptance")).isInstanceOf(BusinessException.class);
        as(NEW_USER,()->tasks.complete(NEW_USER,NEW_EMP,task.getId(),"Incoming delivery"));
        var latest=planning.get(LEAD,task.getId());assertThat(latest.submissions()).hasSize(2);
        assertThat(latest.submissions().getLast().version()).isEqualTo(2);assertThat(latest.submissions().getLast().acceptedAt()).isNull();
        assertThat(latest.submissions().getLast().authorEmployeeId()).isEqualTo(NEW_EMP);
        assertThat(latest.submissions().getLast().assignmentRevision()).isEqualTo(1);
        as(LEAD,()->tasks.approve(LEAD,task.getId(),"Incoming evidence accepted"));
        var revised=as(HR,()->insights.markAudited(HR,task.getId()));
        as(MANAGER,()->insights.decideByManager(MANAGER,revised.auditRecordId(),true,"Reviewed incoming assignment"));
        as(CEO,()->insights.decideByCeo(CEO,revised.auditRecordId(),true,"Final incoming delivery"));
        assertThat(planning.get(NEW_USER,task.getId()).submissions().getFirst()).isEqualTo(old.withCurrentAcceptance(false));
        assertThat(date("select original_due_date from work_original_commitment where work_task_id=?",task.getId())).isEqualTo(today());
    }

    @Test void directLeadHandoverUsesHrAcceptanceAndLeadCannotTransferOrSelfReview() {
        var task=as(HR,()->tasks.create(HR,new DepartmentWorkTaskService.CreateCommand(LEAD_EMP,"Direct Lead worksheet","Direct HR instructions",today())));
        assertThat(handovers.get(LEAD,task.getId()).canHandover()).isFalse();
        assertThatThrownBy(()->transfer(LEAD,task.getId(),SECOND_LEAD_EMP)).isInstanceOf(BusinessException.class);
        as(LEAD,()->tasks.complete(LEAD,LEAD_EMP,task.getId(),"Direct initial delivery"));
        as(HR,()->insights.markAudited(HR,task.getId()));
        var old=planning.get(HR,task.getId()).submissions().getFirst();assertThat(old.acceptedByRole()).isEqualTo("HR_ADMIN");
        jdbc.update("update department_team_lead set active=false,ended_at=now(),ended_by_user_id=? where team_lead_user_id=?",ADMIN,LEAD);
        assignment("department_team_lead","team_lead",SECOND_LEAD,SECOND_LEAD_EMP);
        transfer(HR,task.getId(),SECOND_LEAD_EMP);
        assertThat(planning.get(SECOND_LEAD,task.getId()).submissions()).containsExactly(old.withCurrentAcceptance(false));
        as(SECOND_LEAD,()->tasks.complete(SECOND_LEAD,SECOND_LEAD_EMP,task.getId(),"Direct incoming delivery"));
        assertThatThrownBy(()->tasks.approve(SECOND_LEAD,task.getId(),"Self review denied")).isInstanceOf(BusinessException.class);
        as(HR,()->insights.markAudited(HR,task.getId()));
        assertThat(planning.get(HR,task.getId()).submissions().getLast().acceptedByRole()).isEqualTo("HR_ADMIN");
    }

    @Test void ceoClosedTaskCannotBeHandedOverAndReadsDoNotMutateReceipts() {
        var task=create("Final governance");as(USER,()->tasks.complete(USER,EMP,task.getId(),"Delivered"));as(LEAD,()->tasks.approve(LEAD,task.getId(),"Accepted"));
        var record=as(HR,()->insights.markAudited(HR,task.getId()));as(MANAGER,()->insights.decideByManager(MANAGER,record.auditRecordId(),true,"Reviewed"));
        as(CEO,()->insights.decideByCeo(CEO,record.auditRecordId(),true,"Final"));
        var view=handovers.get(HR,task.getId());assertThat(view.canHandover()).isFalse();assertThat(view.unavailableReason()).isNotBlank();
        assertThatThrownBy(()->transfer(HR,task.getId(),NEW_EMP)).isInstanceOf(BusinessException.class);
        assertThat(count("select count(*) from work_task_handover")).isZero();assertThat(count("select count(*) from work_handover_notice_receipt")).isZero();
    }

    @ParameterizedTest(name="target current policy: {0}")
    @ValueSource(strings={"disabled","archived","terminated","moved","wrong-role","approved-leave","department-disabled"})
    void targetEligibilityIsReadAgainAtMutationAndLeavesNoPartialState(String change) {
        var task=create("Fresh target eligibility");var before=handovers.get(LEAD,task.getId());assertThat(before.eligibleAssignees()).extracting("employeeId").contains(NEW_EMP);
        switch(change) {
            case "disabled" -> jdbc.update("update iam_user_account set enabled=false where id=?",NEW_USER);
            case "archived" -> jdbc.update("update iam_user_account set archived=true,archived_at=now(),enabled=false where id=?",NEW_USER);
            case "terminated" -> jdbc.update("update employee set status='TERMINATED' where id=?",NEW_EMP);
            case "moved" -> jdbc.update("update employee set department_id=? where id=?",OTHER_DEPT,NEW_EMP);
            case "wrong-role" -> jdbc.update("update iam_user_role set role_name='ROLE_MANAGER' where user_id=?",NEW_USER);
            case "approved-leave" -> leave(NEW_EMP,NEW_USER,"APPROVED");
            case "department-disabled" -> jdbc.update("update org_department set active=false where id=?",DEPT);
            default -> throw new IllegalArgumentException(change);
        }
        assertThatThrownBy(()->handovers.handover(LEAD,task.getId(),new WorkTaskHandoverService.Change(before.taskVersion(),NEW_EMP,"Target changed after preview",null)))
                .isInstanceOf(BusinessException.class);
        assertThat(text("select employee_id::text from department_work_task where id=?",task.getId())).isEqualTo(EMP.toString());
        assertThat(count("select count(*) from work_task_handover")).isZero();assertThat(count("select count(*) from work_handover_notice_receipt")).isZero();
    }

    @ParameterizedTest(name="actor current policy: {0}")
    @ValueSource(strings={"disabled","terminated","moved","wrong-role","permission","assignment"})
    void actorAuthorizationCannotBeReusedFromThePreview(String change) {
        var task=create("Fresh actor eligibility");var before=handovers.get(LEAD,task.getId());
        switch(change) {
            case "disabled" -> jdbc.update("update iam_user_account set enabled=false where id=?",LEAD);
            case "terminated" -> jdbc.update("update employee set status='TERMINATED' where id=?",LEAD_EMP);
            case "moved" -> jdbc.update("update employee set department_id=? where id=?",OTHER_DEPT,LEAD_EMP);
            case "wrong-role" -> jdbc.update("update iam_user_role set role_name='ROLE_EMPLOYEE' where user_id=?",LEAD);
            case "permission" -> jdbc.update("insert into iam_user_permission_deny(user_id,permission_name) values(?,'WORK_TASK_CREATE')",LEAD);
            case "assignment" -> jdbc.update("update department_team_lead set active=false,ended_at=now(),ended_by_user_id=? where team_lead_user_id=?",ADMIN,LEAD);
            default -> throw new IllegalArgumentException(change);
        }
        assertThatThrownBy(()->handovers.handover(LEAD,task.getId(),new WorkTaskHandoverService.Change(before.taskVersion(),NEW_EMP,"Actor changed after preview",null))).isInstanceOf(BusinessException.class);
        assertThat(count("select count(*) from work_task_handover")).isZero();assertThat(count("select count(*) from work_handover_notice_receipt")).isZero();
    }

    @Test void pendingLeaveDoesNotInventUnavailabilityAndFutureOrInvalidRequestsAreRejected() {
        var task=create("Strict handover inputs");leave(NEW_EMP,NEW_USER,"PENDING");var before=handovers.get(HR,task.getId());
        assertThat(before.eligibleAssignees()).extracting("employeeId").contains(NEW_EMP);
        for(var request:List.of(new WorkTaskHandoverService.Change(null,NEW_EMP,"Missing revision",null),
                new WorkTaskHandoverService.Change(before.taskVersion(),NEW_EMP,"",null),
                new WorkTaskHandoverService.Change(before.taskVersion(),NEW_EMP,"Future scheduling",Instant.now().plusSeconds(3600)),
                new WorkTaskHandoverService.Change(before.taskVersion(),EMP,"Current employee",null),
                new WorkTaskHandoverService.Change(before.taskVersion(),OTHER_EMP,"Cross department",null),
                new WorkTaskHandoverService.Change(before.taskVersion(),LEAD_EMP,"Different assignee role",null)))
            assertThatThrownBy(()->handovers.handover(HR,task.getId(),request)).isInstanceOf(BusinessException.class);
        code(()->handovers.handover(HR,task.getId(),new WorkTaskHandoverService.Change(before.taskVersion()+1,NEW_EMP,"Stale revision",null)),"WORK_TASK_VERSION_CONFLICT");
        assertThat(count("select count(*) from work_task_handover")).isZero();transfer(HR,task.getId(),NEW_EMP);
    }

    @Test void foreignIdentifiersAndUnauthorizedRolesDoNotLeakPrivateTaskOrEligibleNames() {
        var task=create("Private handover preview");
        code(()->handovers.get(OTHER,task.getId()),"WORK_TASK_NOT_FOUND");code(()->handovers.get(OTHER,UUID.randomUUID()),"WORK_TASK_NOT_FOUND");
        for(UUID actor:List.of(USER,MANAGER,ADMIN,CEO)) {
            assertThatThrownBy(()->handovers.handover(actor,task.getId(),new WorkTaskHandoverService.Change(999L,NEW_EMP,"Unauthorized",null))).isInstanceOf(BusinessException.class);
        }
        assertThat(count("select count(*) from work_task_handover")).isZero();
    }

    @Test void notificationDatabaseFailureRollsBackAssignmentRevisionHistoryAndOutboxThenRecovers() {
        var task=create("Atomic handover");var before=handovers.get(LEAD,task.getId());drain();
        jdbc.execute("create function sprint9_reject_handover_notice() returns trigger language plpgsql as $$ begin raise exception 'sprint9 injected notice outage' using errcode='08006'; end $$");
        jdbc.execute("create trigger sprint9_reject_handover_notice before insert on internal_call_notification for each row execute function sprint9_reject_handover_notice()");
        try {
            assertThatThrownBy(()->transfer(LEAD,task.getId(),NEW_EMP)).hasStackTraceContaining("sprint9 injected notice outage");
            assertThat(handovers.get(LEAD,task.getId()).currentEmployeeId()).isEqualTo(EMP);
            assertThat(handovers.get(LEAD,task.getId()).taskVersion()).isEqualTo(before.taskVersion());
            assertThat(count("select count(*) from work_task_handover")).isZero();assertThat(count("select count(*) from work_handover_notice_receipt")).isZero();
        } finally {
            jdbc.execute("drop trigger sprint9_reject_handover_notice on internal_call_notification");jdbc.execute("drop function sprint9_reject_handover_notice()");
        }
        transfer(LEAD,task.getId(),NEW_EMP);notices(task.getId(),USER,NEW_USER);
    }


    @Test void unfinishedAuthoredDraftIsRetainedAndResetWithoutInventingASubmission() {
        var task=create("Authored unfinished draft");UUID item=UUID.randomUUID();
        var requirements=as(LEAD,()->planning.update(LEAD,task.getId(),new TaskPlanningService.Update(planning.get(LEAD,task.getId()).taskVersion(),"NORMAL",null,false,today(),"Delivery requirements",List.of(new TaskPlanningService.Definition(item,"Required original item",true)))));
        var tick=as(USER,()->planning.tick(USER,task.getId(),new TaskPlanningService.Tick(requirements.taskVersion(),List.of(item))));
        byte[] bytes="%PDF-authored draft".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        var uploaded=as(USER,()->planning.upload(USER,task.getId(),tick.taskVersion(),new MockMultipartFile("file","draft.pdf","application/pdf",bytes)));
        var evidence=uploaded.evidence().getFirst();transfer(LEAD,task.getId(),NEW_EMP);
        var next=planning.get(NEW_USER,task.getId());assertThat(next.submissions()).isEmpty();assertThat(next.evidence()).isEmpty();
        assertThat(next.checklist().getFirst().completed()).isFalse();assertThat(next.retainedDrafts()).hasSize(1);
        var retained=next.retainedDrafts().getFirst();assertThat(retained.authorEmployeeId()).isEqualTo(EMP);assertThat(retained.authorUserId()).isEqualTo(USER);
        assertThat(retained.authorName()).isEqualTo("original");assertThat(retained.evidence()).containsExactly(evidence);
        assertThat(retained.checklist().getFirst().completed()).isTrue();assertThat(retained.assignmentRevision()).isZero();
        assertThatThrownBy(()->jdbc.update("update department_work_task set planning_state=jsonb_set(planning_state,'{retainedDrafts,0,authorName}','\"False author\"'::jsonb) where id=?",task.getId())).hasMessageContaining("immutable");
        assertThatThrownBy(()->jdbc.update("update department_work_task set planning_state=jsonb_set(planning_state,'{retainedDrafts}','[]'::jsonb) where id=?",task.getId())).hasMessageContaining("immutable");
        org.mockito.Mockito.when(s3.getObject(org.mockito.ArgumentMatchers.any(software.amazon.awssdk.services.s3.model.GetObjectRequest.class)))
                .thenAnswer(call->new software.amazon.awssdk.core.ResponseInputStream<>(software.amazon.awssdk.services.s3.model.GetObjectResponse.builder().build(),software.amazon.awssdk.http.AbortableInputStream.create(new java.io.ByteArrayInputStream(bytes))));
        assertThat(planning.download(NEW_USER,task.getId(),evidence.id()).bytes()).isEqualTo(bytes);
        code(()->planning.download(USER,task.getId(),evidence.id()),"WORK_TASK_NOT_FOUND");
        assertThatThrownBy(()->tasks.complete(NEW_USER,NEW_EMP,task.getId(),"Must complete revised requirements")).isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo("WORK_TASK_CHECKLIST_REQUIRED");
    }

    @Test void disabledPreviousParticipantCanBeReplacedWithoutSendingAnUnauthorizedNotice() {
        var task=create("Replace unavailable participant");as(USER,()->tasks.complete(USER,EMP,task.getId(),"Retained author before disable"));
        var original=planning.get(LEAD,task.getId()).submissions().getFirst();jdbc.update("update iam_user_account set enabled=false where id=?",USER);
        transfer(HR,task.getId(),NEW_EMP);drain();assertThat(planning.get(NEW_USER,task.getId()).submissions()).containsExactly(original);
        assertThat(count("select count(*) from work_handover_notice_receipt")).isEqualTo(1);
        assertThat(jdbc.queryForList("select n.recipient_user_id from work_handover_notice_receipt r join internal_call_notification n on n.id=r.notification_id",UUID.class)).containsExactly(NEW_USER);
        assertThatThrownBy(()->planning.get(USER,task.getId())).isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo("ACCOUNT_INACTIVE");
    }

    private boolean attempt(CountDownLatch start,UUID task,long version,UUID target) throws InterruptedException {
        start.await();try { as(LEAD,()->handovers.handover(LEAD,task,new WorkTaskHandoverService.Change(version,target,"Competing assignment",null)));return true; }
        catch(BusinessException conflict) {assertThat(conflict.getErrorCode()).isEqualTo("WORK_TASK_VERSION_CONFLICT");return false;}
    }
    private DepartmentWorkTask create(String title) {return as(LEAD,()->tasks.create(LEAD,new DepartmentWorkTaskService.CreateCommand(EMP,title,"Full controlled handover instructions",today())));}
    private WorkTaskHandoverService.View transfer(UUID actor,UUID task,UUID target) {
        long version=handovers.get(actor,task).taskVersion();return as(actor,()->handovers.handover(actor,task,new WorkTaskHandoverService.Change(version,target,"Controlled assignment change",null)));
    }
    private void notices(UUID task,UUID oldUser,UUID newUser) {
        drain();assertThat(count("select count(*) from work_handover_notice_receipt")).isEqualTo(2);
        var recipients=jdbc.queryForList("select n.recipient_user_id from work_handover_notice_receipt r join internal_call_notification n on n.id=r.notification_id",UUID.class);
        assertThat(recipients).containsExactlyInAnyOrder(oldUser,newUser);
        assertThat(count("select count(distinct event_key) from work_handover_notice_receipt")).isEqualTo(2);
        assertThat(jdbc.queryForList("select n.message from work_handover_notice_receipt r join internal_call_notification n on n.id=r.notification_id",String.class))
                .allMatch(message->message.contains(task.toString())&&!message.contains("Full controlled handover instructions"));
    }
    private void leave(UUID employee,UUID user,String state) {
        jdbc.update("insert into employee_leave_request(id,employee_id,requester_user_id,start_date,end_date,reason,status,decided_by_user_id,decided_at,version,created_at,created_by,updated_at,updated_by) values(?,?,?,?,?,'Personal leave',?,?,now(),0,now(),'sprint9h-test',now(),'sprint9h-test')",
                UUID.randomUUID(),employee,user,java.sql.Date.valueOf(today()),java.sql.Date.valueOf(today()),state,state.equals("APPROVED")?HR:null);
    }
    private void department(UUID id,String code) {jdbc.update("insert into org_department(id,code,name,active,version,created_at,created_by,updated_at,updated_by) values(?,?,?,true,0,now(),'sprint9h-test',now(),'sprint9h-test')",id,code,code);}
    private void employee(UUID id,UUID dep,String name) {jdbc.update("insert into employee(id,employee_number,first_name,last_name,display_name,official_email,department_id,designation,joining_date,status,version,created_at,created_by,updated_at,updated_by) values(?,?,?,?,?,?,?,'Test engineer','2026-01-01','ACTIVE',0,now(),'sprint9h-test',now(),'sprint9h-test')",id,"S9H-"+id.toString().substring(24),name,"Person",name+" Person",name+"@sprint9h.test",dep);}
    private void account(UUID id,UUID emp,String name,String role) {jdbc.update("insert into iam_user_account(id,email,full_name,employee_id,password_hash,enabled,force_password_change,account_status,archived,version,created_at,created_by,updated_at,updated_by) values(?,?,?,?,'test-only-hash',true,false,'ACTIVE',false,0,now(),'sprint9h-test',now(),'sprint9h-test')",id,name+"@sprint9h.test",name,emp);jdbc.update("insert into iam_user_role(user_id,role_name) values(?,?)",id,role);}
    private void assignment(String table,String prefix,UUID user,UUID emp) {jdbc.update("insert into "+table+"(id,department_id,"+prefix+"_user_id,"+prefix+"_employee_id,active,assigned_by_user_id,assigned_at,version,created_at,created_by,updated_at,updated_by) values(?,?,?,?,true,?,now(),0,now(),'sprint9h-test',now(),'sprint9h-test')",UUID.randomUUID(),DEPT,user,emp,ADMIN);}
    private <T>T as(UUID actor,Supplier<T> action) {var old=SecurityContextHolder.getContext();var next=SecurityContextHolder.createEmptyContext();next.setAuthentication(new UsernamePasswordAuthenticationToken(actor.toString(),"",List.of()));SecurityContextHolder.setContext(next);try{T result=action.get();drain();return result;}finally{SecurityContextHolder.setContext(old);}}
    private LocalDate today(){return LocalDate.now(OFFICE);}
    private static UUID id(int n){return UUID.fromString(String.format(Locale.ROOT,"99100000-0000-0000-0000-%012d",n));}
    private long count(String sql,Object...args){return Objects.requireNonNull(jdbc.queryForObject(sql,Long.class,args));}
    private String text(String sql,Object...args){return jdbc.queryForObject(sql,String.class,args);}
    private LocalDate date(String sql,Object...args){return Objects.requireNonNull(jdbc.queryForObject(sql,java.sql.Date.class,args)).toLocalDate();}
    private void code(Runnable action,String expected){assertThatThrownBy(action::run).isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo(expected);}
    private void drain(){if(!(notificationExecutor instanceof ThreadPoolTaskExecutor executor))return;long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);while(executor.getActiveCount()>0||!executor.getThreadPoolExecutor().getQueue().isEmpty()){if(System.nanoTime()>deadline)throw new AssertionError("Notification writes did not settle");try{Thread.sleep(20);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}}}
}
