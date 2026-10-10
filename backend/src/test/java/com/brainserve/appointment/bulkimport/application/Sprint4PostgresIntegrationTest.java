package com.brainserve.appointment.bulkimport.application;

import com.brainserve.appointment.appointment.api.VisitorImport;
import com.brainserve.appointment.audit.api.AuditService;
import com.brainserve.appointment.configuration.application.CompanySetupService;
import com.brainserve.appointment.employee.api.EmployeeProfileImport;
import com.brainserve.appointment.iam.api.CurrentAccountAuthority;
import com.brainserve.appointment.organization.api.DepartmentCommands;
import com.brainserve.appointment.shared.application.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static com.brainserve.appointment.bulkimport.application.BulkImportService.*;
import static org.assertj.core.api.Assertions.*;

/** Runs real production imports, business writers, scoped employee profiles and appointment
 * routing with PostgreSQL + Redis. CI treats any skipped case as a failed database gate. */
@Testcontainers(disabledWithoutDocker=true)
@SpringBootTest(properties={
        "brainserve.security.jwt-secret=test-only-secret-key-that-is-at-least-thirty-two-bytes",
        "brainserve.security.pii-encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "brainserve.bootstrap.system-admin-enabled=false", "brainserve.bootstrap.ceo-enabled=false",
        "brainserve.appointment.office-zone=Asia/Kolkata", "spring.task.scheduling.enabled=false",
        "aws.s3.access-key=test-access-key", "aws.s3.secret-key=test-secret-key"})
