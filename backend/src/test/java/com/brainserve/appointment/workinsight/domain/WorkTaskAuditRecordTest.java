package com.brainserve.appointment.workinsight.domain;

import com.brainserve.appointment.shared.application.BusinessException;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkTaskAuditRecordTest {
    private WorkTaskAuditRecord record() {
        return new WorkTaskAuditRecord(UUID.randomUUID(), LocalDate.now(), UUID.randomUUID(), "Technology",
                UUID.randomUUID(), "BSPL-TECH-0001", "Employee One", UUID.randomUUID(), "Team Lead One",
                "TEAM_LEAD", "EMPLOYEE", "Validate visitor routing", "APPROVED", UUID.randomUUID());
    }

    @Test
    void managerVerifiesBeforeCeoApprovesHrAuditedRecord() {
        WorkTaskAuditRecord record = record();
        record.decideByManager(UUID.randomUUID(), true, "Department evidence verified");
        assertThat(record.getAuditStatus()).isEqualTo(WorkInsightStatus.PENDING_CEO_APPROVAL);
        assertThat(record.getManagerDecidedAt()).isNotNull();

        record.decideByCeo(UUID.randomUUID(), true, "Final evidence verified");
        assertThat(record.getAuditStatus()).isEqualTo(WorkInsightStatus.CEO_APPROVED);
        assertThat(record.getCeoDecidedAt()).isNotNull();
    }

    @Test
    void ceoCannotDecideSameAuditTwice() {
        WorkTaskAuditRecord record = record();
        record.decideByManager(UUID.randomUUID(), true, "Department evidence verified");
        record.decideByCeo(UUID.randomUUID(), false, "Missing evidence");
        assertThatThrownBy(() -> record.decideByCeo(UUID.randomUUID(), true, "Retry"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("not waiting for the CEO decision");
    }

    @Test
    void rejectedAuditBecomesTeamLeadReworkThenCanBeResubmitted() {
        WorkTaskAuditRecord record = record();
        record.decideByManager(UUID.randomUUID(), true, "Department evidence verified");
        record.decideByCeo(UUID.randomUUID(), false, "Missing validation evidence");
        assertThat(record.getAuditStatus()).isEqualTo(WorkInsightStatus.CEO_REWORK_REQUESTED);
        assertThat(record.getReworkReason()).isEqualTo("Missing validation evidence");
        record.assignRework("Repeat validation and attach the failed cases", "CHANGES_REQUESTED");
        assertThat(record.getAuditStatus()).isEqualTo(WorkInsightStatus.REWORK_ASSIGNED);
        record.resubmit(UUID.randomUUID(), "APPROVED");
        assertThat(record.getAuditStatus()).isEqualTo(WorkInsightStatus.PENDING_MANAGER_APPROVAL);
        assertThat(record.getReworkCycle()).isEqualTo(1);
    }

    @Test
    void repeatedManagerAndCeoRejectionsCanBeReworkedToFinalApproval() {
        WorkTaskAuditRecord record = record();

        record.decideByManager(UUID.randomUUID(), false, "Manager needs exception evidence");
        record.assignRework("Attach the exception evidence", "CHANGES_REQUESTED");
        record.resubmit(UUID.randomUUID(), "APPROVED");
        record.decideByManager(UUID.randomUUID(), true, "Manager evidence accepted");
        record.decideByCeo(UUID.randomUUID(), false, "CEO needs recovery evidence");
        record.assignRework("Attach the recovery evidence", "CHANGES_REQUESTED");
        record.resubmit(UUID.randomUUID(), "APPROVED");
        record.decideByManager(UUID.randomUUID(), true, "All evidence accepted");
        record.decideByCeo(UUID.randomUUID(), true, "Final approval");

        assertThat(record.getAuditStatus()).isEqualTo(WorkInsightStatus.CEO_APPROVED);
        assertThat(record.getReworkCycle()).isEqualTo(2);
    }

    @Test
    void hrCanReturnEveryCompletedReworkCycleBeforeManagerReview() {
        WorkTaskAuditRecord record = record();

        record.requestHrRework(UUID.randomUUID(), "HR needs stronger evidence");
        record.assignRework("Add HR evidence", "CHANGES_REQUESTED");
        record.requestHrRework(UUID.randomUUID(), "The corrected evidence is incomplete");

        assertThat(record.getAuditStatus()).isEqualTo(WorkInsightStatus.HR_REWORK_REQUESTED);
        assertThat(record.getReworkCycle()).isEqualTo(2);
    }

    @Test
    void rejectionRequiresReason() {
        assertThatThrownBy(() -> record().decideByManager(UUID.randomUUID(), false, " "))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("rejection reason");
    }
}
