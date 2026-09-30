package com.brainserve.appointment.departmenthr.application;

import com.brainserve.appointment.audit.api.AuditService;
import com.brainserve.appointment.departmenthr.api.DepartmentHrDirectory;
import com.brainserve.appointment.departmenthr.api.DepartmentHrDirectory.TransferResolution;
import com.brainserve.appointment.departmenthr.domain.DepartmentHrAssignment;
import com.brainserve.appointment.departmenthr.infrastructure.DepartmentHrAssignmentRepository;
import com.brainserve.appointment.employee.api.EmployeeDirectory;
import com.brainserve.appointment.iam.api.StaffCommunicationDirectory;
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
public class DepartmentHrAssignmentService implements DepartmentHrDirectory {

    private static final String HR = "ROLE_HR_ADMIN";

    private final DepartmentHrAssignmentRepository assignments;
    private final OrganizationDirectory organization;
    private final EmployeeDirectory employees;
    private final StaffCommunicationDirectory staff;
    private final AuditService audit;

    public DepartmentHrAssignmentService(
            DepartmentHrAssignmentRepository assignments,
            OrganizationDirectory organization,
            EmployeeDirectory employees,
            StaffCommunicationDirectory staff,
            AuditService audit
    ) {
        this.assignments = assignments;
        this.organization = organization;
        this.employees = employees;
        this.staff = staff;
        this.audit = audit;
    }

    @Transactional
    public DepartmentHrAssignment assign(
            UUID actorUserId,
            UUID departmentId,
            UUID hrUserId
    ) {
        var targetDepartment = organization.lockActiveDepartment(departmentId);
        var hr = staff.requireActive(hrUserId);

        if (!hr.roles().contains(HR) || hr.employeeId() == null) {
            throw new BusinessException(
                    "DEPARTMENT_HR_ACCOUNT_REQUIRED",
                    "Select an active HR Admin account linked to an employee profile",
                    HttpStatus.UNPROCESSABLE_ENTITY
            );
        }

        employees.requireActiveEmployee(hr.employeeId());

        var currentForDepartment =
                assignments.findByDepartmentIdAndActiveTrue(departmentId);

        if (currentForDepartment.isPresent()
                && currentForDepartment.get().getHrUserId().equals(hrUserId)) {
            employees.transferDepartment(hr.employeeId(), departmentId);
            return currentForDepartment.get();
        }

        var currentForHr =
                assignments.findByHrUserIdAndActiveTrue(hrUserId);

        UUID previousDepartmentId = currentForHr
                .map(DepartmentHrAssignment::getDepartmentId)
                .orElse(null);

        currentForHr.ifPresent(value -> {
            value.end(actorUserId);
            assignments.saveAndFlush(value);
        });

        currentForDepartment.ifPresent(value -> {
            value.end(actorUserId);
            assignments.saveAndFlush(value);
        });

        employees.transferDepartment(hr.employeeId(), departmentId);

        var created = assignments.saveAndFlush(
                new DepartmentHrAssignment(
                        departmentId,
                        hrUserId,
                        hr.employeeId(),
                        actorUserId
                )
        );

        audit.record(
                "DEPARTMENT_HR_ASSIGNED",
                "DEPARTMENT",
                departmentId.toString(),
                "{\"hrUserId\":\"" + hrUserId
                        + "\",\"departmentCode\":\"" + targetDepartment.code()
                        + "\",\"previousDepartmentId\":"
                        + (previousDepartmentId == null
                        ? "null"
                        : "\"" + previousDepartmentId + "\"")
                        + "}"
        );

        return created;
    }

    /**
     * Public module API used by IAM during HR Admin onboarding.
     */
    @Override
    @Transactional
    public void assignForOnboarding(
            UUID actorUserId,
            UUID departmentId,
            UUID hrUserId
    ) {
        assign(actorUserId, departmentId, hrUserId);
    }

    @Transactional
    public DepartmentHrAssignment end(
            UUID actorUserId,
            UUID assignmentId
    ) {
        var assignment = assignments.findById(assignmentId)
                .orElseThrow(() -> new BusinessException(
                        "DEPARTMENT_HR_ASSIGNMENT_NOT_FOUND",
                        "Department HR assignment was not found",
                        HttpStatus.NOT_FOUND
                ));

        assignment.end(actorUserId);
        assignments.saveAndFlush(assignment);

        audit.record(
                "DEPARTMENT_HR_ASSIGNMENT_ENDED",
                "DEPARTMENT",
                assignment.getDepartmentId().toString(),
                "{\"hrUserId\":\"" + assignment.getHrUserId() + "\"}"
        );

        return assignment;
    }