class Sprint4PostgresIntegrationTest {
    @Container static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>("postgres:17.2-alpine");
    @Container static final GenericContainer<?> REDIS=new GenericContainer<>("redis:7.4.1-alpine").withExposedPorts(6379);
    @DynamicPropertySource static void infrastructure(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",POSTGRES::getJdbcUrl);r.add("spring.datasource.username",POSTGRES::getUsername);r.add("spring.datasource.password",POSTGRES::getPassword);
        r.add("spring.data.redis.host",REDIS::getHost);r.add("spring.data.redis.port",()->REDIS.getMappedPort(6379));
    }
    @Autowired JdbcTemplate jdbc; @Autowired BulkImportService imports; @Autowired CompanySetupService setup;
    @Autowired ObjectMapper mapper; @Autowired CurrentAccountAuthority authority; @Autowired DepartmentCommands departments;
    @Autowired EmployeeProfileImport employees; @Autowired VisitorImport visitors; @Autowired AuditService audit;
    @Autowired PlatformTransactionManager manager;
    static final UUID ADMIN=UUID.fromString("64000000-0000-0000-0000-000000000001");
    static final UUID HR=UUID.fromString("64000000-0000-0000-0000-000000000002");
    static final UUID RECEPTION=UUID.fromString("64000000-0000-0000-0000-000000000003");
    static final UUID CEO=UUID.fromString("64000000-0000-0000-0000-000000000004");
    static final UUID DEPT=UUID.fromString("64000000-0000-0000-0000-000000000101");
    static final UUID HR_EMPLOYEE=UUID.fromString("64000000-0000-0000-0000-000000000201");
    @BeforeEach void reset() {
        jdbc.update("delete from bulk_import_execution_key");jdbc.update("delete from bulk_import_job");
        jdbc.update("delete from appointment where visitor_email like '%@sprint4.test'");
        jdbc.update("delete from department_hr_assignment");jdbc.update("delete from department_manager_assignment");jdbc.update("delete from department_team_lead");
        jdbc.update("delete from iam_user_account where email like '%@sprint4.test'");
        jdbc.update("delete from employee where official_email like '%@sprint4.test'");
        jdbc.update("delete from org_department where code like 'S4_%'");
        jdbc.update("update org_department set active=true");
        jdbc.update("update system_setting set setting_value='30' where setting_key='APPOINTMENT.SLOT_MINUTES'");
        jdbc.update("update system_setting set setting_value='10' where setting_key='APPOINTMENT.MIN_LEAD_MINUTES'");
        jdbc.update("update system_setting set setting_value='Asia/Kolkata' where setting_key='COMPANY.OFFICE_ZONE'");
        jdbc.update("update data_retention_policy set enabled=true");
        jdbc.update("update company_setup_progress set revision=0,current_step='company',completed_at=null,readiness_fingerprint=''");
        department(DEPT,"S4_HR");employee(HR_EMPLOYEE,DEPT,"hr");
        account(ADMIN,null,"admin","ROLE_SYSTEM_ADMIN");account(HR,HR_EMPLOYEE,"hr","ROLE_HR_ADMIN");account(RECEPTION,null,"reception","ROLE_RECEPTIONIST");
        assignment("department_hr_assignment","hr",DEPT,HR,HR_EMPLOYEE);
    }
    @Test void v54IsAdditiveAndDoesNotSeedBusinessData() {
        var flyway=Flyway.configure().dataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword()).load();flyway.validate();
        assertThat(flyway.info().current().getVersion().toString()).isEqualTo("71");
        Map<String,Integer> checksums=new TreeMap<>();for(var m:flyway.info().applied()) if(m.getVersion().getMajor().intValue()<=53) checksums.put(m.getVersion().toString(),m.getChecksum());
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        for(var m:flyway.info().applied()) if(m.getVersion().getMajor().intValue()<=53) assertThat(m.getChecksum()).isEqualTo(checksums.get(m.getVersion().toString()));
        assertThat(count("select count(*) from bulk_import_job")).isZero();
        assertThat(count("select count(*) from company_setup_progress")).isEqualTo(1);
    }
    @Test void departmentEffectsAndRowOutcomesCommitTogetherAndReplayAfterRestart() {
        var job=previewDepartment("S4_NEW","New Department");var result=imports.execute(ADMIN,job.id(),job.checksum(),"original-key");
        assertThat(result.applied()).isEqualTo(1);assertThat(result.rows().getFirst().recordId()).isNotNull();assertThat(result.rows().getFirst().rowNumber()).isEqualTo(2);
        var restarted=new BulkImportService(jdbc,mapper,authority,departments,employees,visitors,audit,manager);
        assertThat(restarted.execute(ADMIN,job.id(),job.checksum(),"new-key-after-restart").applied()).isEqualTo(1);
        assertThat(count("select count(*) from org_department where code='S4_NEW'")).isEqualTo(1);
    }
    @Test void parallelExecutionsOfSameJobNeverDuplicateEffectsOrOverwriteCounters() throws Exception {
        var job=imports.preview(ADMIN,ImportKind.DEPARTMENTS,DuplicatePolicy.FAIL,"code,name\nS4_A,Alpha\nS4_B,Beta");
        try(var pool=Executors.newFixedThreadPool(2)) {
            var start=new CountDownLatch(1);
            Callable<ImportJob> action=()->{start.await();return imports.execute(ADMIN,job.id(),job.checksum(),"parallel-key");};
            var a=pool.submit(action);var b=pool.submit(action);start.countDown();assertThat(a.get(20,TimeUnit.SECONDS).applied()).isEqualTo(2);assertThat(b.get(20,TimeUnit.SECONDS).applied()).isEqualTo(2);
        }
        assertThat(count("select count(*) from org_department where code in ('S4_A','S4_B')")).isEqualTo(2);
    }
    @Test void concurrentPermissionRevocationCannotCommitInsideAnAuthorizedRowTransaction() throws Exception {
        var job=previewDepartment("S4_RACE","Race test");
        var writer=org.mockito.Mockito.mock(DepartmentCommands.class);
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var mutationStarted=new CountDownLatch(1);
        org.mockito.Mockito.when(writer.createId(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString())).thenAnswer(invocation->{
            entered.countDown();if(!release.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("Test writer release timed out");
            return departments.createId(invocation.getArgument(0),invocation.getArgument(1));
        });
        var paused=new BulkImportService(jdbc,mapper,authority,writer,employees,visitors,audit,manager);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var execute=pool.submit(()->{try{return paused.execute(ADMIN,job.id(),job.checksum(),"authority-race-key");}catch(BusinessException revoked){return null;}});
            assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();
            var revoke=pool.submit(()->{mutationStarted.countDown();jdbc.update("insert into iam_user_permission_deny(user_id,permission_name) values(?,'SYSTEM_CONFIGURE')",ADMIN);});
            assertThat(mutationStarted.await(5,TimeUnit.SECONDS)).isTrue();
            try {assertThatThrownBy(()->revoke.get(100,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);}
            finally {release.countDown();}
            execute.get(15,TimeUnit.SECONDS);revoke.get(15,TimeUnit.SECONDS);
        } finally {release.countDown();}
        assertThat(count("select count(*) from org_department where code='S4_RACE'")).isEqualTo(1);
        assertThat(jdbc.queryForObject("select status from bulk_import_row where job_id=?",String.class,job.id())).isEqualTo("APPLIED");
        assertThatThrownBy(()->imports.get(ADMIN,job.id())).isInstanceOf(BusinessException.class);
    }
    @Test void restartResumesOnlyUncommittedRows() {
        var job=imports.preview(ADMIN,ImportKind.DEPARTMENTS,DuplicatePolicy.FAIL,"code,name\nS4_A,Alpha\nS4_B,Beta");
        jdbc.update("update bulk_import_job set status='RUNNING' where id=?",job.id());
        new TransactionTemplate(manager).executeWithoutResult(tx->ReflectionTestUtils.invokeMethod(imports,"applyRow",ADMIN,job.id(),ImportKind.DEPARTMENTS,2));
        assertThat(imports.get(ADMIN,job.id()).applied()).isEqualTo(1);
        var restarted=new BulkImportService(jdbc,mapper,authority,departments,employees,visitors,audit,manager);
        assertThat(restarted.execute(ADMIN,job.id(),job.checksum(),"resume-after-crash").applied()).isEqualTo(2);
        assertThat(count("select count(*) from org_department where code in ('S4_A','S4_B')")).isEqualTo(2);
    }
    @Test void duplicatePoliciesNeverOverwriteExistingNames() {
        var skip=imports.preview(ADMIN,ImportKind.DEPARTMENTS,DuplicatePolicy.SKIP,"code,name\nS4_HR,Overwritten\nS4_HR,Again");assertThat(skip.skipped()).isEqualTo(2);
        var fail=imports.preview(ADMIN,ImportKind.DEPARTMENTS,DuplicatePolicy.FAIL,"code,name\nS4_HR,Overwritten");assertThat(fail.failed()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select name from org_department where id=?",String.class,DEPT)).isEqualTo("S4_HR");
    }
    @Test void mixedEmployeePreviewKeepsInvalidRowsAndImportsOnlyScopedProfilesWithoutAccounts() {
        long accounts=count("select count(*) from iam_user_account");
        var job=imports.preview(HR,ImportKind.EMPLOYEES,DuplicatePolicy.FAIL,employeeCsv("valid","S4_HR")+"Bad,Row,bad@sprint4.test,,UNKNOWN,Engineer,2026-01-01\n");
        assertThat(job.totalRows()).isEqualTo(2);assertThat(job.failed()).isEqualTo(1);assertThat(job.rows().getLast().errors()).isNotEmpty();
        var result=imports.execute(HR,job.id(),job.checksum(),"employee-profile-only");assertThat(result.applied()).isEqualTo(1);assertThat(result.failed()).isEqualTo(1);
        assertThat(count("select count(*) from iam_user_account")).isEqualTo(accounts);
        assertThat(jdbc.queryForObject("select status from employee where official_email='valid@sprint4.test'",String.class)).isEqualTo("ONBOARDING");
    }
    @Test void employeeRowsOutsideCurrentHrDepartmentAreFailedInPreview() {
        department(UUID.randomUUID(),"S4_OTHER");var job=imports.preview(HR,ImportKind.EMPLOYEES,DuplicatePolicy.FAIL,employeeCsv("outsider","S4_OTHER"));
        assertThat(job.failed()).isEqualTo(1);assertThat(job.rows().getFirst().errors()).anyMatch(e->e.contains("another department"));
        assertThat(count("select count(*) from employee where official_email='outsider@sprint4.test'")).isZero();
    }
    @Test void departmentVersionChangedAfterPreviewFailsAffectedEmployeeRow() {
        var job=imports.preview(HR,ImportKind.EMPLOYEES,DuplicatePolicy.FAIL,employeeCsv("changed","S4_HR"));
        jdbc.update("update org_department set version=version+1 where id=?",DEPT);
        var result=imports.execute(HR,job.id(),job.checksum(),"department-version-change");assertThat(result.failed()).isEqualTo(1);assertThat(result.applied()).isZero();
        assertThat(result.rows().getFirst().errors()).anyMatch(e->e.contains("reference changed"));
    }
    @Test void revokedPermissionsBlockExecutionAndSensitiveJobReads() {
        var job=imports.preview(HR,ImportKind.EMPLOYEES,DuplicatePolicy.FAIL,employeeCsv("denied","S4_HR"));
        jdbc.update("insert into iam_user_permission_deny(user_id,permission_name) values(?,'EMPLOYEE_CREATE')",HR);
        assertThatThrownBy(()->imports.execute(HR,job.id(),job.checksum(),"deny-after-preview")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->imports.get(HR,job.id())).isInstanceOf(BusinessException.class);
        assertThat(count("select count(*) from employee where official_email='denied@sprint4.test'")).isZero();
    }
    @Test void unrelatedNewGrantFailsStaleExecutionButDoesNotHideOwnFailedResult() {
        var job=previewDepartment("S4_GRANT","Grant test");jdbc.update("insert into iam_user_permission_grant(user_id,permission_name) values(?,'EMPLOYEE_READ')",ADMIN);
        var result=imports.execute(ADMIN,job.id(),job.checksum(),"grant-after-preview");assertThat(result.failed()).isEqualTo(1);assertThat(result.applied()).isZero();assertThat(imports.get(ADMIN,job.id()).rows()).hasSize(1);
    }
    @Test void anotherOwnerCannotReadExecuteOrExportJob() {
        var job=previewDepartment("S4_PRIVATE","Private");
        assertThatThrownBy(()->imports.get(RECEPTION,job.id())).isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo("IMPORT_NOT_FOUND");
        assertThatThrownBy(()->imports.errors(RECEPTION,job.id())).isInstanceOf(BusinessException.class);assertThatThrownBy(()->imports.execute(RECEPTION,job.id(),job.checksum(),"other-owner-key")).isInstanceOf(BusinessException.class);
    }
    @Test void checksumAndDifferentJobKeyReuseConflictBeforeAnyEffects() {
        var first=previewDepartment("S4_ONE","One");var second=previewDepartment("S4_TWO","Two");
        assertThatThrownBy(()->imports.execute(ADMIN,first.id(),"0".repeat(64),"exact-preview-key")).isInstanceOf(BusinessException.class);
        assertThat(imports.execute(ADMIN,first.id(),first.checksum(),"exact-preview-key").applied()).isEqualTo(1);
        assertThatThrownBy(()->imports.execute(ADMIN,second.id(),second.checksum(),"exact-preview-key")).isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo("IMPORT_KEY_CONFLICT");
        assertThat(count("select count(*) from org_department where code='S4_TWO'")).isZero();
    }
    @Test void expiredPreviewCannotExecuteAndUploadedPiiIsErased() {
        var job=previewDepartment("S4_EXPIRE","Confidential Name");jdbc.update("update bulk_import_job set expires_at=now()-interval '1 minute' where id=?",job.id());
        assertThat(imports.get(ADMIN,job.id()).status()).isEqualTo("EXPIRED");assertThat(imports.get(ADMIN,job.id()).rows().getFirst().values()).isEmpty();
        assertThatThrownBy(()->imports.execute(ADMIN,job.id(),job.checksum(),"expired-key")).isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo("IMPORT_EXPIRED");
    }
    @Test void cleanupErasesAbandonedUploadPiiWithoutDeletingBusinessRecords() {
        var job=previewDepartment("S4_RETAIN","Retained department");imports.execute(ADMIN,job.id(),job.checksum(),"completed-before-expiry");
        jdbc.update("update bulk_import_job set expires_at=now()-interval '1 minute' where id=?",job.id());imports.clearExpiredUploadData();
        var result=imports.get(ADMIN,job.id());assertThat(result.status()).isEqualTo("COMPLETED");assertThat(result.rows().getFirst().values()).isEmpty();assertThat(result.applied()).isEqualTo(1);
        assertThat(count("select count(*) from org_department where code='S4_RETAIN'")).isEqualTo(1);
    }
    @Test void exportedRowErrorsNeutralizeSpreadsheetFormulas() {
        var job=imports.preview(ADMIN,ImportKind.DEPARTMENTS,DuplicatePolicy.FAIL,"code,name\nINVALID CODE,\"=HYPERLINK(\"\"https://example.test\"\")\"");
        assertThat(imports.errors(ADMIN,job.id()).csv()).contains("\"'=HYPERLINK");
    }
    @Test void visitorsFollowRealPendingApprovalWorkflowAndCannotCheckIn() {
        var job=imports.preview(RECEPTION,ImportKind.VISITORS,DuplicatePolicy.FAIL,visitorCsv("visit"));assertThat(job.failed()).isZero();
        var result=imports.execute(RECEPTION,job.id(),job.checksum(),"visitor-pending-only");assertThat(result.applied()).isEqualTo(1);
        String status=jdbc.queryForObject("select status from appointment where id=?",String.class,result.rows().getFirst().recordId());assertThat(status).startsWith("PENDING_").isNotIn("APPROVED","CHECKED_IN","COMPLETED");
        assertThat(count("select count(*) from visit_access_record where appointment_id=?",result.rows().getFirst().recordId())).isZero();
    }
    @Test void invalidVisitorPolicyRowDoesNotRollBackOtherPreviewRows() {
        String good=visitorCsv("valid-policy");String bad=visitorCsv("invalid-policy");
        String[] values=bad.substring(bad.indexOf('\n')+1).split(",",-1);
        values[9]=Instant.parse(values[8]).plusSeconds(1020).toString();
        var job=imports.preview(RECEPTION,ImportKind.VISITORS,DuplicatePolicy.FAIL,good+"\n"+String.join(",",values));
        assertThat(job.totalRows()).isEqualTo(2);assertThat(job.failed()).isEqualTo(1);
        assertThat(job.rows().getFirst().status()).isEqualTo("VALID");
        assertThat(job.rows().getLast().errors()).isNotEmpty();
        assertThat(count("select count(*) from appointment where visitor_email in ('valid-policy@sprint4.test','invalid-policy@sprint4.test')")).isZero();
    }
    @Test void policyOrHostChangesAfterVisitorPreviewFailTheRow() {
        var policy=imports.preview(RECEPTION,ImportKind.VISITORS,DuplicatePolicy.FAIL,visitorCsv("policy"));jdbc.update("update system_setting set version=version+1 where setting_key='APPOINTMENT.MIN_LEAD_MINUTES'");
        assertThat(imports.execute(RECEPTION,policy.id(),policy.checksum(),"visitor-policy-changed").failed()).isEqualTo(1);
        var host=imports.preview(RECEPTION,ImportKind.VISITORS,DuplicatePolicy.FAIL,visitorCsv("host"));jdbc.update("update iam_user_account set enabled=false,account_status='DISABLED' where id=?",HR);
        assertThat(imports.execute(RECEPTION,host.id(),host.checksum(),"visitor-host-disabled").failed()).isEqualTo(1);
        assertThat(count("select count(*) from appointment where visitor_email in ('policy@sprint4.test','host@sprint4.test')")).isZero();
    }
    @Test void setupProgressConflictsOnStaleRevisionAndHonorsRuntimeZone() {
        var initial=setup.state(ADMIN);var updated=setup.progress(ADMIN,initial.revision(),"departments");assertThat(updated.revision()).isGreaterThan(initial.revision());
        assertThatThrownBy(()->setup.progress(ADMIN,initial.revision(),"roles")).isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo("SETUP_REVISION_CONFLICT");
        jdbc.update("update system_setting set setting_value='UTC',version=version+1 where setting_key='COMPANY.OFFICE_ZONE'");
        var drift=setup.state(ADMIN);assertThat(drift.officeZone()).isEqualTo("Asia/Kolkata");assertThat(drift.steps().stream().filter(s->s.id().equals("policy")).findFirst().orElseThrow().issues()).anyMatch(s->s.contains("running deployment"));
    }
    @Test void completedSetupBecomesInProgressAfterLeadershipLostAndIgnoresLoginTimestamps() {
        readyCompany();var current=setup.state(ADMIN);assertThat(current.steps()).allMatch(CompanySetupService.SetupStep::complete);
        var done=setup.complete(ADMIN,current.revision());assertThat(done.status()).isEqualTo("COMPLETE");assertThat(done.completedAt()).isNotNull();
        jdbc.update("update iam_user_account set failed_login_count=1,updated_at=now() where id=?",CEO);assertThat(setup.state(ADMIN).status()).isEqualTo("COMPLETE");
        jdbc.update("update iam_user_account set enabled=false,account_status='DISABLED' where id=?",CEO);var invalidated=setup.state(ADMIN);assertThat(invalidated.status()).isEqualTo("IN_PROGRESS");assertThat(invalidated.completedAt()).isNull();
    }
    @Test void disabledRetentionAndDeniedReviewAuthorityBlockReadiness() {
        readyCompany();jdbc.update("update data_retention_policy set enabled=false where dataset='VISITOR'");jdbc.update("insert into iam_user_permission_deny(user_id,permission_name) values(?,'HR_VISIT_APPROVE')",HR);
        var state=setup.state(ADMIN);assertThat(state.steps().stream().filter(s->s.id().equals("privacy")).findFirst().orElseThrow().issues()).anyMatch(s->s.contains("VISITOR retention"));
        assertThat(state.steps().stream().filter(s->s.id().equals("roles")).findFirst().orElseThrow().complete()).isFalse();assertThatThrownBy(()->setup.complete(ADMIN,state.revision())).isInstanceOf(BusinessException.class);
    }
    private ImportJob previewDepartment(String code,String name) {return imports.preview(ADMIN,ImportKind.DEPARTMENTS,DuplicatePolicy.FAIL,"code,name\n"+code+","+name);}
    private String employeeCsv(String name,String code) {return "firstName,lastName,officialEmail,phoneNumber,departmentCode,designation,joiningDate\n"+name+",Person,"+name+"@sprint4.test,,"+code+",Engineer,2026-01-01\n";}
    private String visitorCsv(String name) {
        LocalDate date=LocalDate.now(ZoneId.of("Asia/Kolkata")).plusDays(2);while(date.getDayOfWeek()==DayOfWeek.SATURDAY||date.getDayOfWeek()==DayOfWeek.SUNDAY) date=date.plusDays(1);
        Instant start=date.atTime(9,30).atZone(ZoneId.of("Asia/Kolkata")).toInstant();
        return "type,visitorName,visitorEmail,visitorPhone,visitorCompany,hostEmployeeId,departmentCode,requestedEmployeeId,slotStart,slotEnd,purpose\nHR_VISIT,Visitor Person,"+name+"@sprint4.test,+919000000000,,"+HR_EMPLOYEE+",S4_HR,,"+start+","+start.plusSeconds(1800)+",Meet department HR";
    }
    private void readyCompany() {
        jdbc.update("update org_department set active=false where code not in ('EXEC','HR','S4_HR')");
        UUID ceoEmployee=UUID.randomUUID();employee(ceoEmployee,DEPT,"ceo");account(CEO,ceoEmployee,"ceo","ROLE_CEO");
        for(var d:jdbc.queryForList("select id,code from org_department where active")) {
            UUID id=(UUID)d.get("id");if(!id.equals(DEPT)) {String label=d.get("code").toString().toLowerCase(Locale.ROOT);UUID emp=UUID.randomUUID(),user=UUID.randomUUID();employee(emp,id,"hr"+label);account(user,emp,"hr"+label,"ROLE_HR_ADMIN");assignment("department_hr_assignment","hr",id,user,emp);}
            String label=d.get("code").toString().toLowerCase(Locale.ROOT);UUID emp=UUID.randomUUID(),user=UUID.randomUUID();employee(emp,id,"manager"+label);account(user,emp,"manager"+label,"ROLE_MANAGER");assignment("department_manager_assignment","manager",id,user,emp);
        }
    }
    private void department(UUID id,String code) {jdbc.update("insert into org_department(id,code,name,active,version,created_at,created_by,updated_at,updated_by) values(?,?,?,true,0,now(),'sprint4-test',now(),'sprint4-test')",id,code,code);}
    private void employee(UUID id,UUID dep,String name) {jdbc.update("insert into employee(id,employee_number,first_name,last_name,display_name,official_email,department_id,designation,joining_date,status,version,created_at,created_by,updated_at,updated_by) values(?,?,?,?,?,?,?,'Test role','2026-01-01','ACTIVE',0,now(),'sprint4-test',now(),'sprint4-test')",id,"S4-"+id.toString().substring(0,12),name,"Person",name+" Person",name+"@sprint4.test",dep);}
    private void account(UUID id,UUID employee,String name,String role) {jdbc.update("insert into iam_user_account(id,email,full_name,employee_id,password_hash,enabled,force_password_change,account_status,archived,version,created_at,created_by,updated_at,updated_by) values(?,?,?,?,'test-only-hash',true,false,'ACTIVE',false,0,now(),'sprint4-test',now(),'sprint4-test')",id,name+"@sprint4.test",name,employee);jdbc.update("insert into iam_user_role(user_id,role_name) values(?,?)",id,role);}
    private void assignment(String table,String prefix,UUID dep,UUID user,UUID employee) {jdbc.update("insert into "+table+"(id,department_id,"+prefix+"_user_id,"+prefix+"_employee_id,active,assigned_by_user_id,assigned_at,version,created_at,created_by,updated_at,updated_by) values(?,?,?,?,true,?,now(),0,now(),'sprint4-test',now(),'sprint4-test')",UUID.randomUUID(),dep,user,employee,ADMIN);}
    private long count(String sql,Object... args) {return Objects.requireNonNull(jdbc.queryForObject(sql,Long.class,args));}
}
