package com.brainserve.appointment.departmenthr.infrastructure;

import com.brainserve.appointment.departmenthr.domain.DepartmentHrAssignment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DepartmentHrAssignmentRepository extends JpaRepository<DepartmentHrAssignment, UUID> {
    Optional<DepartmentHrAssignment> findByDepartmentIdAndActiveTrue(UUID departmentId);
    Optional<DepartmentHrAssignment> findByHrUserIdAndActiveTrue(UUID hrUserId);
    Optional<DepartmentHrAssignment> findByHrEmployeeIdAndActiveTrue(UUID employeeId);
    List<DepartmentHrAssignment> findAllByOrderByAssignedAtDesc();
    List<DepartmentHrAssignment> findAllByActiveTrueOrderByAssignedAtDesc();
}
