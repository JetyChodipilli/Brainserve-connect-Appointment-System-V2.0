package com.brainserve.appointment.worktask.domain;

import com.brainserve.appointment.shared.application.BusinessException;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DepartmentWorkTaskTest {
    private DepartmentWorkTask task() {
        UUID teamLeadUserId = UUID.randomUUID();
        return new DepartmentWorkTask(UUID.randomUUID(), UUID.randomUUID(), teamLeadUserId,
                teamLeadUserId, "TEAM_LEAD", "EMPLOYEE",
                "Complete visitor workflow tests", "Validate the end-to-end approval contracts",
                "Technology", LocalDate.now().plusDays(3));
    }

    @Test
    void employeeCompletesTeamLeadApprovesAndEmployeeAcknowledges() {
        DepartmentWorkTask task = task();

        task.start("API contract tests started");
        task.complete("All workflow tests pass");
        task.approve("Acceptance criteria verified");
        task.acknowledge();

        assertThat(task.getStatus()).isEqualTo(WorkTaskStatus.ACKNOWLEDGED);
        assertThat(task.getStartedAt()).isNotNull();
        assertThat(task.getCompletedAt()).isNotNull();
        assertThat(task.getApprovedAt()).isNotNull();
        assertThat(task.getAcknowledgedAt()).isNotNull();
    }

    @Test
    void requestedChangesReturnTheTaskToEmployeeProgress() {
        DepartmentWorkTask task = task();
        task.complete("Initial delivery");
        task.requestChanges("Add the missing HR routing test");

        assertThat(task.getStatus()).isEqualTo(WorkTaskStatus.CHANGES_REQUESTED);
        assertThat(task.getCompletedAt()).isNull();
        task.start("Adding the HR test");
        assertThat(task.getStatus()).isEqualTo(WorkTaskStatus.IN_PROGRESS);
    }

    @Test
    void employeeCanReviseRejectedWorkUntilTeamLeadReviewsIt() {
        DepartmentWorkTask task = task();
        task.complete("Initial delivery");
        task.requestChanges("Add the missing HR routing test");
        task.complete("Corrected delivery");

        task.reviseEmployeeReworkSubmission("Corrected delivery with final evidence");

        assertThat(task.getStatus()).isEqualTo(WorkTaskStatus.COMPLETED);
        assertThat(task.getEmployeeUpdate()).isEqualTo("Corrected delivery with final evidence");

        task.approve("Rework accepted");
        assertThatThrownBy(() -> task.reviseEmployeeReworkSubmission("Late edit"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("already reviewed");
    }

    @Test
    void employeeCannotAcknowledgeBeforeTeamLeadApproval() {
        assertThatThrownBy(() -> task().acknowledge())
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("ASSIGNED");
    }

    @Test
    void insightsRejectionWaitsForTeamLeadPlanBeforeEmployeeRework() {
        DepartmentWorkTask task = task();
        task.complete("Initial delivery");
        task.approve("Team Lead verified");
        task.requestInsightRework("CEO", "Evidence does not cover the failure path");

        assertThat(task.getStatus()).isEqualTo(WorkTaskStatus.INSIGHT_REWORK_REQUESTED);
        assertThat(task.getReworkCycle()).isEqualTo(1);
        assertThatThrownBy(() -> task.start("Starting without Team Lead guidance"))
                .isInstanceOf(BusinessException.class);

        task.assignInsightRework("Add failure-path evidence and rerun the suite");
        assertThat(task.getStatus()).isEqualTo(WorkTaskStatus.CHANGES_REQUESTED);
        task.start("Rework started");
        task.complete("Failure-path evidence attached");
        task.approve("Rework verified");
        assertThat(task.getStatus()).isEqualTo(WorkTaskStatus.APPROVED);
    }

    @Test
    void employeeCanUpdateInsightsReworkReturnedByEveryGovernanceReviewer() {
        for (String reviewer : new String[] {"HR", "MANAGER", "CEO"}) {
            DepartmentWorkTask task = task();
            task.complete("Initial delivery");
            task.approve("Team Lead verified");
            task.requestInsightRework(reviewer, reviewer + " requested stronger evidence");
            task.assignInsightRework("Correct the evidence requested by " + reviewer);
            task.complete("Corrected evidence submitted");

            task.reviseEmployeeReworkSubmission("Final corrected evidence for " + reviewer);

            assertThat(task.getStatus()).isEqualTo(WorkTaskStatus.COMPLETED);
            assertThat(task.getEmployeeUpdate()).isEqualTo("Final corrected evidence for " + reviewer);
        }
    }

    @Test
    void openTasksMoveToAReplacementLeadButApprovedHistoryDoesNot() {
        UUID previousLead = UUID.randomUUID();
        UUID replacementLead = UUID.randomUUID();
        var open = new DepartmentWorkTask(UUID.randomUUID(), UUID.randomUUID(), previousLead,
                previousLead, "TEAM_LEAD", "EMPLOYEE",
                "Prepare compliance evidence", "Attach the current visitor trail", "Operations",
                LocalDate.now().plusDays(2));
        open.reassignOpenTask(previousLead, replacementLead);
        assertThat(open.getTeamLeadUserId()).isEqualTo(replacementLead);

        var approved = new DepartmentWorkTask(UUID.randomUUID(), UUID.randomUUID(), previousLead,
                previousLead, "TEAM_LEAD", "EMPLOYEE",
                "Closed delivery", "Retain its original approval owner", "Operations", LocalDate.now());
        approved.complete("Done");
        approved.approve("Verified");
        approved.reassignOpenTask(previousLead, replacementLead);
        assertThat(approved.getTeamLeadUserId()).isEqualTo(previousLead);
    }

    @Test
    void hrAssignedTeamLeadDeliveryGoesDirectlyToHrAuditWithoutSelfApproval() {
        UUID teamLeadUserId = UUID.randomUUID();
        DepartmentWorkTask task = new DepartmentWorkTask(UUID.randomUUID(), UUID.randomUUID(),
                teamLeadUserId, UUID.randomUUID(), "HR_ADMIN", "TEAM_LEAD",
                "Prepare weekly delivery evidence", "Attach the completed department evidence",
                "Operations", LocalDate.now().plusDays(2));

        task.start("Evidence collection started");
        task.complete("Evidence attached");

        assertThat(task.isReadyForHrAudit()).isTrue();
        assertThatThrownBy(() -> task.approve("Self-approved"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("directly to HR audit");
    }

    @Test
    void hrAssignedTeamLeadCanReviseACompletedReworkSubmission() {
        UUID teamLeadUserId = UUID.randomUUID();
        DepartmentWorkTask task = new DepartmentWorkTask(UUID.randomUUID(), UUID.randomUUID(),
                teamLeadUserId, UUID.randomUUID(), "HR_ADMIN", "TEAM_LEAD",
                "Prepare weekly delivery evidence", "Attach the completed department evidence",
                "Operations", LocalDate.now().plusDays(2));
        task.complete("Initial evidence");
        task.requestInsightRework("MANAGER", "Add the missing exception evidence");
        task.assignInsightRework("Attach the exception evidence and rerun validation");
        task.complete("First rework submission");

        task.reviseInsightReworkSubmission("Corrected rework submission with exception evidence");

        assertThat(task.getStatus()).isEqualTo(WorkTaskStatus.COMPLETED);
        assertThat(task.getEmployeeUpdate())
                .isEqualTo("Corrected rework submission with exception evidence");
    }

    @Test
    void hrAssignedTeamLeadCanCompleteReworkReturnedByEveryGovernanceReviewer() {
        for (String reviewer : new String[] {"HR", "MANAGER", "CEO"}) {
            UUID teamLeadUserId = UUID.randomUUID();
            DepartmentWorkTask task = new DepartmentWorkTask(UUID.randomUUID(), UUID.randomUUID(),
                    teamLeadUserId, UUID.randomUUID(), "HR_ADMIN", "TEAM_LEAD",
                    "Prepare weekly delivery evidence", "Attach the completed department evidence",
                    "Operations", LocalDate.now().plusDays(2));
            task.complete("Initial evidence");
            task.requestInsightRework(reviewer, reviewer + " requested stronger evidence");
            task.assignInsightRework("Correct the evidence requested by " + reviewer);

            task.complete("Final corrected evidence for " + reviewer);

            assertThat(task.getStatus()).isEqualTo(WorkTaskStatus.COMPLETED);
            assertThat(task.isReadyForHrAudit()).isTrue();
        }
    }

    @Test
    void ceoFinalApprovalClosesHrAssignedTeamLeadWorksheet() {
        UUID teamLeadUserId = UUID.randomUUID();
        DepartmentWorkTask task = new DepartmentWorkTask(UUID.randomUUID(), UUID.randomUUID(),
                teamLeadUserId, UUID.randomUUID(), "HR_ADMIN", "TEAM_LEAD",
                "Prepare weekly delivery evidence", "Attach the completed department evidence",
                "Operations", LocalDate.now().plusDays(2));
        task.complete("Final evidence submitted");

        task.finalizeInsightApproval();

        assertThat(task.getStatus()).isEqualTo(WorkTaskStatus.APPROVED);
        assertThat(task.getApprovedAt()).isNotNull();
        assertThat(task.isReadyForHrAudit()).isFalse();
    }

    @Test
    void ceoFinalApprovalDoesNotRewriteEmployeeApprovalState() {
        DepartmentWorkTask task = task();
        task.complete("Final evidence submitted");
        task.approve("Team Lead accepted the delivery");
        var approvedAt = task.getApprovedAt();

        task.finalizeInsightApproval();

        assertThat(task.getStatus()).isEqualTo(WorkTaskStatus.APPROVED);
        assertThat(task.getApprovedAt()).isEqualTo(approvedAt);
    }

    @Test
    void ordinaryCompletedWorksheetCannotUseInsightsRevisionAction() {
        DepartmentWorkTask task = task();
        task.complete("Initial delivery");

        assertThatThrownBy(() -> task.reviseInsightReworkSubmission("Late edit"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("COMPLETED");
    }
}