    @Override
    @Transactional
    public void endForRoleTransition(
            UUID actorUserId,
            UUID hrUserId
    ) {
        assignments.findByHrUserIdAndActiveTrue(hrUserId)
                .ifPresent(assignment -> {
                    assignment.end(actorUserId);
                    assignments.saveAndFlush(assignment);
                    audit.record(
                            "DEPARTMENT_HR_ASSIGNMENT_ENDED_FOR_ROLE_TRANSITION",
                            "DEPARTMENT",
                            assignment.getDepartmentId().toString(),
                            "{\"hrUserId\":\"" + hrUserId + "\"}"
                    );
                });
    }

    @Override
    @Transactional
    public void assignForRoleTransition(
            UUID actorUserId,
            UUID departmentId,
            UUID hrUserId,
            UUID hrEmployeeId
    ) {
        StaffCommunicationDirectory.StaffMember hr = staff.requireActive(hrUserId);
        if (!hr.roles().contains(HR)
                || hr.employeeId() == null
                || !hr.employeeId().equals(hrEmployeeId)) {
            throw new BusinessException(
                    "DEPARTMENT_HR_EMPLOYEE_LINK_MISMATCH",
                    "The HR Admin account must be active and linked to the supplied employee profile",
                    HttpStatus.UNPROCESSABLE_ENTITY
            );
        }
        assign(actorUserId, departmentId, hrUserId);
    }

    @Override
    @Transactional
    public void replaceForAccountClosure(
            UUID actorUserId,
            UUID closingHrUserId,
            UUID replacementHrUserId
    ) {
        DepartmentHrAssignment current =
                assignments.findByHrUserIdAndActiveTrue(closingHrUserId)
                        .orElseThrow(() -> new BusinessException(
                                "HR_DEPARTMENT_NOT_ASSIGNED",
                                "Assign a department to this HR account before closing it",
                                HttpStatus.CONFLICT
                        ));

        if (closingHrUserId.equals(replacementHrUserId)) {
            throw new BusinessException(
                    "ACCOUNT_CLOSURE_REPLACEMENT_REQUIRED",
                    "Choose another active HR Admin as replacement",
                    HttpStatus.UNPROCESSABLE_ENTITY
            );
        }

        assign(
                actorUserId,
                current.getDepartmentId(),
                replacementHrUserId
        );

        audit.record(
                "DEPARTMENT_HR_REASSIGNED_FOR_ACCOUNT_CLOSURE",
                "DEPARTMENT",
                current.getDepartmentId().toString(),
                "{\"closingHrUserId\":\"" + closingHrUserId
                        + "\",\"replacementHrUserId\":\""
                        + replacementHrUserId + "\"}"
        );
    }

