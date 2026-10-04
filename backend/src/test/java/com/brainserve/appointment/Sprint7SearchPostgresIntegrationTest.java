package com.brainserve.appointment;

import com.brainserve.appointment.search.application.UnifiedSearchService;
import com.brainserve.appointment.shared.api.SearchContract;
import com.brainserve.appointment.shared.application.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Real PostgreSQL/current-authority reads. Required CI rejects disabled Docker skips. */
@Testcontainers(disabledWithoutDocker=true)
@SpringBootTest(properties={"brainserve.security.jwt-secret=test-only-secret-key-that-is-at-least-thirty-two-bytes",
        "brainserve.security.pii-encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "brainserve.bootstrap.system-admin-enabled=false","brainserve.bootstrap.ceo-enabled=false",
        "brainserve.appointment.office-zone=Asia/Kolkata","spring.task.scheduling.enabled=false",
        "spring.kafka.listener.auto-startup=false","brainserve.notification.internal-call-dispatch-ms=3600000","brainserve.notification.poll-ms=3600000",
        "aws.s3.access-key=test-access-key","aws.s3.secret-key=test-secret-key"})
class Sprint7SearchPostgresIntegrationTest {
    @Container static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>("postgres:17.2-alpine");
    @Container static final GenericContainer<?> REDIS=new GenericContainer<>("redis:7.4.1-alpine").withExposedPorts(6379);
    @DynamicPropertySource static void infrastructure(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",POSTGRES::getJdbcUrl);r.add("spring.datasource.username",POSTGRES::getUsername);r.add("spring.datasource.password",POSTGRES::getPassword);
        r.add("spring.data.redis.host",REDIS::getHost);r.add("spring.data.redis.port",()->REDIS.getMappedPort(6379));
    }
    @Autowired JdbcTemplate jdbc;@Autowired UnifiedSearchService search;@Autowired ObjectMapper mapper;
    @org.springframework.test.context.bean.override.mockito.MockitoBean software.amazon.awssdk.services.s3.S3Client s3;
    @org.springframework.test.context.bean.override.mockito.MockitoBean com.brainserve.appointment.document.infrastructure.ClamAvScanner scanner;
    static final UUID DEPT=id(101),OTHER_DEPT=id(102),EMP=id(201),OTHER_EMP=id(202),HR_EMP=id(203),LEAD_EMP=id(204),CEO_EMP=id(205),SEC_EMP=id(206),RECEPTION_EMP=id(207);
    static final UUID USER=id(1),OTHER=id(2),HR=id(3),LEAD=id(4),CEO=id(5),SECURITY=id(6),RECEPTION=id(7),ADMIN=id(8);
    static final UUID TASK=id(301),SECRET_TASK=id(302),APPOINTMENT=id(401),SECRET_APPOINTMENT=id(402),VISITOR=id(501),RESTRICTED_VISITOR=id(502);
    @BeforeEach void fixture() {
        // Each test class owns its disposable container. No operational database is touched.
        jdbc.execute("truncate table department_work_task,work_task_audit_record,appointment,visitor,iam_user_account,employee,org_department restart identity cascade");
        department(DEPT,"S7_MAIN");department(OTHER_DEPT,"S7_OTHER");
        employee(EMP,DEPT,"Needle Employee");employee(OTHER_EMP,OTHER_DEPT,"Needle SecretEmployee");employee(HR_EMP,DEPT,"HR Reader");employee(LEAD_EMP,DEPT,"Lead Reader");employee(CEO_EMP,DEPT,"CEO Reader");employee(SEC_EMP,DEPT,"Security Reader");employee(RECEPTION_EMP,DEPT,"Reception Reader");
        account(USER,EMP,"ROLE_EMPLOYEE");account(OTHER,OTHER_EMP,"ROLE_EMPLOYEE");account(HR,HR_EMP,"ROLE_HR_ADMIN");account(LEAD,LEAD_EMP,"ROLE_TEAM_LEAD");account(CEO,CEO_EMP,"ROLE_CEO");account(SECURITY,SEC_EMP,"ROLE_SECURITY");account(RECEPTION,RECEPTION_EMP,"ROLE_RECEPTIONIST");account(ADMIN,null,"ROLE_SYSTEM_ADMIN");
        assignment("department_hr_assignment","hr",HR,HR_EMP);assignment("department_team_lead","team_lead",LEAD,LEAD_EMP);
        task(TASK,DEPT,EMP,LEAD,"Needle worksheet");task(SECRET_TASK,OTHER_DEPT,OTHER_EMP,LEAD,"Needle SecretWorksheet");
        appointment(APPOINTMENT,DEPT,EMP,EMP,"Needle visitor","PENDING_HR_APPROVAL");appointment(SECRET_APPOINTMENT,OTHER_DEPT,OTHER_EMP,OTHER_EMP,"Needle SecretVisitor","PENDING_HR_APPROVAL");
        visitor(VISITOR,"Needle Registry",false);visitor(RESTRICTED_VISITOR,"Needle Restricted",true);
    }
    @Test void queryAndCountsNeverExposeForeignIdsNamesOrSnippets() throws Exception {
        var r=find(HR,"Needle",0,5);String json=mapper.writeValueAsString(r);
        assertThat(group(r,"appointments").totalElements()).isEqualTo(1);assertThat(group(r,"employees").totalElements()).isEqualTo(1);assertThat(group(r,"worksheets").totalElements()).isEqualTo(1);
        assertThat(group(r,"visitors").available()).isFalse();assertThat(group(r,"visitors").items()).isEmpty();assertThat(group(r,"visitors").totalElements()).isZero();
        assertThat(json).doesNotContain("Secret",SECRET_TASK.toString(),SECRET_APPOINTMENT.toString(),OTHER_EMP.toString(),VISITOR.toString(),"government", "sensitive-contact");
        assertThat(group(find(OTHER,"Needle",0,5),"worksheets").items()).extracting(SearchContract.Item::id).containsExactly(SECRET_TASK);
        assertThat(code(()->search.open(HR,"appointments",SECRET_APPOINTMENT))).isEqualTo(code(()->search.open(HR,"appointments",UUID.randomUUID())));
        assertThat(code(()->search.open(HR,"employees",OTHER_EMP))).isEqualTo(code(()->search.open(HR,"employees",UUID.randomUUID())));
        assertThat(code(()->search.open(USER,"worksheets",SECRET_TASK))).isEqualTo(code(()->search.open(USER,"worksheets",UUID.randomUUID())));
    }
    @Test void employeeReceivesOnlyOwnedWorksheetsAndHrForwardedVisitorCards() {
        assertThat(group(find(USER,"Needle",0,5),"appointments").items()).isEmpty();
        jdbc.update("update appointment set status='PENDING_TEAM_LEAD_APPROVAL',hr_approval_actor_id=?,hr_decision_at=now() where id=?",HR,APPOINTMENT);
        assertThat(group(find(USER,"Needle",0,5),"appointments").items()).extracting(SearchContract.Item::id).containsExactly(APPOINTMENT);
        assertThat(search.open(USER,"appointments",APPOINTMENT).title()).isEqualTo("Needle visitor");
        jdbc.update("update appointment set requested_employee_id=? where id=?",HR_EMP,APPOINTMENT);
        assertThat(code(()->search.open(USER,"appointments",APPOINTMENT))).isEqualTo("SEARCH_RECORD_NOT_FOUND");
        task(id(303),DEPT,HR_EMP,LEAD,"Needle colleague worksheet");
        assertThat(group(find(USER,"Needle",0,5),"worksheets").items()).extracting(SearchContract.Item::id).containsExactly(TASK);
        jdbc.update("update department_work_task set team_lead_user_id=? where id=?",OTHER,TASK);
        assertThat(group(find(LEAD,"Needle worksheet",0,5),"worksheets").items()).isEmpty();
        assertThat(code(()->search.open(LEAD,"worksheets",TASK))).isEqualTo(code(()->search.open(LEAD,"worksheets",UUID.randomUUID())));
    }
    @Test void currentPermissionRoleDepartmentAndAccountRevocationsApplyToQueryAndOpen() {
        assertThat(search.open(USER,"worksheets",TASK).id()).isEqualTo(TASK);
        jdbc.update("insert into iam_user_permission_deny(user_id,permission_name) values(?,'WORK_TASK_READ')",USER);
        assertThat(group(find(USER,"Needle",0,5),"worksheets").available()).isFalse();assertThat(code(()->search.open(USER,"worksheets",TASK))).isEqualTo("SEARCH_RECORD_NOT_FOUND");
        jdbc.update("delete from iam_user_permission_deny where user_id=?",USER);
        jdbc.update("update employee set department_id=?,version=version+1 where id=?",OTHER_DEPT,EMP);
        assertThat(group(find(USER,"Needle",0,5),"worksheets").items()).isEmpty();assertThat(code(()->search.open(USER,"worksheets",TASK))).isEqualTo("WORK_TASK_NOT_FOUND");
        jdbc.update("update iam_user_role set role_name='ROLE_SECURITY' where user_id=?",USER);
        assertThat(group(find(USER,"Needle",0,5),"employees").available()).isFalse();
        jdbc.update("update iam_user_account set enabled=false where id=?",USER);
        assertThat(code(()->find(USER,"Needle",0,5))).isEqualTo("ACCOUNT_INACTIVE");assertThat(code(()->search.open(USER,"worksheets",TASK))).isEqualTo("ACCOUNT_INACTIVE");
    }
    @Test void assignmentAndActiveDepartmentEligibilityAreReadFromCurrentDatabase() {
        assertThat(group(find(HR,"Needle",0,5),"appointments").totalElements()).isEqualTo(1);
        jdbc.update("update department_hr_assignment set active=false,version=version+1 where hr_user_id=?",HR);
        assertThat(code(()->find(HR,"Needle",0,5))).isEqualTo("ACCOUNT_INACTIVE");
        assertThat(code(()->search.open(HR,"appointments",APPOINTMENT))).isEqualTo(code(()->search.open(HR,"appointments",UUID.randomUUID())));
        jdbc.update("update org_department set active=false,version=version+1 where id=?",DEPT);
        assertThat(code(()->find(USER,"Needle",0,5))).isEqualTo("ACCOUNT_INACTIVE");
        assertThat(code(()->find(SECURITY,"Needle",0,5))).isEqualTo("SEARCH_SCOPE_CHANGED");
    }
    @Test void registryPermissionCannotWidenDepartmentRoleAndNeverSearchesGovernmentOrContactFields() throws Exception {
        assertThat(group(find(HR,"Needle",0,5),"visitors").available()).isFalse();
        var r=find(RECEPTION,"Needle",0,5);assertThat(group(r,"visitors").items()).extracting(SearchContract.Item::id).containsExactly(VISITOR);
        assertThat(group(find(SECURITY,"Needle",0,5),"visitors").totalElements()).isEqualTo(1);
        assertThat(group(find(SECURITY,"sensitive-contact",0,5),"visitors").items()).isEmpty();
        assertThat(group(find(SECURITY,"identity-secret",0,5),"visitors").items()).isEmpty();
        assertThat(mapper.writeValueAsString(search.open(SECURITY,"visitors",VISITOR))).doesNotContain("government", "identity-secret", "sensitive-contact");
        assertThat(code(()->search.open(SECURITY,"visitors",RESTRICTED_VISITOR))).isEqualTo(code(()->search.open(SECURITY,"visitors",UUID.randomUUID())));
        jdbc.update("insert into iam_user_permission_deny(user_id,permission_name) values(?,'VISITOR_VERIFY')",SECURITY);
        assertThat(group(find(SECURITY,"Needle",0,5),"visitors").available()).isFalse();assertThat(code(()->search.open(SECURITY,"visitors",VISITOR))).isEqualTo("SEARCH_RECORD_NOT_FOUND");
    }
    @Test void stableGroupedPaginationKeepsScopedTotalsAndTreatsWildcardsAsLiteralText() {
        for(int n=310;n<317;n++)task(id(n),DEPT,EMP,LEAD,"Pagination worksheet");
        var first=find(USER,"Pagination",0,2);var second=find(USER,"Pagination",1,2);
        assertThat(group(first,"worksheets").totalElements()).isEqualTo(7);assertThat(group(first,"worksheets").totalPages()).isEqualTo(4);
        assertThat(group(first,"worksheets").items()).extracting(SearchContract.Item::id).containsExactly(id(310),id(311));
        assertThat(group(second,"worksheets").items()).extracting(SearchContract.Item::id).containsExactly(id(312),id(313));
        assertThat(group(find(USER,"Pagination",9,2),"worksheets").totalElements()).isEqualTo(7);assertThat(group(find(USER,"Pagination",9,2),"worksheets").items()).isEmpty();
        assertThat(group(find(HR,"%_",0,5),"worksheets").totalElements()).isZero();
        task(id(318),DEPT,EMP,LEAD,"Literal %_ worksheet");assertThat(group(find(USER,"%_",0,5),"worksheets").items()).extracting(SearchContract.Item::id).containsExactly(id(318));
        assertThat(code(()->find(USER,"x",0,5))).isEqualTo("SEARCH_QUERY_INVALID");assertThat(code(()->find(USER,"x".repeat(101),0,5))).isEqualTo("SEARCH_QUERY_INVALID");
        assertThat(code(()->find(USER,"Needle",1000,5))).isEqualTo("SEARCH_PAGE_INVALID");assertThat(code(()->find(USER,"Needle",0,26))).isEqualTo("SEARCH_PAGE_INVALID");
    }
    @Test void ceoSeesOnlyPublishedOversightSnapshotsAndOpensRecheckDeliveryStatus() {
        audit(TASK,"Needle published title","PENDING_CEO_APPROVAL");audit(SECRET_TASK,"Needle manager-only title","PENDING_MANAGER_APPROVAL");
        jdbc.update("update department_work_task set description='Unpublished internal secret',title='Live overwritten secret' where id=?",TASK);
        var r=find(CEO,"Needle",0,5);assertThat(group(r,"worksheets").items()).extracting(SearchContract.Item::id).containsExactly(TASK);
        assertThat(search.open(CEO,"worksheets",TASK).title()).isEqualTo("Needle published title");assertThat(search.open(CEO,"worksheets",TASK).route()).isEqualTo("insights");
        assertThat(group(find(CEO,"Unpublished",0,5),"worksheets").items()).isEmpty();
        jdbc.update("update work_task_audit_record set audit_status='PENDING_MANAGER_APPROVAL' where work_task_id=?",TASK);
        assertThat(code(()->search.open(CEO,"worksheets",TASK))).isEqualTo(code(()->search.open(CEO,"worksheets",UUID.randomUUID())));
        assertThat(group(find(ADMIN,"Needle",0,5),"worksheets").available()).isFalse();
    }
    @Test void additiveMigrationsCreateOnlySafeDomainSearchIndexesAndCanValidateTwice() {
        var flyway=Flyway.configure().dataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword()).load();flyway.validate();assertThat(flyway.migrate().migrationsExecuted).isZero();
        var definitions=jdbc.queryForList("select indexdef from pg_indexes where indexname like 'ix_search_%'",String.class);assertThat(definitions).hasSize(8);
        assertThat(String.join(" ",definitions)).doesNotContain("government_id", "visitor_email", "official_email", "security_notes");
    }
    private SearchContract.Response find(UUID actor,String q,int page,int size){return search.search(actor,q,0,0,0,page,size);}
    private SearchContract.Group group(SearchContract.Response r,String type){return r.groups().stream().filter(g->g.type().equals(type)).findFirst().orElseThrow();}
    private String code(Runnable action){try{action.run();throw new AssertionError("Expected a business error");}catch(BusinessException e){return e.getErrorCode();}}
    private static UUID id(int suffix){return UUID.fromString(String.format(Locale.ROOT,"77000000-0000-0000-0000-%012d",suffix));}
    private void department(UUID id,String code){jdbc.update("insert into org_department(id,code,name,active,version,created_at,created_by,updated_at,updated_by) values(?,?,?,true,0,now(),'sprint7-search',now(),'sprint7-search')",id,code,code);}
    private void employee(UUID id,UUID department,String name){jdbc.update("insert into employee(id,employee_number,first_name,last_name,display_name,official_email,department_id,designation,joining_date,status,version,created_at,created_by,updated_at,updated_by) values(?,?,?,'Person',?,?,?,'Engineer','2026-01-01','ACTIVE',0,now(),'sprint7-search',now(),'sprint7-search')",id,"S7-"+id,name,name,id+"@sprint7-search.test",department);}
    private void account(UUID id,UUID employee,String role){jdbc.update("insert into iam_user_account(id,email,full_name,employee_id,password_hash,enabled,force_password_change,account_status,archived,version,created_at,created_by,updated_at,updated_by) values(?,?,?,?,'test-hash',true,false,'ACTIVE',false,0,now(),'sprint7-search',now(),'sprint7-search')",id,id+"@sprint7-search.test",role,employee);jdbc.update("insert into iam_user_role(user_id,role_name) values(?,?)",id,role);}
    private void assignment(String table,String prefix,UUID user,UUID employee){jdbc.update("insert into "+table+"(id,department_id,"+prefix+"_user_id,"+prefix+"_employee_id,active,assigned_by_user_id,assigned_at,version,created_at,created_by,updated_at,updated_by) values(?,?,?,?,true,?,now(),0,now(),'sprint7-search',now(),'sprint7-search')",UUID.randomUUID(),DEPT,user,employee,ADMIN);}
    private void task(UUID id,UUID department,UUID employee,UUID lead,String title){jdbc.update("insert into department_work_task(id,department_id,employee_id,team_lead_user_id,assigned_by_user_id,assigned_by_role,assignee_role,title,description,department_branch,due_date,status,version,created_at,created_by,updated_at,updated_by) values(?,?,?,?,?,'TEAM_LEAD','EMPLOYEE',?,'Scoped instructions','Search branch','2099-01-01','ASSIGNED',0,now(),'sprint7-search',now(),'sprint7-search')",id,department,employee,lead,lead,title);}
    private void appointment(UUID id,UUID department,UUID host,UUID requested,String name,String status){jdbc.update("insert into appointment(id,reference_number,idempotency_key,type,status,visitor_name,visitor_email,visitor_phone,host_employee_id,routing_department_id,requested_employee_id,slot_start,slot_end,purpose,version,created_at,created_by,updated_at,updated_by) values(?,?,?,'EMPLOYEE_VISIT',?,?,'sensitive-contact@example.test','1234567890',?,?,?,'2099-01-01 10:00:00+00','2099-01-01 11:00:00+00','Scoped appointment purpose',0,now(),'sprint7-search',now(),'sprint7-search')",id,"S7-"+id,id.toString(),status,name,host,department,requested);}
    private void visitor(UUID id,String name,boolean restricted){jdbc.update("insert into visitor(id,idempotency_key,name,email,phone,company,government_id_encrypted,government_id_last4,identity_verified,consent_version,consented_at,restricted,version,created_at,created_by,updated_at,updated_by) values(?,?,?,'sensitive-contact@example.test','1234567890','Search company','identity-secret','1234',false,'2026.1',now(),?,0,now(),'sprint7-search',now(),'sprint7-search')",id,id.toString(),name,restricted);}
    private void audit(UUID task,String title,String status){jdbc.update("insert into work_task_audit_record(id,work_task_id,week_start,department_id,department_name,employee_id,employee_number,employee_name,team_lead_user_id,team_lead_name,assigned_by_role,assignee_role,task_title,task_status,audit_status,hr_audited_by_user_id,hr_audited_at,version,created_at,created_by,updated_at,updated_by) values(?,?,'2099-01-01',?,'Search branch',?,'S7-EMP','Employee snapshot',?,'Lead snapshot','TEAM_LEAD','EMPLOYEE',?,'APPROVED',?,?,now(),0,now(),'sprint7-search',now(),'sprint7-search')",UUID.randomUUID(),task,DEPT,EMP,LEAD,title,status,HR);}
}
