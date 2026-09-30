package com.brainserve.appointment.employee.infrastructure;

import com.brainserve.appointment.employee.domain.EmployeeTerminationRequest;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface EmployeeTerminationRequestRepository extends JpaRepository<EmployeeTerminationRequest, UUID> {
    boolean existsByEmployeeIdAndStatus(UUID employeeId, EmployeeTerminationRequest.Status status);
    List<EmployeeTerminationRequest> findTop100ByRequestedByHrUserIdOrderByRequestedAtDesc(UUID hrUserId);
    List<EmployeeTerminationRequest> findAllByStatusOrderByRequestedAtAsc(EmployeeTerminationRequest.Status status);
    List<EmployeeTerminationRequest> findTop100ByOrderByRequestedAtDesc();
}
