package com.brainserve.appointment;

import com.brainserve.appointment.approvalpolicy.api.ReviewDelegations;
import com.brainserve.appointment.approvalpolicy.application.ApprovalPolicyService;
import com.brainserve.appointment.appointment.application.AppointmentService;
import com.brainserve.appointment.document.infrastructure.ClamAvScanner;
import com.brainserve.appointment.notification.application.NotificationPreferenceService;
import com.brainserve.appointment.notification.domain.InternalCallNotification;
import com.brainserve.appointment.notification.infrastructure.InternalCallNotificationRepository;
import com.brainserve.appointment.shared.application.BusinessException;
import com.brainserve.appointment.workinsight.application.WorkInsightService;
import com.brainserve.appointment.worktask.application.DepartmentWorkTaskService;
import com.brainserve.appointment.worktask.application.TaskPlanningService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
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
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import static org.assertj.core.api.Assertions.*;

/** Production policy, preference and review services against migrated PostgreSQL and Redis. */
@Testcontainers(disabledWithoutDocker=true)
@SpringBootTest(properties={
 "brainserve.security.jwt-secret=test-only-secret-key-that-is-at-least-thirty-two-bytes",
 "brainserve.security.pii-encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
 "brainserve.bootstrap.system-admin-enabled=false","brainserve.bootstrap.ceo-enabled=false",
 "brainserve.appointment.office-zone=Asia/Kolkata","brainserve.work-routines.enabled=false",
 "brainserve.approval-reminders.poll-ms=3600000","spring.kafka.listener.auto-startup=false",
 "brainserve.notification.internal-call-dispatch-ms=3600000","brainserve.notification.poll-ms=3600000",
 "aws.s3.access-key=test-access-key","aws.s3.secret-key=test-secret-key"
})
class Sprint10NotificationPostgresIntegrationTest {
 @Container static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>("postgres:17.2-alpine");
 @Container static final GenericContainer<?> REDIS=new GenericContainer<>("redis:7.4.1-alpine").withExposedPorts(6379);
 @DynamicPropertySource static void infrastructure(DynamicPropertyRegistry r) {
  r.add("spring.datasource.url",POSTGRES::getJdbcUrl);r.add("spring.datasource.username",POSTGRES::getUsername);
  r.add("spring.datasource.password",POSTGRES::getPassword);r.add("spring.data.redis.host",REDIS::getHost);
  r.add("spring.data.redis.port",()->REDIS.getMappedPort(6379));
 }
 @Autowired JdbcTemplate jdbc;
 @Autowired ApprovalPolicyService policies;
 @Autowired ReviewDelegations reviews;
 @Autowired NotificationPreferenceService preferences;
 @Autowired InternalCallNotificationRepository notices;
 @Autowired DepartmentWorkTaskService tasks;
 @Autowired TaskPlanningService planning;
 @Autowired WorkInsightService insights;
 @Autowired AppointmentService visits;
 @Autowired TransactionOperations transactions;
 @Autowired @Qualifier("notificationExecutor") Executor notificationExecutor;
 @MockitoBean S3Client s3;
 @MockitoBean ClamAvScanner scanner;
 static final UUID DEPT=id(101),OTHER_DEPT=id(102),EMP=id(201),LEAD_EMP=id(204),HR_EMP=id(205),MANAGER_EMP=id(206),
  OTHER_EMP=id(207),SECOND_LEAD_EMP=id(208),CEO_EMP=id(209),SECOND_HR_EMP=id(210),SECOND_MANAGER_EMP=id(211);
 static final UUID USER=id(1),LEAD=id(4),HR=id(5),MANAGER=id(6),OTHER=id(7),ADMIN=id(8),CEO=id(9),
  SECOND_LEAD=id(10),SECOND_HR=id(11),SECOND_MANAGER=id(12);
 @BeforeEach void fixture() {
  drain();
  jdbc.execute("truncate stored_document,appointment,work_task_audit_record,department_work_task,internal_call_notification,notification_outbox cascade");
  jdbc.execute("truncate department_hr_assignment,department_team_lead,department_manager_assignment,iam_user_account,employee,org_department cascade");
  jdbc.execute("truncate approval_policy cascade");
  jdbc.execute("insert into approval_policy(kind,stage,version,deadline_minutes,reminder_minutes,escalation_role) select k,s,1,5,5,'MANAGER' from (values('WORK'),('VISIT')) kinds(k) cross join (values('TEAM_LEAD'),('HR_ADMIN'),('MANAGER'),('CEO')) stages(s)");
  jdbc.execute("insert into approval_policy(kind,stage,version,deadline_minutes,reminder_minutes,escalation_role) values('VISIT','HOST',1,5,5,'HR_ADMIN')");
  department(DEPT,"S10_MAIN");department(OTHER_DEPT,"S10_OTHER");
  employee(EMP,DEPT,"worker");employee(LEAD_EMP,DEPT,"lead");employee(HR_EMP,DEPT,"hr");employee(MANAGER_EMP,DEPT,"manager");
  employee(OTHER_EMP,OTHER_DEPT,"foreign");employee(SECOND_LEAD_EMP,DEPT,"alternatelead");employee(CEO_EMP,DEPT,"ceo");
  employee(SECOND_HR_EMP,DEPT,"alternatehr");employee(SECOND_MANAGER_EMP,DEPT,"alternatemanager");
  account(USER,EMP,"worker","ROLE_EMPLOYEE");account(LEAD,LEAD_EMP,"lead","ROLE_TEAM_LEAD");
  account(HR,HR_EMP,"hr","ROLE_HR_ADMIN");account(MANAGER,MANAGER_EMP,"manager","ROLE_MANAGER");
  account(OTHER,OTHER_EMP,"foreign","ROLE_TEAM_LEAD");account(ADMIN,null,"admin","ROLE_SYSTEM_ADMIN");
  account(CEO,CEO_EMP,"ceo","ROLE_CEO");account(SECOND_LEAD,SECOND_LEAD_EMP,"alternatelead","ROLE_TEAM_LEAD");
  account(SECOND_HR,SECOND_HR_EMP,"alternatehr","ROLE_HR_ADMIN");account(SECOND_MANAGER,SECOND_MANAGER_EMP,"alternatemanager","ROLE_MANAGER");
  assignment("department_team_lead","team_lead",LEAD,LEAD_EMP);
  assignment("department_hr_assignment","hr",HR,HR_EMP);assignment("department_manager_assignment","manager",MANAGER,MANAGER_EMP);
 }
 @AfterEach void settled(){drain();}

