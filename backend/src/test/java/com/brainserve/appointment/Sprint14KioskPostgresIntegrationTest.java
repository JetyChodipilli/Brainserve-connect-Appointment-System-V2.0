package com.brainserve.appointment;

import com.brainserve.appointment.document.infrastructure.ClamAvScanner;
import com.brainserve.appointment.iam.application.JwtService;
import com.brainserve.appointment.iam.infrastructure.UserAccountRepository;
import com.brainserve.appointment.integration.api.IntegrationModels;
import com.brainserve.appointment.integration.application.IntegrationService;
import com.brainserve.appointment.integration.slack.*;
import com.brainserve.appointment.shared.application.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import com.brainserve.appointment.shared.application.SensitiveStringConverter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionOperations;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.services.s3.S3Client;
import com.brainserve.appointment.appointment.application.*;
import com.brainserve.appointment.appointment.api.GroupVisitController;
import com.brainserve.appointment.appointment.domain.AppointmentType;
import com.brainserve.appointment.kiosk.application.KioskService;
import com.brainserve.appointment.reception.application.VisitorBadgeService;
import java.time.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real PostgreSQL and Redis: no simulated appointment or device persistence. */
@Testcontainers(disabledWithoutDocker=true)
@AutoConfigureMockMvc
@SpringBootTest(properties={
    "brainserve.security.jwt-secret=test-only-secret-key-that-is-at-least-thirty-two-bytes",
    "brainserve.security.pii-encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
    "brainserve.kiosk.enabled=true","brainserve.frontend.public-url=https://brainserve.test",
    "brainserve.appointment.qr-signing-secret=test-only-qr-signing-key-with-at-least-thirty-two-bytes",
    "brainserve.bootstrap.system-admin-enabled=false","brainserve.bootstrap.ceo-enabled=false",
    "brainserve.work-routines.enabled=false","brainserve.integrations.enabled=false",
    "brainserve.integrations.slack.app-base-url=https://brainserve.test",
    "brainserve.approval-reminders.poll-ms=3600000","spring.kafka.listener.auto-startup=false",
    "brainserve.notification.internal-call-dispatch-ms=3600000","brainserve.notification.poll-ms=3600000",
    "aws.s3.access-key=test-access-key","aws.s3.secret-key=test-secret-key"
})
class Sprint14KioskPostgresIntegrationTest {
 @Container static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>("postgres:17.2-alpine");
 @Container static final GenericContainer<?> REDIS=new GenericContainer<>("redis:7.4.1-alpine").withExposedPorts(6379);
 @DynamicPropertySource static void infrastructure(DynamicPropertyRegistry p) {
  p.add("spring.datasource.url",POSTGRES::getJdbcUrl);p.add("spring.datasource.username",POSTGRES::getUsername);p.add("spring.datasource.password",POSTGRES::getPassword);
  p.add("spring.data.redis.host",REDIS::getHost);p.add("spring.data.redis.port",()->REDIS.getMappedPort(6379));
 }
 @Autowired JdbcTemplate jdbc; @Autowired TransactionOperations transactions; @Autowired ObjectMapper json; @Autowired MockMvc mvc; @Autowired StringRedisTemplate redis;
 @Autowired GroupVisitService groups; @Autowired KioskService kiosks; @Autowired VisitorPassService passes; @Autowired VisitorBadgeService badges;
 @MockitoBean S3Client s3; @MockitoBean ClamAvScanner scanner;
 static final UUID ADMIN=id(1),OTHER=id(2),RECEPTION=id(3),SECURITY=id(4),HR=id(5),EMPLOYEE=id(6),HOST=id(7),DEPT=id(8);
 @BeforeEach void fixture() {
  try(var c=redis.getConnectionFactory().getConnection()){c.serverCommands().flushDb();}
  jdbc.execute("truncate integration_connection,integration_resource_revision,integration_business_event,appointment_visit_group,kiosk_device,kiosk_arrival_intake,appointment,iam_user_account,employee,org_department,audit_event cascade");
  jdbc.update("insert into org_department(id,code,name,active,version,created_at,created_by,updated_at,updated_by) values(?,'S14','Reception',true,0,now(),'s14',now(),'s14')",DEPT);
  jdbc.update("insert into employee(id,employee_number,first_name,last_name,display_name,official_email,department_id,designation,joining_date,status,version,created_at,created_by,updated_at,updated_by) values(?,'S14-HR','HR','Person','HR Person','hr@s14.test',?,'HR','2026-01-01','ACTIVE',0,now(),'s14',now(),'s14')",HOST,DEPT);
  account(ADMIN,null,"ROLE_SYSTEM_ADMIN");account(OTHER,null,"ROLE_SYSTEM_ADMIN");account(RECEPTION,null,"ROLE_RECEPTIONIST");account(SECURITY,null,"ROLE_SECURITY");account(HR,HOST,"ROLE_HR_ADMIN");account(EMPLOYEE,null,"ROLE_EMPLOYEE");
  jdbc.update("insert into department_hr_assignment(id,department_id,hr_user_id,hr_employee_id,active,assigned_by_user_id,assigned_at,version,created_at,created_by,updated_at,updated_by) values(?,?,?,?,true,?,now(),0,now(),'s14',now(),'s14')",UUID.randomUUID(),DEPT,HR,HOST,ADMIN);
 }
 @Test void groupSharesOnlyItsReservationAndKeepsSeparateApprovals() {
  var g=groups.create(RECEPTION,request(UUID.randomUUID(),"Group"));assertThat(g.members()).hasSize(2);
  assertThat(g.members()).extracting(GroupVisitService.Member::status).containsOnly("PENDING_SECURITY_INTAKE");
  assertThat(g.members()).extracting(GroupVisitService.Member::referenceNumber).doesNotHaveDuplicates();
  code(()->groups.create(RECEPTION,request(UUID.randomUUID(),"Other")),"SLOT_ALREADY_BOOKED");assertThat(count("appointment")).isEqualTo(2);assertThat(count("appointment_visit_group")).isEqualTo(1);
 }
 @Test void exactReplayIsIdempotentAndDifferentPayloadConflicts() {
  UUID id=UUID.randomUUID();var r=request(id,"One");var g=groups.create(RECEPTION,r);assertThat(groups.create(RECEPTION,r).id()).isEqualTo(g.id());
  code(()->groups.create(RECEPTION,request(id,"Different")),"GROUP_REQUEST_CONFLICT");assertThatThrownBy(()->groups.list(OTHER)).isInstanceOf(BusinessException.class);
 }
 @Test void concurrentExactReplayCreatesOneGroup() throws Exception {
  var r=request(UUID.randomUUID(),"Concurrent");var executor=Executors.newFixedThreadPool(2);
  try{var a=executor.submit(()->groups.create(RECEPTION,r));var b=executor.submit(()->groups.create(RECEPTION,r));assertThat(a.get(15,TimeUnit.SECONDS).id()).isEqualTo(b.get(15,TimeUnit.SECONDS).id());}finally{executor.shutdownNow();}assertThat(count("appointment")).isEqualTo(2);
 }
 @Test void secondMemberFailureRollsBackEveryAppointmentAndEvent() {
  jdbc.execute("alter table appointment add constraint s14_reject_second check(visitor_email <> 'second@s14.test')");
  try{assertThatThrownBy(()->groups.create(RECEPTION,request(UUID.randomUUID(),"Rollback"))).isInstanceOf(BusinessException.class);assertThat(count("appointment")).isZero();assertThat(count("appointment_visit_group")).isZero();assertThat(count("integration_business_event")).isZero();}finally{jdbc.execute("alter table appointment drop constraint s14_reject_second");}
 }
 @Test void duplicateVisitorsWrongRoleAndInactiveReceptionReject() {
  var r=request(UUID.randomUUID(),"Duplicate");var duplicate=new GroupVisitController.Request(r.requestId(),r.label(),r.type(),r.hostEmployeeId(),r.routingDepartmentId(),null,r.slotStart(),r.slotEnd(),r.purpose(),List.of(r.members().getFirst(),r.members().getFirst()));
  code(()->groups.create(RECEPTION,duplicate),"DUPLICATE_GROUP_VISITOR");assertThatThrownBy(()->groups.create(EMPLOYEE,r)).isInstanceOf(BusinessException.class);
  jdbc.update("update iam_user_account set enabled=false where id=?",RECEPTION);assertThatThrownBy(()->groups.create(RECEPTION,r)).isInstanceOf(BusinessException.class);assertThat(count("appointment")).isZero();
 }
 @Test void databaseGuardsGroupMembershipAndCancelReleasesTheSlot() {
  var g=groups.create(RECEPTION,request(UUID.randomUUID(),"Frozen"));assertThatThrownBy(()->jdbc.update("update appointment set slot_start=slot_start+interval '1 day',slot_end=slot_end+interval '1 day' where id=?",g.members().getFirst().appointmentId())).isInstanceOf(org.springframework.dao.DataAccessException.class);
  jdbc.update("update appointment set status='CANCELLED' where visit_group_id=?",g.id());assertThat(groups.create(RECEPTION,request(UUID.randomUUID(),"New")).members()).hasSize(2);
 }
 @Test void devicesAreHashedOwnerScopedVersionedAndRevocable() {
  var d=kiosks.provision(ADMIN,"Gate");assertThat(d.token()).hasSize(43);assertThat(jdbc.queryForObject("select token_hash from kiosk_device where id=?",String.class,d.id())).hasSize(64).doesNotContain(d.token());assertThat(Duration.between(Instant.now(),d.expiresAt()).toHours()).isBetween(7L,8L);
  assertThat(kiosks.devices(OTHER)).isEmpty();assertThat(kiosks.session(d.token()).resetSeconds()).isEqualTo(60);assertThatThrownBy(()->kiosks.revoke(OTHER,d.id(),0)).isInstanceOf(BusinessException.class);assertThatThrownBy(()->kiosks.revoke(ADMIN,d.id(),1)).isInstanceOf(BusinessException.class);
  kiosks.revoke(ADMIN,d.id(),0);code(()->kiosks.session(d.token()),"KIOSK_SESSION_UNAVAILABLE");
 }
 @Test void expiryAndOwnerPermissionLossInvalidateDeviceAccess() {
  var first=kiosks.provision(ADMIN,"Expired");jdbc.update("update kiosk_device set expires_at=now()-interval '1 second' where id=?",first.id());assertThatThrownBy(()->kiosks.session(first.token())).isInstanceOf(BusinessException.class);
  var second=kiosks.provision(ADMIN,"Lost authority");jdbc.update("insert into iam_user_permission_deny(user_id,permission_name) values(?,'SYSTEM_CONFIGURE')",ADMIN);code(()->kiosks.session(second.token()),"KIOSK_SESSION_UNAVAILABLE");
 }
 @Test void duplicateScansQueueOnceWithoutCheckInOrOccupancyChanges() {
  var d=kiosks.provision(ADMIN,"Gate");UUID visit=visit("APPROVED");String token=passes.issue(reference(visit)).token();assertThat(kiosks.intake(d.token(),token).accepted()).isTrue();assertThat(kiosks.intake(d.token(),token).accepted()).isTrue();
  assertThat(count("kiosk_arrival_intake")).isEqualTo(1);assertThat(count("visit_access_record")).isZero();assertThat(jdbc.queryForObject("select status from appointment where id=?",String.class,visit)).isEqualTo("APPROVED");
  var q=kiosks.pending(SECURITY).getFirst();kiosks.resolve(RECEPTION,q.id(),q.version());assertThat(kiosks.pending(SECURITY)).isEmpty();assertThatThrownBy(()->kiosks.resolve(RECEPTION,q.id(),q.version())).isInstanceOf(BusinessException.class);
 }
 @Test void tamperedCancelledExpiredAndCheckedInPassesReject() {
  var d=kiosks.provision(ADMIN,"Gate");UUID visit=visit("APPROVED");String token=passes.issue(reference(visit)).token();assertThatThrownBy(()->kiosks.intake(d.token(),token+"x")).isInstanceOf(BusinessException.class);
  jdbc.update("update appointment set status='CANCELLED' where id=?",visit);assertThatThrownBy(()->kiosks.intake(d.token(),token)).isInstanceOf(BusinessException.class);
  jdbc.update("update appointment set status='CHECKED_IN' where id=?",visit);assertThatThrownBy(()->kiosks.intake(d.token(),token)).isInstanceOf(BusinessException.class);
  jdbc.update("update appointment set status='APPROVED',slot_start=now()-interval '6 hours',slot_end=now()-interval '5 hours' where id=?",visit);assertThatThrownBy(()->kiosks.intake(d.token(),token)).isInstanceOf(BusinessException.class);assertThat(count("kiosk_arrival_intake")).isZero();
 }
 @Test void deviceBudgetIsBoundedAndRedisNeverStoresDeviceSecrets() {
  var d=kiosks.provision(ADMIN,"Budget");for(int i=0;i<60;i++)kiosks.session(d.token());code(()->kiosks.session(d.token()),"KIOSK_RATE_LIMIT");assertThat(redis.keys("kiosk:*")).noneMatch(key->key.contains(d.token()));assertThat(redis.getExpire("kiosk:budget:"+d.id())).isBetween(1L,60L);
 }
 @Test void deviceHttpCannotAccessStaffApisAndReturnsNoVisitorData() throws Exception {
  var d=kiosks.provision(ADMIN,"Gate");UUID visit=visit("APPROVED");String token=passes.issue(reference(visit)).token();mvc.perform(get("/api/v1/reception/visitors-inside").header("X-Kiosk-Token",d.token())).andExpect(status().isUnauthorized());
  mvc.perform(post("/api/v1/kiosk/intake").header("X-Kiosk-Token",d.token()).contentType("application/json").content(json.writeValueAsString(Map.of("token",token)))).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andExpect(content().json("{\"accepted\":true}",true));
 }
 @Test void badgesRequireStaffAndCurrentCheckInAndRecheckCheckout() {
  UUID visit=visit("APPROVED"),record=UUID.randomUUID();assertThatThrownBy(()->passes.badgePass(visit)).isInstanceOf(BusinessException.class);jdbc.update("update appointment set status='CHECKED_IN' where id=?",visit);
  jdbc.update("insert into visit_access_record(id,appointment_id,visitor_name,badge_number,checked_in_at,processed_by,version,created_at,created_by,updated_at,updated_by) values(?,?,'Private Visitor','S14-BADGE',now(),'s14',0,now(),'s14',now(),'s14')",record,visit);
  var b=badges.prepare(SECURITY,record);assertThat(b.visitorName()).isEqualTo("Private Visitor");assertThat(b.qrCodeDataUrl()).startsWith("data:image/png;base64,");assertThat(b.templateVersion()).isEqualTo(1);assertThatThrownBy(()->badges.prepare(EMPLOYEE,record)).isInstanceOf(BusinessException.class);
  jdbc.update("update visit_access_record set checked_out_at=now() where id=?",record);assertThatThrownBy(()->badges.prepare(RECEPTION,record)).isInstanceOf(BusinessException.class);
 }
 @Test void intakeRollsBackWithItsEnclosingTransaction() {
  var d=kiosks.provision(ADMIN,"Gate");UUID visit=visit("APPROVED");String token=passes.issue(reference(visit)).token();transactions.executeWithoutResult(status->{kiosks.intake(d.token(),token);status.setRollbackOnly();});assertThat(count("kiosk_arrival_intake")).isZero();
 }
 private GroupVisitController.Request request(UUID requestId,String label) {
  LocalDate day=LocalDate.now(ZoneId.of("Asia/Kolkata")).plusDays(1);while(day.getDayOfWeek()==DayOfWeek.SATURDAY||day.getDayOfWeek()==DayOfWeek.SUNDAY)day=day.plusDays(1);Instant start=day.atTime(9,30).atZone(ZoneId.of("Asia/Kolkata")).toInstant();
  return new GroupVisitController.Request(requestId,label,AppointmentType.HR_VISIT,HOST,DEPT,null,start,start.plusSeconds(1800),"Group purpose",List.of(new GroupVisitController.Member("First Visitor","first@s14.test","0000000000","Test"),new GroupVisitController.Member("Second Visitor","second@s14.test","0000000001","Test")));
 }
 private UUID visit(String status){UUID id=UUID.randomUUID();jdbc.update("insert into appointment(id,reference_number,idempotency_key,type,status,visitor_name,visitor_email,visitor_phone,host_employee_id,routing_department_id,slot_start,slot_end,purpose,version,created_at,created_by,updated_at,updated_by) values(?,?,?,'HR_VISIT',?,'Private Visitor','private@s14.test','0000000000',?,?,?,?,'Private purpose',0,now(),'s14',now(),'s14')",id,"BSA-S14A-"+id.toString().substring(0,4).toUpperCase(Locale.ROOT),id.toString(),status,HOST,DEPT,Timestamp.from(Instant.now().minusSeconds(300)),Timestamp.from(Instant.now().plusSeconds(1800)));return id;}
 private String reference(UUID id){return jdbc.queryForObject("select reference_number from appointment where id=?",String.class,id);}
 private void account(UUID id,UUID employee,String role){jdbc.update("insert into iam_user_account(id,email,full_name,employee_id,password_hash,enabled,force_password_change,account_status,archived,version,created_at,created_by,updated_at,updated_by) values(?,?,?,?,'test-only-hash',true,false,'ACTIVE',false,0,now(),'s14',now(),'s14')",id,id+"@s14.test",role,employee);jdbc.update("insert into iam_user_role(user_id,role_name) values(?,?)",id,role);}
 private long count(String table){return Objects.requireNonNull(jdbc.queryForObject("select count(*) from "+table,Long.class));}
 private void code(Runnable action,String code){assertThatThrownBy(action::run).isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo(code);}
 private static UUID id(int value){return UUID.fromString(String.format(Locale.ROOT,"b1400000-0000-0000-0000-%012d",value));}
}
