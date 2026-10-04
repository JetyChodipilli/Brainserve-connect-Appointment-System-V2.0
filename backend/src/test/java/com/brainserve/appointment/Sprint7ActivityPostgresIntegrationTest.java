package com.brainserve.appointment;

import com.brainserve.appointment.worktask.application.*;
import com.brainserve.appointment.worktask.api.TaskActivityAccess;
import com.brainserve.appointment.workinsight.application.*;
import com.brainserve.appointment.worktask.domain.DepartmentWorkTask;
import com.brainserve.appointment.iam.api.CurrentAccountAuthority;
import com.brainserve.appointment.shared.application.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import static org.assertj.core.api.Assertions.*;

/** PostgreSQL/Redis against the production services, real current-account checks and history triggers. */
@Testcontainers(disabledWithoutDocker=true)
@SpringBootTest(properties={"brainserve.security.jwt-secret=test-only-secret-key-that-is-at-least-thirty-two-bytes",
        "brainserve.security.pii-encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "brainserve.bootstrap.system-admin-enabled=false","brainserve.bootstrap.ceo-enabled=false",
        "brainserve.appointment.office-zone=Asia/Kolkata","spring.task.scheduling.enabled=false",
        "spring.kafka.listener.auto-startup=false","brainserve.notification.internal-call-dispatch-ms=3600000","brainserve.notification.poll-ms=3600000",
        "aws.s3.access-key=test-access-key","aws.s3.secret-key=test-secret-key"})
class Sprint7ActivityPostgresIntegrationTest {
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
    @Autowired com.brainserve.appointment.worktask.application.TaskActivityService activity;
    @Autowired com.brainserve.appointment.appointment.application.AppointmentTimelineService visits;
    @Autowired CurrentAccountAuthority authority; @Autowired ObjectMapper mapper;
    static final UUID DEPT=id(101), OTHER_DEPT=id(102), EMP=id(201), LEAD_EMP=id(202), HR_EMP=id(203), MANAGER_EMP=id(204), CEO_EMP=id(205), OTHER_EMP=id(206);
    static final UUID USER=id(1), LEAD=id(2), HR=id(3), MANAGER=id(4), CEO=id(5), OTHER=id(6), ADMIN=id(7), UNRELATED=id(8), UNRELATED_EMP=id(207);
    @BeforeEach void reset() {
        drainNotifications();
        org.mockito.Mockito.reset(s3,scanner);
        jdbc.update("delete from stored_document where owner_type='WORK_TASK'");
        jdbc.update("delete from internal_call_notification where sender_user_id in (select id from iam_user_account where email like '%@sprint7activity.test') or recipient_user_id in (select id from iam_user_account where email like '%@sprint7activity.test')");
        jdbc.update("delete from appointment where visitor_email like '%@sprint7activity.test'");
        jdbc.update("delete from workboard_preference");jdbc.update("delete from work_task_audit_record");jdbc.execute("truncate work_review_stage_event restart identity");jdbc.update("delete from department_work_task");
        jdbc.update("delete from audit_event where event_type like 'WORK_TASK_%' or event_type like 'WORK_INSIGHT_%'");
        jdbc.update("delete from audit_event_history where event_type like 'WORK_TASK_%' or event_type like 'WORK_INSIGHT_%'");
        jdbc.update("delete from department_hr_assignment");jdbc.update("delete from department_manager_assignment");jdbc.update("delete from department_team_lead");
        jdbc.update("delete from iam_user_account where email like '%@sprint7activity.test'");
        jdbc.update("delete from employee where official_email like '%@sprint7activity.test'");jdbc.update("delete from org_department where code like 'S7A_%'");
        department(DEPT,"S7A_MAIN");department(OTHER_DEPT,"S7A_OTHER");
        employee(EMP,DEPT,"employee");employee(LEAD_EMP,DEPT,"lead");employee(HR_EMP,DEPT,"hr");employee(MANAGER_EMP,DEPT,"manager");employee(CEO_EMP,DEPT,"ceo");employee(OTHER_EMP,OTHER_DEPT,"other");
        employee(UNRELATED_EMP,DEPT,"unrelated");account(UNRELATED,UNRELATED_EMP,"unrelated","ROLE_EMPLOYEE");
        account(USER,EMP,"employee","ROLE_EMPLOYEE");account(LEAD,LEAD_EMP,"lead","ROLE_TEAM_LEAD");account(HR,HR_EMP,"hr","ROLE_HR_ADMIN");
        account(MANAGER,MANAGER_EMP,"manager","ROLE_MANAGER");account(CEO,CEO_EMP,"ceo","ROLE_CEO");account(OTHER,OTHER_EMP,"other","ROLE_EMPLOYEE");account(ADMIN,null,"admin","ROLE_SYSTEM_ADMIN");
        assignment("department_team_lead","team_lead",LEAD,LEAD_EMP);assignment("department_hr_assignment","hr",HR,HR_EMP);assignment("department_manager_assignment","manager",MANAGER,MANAGER_EMP);
    }

