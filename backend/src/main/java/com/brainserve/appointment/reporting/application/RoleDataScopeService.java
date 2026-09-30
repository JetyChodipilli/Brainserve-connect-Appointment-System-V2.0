package com.brainserve.appointment.reporting.application;

import com.brainserve.appointment.employee.api.EmployeeDirectory;
import com.brainserve.appointment.iam.api.StaffCommunicationDirectory;
import com.brainserve.appointment.reporting.api.HistoryDataset;
import com.brainserve.appointment.shared.application.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.Set;
import java.util.UUID;

@Service
public class RoleDataScopeService {
    private static final String SYSTEM_ADMIN = "ROLE_SYSTEM_ADMIN";
    private static final String CEO = "ROLE_CEO";
    private static final String HR = "ROLE_HR_ADMIN";
    private static final String MANAGER = "ROLE_MANAGER";
    private static final String TEAM_LEAD = "ROLE_TEAM_LEAD";
    private static final String RECEPTIONIST = "ROLE_RECEPTIONIST";
    private static final String SECURITY = "ROLE_SECURITY";
    private static final String EMPLOYEE = "ROLE_EMPLOYEE";

    private final StaffCommunicationDirectory staff;
    private final EmployeeDirectory employees;

    public RoleDataScopeService(StaffCommunicationDirectory staff, EmployeeDirectory employees) {
        this.staff = staff;
        this.employees = employees;
    }

    public RoleDataScope resolve(UUID userId) {
        var member = staff.requireActive(userId);
        String role = member.roles().stream().min(Comparator.comparingInt(RoleDataScopeService::roleRank))
                .orElseThrow(() -> denied("The account has no reporting role"));
        boolean organizationWide = role.equals(SYSTEM_ADMIN) || role.equals(CEO)
                || role.equals(RECEPTIONIST) || role.equals(SECURITY);
        UUID departmentId = member.employeeId() == null || organizationWide
                ? null : employees.departmentIdForEmployee(member.employeeId());
        return new RoleDataScope(userId, member.employeeId(), departmentId, role, organizationWide);
    }

    public UUID effectiveDepartment(RoleDataScope scope, UUID requestedDepartment) {
        if (scope.organizationWide()) return requestedDepartment;
        if (scope.departmentId() == null) return null;
        if (requestedDepartment != null && !requestedDepartment.equals(scope.departmentId())) {
            throw denied("You cannot view another department's history");
        }
        return scope.departmentId();
    }

    public void requireDataset(RoleDataScope scope, HistoryDataset dataset) {
        Set<HistoryDataset> allowed = switch (scope.role()) {
            case SYSTEM_ADMIN -> Set.of(HistoryDataset.values());
            case CEO -> Set.of(HistoryDataset.VISITS, HistoryDataset.EMPLOYEES, HistoryDataset.TERMINATIONS,
                    HistoryDataset.WORKBOARD, HistoryDataset.AUDIT, HistoryDataset.CHECKPOINTS);
            case HR -> Set.of(HistoryDataset.VISITS, HistoryDataset.EMPLOYEES, HistoryDataset.TERMINATIONS,
                    HistoryDataset.WORKBOARD, HistoryDataset.AUDIT, HistoryDataset.CHECKPOINTS);
            case MANAGER -> Set.of(HistoryDataset.VISITS, HistoryDataset.EMPLOYEES,
                    HistoryDataset.WORKBOARD, HistoryDataset.CHECKPOINTS);
            case TEAM_LEAD -> Set.of(HistoryDataset.VISITS, HistoryDataset.EMPLOYEES, HistoryDataset.WORKBOARD);
            case RECEPTIONIST, SECURITY -> Set.of(HistoryDataset.VISITS, HistoryDataset.CHECKPOINTS);
            case EMPLOYEE -> Set.of(HistoryDataset.VISITS, HistoryDataset.WORKBOARD);
            default -> Set.of();
        };
        if (!allowed.contains(dataset)) throw denied("This dataset is not available to your role");
    }

    private static int roleRank(String role) {
        return switch (role) {
            case SYSTEM_ADMIN -> 0;
            case CEO -> 1;
            case HR -> 2;
            case MANAGER -> 3;
            case TEAM_LEAD -> 4;
            case RECEPTIONIST -> 5;
            case SECURITY -> 6;
            case EMPLOYEE -> 7;
            default -> 99;
        };
    }

    private static BusinessException denied(String message) {
        return new BusinessException("HISTORY_SCOPE_DENIED", message, HttpStatus.FORBIDDEN);
    }

    public record RoleDataScope(UUID userId, UUID employeeId, UUID departmentId,
                                String role, boolean organizationWide) {}
}
