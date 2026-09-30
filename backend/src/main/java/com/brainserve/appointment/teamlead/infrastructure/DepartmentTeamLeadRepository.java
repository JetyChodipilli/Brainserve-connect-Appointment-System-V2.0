package com.brainserve.appointment.teamlead.infrastructure;

import com.brainserve.appointment.teamlead.domain.DepartmentTeamLeadAssignment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DepartmentTeamLeadRepository extends JpaRepository<DepartmentTeamLeadAssignment, UUID> {
    Optional<DepartmentTeamLeadAssignment> findByDepartmentIdAndActiveTrue(UUID departmentId);
    Optional<DepartmentTeamLeadAssignment> findByTeamLeadUserIdAndActiveTrue(UUID userId);
    Optional<DepartmentTeamLeadAssignment> findByTeamLeadEmployeeIdAndActiveTrue(UUID employeeId);
    List<DepartmentTeamLeadAssignment> findAllByActiveTrueOrderByAssignedAtAsc();
    List<DepartmentTeamLeadAssignment> findAllByOrderByAssignedAtDesc();
}
