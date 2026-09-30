package com.brainserve.appointment.iam.application;

import com.brainserve.appointment.iam.infrastructure.RefreshTokenSessionRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Service
public class AuthenticationSessionRetentionService {
    private final RefreshTokenSessionRepository sessions;
    private final int expiredSessionRetentionDays;

    public AuthenticationSessionRetentionService(
            RefreshTokenSessionRepository sessions,
            @Value("${brainserve.security.expired-session-retention-days:30}") int expiredSessionRetentionDays) {
        this.sessions = sessions;
        this.expiredSessionRetentionDays = Math.max(1, Math.min(expiredSessionRetentionDays, 365));
    }

    @Scheduled(cron = "${brainserve.security.session-retention-cron:0 25 3 * * *}", zone = "UTC")
    @Transactional
    public int removeExpiredSessionSecrets() {
        Instant cutoff = Instant.now().minus(expiredSessionRetentionDays, ChronoUnit.DAYS);
        return sessions.deleteExpiredBefore(cutoff);
    }
}
