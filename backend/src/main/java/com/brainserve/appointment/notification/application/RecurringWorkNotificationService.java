package com.brainserve.appointment.notification.application;

import com.brainserve.appointment.notification.api.RecurringWorkNotifications;
import com.brainserve.appointment.notification.domain.InternalCallNotification;
import com.brainserve.appointment.notification.infrastructure.InternalCallNotificationRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

@Service
public class RecurringWorkNotificationService implements RecurringWorkNotifications {
    private final InternalCallNotificationRepository notifications;
    private final JdbcTemplate jdbc;
    public RecurringWorkNotificationService(InternalCallNotificationRepository notifications, JdbcTemplate jdbc) {
        this.notifications = notifications; this.jdbc = jdbc;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(String eventKey, UUID senderId, UUID recipientId, String senderName,
                        String recipientName, String message) {
        // Serialize the key before checking the receipt; no duplicate outbox row on direct replay.
        jdbc.query("select pg_advisory_xact_lock(hashtextextended(?,0))", (rs,n) -> 0, "routine-notice:" + eventKey);
        if (Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from work_routine_notice_receipt where event_key=?)", Boolean.class, eventKey))) return;
        String normalized = message.trim().replaceAll("\\s+", " ");
        if (normalized.length() > 500) normalized = normalized.substring(0,497) + "...";
        var notification = notifications.saveAndFlush(new InternalCallNotification(senderId,recipientId,senderName,
                recipientName,normalized,InternalCallNotification.MessagePriority.NORMAL,InternalCallNotification.MessageCategory.WORK));
        jdbc.update("insert into work_routine_notice_receipt(event_key,notification_id) values (?,?)", eventKey,notification.getId());
        // Existing delivery polling reads QUEUED committed rows. No async event is required for
        // correctness, so restart between commit and delivery cannot lose this intent.
    }
}
