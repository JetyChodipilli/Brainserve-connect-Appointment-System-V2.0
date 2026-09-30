package com.brainserve.appointment.manager.application;

import com.brainserve.appointment.audit.api.AuditService;
import com.brainserve.appointment.employee.api.EmployeeDirectory;
import com.brainserve.appointment.iam.api.StaffCommunicationDirectory;
import com.brainserve.appointment.manager.api.ManagerDirectory;
import com.brainserve.appointment.manager.domain.DepartmentManagerAssignment;
import com.brainserve.appointment.manager.infrastructure.DepartmentManagerAssignmentRepository;
import com.brainserve.appointment.organization.api.OrganizationDirectory;
import com.brainserve.appointment.shared.application.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
public class ManagerAssignmentService implements ManagerDirectory {
    private static final String MANAGER = "ROLE_MANAGER";
    private final DepartmentManagerAssignmentRepository assignments;
    private final OrganizationDirectory organization;
    private final EmployeeDirectory employees;
    private final StaffCommunicationDirectory staff;
    private final AuditService audit;

    public ManagerAssignmentService(DepartmentManagerAssignmentRepository assignments,
                                    OrganizationDirectory organization, EmployeeDirectory employees,
                                    StaffCommunicationDirectory staff, AuditService audit) {
        this.assignments = assignments;
        this.organization = organization;
        this.employees = employees;
        this.staff = staff;
        this.audit = audit;
    }

