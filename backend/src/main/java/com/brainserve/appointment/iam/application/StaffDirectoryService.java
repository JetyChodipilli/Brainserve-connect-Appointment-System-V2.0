package com.brainserve.appointment.iam.application;

import com.brainserve.appointment.iam.api.StaffDirectory;
import com.brainserve.appointment.iam.domain.SystemRole;
import com.brainserve.appointment.iam.domain.UserAccount;
import com.brainserve.appointment.iam.infrastructure.UserAccountRepository;
import com.brainserve.appointment.shared.application.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

@Service
public class StaffDirectoryService implements StaffDirectory {
    private final UserAccountRepository users;

    public StaffDirectoryService(UserAccountRepository users) { this.users = users; }

    @Override
    @Transactional(readOnly = true)
    public List<String> hrApprovalRecipients() {
        return recipients(Set.of(SystemRole.ROLE_HR_ADMIN));
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> hrApprovalRecipients(java.util.UUID hostEmployeeId) {
        if (hostEmployeeId != null) {
            var targeted = users.findByEmployeeId(hostEmployeeId)
                    .filter(UserAccount::isEnabled)
                    .filter(user -> user.getRoles().contains(SystemRole.ROLE_HR_ADMIN))
                    .map(UserAccount::getEmail);
            if (targeted.isPresent()) return List.of(targeted.get());
        }
        return hrApprovalRecipients();
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> ceoApprovalRecipients() {
        List<String> recipients = recipients(Set.of(SystemRole.ROLE_CEO));
        if (recipients.size() != 1) {
            throw new BusinessException("CEO_GOVERNANCE_CONFLICT",
                    recipients.isEmpty()
                            ? "No active company CEO is available for this approval"
                            : "Multiple active CEO accounts require database reconciliation",
                    HttpStatus.CONFLICT);
        }
        return recipients;
    }

    private List<String> recipients(Set<SystemRole> roles) {
        return roles.stream().flatMap(role ->
                        users.findDistinctByRolesContainingAndStatusAndEnabledTrueAndArchivedFalse(
                                role, com.brainserve.appointment.iam.domain.AccountStatus.ACTIVE).stream())
                .map(UserAccount::getEmail).distinct().sorted().toList();
    }
}
