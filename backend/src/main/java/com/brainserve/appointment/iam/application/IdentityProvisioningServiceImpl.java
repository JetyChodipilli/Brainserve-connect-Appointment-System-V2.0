package com.brainserve.appointment.iam.application;

import com.brainserve.appointment.iam.api.IdentityProvisioningService;
import com.brainserve.appointment.iam.domain.AccountStatus;
import com.brainserve.appointment.iam.domain.SystemRole;
import com.brainserve.appointment.iam.domain.UserAccount;
import com.brainserve.appointment.iam.infrastructure.UserAccountRepository;
import com.brainserve.appointment.shared.application.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class IdentityProvisioningServiceImpl implements IdentityProvisioningService {
    private final UserAccountRepository users;
    private final AccountProvisioningService provisioning;

    public IdentityProvisioningServiceImpl(UserAccountRepository users, AccountProvisioningService provisioning) {
        this.users = users; this.provisioning = provisioning;
    }

    @Override
    @Transactional
    public void createEmployeeAccount(UUID employeeId, String fullName, String officialEmail,
                                      String temporaryPassword, UUID createdByUserId) {
        var existing = users.findByEmailIgnoreCase(officialEmail.trim().toLowerCase());
        if (existing.isPresent()) {
            UserAccount account = existing.get();
            if (account.getEmployeeId() != null || account.getRoles().size() != 1
                    || !account.getRoles().contains(SystemRole.ROLE_EMPLOYEE)
                    || (account.getStatus() != AccountStatus.PENDING_HR_APPROVAL
                        && account.getStatus() != AccountStatus.ACTIVE)) {
                throw new BusinessException("EMPLOYEE_LOGIN_LINK_CONFLICT",
                        "The official email belongs to an account that cannot be linked to this employee",
                        HttpStatus.CONFLICT);
            }
            account.linkEmployee(employeeId);
            users.saveAndFlush(account);
            return;
        }
        provisioning.createForHrApproval(createdByUserId, fullName, officialEmail, temporaryPassword,
                SystemRole.ROLE_EMPLOYEE, employeeId);
    }

    @Override
    @Transactional
    public void disableEmployeeAccount(UUID employeeId) {
        users.findByEmployeeId(employeeId).ifPresent(UserAccount::disable);
    }
}