    private TaskActivityService.Create request(String body,UUID key,UUID... mentions) {return new TaskActivityService.Create(key,body,List.of(mentions),List.of());}
    private TaskActivityService.Comment comment(UUID task,String body,UUID... mentions) {return as(USER,()->activity.create(USER,task,request(body,UUID.randomUUID(),mentions)));}
    @Test void foreignRemovedAndUnrelatedAccountsCannotReadOrCommentAndCeoIsReadOnly() {
        var task=create("Scoped discussion");comment(task.getId(),"Within task",LEAD);
        assertCode(()->activity.comments(OTHER,task.getId(),0,50),"WORK_TASK_NOT_FOUND");
        assertCode(()->activity.create(OTHER,task.getId(),request("Cross department",UUID.randomUUID())),"WORK_TASK_NOT_FOUND");
        assertCode(()->activity.comments(UNRELATED,task.getId(),0,50),"WORK_TASK_NOT_FOUND");
        assertCode(()->activity.comments(USER,UUID.randomUUID(),0,50),"WORK_TASK_NOT_FOUND");
        assertCode(()->activity.timeline(ADMIN,task.getId(),0,50),"WORK_TASK_PERMISSION_DENIED");
        jdbc.update("update iam_user_account set enabled=false where id=?",USER);
        assertCode(()->activity.comments(USER,task.getId(),0,50),"ACCOUNT_INACTIVE");
        assertCode(()->activity.create(USER,task.getId(),request("Removed account",UUID.randomUUID())),"ACCOUNT_INACTIVE");
        jdbc.update("update iam_user_account set enabled=true where id=?",USER);
        jdbc.update("update department_team_lead set active=false,ended_at=now(),ended_by_user_id=?,version=version+1 where team_lead_user_id=?",ADMIN,LEAD);
        assertCode(()->activity.comments(LEAD,task.getId(),0,50),"ACCOUNT_INACTIVE");
        jdbc.update("update department_team_lead set active=true,ended_at=null,ended_by_user_id=null,version=version+1 where team_lead_user_id=?",LEAD);
        tasks.complete(USER,EMP,task.getId(),"Delivered");tasks.approve(LEAD,task.getId(),"Approved");
        var reviewed=insights.markAudited(HR,task.getId());insights.decideByManager(MANAGER,reviewed.auditRecordId(),true,"Reviewed");
        assertThat(activity.comments(CEO,task.getId(),0,50).canComment()).isFalse();
        assertCode(()->activity.create(CEO,task.getId(),request("Oversight",UUID.randomUUID())),"WORK_TASK_PERMISSION_DENIED");
    }
    @Test void plainTextEditsAndRemovalRetainOriginalRevisionsAndBusinessApprovals() {
        var task=create("Retained business decisions");tasks.complete(USER,EMP,task.getId(),"Delivery");tasks.approve(LEAD,task.getId(),"Accepted delivery");
        long originalApprovals=count("select count(*) from audit_event_history where target_id=? and event_type='WORK_TASK_APPROVED'",task.getId().toString());
        String html="<script>alert('inert')</script>\nLine two";var created=comment(task.getId(),html,LEAD);
        assertThat(activity.comments(LEAD,task.getId(),0,50).comments().getFirst().body()).isEqualTo(html);
        var edited=as(USER,()->activity.edit(USER,task.getId(),created.id(),new TaskActivityService.Edit(0L,"Corrected text",List.of(LEAD),List.of())));
        assertThat(edited.version()).isEqualTo(1);assertThat(edited.editedAt()).isNotNull();
        assertCode(()->activity.edit(LEAD,task.getId(),created.id(),new TaskActivityService.Edit(1L,"Not mine",List.of(),List.of())),"WORK_TASK_PERMISSION_DENIED");
        assertCode(()->activity.edit(USER,task.getId(),created.id(),new TaskActivityService.Edit(0L,"Stale",List.of(),List.of())),"WORK_TASK_COMMENT_CONFLICT");
        var removed=as(USER,()->activity.remove(USER,task.getId(),created.id(),1));
        assertThat(removed.body()).isNull();assertThat(removed.mentions()).isEmpty();assertThat(removed.attachments()).isEmpty();assertThat(removed.deletedAt()).isNotNull();
        assertThat(jdbc.queryForList("select body from task_comment_revision where comment_id=? order by version",String.class,created.id())).containsExactly(html,"Corrected text","Corrected text");
        assertThat(count("select count(*) from audit_event_history where target_id=? and event_type='WORK_TASK_APPROVED'",task.getId().toString())).isEqualTo(originalApprovals);
        assertThat(board.detail(USER,task.getId()).item().status()).isEqualTo("APPROVED");
        assertThat(jdbc.queryForList("select details_json::text from audit_event_history where target_id=? and event_type like 'WORK_TASK_COMMENT_%'",String.class,task.getId().toString())).noneMatch(value->value.contains("inert")||value.contains("Corrected text"));
    }
    @Test void mentionsAreCurrentParticipantsAndNotificationsCommitWithoutBodyBroadcast() {
        var task=create("Mentions");
        assertCode(()->comment(task.getId(),"Not allowed",OTHER),"WORK_TASK_COMMENT_INVALID");
        assertCode(()->comment(task.getId(),"Not assigned",UNRELATED),"WORK_TASK_COMMENT_INVALID");
        assertCode(()->comment(task.getId(),"Self",USER),"WORK_TASK_COMMENT_INVALID");
        assertThat(activity.comments(USER,task.getId(),0,50).participants()).extracting(TaskActivityService.Participant::id).contains(LEAD,HR,MANAGER).doesNotContain(OTHER,UNRELATED);
        var tx=new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        tx.executeWithoutResult(status->{comment(task.getId(),"Rolled back confidential body",HR);assertThat(count("select count(*) from internal_call_notification where message like '%Reference %'")).isZero();status.setRollbackOnly();});
        drainNotifications();assertThat(count("select count(*) from task_comment where work_task_id=?",task.getId())).isZero();
        var created=comment(task.getId(),"Do not send this confidential body",HR,MANAGER);drainNotifications();
        var messages=jdbc.queryForList("select message from internal_call_notification where message like ?",String.class,"%Reference "+created.id()+".%");
        assertThat(messages).hasSize(3).allMatch(text->text.contains(task.getId().toString())&&!text.contains("confidential"));
        assertThat(activity.timeline(USER,task.getId(),0,50).events()).filteredOn(e->"COMMENT_NOTIFICATION".equals(e.eventType())).hasSize(3).allMatch(e->Set.of("QUEUED","DELIVERED","FAILED").contains(e.deliveryStatus()));
        jdbc.update("update iam_user_account set enabled=false where id=?",HR);
        assertCode(()->comment(task.getId(),"Removed mention",HR),"WORK_TASK_COMMENT_INVALID");
    }
    @Test void sameRequestReplayIsIdempotentAndDifferentPayloadConflicts() throws Exception {
        var task=create("Idempotent comments");UUID key=UUID.randomUUID();var command=request("One body",key,LEAD);
        var first=as(USER,()->activity.create(USER,task.getId(),command));
        assertThat(as(USER,()->activity.create(USER,task.getId(),command)).id()).isEqualTo(first.id());
        assertCode(()->activity.create(USER,task.getId(),request("Changed",key,LEAD)),"WORK_TASK_COMMENT_CONFLICT");
        assertCode(()->activity.create(USER,task.getId(),null),"WORK_TASK_COMMENT_INVALID");
        assertCode(()->activity.create(USER,task.getId(),request("Bad\0text",UUID.randomUUID())),"WORK_TASK_COMMENT_INVALID");
        UUID concurrentKey=UUID.randomUUID();try(var pool=Executors.newFixedThreadPool(2)) {
            var start=new CountDownLatch(1);Callable<UUID> writer=()->{start.await();return as(USER,()->activity.create(USER,task.getId(),request("Concurrent",concurrentKey))).id();};
            var a=pool.submit(writer);var b=pool.submit(writer);start.countDown();assertThat(a.get(15,TimeUnit.SECONDS)).isEqualTo(b.get(15,TimeUnit.SECONDS));
        }
        drainNotifications();assertThat(count("select count(*) from task_comment where work_task_id=?",task.getId())).isEqualTo(2);
        assertThat(count("select count(*) from task_comment_revision where comment_id=?",first.id())).isEqualTo(1);
    }
    @Test void concurrentEditsHaveOneWinnerAndNeverOverwriteARevision() throws Exception {
        var task=create("Concurrent discussion");var created=comment(task.getId(),"Original");
        try(var pool=Executors.newFixedThreadPool(2)) {
            var start=new CountDownLatch(1);Callable<Boolean> writer=()->{start.await();try{as(USER,()->activity.edit(USER,task.getId(),created.id(),new TaskActivityService.Edit(0L,"Edited",List.of(),List.of())));return true;}catch(BusinessException e){assertThat(e.getErrorCode()).isEqualTo("WORK_TASK_COMMENT_CONFLICT");return false;}};
            var a=pool.submit(writer);var b=pool.submit(writer);start.countDown();assertThat(List.of(a.get(15,TimeUnit.SECONDS),b.get(15,TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
        }
        assertThat(jdbc.queryForList("select version from task_comment_revision where comment_id=? order by version",Long.class,created.id())).containsExactly(0L,1L);
    }
    @Test void privateScannedEvidenceSurvivesDraftUnlinkAndForeignLinksAreRejected() {
        var task=create("Private linked evidence");byte[] bytes="%PDF-comment".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        var upload=planning.upload(USER,task.getId(),planning.get(USER,task.getId()).taskVersion(),new org.springframework.mock.web.MockMultipartFile("file","private.pdf","application/pdf",bytes));
        var evidence=upload.evidence().getFirst();
        assertCode(()->activity.create(USER,task.getId(),new TaskActivityService.Create(UUID.randomUUID(),"Foreign attachment",List.of(),List.of(UUID.randomUUID()))),"WORK_TASK_COMMENT_INVALID");
        var created=as(USER,()->activity.create(USER,task.getId(),new TaskActivityService.Create(UUID.randomUUID(),"Private attachment",List.of(LEAD),List.of(evidence.id()))));
        planning.remove(USER,task.getId(),evidence.id(),upload.taskVersion());
        org.mockito.Mockito.when(s3.getObject(org.mockito.ArgumentMatchers.any(software.amazon.awssdk.services.s3.model.GetObjectRequest.class))).thenReturn(new software.amazon.awssdk.core.ResponseInputStream<>(software.amazon.awssdk.services.s3.model.GetObjectResponse.builder().build(),software.amazon.awssdk.http.AbortableInputStream.create(new java.io.ByteArrayInputStream(bytes))));
        assertThat(activity.download(LEAD,task.getId(),created.id(),evidence.id()).bytes()).isEqualTo(bytes);
        assertCode(()->activity.download(OTHER,task.getId(),created.id(),evidence.id()),"WORK_TASK_NOT_FOUND");
        assertCode(()->activity.download(LEAD,task.getId(),UUID.randomUUID(),evidence.id()),"WORK_TASK_COMMENT_NOT_FOUND");
        assertThat(activity.comments(LEAD,task.getId(),0,50).comments().getFirst().attachments()).containsExactly(evidence);
        activity.remove(USER,task.getId(),created.id(),0);
        assertCode(()->activity.download(LEAD,task.getId(),created.id(),evidence.id()),"WORK_TASK_COMMENT_NOT_FOUND");
        assertThat(count("select count(*) from task_comment_revision where comment_id=? and attachments->0->>'sha256'=?",created.id(),evidence.sha256())).isEqualTo(2);
    }
    @Test void timelineReplaysStableSameTimeOrderAndHistoricalActorSnapshotsAfterRename() {
        var task=create("Retained timeline");UUID first=UUID.fromString("77000000-0000-0000-0000-000000999991"),second=UUID.fromString("77000000-0000-0000-0000-000000999992");
        Instant at=Instant.now();for(UUID id:List.of(second,first))jdbc.update("insert into audit_event(id,occurred_at,actor_id,event_type,target_type,target_id,outcome,correlation_id,details_json) values(?,?,?,'WORK_TASK_PLANNING_UPDATED','WORK_TASK',?,'SUCCESS','stable-correlation','{}'::jsonb)",id,java.sql.Timestamp.from(at),USER.toString(),task.getId().toString());
        jdbc.update("update iam_user_account set full_name='Renamed now' where id=?",USER);
        var before=activity.timeline(USER,task.getId(),0,100);var after=activity.timeline(USER,task.getId(),0,100);
        assertThat(after.events()).extracting(com.brainserve.appointment.audit.api.ActivityHistory.Event::id).containsExactlyElementsOf(before.events().stream().map(com.brainserve.appointment.audit.api.ActivityHistory.Event::id).toList());
        var sameTime=after.events().stream().filter(e->e.id().equals("audit:"+first)||e.id().equals("audit:"+second)).toList();
        assertThat(sameTime).extracting(com.brainserve.appointment.audit.api.ActivityHistory.Event::id).containsExactly("audit:"+first,"audit:"+second);
        assertThat(sameTime).allMatch(e->e.actor().snapshotRecorded()&&e.actor().name().equals("employee")&&e.correlationId().equals("stable-correlation"));
        // Live/partitioned copies are deduplicated. Deleting the hot copy retains the same public identity.
        jdbc.update("delete from audit_event where id=?",first);
        assertThat(activity.timeline(USER,task.getId(),0,100).events()).extracting(com.brainserve.appointment.audit.api.ActivityHistory.Event::id).contains("audit:"+first);
        tasks.complete(USER,EMP,task.getId(),"Version one");tasks.requestChanges(LEAD,task.getId(),"Correct");tasks.complete(USER,EMP,task.getId(),"Version two");
        assertThat(activity.timeline(USER,task.getId(),0,100).events()).filteredOn(e->"STATUS_CHANGED".equals(e.eventType())&&e.evidenceVersion()!=null).extracting(com.brainserve.appointment.audit.api.ActivityHistory.Event::evidenceVersion).contains(1L,2L);
        var head=activity.timeline(USER,task.getId(),0,1);assertThat(head.hasMore()).isTrue();assertThat(head.events()).hasSize(1);
    }
    @Test void visitTimelineUsesCurrentHostScopeCheckpointSnapshotsAndHonestEmailReceiptState() {
        UUID id=UUID.randomUUID();Instant from=Instant.now().plusSeconds(3600);
        jdbc.update("""
            insert into appointment(id,reference_number,idempotency_key,type,status,visitor_name,visitor_email,visitor_phone,host_employee_id,routing_department_id,slot_start,slot_end,purpose,version,created_at,created_by,updated_at,updated_by)
            values(?,?,?,'CLIENT_MEETING','APPROVED','Test Visitor','visitor@sprint7activity.test','9999999999',?,?,?,?,'Timeline test',0,now(),'test',now(),'test')
            """,id,"S7A-"+id,id.toString(),EMP,DEPT,java.sql.Timestamp.from(from),java.sql.Timestamp.from(from.plusSeconds(1800)));
        jdbc.update("insert into visitor_checkpoint_event(occurred_at,appointment_id,access_record_id,department_id,visitor_name,badge_number,event_type,actor_id) values(now(),?,?,?,'Test Visitor','S7A-123','CHECKED_IN',?)",id,UUID.randomUUID(),DEPT,USER.toString());
        jdbc.update("""
            insert into notification_outbox(id,event_key,channel,destination,template,payload_json,status,attempt_count,next_attempt_at,version,created_at,created_by,updated_at,updated_by)
            values(?,?,'EMAIL','visitor@sprint7activity.test','APPOINTMENT_OTP','{}'::jsonb,'PENDING',0,now(),0,now(),'test',now(),'test')
            """,UUID.randomUUID(),"appointment-requested:"+id);
        var events=visits.timeline(USER,id,0,50).events();assertThat(events).filteredOn(e->"CHECKED_IN".equals(e.eventType())).allMatch(e->e.actor().snapshotRecorded());
        assertThat(events).filteredOn(e->"VISIT_EMAIL_NOTIFICATION".equals(e.eventType())).extracting(com.brainserve.appointment.audit.api.ActivityHistory.Event::deliveryStatus).containsExactly("QUEUED");
        jdbc.update("update notification_outbox set status='SENT',sent_at=now() where event_key=?","appointment-requested:"+id);
        assertThat(visits.timeline(USER,id,0,50).events()).filteredOn(e->"VISIT_EMAIL_NOTIFICATION".equals(e.eventType())).extracting(com.brainserve.appointment.audit.api.ActivityHistory.Event::deliveryStatus).containsExactly("SENT");
        assertCode(()->visits.timeline(OTHER,id,0,50),"SEARCH_RECORD_NOT_FOUND");assertCode(()->visits.timeline(USER,UUID.randomUUID(),0,50),"SEARCH_RECORD_NOT_FOUND");
        jdbc.update("insert into iam_user_permission_deny(user_id,permission_name) values(?,'APPOINTMENT_APPROVE')",USER);
        assertCode(()->visits.timeline(USER,id,0,50),"SEARCH_RECORD_NOT_FOUND");
    }
    private <T>T as(UUID actor,java.util.function.Supplier<T> work) {
        var old=org.springframework.security.core.context.SecurityContextHolder.getContext();
        var context=org.springframework.security.core.context.SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(actor.toString(),"",List.of()));
        org.springframework.security.core.context.SecurityContextHolder.setContext(context);
        try{return work.get();}finally{org.springframework.security.core.context.SecurityContextHolder.setContext(old);}
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
    private DepartmentWorkTask create(String title) {return as(LEAD,()->tasks.create(LEAD,new DepartmentWorkTaskService.CreateCommand(EMP,title,"Full delivery instructions",today())));}
    private LocalDate today() {return LocalDate.now(ZoneId.of("Asia/Kolkata"));}
    private void timestamp(UUID task,Instant at) {jdbc.update("update department_work_task set created_at=? where id=?",java.sql.Timestamp.from(at),task);}
    private void assertNotFound(Runnable action) {assertThatThrownBy(action::run).isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo("WORK_TASK_NOT_FOUND");}
    private static UUID id(int suffix) {return UUID.fromString(String.format(Locale.ROOT,"77000000-0000-0000-0000-%012d",suffix));}
    private void department(UUID id,String code) {jdbc.update("insert into org_department(id,code,name,active,version,created_at,created_by,updated_at,updated_by) values(?,?,?,true,0,now(),'sprint7activity-test',now(),'sprint7activity-test')",id,code,code);}
    private void employee(UUID id,UUID dep,String name) {jdbc.update("insert into employee(id,employee_number,first_name,last_name,display_name,official_email,department_id,designation,joining_date,status,version,created_at,created_by,updated_at,updated_by) values(?,?,?,?,?,?,?,'Test role','2026-01-01','ACTIVE',0,now(),'sprint7activity-test',now(),'sprint7activity-test')",id,"S7A-"+id.toString().substring(24),name,"Person",name+" Person",name+"@sprint7activity.test",dep);}
    private void account(UUID id,UUID emp,String name,String role) {jdbc.update("insert into iam_user_account(id,email,full_name,employee_id,password_hash,enabled,force_password_change,account_status,archived,version,created_at,created_by,updated_at,updated_by) values(?,?,?,?,'test-only-hash',true,false,'ACTIVE',false,0,now(),'sprint7activity-test',now(),'sprint7activity-test')",id,name+"@sprint7activity.test",name,emp);jdbc.update("insert into iam_user_role(user_id,role_name) values(?,?)",id,role);}
    private void assignment(String table,String prefix,UUID user,UUID emp) {jdbc.update("insert into "+table+"(id,department_id,"+prefix+"_user_id,"+prefix+"_employee_id,active,assigned_by_user_id,assigned_at,version,created_at,created_by,updated_at,updated_by) values(?,?,?,?,true,?,now(),0,now(),'sprint7activity-test',now(),'sprint7activity-test')",UUID.randomUUID(),DEPT,user,emp,ADMIN);}
    private long count(String sql,Object... params) {return Objects.requireNonNull(jdbc.queryForObject(sql,Long.class,params));}
}
