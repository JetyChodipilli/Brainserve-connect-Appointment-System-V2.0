package com.brainserve.appointment.rolechange.infrastructure;

import com.brainserve.appointment.rolechange.domain.RoleDepartmentChangeRequest;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface RoleDepartmentChangeRequestRepository extends JpaRepository<RoleDepartmentChangeRequest, UUID> {
    boolean existsByRequesterUserIdAndStatus(UUID requesterUserId, RoleDepartmentChangeRequest.Status status);
    List<RoleDepartmentChangeRequest> findTop50ByRequesterUserIdOrderByRequestedAtDesc(UUID requesterUserId);
    List<RoleDepartmentChangeRequest> findAllByStatusOrderByRequestedAtAsc(RoleDepartmentChangeRequest.Status status);
}
