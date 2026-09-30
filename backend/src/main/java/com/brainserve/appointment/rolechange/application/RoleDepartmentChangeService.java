package com.brainserve.appointment.rolechange.application;

import com.brainserve.appointment.audit.api.AuditService;
import com.brainserve.appointment.departmenthr.api.DepartmentHrDirectory;
import com.brainserve.appointment.employee.api.EmployeeDirectory;
import com.brainserve.appointment.employee.api.EmployeeProfileProvisioning;
import com.brainserve.appointment.iam.api.StaffCommunicationDirectory;
import com.brainserve.appointment.notification.api.InternalNotificationGateway;
import com.brainserve.appointment.organization.api.OrganizationDirectory;
import com.brainserve.appointment.rolechange.domain.RoleDepartmentChangeRequest;
import com.brainserve.appointment.rolechange.infrastructure.RoleDepartmentChangeRequestRepository;
import com.brainserve.appointment.shared.application.BusinessException;
import com.brainserve.appointment.teamlead.api.TeamLeadDirectory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Service
public class RoleDepartmentChangeService {

    private static final String CEO = "ROLE_CEO";
    private static final String HR = "ROLE_HR_ADMIN";
    private static final String TEAM_LEAD = "ROLE_TEAM_LEAD";

    private final RoleDepartmentChangeRequestRepository requests;
    private final StaffCommunicationDirectory staff;
    private final OrganizationDirectory organization;
    private final EmployeeDirectory employees;
    private final DepartmentHrDirectory departmentHrs;
    private final TeamLeadDirectory teamLeads;
    private final EmployeeProfileProvisioning employeeProfiles;
    private final InternalNotificationGateway notifications;
    private final AuditService audit;

    public RoleDepartmentChangeService(
            RoleDepartmentChangeRequestRepository requests,
            StaffCommunicationDirectory staff,
            OrganizationDirectory organization,
            EmployeeDirectory employees,
            DepartmentHrDirectory departmentHrs,
            TeamLeadDirectory teamLeads,
            EmployeeProfileProvisioning employeeProfiles,
            InternalNotificationGateway notifications,
            AuditService audit
    ) {
        this.requests = requests;
        this.staff = staff;
        this.organization = organization;
        this.employees = employees;
        this.departmentHrs = departmentHrs;
        this.teamLeads = teamLeads;
        this.employeeProfiles = employeeProfiles;
        this.notifications = notifications;
        this.audit = audit;
    }

    @Transactional
    public View request(
            UUID requesterUserId,
            Command command
    ) {
        var requester = staff.requireActive(requesterUserId);

        RoleDepartmentChangeRequest.RoleType role =
                resolveRequesterRole(requester);

        var target = organization.requireActiveDepartment(
                command.targetDepartmentId()
        );

        if (requests.existsByRequesterUserIdAndStatus(
                requesterUserId,
                RoleDepartmentChangeRequest.Status.PENDING
        )) {
            throw new BusinessException(
                    "DEPARTMENT_CHANGE_ALREADY_PENDING",
                    "You already have a pending department change request",
                    HttpStatus.CONFLICT
            );
        }

        String reason = normalizeReason(command.reason());

        UUID fromDepartmentId =
                currentDepartmentId(requester, role);

        if (target.id().equals(fromDepartmentId)) {
            throw new BusinessException(
                    "DEPARTMENT_CHANGE_SAME_DEPARTMENT",
                    "Select a different department",
                    HttpStatus.UNPROCESSABLE_ENTITY
            );
        }

        Occupant occupant = occupant(role, target.id());

        if (role == RoleDepartmentChangeRequest.RoleType.TEAM_LEAD) {
            departmentHrs.requireForDepartment(target.id());
        }

        var created = requests.saveAndFlush(
                new RoleDepartmentChangeRequest(
                        requester.userId(),
                        requester.employeeId(),
                        role,
                        fromDepartmentId,
                        target.id(),
                        occupant == null ? null : occupant.userId(),
                        occupant == null ? null : occupant.employeeId(),
                        reason,
                        command.phoneNumber(),
                        defaultDesignation(command.designation(), role),
                        command.joiningDate() == null
                                ? LocalDate.now()
                                : command.joiningDate()
                )
        );

        audit.record(
                "ROLE_DEPARTMENT_CHANGE_REQUESTED",
                "ROLE_DEPARTMENT_CHANGE",
                created.getId().toString(),
                "{\"role\":\"" + role
                        + "\",\"targetDepartmentId\":\"" + target.id()
                        + "\",\"conflict\":" + (occupant != null) + "}"
        );

        notifications.notifyRoleDepartmentChangeApprover(
                requester.userId(),
                role.name(),
                target.id(),
                requester.fullName()
                        + " requested a "
                        + label(role)
                        + " department change to "
                        + target.name()
                        + (occupant == null
                        ? "."
                        : ". The department already has "
                          + occupant.name()
                          + "; choose swap or replace in Roles & responsibilities.")
        );

        return view(created);
    }

