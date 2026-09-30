package com.brainserve.appointment.iam.application;

import com.brainserve.appointment.iam.api.TeamLeadIdentityService;
import com.brainserve.appointment.iam.domain.SystemRole;
import com.brainserve.appointment.iam.domain.UserAccount;
import com.brainserve.appointment.iam.infrastructure.RefreshTokenSessionRepository;
import com.brainserve.appointment.iam.infrastructure.UserAccountRepository;
import com.brainserve.appointment.shared.application.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Service
public class TeamLeadIdentityServiceImpl implements TeamLeadIdentityService {
    private final UserAccountRepository users;
    private final RefreshTokenSessionRepository sessions;
    public TeamLeadIdentityServiceImpl(UserAccountRepository users, RefreshTokenSessionRepository sessions) {
        this.users = users;
        this.sessions = sessions;
    }

    @Override
    @Transactional
    public TeamLeadIdentity promoteActiveEmployee(UUID employeeId) {
        UserAccount user = users.findByEmployeeIdForUpdate(employeeId).orElseThrow(() -> new BusinessException(
                "EMPLOYEE_LOGIN_NOT_FOUND", "The selected employee must have an active login before Team Lead assignment",
                HttpStatus.UNPROCESSABLE_ENTITY));
        try { user.promoteToTeamLead(); }
        catch (IllegalStateException ex) { throw new BusinessException("TEAM_LEAD_PROMOTION_NOT_ALLOWED",
                ex.getMessage(), HttpStatus.UNPROCESSABLE_ENTITY); }
        users.saveAndFlush(user);
        sessions.revokeAllForUser(user.getId(), Instant.now());
        return from(user);
    }

    @Override
    @Transactional
    public void demote(UUID userId) {
        users.findByIdForUpdate(userId).filter(user -> user.getRoles().contains(SystemRole.ROLE_TEAM_LEAD)).ifPresent(user -> {
            user.demoteTeamLeadToEmployee();
            users.saveAndFlush(user);
            sessions.revokeAllForUser(userId, Instant.now());
        });
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<TeamLeadIdentity> activeByUserId(UUID userId) {
        return users.findById(userId).filter(UserAccount::isEnabled)
                .filter(user -> user.getRoles().contains(SystemRole.ROLE_TEAM_LEAD)).map(this::from);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<TeamLeadIdentity> activeByEmployeeId(UUID employeeId) {
        return users.findByEmployeeId(employeeId).filter(UserAccount::isEnabled)
                .filter(user -> user.getRoles().contains(SystemRole.ROLE_TEAM_LEAD)).map(this::from);
    }

    private TeamLeadIdentity from(UserAccount user) {
        return new TeamLeadIdentity(user.getId(), user.getEmployeeId(), user.getFullName(), user.getEmail());
    }
}
