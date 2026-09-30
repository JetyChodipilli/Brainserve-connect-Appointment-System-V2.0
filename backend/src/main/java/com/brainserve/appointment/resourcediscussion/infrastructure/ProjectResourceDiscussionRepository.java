package com.brainserve.appointment.resourcediscussion.infrastructure;

import com.brainserve.appointment.resourcediscussion.domain.ProjectResourceDiscussion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ProjectResourceDiscussionRepository extends JpaRepository<ProjectResourceDiscussion, UUID> {
    List<ProjectResourceDiscussion> findTop100ByRequestedByUserIdOrderByCreatedAtDesc(UUID userId);
    List<ProjectResourceDiscussion> findTop100ByHrRecipientUserIdOrderByCreatedAtDesc(UUID userId);
    List<ProjectResourceDiscussion> findTop100ByOrderByCreatedAtDesc();
}