    @Transactional
    public DepartmentManagerAssignment assign(UUID actorUserId, UUID departmentId, UUID managerUserId) {
        organization.lockActiveDepartment(departmentId);
        var manager = staff.requireActive(managerUserId);
        if (!manager.roles().equals(Set.of(MANAGER)) || manager.employeeId() == null) {
            throw new BusinessException("DEPARTMENT_MANAGER_ACCOUNT_REQUIRED",
                    "Select an active Manager account linked to one employee profile",
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }
        employees.requireActiveEmployee(manager.employeeId());
        var currentForDepartment = assignments.findByDepartmentIdAndActiveTrue(departmentId);
        if (currentForDepartment.isPresent()
                && currentForDepartment.get().getManagerUserId().equals(managerUserId)) {
            employees.transferDepartment(manager.employeeId(), departmentId);
            return currentForDepartment.get();
        }
        assignments.findByManagerUserIdAndActiveTrue(managerUserId).ifPresent(value -> {
            value.end(actorUserId);
            assignments.saveAndFlush(value);
        });
        currentForDepartment.ifPresent(value -> {
            value.end(actorUserId);
            assignments.saveAndFlush(value);
        });
        employees.transferDepartment(manager.employeeId(), departmentId);
        DepartmentManagerAssignment created = assignments.saveAndFlush(new DepartmentManagerAssignment(
                departmentId, managerUserId, manager.employeeId(), actorUserId));
        audit.record("DEPARTMENT_MANAGER_ASSIGNED", "DEPARTMENT", departmentId.toString(),
                "{\"managerUserId\":\"" + managerUserId + "\"}");
        return created;
    }

    @Override
    @Transactional
    public void assignForOnboarding(UUID actorUserId, UUID departmentId, UUID managerUserId) {
        assign(actorUserId, departmentId, managerUserId);
    }

    @Transactional
    public DepartmentManagerAssignment end(UUID actorUserId, UUID assignmentId) {
        DepartmentManagerAssignment assignment = assignments.findById(assignmentId).orElseThrow(() ->
                new BusinessException("DEPARTMENT_MANAGER_ASSIGNMENT_NOT_FOUND",
                        "Department Manager assignment was not found", HttpStatus.NOT_FOUND));
        assignment.end(actorUserId);
        audit.record("DEPARTMENT_MANAGER_ASSIGNMENT_ENDED", "DEPARTMENT",
                assignment.getDepartmentId().toString(),
                "{\"managerUserId\":\"" + assignment.getManagerUserId() + "\"}");
        return assignment;
    }

    @Override
    @Transactional
    public void endForRoleTransition(UUID actorUserId, UUID managerUserId) {
        assignments.findByManagerUserIdAndActiveTrue(managerUserId)
                .ifPresent(assignment -> {
                    assignment.end(actorUserId);
                    assignments.saveAndFlush(assignment);
                    audit.record("DEPARTMENT_MANAGER_ASSIGNMENT_ENDED_FOR_ROLE_TRANSITION",
                            "DEPARTMENT", assignment.getDepartmentId().toString(),
                            "{\"managerUserId\":\"" + managerUserId + "\"}");
                });
    }

    @Override
    @Transactional
    public void assignForRoleTransition(UUID actorUserId, UUID departmentId, UUID managerUserId,
                                        UUID managerEmployeeId) {
        StaffCommunicationDirectory.StaffMember manager = staff.requireActive(managerUserId);
        if (!manager.roles().equals(Set.of(MANAGER))
                || manager.employeeId() == null
                || !manager.employeeId().equals(managerEmployeeId)) {
            throw new BusinessException("DEPARTMENT_MANAGER_EMPLOYEE_LINK_MISMATCH",
                    "The Manager account must be active and linked to the supplied employee profile",
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }
        assign(actorUserId, departmentId, managerUserId);
    }

    @Override
    @Transactional
    public void replaceForAccountClosure(UUID actorUserId, UUID closingManagerUserId,
                                         UUID replacementManagerUserId) {
        DepartmentManagerAssignment current = assignments
                .findByManagerUserIdAndActiveTrue(closingManagerUserId)
                .orElseThrow(() -> new BusinessException("MANAGER_DEPARTMENT_NOT_ASSIGNED",
                        "Assign a department to this Manager before closing the account",
                        HttpStatus.CONFLICT));
        if (closingManagerUserId.equals(replacementManagerUserId)) {
            throw new BusinessException("ACCOUNT_CLOSURE_REPLACEMENT_REQUIRED",
                    "Choose another active Manager as replacement",
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }
        assign(actorUserId, current.getDepartmentId(), replacementManagerUserId);
        audit.record("DEPARTMENT_MANAGER_REASSIGNED_FOR_ACCOUNT_CLOSURE", "DEPARTMENT",
                current.getDepartmentId().toString(),
                "{\"closingManagerUserId\":\"" + closingManagerUserId
                        + "\",\"replacementManagerUserId\":\"" + replacementManagerUserId + "\"}");
    }

    @Transactional(readOnly = true)
    public List<DepartmentManagerAssignment> history() {
        return assignments.findAllByOrderByAssignedAtDesc();
    }

    @Transactional(readOnly = true)
    public List<Candidate> candidates() {
        return staff.activeWithAnyRole(Set.of(MANAGER)).stream()
                .filter(value -> value.employeeId() != null)
                .map(value -> {
                    var assignment = assignments.findByManagerUserIdAndActiveTrue(value.userId()).orElse(null);
                    var department = assignment == null ? null
                            : organization.findDepartment(assignment.getDepartmentId()).orElse(null);
                    return new Candidate(value.userId(), value.employeeId(), value.fullName(), value.email(),
                            assignment == null ? null : assignment.getDepartmentId(),
                            department == null ? null : department.code(),
                            department == null ? null : department.name());
                }).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Assignment> activeForDepartment(UUID departmentId) {
        return assignments.findByDepartmentIdAndActiveTrue(departmentId).map(this::view);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Assignment> activeForUser(UUID managerUserId) {
        return assignments.findByManagerUserIdAndActiveTrue(managerUserId).map(this::view);
    }

    @Override
    @Transactional(readOnly = true)
    public Assignment requireForDepartment(UUID departmentId) {
        return activeForDepartment(departmentId).orElseThrow(() -> new BusinessException(
                "DEPARTMENT_MANAGER_NOT_ASSIGNED",
                "Assign an active Manager to this department before routing a CEO visit",
                HttpStatus.CONFLICT));
    }

    @Override
    @Transactional(readOnly = true)
    public Assignment requireForUser(UUID managerUserId) {
        return activeForUser(managerUserId).orElseThrow(() -> new BusinessException(
                "MANAGER_DEPARTMENT_NOT_ASSIGNED",
                "No active department is assigned to this Manager", HttpStatus.FORBIDDEN));
    }

    @Override
    @Transactional(readOnly = true)
    public Assignment requireAssignedReviewer(UUID departmentId, UUID managerUserId) {
        Assignment assignment = requireForDepartment(departmentId);
        if (!assignment.managerUserId().equals(managerUserId)) {
            throw new BusinessException("MANAGER_DEPARTMENT_SCOPE_DENIED",
                    "This CEO visit belongs to another department Manager", HttpStatus.FORBIDDEN);
        }
        return assignment;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Assignment> activeAssignments() {
        return assignments.findAllByActiveTrueOrderByAssignedAtDesc().stream().map(this::view).toList();
    }

    private Assignment view(DepartmentManagerAssignment value) {
        var manager = staff.requireActive(value.getManagerUserId());
        return new Assignment(value.getId(), value.getDepartmentId(), value.getManagerUserId(),
                value.getManagerEmployeeId(), manager.fullName(), manager.email());
    }

    public record Candidate(UUID userId, UUID employeeId, String fullName, String email,
                            UUID currentDepartmentId, String currentDepartmentCode,
                            String currentDepartmentName) {}
}
