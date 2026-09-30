package com.brainserve.appointment.employee.application;

import com.brainserve.appointment.audit.api.AuditService;
import com.brainserve.appointment.employee.domain.*;
import com.brainserve.appointment.employee.api.EmployeeLeaveEvents;
import com.brainserve.appointment.employee.infrastructure.EmployeeRepository;
import com.brainserve.appointment.employee.infrastructure.LeaveRequestRepository;
import com.brainserve.appointment.shared.application.BusinessException;
import com.brainserve.appointment.departmenthr.api.DepartmentHrDirectory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

@Service
public class LeaveRequestService {
    private final LeaveRequestRepository leaves; private final EmployeeRepository employees;
    private final ApplicationEventPublisher events; private final AuditService audit;
    private final DepartmentHrDirectory departmentHrs;
    private final ZoneId officeZone;
    public LeaveRequestService(LeaveRequestRepository leaves, EmployeeRepository employees,
                               ApplicationEventPublisher events, AuditService audit,
                               DepartmentHrDirectory departmentHrs,
                               @Value("${brainserve.appointment.office-zone:Asia/Kolkata}") String officeZone) {
        this.leaves = leaves; this.employees = employees; this.events = events; this.audit = audit;
        this.departmentHrs = departmentHrs;
        this.officeZone = ZoneId.of(officeZone);
    }
    @Transactional
    public LeaveRequest create(UUID userId, UUID employeeId, LocalDate from, LocalDate to, String reason) {
        Employee employee = employees.findById(employeeId).orElseThrow(this::notFound);
        if (!employee.isActiveForManagement()) throw new BusinessException("EMPLOYEE_NOT_ACTIVE",
                "Only an active employee can request leave", HttpStatus.UNPROCESSABLE_ENTITY);
        if (from.isBefore(LocalDate.now(officeZone))) throw new BusinessException("LEAVE_START_IN_PAST",
                "Leave cannot start in the past", HttpStatus.UNPROCESSABLE_ENTITY);
        LeaveRequest saved = leaves.saveAndFlush(new LeaveRequest(employeeId, userId, from, to, reason));
        events.publishEvent(new EmployeeLeaveEvents.LeaveRequested(saved.getId(), userId, employee.getDepartmentId(),
                employee.getDisplayName(), from, to, reason));
        audit.record("LEAVE_REQUEST_CREATED", "LEAVE_REQUEST", saved.getId().toString(), "{\"status\":\"PENDING\"}");
        return saved;
    }
    @Transactional(readOnly = true) public List<LeaveRequest> mine(UUID employeeId) { return leaves.findAllByEmployeeIdOrderByCreatedAtDesc(employeeId); }
    @Transactional(readOnly = true)
    public List<LeaveRequest> pending(UUID hrUserId) {
        UUID departmentId = departmentHrs.requireForUser(hrUserId).departmentId();
        return leaves.findPendingForDepartment(LeaveRequestStatus.PENDING, departmentId);
    }
    @Transactional
    public LeaveRequest decide(UUID id, UUID actor, LeaveRequestStatus decision, String remarks) {
        LeaveRequest leave = leaves.findById(id).orElseThrow(() -> new BusinessException("LEAVE_NOT_FOUND", "Leave request was not found", HttpStatus.NOT_FOUND));
        UUID employeeDepartmentId = employees.findById(leave.getEmployeeId()).orElseThrow(this::notFound).getDepartmentId();
        departmentHrs.requireAssignedReviewer(employeeDepartmentId, actor);
        leave.decide(decision, actor, remarks);
        events.publishEvent(new EmployeeLeaveEvents.LeaveDecided(id, actor, leave.getRequesterUserId(),
                decision.name(), leave.getStartDate(), leave.getEndDate()));
        audit.record("LEAVE_REQUEST_" + decision.name(), "LEAVE_REQUEST", id.toString(), "{\"status\":\"" + decision.name() + "\"}");
        return leave;
    }
    private BusinessException notFound() { return new BusinessException("EMPLOYEE_NOT_FOUND", "Employee was not found", HttpStatus.NOT_FOUND); }
}
