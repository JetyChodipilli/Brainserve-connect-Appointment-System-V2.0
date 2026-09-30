package com.brainserve.appointment.employee.infrastructure;

import com.brainserve.appointment.employee.domain.LeaveRequest;
import com.brainserve.appointment.employee.domain.LeaveRequestStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface LeaveRequestRepository extends JpaRepository<LeaveRequest, UUID> {
    List<LeaveRequest> findAllByEmployeeIdOrderByCreatedAtDesc(UUID employeeId);
    List<LeaveRequest> findAllByStatusOrderByCreatedAtAsc(LeaveRequestStatus status);
    @Query("""
            select request
              from LeaveRequest request
              join Employee employee on employee.id = request.employeeId
             where request.status = :status
               and employee.departmentId = :departmentId
             order by request.createdAt asc
            """)
    List<LeaveRequest> findPendingForDepartment(@Param("status") LeaveRequestStatus status,
                                                @Param("departmentId") UUID departmentId);
    List<LeaveRequest> findAllByStartDateLessThanEqualAndEndDateGreaterThanEqualOrderByStartDateAsc(LocalDate to, LocalDate from);
}
