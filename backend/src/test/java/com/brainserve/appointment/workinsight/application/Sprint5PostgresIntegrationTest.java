package com.brainserve.appointment.workinsight.application;

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
class Sprint5PostgresIntegrationTest {
    @Container static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>("postgres:17.2-alpine");
    @Container static final GenericContainer<?> REDIS=new GenericContainer<>("redis:7.4.1-alpine").withExposedPorts(6379);
    @DynamicPropertySource static void infrastructure(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",POSTGRES::getJdbcUrl);r.add("spring.datasource.username",POSTGRES::getUsername);r.add("spring.datasource.password",POSTGRES::getPassword);
        r.add("spring.data.redis.host",REDIS::getHost);r.add("spring.data.redis.port",()->REDIS.getMappedPort(6379));
    }
    @Autowired JdbcTemplate jdbc; @Autowired NamedParameterJdbcTemplate named;
    @Autowired WorkboardQueryService board; @Autowired DepartmentWorkTaskService tasks; @Autowired WorkInsightService insights;
    @Autowired @org.springframework.beans.factory.annotation.Qualifier("notificationExecutor") java.util.concurrent.Executor notificationExecutor;
    @Autowired CurrentAccountAuthority authority; @Autowired ObjectMapper mapper;
    static final UUID DEPT=id(101), OTHER_DEPT=id(102), EMP=id(201), LEAD_EMP=id(202), HR_EMP=id(203), MANAGER_EMP=id(204), CEO_EMP=id(205), OTHER_EMP=id(206);
    static final UUID USER=id(1), LEAD=id(2), HR=id(3), MANAGER=id(4), CEO=id(5), OTHER=id(6), ADMIN=id(7);
    @BeforeEach void reset() {
        drainNotifications();
        jdbc.update("delete from internal_call_notification where sender_user_id in (select id from iam_user_account where email like '%@sprint5.test') or recipient_user_id in (select id from iam_user_account where email like '%@sprint5.test')");
        jdbc.update("delete from workboard_preference");jdbc.update("delete from work_task_audit_record");jdbc.execute("truncate work_review_stage_event restart identity");jdbc.update("delete from department_work_task");
        // Remove fixture-only stage history after source rows so deferred audit triggers cannot recreate it.
        jdbc.execute("truncate approval_stage cascade");
        jdbc.update("delete from audit_event where event_type like 'WORK_TASK_%' or event_type like 'WORK_INSIGHT_%'");
        jdbc.update("delete from audit_event_history where event_type like 'WORK_TASK_%' or event_type like 'WORK_INSIGHT_%'");
        jdbc.update("delete from department_hr_assignment");jdbc.update("delete from department_manager_assignment");jdbc.update("delete from department_team_lead");
        jdbc.update("delete from iam_user_account where email like '%@sprint5.test'");
        jdbc.update("delete from employee where official_email like '%@sprint5.test'");jdbc.update("delete from org_department where code like 'S5_%'");
        department(DEPT,"S5_MAIN");department(OTHER_DEPT,"S5_OTHER");
        employee(EMP,DEPT,"employee");employee(LEAD_EMP,DEPT,"lead");employee(HR_EMP,DEPT,"hr");employee(MANAGER_EMP,DEPT,"manager");employee(CEO_EMP,DEPT,"ceo");employee(OTHER_EMP,OTHER_DEPT,"other");
        account(USER,EMP,"employee","ROLE_EMPLOYEE");account(LEAD,LEAD_EMP,"lead","ROLE_TEAM_LEAD");account(HR,HR_EMP,"hr","ROLE_HR_ADMIN");
        account(MANAGER,MANAGER_EMP,"manager","ROLE_MANAGER");account(CEO,CEO_EMP,"ceo","ROLE_CEO");account(OTHER,OTHER_EMP,"other","ROLE_EMPLOYEE");account(ADMIN,null,"admin","ROLE_SYSTEM_ADMIN");
        assignment("department_team_lead","team_lead",LEAD,LEAD_EMP);assignment("department_hr_assignment","hr",HR,HR_EMP);assignment("department_manager_assignment","manager",MANAGER,MANAGER_EMP);
    }
    @Test void v55IsAdditiveAndLegacySubmissionEvidenceRemainsUnknown() {
        Flyway flyway=Flyway.configure().dataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword()).load();flyway.validate();
        assertThat(flyway.info().current().getVersion().toString()).isEqualTo("68");assertThat(flyway.migrate().migrationsExecuted).isZero();
        var task=create("Unknown old delivery");jdbc.update("update department_work_task set submission_version=null,status='COMPLETED',completed_at=now() where id=?",task.getId());
        assertThat(board.detail(USER,task.getId()).item().submissionVersion()).isNull();
        assertThat(count("select count(*) from workboard_preference")).isZero();
    }
    @Test void countsAndPagesBeyondLegacyCapsRemainExactAndStableAcrossLayouts() {
        // Create via the production writer, including notification/audit behavior, beyond both 200/500 legacy caps.
        for(int i=0;i<505;i++) create(String.format(Locale.ROOT,"Delivery %03d",i));
        var first=board.list(USER,all(),0,100);var second=board.list(USER,all(),1,100);var last=board.list(HR,all(),5,100);
        assertThat(first.totalElements()).isEqualTo(505);assertThat(first.totalPages()).isEqualTo(6);assertThat(first.items()).hasSize(100);
        assertThat(last.items()).hasSize(5);assertThat(last.totalElements()).isEqualTo(505);
        assertThat(first.counts().scopes().get("ALL")).isEqualTo(505);assertThat(first.counts().quickFilters().get("MY_ACTIONS")).isEqualTo(505);
        assertThat(first.laneCounts()).containsEntry("DELIVERY",505L).containsEntry("REVIEW",0L).containsEntry("CLOSED",0L);
        assertThat(first.items().stream().map(Item::id)).doesNotContainAnyElementsOf(second.items().stream().map(Item::id).toList());
        board.savePreferences(USER,new PreferencesUpdate(0L,Layout.BOARD,Density.COMFORTABLE,List.of()));
        var after=board.list(USER,all(),0,100);assertThat(after.items()).isEqualTo(first.items());assertThat(after.counts()).isEqualTo(first.counts());
        var empty=board.list(USER,new Criteria(Period.ALL,QuickFilter.ALL,"absent%_","ALL","",Sort.DUE_DATE),0,20);
        assertThat(empty.totalElements()).isZero();assertThat(empty.items()).isEmpty();assertThat(empty.counts().scopes().values()).containsOnly(0L);
        assertThat(empty.counts().quickFilters().values()).containsOnly(0L);assertThat(empty.laneCounts().values()).containsOnly(0L);
    }
    @Test void guessedIdsEmployeeRelinkAndChangedAssignmentsNeverExposeDetailsOrCounts() {
        var own=create("Private own delivery");
        tasks.complete(USER,EMP,own.getId(),"Own submission");tasks.approve(LEAD,own.getId(),"Reviewed");insights.markAudited(HR,own.getId());
        assertThat(insights.taskWorkflowStates(USER)).hasSize(1);
        assertThat(board.list(OTHER,all(),0,20).totalElements()).isZero();assertNotFound(()->board.detail(OTHER,own.getId()));assertNotFound(()->board.detail(USER,UUID.randomUUID()));
        assertThatThrownBy(()->board.list(CEO,all(),0,20)).isInstanceOf(BusinessException.class);assertThatThrownBy(()->board.preferences(ADMIN)).isInstanceOf(BusinessException.class);
        jdbc.update("update iam_user_account set employee_id=null where id=?",OTHER);
        jdbc.update("update iam_user_account set employee_id=?,version=version+1 where id=?",OTHER_EMP,USER);
        assertThat(board.list(USER,all(),0,20).totalElements()).isZero();assertNotFound(()->board.detail(USER,own.getId()));
        assertThat(tasks.list(USER,EMP)).isEmpty();assertThat(insights.taskWorkflowStates(USER)).isEmpty();
        jdbc.update("update employee set department_id=?,version=version+1 where id=?",OTHER_DEPT,HR_EMP);
        jdbc.update("update department_hr_assignment set department_id=?,version=version+1 where hr_user_id=?",OTHER_DEPT,HR);
        assertThat(board.list(HR,all(),0,20).totalElements()).isZero();assertNotFound(()->board.detail(HR,own.getId()));
    }
    @Test void permissionRevocationBlocksListDetailPreferencesAndExistingWriters() {
        var own=create("Revoked worksheet");board.savePreferences(USER,new PreferencesUpdate(0L,Layout.LIST,Density.COMPACT,List.of()));
        jdbc.update("insert into iam_user_permission_deny(user_id,permission_name) values(?,'WORK_TASK_READ')",USER);
        assertThatThrownBy(()->board.list(USER,all(),0,20)).isInstanceOf(BusinessException.class);assertThatThrownBy(()->board.detail(USER,own.getId())).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->board.preferences(USER)).isInstanceOf(BusinessException.class);assertThatThrownBy(()->board.savePreferences(USER,new PreferencesUpdate(1L,Layout.BOARD,Density.COMPACT,List.of()))).isInstanceOf(BusinessException.class);
        jdbc.update("insert into iam_user_permission_deny(user_id,permission_name) values(?,'WORK_TASK_PROGRESS')",USER);
        assertThatThrownBy(()->tasks.complete(USER,EMP,own.getId(),"Not authorized",own.getVersion())).isInstanceOf(BusinessException.class);
        assertThat(jdbc.queryForObject("select status from department_work_task where id=?",String.class,own.getId())).isEqualTo("ASSIGNED");
    }
    @Test void literalSearchStatusBranchAndDueTodayHonorExactCriteria() {
        var literal=create("100%_! ready");create("100XX ready");
        assertThat(board.list(USER,new Criteria(Period.ALL,QuickFilter.ALL,"%_!","ALL","",Sort.TITLE),0,20).items()).extracting(Item::id).containsExactly(literal.getId());
        assertThat(board.list(USER,new Criteria(Period.ALL,QuickFilter.ALL,"S5_MAIN","ALL","S5_MAIN",Sort.PRIORITY),0,20).totalElements()).isEqualTo(2);
        tasks.complete(USER,EMP,literal.getId(),"Submitted",literal.getVersion());
        var due=board.list(USER,new Criteria(Period.ALL,QuickFilter.DUE_TODAY,"","COMPLETED","S5_MAIN",Sort.DUE_DATE),0,20);
        assertThat(due.items()).extracting(Item::id).containsExactly(literal.getId()); // Submitted delivery due today still matches DueToday.
        assertThat(due.counts().quickFilters().get("OVERDUE_DELIVERY")).isZero();
        assertThat(board.list(USER,new Criteria(Period.ALL,QuickFilter.ALL,"","ALL","wrong branch",Sort.PRIORITY),0,20).totalElements()).isZero();
        assertThat(due.items().getFirst().priority()).isEqualTo("NORMAL");assertThat(due.items().getFirst().blocked()).isFalse();
    }
    @Test void officeDateHalfOpenBoundsAcrossDstClassifyTodayCarryAndClosedHistory() {
        var before=create("Before boundary");var start=create("Start boundary");var end=create("End boundary");var closed=create("Closed old");
        Instant startAt=Instant.parse("2026-03-08T05:00:00Z"),endAt=Instant.parse("2026-03-09T04:00:00Z");
        timestamp(before.getId(),startAt.minusNanos(1000));timestamp(start.getId(),startAt);timestamp(end.getId(),endAt);timestamp(closed.getId(),startAt.minusSeconds(86400));
        // Close old employee delivery with the real writers and final governance.
        tasks.complete(USER,EMP,closed.getId(),"Done");tasks.approve(LEAD,closed.getId(),"Reviewed");tasks.acknowledge(USER,EMP,closed.getId());
        var audit=insights.markAudited(HR,closed.getId());insights.decideByManager(MANAGER,audit.auditRecordId(),true,"Verified");insights.decideByCeo(CEO,audit.auditRecordId(),true,"Approved");
        var fixed=new WorkboardQueryService(named,authority,mapper,ZoneId.of("America/New_York"),Clock.fixed(Instant.parse("2026-03-08T12:00:00Z"),ZoneOffset.UTC));
        assertThat(fixed.list(USER,new Criteria(Period.TODAY,QuickFilter.ALL,"","ALL","",Sort.DUE_DATE),0,20).items()).extracting(Item::id).containsExactly(start.getId());
        assertThat(fixed.list(USER,new Criteria(Period.CARRY_FORWARD,QuickFilter.ALL,"","ALL","",Sort.DUE_DATE),0,20).items()).extracting(Item::id).containsExactly(before.getId());
        assertThat(fixed.list(USER,new Criteria(Period.HISTORY,QuickFilter.ALL,"","ALL","",Sort.DUE_DATE),0,20).items()).extracting(Item::id).containsExactly(closed.getId());
    }
    @Test void preferencesAreOwnerOnlyDurableAndAtomicOnInitialAndSubsequentRevision() throws Exception {
        SavedFilter filter=new SavedFilter("mine","My actions",Period.ALL,QuickFilter.MY_ACTIONS,"literal%","ALL","",Sort.UPDATED_AT);
        var saved=board.savePreferences(USER,new PreferencesUpdate(0L,Layout.BOARD,Density.COMFORTABLE,List.of(filter)));
        assertThat(saved.revision()).isEqualTo(1);assertThat(board.preferences(OTHER).revision()).isZero();
        var restarted=new WorkboardQueryService(named,authority,mapper,ZoneId.of("Asia/Kolkata"),Clock.systemUTC());assertThat(restarted.preferences(USER)).isEqualTo(saved);
        assertThatThrownBy(()->board.savePreferences(USER,new PreferencesUpdate(0L,Layout.LIST,Density.COMPACT,List.of()))).isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo("WORKBOARD_PREFERENCE_CONFLICT");
        try(var pool=Executors.newFixedThreadPool(2)) {
            CountDownLatch start=new CountDownLatch(1);Callable<Boolean> save=()->{start.await();try{board.savePreferences(USER,new PreferencesUpdate(1L,Layout.LIST,Density.COMPACT,List.of(filter)));return true;}catch(BusinessException ex){assertThat(ex.getErrorCode()).isEqualTo("WORKBOARD_PREFERENCE_CONFLICT");return false;}};
            var a=pool.submit(save);var b=pool.submit(save);start.countDown();assertThat(List.of(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
        }
        assertThat(board.preferences(USER).revision()).isEqualTo(2);assertThat(board.preferences(OTHER).savedFilters()).isEmpty();
    }
    @Test void concurrentObservedSubmissionsConflictWithoutDuplicatingEvidence() throws Exception {
        var task=create("Concurrent submission");long observed=task.getVersion();
        try(var pool=Executors.newFixedThreadPool(2)) {
            var start=new CountDownLatch(1);Callable<Boolean> complete=()->{start.await();try{tasks.complete(USER,EMP,task.getId(),"Delivered",observed);return true;}catch(BusinessException ex){assertThat(ex.getErrorCode()).isEqualTo("WORK_TASK_VERSION_CONFLICT");return false;}};
            var a=pool.submit(complete);var b=pool.submit(complete);start.countDown();assertThat(List.of(a.get(15,TimeUnit.SECONDS),b.get(15,TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
        }
        assertThat(board.detail(USER,task.getId()).item().submissionVersion()).isEqualTo(1L);
        assertThat(count("select count(*) from audit_event where target_id=? and event_type='WORK_TASK_COMPLETED'",task.getId().toString())).isEqualTo(1);
        assertThatThrownBy(()->tasks.start(OTHER,OTHER_EMP,task.getId(),"foreign",observed)).isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo("WORK_TASK_EMPLOYEE_SCOPE_DENIED");
    }
    @Test void employeeDeliveryReviewAndFinalGovernanceStaySeparateIncludingLateAcknowledgement() {
        var task=create("Full governance");
        assertThat(board.detail(USER,task.getId()).item().allowedActions()).containsExactly("start","complete");
        tasks.complete(USER,EMP,task.getId(),"First submission",task.getVersion());
        var submitted=board.detail(LEAD,task.getId()).item();assertThat(submitted.status()).isEqualTo("COMPLETED");assertThat(submitted.lane()).isEqualTo("REVIEW");assertThat(submitted.allowedActions()).contains("approve","request-changes");
        assertThat(board.list(USER,new Criteria(Period.ALL,QuickFilter.AWAITING_MY_REVIEW,"","ALL","",Sort.DUE_DATE),0,20).items()).isEmpty();
        tasks.approve(LEAD,task.getId(),"Delivery review",submitted.version());var ready=board.detail(HR,task.getId()).item();assertThat(ready.allowedActions()).contains("hr-audit","hr-rework");
        var audit=insights.markAudited(HR,task.getId(),ready.version());var revised=board.detail(HR,task.getId()).item();assertThat(revised.version()).isGreaterThan(ready.version());
        assertThatThrownBy(()->insights.requestHrRework(HR,task.getId(),"stale feedback",ready.version())).isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo("WORK_TASK_VERSION_CONFLICT");
        assertThat(board.detail(MANAGER,task.getId()).item().allowedActions()).containsExactly("open-oversight");
        insights.decideByManager(MANAGER,audit.auditRecordId(),true,"Manager checked");assertThat(board.detail(MANAGER,task.getId()).item().allowedActions()).isEmpty();
        insights.decideByCeo(CEO,audit.auditRecordId(),true,"Final approved");
        var employee=board.detail(USER,task.getId()).item();assertThat(employee.allowedActions()).containsExactly("acknowledge");assertThat(employee.lane()).isEqualTo("REVIEW");assertThat(employee.nextActor()).isEqualTo("EMPLOYEE");
        tasks.acknowledge(USER,EMP,task.getId(),employee.version());assertThat(board.detail(USER,task.getId()).item().lane()).isEqualTo("CLOSED");
    }
    @Test void correctionsRemainReworkUntilResubmittedAndIncrementOnlySubmissionText() {
        var task=create("Correction");tasks.complete(USER,EMP,task.getId(),"First");tasks.requestChanges(LEAD,task.getId(),"Fix details");
        tasks.start(USER,EMP,task.getId(),"Correcting");var correcting=board.detail(USER,task.getId()).item();assertThat(correcting.lane()).isEqualTo("REWORK");assertThat(correcting.submissionVersion()).isEqualTo(1);
        assertThat(board.list(USER,new Criteria(Period.ALL,QuickFilter.RETURNED_FOR_REWORK,"","ALL","",Sort.DUE_DATE),0,20).items()).hasSize(1);
        tasks.complete(USER,EMP,task.getId(),"Corrected",correcting.version());var submitted=board.detail(USER,task.getId()).item();assertThat(submitted.lane()).isEqualTo("REVIEW");assertThat(submitted.submissionVersion()).isEqualTo(2);
        assertThat(submitted.allowedActions()).contains("revise-rework");tasks.reviseEmployeeRework(USER,EMP,task.getId(),"Additional correction",submitted.version());assertThat(board.detail(USER,task.getId()).item().submissionVersion()).isEqualTo(3);
    }
    @Test void teamLeadDeliveryCannotSelfReviewAndUsesExistingHrReworkWriters() {
        var task=tasks.create(HR,new DepartmentWorkTaskService.CreateCommand(LEAD_EMP,"Lead delivery","Full instructions for lead",today()));
        tasks.complete(LEAD,LEAD_EMP,task.getId(),"Lead submitted",task.getVersion());var submitted=board.detail(LEAD,task.getId()).item();assertThat(submitted.allowedActions()).doesNotContain("approve","request-changes");
        assertThatThrownBy(()->tasks.approve(LEAD,task.getId(),"self",submitted.version())).isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo("WORK_TASK_SELF_REVIEW_NOT_ALLOWED");
        var returned=insights.requestHrRework(HR,task.getId(),"Clarify results",submitted.version());assertThat(returned.auditStatus()).isEqualTo("HR_REWORK_REQUESTED");
        var plan=board.detail(LEAD,task.getId()).item();assertThat(plan.allowedActions()).contains("insight-rework");insights.assignRework(LEAD,task.getId(),"Correct the results",plan.version());
        tasks.complete(LEAD,LEAD_EMP,task.getId(),"Corrected lead submission");var revise=board.detail(LEAD,task.getId()).item();assertThat(revise.allowedActions()).contains("revise-rework");
        insights.reviseReworkSubmission(LEAD,task.getId(),"Expanded results",revise.version());assertThat(board.detail(LEAD,task.getId()).item().submissionVersion()).isEqualTo(3);
        var hr=board.detail(HR,task.getId()).item();insights.markAudited(HR,task.getId(),hr.version());assertThat(board.detail(LEAD,task.getId()).item().allowedActions()).doesNotContain("revise-rework");
    }
    @Test void missingActiveManagerRemovesHrAuditEligibilityButRetainsRealFeedbackAction() {
        var task=create("No Manager");tasks.complete(USER,EMP,task.getId(),"Done");tasks.approve(LEAD,task.getId(),"Reviewed");
        jdbc.update("update department_manager_assignment set active=false,ended_at=now(),ended_by_user_id=?,version=version+1 where manager_user_id=?",ADMIN,MANAGER);
        jdbc.update("update iam_user_account set enabled=false,account_status='DISABLED' where id=?",MANAGER);
        assertThat(board.detail(HR,task.getId()).item().allowedActions()).contains("hr-rework").doesNotContain("hr-audit");
    }
    @Test void retainedHistoryIsWhitelistedBoundedAndStableWithoutRawAuditDetails() throws Exception {
        var task=create("History");String target=task.getId().toString();
        for(int n=0;n<205;n++) jdbc.update("insert into audit_event(id,occurred_at,actor_id,event_type,target_type,target_id,outcome,details_json) values(?,?,'sensitive-user','WORK_TASK_COMPLETED','WORK_TASK',?,'SUCCESS',cast(? as jsonb))",UUID.randomUUID(),java.sql.Timestamp.from(Instant.parse("2026-01-01T00:00:00Z").plusSeconds(n)),target,"{\"credential\":\"must-not-return\",\"requestPath\":\"/internal\"}");
        jdbc.update("insert into audit_event(id,occurred_at,actor_id,event_type,target_type,target_id,outcome,details_json) values(?,now(),'sensitive-user','LOGIN_FAILED','WORK_TASK',?,'SUCCESS','{}')",UUID.randomUUID(),target);
        var detail=board.detail(USER,task.getId());assertThat(detail.history()).hasSize(200);assertThat(detail.historyTruncated()).isTrue();assertThat(detail.history()).extracting(History::occurredAt).isSorted();
        assertThat(detail.history().getFirst().occurredAt()).isAfter(Instant.parse("2026-01-01T00:00:04Z"));
        assertThat(mapper.writeValueAsString(detail.history())).doesNotContain("credential","must-not-return","requestPath","LOGIN_FAILED","sensitive-user");
        assertNotFound(()->board.detail(OTHER,task.getId()));
    }
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
    private static UUID id(int suffix) {return UUID.fromString(String.format(Locale.ROOT,"65000000-0000-0000-0000-%012d",suffix));}
    private void department(UUID id,String code) {jdbc.update("insert into org_department(id,code,name,active,version,created_at,created_by,updated_at,updated_by) values(?,?,?,true,0,now(),'sprint5-test',now(),'sprint5-test')",id,code,code);}
    private void employee(UUID id,UUID dep,String name) {jdbc.update("insert into employee(id,employee_number,first_name,last_name,display_name,official_email,department_id,designation,joining_date,status,version,created_at,created_by,updated_at,updated_by) values(?,?,?,?,?,?,?,'Test role','2026-01-01','ACTIVE',0,now(),'sprint5-test',now(),'sprint5-test')",id,"S5-"+id.toString().substring(24),name,"Person",name+" Person",name+"@sprint5.test",dep);}
    private void account(UUID id,UUID emp,String name,String role) {jdbc.update("insert into iam_user_account(id,email,full_name,employee_id,password_hash,enabled,force_password_change,account_status,archived,version,created_at,created_by,updated_at,updated_by) values(?,?,?,?,'test-only-hash',true,false,'ACTIVE',false,0,now(),'sprint5-test',now(),'sprint5-test')",id,name+"@sprint5.test",name,emp);jdbc.update("insert into iam_user_role(user_id,role_name) values(?,?)",id,role);}
    private void assignment(String table,String prefix,UUID user,UUID emp) {jdbc.update("insert into "+table+"(id,department_id,"+prefix+"_user_id,"+prefix+"_employee_id,active,assigned_by_user_id,assigned_at,version,created_at,created_by,updated_at,updated_by) values(?,?,?,?,true,?,now(),0,now(),'sprint5-test',now(),'sprint5-test')",UUID.randomUUID(),DEPT,user,emp,ADMIN);}
    private long count(String sql,Object... params) {return Objects.requireNonNull(jdbc.queryForObject(sql,Long.class,params));}
}