    @Transactional(readOnly = true)
    public List<DepartmentHrAssignment> history() {
        return assignments.findAllByOrderByAssignedAtDesc();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Assignment> activeForUser(UUID hrUserId) {
        return assignments.findByHrUserIdAndActiveTrue(hrUserId)
                .map(this::view);
    }

    @Override
    @Transactional
    public void transferApproved(
            UUID actorUserId,
            UUID hrUserId,
            UUID targetDepartmentId,
            TransferResolution resolution
    ) {
        var current =
                assignments.findByHrUserIdAndActiveTrue(hrUserId).orElse(null);

        var target =
                assignments.findByDepartmentIdAndActiveTrue(targetDepartmentId)
                        .orElse(null);

        if (current != null
                && current.getDepartmentId().equals(targetDepartmentId)) {
            return;
        }

        boolean conflict =
                target != null && !target.getHrUserId().equals(hrUserId);

        if (conflict && resolution == TransferResolution.SWAP) {

            if (current == null) {
                throw new BusinessException(
                        "HR_SWAP_SOURCE_REQUIRED",
                        "An unassigned HR cannot swap departments. Choose replace.",
                        HttpStatus.CONFLICT
                );
            }

            organization.requireActiveDepartment(targetDepartmentId);

            UUID sourceDepartmentId = current.getDepartmentId();

            current.end(actorUserId);
            assignments.saveAndFlush(current);

            target.end(actorUserId);
            assignments.saveAndFlush(target);

            employees.transferDepartment(
                    current.getHrEmployeeId(),
                    targetDepartmentId
            );

            employees.transferDepartment(
                    target.getHrEmployeeId(),
                    sourceDepartmentId
            );

            assignments.saveAndFlush(
                    new DepartmentHrAssignment(
                            targetDepartmentId,
                            current.getHrUserId(),
                            current.getHrEmployeeId(),
                            actorUserId
                    )
            );

            assignments.saveAndFlush(
                    new DepartmentHrAssignment(
                            sourceDepartmentId,
                            target.getHrUserId(),
                            target.getHrEmployeeId(),
                            actorUserId
                    )
            );

            audit.record(
                    "DEPARTMENT_HR_SWAPPED",
                    "DEPARTMENT",
                    targetDepartmentId.toString(),
                    "{\"requesterUserId\":\"" + hrUserId
                            + "\",\"otherHrUserId\":\""
                            + target.getHrUserId()
                            + "\",\"sourceDepartmentId\":\""
                            + sourceDepartmentId + "\"}"
            );

            return;
        }

        if (conflict && resolution != TransferResolution.REPLACE) {
            throw new BusinessException(
                    "HR_CHANGE_RESOLUTION_REQUIRED",
                    "The target department already has an HR Admin. Choose replace or swap.",
                    HttpStatus.CONFLICT
            );
        }

        assign(actorUserId, targetDepartmentId, hrUserId);
    }

    @Transactional(readOnly = true)
    public List<Candidate> candidates() {
        return staff.activeWithAnyRole(Set.of(HR))
                .stream()
                .filter(value -> value.employeeId() != null)
                .map(value -> {
                    var assignment =
                            assignments.findByHrUserIdAndActiveTrue(value.userId())
                                    .orElse(null);

                    var department = assignment == null
                            ? null
                            : organization.findDepartment(
                            assignment.getDepartmentId()
                    ).orElse(null);

                    return new Candidate(
                            value.userId(),
                            value.employeeId(),
                            value.fullName(),
                            value.email(),
                            assignment == null
                                    ? null
                                    : assignment.getDepartmentId(),
                            department == null
                                    ? null
                                    : department.code(),
                            department == null
                                    ? null
                                    : department.name()
                    );
                })
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Assignment> activeForDepartment(UUID departmentId) {
        return assignments.findByDepartmentIdAndActiveTrue(departmentId)
                .map(this::view);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Assignment> activeForHrEmployee(UUID hrEmployeeId) {
        return assignments.findByHrEmployeeIdAndActiveTrue(hrEmployeeId)
                .map(this::view);
    }

    @Override
    @Transactional(readOnly = true)
    public Assignment requireForDepartment(UUID departmentId) {
        return activeForDepartment(departmentId)
                .orElseThrow(() -> new BusinessException(
                        "DEPARTMENT_HR_NOT_ASSIGNED",
                        "Assign an active HR Admin to this department first",
                        HttpStatus.CONFLICT
                ));
    }

    @Override
    @Transactional(readOnly = true)
    public Assignment requireForUser(UUID hrUserId) {
        return assignments.findByHrUserIdAndActiveTrue(hrUserId)
                .map(this::view)
                .orElseThrow(() -> new BusinessException(
                        "HR_DEPARTMENT_NOT_ASSIGNED",
                        "No active department is assigned to this HR Admin",
                        HttpStatus.FORBIDDEN
                ));
    }

    @Override
    @Transactional(readOnly = true)
    public Assignment requireAssignedReviewer(
            UUID departmentId,
            UUID hrUserId
    ) {
        Assignment assignment = requireForDepartment(departmentId);

        if (!assignment.hrUserId().equals(hrUserId)) {
            throw new BusinessException(
                    "HR_DEPARTMENT_SCOPE_DENIED",
                    "This record belongs to another department HR",
                    HttpStatus.FORBIDDEN
            );
        }

        return assignment;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Assignment> activeAssignments() {
        return assignments.findAllByActiveTrueOrderByAssignedAtDesc()
                .stream()
                .map(this::view)
                .toList();
    }

    private Assignment view(DepartmentHrAssignment value) {
        var hr = staff.requireActive(value.getHrUserId());

        return new Assignment(
                value.getId(),
                value.getDepartmentId(),
                value.getHrUserId(),
                value.getHrEmployeeId(),
                hr.fullName(),
                hr.email()
        );
    }

    public record Candidate(
            UUID userId,
            UUID employeeId,
            String fullName,
            String email,
            UUID currentDepartmentId,
            String currentDepartmentCode,
            String currentDepartmentName
    ) {
    }
}
