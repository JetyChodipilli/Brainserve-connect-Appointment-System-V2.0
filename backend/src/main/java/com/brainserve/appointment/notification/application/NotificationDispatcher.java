package com.brainserve.appointment.notification.application;

import com.brainserve.appointment.notification.domain.OutboxMessage;
import com.brainserve.appointment.notification.infrastructure.OutboxRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class NotificationDispatcher {
    private final OutboxRepository outbox;
    private final JavaMailSender mail;
    private final ObjectMapper mapper;
    private final String from;
    private final TransactionOperations transactions;
    public NotificationDispatcher(OutboxRepository outbox, JavaMailSender mail, ObjectMapper mapper,
                                  @Value("${brainserve.notification.from}") String from,
                                  TransactionOperations transactions) {
        this.outbox = outbox; this.mail = mail; this.mapper = mapper; this.from = from;
        this.transactions = transactions;
    }

    @Scheduled(fixedDelayString = "${brainserve.notification.poll-ms}")
    public void dispatch() {
        List<ClaimedEmail> messages = claimReady();
        for (ClaimedEmail message : messages) {
            String failure = null;
            try {
                Map<String, String> payload = mapper.readValue(message.payloadJson(), new TypeReference<>() {});
                SimpleMailMessage email = new SimpleMailMessage();
                email.setFrom(from); email.setTo(message.destination());
                applyTemplate(email, message.template(), payload);
                mail.send(email);
            } catch (MailException | com.fasterxml.jackson.core.JsonProcessingException | IllegalArgumentException ex) {
                failure = ex.getClass().getSimpleName();
            }
            complete(message.id(), failure);
        }
    }

    private List<ClaimedEmail> claimReady() {
        List<ClaimedEmail> claimed = transactions.execute(status -> {
            List<OutboxMessage> ready = outbox.lockReady(
                    Set.of(OutboxMessage.Status.PENDING, OutboxMessage.Status.PROCESSING),
                    Instant.now(), PageRequest.of(0, 25));
            ready.forEach(OutboxMessage::markProcessing);
            outbox.flush();
            return ready.stream().map(message -> new ClaimedEmail(message.getId(), message.getDestination(),
                    message.getTemplate(), message.getPayloadJson())).toList();
        });
        return claimed == null ? List.of() : claimed;
    }

    private void complete(UUID messageId, String failure) {
        transactions.executeWithoutResult(status -> outbox.findById(messageId).ifPresent(message -> {
            if (failure == null) message.markSent();
            else message.retry(failure);
        }));
    }

    private record ClaimedEmail(UUID id, String destination, String template, String payloadJson) {}

    private void applyTemplate(SimpleMailMessage email, String template, Map<String, String> payload) {
        String reference = payload.getOrDefault("reference", "your request");
        switch (template) {
            case "APPOINTMENT_OTP" -> {
                email.setSubject("Verify your BrainServe Connect appointment");
                email.setText("Your BrainServe Connect appointment reference is " + reference +
                        ". Verification code: " + payload.get("otp") + ". It expires in 10 minutes.");
            }
            case "APPOINTMENT_CANCELLATION_OTP" -> {
                email.setSubject("Confirm cancellation of your BrainServe Connect appointment");
                email.setText("Appointment " + reference + " can be cancelled with code "
                        + payload.get("otp") + ". It expires in 10 minutes. "
                        + "Ignore this message if you did not request cancellation.");
            }
            case "HR_VISIT_APPROVAL_REQUIRED" -> {
                email.setSubject("BrainServe Connect visit awaiting HR approval");
                email.setText("Visit " + reference + " is waiting in the HR approval queue. Sign in to review it.");
            }
            case "MANAGER_VISIT_APPROVAL_REQUIRED" -> {
                email.setSubject("BrainServe Connect CEO visit awaiting Manager approval");
                email.setText("Visit " + reference
                        + " was verified by Reception and is waiting in your department Manager queue.");
            }
            case "CEO_VISIT_APPROVAL_REQUIRED" -> {
                email.setSubject("BrainServe Connect CEO visit awaiting approval");
                email.setText("Visit " + reference
                        + " was reviewed by the assigned department Manager and is now waiting for the CEO's final decision.");
            }
            case "TEAM_LEAD_VISIT_APPROVAL_REQUIRED" -> {
                email.setSubject("BrainServe Connect visit awaiting Team Lead approval");
                email.setText("Visit " + reference + " was reviewed by HR and is waiting for your department decision. "
                        + "Sign in to approve or reject it.");
            }
            default -> throw new IllegalArgumentException("Unsupported notification template: " + template);
        }
    }
}
