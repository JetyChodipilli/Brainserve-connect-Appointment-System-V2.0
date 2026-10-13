package com.brainserve.appointment.notification.application;

import com.brainserve.appointment.notification.domain.OutboxMessage;
import com.brainserve.appointment.notification.infrastructure.OutboxRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.List;
import java.util.Optional;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class NotificationDispatcherRegressionTest {
    // Regression: AUD-03 — an expired worker cannot finish a newer email attempt.
    // Found by /qa on 2026-10-13
    // Report: docs/SECURITY_FUNCTIONAL_CAPACITY_AUDIT.md
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void anOldCompletionCannotOverwriteAReclaimedAttempt(boolean oldFailure) {
        OutboxMessage message = new OutboxMessage("synthetic", "visitor@audit.invalid", "APPOINTMENT_OTP",
                "{\"reference\":\"synthetic\",\"otp\":\"000000\"}");
        var outbox = mock(OutboxRepository.class);
        when(outbox.lockReady(any(), any(), any())).thenReturn(List.of(message));
        when(outbox.findByIdForUpdate(message.getId())).thenReturn(Optional.of(message));
        var mail = mock(JavaMailSender.class);
        doAnswer(call -> {
            message.markProcessing(); // Another worker reclaims the expired lease.
            if (oldFailure) { message.markSent(); throw new MailSendException("synthetic transport failure"); }
            return null;
        }).when(mail).send(any(SimpleMailMessage.class));
        var manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenAnswer(call -> new SimpleTransactionStatus());
        new NotificationDispatcher(outbox, mail, new ObjectMapper(), "sender@audit.invalid",
                new TransactionTemplate(manager)).dispatch();
        assertThat(message).extracting("status").isEqualTo(oldFailure ? OutboxMessage.Status.SENT : OutboxMessage.Status.PROCESSING);
        assertThat(message.getAttemptCount()).isEqualTo(2);
    }
}
