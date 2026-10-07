package com.brainserve.appointment.notification.application;

import com.brainserve.appointment.notification.api.HandoverNotifications;
import com.brainserve.appointment.notification.domain.InternalCallNotification;
import com.brainserve.appointment.notification.infrastructure.InternalCallNotificationRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

@Service
public class HandoverNotificationService implements HandoverNotifications {
    private final InternalCallNotificationRepository notifications;
    private final JdbcTemplate jdbc;
    public HandoverNotificationService(InternalCallNotificationRepository notifications,JdbcTemplate jdbc) {this.notifications=notifications;this.jdbc=jdbc;}
    @Override
    @Transactional(propagation=Propagation.MANDATORY)
    public void enqueue(String eventKey,UUID senderId,UUID recipientId,String senderName,String recipientName,String message) {
        jdbc.query("select pg_advisory_xact_lock(hashtextextended(?,0))",(rs,n)->0,"handover-notice:"+eventKey);
        if(Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from work_handover_notice_receipt where event_key=?)",Boolean.class,eventKey))) return;
        String normalized=message.trim().replaceAll("\\s+"," ");
        if(normalized.length()>500) normalized=normalized.substring(0,497)+"...";
        var row=notifications.saveAndFlush(new InternalCallNotification(senderId,recipientId,senderName,recipientName,normalized,
                InternalCallNotification.MessagePriority.NORMAL,InternalCallNotification.MessageCategory.WORK));
        jdbc.update("insert into work_handover_notice_receipt(event_key,notification_id) values (?,?)",eventKey,row.getId());
        // The existing poller reads committed QUEUED rows; restart cannot lose this intent.
    }
}
