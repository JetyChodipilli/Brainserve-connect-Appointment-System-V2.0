package com.brainserve.appointment.teamlead.application;

import com.brainserve.appointment.audit.api.AuditService;
import com.brainserve.appointment.departmenthr.api.DepartmentHrDirectory;
import com.brainserve.appointment.employee.api.EmployeeDirectory;
import com.brainserve.appointment.iam.api.TeamLeadIdentityService;
import com.brainserve.appointment.organization.api.OrganizationDirectory;
import com.brainserve.appointment.shared.application.BusinessException;
import com.brainserve.appointment.teamlead.api.TeamLeadDirectory;
import com.brainserve.appointment.teamlead.api.TeamLeadDirectory.TransferResolution;
import com.brainserve.appointment.teamlead.api.TeamLeadEvents;
import com.brainserve.appointment.teamlead.domain.DepartmentTeamLeadAssignment;
import com.brainserve.appointment.teamlead.infrastructure.DepartmentTeamLeadRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class TeamLeadAssignmentService implements TeamLeadDirectory {

    private final DepartmentTeamLeadRepository assignments;
    private final EmployeeDirectory employees;
    private final OrganizationDirectory organization;
    private final TeamLeadIdentityService identities;
    private final AuditService audit;
    private final ApplicationEventPublisher events;
    private final DepartmentHrDirectory departmentHrs;

    public TeamLeadAssignmentService(
            DepartmentTeamLeadRepository assignments,
            EmployeeDirectory employees,
            OrganizationDirectory organization,
            TeamLeadIdentityService identities,
            AuditService audit,
            ApplicationEventPublisher events,
            DepartmentHrDirectory departmentHrs
    ) {
        this.assignments = assignments;
        this.employees = employees;
        this.organization = organization;
        this.identities = identities;
        this.audit = audit;
        this.events = events;
        this.departmentHrs = departmentHrs;
    }

    @Transactional
    public DepartmentTeamLeadAssignment assign(
            UUID actorUserId,
            UUID departmentId,
            UUID employeeId
    ) {
        if (!departmentHrs.requireForUser(actorUserId)
                .departmentId()
                .equals(departmentId)) {
            throw assignmentScopeDenied();
        }

        return assignWithinDepartment(
                actorUserId,
                departmentId,
                employeeId
        );
    }

    private DepartmentTeamLeadAssignment assignWithinDepartment(
            UUID actorUserId,
            UUID departmentId,
            UUID employeeId
    ) {
        organization.lockActiveDepartment(departmentId);
        employees.requireActiveEmployee(employeeId);

        if (!employees.departmentIdForEmployee(employeeId)
                .equals(departmentId)) {
            throw new BusinessException(
                    "TEAM_LEAD_DEPARTMENT_MISMATCH",
                    "The Team Lead must be an employee of the selected department",
                    HttpStatus.UNPROCESSABLE_ENTITY
            );
        }

        Optional<DepartmentTeamLeadAssignment> current =
                assignments.findByDepartmentIdAndActiveTrue(departmentId);

        /*
         * If this department already points to the same employee, do not return
         * blindly. Older/inconsistent data can contain an active department
         * Team Lead assignment while the linked IAM account is still
         * ROLE_EMPLOYEE. Reconcile the IAM identity first so the user signs in
         * as ROLE_TEAM_LEAD and receives department-scoped access.
         */
        if (current.isPresent()
                && current.get()
                .getTeamLeadEmployeeId()
                .equals(employeeId)) {

            DepartmentTeamLeadAssignment existing = current.get();

            TeamLeadIdentityService.TeamLeadIdentity identity =
                    identities.activeByEmployeeId(employeeId)
                            .orElseGet(() ->
                                    identities.promoteActiveEmployee(employeeId)
                            );

            if (!existing.getTeamLeadUserId().equals(identity.userId())) {
                throw new BusinessException(
                        "TEAM_LEAD_ASSIGNMENT_IDENTITY_MISMATCH",
                        "The existing Team Lead assignment is linked to another user account",
                        HttpStatus.CONFLICT
                );
            }

            return existing;
        }

        assignments.findByTeamLeadEmployeeIdAndActiveTrue(employeeId)
                .ifPresent(value -> {
                    throw new BusinessException(
                            "TEAM_LEAD_ALREADY_ASSIGNED",
                            "This employee already leads another department",
                            HttpStatus.CONFLICT
                    );
                });

        current.ifPresent(value -> {
            value.end(actorUserId);
            assignments.saveAndFlush(value);
            identities.demote(value.getTeamLeadUserId());
        });

        TeamLeadIdentityService.TeamLeadIdentity identity =
                identities.promoteActiveEmployee(employeeId);

        DepartmentTeamLeadAssignment created = assignments.saveAndFlush(
                new DepartmentTeamLeadAssignment(
                        departmentId,
                        identity.userId(),
                        employeeId,
                        actorUserId
                )
        );

        audit.record(
                "TEAM_LEAD_ASSIGNED",
                "DEPARTMENT",
                departmentId.toString(),
                "{\"employeeId\":\"" + employeeId + "\"}"
        );

        return created;
    }

    @Transactional
    public DepartmentTeamLeadAssignment end(
            UUID actorUserId,
            UUID assignmentId
    ) {
        DepartmentTeamLeadAssignment assignment = assignments
                .findById(assignmentId)
                .orElseThrow(() -> new BusinessException(
                        "TEAM_LEAD_ASSIGNMENT_NOT_FOUND",
                        "Team Lead assignment was not found",
                        HttpStatus.NOT_FOUND
                ));

        if (!departmentHrs.requireForUser(actorUserId)
                .departmentId()
                .equals(assignment.getDepartmentId())) {
            throw assignmentScopeDenied();
        }

        assignment.end(actorUserId);
        identities.demote(assignment.getTeamLeadUserId());

        audit.record(
                "TEAM_LEAD_ASSIGNMENT_ENDED",
                "DEPARTMENT",
                assignment.getDepartmentId().toString(),
                "{\"employeeId\":\""
                        + assignment.getTeamLeadEmployeeId()
                        + "\"}"
        );

        return assignment;
    }

    @Override
    @Transactional
    public void endForEmployeeIfAssigned(
            UUID actorUserId,
            UUID employeeId
    ) {
        assignments.findByTeamLeadEmployeeIdAndActiveTrue(employeeId)
                .ifPresent(assignment -> {
                    assignment.end(actorUserId);
                    assignments.saveAndFlush(assignment);
                    identities.demote(assignment.getTeamLeadUserId());

                    audit.record(
                            "TEAM_LEAD_ASSIGNMENT_ENDED_FOR_TERMINATION",
                            "DEPARTMENT",
                            assignment.getDepartmentId().toString(),
                            "{\"employeeId\":\"" + employeeId + "\"}"
                    );
                });
    }

    @Override
    @Transactional
    public void endForRoleTransition(
            UUID actorUserId,
            UUID teamLeadUserId
    ) {
        assignments.findByTeamLeadUserIdAndActiveTrue(teamLeadUserId)
                .ifPresent(assignment -> {
                    assignment.end(actorUserId);
                    assignments.saveAndFlush(assignment);
                    audit.record(
                            "TEAM_LEAD_ASSIGNMENT_ENDED_FOR_ROLE_TRANSITION",
                            "DEPARTMENT",
                            assignment.getDepartmentId().toString(),
                            "{\"teamLeadUserId\":\"" + teamLeadUserId + "\"}"
                    );
                });
    }

    @Override
    @Transactional
    public void assignForRoleTransition(
            UUID actorUserId,
            UUID departmentId,
            UUID teamLeadUserId,
            UUID teamLeadEmployeeId
    ) {
        organization.lockActiveDepartment(departmentId);
        employees.requireActiveEmployee(teamLeadEmployeeId);

        if (!departmentId.equals(employees.departmentIdForEmployee(teamLeadEmployeeId))) {
            throw new BusinessException(
                    "TEAM_LEAD_DEPARTMENT_MISMATCH",
                    "The Team Lead employee profile must belong to the selected department",
                    HttpStatus.UNPROCESSABLE_ENTITY
            );
        }

        TeamLeadIdentityService.TeamLeadIdentity identity = identities
                .activeByUserId(teamLeadUserId)
                .orElseThrow(() -> new BusinessException(
                        "TEAM_LEAD_ACCOUNT_NOT_ACTIVE",
                        "The transitioned Team Lead account is not active",
                        HttpStatus.UNPROCESSABLE_ENTITY
                ));

        if (!teamLeadEmployeeId.equals(identity.employeeId())) {
            throw new BusinessException(
                    "TEAM_LEAD_EMPLOYEE_LINK_MISMATCH",
                    "The Team Lead account is linked to another employee profile",
                    HttpStatus.CONFLICT
            );
        }

        Optional<DepartmentTeamLeadAssignment> currentForUser =
                assignments.findByTeamLeadUserIdAndActiveTrue(teamLeadUserId);
        if (currentForUser.isPresent()
                && currentForUser.get().getDepartmentId().equals(departmentId)
                && currentForUser.get().getTeamLeadEmployeeId().equals(teamLeadEmployeeId)) {
            return;
        }

        currentForUser.ifPresent(value -> {
            value.end(actorUserId);
            assignments.saveAndFlush(value);
        });

        assignments.findByDepartmentIdAndActiveTrue(departmentId)
                .filter(value -> !value.getTeamLeadUserId().equals(teamLeadUserId))
                .ifPresent(value -> {
                    value.end(actorUserId);
                    assignments.saveAndFlush(value);
                });

        assignments.saveAndFlush(new DepartmentTeamLeadAssignment(
                departmentId, teamLeadUserId, teamLeadEmployeeId, actorUserId));

        audit.record(
                "TEAM_LEAD_ASSIGNED_FOR_ROLE_TRANSITION",
                "DEPARTMENT",
                departmentId.toString(),
                "{\"teamLeadUserId\":\"" + teamLeadUserId
                        + "\",\"employeeId\":\"" + teamLeadEmployeeId + "\"}"
        );
    }

    @Override
    @Transactional
    public void replaceForAccountClosure(
            UUID actorUserId,
            UUID closingTeamLeadUserId,
            UUID replacementEmployeeId
    ) {
        DepartmentTeamLeadAssignment current = assignments
                .findByTeamLeadUserIdAndActiveTrue(closingTeamLeadUserId)
                .orElseThrow(() -> new BusinessException(
                        "TEAM_LEAD_ASSIGNMENT_NOT_FOUND",
                        "No active department is assigned to this Team Lead",
                        HttpStatus.CONFLICT
                ));

        if (current.getTeamLeadEmployeeId()
                .equals(replacementEmployeeId)) {
            throw new BusinessException(
                    "ACCOUNT_CLOSURE_REPLACEMENT_REQUIRED",
                    "Choose another employee as the replacement Team Lead",
                    HttpStatus.UNPROCESSABLE_ENTITY
            );
        }

        DepartmentTeamLeadAssignment replacement =
                assignWithinDepartment(
                        actorUserId,
                        current.getDepartmentId(),
                        replacementEmployeeId
                );

        events.publishEvent(
                new TeamLeadEvents.ReassignedForAccountClosure(
                        actorUserId,
                        current.getDepartmentId(),
                        closingTeamLeadUserId,
                        replacement.getTeamLeadUserId()
                )
        );

        audit.record(
                "TEAM_LEAD_REASSIGNED_FOR_ACCOUNT_CLOSURE",
                "DEPARTMENT",
                current.getDepartmentId().toString(),
                "{\"closingTeamLeadUserId\":\""
                        + closingTeamLeadUserId
                        + "\",\"replacementEmployeeId\":\""
                        + replacementEmployeeId
                        + "\"}"
        );
    }

    @Transactional(readOnly = true)
    public List<DepartmentTeamLeadAssignment> history() {
        return assignments.findAllByOrderByAssignedAtDesc();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Assignment> activeForDepartment(UUID departmentId) {
        return assignments.findByDepartmentIdAndActiveTrue(departmentId)
                .flatMap(this::activeView);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Assignment> activeForUser(UUID teamLeadUserId) {
        return assignments.findByTeamLeadUserIdAndActiveTrue(teamLeadUserId)
                .flatMap(this::activeView);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Assignment> activeAssignments() {
        return assignments.findAllByActiveTrueOrderByAssignedAtAsc()
                .stream()
                .map(this::activeView)
                .flatMap(Optional::stream)
                .toList();
    }

    @Override
    @Transactional
    public void transferApproved(
            UUID actorUserId,
            UUID teamLeadUserId,
            UUID targetDepartmentId,
            TransferResolution resolution
    ) {
        organization.requireActiveDepartment(targetDepartmentId);

        DepartmentTeamLeadAssignment current = assignments
                .findByTeamLeadUserIdAndActiveTrue(teamLeadUserId)
                .orElseThrow(() -> new BusinessException(
                        "TEAM_LEAD_ASSIGNMENT_NOT_FOUND",
                        "No active department is assigned to this Team Lead",
                        HttpStatus.NOT_FOUND
                ));

        if (current.getDepartmentId().equals(targetDepartmentId)) {
            return;
        }

        DepartmentTeamLeadAssignment target = assignments
                .findByDepartmentIdAndActiveTrue(targetDepartmentId)
                .orElse(null);

        boolean conflict = target != null
                && !target.getTeamLeadUserId().equals(teamLeadUserId);

        if (conflict
                && resolution != TransferResolution.REPLACE
                && resolution != TransferResolution.SWAP) {
            throw new BusinessException(
                    "TEAM_LEAD_CHANGE_RESOLUTION_REQUIRED",
                    "The target department already has a Team Lead. Choose replace or swap.",
                    HttpStatus.CONFLICT
            );
        }

        if (!conflict && resolution == TransferResolution.SWAP) {
            throw new BusinessException(
                    "TEAM_LEAD_SWAP_TARGET_REQUIRED",
                    "Swap requires an active Team Lead in the target department",
                    HttpStatus.CONFLICT
            );
        }

        UUID sourceDepartmentId = current.getDepartmentId();

        current.end(actorUserId);
        assignments.saveAndFlush(current);

        if (target != null) {
            target.end(actorUserId);
            assignments.saveAndFlush(target);
        }

        employees.transferDepartment(
                current.getTeamLeadEmployeeId(),
                targetDepartmentId
        );

        assignments.saveAndFlush(
                new DepartmentTeamLeadAssignment(
                        targetDepartmentId,
                        current.getTeamLeadUserId(),
                        current.getTeamLeadEmployeeId(),
                        actorUserId
                )
        );

        if (target != null && resolution == TransferResolution.SWAP) {
            employees.transferDepartment(
                    target.getTeamLeadEmployeeId(),
                    sourceDepartmentId
            );

            assignments.saveAndFlush(
                    new DepartmentTeamLeadAssignment(
                            sourceDepartmentId,
                            target.getTeamLeadUserId(),
                            target.getTeamLeadEmployeeId(),
                            actorUserId
                    )
            );
        } else if (target != null) {
            identities.demote(target.getTeamLeadUserId());
        }

        audit.record(
                "TEAM_LEAD_DEPARTMENT_CHANGED",
                "DEPARTMENT",
                targetDepartmentId.toString(),
                "{\"teamLeadUserId\":\""
                        + teamLeadUserId
                        + "\",\"fromDepartmentId\":\""
                        + sourceDepartmentId
                        + "\",\"resolution\":\""
                        + resolution
                        + "\"}"
        );
    }

    @Transactional(readOnly = true)
    public Workspace workspace(
            UUID teamLeadUserId,
            Pageable pageable
    ) {
        Assignment assignment = requireForUser(teamLeadUserId);

        OrganizationDirectory.ActiveDepartment department =
                organization.requireActiveDepartment(
                        assignment.departmentId()
                );

        return new Workspace(
                assignment,
                department,
                employees.departmentMembers(
                        assignment.departmentId(),
                        pageable
                )
        );
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Assignment> activeForHost(UUID hostEmployeeId) {
        UUID departmentId =
                employees.departmentIdForEmployee(hostEmployeeId);

        return assignments.findByDepartmentIdAndActiveTrue(departmentId)
                .flatMap(this::activeView);
    }

    @Override
    @Transactional(readOnly = true)
    public Assignment requireAssignedForHost(
            UUID teamLeadUserId,
            UUID hostEmployeeId
    ) {
        Assignment assignment = activeForHost(hostEmployeeId)
                .orElseThrow(() -> new BusinessException(
                        "TEAM_LEAD_NOT_ASSIGNED",
                        "The host department has no active Team Lead",
                        HttpStatus.CONFLICT
                ));

        if (!assignment.teamLeadUserId().equals(teamLeadUserId)) {
            throw new BusinessException(
                    "TEAM_LEAD_SCOPE_DENIED",
                    "This appointment belongs to another department",
                    HttpStatus.FORBIDDEN
            );
        }

        return assignment;
    }

    @Override
    @Transactional(readOnly = true)
    public Assignment requireForUser(UUID teamLeadUserId) {
        DepartmentTeamLeadAssignment assignment = assignments
                .findByTeamLeadUserIdAndActiveTrue(teamLeadUserId)
                .orElseThrow(() -> new BusinessException(
                        "TEAM_LEAD_ASSIGNMENT_NOT_FOUND",
                        "No active department is assigned to this Team Lead",
                        HttpStatus.NOT_FOUND
                ));

        return activeView(assignment)
                .orElseThrow(() -> new BusinessException(
                        "TEAM_LEAD_ACCOUNT_NOT_ACTIVE",
                        "The Team Lead account is not active",
                        HttpStatus.FORBIDDEN
                ));
    }

    private Optional<Assignment> activeView(
            DepartmentTeamLeadAssignment value
    ) {
        return identities.activeByUserId(value.getTeamLeadUserId())
                .map(identity -> new Assignment(
                        value.getId(),
                        value.getDepartmentId(),
                        identity.userId(),
                        identity.employeeId(),
                        identity.fullName(),
                        identity.email()
                ));
    }

    private BusinessException assignmentScopeDenied() {
        return new BusinessException(
                "TEAM_LEAD_ASSIGNMENT_DEPARTMENT_SCOPE_DENIED",
                "HR may manage Team Lead access only for their assigned department",
                HttpStatus.FORBIDDEN
        );
    }

    public record Workspace(
            Assignment assignment,
            OrganizationDirectory.ActiveDepartment department,
            org.springframework.data.domain.Page<
                    EmployeeDirectory.DepartmentMember> employees
    ) {
    }
}
