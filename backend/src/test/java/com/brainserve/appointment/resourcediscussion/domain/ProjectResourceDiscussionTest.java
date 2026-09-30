package com.brainserve.appointment.resourcediscussion.domain;

import com.brainserve.appointment.shared.application.BusinessException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProjectResourceDiscussionTest {
    private ProjectResourceDiscussion discussion() {
        return new ProjectResourceDiscussion(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "Customer analytics", "Java, React and QA", 4, ResourcePriority.HIGH,
                Instant.now().plus(1, ChronoUnit.DAYS), "Delivery capacity is below the committed sprint plan");
    }

    @Test
    void hrCanScheduleAndParticipantCanCompleteTheDiscussion() {
        ProjectResourceDiscussion discussion = discussion();
        Instant meetingAt = Instant.now().plus(2, ChronoUnit.DAYS);

        discussion.schedule("Meet in the HR cabin with the allocation sheet", meetingAt);
        assertThat(discussion.getStatus()).isEqualTo(ResourceDiscussionStatus.SCHEDULED);
        assertThat(discussion.getScheduledAt()).isEqualTo(meetingAt);

        discussion.complete();
        assertThat(discussion.getStatus()).isEqualTo(ResourceDiscussionStatus.COMPLETED);
        assertThat(discussion.getCompletedAt()).isNotNull();
    }

    @Test
    void informationRequestMustBeRevisedBeforeHrCanDecideAgain() {
        ProjectResourceDiscussion discussion = discussion();
        discussion.requestInformation("Add skill seniority and updated headcount");

        assertThatThrownBy(() -> discussion.schedule("Too early", Instant.now().plusSeconds(7200)))
                .isInstanceOf(BusinessException.class);

        discussion.revise("2 senior Java, 1 React, 1 QA", 4,
                Instant.now().plus(3, ChronoUnit.DAYS), "Updated delivery and seniority requirement");
        assertThat(discussion.getStatus()).isEqualTo(ResourceDiscussionStatus.REQUESTED);
    }
}
