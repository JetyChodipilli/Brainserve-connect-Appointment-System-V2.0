package com.brainserve.appointment;

import com.brainserve.appointment.drafts.application.FormDraftService;
import com.brainserve.appointment.shared.application.BusinessException;
import com.brainserve.appointment.worktask.application.DepartmentWorkTaskService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static com.brainserve.appointment.drafts.application.FormDraftService.Form.*;

/** Real PostgreSQL revisions, account authority, production business writers and expiry cleanup. */
@Testcontainers(disabledWithoutDocker=true)
@SpringBootTest(properties={"brainserve.security.jwt-secret=test-only-secret-key-that-is-at-least-thirty-two-bytes",
        "brainserve.security.pii-encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "brainserve.bootstrap.system-admin-enabled=false","brainserve.bootstrap.ceo-enabled=false",
        "brainserve.appointment.office-zone=Asia/Kolkata","spring.task.scheduling.enabled=false",
        "spring.kafka.listener.auto-startup=false","brainserve.notification.internal-call-dispatch-ms=3600000","brainserve.notification.poll-ms=3600000",
        "aws.s3.access-key=test-access-key","aws.s3.secret-key=test-secret-key"})
class Sprint7DraftsPostgresIntegrationTest {
    @Container static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>("postgres:17.2-alpine");
    @Container static final GenericContainer<?> REDIS=new GenericContainer<>("redis:7.4.1-alpine").withExposedPorts(6379);
    @DynamicPropertySource static void infrastructure(DynamicPropertyRegistry r){r.add("spring.datasource.url",POSTGRES::getJdbcUrl);r.add("spring.datasource.username",POSTGRES::getUsername);r.add("spring.datasource.password",POSTGRES::getPassword);r.add("spring.data.redis.host",REDIS::getHost);r.add("spring.data.redis.port",()->REDIS.getMappedPort(6379));}
    @org.springframework.test.context.bean.override.mockito.MockitoBean software.amazon.awssdk.services.s3.S3Client s3;
    @org.springframework.test.context.bean.override.mockito.MockitoBean com.brainserve.appointment.document.infrastructure.ClamAvScanner scanner;
    @Autowired JdbcTemplate jdbc;@Autowired FormDraftService drafts;@Autowired DepartmentWorkTaskService tasks;
    @Autowired @org.springframework.beans.factory.annotation.Qualifier("notificationExecutor") java.util.concurrent.Executor notificationExecutor;
    static final UUID DEPT=id(101),OTHER_DEPT=id(102),EMP=id(201),LEAD_EMP=id(202),HR_EMP=id(203),OTHER_EMP=id(204);
    static final UUID USER=id(1),LEAD=id(2),HR=id(3),ADMIN=id(4),OTHER=id(5),RECEPTION=id(6),SECURITY=id(7);
    @BeforeEach void fixture(){
        drain();jdbc.update("delete from owned_form_draft");jdbc.update("delete from internal_call_notification where sender_user_id in(select id from iam_user_account where email like '%@sprint7draft.test') or recipient_user_id in(select id from iam_user_account where email like '%@sprint7draft.test')");
        jdbc.update("delete from work_task_audit_record");jdbc.execute("truncate work_review_stage_event restart identity");jdbc.update("delete from department_work_task");jdbc.update("delete from department_hr_assignment");jdbc.update("delete from department_team_lead");jdbc.update("delete from iam_user_account where email like '%@sprint7draft.test'");jdbc.update("delete from employee where official_email like '%@sprint7draft.test'");jdbc.update("delete from org_department where code like 'S7D_%'");
        department(DEPT,"S7D_MAIN");department(OTHER_DEPT,"S7D_OTHER");employee(EMP,DEPT,"employee");employee(LEAD_EMP,DEPT,"lead");employee(HR_EMP,DEPT,"hr");employee(OTHER_EMP,OTHER_DEPT,"other");
        account(USER,EMP,"employee","ROLE_EMPLOYEE");account(LEAD,LEAD_EMP,"lead","ROLE_TEAM_LEAD");account(HR,HR_EMP,"hr","ROLE_HR_ADMIN");account(ADMIN,null,"admin","ROLE_SYSTEM_ADMIN");account(OTHER,OTHER_EMP,"other","ROLE_EMPLOYEE");account(RECEPTION,null,"reception","ROLE_RECEPTIONIST");account(SECURITY,null,"security","ROLE_SECURITY");
        assignment("department_team_lead","team_lead",LEAD,LEAD_EMP);assignment("department_hr_assignment","hr",HR,HR_EMP);
    }
    @Test void ownerIsServerAuthorityAndForeignKeysDoNotExposeDrafts(){
        jdbc.update("insert into iam_user_permission_grant(user_id,permission_name) values(?,'COMPANY_PROFILE_MANAGE')",HR);
        var own=drafts.save(ADMIN,COMPANY_PROFILE,"company",save(0,Map.of("COMPANY.NAME","Owned Company")));
        assertThat(drafts.read(HR,COMPANY_PROFILE,"company")).isNull();
        code(()->drafts.submit(HR,COMPANY_PROFILE,"company",new FormDraftService.Submit(own.revision(),own.submissionKey(),null)),"DRAFT_NOT_FOUND");
        assertThat(drafts.read(ADMIN,COMPANY_PROFILE,"company").fields()).containsEntry("COMPANY.NAME","Owned Company");
    }
    @Test void credentialsAndBinaryFieldsUnknownSchemaAndMissingRevisionsAreRejected(){
        for(String key:List.of("password","otp","token","credentials","documents","ownerId","identityDocumentLastFour"))code(()->drafts.save(RECEPTION,VISIT_INTAKE,"reception",save(0,Map.of(key,"forbidden"))),"DRAFT_FIELDS_INVALID");
        code(()->drafts.save(ADMIN,COMPANY_PROFILE,"company",new FormDraftService.Save(null,0L,Map.of())),"DRAFT_FIELDS_INVALID");
        code(()->drafts.save(ADMIN,COMPANY_PROFILE,"company",new FormDraftService.Save(2,0L,Map.of())),"DRAFT_FIELDS_INVALID");
        code(()->drafts.save(ADMIN,COMPANY_PROFILE,"company",new FormDraftService.Save(1,null,Map.of())),"DRAFT_FIELDS_INVALID");
        code(()->drafts.save(ADMIN,COMPANY_PROFILE,"company",save(0,Map.of("COMPANY.NAME","x".repeat(2001)))),"DRAFT_FIELDS_INVALID");assertThat(count("select count(*) from owned_form_draft")).isZero();
    }
    @Test void visitorDraftFieldsAreEncryptedAtRestAndRestoredExactly(){
        var value=drafts.save(RECEPTION,VISIT_INTAKE,"reception",save(0,Map.of("visitorName","Private Visitor","visitorEmail","private@example.test","purpose","Interview")));
        String encrypted=jdbc.queryForObject("select fields_ciphertext from owned_form_draft where owner_id=?",String.class,RECEPTION);assertThat(encrypted).doesNotContain("Private Visitor","private@example.test");
        assertThat(drafts.read(RECEPTION,VISIT_INTAKE,"reception").fields()).isEqualTo(value.fields());assertThat(value.expiresAt()).isAfter(value.updatedAt().plus(6,java.time.temporal.ChronoUnit.DAYS)).isBefore(value.updatedAt().plus(8,java.time.temporal.ChronoUnit.DAYS));
    }
    @Test void expiredDraftCannotBeRecoveredAndServerCleanupDeletesIt(){
        drafts.save(ADMIN,COMPANY_PROFILE,"company",save(0,Map.of("COMPANY.NAME","Expired")));jdbc.update("update owned_form_draft set updated_at=now()-interval '8 days',expires_at=now()-interval '1 day'");
        assertThat(drafts.read(ADMIN,COMPANY_PROFILE,"company")).isNull();assertThat(drafts.cleanupExpired()).isEqualTo(1);assertThat(count("select count(*) from owned_form_draft")).isZero();
    }
    @Test void twoRealTransactionsHaveOneRevisionWinnerAndCannotOverwrite()throws Exception{
        var first=drafts.save(ADMIN,COMPANY_PROFILE,"company",save(0,Map.of("COMPANY.NAME","Initial")));
        try(var pool=Executors.newFixedThreadPool(2)){var start=new CountDownLatch(1);Callable<Boolean> write=()->{start.await();try{drafts.save(ADMIN,COMPANY_PROFILE,"company",save(first.revision(),Map.of("COMPANY.NAME",Thread.currentThread().getName())));return true;}catch(BusinessException ex){assertThat(ex.getErrorCode()).isEqualTo("DRAFT_REVISION_CONFLICT");return false;}};var a=pool.submit(write);var b=pool.submit(write);start.countDown();assertThat(List.of(a.get(15,TimeUnit.SECONDS),b.get(15,TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);}
        assertThat(drafts.read(ADMIN,COMPANY_PROFILE,"company").revision()).isEqualTo(2);code(()->drafts.discard(ADMIN,COMPANY_PROFILE,"company",1L),"DRAFT_REVISION_CONFLICT");
    }
    @Test void roleAndPermissionRevocationPreventRecoveryAndBusinessSubmission(){
        var created=drafts.save(LEAD,TASK_CREATE,"new",save(0,taskFields(EMP)));
        jdbc.update("insert into iam_user_permission_deny(user_id,permission_name) values(?,'WORK_TASK_CREATE')",LEAD);
        assertThatThrownBy(()->drafts.read(LEAD,TASK_CREATE,"new")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->drafts.submit(LEAD,TASK_CREATE,"new",new FormDraftService.Submit(created.revision(),created.submissionKey(),null))).isInstanceOf(BusinessException.class);assertThat(count("select count(*) from department_work_task")).isZero();
        var visit=drafts.save(RECEPTION,VISIT_INTAKE,"reception",save(0,Map.of("visitorName","Current Actor")));jdbc.update("update iam_user_role set role_name='ROLE_SECURITY' where user_id=?",RECEPTION);
        code(()->drafts.read(RECEPTION,VISIT_INTAKE,"reception"),"DRAFT_NOT_FOUND");
    }
    @Test void taskCreationAndReceiptCommitTogetherAndLostResponseRetryHasOneEffect(){
        var saved=drafts.save(LEAD,TASK_CREATE,"new",save(0,taskFields(EMP)));var first=drafts.submit(LEAD,TASK_CREATE,"new",new FormDraftService.Submit(saved.revision(),saved.submissionKey(),null));
        var retry=drafts.submit(LEAD,TASK_CREATE,"new",new FormDraftService.Submit(saved.revision(),saved.submissionKey(),null));assertThat(retry).isEqualTo(first);assertThat(count("select count(*) from department_work_task")).isEqualTo(1);
        assertThat(drafts.read(LEAD,TASK_CREATE,"new").receipt()).isEqualTo(first);assertThat(drafts.read(LEAD,TASK_CREATE,"new").fields()).isEmpty();
    }
    @Test void currentAssigneeEligibilityIsRevalidatedAndFailedSubmitLeavesDraft(){
        var saved=drafts.save(LEAD,TASK_CREATE,"new",save(0,taskFields(OTHER_EMP)));assertThatThrownBy(()->drafts.submit(LEAD,TASK_CREATE,"new",new FormDraftService.Submit(saved.revision(),saved.submissionKey(),null))).isInstanceOf(BusinessException.class);
        assertThat(count("select count(*) from department_work_task")).isZero();assertThat(drafts.read(LEAD,TASK_CREATE,"new").receipt()).isNull();assertThat(drafts.read(LEAD,TASK_CREATE,"new").fields()).containsEntry("employeeId",OTHER_EMP.toString());
    }
    @Test void perTaskDraftCannotBeReadByUnrelatedEmployeeAndSubmitChecksCurrentVersion(){
        var task=tasks.create(LEAD,new DepartmentWorkTaskService.CreateCommand(EMP,"Observed task","Real instructions",today()));String context=task.getId()+"~complete";
        var saved=drafts.save(USER,TASK_UPDATE,context,save(0,Map.of("note","Actual delivery","taskVersion",String.valueOf(task.getVersion()))));
        code(()->drafts.read(OTHER,TASK_UPDATE,context),"DRAFT_NOT_FOUND");tasks.start(USER,EMP,task.getId(),"Started",task.getVersion());
        code(()->drafts.submit(USER,TASK_UPDATE,context,new FormDraftService.Submit(saved.revision(),saved.submissionKey(),null)),"WORK_TASK_VERSION_CONFLICT");assertThat(drafts.read(USER,TASK_UPDATE,context).fields()).containsEntry("note","Actual delivery");assertThat(drafts.read(USER,TASK_UPDATE,context).receipt()).isNull();
    }
    @Test void companyValidationRollsBackAllChangesAndReceipt(){
        String before=jdbc.queryForObject("select setting_value from system_setting where setting_key='COMPANY.NAME'",String.class);
        var saved=drafts.save(ADMIN,COMPANY_PROFILE,"company",save(0,Map.of("COMPANY.NAME","Should Roll Back","COMPANY.EMAIL_DOMAIN","invalid domain","COMPANY.HQ_ADDRESS","Real Address","COMPANY.SUPPORT_EMAIL","support@example.test")));
        assertThatThrownBy(()->drafts.submit(ADMIN,COMPANY_PROFILE,"company",new FormDraftService.Submit(saved.revision(),saved.submissionKey(),null))).isInstanceOf(BusinessException.class);
        assertThat(jdbc.queryForObject("select setting_value from system_setting where setting_key='COMPANY.NAME'",String.class)).isEqualTo(before);assertThat(drafts.read(ADMIN,COMPANY_PROFILE,"company").receipt()).isNull();
    }
    private static FormDraftService.Save save(long revision,Map<String,String> fields){return new FormDraftService.Save(1,revision,fields);}
    private Map<String,String> taskFields(UUID employee){return Map.of("employeeId",employee.toString(),"title","Explicit worksheet","description","Real acceptance criteria","dueDate",today().toString());}
    private LocalDate today(){return LocalDate.now(ZoneId.of("Asia/Kolkata"));}
    private static UUID id(int value){return UUID.fromString(String.format(Locale.ROOT,"77000000-0000-0000-0000-%012d",value));}
    private void department(UUID id,String code){jdbc.update("insert into org_department(id,code,name,active,version,created_at,created_by,updated_at,updated_by) values(?,?,?,true,0,now(),'sprint7-drafts',now(),'sprint7-drafts')",id,code,code);}
    private void employee(UUID id,UUID dep,String name){jdbc.update("insert into employee(id,employee_number,first_name,last_name,display_name,official_email,department_id,designation,joining_date,status,version,created_at,created_by,updated_at,updated_by) values(?,?,?,?,?,?,?,'Engineer','2026-01-01','ACTIVE',0,now(),'sprint7-drafts',now(),'sprint7-drafts')",id,"S7D-"+id.toString().substring(24),name,"Person",name+" Person",name+"@sprint7draft.test",dep);}
    private void account(UUID id,UUID emp,String name,String role){jdbc.update("insert into iam_user_account(id,email,full_name,employee_id,password_hash,enabled,force_password_change,account_status,archived,version,created_at,created_by,updated_at,updated_by) values(?,?,?,?,'test-only-hash',true,false,'ACTIVE',false,0,now(),'sprint7-drafts',now(),'sprint7-drafts')",id,name+"@sprint7draft.test",name,emp);jdbc.update("insert into iam_user_role(user_id,role_name) values(?,?)",id,role);}
    private void assignment(String table,String prefix,UUID user,UUID emp){jdbc.update("insert into "+table+"(id,department_id,"+prefix+"_user_id,"+prefix+"_employee_id,active,assigned_by_user_id,assigned_at,version,created_at,created_by,updated_at,updated_by) values(?,?,?,?,true,?,now(),0,now(),'sprint7-drafts',now(),'sprint7-drafts')",UUID.randomUUID(),DEPT,user,emp,ADMIN);}
    private long count(String sql){return Objects.requireNonNull(jdbc.queryForObject(sql,Long.class));}
    private void code(Runnable action,String code){assertThatThrownBy(action::run).isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo(code);}
    private void drain(){if(!(notificationExecutor instanceof org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor executor))return;long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);while(executor.getActiveCount()>0||!executor.getThreadPoolExecutor().getQueue().isEmpty()){if(System.nanoTime()>deadline)throw new AssertionError("Notifications did not settle");try{Thread.sleep(20);}catch(InterruptedException ex){Thread.currentThread().interrupt();throw new AssertionError(ex);}}}
}
