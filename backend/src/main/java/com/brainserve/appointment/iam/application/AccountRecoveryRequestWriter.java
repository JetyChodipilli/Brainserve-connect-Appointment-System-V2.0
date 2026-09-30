package com.brainserve.appointment.iam.application;

import com.brainserve.appointment.iam.domain.AccountRecoveryRequest;
import com.brainserve.appointment.iam.domain.AccountRecoveryStatus;
import com.brainserve.appointment.iam.domain.AccountRecoveryType;
import com.brainserve.appointment.iam.infrastructure.AccountRecoveryRequestRepository;
import com.brainserve.appointment.iam.infrastructure.UserAccountRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Commits the public recovery request before any secondary audit work runs.
 * A public audit-store outage must never roll back the recovery queue row.
 */
@Service
public class AccountRecoveryRequestWriter {
    private final AccountRecoveryRequestRepository requests;
    private final UserAccountRepository users;

    public AccountRecoveryRequestWriter(AccountRecoveryRequestRepository requests,
                                        UserAccountRepository users) {
        this.requests = requests;
        this.users = users;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<UUID> createIfAbsent(UUID userId, AccountRecoveryType type) {
        // Serialize requests for the same account across application instances.
        var user = users.findByIdForUpdate(userId).orElseThrow();
        if (requests.existsByUser_IdAndTypeAndStatus(userId, type, AccountRecoveryStatus.PENDING)) {
            return Optional.empty();
        }
        return Optional.of(requests.saveAndFlush(new AccountRecoveryRequest(user, type)).getId());
    }
}
