package com.brainserve.appointment.iam.application;

import com.brainserve.appointment.iam.domain.*;
import com.brainserve.appointment.iam.infrastructure.UserAccountRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
public class HrAccountLifecycleService {
    private final UserAccountRepository users;
    public HrAccountLifecycleService(UserAccountRepository users) { this.users = users; }
    @Transactional(readOnly = true)
    public List<UserAccount> list() {
        return users.findDistinctByRolesContainingAndStatusAndEnabledTrueAndArchivedFalse(
                        SystemRole.ROLE_HR_ADMIN, AccountStatus.ACTIVE).stream()
                .sorted(Comparator.comparing(UserAccount::getFullName)).toList();
    }
}
