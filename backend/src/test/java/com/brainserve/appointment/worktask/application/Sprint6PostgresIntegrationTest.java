package com.brainserve.appointment.worktask.application;

import com.brainserve.appointment.workinsight.application.WorkboardQueryService;
import com.brainserve.appointment.workinsight.application.WorkInsightService;

import com.brainserve.appointment.iam.api.CurrentAccountAuthority;
import com.brainserve.appointment.shared.application.BusinessException;
import com.brainserve.appointment.worktask.application.DepartmentWorkTaskService;
import com.brainserve.appointment.worktask.domain.DepartmentWorkTask;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static com.brainserve.appointment.workinsight.application.WorkboardQueryService.*;
import com.brainserve.appointment.workinsight.application.WorkboardQueryService.Period;
import static org.assertj.core.api.Assertions.*;

/** Full production application, real delivery/review writers, PostgreSQL and Redis. No mocked persistence/security.
 * The mandatory CI database gate rejects skips; only hosts without Docker skip locally. */
@Testcontainers(disabledWithoutDocker=true)
@SpringBootTest(properties={"brainserve.security.jwt-secret=test-only-secret-key-that-is-at-least-thirty-two-bytes",
        "brainserve.security.pii-encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "brainserve.bootstrap.system-admin-enabled=false","brainserve.bootstrap.ceo-enabled=false",
        "brainserve.appointment.office-zone=Asia/Kolkata","spring.task.scheduling.enabled=false",
        "spring.kafka.listener.auto-startup=false","brainserve.notification.internal-call-dispatch-ms=3600000","brainserve.notification.poll-ms=3600000",
        "aws.s3.access-key=test-access-key","aws.s3.secret-key=test-secret-key"})
