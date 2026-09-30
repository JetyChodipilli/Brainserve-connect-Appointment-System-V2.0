package com.brainserve.appointment.manager.infrastructure;

import com.brainserve.appointment.manager.domain.DepartmentManagerAssignment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DepartmentManagerAssignmentRepository
        extends JpaRepository<DepartmentManagerAssignment, UUID> {
    Optional<DepartmentManagerAssignment> findByDepartmentIdAndActiveTrue(UUID departmentId);
    Optional<DepartmentManagerAssignment> findByManagerUserIdAndActiveTrue(UUID managerUserId);
    Optional<DepartmentManagerAssignment> findByManagerEmployeeIdAndActiveTrue(UUID managerEmployeeId);
    List<DepartmentManagerAssignment> findAllByActiveTrueOrderByAssignedAtDesc();
    List<DepartmentManagerAssignment> findAllByOrderByAssignedAtDesc();
}