    @Transactional(readOnly = true)
    public List<View> mine(UUID requesterUserId) {
        staff.requireActive(requesterUserId);

        return requests
                .findTop50ByRequesterUserIdOrderByRequestedAtDesc(
                        requesterUserId
                )
                .stream()
                .map(this::view)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<View> pendingForApprover(UUID approverUserId) {
        var approver = staff.requireActive(approverUserId);

        return requests
                .findAllByStatusOrderByRequestedAtAsc(
                        RoleDepartmentChangeRequest.Status.PENDING
                )
                .stream()
                .filter(request -> canApprove(approver, request))
                .map(this::view)
                .toList();
    }

    @Transactional
    public View approve(
            UUID approverUserId,
            UUID requestId,
            RoleDepartmentChangeRequest.Resolution requestedResolution,
            String note
    ) {
        var request = requirePending(requestId);
        var approver = staff.requireActive(approverUserId);

        requireApprover(approver, request);

        Occupant currentOccupant = occupant(
                request.getRequesterRole(),
                request.getTargetDepartmentId()
        );

        boolean conflict = currentOccupant != null
                && !currentOccupant.userId()
                .equals(request.getRequesterUserId());

        RoleDepartmentChangeRequest.Resolution resolution = conflict
                ? requestedResolution
                : RoleDepartmentChangeRequest.Resolution.MOVE;

        if (conflict
                && resolution
                != RoleDepartmentChangeRequest.Resolution.REPLACE
                && resolution
                != RoleDepartmentChangeRequest.Resolution.SWAP) {
            throw new BusinessException(
                    "ROLE_ASSIGNMENT_CONFLICT_RESOLUTION_REQUIRED",
                    "The target department already has this role. Choose replace or swap.",
                    HttpStatus.CONFLICT
            );
        }

        if (request.getRequesterRole()
                == RoleDepartmentChangeRequest.RoleType.HR_ADMIN) {

            ensureHrEmployeeProfile(request);

            departmentHrs.transferApproved(
                    approverUserId,
                    request.getRequesterUserId(),
                    request.getTargetDepartmentId(),
                    DepartmentHrDirectory.TransferResolution.valueOf(
                            resolution.name()
                    )
            );
        } else {
            teamLeads.transferApproved(
                    approverUserId,
                    request.getRequesterUserId(),
                    request.getTargetDepartmentId(),
                    TeamLeadDirectory.TransferResolution.valueOf(
                            resolution.name()
                    )
            );
        }

        request.approve(
                approverUserId,
                resolution,
                note
        );

        var saved = requests.saveAndFlush(request);

        var target = organization.requireActiveDepartment(
                request.getTargetDepartmentId()
        );

        audit.record(
                "ROLE_DEPARTMENT_CHANGE_APPROVED",
                "ROLE_DEPARTMENT_CHANGE",
                requestId.toString(),
                "{\"resolution\":\"" + resolution
                        + "\",\"targetDepartmentId\":\""
                        + target.id() + "\"}"
        );

        notifications.notifyRoleDepartmentChangeDecision(
                approverUserId,
                request.getRequesterUserId(),
                "Your "
                        + label(request.getRequesterRole())
                        + " department change to "
                        + target.name()
                        + " was approved using "
                        + resolution.name().toLowerCase()
                        + ". Sign in again to refresh role scope."
        );

        return view(saved);
    }

    @Transactional
    public View reject(
            UUID approverUserId,
            UUID requestId,
            String note
    ) {
        var request = requirePending(requestId);
        var approver = staff.requireActive(approverUserId);

        requireApprover(approver, request);

        String reason = normalizeReason(note);

        request.reject(approverUserId, reason);

        var saved = requests.saveAndFlush(request);

        audit.record(
                "ROLE_DEPARTMENT_CHANGE_REJECTED",
                "ROLE_DEPARTMENT_CHANGE",
                requestId.toString(),
                "{\"reason\":\"" + safe(reason) + "\"}"
        );

        notifications.notifyRoleDepartmentChangeDecision(
                approverUserId,
                request.getRequesterUserId(),
                "Your "
                        + label(request.getRequesterRole())
                        + " department change request was rejected: "
                        + reason
        );

        return view(saved);
    }

    @Transactional
    public View cancel(
            UUID requesterUserId,
            UUID requestId
    ) {
        var request = requirePending(requestId);

        if (!request.getRequesterUserId().equals(requesterUserId)) {
            throw new BusinessException(
                    "DEPARTMENT_CHANGE_CANCEL_DENIED",
                    "Only the requester can cancel this request",
                    HttpStatus.FORBIDDEN
            );
        }

        request.cancel(requesterUserId);

        var saved = requests.saveAndFlush(request);

        audit.record(
                "ROLE_DEPARTMENT_CHANGE_CANCELLED",
                "ROLE_DEPARTMENT_CHANGE",
                requestId.toString(),
                "{}"
        );

        return view(saved);
    }

    private void ensureHrEmployeeProfile(
            RoleDepartmentChangeRequest request
    ) {
        var requester = staff.requireActive(
                request.getRequesterUserId()
        );

        if (requester.employeeId() != null) {
            return;
        }

        employeeProfiles.createAndLink(
                requester.userId(),
                requester.fullName(),
                requester.email(),
                request.getTargetDepartmentId(),
                request.getProfilePhoneNumber(),
                defaultDesignation(
                        request.getProfileDesignation(),
                        RoleDepartmentChangeRequest.RoleType.HR_ADMIN
                ),
                request.getProfileJoiningDate() == null
                        ? LocalDate.now()
                        : request.getProfileJoiningDate()
        );
    }

    private RoleDepartmentChangeRequest requirePending(
            UUID requestId
    ) {
        var request = requests.findById(requestId)
                .orElseThrow(() -> new BusinessException(
                        "DEPARTMENT_CHANGE_NOT_FOUND",
                        "Department change request was not found",
                        HttpStatus.NOT_FOUND
                ));

        if (request.getStatus()
                != RoleDepartmentChangeRequest.Status.PENDING) {
            throw new BusinessException(
                    "DEPARTMENT_CHANGE_NOT_PENDING",
                    "This department change request is no longer pending",
                    HttpStatus.CONFLICT
            );
        }

        return request;
    }

    private boolean canApprove(
            StaffCommunicationDirectory.StaffMember approver,
            RoleDepartmentChangeRequest request
    ) {
        if (request.getRequesterRole()
                == RoleDepartmentChangeRequest.RoleType.HR_ADMIN) {
            return approver.roles().contains(CEO);
        }

        if (!approver.roles().contains(HR)) {
            return false;
        }

        return departmentHrs
                .activeForDepartment(
                        request.getTargetDepartmentId()
                )
                .map(value ->
                        value.hrUserId().equals(approver.userId())
                )
                .orElse(false);
    }

    private void requireApprover(
            StaffCommunicationDirectory.StaffMember approver,
            RoleDepartmentChangeRequest request
    ) {
        if (!canApprove(approver, request)) {
            throw new BusinessException(
                    "DEPARTMENT_CHANGE_APPROVAL_DENIED",
                    request.getRequesterRole()
                            == RoleDepartmentChangeRequest.RoleType.HR_ADMIN
                            ? "Only CEO can approve HR department changes"
                            : "Only the HR Admin assigned to the destination department can approve this Team Lead change",
                    HttpStatus.FORBIDDEN
            );
        }
    }

    private RoleDepartmentChangeRequest.RoleType resolveRequesterRole(
            StaffCommunicationDirectory.StaffMember requester
    ) {
        if (requester.roles().contains(HR)) {
            return RoleDepartmentChangeRequest.RoleType.HR_ADMIN;
        }

        if (requester.roles().contains(TEAM_LEAD)) {
            return RoleDepartmentChangeRequest.RoleType.TEAM_LEAD;
        }

        throw new BusinessException(
                "DEPARTMENT_CHANGE_ROLE_NOT_SUPPORTED",
                "Only HR Admin and Team Lead can request a department change",
                HttpStatus.FORBIDDEN
        );
    }

    private UUID currentDepartmentId(
            StaffCommunicationDirectory.StaffMember requester,
            RoleDepartmentChangeRequest.RoleType role
    ) {
        if (role == RoleDepartmentChangeRequest.RoleType.HR_ADMIN) {
            var assignment = departmentHrs
                    .activeForUser(requester.userId())
                    .orElse(null);

            if (assignment != null) {
                return assignment.departmentId();
            }
        } else {
            return teamLeads
                    .requireForUser(requester.userId())
                    .departmentId();
        }

        return requester.employeeId() == null
                ? null
                : employees.departmentIdForEmployee(
                requester.employeeId()
        );
    }

    private Occupant occupant(
            RoleDepartmentChangeRequest.RoleType role,
            UUID departmentId
    ) {
        if (role == RoleDepartmentChangeRequest.RoleType.HR_ADMIN) {
            return departmentHrs
                    .activeForDepartment(departmentId)
                    .map(value -> new Occupant(
                            value.hrUserId(),
                            value.hrEmployeeId(),
                            value.fullName(),
                            value.email()
                    ))
                    .orElse(null);
        }

        return teamLeads
                .activeForDepartment(departmentId)
                .map(value -> new Occupant(
                        value.teamLeadUserId(),
                        value.teamLeadEmployeeId(),
                        value.fullName(),
                        value.email()
                ))
                .orElse(null);
    }

    private View view(RoleDepartmentChangeRequest request) {
        var requester = staff
                .findByUserId(request.getRequesterUserId())
                .orElse(null);

        var from = request.getFromDepartmentId() == null
                ? null
                : organization
                .findDepartment(request.getFromDepartmentId())
                .orElse(null);

        var target = organization
                .findDepartment(request.getTargetDepartmentId())
                .orElse(null);

        Occupant liveOccupant =
                request.getStatus()
                        == RoleDepartmentChangeRequest.Status.PENDING
                        ? occupant(
                        request.getRequesterRole(),
                        request.getTargetDepartmentId()
                )
                        : null;

        boolean conflict = liveOccupant != null
                && !liveOccupant.userId()
                .equals(request.getRequesterUserId());

        return new View(
                request.getId(),
                request.getRequesterUserId(),
                request.getRequesterEmployeeId(),
                requester == null
                        ? "Former staff member"
                        : requester.fullName(),
                requester == null
                        ? ""
                        : requester.email(),
                request.getRequesterRole(),
                request.getFromDepartmentId(),
                from == null ? null : from.name(),
                request.getTargetDepartmentId(),
                target == null ? "Department" : target.name(),
                conflict,
                liveOccupant == null
                        ? request.getTargetOccupantUserId()
                        : liveOccupant.userId(),
                liveOccupant == null
                        ? null
                        : liveOccupant.name(),
                request.getReason(),
                request.getStatus(),
                request.getRequestedAt(),
                request.getResolution(),
                request.getDecisionNote(),
                request.getDecidedByUserId(),
                request.getDecidedAt()
        );
    }

    private String normalizeReason(String value) {
        String normalized = value == null
                ? ""
                : value.trim().replaceAll("\\s+", " ");

        if (normalized.length() < 5
                || normalized.length() > 500) {
            throw new BusinessException(
                    "INVALID_DEPARTMENT_CHANGE_REASON",
                    "Provide a reason containing 5 to 500 characters",
                    HttpStatus.UNPROCESSABLE_ENTITY
            );
        }

        return normalized;
    }

    private String defaultDesignation(
            String value,
            RoleDepartmentChangeRequest.RoleType role
    ) {
        if (value != null && !value.isBlank()) {
            return value.trim();
        }

        return role == RoleDepartmentChangeRequest.RoleType.HR_ADMIN
                ? "HR Admin"
                : "Team Lead";
    }

    private String label(
            RoleDepartmentChangeRequest.RoleType role
    ) {
        return role == RoleDepartmentChangeRequest.RoleType.HR_ADMIN
                ? "HR Admin"
                : "Team Lead";
    }

    private String safe(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"");
    }

    private record Occupant(
            UUID userId,
            UUID employeeId,
            String name,
            String email
    ) {
    }

    public record Command(
            UUID targetDepartmentId,
            String reason,
            String phoneNumber,
            String designation,
            LocalDate joiningDate
    ) {
    }

    public record View(
            UUID id,
            UUID requesterUserId,
            UUID requesterEmployeeId,
            String requesterName,
            String requesterEmail,
            RoleDepartmentChangeRequest.RoleType requesterRole,
            UUID fromDepartmentId,
            String fromDepartmentName,
            UUID targetDepartmentId,
            String targetDepartmentName,
            boolean targetOccupied,
            UUID targetOccupantUserId,
            String targetOccupantName,
            String reason,
            RoleDepartmentChangeRequest.Status status,
            java.time.Instant requestedAt,
            RoleDepartmentChangeRequest.Resolution resolution,
            String decisionNote,
            UUID decidedByUserId,
            java.time.Instant decidedAt
    ) {
    }
}