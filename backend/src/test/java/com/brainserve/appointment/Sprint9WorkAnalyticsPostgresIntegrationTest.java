package com.brainserve.appointment;

import com.brainserve.appointment.document.infrastructure.ClamAvScanner;
import com.brainserve.appointment.shared.application.BusinessException;
import com.brainserve.appointment.workinsight.application.WorkInsightService;
import com.brainserve.appointment.worktask.application.*;
import com.brainserve.appointment.worktask.domain.DepartmentWorkTask;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
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

/** Independent PRD metric reconciliation using real PostgreSQL/Redis and the canonical workflow writers. */
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
class Sprint9WorkAnalyticsPostgresIntegrationTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.2-alpine");
    @Container static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4.1-alpine").withExposedPorts(6379);
    @DynamicPropertySource static void infrastructure(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);r.add("spring.data.redis.host", REDIS::getHost);
        r.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate named;
    @Autowired com.brainserve.appointment.iam.api.CurrentAccountAuthority authority;
    @Autowired WorkAnalyticsService analytics;
    @Autowired DepartmentWorkTaskService tasks;
    @Autowired WorkTaskHandoverService handovers;
    @Autowired TaskPlanningService planning;
    @Autowired WorkInsightService insights;
    @Autowired @Qualifier("notificationExecutor") Executor notificationExecutor;
    @MockitoBean S3Client s3;
    @MockitoBean ClamAvScanner scanner;
    static final ZoneId OFFICE=ZoneId.of("Asia/Kolkata");
    static final UUID DEPT=id(101),OTHER_DEPT=id(102),EMP=id(201),NEW_EMP=id(202),LEAD_EMP=id(203),
            HR_EMP=id(204),MANAGER_EMP=id(205),OTHER_EMP=id(206),OTHER_LEAD_EMP=id(207),OTHER_HR_EMP=id(208),CEO_EMP=id(209);
    static final UUID USER=id(1),NEW_USER=id(2),LEAD=id(3),HR=id(4),MANAGER=id(5),OTHER=id(6),ADMIN=id(7),
            CEO=id(8),OTHER_LEAD=id(9),OTHER_HR=id(10);
    @BeforeEach void fixture() {
        drain();org.mockito.Mockito.reset(s3,scanner);
        jdbc.execute("truncate stored_document, workboard_preference, work_task_audit_record, department_work_task, work_original_commitment, internal_call_notification cascade");
        jdbc.execute("truncate department_hr_assignment, department_team_lead, department_manager_assignment, iam_user_account, employee, org_department cascade");
        department(DEPT,"S9A_MAIN");department(OTHER_DEPT,"S9A_OTHER");
        employee(EMP,DEPT,"employee");employee(NEW_EMP,DEPT,"incoming");employee(LEAD_EMP,DEPT,"lead");
        employee(HR_EMP,DEPT,"hr");employee(MANAGER_EMP,DEPT,"manager");employee(OTHER_EMP,OTHER_DEPT,"foreign");
        employee(OTHER_LEAD_EMP,OTHER_DEPT,"foreignlead");employee(OTHER_HR_EMP,OTHER_DEPT,"foreignhr");employee(CEO_EMP,DEPT,"ceo");
        account(USER,EMP,"employee","ROLE_EMPLOYEE");account(NEW_USER,NEW_EMP,"incoming","ROLE_EMPLOYEE");
        account(LEAD,LEAD_EMP,"lead","ROLE_TEAM_LEAD");account(HR,HR_EMP,"hr","ROLE_HR_ADMIN");
        account(MANAGER,MANAGER_EMP,"manager","ROLE_MANAGER");account(OTHER,OTHER_EMP,"foreign","ROLE_EMPLOYEE");
        account(ADMIN,null,"admin","ROLE_SYSTEM_ADMIN");account(CEO,CEO_EMP,"ceo","ROLE_CEO");
        account(OTHER_LEAD,OTHER_LEAD_EMP,"foreignlead","ROLE_TEAM_LEAD");account(OTHER_HR,OTHER_HR_EMP,"foreignhr","ROLE_HR_ADMIN");
        assignment("department_team_lead","team_lead",DEPT,LEAD,LEAD_EMP);
        assignment("department_hr_assignment","hr",DEPT,HR,HR_EMP);
        assignment("department_manager_assignment","manager",DEPT,MANAGER,MANAGER_EMP);
        assignment("department_team_lead","team_lead",OTHER_DEPT,OTHER_LEAD,OTHER_LEAD_EMP);
        assignment("department_hr_assignment","hr",OTHER_DEPT,OTHER_HR,OTHER_HR_EMP);
        assertThat(count("select count(*) from iam_user_account u where (select count(*) from iam_user_role r where r.user_id=u.id)<>1")).isZero();
    }
    @AfterEach void settled(){drain();}

    @Test void contextUsesCurrentOfficeScopeAndAllThirteenCardsHaveHonestDefinitions() {
        assertThat(POSTGRES.isRunning()).isTrue();assertThat(REDIS.isRunning()).isTrue();
        assertThat(count("select count(*) from flyway_schema_history where version='62' and success")).isEqualTo(1);
        JsonNode context=json(analytics.context(HR));assertThat(context.path("metricVersion").asText()).isEqualTo("sprint9.v1");
        assertThat(context.path("officeZone").asText()).isEqualTo(OFFICE.getId());assertThat(context.path("officeDate").asText()).isEqualTo(today().toString());
        assertThat(ids(context.path("departmentOptions"))).containsExactly(DEPT);
        JsonNode result=summary(HR,today(),today());assertThat(result.path("cards").size()).isEqualTo(13);
        for(int n=1;n<=13;n++) {JsonNode c=card(result,String.format(Locale.ROOT,"WORK%02d",n));assertThat(c.path("definition").asText()).isNotBlank();assertThat(c.path("coverageKnown").asLong()).isLessThanOrEqualTo(c.path("coverageTotal").asLong());}
        assertThat(json(analytics.context(USER)).path("canReadWorkload").asBoolean()).isFalse();
        assertThat(json(analytics.context(CEO)).path("scope").asText()).isNotBlank();
    }

    @Test void mixedLifecycleReconcilesStockDeliveryReviewSubmissionCarryAndFinalFlowDefinitions() {
        var overdue=create(EMP,"Overdue carry",today().minusDays(1));
        jdbc.update("update department_work_task set created_at=? where id=?",Timestamp.from(today().minusDays(2).atStartOfDay(OFFICE).toInstant()),overdue.getId());
        var waiting=create(EMP,"Waiting Lead",today());submit(USER,EMP,waiting.getId(),"Waiting on reviewer");
        var blocked=create(NEW_EMP,"Open dependency",today().plusDays(1));
        var p=planning.get(NEW_USER,blocked.getId());as(NEW_USER,()->planning.raise(NEW_USER,blocked.getId(),new TaskPlanningService.Raise(p.taskVersion(),"Pending dependency",LEAD)));
        var accepted=create(EMP,"Accepted awaiting HR",today());submit(USER,EMP,accepted.getId(),"Accepted delivery");accept(accepted.getId());
        var closed=create(NEW_EMP,"CEO final",today());submit(NEW_USER,NEW_EMP,closed.getId(),"Final delivery");accept(closed.getId());close(closed.getId());
        JsonNode hr=summary(HR,today(),today()),lead=summary(LEAD,today(),today());
        value(hr,"WORK01",4);value(hr,"WORK02",2);value(hr,"WORK03",1);value(hr,"WORK04",1);
        value(lead,"WORK05",1);value(hr,"WORK05",1);
        assertThat(card(hr,"WORK06").path("sampleCount").asLong()).isEqualTo(3);
        ratio(hr,"WORK07",2,3);
        ratio(hr,"WORK09",0,1);
        value(hr,"WORK11",4);value(hr,"WORK12",1);value(hr,"WORK13",1);
        assertThat(recordIds(HR,"WORK01",today(),today())).containsExactlyInAnyOrder(overdue.getId(),waiting.getId(),blocked.getId(),accepted.getId());
        assertThat(recordIds(HR,"WORK03",today(),today())).containsExactly(overdue.getId());
        assertThat(recordIds(HR,"WORK04",today(),today())).containsExactly(blocked.getId());
        assertThat(recordIds(LEAD,"WORK05",today(),today())).containsExactly(waiting.getId());
        assertThat(recordIds(HR,"WORK07",today(),today())).containsExactlyInAnyOrder(waiting.getId(),accepted.getId(),closed.getId());
        assertThat(recordIds(HR,"WORK13",today(),today())).containsExactly(closed.getId());
        assertThat(hr.path("stages").size()).isEqualTo(4);
        assertThat(hr.path("trend").size()).isGreaterThan(0);
    }

    @Test void originalDueCohortRetainsLateAndUnfinishedTasksAfterMutableDeadlineEdits() {
        var accepted=create(EMP,"On-time original commitment",today());submit(USER,EMP,accepted.getId(),"Delivered on time");accept(accepted.getId());
        var unfinished=create(NEW_EMP,"Unfinished original commitment",today());
        var late=create(EMP,"Late accepted commitment",today().minusDays(1));submit(USER,EMP,late.getId(),"Delivered late");accept(late.getId());
        JsonNode before=summary(HR,today().minusDays(1),today());ratio(before,"WORK07",1,3);
        for(UUID id:List.of(unfinished.getId(),late.getId(),accepted.getId())) {
            var p=planning.get(LEAD,id);as(LEAD,()->planning.update(LEAD,id,new TaskPlanningService.Update(p.taskVersion(),"NORMAL",null,false,today().plusDays(60),"Revised scheduling commitment",List.of())));
        }
        JsonNode after=summary(HR,today().minusDays(1),today());ratio(after,"WORK07",1,3);
        assertThat(recordIds(HR,"WORK07",today().minusDays(1),today())).containsExactlyInAnyOrder(accepted.getId(),unfinished.getId(),late.getId());
        assertThat(date("select original_due_date from work_original_commitment where work_task_id=?",late.getId())).isEqualTo(today().minusDays(1));
        assertThat(after.path("trend")).isEqualTo(before.path("trend"));
    }

    @Test void evidenceAcceptanceBeforeCeoCountsForEmployeeAndDirectLeadAndReworkInvalidatesIt() {
        var employee=create(EMP,"Employee acceptance before CEO",today());submit(USER,EMP,employee.getId(),"Employee delivered");accept(employee.getId());
        var direct=as(HR,()->tasks.create(HR,new DepartmentWorkTaskService.CreateCommand(LEAD_EMP,"Direct Lead acceptance before CEO","Direct worksheet",today())));
        submit(LEAD,LEAD_EMP,direct.getId(),"Direct Lead delivered");as(HR,()->insights.markAudited(HR,direct.getId()));
        ratio(summary(HR,today(),today()),"WORK07",2,2);
        assertThat(count("select count(*) from work_task_audit_record where audit_status='CEO_APPROVED'")).isZero();
        as(LEAD,()->tasks.requestChanges(LEAD,employee.getId(),"Revise accepted Employee evidence"));
        ratio(summary(HR,today(),today()),"WORK07",1,2);
        as(HR,()->insights.requestHrRework(HR,direct.getId(),"Revise direct Lead evidence"));
        ratio(summary(HR,today(),today()),"WORK07",0,2);
        assertThat(planning.get(LEAD,direct.getId()).submissions().getFirst().acceptedAt()).isNotNull();
    }

    @Test void handoverInvalidatesCurrentAcceptanceWithoutRemovingOriginalDenominatorOrAuthoredHistory() {
        var task=create(EMP,"Handed over original cohort",today());submit(USER,EMP,task.getId(),"Original author delivery");accept(task.getId());
        ratio(summary(HR,today(),today()),"WORK07",1,1);
        as(LEAD,()->handovers.handover(LEAD,task.getId(),new WorkTaskHandoverService.Change(handovers.get(LEAD,task.getId()).taskVersion(),NEW_EMP,"New accountable assignee",null)));
        ratio(summary(HR,today(),today()),"WORK07",0,1);
        assertThat(recordIds(USER,"WORK07",today(),today())).isEmpty();assertThat(recordIds(NEW_USER,"WORK07",today(),today())).containsExactly(task.getId());
        assertThat(planning.get(NEW_USER,task.getId()).submissions().getFirst().authorEmployeeId()).isEqualTo(EMP);
        submit(NEW_USER,NEW_EMP,task.getId(),"Incoming author delivery");accept(task.getId());
        ratio(summary(HR,today(),today()),"WORK07",1,1);
    }

    @Test void submissionAttemptsAreDistinctVersionsAndAreNotApprovalOrTaskCompletionCounts() {
        var task=create(EMP,"Multiple evidence attempts",today());submit(USER,EMP,task.getId(),"Attempt one");
        as(LEAD,()->tasks.requestChanges(LEAD,task.getId(),"Correct first attempt"));submit(USER,EMP,task.getId(),"Attempt two");
        JsonNode before=summary(HR,today(),today());JsonNode c=card(before,"WORK06");
        assertThat(c.path("sampleCount").asLong()).isEqualTo(2);
        assertThat(c.path("value").asLong()).isEqualTo(2);
        assertThat(c.path("reason").asText()).contains("unique tasks: 1");
        assertThat(recordIds(HR,"WORK06",today(),today())).hasSize(2).containsOnly(task.getId());
        value(before,"WORK13",0);ratio(before,"WORK07",0,1);
        accept(task.getId());JsonNode after=summary(HR,today(),today());
        assertThat(card(after,"WORK06").path("sampleCount").asLong()).isEqualTo(2);value(after,"WORK13",0);ratio(after,"WORK07",1,1);
        assertThat(recordIds(HR,"WORK06",today().minusDays(2),today().minusDays(1))).isEmpty();
    }

    @Test void workloadRetainsUnavailableAssignmentsAndPartialEstimatesWithoutInventingCapacity() {
        var estimated=create(EMP,"Estimated outstanding",today());var unknown=create(EMP,"Unknown effort",today().plusDays(1));
        var unavailable=create(NEW_EMP,"Unavailable assigned member",today().minusDays(1));
        var p=planning.get(LEAD,estimated.getId());as(LEAD,()->planning.update(LEAD,estimated.getId(),new TaskPlanningService.Update(p.taskVersion(),"HIGH",60,false,today(),"Maintained estimate",List.of())));
        leave(NEW_EMP,NEW_USER,"APPROVED");
        JsonNode work=json(analytics.workload(HR,null));JsonNode member=member(work,EMP),absent=member(work,NEW_EMP);
        assertThat(member.path("activeTasks").asLong()).isEqualTo(2);assertThat(member.path("dueToday").asLong()).isEqualTo(1);
        assertThat(member.path("upcoming").asLong()).isEqualTo(1);assertThat(member.path("estimatedMinutes").asLong()).isEqualTo(60);
        assertThat(member.path("estimatedTasks").asLong()).isEqualTo(1);assertThat(member.path("unestimatedTasks").asLong()).isEqualTo(1);
        assertThat(member.path("capacityMinutes").isNull()).isTrue();
        assertThat(absent.path("eligible").asBoolean()).isFalse();assertThat(absent.path("unavailableReasons").size()).isGreaterThan(0);
        assertThat(absent.path("activeTasks").asLong()).isEqualTo(1);assertThat(absent.path("overdueDelivery").asLong()).isEqualTo(1);
        assertThat(work.path("totals").path("activeTasks").asLong()).isEqualTo(3);
        assertThat(recordIds(HR,"WORK11",today(),today())).containsExactlyInAnyOrder(estimated.getId(),unknown.getId(),unavailable.getId());
        jdbc.update("update employee_leave_request set status='REJECTED' where employee_id=?",NEW_EMP);
        assertThat(member(json(analytics.workload(HR,null)),NEW_EMP).path("eligible").asBoolean()).isTrue();
        jdbc.update("update employee set status='TERMINATED' where id=?",NEW_EMP);
        assertThat(member(json(analytics.workload(HR,null)),NEW_EMP).path("eligible").asBoolean()).isFalse();
        assertThat(member(json(analytics.workload(HR,null)),NEW_EMP).path("activeTasks").asLong()).isEqualTo(1);
        jdbc.update("update employee set status='ACTIVE' where id=?",NEW_EMP);jdbc.update("update iam_user_account set enabled=false where id=?",NEW_USER);
        assertThat(member(json(analytics.workload(HR,null)),NEW_EMP).path("eligible").asBoolean()).isFalse();
    }

    @Test void carryOverCountsEachOutstandingWorksheetOnceAcrossManySnapshotsAndHandover() {
        var old=create(EMP,"Single carried worksheet",today().minusDays(2));
        jdbc.update("update department_work_task set created_at=? where id=?",Timestamp.from(today().minusDays(10).atStartOfDay(OFFICE).toInstant()),old.getId());
        for(int n=0;n<3;n++) {submit(USER,EMP,old.getId(),"Attempt "+n);as(LEAD,()->tasks.requestChanges(LEAD,old.getId(),"Another revision"));}
        value(summary(HR,today(),today()),"WORK12",1);
        as(LEAD,()->handovers.handover(LEAD,old.getId(),new WorkTaskHandoverService.Change(handovers.get(LEAD,old.getId()).taskVersion(),NEW_EMP,"Current assignment updated",null)));
        value(summary(HR,today(),today()),"WORK12",1);assertThat(recordIds(HR,"WORK12",today(),today())).containsExactly(old.getId());
        submit(NEW_USER,NEW_EMP,old.getId(),"Final incoming version");accept(old.getId());close(old.getId());value(summary(HR,today(),today()),"WORK12",0);
    }

    @Test void closedReworkRateUsesFinalCohortAndKeepsCurrentReworkBacklogSeparate() {
        var clean=create(EMP,"Final without rework",today());submit(USER,EMP,clean.getId(),"Clean delivery");accept(clean.getId());close(clean.getId());
        var revised=create(NEW_EMP,"Final after rework",today());submit(NEW_USER,NEW_EMP,revised.getId(),"Initial delivery");
        as(LEAD,()->tasks.requestChanges(LEAD,revised.getId(),"Correction required"));submit(NEW_USER,NEW_EMP,revised.getId(),"Corrected delivery");accept(revised.getId());close(revised.getId());
        var open=create(EMP,"Still in rework",today());submit(USER,EMP,open.getId(),"Open correction");as(LEAD,()->tasks.requestChanges(LEAD,open.getId(),"Still needs correction"));
        JsonNode result=summary(HR,today(),today());ratio(result,"WORK09",1,2);value(result,"WORK13",2);value(result,"WORK01",1);
        assertThat(recordIds(HR,"WORK09",today(),today())).containsExactlyInAnyOrder(clean.getId(),revised.getId());
        assertThat(card(result,"WORK09").path("definition").asText()).containsIgnoringCase("rework");
    }

    @Test void historicalUnknownCommitmentsAndMissingStageFactsStayUnknownWithVisibleCoverage() {
        var legacy=create(EMP,"Unknown historic commitment",today());
        // Represent a pre-instrumentation database, without fabricating facts from mutable fields.
        jdbc.execute("truncate work_original_commitment");
        jdbc.update("update department_work_task set submission_version=null,status='COMPLETED' where id=?",legacy.getId());
        JsonNode result=summary(HR,today(),today());JsonNode c=card(result,"WORK07");
        assertThat(c.path("coverageKnown").asLong()).isZero();assertThat(c.path("coverageTotal").asLong()).isEqualTo(1);
        assertThat(c.path("excluded").asLong()).isGreaterThanOrEqualTo(1);assertThat(c.path("smallSample").asBoolean()).isTrue();
        assertThat(c.path("value").isNull()).isTrue();assertThat(c.path("reason").asText()).isNotBlank();
        assertThat(recordIds(HR,"WORK07",today(),today())).isEmpty();
        assertThat(planning.get(USER,legacy.getId()).submissions()).isEmpty();
    }

    @Test void officePeriodAndDepartmentFiltersReconcileDrillsAndFormulaSafeCsvAtOneMetricVersion() {
        var dangerous=create(EMP,"=HYPERLINK(\"https://example.invalid\")",today());
        var tomorrow=create(NEW_EMP,"Tomorrow cohort",today().plusDays(1));
        var foreign=as(OTHER_LEAD,()->tasks.create(OTHER_LEAD,new DepartmentWorkTaskService.CreateCommand(OTHER_EMP,"Foreign private title","Foreign private instructions",today())));
        JsonNode current=summary(HR,today(),today());assertThat(recordIds(HR,"WORK07",today(),today())).containsExactly(dangerous.getId());
        assertThat(recordIds(HR,"WORK07",today().plusDays(1),today().plusDays(1))).containsExactly(tomorrow.getId());
        JsonNode page=json(analytics.records(HR,"WORK07",today(),today(),DEPT,0,1));
        assertThat(page.path("metricVersion").asText()).isEqualTo(current.path("metricVersion").asText());assertThat(page.path("totalElements").asLong()).isEqualTo(1);
        String csv=csv(HR,"WORK07",today(),today(),DEPT,current.path("metricVersion").asText());
        assertThat(csv).contains(dangerous.getId().toString()).doesNotContain(tomorrow.getId().toString(),foreign.getId().toString(),"Foreign private title");
        assertThat(csv).contains("'=HYPERLINK");
        assertThatThrownBy(()->analytics.exportCsv(HR,"WORK07",today(),today(),DEPT,"stale-definition-version")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->analytics.summary(HR,today(),today(),OTHER_DEPT)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->analytics.records(HR,"WORK07",today(),today(),OTHER_DEPT,0,20)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->analytics.exportCsv(HR,"WORK07",today(),today(),OTHER_DEPT,current.path("metricVersion").asText())).isInstanceOf(BusinessException.class);
        JsonNode ceo=json(analytics.summary(CEO,today(),today(),null));assertThat(card(ceo,"WORK07").path("denominator").asLong()).isEqualTo(2);
        assertThat(recordIds(CEO,"WORK07",today(),today())).containsExactlyInAnyOrder(dangerous.getId(),foreign.getId());
        JsonNode scoped=json(analytics.summary(CEO,today(),today(),DEPT));assertThat(card(scoped,"WORK07").path("denominator").asLong()).isEqualTo(1);
        assertThat(scoped.path("departmentTrends").size()).isGreaterThan(0);
    }

    @ParameterizedTest(name="live analytics access: {0}")
    @ValueSource(strings={"read-permission","disabled","terminated","assignment","department"})
    void revokedAccessCannotReuseSummaryRecordOrExportScope(String change) {
        create(EMP,"Previously authorized analytics",today());JsonNode before=summary(HR,today(),today());
        switch(change) {
            case "read-permission" -> jdbc.update("insert into iam_user_permission_deny(user_id,permission_name) values(?,'WORK_TASK_READ')",HR);
            case "disabled" -> jdbc.update("update iam_user_account set enabled=false where id=?",HR);
            case "terminated" -> jdbc.update("update employee set status='TERMINATED' where id=?",HR_EMP);
            case "assignment" -> jdbc.update("update department_hr_assignment set active=false,ended_at=now(),ended_by_user_id=? where hr_user_id=?",ADMIN,HR);
            case "department" -> jdbc.update("update org_department set active=false where id=?",DEPT);
            default -> throw new IllegalArgumentException(change);
        }
        assertThatThrownBy(()->analytics.summary(HR,today(),today(),null)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->analytics.records(HR,"WORK07",today(),today(),null,0,20)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->analytics.exportCsv(HR,"WORK07",today(),today(),null,before.path("metricVersion").asText())).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->analytics.workload(HR,null)).isInstanceOf(BusinessException.class);
    }

    @Test void malformedRangesMetricsPagesAndWorkloadRoleEscalationFailHonestly() {
        assertThatThrownBy(()->analytics.summary(HR,today().plusDays(1),today(),null)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->analytics.summary(HR,today().minusDays(366),today(),null)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->analytics.records(HR,"WORK99",today(),today(),null,0,20)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->analytics.records(HR,"WORK07",today(),today(),null,-1,20)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->analytics.records(HR,"WORK07",today(),today(),null,0,1000)).isInstanceOf(BusinessException.class);
        for(UUID actor:List.of(USER,MANAGER,CEO,ADMIN))assertThatThrownBy(()->analytics.workload(actor,null)).isInstanceOf(BusinessException.class);
    }


    @Test void reviewDistributionsUseActualStageIntervalsContinuousPercentilesAndDiscloseNegativeFacts() {
        var first=create(EMP,"Controlled stage history one",today());var second=create(NEW_EMP,"Controlled stage history two",today());
        LocalDate period=today().minusDays(1);Instant origin=period.atTime(10,0).atZone(OFFICE).toInstant();
        jdbc.update("update department_work_task set created_at=? where id in (?,?)",Timestamp.from(origin.minusSeconds(3600)),first.getId(),second.getId());
        stageHistory(first.getId(),origin,new long[]{60,120,30,0});
        stageHistory(second.getId(),origin.plusSeconds(3600),new long[]{180,360,90,60});
        var invalid=create(EMP,"Negative stage interval",today());
        jdbc.update("update department_work_task set created_at=? where id=?",Timestamp.from(origin.minusSeconds(3600)),invalid.getId());
        stageEvent(invalid.getId(),"TEAM_LEAD",null,origin.plusSeconds(7200));
        stageEvent(invalid.getId(),null,"TEAM_LEAD",origin.plusSeconds(7140));
        JsonNode result=summary(HR,period,period),leadStage=stage(result,"TEAM_LEAD"),hrStage=stage(result,"HR_ADMIN");
        assertThat(leadStage.path("sampleCount").asLong()).isEqualTo(2);
        assertThat(leadStage.path("medianSeconds").asDouble()).isEqualTo(120);
        assertThat(leadStage.path("p95Seconds").asDouble()).isCloseTo(174,within(0.00001));
        assertThat(leadStage.path("coverageKnown").asLong()).isEqualTo(2);
        assertThat(leadStage.path("coverageTotal").asLong()).isEqualTo(3);
        assertThat(hrStage.path("medianSeconds").asDouble()).isEqualTo(240);
        assertThat(hrStage.path("p95Seconds").asDouble()).isCloseTo(348,within(0.00001));
        assertThat(stage(result,"MANAGER").path("medianSeconds").asDouble()).isEqualTo(60);
        assertThat(stage(result,"MANAGER").path("p95Seconds").asDouble()).isCloseTo(87,within(0.00001));
        assertThat(stage(result,"CEO").path("medianSeconds").asDouble()).isEqualTo(30);
        assertThat(stage(result,"CEO").path("p95Seconds").asDouble()).isCloseTo(57,within(0.00001));
        JsonNode card=card(result,"WORK08");assertThat(card.path("sampleCount").asLong()).isEqualTo(8);
        assertThat(card.path("excluded").asLong()).isEqualTo(1);
        assertThat(card.path("value").asDouble()).isCloseTo(297,within(0.00001));
        var records=json(analytics.records(HR,"WORK08",period,period,null,0,50));
        assertThat(records.path("totalElements").asLong()).isEqualTo(9);assertThat(records.path("items").size()).isEqualTo(9);
        assertThat(csv(HR,"WORK08",period,period,null,"sprint9.v1")).contains(first.getId().toString(),second.getId().toString(),invalid.getId().toString());
        assertThatThrownBy(()->jdbc.update("update work_review_stage_event set occurred_at=occurred_at+interval '1 second' where work_task_id=?",first.getId())).hasMessageContaining("immutable");
        assertThatThrownBy(()->jdbc.update("delete from work_review_stage_event where work_task_id=?",first.getId())).hasMessageContaining("immutable");
    }

    @Test void handoverClosesTheOldQueueAdministrativelyAndNewSubmissionStartsARealEntry() {
        var task=create(EMP,"Administrative queue handover",today());
        submit(USER,EMP,task.getId(),"First authored delivery");accept(task.getId());
        as(HR,()->insights.markAudited(HR,task.getId()));
        assertThat(jdbc.queryForObject("select work_current_review_stage(?)",String.class,task.getId())).isEqualTo("MANAGER");
        as(LEAD,()->handovers.handover(LEAD,task.getId(),new WorkTaskHandoverService.Change(
                handovers.get(LEAD,task.getId()).taskVersion(),NEW_EMP,"Transfer pending review",null)));
        assertThat(jdbc.queryForObject("select work_current_review_stage(?)",String.class,task.getId())).isNull();
        assertThat(count("select count(*) from work_review_stage_event where work_task_id=? and previous_stage='MANAGER'",task.getId())).isZero();
        assertThat(jdbc.queryForObject("select rework_cycle from department_work_task where id=?",Integer.class,task.getId())).isZero();
        submit(NEW_USER,NEW_EMP,task.getId(),"New accountable delivery");
        assertThat(jdbc.queryForObject("select work_current_review_stage(?)",String.class,task.getId())).isEqualTo("TEAM_LEAD");
        assertThat(count("select count(*) from work_review_stage_event e join department_work_task t on t.id=e.work_task_id where t.id=? and e.stage='TEAM_LEAD' and e.assignment_revision=1 and e.submission_version=t.submission_version",task.getId())).isEqualTo(1);
    }

    @Test void returnedAndResubmittedDeliveryHasSeparateCurrentReviewEntries() {
        var task=create(EMP,"Actual rework queue entries",today());
        submit(USER,EMP,task.getId(),"Initial delivery");accept(task.getId());
        as(HR,()->insights.requestHrRework(HR,task.getId(),"Revise the delivery"));
        assertThat(jdbc.queryForObject("select work_current_review_stage(?)",String.class,task.getId())).isEqualTo("TEAM_LEAD");
        assertThat(count("select count(*) from work_review_stage_event where work_task_id=? and previous_stage='HR_ADMIN'",task.getId())).isEqualTo(1);
        as(LEAD,()->insights.assignRework(LEAD,task.getId(),"Apply the HR correction"));
        assertThat(jdbc.queryForObject("select work_current_review_stage(?)",String.class,task.getId())).isNull();
        submit(USER,EMP,task.getId(),"Corrected delivery");
        assertThat(jdbc.queryForObject("select work_current_review_stage(?)",String.class,task.getId())).isEqualTo("TEAM_LEAD");
        assertThat(count("select count(*) from work_review_stage_event e join department_work_task t on t.id=e.work_task_id where t.id=? and e.stage='TEAM_LEAD' and e.submission_version=t.submission_version",task.getId())).isEqualTo(1);
        accept(task.getId());
        assertThat(jdbc.queryForObject("select work_current_review_stage(?)",String.class,task.getId())).isEqualTo("HR_ADMIN");
        as(HR,()->insights.markAudited(HR,task.getId()));
        assertThat(jdbc.queryForObject("select work_current_review_stage(?)",String.class,task.getId())).isEqualTo("MANAGER");
    }

    @Test void unresolvedReviewAgeUsesKnownCurrentEntryAndDoesNotInventMissingLegacyEntry() {
        var task=create(EMP,"Current reviewer age",today());submit(USER,EMP,task.getId(),"Pending actual review");
        Instant entry=Objects.requireNonNull(jdbc.queryForObject("select occurred_at from work_review_stage_event where work_task_id=? and stage='TEAM_LEAD' order by id desc limit 1",Timestamp.class,task.getId())).toInstant();
        Instant asOf=entry.plusSeconds(120);
        var fixed=new WorkAnalyticsService(named,authority,OFFICE,Clock.fixed(asOf,ZoneOffset.UTC));
        LocalDate from=entry.atZone(OFFICE).toLocalDate(),to=asOf.atZone(OFFICE).toLocalDate();
        JsonNode known=json(fixed.summary(HR,from,to,null)),knownStage=stage(known,"TEAM_LEAD");
        assertThat(knownStage.path("sampleCount").asLong()).isZero();assertThat(knownStage.path("unresolved").asLong()).isEqualTo(1);
        assertThat(knownStage.path("oldestUnresolvedSeconds").asDouble()).isCloseTo(120,within(0.00001));
        assertThat(knownStage.path("medianSeconds").isNull()).isTrue();assertThat(knownStage.path("p95Seconds").isNull()).isTrue();
        assertThat(knownStage.path("coverageKnown").asLong()).isEqualTo(1);
        jdbc.execute("truncate work_review_stage_event");
        JsonNode unknown=json(fixed.summary(HR,from,to,null)),unknownStage=stage(unknown,"TEAM_LEAD");
        assertThat(unknownStage.path("unresolved").asLong()).isEqualTo(1);assertThat(unknownStage.path("oldestUnresolvedSeconds").isNull()).isTrue();
        assertThat(unknownStage.path("coverageKnown").asLong()).isZero();assertThat(unknownStage.path("coverageTotal").asLong()).isEqualTo(1);
        assertThat(card(unknown,"WORK08").path("excluded").asLong()).isEqualTo(1);
        assertThat(recordIds(HR,"WORK08",from,to)).containsExactly(task.getId());
    }

    @Test void resolvedBlockerPercentileExcludesNegativeIntervalsWhileOpenStockRemainsIndependent() throws Exception {
        var task=create(EMP,"Blocker distributions",today());var initial=planning.get(USER,task.getId());
        var raised=as(USER,()->planning.raise(USER,task.getId(),new TaskPlanningService.Raise(initial.taskVersion(),"Actual dependency",LEAD)));
        as(LEAD,()->planning.resolve(LEAD,task.getId(),raised.blockers().getFirst().id(),new TaskPlanningService.Resolve(raised.taskVersion(),"Actual dependency received")));
        LocalDate period=today().minusDays(1);Instant base=period.atTime(11,0).atZone(OFFICE).toInstant();
        jdbc.update("update department_work_task set created_at=? where id=?",Timestamp.from(base.minusSeconds(3600)),task.getId());
        var samples=List.of(new com.brainserve.appointment.worktask.domain.TaskPlanningState.Blocker(UUID.randomUUID(),"Sixty seconds",LEAD,base,base.plusSeconds(60),USER,LEAD,"Resolved"),
                new com.brainserve.appointment.worktask.domain.TaskPlanningState.Blocker(UUID.randomUUID(),"Three minutes",LEAD,base.plusSeconds(120),base.plusSeconds(300),USER,LEAD,"Resolved"),
                new com.brainserve.appointment.worktask.domain.TaskPlanningState.Blocker(UUID.randomUUID(),"Invalid negative history",LEAD,base.plusSeconds(600),base.plusSeconds(540),USER,LEAD,"Invalid source"),
                new com.brainserve.appointment.worktask.domain.TaskPlanningState.Blocker(UUID.randomUUID(),"Still waiting",LEAD,base.plusSeconds(720),null,USER,null,null));
        jdbc.update("update department_work_task set planning_state=jsonb_set(planning_state,'{blockers}',?::jsonb),blocked=true where id=?",mapper.writeValueAsString(samples),task.getId());
        JsonNode result=summary(HR,period,period),c=card(result,"WORK10");
        assertThat(c.path("sampleCount").asLong()).isEqualTo(2);assertThat(c.path("coverageKnown").asLong()).isEqualTo(2);
        assertThat(c.path("coverageTotal").asLong()).isEqualTo(3);assertThat(c.path("excluded").asLong()).isEqualTo(1);
        assertThat(c.path("value").asDouble()).isCloseTo(174,within(0.00001));value(result,"WORK04",1);
        assertThat(recordIds(HR,"WORK10",period,period)).hasSize(3).containsOnly(task.getId());
        assertThat(recordIds(HR,"WORK04",period,period)).containsExactly(task.getId());
        assertThat(csv(HR,"WORK10",period,period,null,"sprint9.v1")).contains("Invalid negative history");
    }

    @Test void repeatedCeoEventDeliveryCannotInflateDistinctTaskAndAuditCycleThroughput() throws Exception {
        var task=create(EMP,"One final audit cycle",today());submit(USER,EMP,task.getId(),"Final evidence");accept(task.getId());close(task.getId());
        value(summary(HR,today(),today()),"WORK13",1);
        var row=jdbc.queryForMap("select target_id,occurred_at,details_json::text details from audit_event_history where event_type='WORK_INSIGHT_CEO_APPROVED' and details_json->>'workTaskId'=? order by occurred_at limit 1",task.getId().toString());
        for(int n=0;n<3;n++)jdbc.update("insert into audit_event(id,occurred_at,actor_id,event_type,target_type,target_id,outcome,correlation_id,details_json) values(?,?,?,'WORK_INSIGHT_CEO_APPROVED','WORK_TASK_AUDIT',?,'SUCCESS','repeated-event-test',?::jsonb)",
                UUID.randomUUID(),row.get("occurred_at"),CEO.toString(),row.get("target_id"),row.get("details"));
        value(summary(HR,today(),today()),"WORK13",1);
        assertThat(recordIds(HR,"WORK13",today(),today())).containsExactly(task.getId());
        assertThat(csv(HR,"WORK13",today(),today(),null,"sprint9.v1").lines().filter(line->line.contains(task.getId().toString())).count()).isEqualTo(1);
        value(summary(HR,today().minusDays(1),today().minusDays(1)),"WORK13",0);
    }


    @Test void oversizedExportsRequireNarrowerFiltersAndNeverSilentlyTruncateDisplayedCohort() {
        jdbc.update("""
          insert into department_work_task(id,department_id,employee_id,team_lead_user_id,assigned_by_user_id,assigned_by_role,assignee_role,
             title,description,department_branch,due_date,status,version,created_at,created_by,updated_at,updated_by)
          select gen_random_uuid(),?,?,?,?,'TEAM_LEAD','EMPLOYEE','Export limit row '||n,'Bounded export fixture','S9A_MAIN',?,'ASSIGNED',0,now(),'sprint9a-test',now(),'sprint9a-test'
          from generate_series(1,5001) n
          """,DEPT,EMP,LEAD,LEAD,java.sql.Date.valueOf(today()));
        JsonNode page=json(analytics.records(HR,"WORK01",today(),today(),null,0,20));
        assertThat(page.path("totalElements").asLong()).isEqualTo(5001);assertThat(page.path("items").size()).isEqualTo(20);
        assertThatThrownBy(()->analytics.exportCsv(HR,"WORK01",today(),today(),null,"sprint9.v1")).isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo("WORK_ANALYTICS_EXPORT_TOO_LARGE");
    }

    private JsonNode stage(JsonNode summary,String name){for(JsonNode row:summary.path("stages"))if(row.path("stage").asText().equals(name))return row;throw new AssertionError("Missing stage "+name);}
    private void stageHistory(UUID task,Instant entry,long[] durations){String[] names={"TEAM_LEAD","HR_ADMIN","MANAGER","CEO"};stageEvent(task,names[0],null,entry);Instant cursor=entry;for(int n=0;n<names.length;n++){cursor=cursor.plusSeconds(durations[n]);stageEvent(task,n+1<names.length?names[n+1]:null,names[n],cursor);}}
    private void stageEvent(UUID task,String stage,String previous,Instant at){jdbc.update("insert into work_review_stage_event(work_task_id,stage,previous_stage,occurred_at,assignment_revision,submission_version,audit_cycle,source) values(?,?,?,?,0,null,0,'TASK')",task,stage,previous,Timestamp.from(at));}
    private DepartmentWorkTask create(UUID employee,String title,LocalDate due){return as(LEAD,()->tasks.create(LEAD,new DepartmentWorkTaskService.CreateCommand(employee,title,"Full retained analytics instructions",due)));}
    private void submit(UUID actor,UUID employee,UUID task,String note){as(actor,()->tasks.complete(actor,employee,task,note));}
    private void accept(UUID task){as(LEAD,()->tasks.approve(LEAD,task,"Evidence acceptance"));}
    private void close(UUID task){var audit=as(HR,()->insights.markAudited(HR,task));as(MANAGER,()->insights.decideByManager(MANAGER,audit.auditRecordId(),true,"Manager decision"));as(CEO,()->insights.decideByCeo(CEO,audit.auditRecordId(),true,"CEO decision"));}
    private JsonNode json(Object value){return mapper.valueToTree(value);}
    private JsonNode summary(UUID actor,LocalDate from,LocalDate to){return json(analytics.summary(actor,from,to,null));}
    private JsonNode card(JsonNode summary,String id){for(JsonNode c:summary.path("cards"))if(c.path("id").asText().equals(id))return c;throw new AssertionError("Missing metric "+id);}
    private void value(JsonNode summary,String id,long expected){JsonNode c=card(summary,id);assertThat(c.path("value").isNumber()).as(id+" known count").isTrue();assertThat(c.path("value").asDouble()).as(id+" value").isEqualTo((double)expected);}
    private void ratio(JsonNode summary,String id,long numerator,long denominator){JsonNode c=card(summary,id);assertThat(c.path("numerator").isNumber()).as(id+" numerator present").isTrue();assertThat(c.path("denominator").isNumber()).as(id+" denominator present").isTrue();assertThat(c.path("numerator").asLong()).as(id+" numerator").isEqualTo(numerator);assertThat(c.path("denominator").asLong()).as(id+" denominator").isEqualTo(denominator);}
    private List<UUID> recordIds(UUID actor,String metric,LocalDate from,LocalDate to){return ids(json(analytics.records(actor,metric,from,to,null,0,50)).path("items"));}
    private List<UUID> ids(JsonNode array){assertThat(array.isArray()).isTrue();List<UUID> result=new ArrayList<>();for(JsonNode row:array)result.add(UUID.fromString(row.path("id").asText()));return result;}
    private JsonNode member(JsonNode workload,UUID employee){for(JsonNode row:workload.path("members"))if(row.path("employeeId").asText().equals(employee.toString()))return row;throw new AssertionError("Missing workload member "+employee);}
    private String csv(UUID actor,String metric,LocalDate from,LocalDate to,UUID department,String version){Object raw=analytics.exportCsv(actor,metric,from,to,department,version);return raw instanceof byte[] bytes?new String(bytes,java.nio.charset.StandardCharsets.UTF_8):Objects.toString(raw);}
    private void leave(UUID employee,UUID user,String status){jdbc.update("insert into employee_leave_request(id,employee_id,requester_user_id,start_date,end_date,reason,status,decided_by_user_id,decided_at,version,created_at,created_by,updated_at,updated_by) values(?,?,?,?,?,'Maintained leave',?,?,now(),0,now(),'sprint9a-test',now(),'sprint9a-test')",UUID.randomUUID(),employee,user,java.sql.Date.valueOf(today()),java.sql.Date.valueOf(today()),status,status.equals("APPROVED")?HR:null);}
    private void department(UUID id,String code){jdbc.update("insert into org_department(id,code,name,active,version,created_at,created_by,updated_at,updated_by) values(?,?,?,true,0,now(),'sprint9a-test',now(),'sprint9a-test')",id,code,code);}
    private void employee(UUID id,UUID department,String name){jdbc.update("insert into employee(id,employee_number,first_name,last_name,display_name,official_email,department_id,designation,joining_date,status,version,created_at,created_by,updated_at,updated_by) values(?,?,?,?,?,?,?,'Test engineer','2026-01-01','ACTIVE',0,now(),'sprint9a-test',now(),'sprint9a-test')",id,"S9A-"+id.toString().substring(24),name,"Person",name+" Person",name+"@sprint9a.test",department);}
    private void account(UUID id,UUID employee,String name,String role){jdbc.update("insert into iam_user_account(id,email,full_name,employee_id,password_hash,enabled,force_password_change,account_status,archived,version,created_at,created_by,updated_at,updated_by) values(?,?,?,?,'test-only-hash',true,false,'ACTIVE',false,0,now(),'sprint9a-test',now(),'sprint9a-test')",id,name+"@sprint9a.test",name,employee);jdbc.update("insert into iam_user_role(user_id,role_name) values(?,?)",id,role);}
    private void assignment(String table,String prefix,UUID department,UUID user,UUID employee){jdbc.update("insert into "+table+"(id,department_id,"+prefix+"_user_id,"+prefix+"_employee_id,active,assigned_by_user_id,assigned_at,version,created_at,created_by,updated_at,updated_by) values(?,?,?,?,true,?,now(),0,now(),'sprint9a-test',now(),'sprint9a-test')",UUID.randomUUID(),department,user,employee,ADMIN);}
    private <T>T as(UUID actor,Supplier<T> action){var old=SecurityContextHolder.getContext();var next=SecurityContextHolder.createEmptyContext();next.setAuthentication(new UsernamePasswordAuthenticationToken(actor.toString(),"",List.of()));SecurityContextHolder.setContext(next);try{T result=action.get();drain();return result;}finally{SecurityContextHolder.setContext(old);}}
    private LocalDate today(){return LocalDate.now(OFFICE);}private static UUID id(int n){return UUID.fromString(String.format(Locale.ROOT,"99200000-0000-0000-0000-%012d",n));}
    private long count(String sql,Object...args){return Objects.requireNonNull(jdbc.queryForObject(sql,Long.class,args));}
    private LocalDate date(String sql,Object...args){return Objects.requireNonNull(jdbc.queryForObject(sql,java.sql.Date.class,args)).toLocalDate();}
    private void drain(){if(!(notificationExecutor instanceof ThreadPoolTaskExecutor executor))return;long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);while(executor.getActiveCount()>0||!executor.getThreadPoolExecutor().getQueue().isEmpty()){if(System.nanoTime()>deadline)throw new AssertionError("Notification writes did not settle");try{Thread.sleep(20);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}}}
}