class Sprint6PostgresIntegrationTest {
    @Container static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>("postgres:17.2-alpine");
    @Container static final GenericContainer<?> REDIS=new GenericContainer<>("redis:7.4.1-alpine").withExposedPorts(6379);
    @DynamicPropertySource static void infrastructure(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",POSTGRES::getJdbcUrl);r.add("spring.datasource.username",POSTGRES::getUsername);r.add("spring.datasource.password",POSTGRES::getPassword);
        r.add("spring.data.redis.host",REDIS::getHost);r.add("spring.data.redis.port",()->REDIS.getMappedPort(6379));
    }
    @Autowired JdbcTemplate jdbc; @Autowired NamedParameterJdbcTemplate named;
    @Autowired WorkboardQueryService board; @Autowired DepartmentWorkTaskService tasks; @Autowired WorkInsightService insights;
    @Autowired @org.springframework.beans.factory.annotation.Qualifier("notificationExecutor") java.util.concurrent.Executor notificationExecutor;
    @org.springframework.test.context.bean.override.mockito.MockitoBean software.amazon.awssdk.services.s3.S3Client s3;
    @org.springframework.test.context.bean.override.mockito.MockitoBean com.brainserve.appointment.document.infrastructure.ClamAvScanner scanner;
    @Autowired com.brainserve.appointment.document.application.DocumentService documents;
    @Autowired jakarta.persistence.EntityManager entityManager;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
    @Autowired com.brainserve.appointment.worktask.infrastructure.DepartmentWorkTaskRepository repository;
    @Autowired TaskPlanningService planning;
    @Autowired CurrentAccountAuthority authority; @Autowired ObjectMapper mapper;
    static final UUID DEPT=id(101), OTHER_DEPT=id(102), EMP=id(201), LEAD_EMP=id(202), HR_EMP=id(203), MANAGER_EMP=id(204), CEO_EMP=id(205), OTHER_EMP=id(206);
    static final UUID USER=id(1), LEAD=id(2), HR=id(3), MANAGER=id(4), CEO=id(5), OTHER=id(6), ADMIN=id(7);
    @BeforeEach void reset() {
        drainNotifications();
        org.mockito.Mockito.reset(s3,scanner);
        jdbc.update("delete from stored_document where owner_type='WORK_TASK'");
        jdbc.update("delete from internal_call_notification where sender_user_id in (select id from iam_user_account where email like '%@sprint6.test') or recipient_user_id in (select id from iam_user_account where email like '%@sprint6.test')");
        jdbc.update("delete from workboard_preference");jdbc.update("delete from work_task_audit_record");jdbc.update("delete from department_work_task");
        jdbc.update("delete from audit_event where event_type like 'WORK_TASK_%' or event_type like 'WORK_INSIGHT_%'");
        jdbc.update("delete from audit_event_history where event_type like 'WORK_TASK_%' or event_type like 'WORK_INSIGHT_%'");
        jdbc.update("delete from department_hr_assignment");jdbc.update("delete from department_manager_assignment");jdbc.update("delete from department_team_lead");
        jdbc.update("delete from iam_user_account where email like '%@sprint6.test'");
        jdbc.update("delete from employee where official_email like '%@sprint6.test'");jdbc.update("delete from org_department where code like 'S6_%'");
        department(DEPT,"S6_MAIN");department(OTHER_DEPT,"S6_OTHER");
        employee(EMP,DEPT,"employee");employee(LEAD_EMP,DEPT,"lead");employee(HR_EMP,DEPT,"hr");employee(MANAGER_EMP,DEPT,"manager");employee(CEO_EMP,DEPT,"ceo");employee(OTHER_EMP,OTHER_DEPT,"other");
        account(USER,EMP,"employee","ROLE_EMPLOYEE");account(LEAD,LEAD_EMP,"lead","ROLE_TEAM_LEAD");account(HR,HR_EMP,"hr","ROLE_HR_ADMIN");
        account(MANAGER,MANAGER_EMP,"manager","ROLE_MANAGER");account(CEO,CEO_EMP,"ceo","ROLE_CEO");account(OTHER,OTHER_EMP,"other","ROLE_EMPLOYEE");account(ADMIN,null,"admin","ROLE_SYSTEM_ADMIN");
        assignment("department_team_lead","team_lead",LEAD,LEAD_EMP);assignment("department_hr_assignment","hr",HR,HR_EMP);assignment("department_manager_assignment","manager",MANAGER,MANAGER_EMP);
    }
    @Test void migrationDefaultsRetainUnknownOriginalCoverageAndDoNotInventLegacySnapshots() {
        Flyway flyway=Flyway.configure().dataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword()).load();flyway.validate();
        assertThat(flyway.info().current().getVersion().toString()).isEqualTo("60");assertThat(flyway.migrate().migrationsExecuted).isZero();
        var task=create("Legacy boundary");jdbc.update("update department_work_task set original_due_date_known=false,submission_version=null,status='COMPLETED' where id=?",task.getId());
        var p=planning.get(USER,task.getId());assertThat(p.priority()).isEqualTo("NORMAL");assertThat(p.originalDueDateKnown()).isFalse();assertThat(p.submissions()).isEmpty();
    }
    @Test void returnedPlanningRevisionCanImmediatelyTickAndBlockWithoutSelfConflict() {
        var task=create("Sequential revisions");UUID check=UUID.randomUUID();
        var p=configure(task.getId(),false,List.of(new TaskPlanningService.Definition(check,"Verified delivery",true)));
        assertThat(p.taskVersion()).isGreaterThan(task.getVersion());
        var tick=planning.tick(USER,task.getId(),new TaskPlanningService.Tick(p.taskVersion(),List.of(check)));
        assertThat(tick.checklist().getFirst().completed()).isTrue();assertThat(tick.taskVersion()).isGreaterThan(p.taskVersion());
        var raised=planning.raise(USER,task.getId(),new TaskPlanningService.Raise(tick.taskVersion(),"Waiting for dependency",null));
        assertThat(raised.blockers()).hasSize(1);assertThat(raised.blockers().getFirst().contactUserId()).isEqualTo(LEAD);
        assertThat(planning.get(USER,task.getId()).taskVersion()).isEqualTo(raised.taskVersion());
    }
    @Test void equalJsonSnapshotsDoNotAdvanceVersionOnReadsOrUnchangedOrmFlushes() {
        var task=create("Stable JSON revision");UUID check=UUID.randomUUID();var p=configure(task.getId(),false,List.of(new TaskPlanningService.Definition(check,"Same definition",false)));
        long persisted=p.taskVersion();
        assertThat(planning.get(USER,task.getId()).taskVersion()).isEqualTo(persisted);assertThat(planning.get(LEAD,task.getId()).taskVersion()).isEqualTo(persisted);
        new org.springframework.transaction.support.TransactionTemplate(transactionManager).executeWithoutResult(status->{
            var loaded=repository.findById(task.getId()).orElseThrow();entityManager.flush();entityManager.flush();
            assertThat(loaded.getVersion()).isEqualTo(persisted);
        });
        assertThat(planning.get(USER,task.getId()).taskVersion()).isEqualTo(persisted);
        var noop=planning.update(LEAD,task.getId(),new TaskPlanningService.Update(persisted,"NORMAL",null,false,today(),"Confirm unchanged requirements",List.of(new TaskPlanningService.Definition(check,"Same definition",false))));
        assertThat(noop.taskVersion()).isEqualTo(persisted+1);assertThat(planning.get(USER,task.getId()).taskVersion()).isEqualTo(noop.taskVersion());
    }
    @Test void checklistAndEvidenceRequirementsApplyToLegacyCompletionWithoutExpectedVersion() {
        var task=create("Mandatory delivery");UUID check=UUID.randomUUID();var p=configure(task.getId(),true,List.of(new TaskPlanningService.Definition(check,"Required",true)));
        assertCode(()->tasks.complete(USER,EMP,task.getId(),"Attempt"),"WORK_TASK_CHECKLIST_REQUIRED");
        planning.tick(USER,task.getId(),new TaskPlanningService.Tick(p.taskVersion(),List.of(check)));
        assertCode(()->tasks.complete(USER,EMP,task.getId(),"Attempt"),"WORK_TASK_EVIDENCE_REQUIRED");
        assertThat(board.detail(USER,task.getId()).item().status()).isEqualTo("ASSIGNED");
    }
    @Test void snapshotDefinitionsRemainFrozenThroughReworkAndAcceptedSeparatelyFromTick() {
        var task=create("Frozen checklist");UUID check=UUID.randomUUID();var p=configure(task.getId(),false,List.of(new TaskPlanningService.Definition(check,"Original definition",true)));
        var tick=planning.tick(USER,task.getId(),new TaskPlanningService.Tick(p.taskVersion(),List.of(check)));tasks.complete(USER,EMP,task.getId(),"Delivered",tick.taskVersion());
        var submitted=planning.get(LEAD,task.getId());assertThat(submitted.submissions().getFirst().acceptedAt()).isNull();
        tasks.approve(LEAD,task.getId(),"Accepted",submitted.taskVersion());var accepted=planning.get(HR,task.getId());
        assertThat(accepted.submissions().getFirst().acceptedByRole()).isEqualTo("TEAM_LEAD");
        var changed=planning.update(LEAD,task.getId(),new TaskPlanningService.Update(accepted.taskVersion(),"HIGH",90,false,today().plusDays(1),"New requirement",List.of(new TaskPlanningService.Definition(check,"Revised definition",true))));
        assertThat(changed.checklist().getFirst().completed()).isFalse();assertThat(changed.submissions().getFirst().checklist().getFirst().title()).isEqualTo("Original definition");
        assertThat(changed.submissions().getFirst().acceptedAt()).isEqualTo(accepted.submissions().getFirst().acceptedAt());
        assertThat(changed.originalDueDate()).isEqualTo(today());assertThat(changed.dueDate()).isEqualTo(today().plusDays(1));
    }
    @Test void reviseEmployeeWriterChecksRequiredChecklistAndKeepsPriorSubmissions() {
        var task=create("Revised employee");tasks.complete(USER,EMP,task.getId(),"Original");tasks.requestChanges(LEAD,task.getId(),"Correct delivery");tasks.complete(USER,EMP,task.getId(),"Second");
        UUID check=UUID.randomUUID();configure(task.getId(),false,List.of(new TaskPlanningService.Definition(check,"New requirement",true)));
        assertCode(()->tasks.reviseEmployeeRework(USER,EMP,task.getId(),"Third"),"WORK_TASK_CHECKLIST_REQUIRED");
        var p=planning.get(USER,task.getId());planning.tick(USER,task.getId(),new TaskPlanningService.Tick(p.taskVersion(),List.of(check)));tasks.reviseEmployeeRework(USER,EMP,task.getId(),"Third");
        assertThat(planning.get(USER,task.getId()).submissions()).extracting(com.brainserve.appointment.worktask.domain.TaskPlanningState.Submission::version).containsExactly(1L,2L,3L);
    }
    @Test void hrDirectLeadAcceptanceAndInsightRevisionUseTheSameRequirementBoundary() {
        var task=tasks.create(HR,new DepartmentWorkTaskService.CreateCommand(LEAD_EMP,"Lead evidence","Lead instructions",today()));
        tasks.complete(LEAD,LEAD_EMP,task.getId(),"Initial");insights.markAudited(HR,task.getId());
        assertThat(planning.get(LEAD,task.getId()).submissions().getFirst().acceptedByRole()).isEqualTo("HR_ADMIN");
        insights.requestHrRework(HR,task.getId(),"More detail");insights.assignRework(LEAD,task.getId(),"Correct it");tasks.complete(LEAD,LEAD_EMP,task.getId(),"Second");
        UUID check=UUID.randomUUID();configure(task.getId(),false,List.of(new TaskPlanningService.Definition(check,"Required correction",true)));
        assertCode(()->insights.reviseReworkSubmission(LEAD,task.getId(),"Unchecked"),"WORK_TASK_CHECKLIST_REQUIRED");
    }
    @Test void blockedFilterAndPrioritySortUseRealScopedValuesAndKeepDeadlines() {
        var normal=create("Normal");var urgent=create("Urgent");var p=planning.get(LEAD,urgent.getId());
        planning.update(LEAD,urgent.getId(),new TaskPlanningService.Update(p.taskVersion(),"URGENT",null,false,today(),"Urgent work",List.of()));
        p=planning.get(USER,urgent.getId());planning.raise(USER,urgent.getId(),new TaskPlanningService.Raise(p.taskVersion(),"Dependency wait",HR));
        var priority=board.list(USER,new Criteria(Period.ALL,QuickFilter.ALL,"","ALL","",Sort.PRIORITY),0,20);
        assertThat(priority.items()).extracting(Item::id).containsExactly(urgent.getId(),normal.getId());assertThat(priority.items().getFirst().priority()).isEqualTo("URGENT");
        var blocked=board.list(USER,new Criteria(Period.ALL,QuickFilter.BLOCKED,"","ALL","",Sort.DUE_DATE),0,20);
        assertThat(blocked.items()).extracting(Item::id).containsExactly(urgent.getId());assertThat(blocked.counts().quickFilters().get("BLOCKED")).isEqualTo(1);
        assertThat(board.list(OTHER,new Criteria(Period.ALL,QuickFilter.BLOCKED,"","ALL","",Sort.DUE_DATE),0,20).totalElements()).isZero();
        assertThat(planning.get(USER,urgent.getId()).dueDate()).isEqualTo(today());
    }
    @Test void blockerIntervalsContactsAndResolutionStayScopedAndRetained() {
        var task=create("Blocked history");var p=planning.get(USER,task.getId());
        assertCode(()->planning.raise(USER,task.getId(),new TaskPlanningService.Raise(p.taskVersion(),"Foreign contact",OTHER)),"WORK_TASK_PLANNING_INVALID");
        var first=planning.raise(USER,task.getId(),new TaskPlanningService.Raise(p.taskVersion(),"Wait",null));UUID blocker=first.blockers().getFirst().id();
        assertCode(()->planning.raise(USER,task.getId(),new TaskPlanningService.Raise(first.taskVersion(),"Duplicate",null)),"WORK_TASK_ALREADY_BLOCKED");
        assertCode(()->planning.resolve(USER,task.getId(),blocker,new TaskPlanningService.Resolve(first.taskVersion(),"Self resolve")),"WORK_TASK_PERMISSION_DENIED");
        var resolved=planning.resolve(LEAD,task.getId(),blocker,new TaskPlanningService.Resolve(first.taskVersion(),"Dependency received"));
        var reopened=planning.raise(USER,task.getId(),new TaskPlanningService.Raise(resolved.taskVersion(),"New dependency",HR));
        assertThat(reopened.blockers()).hasSize(2);assertThat(reopened.blockers().getFirst().resolvedBy()).isEqualTo(LEAD);assertThat(reopened.blockers().getLast().id()).isNotEqualTo(blocker);
        assertThat(reopened.contactOptions()).extracting(TaskPlanningService.ContactOption::id).doesNotContain(OTHER,ADMIN);
    }
    @Test void permissionDenialsAndForeignIdentifiersPrecedeVersionConflicts() {
        var task=create("Private planning");assertCode(()->planning.get(OTHER,task.getId()),"WORK_TASK_NOT_FOUND");assertCode(()->planning.get(OTHER,UUID.randomUUID()),"WORK_TASK_NOT_FOUND");
        assertCode(()->planning.tick(OTHER,task.getId(),new TaskPlanningService.Tick(999L,List.of())),"WORK_TASK_NOT_FOUND");
        assertCode(()->planning.get(ADMIN,task.getId()),"WORK_TASK_PERMISSION_DENIED");assertCode(()->planning.get(CEO,task.getId()),"WORK_TASK_NOT_FOUND");
        jdbc.update("insert into iam_user_permission_deny(user_id,permission_name) values(?,'WORK_TASK_CREATE')",LEAD);
        assertThat(planning.get(LEAD,task.getId()).permissions().manage()).isFalse();
        assertCode(()->configure(task.getId(),false,List.of()),"WORK_TASK_PERMISSION_DENIED");
        jdbc.update("insert into iam_user_permission_deny(user_id,permission_name) values(?,'WORK_TASK_PROGRESS')",USER);
        assertCode(()->planning.tick(USER,task.getId(),new TaskPlanningService.Tick(task.getVersion(),List.of())),"WORK_TASK_PERMISSION_DENIED");
    }
    @Test void concurrentChecklistUpdatesHaveOneWinnerAndPersistCompleteJson() throws Exception {
        var task=create("Concurrent planning");UUID check=UUID.randomUUID();var p=configure(task.getId(),false,List.of(new TaskPlanningService.Definition(check,"Tick",false)));
        try(var pool=Executors.newFixedThreadPool(2)) {
            var start=new CountDownLatch(1);Callable<Boolean> tick=()->{start.await();try{planning.tick(USER,task.getId(),new TaskPlanningService.Tick(p.taskVersion(),List.of(check)));return true;}catch(BusinessException e){assertThat(e.getErrorCode()).isEqualTo("WORK_TASK_VERSION_CONFLICT");return false;}};
            var a=pool.submit(tick);var b=pool.submit(tick);start.countDown();assertThat(List.of(a.get(15,TimeUnit.SECONDS),b.get(15,TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
        }
        assertThat(planning.get(USER,task.getId()).checklist().getFirst().completed()).isTrue();
    }
    @Test void malformedPlanningUnknownChecklistAndMissingRevisionFailWithBusinessErrors() {
        var task=create("Validation");long v=planning.get(LEAD,task.getId()).taskVersion();
        assertCode(()->planning.update(LEAD,task.getId(),new TaskPlanningService.Update(v,null,null,false,today(),"Reason",List.of())),"WORK_TASK_PLANNING_INVALID");
        assertCode(()->planning.update(LEAD,task.getId(),new TaskPlanningService.Update(v,"LOW",null,false,today(),"Reason",Arrays.asList((TaskPlanningService.Definition)null))),"WORK_TASK_PLANNING_INVALID");
        assertCode(()->planning.tick(USER,task.getId(),new TaskPlanningService.Tick(null,List.of())),"WORK_TASK_PLANNING_INVALID");
        assertCode(()->planning.tick(USER,task.getId(),new TaskPlanningService.Tick(v,List.of(UUID.randomUUID()))),"WORK_TASK_PLANNING_INVALID");
    }
    @Test void finalGovernanceClosesDraftMutationButKeepsFrozenHistoryReadable() {
        var task=create("Final delivery");tasks.complete(USER,EMP,task.getId(),"Delivered");tasks.approve(LEAD,task.getId(),"Accepted");
        var audit=insights.markAudited(HR,task.getId());insights.decideByManager(MANAGER,audit.auditRecordId(),true,"Reviewed");insights.decideByCeo(CEO,audit.auditRecordId(),true,"Final");
        var p=planning.get(USER,task.getId());assertThat(p.permissions().progress()).isFalse();assertThat(p.permissions().upload()).isFalse();assertThat(planning.get(LEAD,task.getId()).permissions().manage()).isFalse();
        assertThat(planning.get(CEO,task.getId()).submissions()).hasSize(1);assertCode(()->planning.tick(USER,task.getId(),new TaskPlanningService.Tick(p.taskVersion(),List.of())),"WORK_TASK_PERMISSION_DENIED");
    }
    @Test void uploadedEvidenceIsPrivateRetainedAfterUnlinkAndFrozenAcrossRework() throws Exception {
        var task=create("Evidence lifecycle");byte[] bytes="%PDF-delivery".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        var uploaded=planning.upload(USER,task.getId(),planning.get(USER,task.getId()).taskVersion(),file(bytes));
        var evidence=uploaded.evidence().getFirst();assertThat(evidence.sha256()).hasSize(64);assertThat(count("select count(*) from stored_document where id=? and owner_type='WORK_TASK' and status='CLEAN'",evidence.documentId())).isEqualTo(1);
        tasks.complete(USER,EMP,task.getId(),"First",uploaded.taskVersion());tasks.approve(LEAD,task.getId(),"Accepted");
        assertCode(()->documents.delete(USER,evidence.documentId()),"DOCUMENT_NOT_FOUND");
        var audit=insights.requestHrRework(HR,task.getId(),"Revise evidence");insights.assignRework(LEAD,task.getId(),"New version");
        var p=planning.get(USER,task.getId());planning.remove(USER,task.getId(),evidence.id(),p.taskVersion());
        tasks.complete(USER,EMP,task.getId(),"Second");var snapshots=planning.get(USER,task.getId()).submissions();
        assertThat(snapshots.getFirst().evidence()).containsExactly(evidence);assertThat(snapshots.getFirst().acceptedByRole()).isEqualTo("TEAM_LEAD");assertThat(snapshots.getLast().evidence()).isEmpty();
        org.mockito.Mockito.when(s3.getObject(org.mockito.ArgumentMatchers.any(software.amazon.awssdk.services.s3.model.GetObjectRequest.class))).thenReturn(new software.amazon.awssdk.core.ResponseInputStream<>(software.amazon.awssdk.services.s3.model.GetObjectResponse.builder().build(),software.amazon.awssdk.http.AbortableInputStream.create(new java.io.ByteArrayInputStream(bytes))));
        assertThat(planning.download(LEAD,task.getId(),evidence.id()).bytes()).isEqualTo(bytes);
        assertThat(count("select count(*) from audit_event where target_id=? and event_type='DOCUMENT_READ'",task.getId().toString())).isEqualTo(1);
        assertThat(count("select count(*) from audit_event where target_id=? and event_type='WORK_TASK_EVIDENCE_READ'",task.getId().toString())).isEqualTo(1);
        assertCode(()->planning.download(OTHER,task.getId(),evidence.id()),"WORK_TASK_NOT_FOUND");
        assertThat(count("select count(*) from audit_event where target_id=? and event_type in ('DOCUMENT_READ','WORK_TASK_EVIDENCE_READ')",task.getId().toString())).isEqualTo(2);
        org.mockito.Mockito.verify(s3,org.mockito.Mockito.never()).deleteObject(org.mockito.ArgumentMatchers.any(software.amazon.awssdk.services.s3.model.DeleteObjectRequest.class));
    }
    @Test void scannerFailureAndWrongMagicLeaveNoDatabaseOrObjectEvidence() {
        var task=create("Secure scan");long v=planning.get(USER,task.getId()).taskVersion();
        assertCode(()->planning.upload(USER,task.getId(),v,file(new byte[]{1,2,3})),"DOCUMENT_CONTENT_MISMATCH");
        org.mockito.Mockito.doThrow(new BusinessException("MALWARE_SCANNER_UNAVAILABLE","Unavailable",org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE)).when(scanner).assertClean(org.mockito.ArgumentMatchers.any());
        assertCode(()->planning.upload(USER,task.getId(),v,file("%PDF-file".getBytes())),"MALWARE_SCANNER_UNAVAILABLE");
        assertThat(planning.get(USER,task.getId()).evidence()).isEmpty();assertThat(count("select count(*) from stored_document where owner_id=?",task.getId())).isZero();org.mockito.Mockito.verifyNoInteractions(s3);
    }
    @Test void permissionRevokedDuringStorageRollsBackLinkAndCleansPrivateObject() {
        var task=create("Upload revocation");long v=planning.get(USER,task.getId()).taskVersion();
        org.mockito.Mockito.when(s3.putObject(org.mockito.ArgumentMatchers.any(software.amazon.awssdk.services.s3.model.PutObjectRequest.class),org.mockito.ArgumentMatchers.any(software.amazon.awssdk.core.sync.RequestBody.class))).thenAnswer(call->{jdbc.update("insert into iam_user_permission_deny(user_id,permission_name) values(?,'WORK_TASK_PROGRESS')",USER);return software.amazon.awssdk.services.s3.model.PutObjectResponse.builder().build();});
        assertCode(()->planning.upload(USER,task.getId(),v,file("%PDF-file".getBytes())),"WORK_TASK_PERMISSION_DENIED");
        assertThat(count("select count(*) from stored_document where owner_id=?",task.getId())).isZero();assertThat(planning.get(USER,task.getId()).evidence()).isEmpty();
        org.mockito.Mockito.verify(s3).deleteObject(org.mockito.ArgumentMatchers.any(software.amazon.awssdk.services.s3.model.DeleteObjectRequest.class));
    }
    @Test void historyIsBoundedAndTruncationExplicitWithoutDroppingRetainedVersions() throws Exception {
        var task=create("Bounded history");var state=new com.brainserve.appointment.worktask.domain.TaskPlanningState();
        for(int n=0;n<55;n++)state.capture(n+1,Instant.parse("2026-01-01T00:00:00Z").plusSeconds(n));
        for(int n=0;n<105;n++)state.blockers.add(new com.brainserve.appointment.worktask.domain.TaskPlanningState.Blocker(UUID.randomUUID(),"Historical blocker",LEAD,Instant.now(),Instant.now(),USER,LEAD,"Resolved"));
        jdbc.update("update department_work_task set planning_state=cast(? as jsonb) where id=?",mapper.writeValueAsString(state),task.getId());
        var p=planning.get(USER,task.getId());assertThat(p.submissions()).hasSize(50);assertThat(p.blockers()).hasSize(100);assertThat(p.submissionsTruncated()).isTrue();assertThat(p.blockersTruncated()).isTrue();
        assertThat(count("select jsonb_array_length(planning_state->'submissions') from department_work_task where id=?",task.getId())).isEqualTo(55);
    }
    private org.springframework.mock.web.MockMultipartFile file(byte[] bytes){return new org.springframework.mock.web.MockMultipartFile("file","delivery.pdf","application/pdf",bytes);}
    private TaskPlanningService.Planning configure(UUID task,boolean evidence,List<TaskPlanningService.Definition> checklist) {
        return planning.update(LEAD,task,new TaskPlanningService.Update(planning.get(LEAD,task).taskVersion(),"NORMAL",null,evidence,today(),"Requirements",checklist));
    }
    private void assertCode(Runnable action,String code){assertThatThrownBy(action::run).isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo(code);}
    @AfterEach void finishNotifications() { drainNotifications(); }
    private void drainNotifications() {
        var executor=(org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor)notificationExecutor;
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
        while(executor.getActiveCount()>0 || !executor.getThreadPoolExecutor().getQueue().isEmpty()) {
            if(System.nanoTime()>deadline) throw new AssertionError("Production notification writes did not settle before fixture cleanup");
            try { Thread.sleep(20); } catch(InterruptedException ex) {Thread.currentThread().interrupt();throw new AssertionError(ex);}
        }
    }
    private DepartmentWorkTask create(String title) {return tasks.create(LEAD,new DepartmentWorkTaskService.CreateCommand(EMP,title,"Full delivery instructions",today()));}
    private Criteria all() {return new Criteria(Period.ALL,QuickFilter.ALL,"","ALL","",Sort.DUE_DATE);}
    private LocalDate today() {return LocalDate.now(ZoneId.of("Asia/Kolkata"));}
    private void timestamp(UUID task,Instant at) {jdbc.update("update department_work_task set created_at=? where id=?",java.sql.Timestamp.from(at),task);}
    private void assertNotFound(Runnable action) {assertThatThrownBy(action::run).isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo("WORK_TASK_NOT_FOUND");}
    private static UUID id(int suffix) {return UUID.fromString(String.format(Locale.ROOT,"66000000-0000-0000-0000-%012d",suffix));}
    private void department(UUID id,String code) {jdbc.update("insert into org_department(id,code,name,active,version,created_at,created_by,updated_at,updated_by) values(?,?,?,true,0,now(),'sprint6-test',now(),'sprint6-test')",id,code,code);}
    private void employee(UUID id,UUID dep,String name) {jdbc.update("insert into employee(id,employee_number,first_name,last_name,display_name,official_email,department_id,designation,joining_date,status,version,created_at,created_by,updated_at,updated_by) values(?,?,?,?,?,?,?,'Test role','2026-01-01','ACTIVE',0,now(),'sprint6-test',now(),'sprint6-test')",id,"S6-"+id.toString().substring(24),name,"Person",name+" Person",name+"@sprint6.test",dep);}
    private void account(UUID id,UUID emp,String name,String role) {jdbc.update("insert into iam_user_account(id,email,full_name,employee_id,password_hash,enabled,force_password_change,account_status,archived,version,created_at,created_by,updated_at,updated_by) values(?,?,?,?,'test-only-hash',true,false,'ACTIVE',false,0,now(),'sprint6-test',now(),'sprint6-test')",id,name+"@sprint6.test",name,emp);jdbc.update("insert into iam_user_role(user_id,role_name) values(?,?)",id,role);}
    private void assignment(String table,String prefix,UUID user,UUID emp) {jdbc.update("insert into "+table+"(id,department_id,"+prefix+"_user_id,"+prefix+"_employee_id,active,assigned_by_user_id,assigned_at,version,created_at,created_by,updated_at,updated_by) values(?,?,?,?,true,?,now(),0,now(),'sprint6-test',now(),'sprint6-test')",UUID.randomUUID(),DEPT,user,emp,ADMIN);}
    private long count(String sql,Object... params) {return Objects.requireNonNull(jdbc.queryForObject(sql,Long.class,params));}
}
