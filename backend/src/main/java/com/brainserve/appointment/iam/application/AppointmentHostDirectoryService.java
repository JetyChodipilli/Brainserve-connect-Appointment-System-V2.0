package com.brainserve.appointment.iam.application;

import com.brainserve.appointment.iam.api.AppointmentHostDirectory;
import com.brainserve.appointment.iam.domain.SystemRole;
import com.brainserve.appointment.iam.domain.UserAccount;
import com.brainserve.appointment.iam.infrastructure.UserAccountRepository;
import com.brainserve.appointment.shared.application.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class AppointmentHostDirectoryService implements AppointmentHostDirectory {
    private final UserAccountRepository users;

    public AppointmentHostDirectoryService(UserAccountRepository users) { this.users = users; }

    @Override
    @Transactional(readOnly = true)
    public List<HostAccount> activeChiefExecutives() {
        return activeWithRole(SystemRole.ROLE_CEO, "CEO");
    }

    @Override
    @Transactional(readOnly = true)
    public List<HostAccount> activeHumanResourcesAdministrators() {
        return activeWithRole(SystemRole.ROLE_HR_ADMIN, "HR");
    }

    private List<HostAccount> activeWithRole(SystemRole role, String category) {
        return users.findDistinctByRolesContainingAndStatusAndEnabledTrueAndArchivedFalse(
                        role, com.brainserve.appointment.iam.domain.AccountStatus.ACTIVE).stream()
                .map(user -> new HostAccount(user.getId(), user.getEmployeeId(), user.getFullName(),
                        user.getEmail(), category))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isAccountEligible(UUID employeeId) {
        return users.findByEmployeeId(employeeId).map(UserAccount::isEnabled).orElse(false);
    }

    @Override
    @Transactional(readOnly = true)
    public String categoryForEmployee(UUID employeeId) {
        return users.findByEmployeeId(employeeId).map(user -> {
            if (user.getRoles().contains(SystemRole.ROLE_CEO)) return "CEO";
            if (user.getRoles().contains(SystemRole.ROLE_HR_ADMIN)) return "HR";
            if (user.getRoles().contains(SystemRole.ROLE_TEAM_LEAD)) return "TEAM_LEAD";
            return "EMPLOYEE";
        }).orElse("EMPLOYEE");
    }

    @Override
    @Transactional
    public void linkEmployee(UUID userId, UUID employeeId) {
        UserAccount user = users.findById(userId).orElseThrow(() -> new BusinessException(
                "USER_NOT_FOUND", "User account was not found", HttpStatus.NOT_FOUND));
        user.linkEmployee(employeeId);
    }
}
