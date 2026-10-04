package com.brainserve.appointment.reporting.application;

import com.brainserve.appointment.iam.api.CurrentAccountAuthority;
import com.brainserve.appointment.operations.api.DashboardDependencyProbe;
import com.brainserve.appointment.operations.application.IntegrationHealthService;
import com.brainserve.appointment.reporting.application.RoleDashboardQueryService.PeriodPreset;
import com.brainserve.appointment.shared.application.BusinessException;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.*;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Executes production predicates and V53 triggers on PostgreSQL; mandatory in CI. */
@Testcontainers(disabledWithoutDocker = true)
class AdministrationDashboardIntegrationTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.2-alpine");
    static JdbcTemplate jdbc;
    static DriverManagerDataSource dataSource;
    static DataSourceTransactionManager transactions;
    static Map<String, Integer> originalChecksums;
    static final UUID DEPT_A = UUID.fromString("10000000-0000-0000-0000-000000000011");
    static final UUID DEPT_B = UUID.fromString("10000000-0000-0000-0000-000000000012");
    static final UUID EMPLOYEE_A = UUID.fromString("20000000-0000-0000-0000-000000000011");
    static final UUID EMPLOYEE_B = UUID.fromString("20000000-0000-0000-0000-000000000012");
    static final UUID ADMIN = UUID.fromString("30000000-0000-0000-0000-000000000011");
    static final UUID CEO = UUID.fromString("30000000-0000-0000-0000-000000000012");
    static final UUID OTHER = UUID.fromString("30000000-0000-0000-0000-000000000013");
    static final UUID LEGACY_UNKNOWN = UUID.fromString("50000000-0000-0000-0000-000000000011");
    static final UUID LEGACY_PROVEN = UUID.fromString("50000000-0000-0000-0000-000000000012");
    static final Instant AS_OF = Instant.parse("2026-05-03T12:00:00Z");
    static final LocalDate DAY = LocalDate.of(2026, 5, 2);
    TransactionStatus transaction;
    AdministrationDashboardService service;

    @BeforeAll static void upgradeWithExistingHistory() {
        var configuration = Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        configuration.target("52").load().migrate();
        dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        transactions = new DataSourceTransactionManager(dataSource);
        originalChecksums = new LinkedHashMap<>();
        for (var migration : configuration.target("52").load().info().applied()) originalChecksums.put(migration.getVersion().toString(), migration.getChecksum());
        var seed = new TransactionTemplate(transactions);
        seed.executeWithoutResult(status -> {
            department(DEPT_A, "DASH_A"); department(DEPT_B, "DASH_B");
            employee(EMPLOYEE_A, DEPT_A, "DASH-CEO"); employee(EMPLOYEE_B, DEPT_B, "DASH-OTHER");
            account(ADMIN, null, "ROLE_SYSTEM_ADMIN", "ACTIVE", false);
            account(CEO, EMPLOYEE_A, "ROLE_CEO", "ACTIVE", false);
            account(OTHER, EMPLOYEE_B, "ROLE_EMPLOYEE", "ACTIVE", false);
            task(LEGACY_UNKNOWN, "legacy-unknown", LocalDate.of(2020, 1, 1), "ASSIGNED");
            // Mimic V29's synthetic TASK_CREATED backfill: current date is no proof.
            jdbc.update("update workboard_activity_event set details_json = details_json || '{\"backfilled\":true}'::jsonb where work_task_id = ?", LEGACY_UNKNOWN);
            task(LEGACY_PROVEN, "legacy-proven", LocalDate.of(2020, 2, 1), "ASSIGNED");
            jdbc.update("update department_work_task set due_date = '2020-03-01', updated_at = '2020-01-02T12:00:00Z' where id = ?", LEGACY_PROVEN);
        });
        configuration.target("latest").load().migrate();
        configuration.target("latest").load().validate();
    }

    @BeforeEach void prepare() {
        transaction = transactions.getTransaction(new DefaultTransactionDefinition());
        service = service(AS_OF, "Asia/Kolkata");
    }

    @AfterEach void rollback() { if (!transaction.isCompleted()) transactions.rollback(transaction); }

    @Test void additiveUpgradePreservesChecksumsAndRejectsSyntheticOriginalDeadlines() {
        for (var migration : Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()).load().info().applied()) {
            if (migration.getVersion().getMajor().intValue() <= 52) assertThat(migration.getChecksum()).isEqualTo(originalChecksums.get(migration.getVersion().toString()));
        }
        assertThat(jdbc.queryForObject("select count(*) from work_original_commitment where work_task_id = ?", Long.class, LEGACY_UNKNOWN)).isZero();
        assertThat(jdbc.queryForObject("select original_due_date from work_original_commitment where work_task_id = ?", LocalDate.class, LEGACY_PROVEN))
                .isEqualTo(LocalDate.of(2020, 2, 1));
        assertThat(jdbc.queryForObject("select count(*) from appointment_stage_event", Long.class)).isZero();
        var missing = card(cards(CEO, DAY, DAY), "WORK07");
        assertThat(missing.state()).isEqualTo("UNAVAILABLE");
        assertThat(missing.value()).isNull();
        assertThat(missing.excludedCount()).isEqualTo(1);
    }

    @Test void rawP95RetainsZeroWaitsAndExcludesMissingOrNegativeAtOfficeBoundaries() {
        Instant start = Instant.parse("2026-05-01T18:30:00Z");
        for (int n = 0; n < 10; n++) {
            Instant checkin = n == 0 ? start : start.plusSeconds(n * 3600L);
            visit("WAIT" + n, DEPT_A, EMPLOYEE_A, checkin, n == 0 ? checkin.minusSeconds(3600) : checkin, "COMPLETED", true, true);
        }
        visit("NEGATIVE", DEPT_B, EMPLOYEE_B, start.plusSeconds(40), start.plusSeconds(100), "COMPLETED", true, true);
        visit("MISSING", DEPT_B, EMPLOYEE_B, start.plusSeconds(50), null, "COMPLETED", true, true);
        visit("BEFORE", DEPT_A, EMPLOYEE_A, start.minusNanos(1000), start.minusNanos(1000), "COMPLETED", true, true);
        visit("NEXT", DEPT_A, EMPLOYEE_A, start.plusSeconds(86400), start.plusSeconds(86400), "COMPLETED", true, true);
        var wait = card(cards(CEO, DAY, DAY), "VIS09");
        assertThat(wait.state()).isEqualTo("AVAILABLE");
        assertThat(wait.value()).isCloseTo(1980d, within(0.000001));
        assertThat(wait.sampleSize()).isEqualTo(10);
        assertThat(wait.excludedCount()).isEqualTo(2);
        assertThat(wait.coveragePercent()).isCloseTo(100d * 10 / 12, within(0.000001));
        Double independentlyWeighted = jdbc.queryForObject("select avg(extract(epoch from (v.checked_in_at - a.security_intake_at))) from visit_access_record v join appointment a on a.id = v.appointment_id "
                + "where a.reference_number like 'DASH-WAIT%'", Double.class);
        assertThat(independentlyWeighted).isEqualTo(360d); // QA04: 60min + nine zeros = 6min.
        var records = records(CEO, "VIS09", DAY, DAY, 0, 100);
        assertThat(records.totalElements()).isEqualTo(10);
        assertThat(records.items()).hasSize(10);
        assertThat(records.items()).filteredOn(row -> row.detail().startsWith("0.000000 seconds")).hasSize(9);
        assertThat(records.items()).noneMatch(row -> row.label().contains("NEGATIVE") || row.label().contains("MISSING") || row.label().contains("BEFORE") || row.label().contains("NEXT"));
    }

    @Test void zeroOnlyP95IsAvailableAndTrulyNoObservationsAreNotApplicable() {
        Instant noon = Instant.parse("2026-05-02T06:30:00Z");
        visit("ZERO", DEPT_A, EMPLOYEE_A, noon, noon, "COMPLETED", true, true);
        assertThat(card(cards(CEO, DAY, DAY), "VIS09").value()).isZero();
        var empty = card(cards(CEO, DAY.minusDays(1), DAY.minusDays(1)), "VIS09");
        assertThat(empty.state()).isEqualTo("NOT_APPLICABLE");
        assertThat(empty.value()).isNull();
        assertThat(empty.sampleSize()).isZero();
    }

    @Test void repeatedEventsDoNotDuplicateArrivalOrCheckinAndNowIgnoresHistoricalPicker() {
        Instant noon = Instant.parse("2026-05-02T06:30:00Z");
        UUID id = visit("DEDUP", DEPT_B, EMPLOYEE_B, noon, noon.minusSeconds(30), "CHECKED_IN", true, false);
        for (int n = 0; n < 5; n++) {
            audit("VISITOR_SECURITY_INTAKE", "APPOINTMENT", id, null, noon);
            jdbc.update("insert into visitor_checkpoint_event(occurred_at, appointment_id, access_record_id, department_id, visitor_name, badge_number, event_type, actor_id) "
                    + "select occurred_at, appointment_id, access_record_id, department_id, visitor_name, badge_number, event_type, actor_id from visitor_checkpoint_event where appointment_id = ? limit 1", id);
        }
        assertThat(card(cards(CEO, DAY, DAY), "VIS02").value()).isEqualTo(1d);
        assertThat(records(CEO, "VIS02", DAY, DAY, 0, 100).totalElements()).isEqualTo(1);
        assertThat(card(cards(CEO, LocalDate.of(2020, 1, 1), LocalDate.of(2020, 1, 7)), "VIS05").value()).isEqualTo(1d);
        assertThat(records(CEO, "VIS05", DAY.minusDays(6), DAY, 0, 100).totalElements()).isEqualTo(1);
        assertThat(card(cards(CEO, DAY.minusDays(6), DAY), "VIS09").sampleSize()).isEqualTo(1);
    }

    @Test void allTwentyOriginalDueTasksRemainDenominatorAfterMutableDueDateExtensions() {
        List<UUID> ids = new ArrayList<>();
        Instant onTime = Instant.parse("2026-05-02T12:00:00Z");
        Instant late = Instant.parse("2026-05-02T19:00:00Z");
        for (int n = 0; n < 20; n++) {
            UUID id = UUID.randomUUID(); ids.add(id); task(id, "COHORT-" + n, DAY, "ASSIGNED");
            if (n < 17) finalAcceptance(id, n < 15 ? onTime : late);
        }
        var before = card(cards(CEO, DAY, DAY), "WORK07");
        assertThat(before.value()).isEqualTo(75d);
        assertThat(before.eligibleCount()).isEqualTo(20);
        assertThat(records(CEO, "WORK07", DAY, DAY, 0, 100).totalElements()).isEqualTo(20);
        assertThat(records(CEO, "WORK07", DAY, DAY, 0, 100).items()).hasSize(20);
        for (UUID id : ids) jdbc.update("update department_work_task set due_date = '2026-06-01', updated_at = '2026-05-03T08:00:00Z' where id = ?", id);
        assertThat(card(cards(CEO, DAY, DAY), "WORK07").value()).isEqualTo(75d);
        assertThat(jdbc.queryForObject("select count(*) from work_original_commitment where original_due_date = '2026-05-02'", Long.class)).isEqualTo(20);
        var first = records(CEO, "WORK07", DAY, DAY, 0, 7);
        var second = records(CEO, "WORK07", DAY, DAY, 1, 7);
        assertThat(first.totalPages()).isEqualTo(3);
        assertThat(first.items()).extracting("id").doesNotContainAnyElementsOf(second.items().stream().map(AdministrationDashboardService.RecordItem::id).toList());
        assertThat(first.items()).extracting(AdministrationDashboardService.RecordItem::id).isSorted();
        assertThat(records(CEO, "WORK07", DAY, DAY, 500, 7).items()).isEmpty();
        assertThat(records(CEO, "WORK07", DAY, DAY, 500, 7).totalElements()).isEqualTo(20);
    }

    @Test void statusAndManagerDecisionsCannotManufactureEvidenceAcceptance() {
        for (String status : List.of("COMPLETED", "APPROVED", "ACKNOWLEDGED")) {
            UUID id = UUID.randomUUID(); task(id, "INTERMEDIATE-" + status, DAY, "ASSIGNED");
            jdbc.update("update department_work_task set status = ?, updated_at = '2026-05-02T12:00:00Z' where id = ?", status, id);
            audit("WORK_INSIGHT_HR_AUDITED", "WORK_TASK_AUDIT", UUID.randomUUID(), id, Instant.parse("2026-05-02T13:00:00Z"));
            audit("WORK_INSIGHT_MANAGER_APPROVED", "WORK_TASK_AUDIT", UUID.randomUUID(), id, Instant.parse("2026-05-02T14:00:00Z"));
        }
        assertThat(card(cards(CEO, DAY, DAY), "WORK07").value()).isZero();
        var overdue = card(cards(CEO, DAY, DAY), "WORK03");
        // Baseline legacy execution work is overdue; intermediate deliveries are separate.
        assertThat(overdue.value()).isEqualTo(2d);
        assertThat(overdue.reason()).contains("3 submitted or employee-approved deliveries");
    }

    @Test void incompleteCurrentAndFutureCohortsKeepUnfinishedTasksAndDisclosePreliminaryState() {
        UUID accepted = UUID.randomUUID(), unfinished = UUID.randomUUID();
        task(accepted, "TODAY-ACCEPTED", DAY.plusDays(1), "ASSIGNED");
        task(unfinished, "TODAY-UNFINISHED", DAY.plusDays(1), "IN_PROGRESS");
        finalAcceptance(accepted, AS_OF.minusSeconds(60));
        var current = card(cards(CEO, DAY.plusDays(1), DAY.plusDays(1)), "WORK07");
        assertThat(current.value()).isEqualTo(50d);
        assertThat(current.eligibleCount()).isEqualTo(2);
        assertThat(current.reason()).contains("Preliminary");
        UUID future = UUID.randomUUID(); task(future, "FUTURE", DAY.plusDays(2), "ASSIGNED");
        var futureCard = card(cards(CEO, DAY.plusDays(2), DAY.plusDays(2)), "WORK07");
        assertThat(futureCard.value()).isZero();
        assertThat(futureCard.eligibleCount()).isEqualTo(1);
        assertThat(futureCard.reason()).contains("Preliminary");
    }

    @Test void currentEvidenceAcceptanceAndReworkRespectOriginalCutoff() {
        UUID reopened = UUID.randomUUID(); task(reopened, "REOPENED", DAY, "ASSIGNED");
        finalAcceptance(reopened, Instant.parse("2026-05-02T08:00:00Z"));
        jdbc.update("update department_work_task set status = 'INSIGHT_REWORK_REQUESTED', updated_at = '2026-05-02T10:00:00Z' where id = ?", reopened);
        assertThat(card(cards(CEO, DAY, DAY), "WORK07").value()).isZero();
        finalAcceptance(reopened, Instant.parse("2026-05-02T12:00:00Z"));
        assertThat(card(cards(CEO, DAY, DAY), "WORK07").value()).isEqualTo(100d);
        jdbc.update("update department_work_task set status = 'INSIGHT_REWORK_REQUESTED', updated_at = '2026-05-03T08:00:00Z' where id = ?", reopened);
        assertThat(card(cards(CEO, DAY, DAY), "WORK07").value()).isZero();
        assertThat(card(cards(CEO, DAY, DAY.plusDays(1)), "WORK07").value()).isZero();
    }

    @Test void employeeEvidenceAcceptanceCountsBeforeFinalCeoClosure() {
        UUID id = UUID.randomUUID(); task(id, "EVIDENCE-BEFORE-CLOSURE", DAY, "ASSIGNED");
        evidenceAcceptance(id, Instant.parse("2026-05-02T12:00:00Z"));
        assertThat(card(cards(CEO, DAY, DAY), "WORK07").value()).isEqualTo(100d);
        assertThat(jdbc.queryForObject("select count(*) from audit_event_history where event_type='WORK_INSIGHT_CEO_APPROVED'", Long.class)).isZero();
        audit("WORK_INSIGHT_CEO_APPROVED", "WORK_TASK_AUDIT", UUID.randomUUID(), id, Instant.parse("2026-05-03T12:00:00Z"));
        assertThat(card(cards(CEO, DAY, DAY), "WORK07").value()).isEqualTo(100d);
    }

    @Test void currentActorRoleEnabledArchiveAndEffectiveOverridesAreRereadInOneTransaction() {
        var first = cards(CEO, DAY, DAY);
        assertThat(card(first, "VIS05").state()).isEqualTo("AVAILABLE");
        jdbc.update("insert into iam_user_permission_grant values (?, 'VISITOR_OCCUPANCY_READ')", CEO);
        jdbc.update("insert into iam_user_permission_deny values (?, 'VISITOR_OCCUPANCY_READ')", CEO);
        assertThat(card(cards(CEO, DAY, DAY), "VIS05").state()).isEqualTo("RESTRICTED");
        assertThatThrownBy(() -> records(CEO, "VIS05", DAY, DAY, 0, 50)).isInstanceOf(BusinessException.class);
        jdbc.update("delete from iam_user_permission_deny where user_id = ?", CEO);
        assertThat(card(cards(CEO, DAY, DAY), "VIS05").state()).isEqualTo("AVAILABLE");
        assertThatThrownBy(() -> records(CEO, "OPS04", DAY, DAY, 0, 50)).isInstanceOf(BusinessException.class);
        jdbc.update("update iam_user_role set role_name = 'ROLE_EMPLOYEE' where user_id = ?", CEO);
        assertThatThrownBy(() -> cards(CEO, DAY, DAY)).isInstanceOf(BusinessException.class);
        jdbc.update("update iam_user_role set role_name = 'ROLE_CEO' where user_id = ?", CEO);
        jdbc.update("update iam_user_account set enabled = false where id = ?", CEO);
        assertThatThrownBy(() -> cards(CEO, DAY, DAY)).isInstanceOf(BusinessException.class);
        jdbc.update("update iam_user_account set enabled = false, account_status = 'DISABLED', archived = true, archived_at = ? where id = ?", AS_OF.atOffset(ZoneOffset.UTC), CEO);
        assertThatThrownBy(() -> cards(CEO, DAY, DAY)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> cards(OTHER, DAY, DAY)).isInstanceOf(BusinessException.class);
    }

    @Test void companyScopeIncludesBothDepartmentsAndCeoQueueRequiresEligibleCurrentStage() {
        Instant noon = AS_OF.minusSeconds(3600);
        visit("COMPANY-A", DEPT_A, EMPLOYEE_A, noon, noon, "COMPLETED", true, false);
        visit("COMPANY-B", DEPT_B, EMPLOYEE_B, noon, noon, "COMPLETED", true, false);
        UUID eligible = visit("CEO-ELIGIBLE", DEPT_B, EMPLOYEE_A, AS_OF.plusSeconds(3600), null, "PENDING_CEO_APPROVAL", false, false);
        jdbc.update("update appointment set type = 'CEO_VISIT', manager_decision_at = ?, manager_approval_actor_id = ? where id = ?", AS_OF.atOffset(ZoneOffset.UTC), OTHER, eligible);
        UUID wrongHost = visit("WRONG-HOST", DEPT_B, EMPLOYEE_B, AS_OF.plusSeconds(7200), null, "PENDING_CEO_APPROVAL", false, false);
        jdbc.update("update appointment set type = 'CEO_VISIT', manager_decision_at = ?, manager_approval_actor_id = ? where id = ?", AS_OF.atOffset(ZoneOffset.UTC), OTHER, wrongHost);
        UUID missingDecision = visit("NO-MANAGER", DEPT_A, EMPLOYEE_A, AS_OF.plusSeconds(10800), null, "PENDING_CEO_APPROVAL", false, false);
        jdbc.update("update appointment set type = 'CEO_VISIT' where id = ?", missingDecision);
        var response = cards(CEO, DAY, DAY);
        assertThat(response.scope()).isEqualTo("COMPANY");
        assertThat(response.departmentId()).isNull();
        assertThat(card(response, "VIS05").value()).isEqualTo(2d);
        assertThat(card(response, "VIS07").value()).isEqualTo(1d);
        assertThat(records(CEO, "VIS07", DAY, DAY, 0, 100).items()).extracting("id").containsExactly(eligible.toString());
        assertThat(response.supplementary().getFirst().value()).isEqualTo(5d);
        assertThat(response.coverage()).filteredOn(coverage -> coverage.id().equals("RETAINED_VISIT_HISTORY"))
                .singleElement().satisfies(coverage -> assertThat(coverage.since()).isNotNull());
    }

    @Test void adminQueueAndUniqueDeadJobsReconcileAndMissingOperationalEvidenceStaysUnavailable() {
        UUID pendingCeo = UUID.randomUUID(), pendingHr = UUID.randomUUID(), archivedCeo = UUID.randomUUID();
        // Pending CEOs cannot coexist with the governed active CEO. Temporarily
        // disable it in this rollback-only fixture before creating the queue.
        jdbc.update("update iam_user_account set enabled = false, account_status = 'DISABLED' where id = ?", CEO);
        account(pendingCeo, null, "ROLE_CEO", "PENDING_APPROVAL", false);
        account(pendingHr, null, "ROLE_HR_ADMIN", "PENDING_APPROVAL", false);
        account(archivedCeo, null, "ROLE_CEO", "PENDING_APPROVAL", true);
        for (String state : List.of("DEAD", "DEAD", "SENT", "PENDING")) {
            jdbc.update("insert into notification_outbox(id, event_key, channel, destination, template, payload_json, status, next_attempt_at, created_at, created_by, updated_at, updated_by) "
                    + "values (?, ?, 'EMAIL', 'safe@example.invalid', 'SAFE_TEMPLATE', '{}'::jsonb, ?, now(), '2026-05-01T12:00:00Z', 'fixture', now(), 'fixture')", UUID.randomUUID(), UUID.randomUUID().toString(), state);
        }
        var response = cards(ADMIN, DAY, DAY);
        assertThat(card(response, "IAM03").value()).isEqualTo(1d);
        assertThat(records(ADMIN, "IAM03", DAY, DAY, 0, 100).items()).extracting("id").containsExactly(pendingCeo.toString());
        assertThat(card(response, "NTF03").value()).isEqualTo(2d);
        assertThat(records(ADMIN, "NTF03", DAY, DAY, 0, 100).totalElements()).isEqualTo(2);
        for (String id : List.of("OPS01", "VIS08", "OPS07")) {
            assertThat(card(response, id).state()).isEqualTo("UNAVAILABLE");
            assertThat(records(ADMIN, id, DAY, DAY, 0, 100).items()).isEmpty();
        }
        jdbc.update("insert into iam_user_permission_deny values (?, 'ROLE_MANAGE')", ADMIN);
        assertThat(card(cards(ADMIN, DAY, DAY), "IAM03").state()).isEqualTo("RESTRICTED");
        assertThatThrownBy(() -> records(ADMIN, "IAM03", DAY, DAY, 0, 100)).isInstanceOf(BusinessException.class);
    }

    @Test void rollbackRemovesSourceCommitmentStageAndHistoryFactsAndGenerationAdvance() {
        long before = jdbc.queryForObject("select generation from reporting_source_revision", Long.class);
        UUID taskId = UUID.randomUUID();
        UUID visitId = UUID.randomUUID();
        var independent = new TransactionTemplate(transactions);
        independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        assertThatThrownBy(() -> independent.executeWithoutResult(status -> {
            task(taskId, "ROLLBACK", DAY, "ASSIGNED");
            insertVisit(visitId, "ROLLBACK", DEPT_A, EMPLOYEE_A, AS_OF, null, "PENDING_SECURITY_INTAKE", false, false);
            jdbc.update("update appointment set status = 'PENDING_RECEPTION_VERIFICATION', updated_at = ? where id = ?", AS_OF.atOffset(ZoneOffset.UTC), visitId);
            assertThat(jdbc.queryForObject("select count(*) from appointment_stage_event where appointment_id = ?", Long.class, visitId)).isEqualTo(2);
            throw new IllegalStateException("rollback fixture");
        })).hasMessage("rollback fixture");
        assertThat(jdbc.queryForObject("select count(*) from department_work_task where id = ?", Long.class, taskId)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from work_original_commitment where work_task_id = ?", Long.class, taskId)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from appointment_stage_event where appointment_id = ?", Long.class, visitId)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from workboard_activity_event where work_task_id = ?", Long.class, taskId)).isZero();
        assertThat(jdbc.queryForObject("select generation from reporting_source_revision", Long.class)).isEqualTo(before);
    }

    @Test void originalCommitmentAndStageFactsRejectUpdatesInsideSavepoints() {
        UUID taskId = UUID.randomUUID(); task(taskId, "IMMUTABLE", DAY, "ASSIGNED");
        UUID visitId = visit("IMMUTABLE", DEPT_A, EMPLOYEE_A, AS_OF, null, "PENDING_SECURITY_INTAKE", false, false);
        for (String sql : List.of("update work_original_commitment set original_due_date = '2030-01-01' where work_task_id = '" + taskId + "'",
                "delete from work_original_commitment where work_task_id = '" + taskId + "'",
                "update appointment_stage_event set stage = 'APPROVED' where appointment_id = '" + visitId + "'")) {
            Object savepoint = transaction.createSavepoint();
            assertThatThrownBy(() -> jdbc.update(sql)).hasMessageContaining("immutable");
            transaction.rollbackToSavepoint(savepoint); transaction.releaseSavepoint(savepoint);
        }
        assertThat(jdbc.queryForObject("select original_due_date from work_original_commitment where work_task_id = ?", LocalDate.class, taskId)).isEqualTo(DAY);
        assertThat(jdbc.queryForObject("select stage from appointment_stage_event where appointment_id = ?", String.class, visitId)).isEqualTo("PENDING_SECURITY_INTAKE");
    }

    @Test void configuredOfficeZoneChangesDateBoundariesAndOriginalDeadlineConsistently() {
        service = service(AS_OF, "America/New_York");
        Instant midnight = Instant.parse("2026-05-02T04:00:00Z");
        visit("NY-MIDNIGHT", DEPT_A, EMPLOYEE_A, midnight, midnight, "COMPLETED", true, true);
        visit("NY-BEFORE", DEPT_B, EMPLOYEE_B, midnight.minusSeconds(1), midnight.minusSeconds(1), "COMPLETED", true, true);
        assertThat(card(cards(CEO, DAY, DAY), "VIS02").value()).isEqualTo(1d);
        UUID id = UUID.randomUUID(); task(id, "NY-DEADLINE", DAY, "ASSIGNED");
        finalAcceptance(id, Instant.parse("2026-05-03T03:59:59Z"));
        assertThat(card(cards(CEO, DAY, DAY), "WORK07").value()).isEqualTo(100d);
        assertThat(cards(CEO, DAY, DAY).officeZone()).isEqualTo("America/New_York");
    }

    @Test void actualDependencyWrapperRemovesExceptionAndConnectionDetails() {
        var integrations = mock(IntegrationHealthService.class);
        when(integrations.inspect()).thenReturn(new IntegrationHealthService.IntegrationOverview("DEGRADED", AS_OF,
                List.of(new IntegrationHealthService.ServiceStatus("PostgreSQL", "db", false, "postgres://secret-user:secret-pass@private", 1),
                        new IntegrationHealthService.ServiceStatus("Redis", "cache", true, "redis://private", 1),
                        new IntegrationHealthService.ServiceStatus("Untrusted name with secret", "bad", false, "password", 1))));
        service = new AdministrationDashboardService(new NamedParameterJdbcTemplate(jdbc), new CurrentAccountAuthority(jdbc),
                new DashboardDependencyProbe(integrations), ZoneId.of("Asia/Kolkata"), Clock.fixed(AS_OF, ZoneOffset.UTC));
        var response = records(ADMIN, "OPS04", DAY, DAY, 0, 100);
        assertThat(response.items()).hasSize(2);
        assertThat(response.toString()).doesNotContain("secret", "private", "password", "postgres://", "redis://");
    }

    private AdministrationDashboardService service(Instant now, String zone) {
        var named = new NamedParameterJdbcTemplate(jdbc);
        var probe = mock(DashboardDependencyProbe.class);
        when(probe.inspect()).thenReturn(new DashboardDependencyProbe.Snapshot(now, List.of(new DashboardDependencyProbe.Observation("PostgreSQL", true))));
        return new AdministrationDashboardService(named, new CurrentAccountAuthority(jdbc), probe, ZoneId.of(zone), Clock.fixed(now, ZoneOffset.UTC));
    }
    private AdministrationDashboardService.DashboardCards cards(UUID actor, LocalDate from, LocalDate to) { return service.cards(actor, PeriodPreset.CUSTOM, from, to); }
    private AdministrationDashboardService.MetricRecords records(UUID actor, String id, LocalDate from, LocalDate to, int page, int size) { return service.records(actor, id, PeriodPreset.CUSTOM, from, to, page, size); }
    private AdministrationDashboardService.MetricCard card(AdministrationDashboardService.DashboardCards response, String id) { return response.cards().stream().filter(card -> card.id().equals(id)).findFirst().orElseThrow(); }

    static void department(UUID id, String code) {
        jdbc.update("insert into org_department(id, code, name, created_at, created_by, updated_at, updated_by) values (?, ?, ?, now(), 'fixture', now(), 'fixture')", id, code, code);
    }
    static void employee(UUID id, UUID department, String number) {
        jdbc.update("insert into employee(id, employee_number, first_name, last_name, display_name, official_email, department_id, designation, joining_date, status, created_at, created_by, updated_at, updated_by) "
                + "values (?, ?, 'Fixture', 'Employee', ?, ?, ?, 'Fixture', '2020-01-01', 'ACTIVE', now(), 'fixture', now(), 'fixture')", id, number, number, number.toLowerCase() + "@example.invalid", department);
    }
    static void account(UUID id, UUID employee, String role, String state, boolean archived) {
        jdbc.update("insert into iam_user_account(id, employee_id, email, password_hash, full_name, account_status, enabled, archived, archived_at, created_at, created_by, updated_at, updated_by) "
                + "values (?, ?, ?, 'not-a-login', 'Fixture Account', ?, ?, ?, ?, '2026-01-01T00:00:00Z', 'fixture', now(), 'fixture')", id, employee, "dash-" + id + "@example.invalid", state, state.equals("ACTIVE") && !archived, archived, archived ? AS_OF.atOffset(ZoneOffset.UTC) : null);
        jdbc.update("insert into iam_user_role(user_id, role_name) values (?, ?)", id, role);
    }
    static void task(UUID id, String title, LocalDate due, String status) {
        jdbc.update("insert into department_work_task(id, department_id, employee_id, team_lead_user_id, title, description, department_branch, due_date, status, assigned_by_user_id, assigned_by_role, assignee_role, created_at, created_by, updated_at, updated_by) "
                + "values (?, ?, ?, ?, ?, 'Fixture', 'Fixture branch', ?, ?, ?, 'TEAM_LEAD', 'EMPLOYEE', '2020-01-01T00:00:00Z', 'fixture', '2020-01-01T00:00:00Z', 'fixture')", id, DEPT_A, EMPLOYEE_B, OTHER, title, due, status, OTHER);
    }
    UUID visit(String reference, UUID department, UUID host, Instant checkin, Instant arrival, String state, boolean access, boolean checkedOut) {
        UUID id = UUID.randomUUID(); insertVisit(id, reference, department, host, checkin, arrival, state, access, checkedOut); return id;
    }
    void insertVisit(UUID id, String reference, UUID department, UUID host, Instant checkin, Instant arrival, String state, boolean access, boolean checkedOut) {
        jdbc.update("insert into appointment(id, reference_number, idempotency_key, type, status, visitor_name, visitor_email, visitor_phone, host_employee_id, routing_department_id, slot_start, slot_end, purpose, security_intake_at, security_intake_actor_id, arrival_visitor_name, arrival_purpose, created_at, created_by, updated_at, updated_by) "
                + "values (?, ?, ?, 'EMPLOYEE_VISIT', ?, 'Fixture Visitor', 'visitor@example.invalid', '0000000000', ?, ?, ?, ?, 'Fixture', ?, ?, ?, ?, '2020-01-01T00:00:00Z', 'fixture', '2020-01-01T00:00:00Z', 'fixture')",
                id, "DASH-" + reference, id.toString(), state, host, department, checkin.atOffset(ZoneOffset.UTC), checkin.plusSeconds(1).atOffset(ZoneOffset.UTC),
                arrival == null ? null : arrival.atOffset(ZoneOffset.UTC), arrival == null ? null : ADMIN, arrival == null ? null : "Fixture Visitor", arrival == null ? null : "Fixture");
        if (access) jdbc.update("insert into visit_access_record(id, appointment_id, visitor_name, badge_number, checked_in_at, checked_out_at, processed_by, created_at, created_by, updated_at, updated_by) "
                + "values (?, ?, 'Fixture Visitor', ?, ?, ?, 'fixture', '2020-01-01T00:00:00Z', 'fixture', '2020-01-01T00:00:00Z', 'fixture')", UUID.randomUUID(), id, id.toString().substring(0, 20), checkin.atOffset(ZoneOffset.UTC), checkedOut ? checkin.plusSeconds(1).atOffset(ZoneOffset.UTC) : null);
    }
    void finalAcceptance(UUID id, Instant at) {
        evidenceAcceptance(id, at);
        audit("WORK_INSIGHT_CEO_APPROVED", "WORK_TASK_AUDIT", UUID.randomUUID(), id, at);
    }
    void evidenceAcceptance(UUID id, Instant at) {
        jdbc.update("update department_work_task set status='APPROVED', approved_at=?, updated_at=?, "
                + "planning_state=jsonb_set(planning_state,'{submissions}',coalesce(planning_state->'submissions','[]'::jsonb) || jsonb_build_array(jsonb_build_object("
                + "'version',coalesce(submission_version,0)+1,'submittedAt',?::text,'acceptedAt',?::text,'acceptedByRole','TEAM_LEAD',"
                + "'checklist','[]'::jsonb,'evidence','[]'::jsonb,'assignmentRevision',assignment_revision,'authorshipKnown',false,'currentlyAccepted',false))), "
                + "submission_version=coalesce(submission_version,0)+1 where id=?",
                at.atOffset(ZoneOffset.UTC),at.atOffset(ZoneOffset.UTC),at.minusSeconds(60).toString(),at.toString(),id);
    }
    void audit(String type, String targetType, UUID target, UUID workTask, Instant at) {
        jdbc.update("insert into audit_event(id, occurred_at, actor_id, event_type, target_type, target_id, outcome, details_json) values (?, ?, ?, ?, ?, ?, 'SUCCESS', ?::jsonb)",
                UUID.randomUUID(), at.atOffset(ZoneOffset.UTC), CEO.toString(), type, targetType, target.toString(), workTask == null ? "{}" : "{\"workTaskId\":\"" + workTask + "\"}");
    }
}