 @Test void migrationsAndDefaultsRetainHistoryAndDoNotStartDeadlineAutomation() {
  assertThat(POSTGRES.isRunning()&&REDIS.isRunning()).isTrue();
  assertThat(count("select count(*) from flyway_schema_history where version in('63','64') and success")).isEqualTo(2);
  assertThat(policies.policies(ADMIN)).hasSize(9).allMatch(p->!p.enabled());
  assertThat(preferences.read(USER).version()).isZero();assertThat(preferences.read(USER).zoneId()).isEqualTo("Asia/Kolkata");
  UUID task=submitted("Explicit inactive policy");var stage=stage(LEAD,task);
  assertThat(stage.deadlineAt()).isNull();assertThat(stage.entryKnown()).isTrue();
  assertThatThrownBy(()->jdbc.update("update approval_policy set enabled=true")).hasMessageContaining("immutable");
  assertThatThrownBy(()->policies.policies(USER)).isInstanceOf(BusinessException.class);
 }
 @Test void quietHoursHoldRoutineMessagesButMandatoryNoticesIgnoreDisabledChannels() {
  preferences.save(USER,new NotificationPreferenceService.Preference(0,false,false,false,"IMMEDIATE","UTC",true,"22:00","08:00"));
  UUID routine=notice(InternalCallNotification.MessageCategory.GENERAL,"2026-10-07T23:00:00Z");
  UUID mandatory=notice(InternalCallNotification.MessageCategory.SECURITY,"2026-10-07T23:00:00Z");
  assertThat(prepare(List.of(routine,mandatory),Instant.parse("2026-10-07T23:01:00Z"))).containsExactly(mandatory);
  assertThat(notices.findById(routine).orElseThrow().getNextDeliveryAttemptAt()).isEqualTo("2026-10-08T08:00:00Z");
  assertThat(prepare(List.of(routine),Instant.parse("2026-10-08T08:00:00Z"))).isEmpty();
  assertThat(notices.findById(routine).orElseThrow().getDeliveryStatus()).isEqualTo(InternalCallNotification.DeliveryStatus.SUPPRESSED);
  transactions.executeWithoutResult(tx->{
   assertThat(notices.acknowledgeIfMatching(routine,HR,USER,Instant.now())).isZero();
   assertThat(notices.markPublishedIfUnacknowledged(routine,0,Instant.now(),Instant.now().plusSeconds(30))).isZero();
   assertThat(notices.markFailedIfUnacknowledged(routine,0,"Late transport error",Instant.now().plusSeconds(30),Instant.now())).isZero();
  });
  assertThat(count("select count(*) from notification_email_receipt")).isZero();
 }
 @Test void hourlyDigestAndTransportRetriesRetainExactlyOneEmailEventAndEachSourceReceipt() {
  preferences.save(USER,new NotificationPreferenceService.Preference(0,true,true,false,"HOURLY","UTC",false,"22:00","08:00"));
  UUID a=notice(InternalCallNotification.MessageCategory.GENERAL,"2026-10-07T12:10:00Z"),b=notice(InternalCallNotification.MessageCategory.GENERAL,"2026-10-07T12:30:00Z");
  assertThat(prepare(List.of(a,b),Instant.parse("2026-10-07T12:59:00Z"))).isEmpty();
  assertThat(prepare(List.of(a,b),Instant.parse("2026-10-07T13:00:00Z"))).containsExactly(a,b);
  prepare(List.of(a,b),Instant.parse("2026-10-07T13:05:00Z"));
  assertThat(count("select count(*) from notification_email_receipt")).isEqualTo(2);
  assertThat(count("select count(*) from notification_outbox where template='ROUTINE_DIGEST'")).isEqualTo(1);
  assertThat(jdbc.queryForObject("select payload_json::text from notification_outbox where template='ROUTINE_DIGEST'",String.class)).doesNotContain("Private message");
  assertThatThrownBy(()->jdbc.update("delete from notification_email_receipt")).hasMessageContaining("immutable");
 }
 @Test void versionConflictsDoNotRewritePreferencesOrPreviouslyDeliveredHistory() {
  var saved=preferences.save(USER,new NotificationPreferenceService.Preference(0,true,false,false,"HOURLY","UTC",false,"22:00","08:00"));
  UUID n=notice(InternalCallNotification.MessageCategory.GENERAL,"2026-10-07T12:10:00Z");
  transactions.executeWithoutResult(tx->notices.findById(n).orElseThrow().markDelivered());
  assertThatThrownBy(()->preferences.save(USER,new NotificationPreferenceService.Preference(0,false,true,true,"DAILY","UTC",false,"22:00","08:00"))).isInstanceOf(BusinessException.class);
  assertThat(preferences.read(USER)).isEqualTo(saved);assertThat(count("select count(*) from notification_preference_history")).isEqualTo(1);
  assertThat(notices.findById(n).orElseThrow().getDeliveryStatus()).isEqualTo(InternalCallNotification.DeliveryStatus.DELIVERED);
  assertThatThrownBy(()->preferences.save(USER,new NotificationPreferenceService.Preference(1,true,false,false,"DAILY","Invalid/Zone",true,"08:00","08:00"))).isInstanceOf(BusinessException.class);
 }
 @Test void newlyEnteredStagesCapturePolicyVersionsWhileExistingStagesKeepTheirDeadline() {
  enable("WORK","TEAM_LEAD");UUID first=submitted("Captured version two");var before=stage(LEAD,first);
  var p=policies.policies(ADMIN).stream().filter(v->v.kind().equals("WORK")&&v.stage().equals("TEAM_LEAD")).findFirst().orElseThrow();
  policies.savePolicy(ADMIN,new ApprovalPolicyService.Policy(0,p.kind(),p.stage(),p.version(),true,60,10,"CEO"));
  UUID second=submitted("Captured version three");
  assertThat(stage(LEAD,first).deadlineAt()).isEqualTo(before.deadlineAt());assertThat(stage(LEAD,first).policyVersion()).isEqualTo(2);
  assertThat(stage(LEAD,second).policyVersion()).isEqualTo(3);
  assertThatThrownBy(()->policies.savePolicy(ADMIN,p)).isInstanceOf(BusinessException.class);
 }
 @Test void delegationIsScopedAndPreservesTheSubmissionAuthorThroughTheFullApprovalChain() {
  UUID task=submitted("Scoped delegated approval");var lead=stage(LEAD,task);
  assertThat(policies.candidates(LEAD,lead.id())).extracting("userId").contains(SECOND_LEAD).doesNotContain(USER,OTHER,CEO);
  var grant=policies.delegate(LEAD,lead.id(),SECOND_LEAD,Instant.now().plusSeconds(3600),"Cover reviewer absence");
  assertThat(planning.get(SECOND_LEAD,task).submissions().getFirst().authorUserId()).isEqualTo(USER);
  decide(SECOND_LEAD,stage(SECOND_LEAD,task));
  assertThatThrownBy(()->planning.get(SECOND_LEAD,task)).isInstanceOf(BusinessException.class);
  var hr=stage(HR,task);policies.delegate(HR,hr.id(),SECOND_HR,Instant.now().plusSeconds(3600),"Cover current HR review");decide(SECOND_HR,stage(SECOND_HR,task));
  var manager=stage(MANAGER,task);policies.delegate(MANAGER,manager.id(),SECOND_MANAGER,Instant.now().plusSeconds(3600),"Cover manager review");decide(SECOND_MANAGER,stage(SECOND_MANAGER,task));
  var ceo=stage(CEO,task);assertThat(ceo.canDelegate()).isFalse();
  assertThatThrownBy(()->policies.delegate(CEO,ceo.id(),SECOND_MANAGER,Instant.now().plusSeconds(3600),"Cannot delegate final CEO")).isInstanceOf(BusinessException.class);
  decide(CEO,ceo);assertThat(policies.queue(CEO,false,0).items()).isEmpty();
  assertThat(planning.get(USER,task).submissions().getFirst().authorUserId()).isEqualTo(USER);
  assertThat(count("select count(*) from approval_delegation where id=?",grant.id())).isEqualTo(1);
  assertThat(jdbc.queryForObject("select manager_decided_by_user_id from work_task_audit_record where work_task_id=?",UUID.class,task)).isEqualTo(SECOND_MANAGER);
 }
 @ParameterizedTest @ValueSource(strings={"revoked","expired","disabled","permission","role","department","leave","owner"})
 void everyDelegatedActionRechecksCurrentEligibility(String change) {
  UUID task=submitted("Current eligibility "+change);var stage=stage(LEAD,task);
  var grant=policies.delegate(LEAD,stage.id(),SECOND_LEAD,Instant.now().plusSeconds(3600),"Cover assigned lead absence");
  switch(change) {
   case "revoked" -> policies.revoke(LEAD,grant.id());
   case "expired" -> jdbc.update("update approval_delegation set created_at=now()-interval '2 hours',expires_at=now()-interval '1 hour' where id=?",grant.id());
   case "disabled" -> jdbc.update("update iam_user_account set enabled=false where id=?",SECOND_LEAD);
   case "permission" -> jdbc.update("insert into iam_user_permission_deny(user_id,permission_name) values(?,'WORK_TASK_REVIEW')",SECOND_LEAD);
   case "role" -> jdbc.update("update iam_user_role set role_name='ROLE_EMPLOYEE' where user_id=?",SECOND_LEAD);
   case "department" -> jdbc.update("update employee set department_id=? where id=?",OTHER_DEPT,SECOND_LEAD_EMP);
   case "leave" -> jdbc.update("insert into employee_leave_request(id,employee_id,requester_user_id,start_date,end_date,reason,status,decided_by_user_id,decided_at,version,created_at,created_by,updated_at,updated_by) values(?,?,?,current_date,current_date,'Approved reviewer leave','APPROVED',?,now(),0,now(),'s10',now(),'s10')",UUID.randomUUID(),SECOND_LEAD_EMP,SECOND_LEAD,HR);
   case "owner" -> jdbc.update("update iam_user_account set enabled=false where id=?",LEAD);
  }
  assertThat(reviews.allows(SECOND_LEAD,"WORK",task,"TEAM_LEAD")).isFalse();
  assertThatThrownBy(()->as(SECOND_LEAD,()->{insights.decideQueued(SECOND_LEAD,stage.id(),stage.resourceVersion(),true,"Forbidden stale delegation");return null;})).isInstanceOf(BusinessException.class);
  assertThat(jdbc.queryForObject("select status from department_work_task where id=?",String.class,task)).isEqualTo("COMPLETED");
 }
 @Test void ownerLeaveDoesNotRemoveTheirAbilityToDelegateAndForeignRolesCannotReceiveAuthority() {
  UUID task=submitted("Reviewer leave coverage");var stage=stage(LEAD,task);
  jdbc.update("insert into employee_leave_request(id,employee_id,requester_user_id,start_date,end_date,reason,status,decided_by_user_id,decided_at,version,created_at,created_by,updated_at,updated_by) values(?,?,?,current_date,current_date,'Approved lead leave','APPROVED',?,now(),0,now(),'s10',now(),'s10')",UUID.randomUUID(),LEAD_EMP,LEAD,HR);
  for(UUID target:List.of(USER,OTHER,HR,CEO)) assertThatThrownBy(()->policies.delegate(LEAD,stage.id(),target,Instant.now().plusSeconds(3600),"Invalid review authority")).isInstanceOf(BusinessException.class);
  policies.delegate(LEAD,stage.id(),SECOND_LEAD,Instant.now().plusSeconds(3600),"Cover approved lead leave");
  assertThat(reviews.allows(SECOND_LEAD,"WORK",task,"TEAM_LEAD")).isTrue();
 }
 @Test void concurrentReminderWorkersHaveOneReceiptPerWindowAndStopAfterResolution() throws Exception {
  enable("WORK","TEAM_LEAD");UUID task=submitted("Durable reminder retries");var stage=stage(LEAD,task);
  jdbc.update("update approval_stage set deadline_at=now()-interval '1 minute',next_reminder_at=now()-interval '1 minute' where id=?",stage.id());
  try(var pool=Executors.newFixedThreadPool(2)) {var start=new CountDownLatch(1);Callable<Void> work=()->{start.await();policies.dispatch();return null;};var a=pool.submit(work);var b=pool.submit(work);start.countDown();a.get(20,TimeUnit.SECONDS);b.get(20,TimeUnit.SECONDS);}
  assertThat(count("select count(*) from approval_reminder_receipt where stage_id=?",stage.id())).isEqualTo(2);
  assertThat(count("select count(*) from internal_call_notification where category='ESCALATION' and mandatory")).isEqualTo(2);
  jdbc.update("update approval_stage set next_reminder_at=now()-interval '1 second' where id=?",stage.id());policies.dispatch();
  assertThat(count("select count(*) from approval_reminder_receipt where stage_id=?",stage.id())).isEqualTo(2);
  decide(LEAD,stage(LEAD,task));policies.dispatch();
  assertThat(count("select count(*) from approval_reminder_receipt where stage_id=?",stage.id())).isEqualTo(2);
 }
 @Test void simultaneousReviewAndReminderCompleteWithoutDeadlockOrAutoApproval() throws Exception {
  enable("WORK","TEAM_LEAD");UUID task=submitted("Concurrent review and reminder");var stage=stage(LEAD,task);
  jdbc.update("update approval_stage set deadline_at=now()-interval '1 minute',next_reminder_at=now()-interval '1 minute' where id=?",stage.id());
  try(var pool=Executors.newFixedThreadPool(2)){var start=new CountDownLatch(1);var a=pool.submit(()->{start.await();policies.dispatch();return true;});var b=pool.submit(()->{start.await();decide(LEAD,stage);return true;});start.countDown();assertThat(a.get(20,TimeUnit.SECONDS)&&b.get(20,TimeUnit.SECONDS)).isTrue();}
  assertThat(stage(HR,task).stage()).isEqualTo("HR_ADMIN");
  assertThat(count("select count(*) from approval_stage where id=? and closed_at is not null",stage.id())).isEqualTo(1);
 }
 @Test void delegationRevocationAndFailedNotificationTransactionsLeaveNoPartialReviewOrReceipts() {
  enable("WORK","TEAM_LEAD");UUID task=submitted("Atomic reminder failure");var stage=stage(LEAD,task);
  jdbc.update("update approval_stage set deadline_at=now()-interval '1 minute',next_reminder_at=now()-interval '1 minute' where id=?",stage.id());
  jdbc.execute("create function fail_s10_notice() returns trigger language plpgsql as $$ begin if NEW.category='ESCALATION' then raise exception 's10 injected notice failure'; end if; return NEW; end $$");
  jdbc.execute("create trigger fail_s10_notice before insert on internal_call_notification for each row execute function fail_s10_notice()");
  try{assertThatThrownBy(policies::dispatch).hasMessageContaining("s10 injected notice failure");assertThat(count("select count(*) from approval_reminder_receipt")).isZero();}
  finally{jdbc.execute("drop trigger fail_s10_notice on internal_call_notification");jdbc.execute("drop function fail_s10_notice()");}
  policies.dispatch();assertThat(count("select count(*) from approval_reminder_receipt")).isEqualTo(2);
  var grant=policies.delegate(LEAD,stage.id(),SECOND_LEAD,Instant.now().plusSeconds(3600),"Audited revocable coverage");
  assertThatThrownBy(()->policies.revoke(OTHER,grant.id())).isInstanceOf(BusinessException.class);
  policies.revoke(ADMIN,grant.id());assertThat(reviews.allows(SECOND_LEAD,"WORK",task,"TEAM_LEAD")).isFalse();
 }
 @Test void visitDelegationUsesTheVisitStageAndExistingDomainDecisionRules() {
  UUID visit=UUID.randomUUID();
  jdbc.update("insert into appointment(id,reference_number,idempotency_key,type,status,visitor_name,visitor_email,visitor_phone,host_employee_id,requested_employee_id,routing_department_id,slot_start,slot_end,purpose,version,created_at,created_by,updated_at,updated_by) values(?,?,?,'EMPLOYEE_VISIT','PENDING_HR_APPROVAL','Fixture Visitor','visitor@example.invalid','0000000000',?,?,?,now()+interval '1 day',now()+interval '1 day 30 minutes','Authorized visitor',0,now(),'s10',now(),'s10')",visit,"S10-"+visit,visit.toString(),EMP,EMP,DEPT);
  var hr=stage(HR,visit);assertThat(hr.kind()).isEqualTo("VISIT");policies.delegate(HR,hr.id(),SECOND_HR,Instant.now().plusSeconds(3600),"Cover visitor HR review");
  as(SECOND_HR,()->visits.reviewQueued(SECOND_HR,visit,hr.id(),hr.resourceVersion(),true,"HR visitor reviewed"));
  var lead=stage(LEAD,visit);assertThat(lead.stage()).isEqualTo("TEAM_LEAD");policies.delegate(LEAD,lead.id(),SECOND_LEAD,Instant.now().plusSeconds(3600),"Cover visitor lead review");
  assertThatThrownBy(()->as(SECOND_HR,()->visits.reviewQueued(SECOND_HR,visit,hr.id(),hr.resourceVersion(),true,"Stale stage cannot replay"))).isInstanceOf(BusinessException.class);
  as(SECOND_LEAD,()->visits.reviewQueued(SECOND_LEAD,visit,lead.id(),lead.resourceVersion(),true,"Lead visitor reviewed"));
  assertThat(jdbc.queryForObject("select status from appointment where id=?",String.class,visit)).isEqualTo("APPROVED");
 }
 private UUID submitted(String title){var task=as(LEAD,()->tasks.create(LEAD,new DepartmentWorkTaskService.CreateCommand(EMP,title,"Private retained instructions",today())));as(USER,()->tasks.complete(USER,EMP,task.getId(),"Authored delivery update"));return task.getId();}
 private ApprovalPolicyService.QueueItem stage(UUID actor,UUID resource){return policies.queue(actor,false,0).items().stream().filter(i->i.resourceId().equals(resource)).findFirst().orElseThrow();}
 private void decide(UUID actor,ApprovalPolicyService.QueueItem stage){as(actor,()->{insights.decideQueued(actor,stage.id(),stage.resourceVersion(),true,"Reviewed retained evidence");return null;});}
 private void enable(String kind,String stage){var p=policies.policies(ADMIN).stream().filter(v->v.kind().equals(kind)&&v.stage().equals(stage)).findFirst().orElseThrow();policies.savePolicy(ADMIN,new ApprovalPolicyService.Policy(0,kind,stage,p.version(),true,5,5,"MANAGER"));}
 private UUID notice(InternalCallNotification.MessageCategory category,String at){var n=notices.saveAndFlush(new InternalCallNotification(HR,USER,"HR","Worker","Private message text",InternalCallNotification.MessagePriority.NORMAL,category));jdbc.update("update internal_call_notification set sent_at=? where id=?",Timestamp.from(Instant.parse(at)),n.getId());return n.getId();}
 private List<UUID> prepare(List<UUID> ids,Instant now){return transactions.execute(tx->{var rows=ids.stream().map(id->notices.findById(id).orElseThrow()).toList();var result=preferences.prepare(rows,now).stream().map(InternalCallNotification::getId).toList();notices.flush();return result;});}
 private void department(UUID id,String code){jdbc.update("insert into org_department(id,code,name,active,version,created_at,created_by,updated_at,updated_by) values(?,?,?,true,0,now(),'s10',now(),'s10')",id,code,code);}
 private void employee(UUID id,UUID dep,String name){jdbc.update("insert into employee(id,employee_number,first_name,last_name,display_name,official_email,department_id,designation,joining_date,status,version,created_at,created_by,updated_at,updated_by) values(?,?,?,?,?,?,?,'Engineer','2026-01-01','ACTIVE',0,now(),'s10',now(),'s10')",id,"S10-"+id.toString().substring(24),name,"Person",name+" Person",name+"@s10.test",dep);}
 private void account(UUID id,UUID emp,String name,String role){jdbc.update("insert into iam_user_account(id,email,full_name,employee_id,password_hash,enabled,force_password_change,account_status,archived,version,created_at,created_by,updated_at,updated_by) values(?,?,?,?,'test-only-hash',true,false,'ACTIVE',false,0,now(),'s10',now(),'s10')",id,name+"@s10.test",name,emp);jdbc.update("insert into iam_user_role(user_id,role_name) values(?,?)",id,role);}
 private void assignment(String table,String prefix,UUID user,UUID emp){jdbc.update("insert into "+table+"(id,department_id,"+prefix+"_user_id,"+prefix+"_employee_id,active,assigned_by_user_id,assigned_at,version,created_at,created_by,updated_at,updated_by) values(?,?,?,?,true,?,now(),0,now(),'s10',now(),'s10')",UUID.randomUUID(),DEPT,user,emp,ADMIN);}
 private <T>T as(UUID actor,Supplier<T> action){var old=SecurityContextHolder.getContext();var next=SecurityContextHolder.createEmptyContext();next.setAuthentication(new UsernamePasswordAuthenticationToken(actor.toString(),"",List.of()));SecurityContextHolder.setContext(next);try{T result=action.get();drain();return result;}finally{SecurityContextHolder.setContext(old);}}
 private LocalDate today(){return LocalDate.now(ZoneId.of("Asia/Kolkata"));}
 private static UUID id(int n){return UUID.fromString(String.format(Locale.ROOT,"aa100000-0000-0000-0000-%012d",n));}
 private long count(String sql,Object...args){return Objects.requireNonNull(jdbc.queryForObject(sql,Long.class,args));}
 private void drain(){if(!(notificationExecutor instanceof ThreadPoolTaskExecutor executor))return;long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);while(executor.getActiveCount()>0||!executor.getThreadPoolExecutor().getQueue().isEmpty()){if(System.nanoTime()>deadline)throw new AssertionError("Notification writes did not settle");try{Thread.sleep(20);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}}}
}
