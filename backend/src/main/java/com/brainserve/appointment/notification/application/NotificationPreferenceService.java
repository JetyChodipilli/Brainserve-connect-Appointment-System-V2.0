package com.brainserve.appointment.notification.application;

import com.brainserve.appointment.iam.api.CurrentAccountAuthority;
import com.brainserve.appointment.notification.domain.InternalCallNotification;
import com.brainserve.appointment.notification.domain.OutboxMessage;
import com.brainserve.appointment.shared.application.BusinessException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class NotificationPreferenceService {
    private final JdbcTemplate jdbc;
    private final CurrentAccountAuthority authority;
    private final ObjectMapper mapper;
    private final String officeZone;
    public NotificationPreferenceService(JdbcTemplate jdbc, CurrentAccountAuthority authority, ObjectMapper mapper,
            @Value("${brainserve.appointment.office-zone:Asia/Kolkata}") String officeZone) {
        this.jdbc = jdbc; this.authority = authority; this.mapper = mapper; this.officeZone = officeZone;
    }
    public record Preference(long version, boolean inAppEnabled, boolean emailEnabled, boolean soundEnabled,
                             String cadence, String zoneId, boolean quietEnabled, String quietStart, String quietEnd) {}

    @Transactional(readOnly = true)
    public Preference read(UUID actor) { authority.requireActive(actor); return stored(actor); }

    private Preference stored(UUID actor) {
        var rows = jdbc.query("select * from notification_preference where user_id=?", (rs, n) ->
                new Preference(rs.getLong("version"), rs.getBoolean("in_app_enabled"), rs.getBoolean("email_enabled"),
                        rs.getBoolean("sound_enabled"), rs.getString("cadence"), rs.getString("zone_id"),
                        rs.getBoolean("quiet_enabled"), rs.getTime("quiet_start").toLocalTime().toString(),
                        rs.getTime("quiet_end").toLocalTime().toString()), actor);
        return rows.isEmpty() ? new Preference(0, true, false, true, "IMMEDIATE", officeZone, false, "22:00", "08:00") : rows.getFirst();
    }

    @Transactional
    public Preference save(UUID actor, Preference value) {
        authority.requireActive(actor);
        validate(value);
        jdbc.query("select id from iam_user_account where id=? for update", (rs,n) -> 0, actor);
        authority.requireActive(actor);
        jdbc.update("insert into notification_preference(user_id,zone_id) values(?,?) on conflict do nothing", actor, officeZone);
        int updated = jdbc.update("""
                update notification_preference set version=version+1,in_app_enabled=?,email_enabled=?,sound_enabled=?,
                cadence=?,zone_id=?,quiet_enabled=?,quiet_start=?::time,quiet_end=?::time,updated_at=now()
                where user_id=? and version=?
                """, value.inAppEnabled(), value.emailEnabled(), value.soundEnabled(), value.cadence(), value.zoneId(),
                value.quietEnabled(), value.quietStart(), value.quietEnd(), actor, value.version());
        if (updated != 1) throw new BusinessException("NOTIFICATION_PREFERENCE_CONFLICT", "Preferences changed. Reload before saving.", HttpStatus.CONFLICT);
        Preference saved = stored(actor);
        jdbc.update("insert into notification_preference_history(user_id,version,snapshot_json) values(?,?,?::jsonb)", actor, saved.version(), json(saved));
        // Previously held routine messages are reconsidered; delivered history and mandatory jobs are untouched.
        jdbc.update("update internal_call_notification set next_delivery_attempt_at=now() where recipient_user_id=? and not mandatory and delivery_status in ('QUEUED','FAILED') and (delivery_attempts=0 or delivery_status='FAILED')", actor);
        return saved;
    }

    private static void validate(Preference value) {
        try {
            if (value.version()<0 || !Set.of("IMMEDIATE", "HOURLY", "DAILY").contains(value.cadence())) throw new IllegalArgumentException();
            ZoneId.of(value.zoneId());
            LocalTime start=LocalTime.parse(value.quietStart()), end=LocalTime.parse(value.quietEnd());
            if(start.getSecond()!=0 || start.getNano()!=0 || end.getSecond()!=0 || end.getNano()!=0) throw new IllegalArgumentException();
            if (value.quietEnabled() && start.equals(end)) throw new IllegalArgumentException();
        } catch (RuntimeException ex) {
            throw new BusinessException("INVALID_NOTIFICATION_PREFERENCE", "Use a valid time zone, cadence and distinct quiet-hour times.", HttpStatus.UNPROCESSABLE_ENTITY);
        }
    }

    /** Called while the existing delivery worker owns the notification rows. */
    public List<InternalCallNotification> prepare(List<InternalCallNotification> ready, Instant now) {
        var deliver = new ArrayList<InternalCallNotification>();
        for (var notification : ready) {
            if (notification.isMandatory()) { notification.schedule(now, null); deliver.add(notification); continue; }
            // Account closure or termination cannot generate a new routine email copy.
            try { authority.requireActive(notification.getRecipientUserId()); }
            catch (BusinessException denied) { notification.suppress(); continue; }
            Preference p = stored(notification.getRecipientUserId());
            ZoneId zone = ZoneId.of(p.zoneId());
            Instant due = NotificationSchedule.due(notification.getSentAt(), p.cadence(), zone, p.quietEnabled(),
                    LocalTime.parse(p.quietStart()), LocalTime.parse(p.quietEnd()));
            due = NotificationSchedule.afterQuiet(due.isBefore(now)?now:due, zone, p.quietEnabled(),
                    LocalTime.parse(p.quietStart()), LocalTime.parse(p.quietEnd()));
            notification.schedule(due, p.version());
            if (due.isAfter(now)) { notification.defer(due); continue; }
            if (p.emailEnabled()) queueEmail(notification, p);
            if (p.inAppEnabled()) deliver.add(notification); else notification.suppress();
        }
        return deliver;
    }

    /** Recheck routine email policy on every transport claim; mandatory templates bypass preferences. */
    public List<OutboxMessage> prepareEmails(List<OutboxMessage> ready,Instant now) {
        var deliver=new ArrayList<OutboxMessage>();
        for(var message:ready) {
            if(!Set.of("ROUTINE_MESSAGE","ROUTINE_DIGEST").contains(message.getTemplate())) {deliver.add(message);continue;}
            var owners=jdbc.query("select distinct n.recipient_user_id from notification_email_receipt r join internal_call_notification n on n.id=r.notification_id where r.event_key=?",(rs,n)->rs.getObject(1,UUID.class),message.getEventKey());
            if(owners.size()!=1) {message.suppress();continue;}
            UUID user=owners.getFirst();
            try {authority.requireActive(user);} catch(BusinessException denied) {message.suppress();continue;}
            Preference p=stored(user);
            if(!p.emailEnabled()) {message.suppress();continue;}
            Instant due=NotificationSchedule.afterQuiet(now,ZoneId.of(p.zoneId()),p.quietEnabled(),LocalTime.parse(p.quietStart()),LocalTime.parse(p.quietEnd()));
            if(due.isAfter(now)) {message.defer(due);continue;}
            message.currentDestination(jdbc.queryForObject("select email from iam_user_account where id=?",String.class,user));
            deliver.add(message);
        }
        return deliver;
    }

    private void queueEmail(InternalCallNotification n, Preference p) {
        Instant boundary = NotificationSchedule.due(n.getSentAt(), p.cadence(), ZoneId.of(p.zoneId()), p.quietEnabled(),
                LocalTime.parse(p.quietStart()), LocalTime.parse(p.quietEnd()));
        String key = "notification:" + ("IMMEDIATE".equals(p.cadence()) ? n.getId()
                : n.getRecipientUserId()+":"+p.cadence()+":"+boundary.getEpochSecond());
        int added = jdbc.update("insert into notification_email_receipt(notification_id,event_key) values(?,?) on conflict do nothing", n.getId(), key);
        if (added == 0) return;
        jdbc.update("""
                insert into notification_outbox(id,event_key,channel,destination,template,payload_json,status,attempt_count,
                next_attempt_at,version,created_at,created_by,updated_at,updated_by)
                select ?,?,'EMAIL',email,?,?::jsonb,'PENDING',0,?,0,now(),'notification-policy',now(),'notification-policy'
                from iam_user_account where id=? and enabled and account_status='ACTIVE' and not archived
                on conflict(event_key) do nothing
                """, UUID.randomUUID(), key, "IMMEDIATE".equals(p.cadence())?"ROUTINE_MESSAGE":"ROUTINE_DIGEST",
                json(Map.of("reference", "your authorized BrainServe notification history")), Timestamp.from(Instant.now()), n.getRecipientUserId());
    }
    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (JsonProcessingException ex) { throw new IllegalStateException("Notification policy serialization failed", ex); }
    }
}
