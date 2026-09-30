package com.brainserve.appointment.employee.application;

import com.brainserve.appointment.employee.api.EmployeeRecords;
import com.brainserve.appointment.employee.infrastructure.EmployeeRepository;
import com.brainserve.appointment.employee.infrastructure.LeaveRequestRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

@Service
public class EmployeeRecordService implements EmployeeRecords {
    private final EmployeeRepository employees;
    private final LeaveRequestRepository leaves;
    public EmployeeRecordService(EmployeeRepository employees, LeaveRequestRepository leaves) {
        this.employees = employees; this.leaves = leaves;
    }
    @Override @Transactional(readOnly = true)
    public List<EmployeeRecord> allEmployees() {
        return employees.findAll().stream().map(value -> new EmployeeRecord(value.getId(), value.getEmployeeNumber(),
                value.getDisplayName(), value.getOfficialEmail(), value.getDesignation(), value.getStatus().name(),
                value.getJoiningDate(), value.getRelievingDate())).toList();
    }
    @Override @Transactional(readOnly = true)
    public List<LeaveRecord> leavesOverlapping(LocalDate from, LocalDate to) {
        return leaves.findAllByStartDateLessThanEqualAndEndDateGreaterThanEqualOrderByStartDateAsc(to, from).stream()
                .map(value -> new LeaveRecord(value.getId(), value.getEmployeeId(), value.getRequesterUserId(),
                        value.getStartDate(), value.getEndDate(), value.getReason(), value.getStatus().name(),
                        value.getDecidedByUserId(), value.getDecidedAt(), value.getDecisionReason())).toList();
    }
}
