package com.brainserve.appointment.audit.application;

import com.brainserve.appointment.audit.api.ActivityHistory;
import com.brainserve.appointment.shared.application.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;
import java.sql.*;
import java.util.*;

@Service
public class ActivityHistoryService implements ActivityHistory {
    private final NamedParameterJdbcTemplate jdbc;
    public ActivityHistoryService(NamedParameterJdbcTemplate jdbc){this.jdbc=jdbc;}
    private static final String AUDIT_COLUMNS="""
        'audit:'||id::text event_id,occurred_at,event_type,actor_id,
        details_json #>> '{_activity,actor,name}' actor_name,details_json #>> '{_activity,actor,role}' actor_role,
        details_json #>> '{_activity,cycle}' cycle,details_json #>> '{_activity,evidenceVersion}' evidence_version,
        details_json #>> '{_activity,departmentId}' department_id,correlation_id,
        CASE WHEN event_type='WORK_TASK_COMMENT_CREATED' AND COALESCE((details_json->>'notificationRequested')::boolean,false) THEN 'REQUESTED' ELSE NULL END delivery_status,
        details_json->>'reason' note
        """;
    @Override public Page task(UUID id,int page,int size) {
        String relation="""
            with audit_ids as (select id::text from work_task_audit_record where work_task_id=:id),
            retained as (
                select distinct on (id) * from (
                    select * from audit_event where outcome='SUCCESS' and
                        ((target_type='WORK_TASK' and target_id=:target) or (target_type='WORK_TASK_AUDIT' and (target_id in (select * from audit_ids) or details_json->>'workTaskId'=:target)))
                    union all
                    select * from audit_event_history where outcome='SUCCESS' and
                        ((target_type='WORK_TASK' and target_id=:target) or (target_type='WORK_TASK_AUDIT' and (target_id in (select * from audit_ids) or details_json->>'workTaskId'=:target)))
                ) both_sources order by id,occurred_at
            ), combined as (select
            """+AUDIT_COLUMNS+"""
            from retained
            union all select 'task:'||id::text,occurred_at,event_type,actor_id,
                details_json #>> '{_activity,actor,name}',details_json #>> '{_activity,actor,role}',
                details_json #>> '{_activity,cycle}',details_json #>> '{_activity,evidenceVersion}',department_id::text,
                details_json #>> '{_activity,correlationId}',null,
                CASE WHEN event_type='STATUS_CHANGED' THEN 'Status: '||COALESCE(previous_status,'Unknown')||' → '||current_status ELSE NULL END
            from workboard_activity_event where work_task_id=:id
            union all select 'notification:'||n.id::text,n.sent_at,'COMMENT_NOTIFICATION',n.sender_user_id::text,n.sender_name,null,
                null,null,t.department_id::text,null,n.delivery_status,null
            from internal_call_notification n join task_comment c on n.message=
                'Comment added to worksheet '||c.work_task_id::text||'. Open Workboard to view it. Reference '||c.id::text||'.'
                join department_work_task t on t.id=c.work_task_id
            where c.work_task_id=:id
            )
            """;
        return read(relation,id,page,size);
    }
    @Override public Page appointment(UUID id,int page,int size) {
        String relation="""
            with retained as (select distinct on (id) * from (
                select * from audit_event where outcome='SUCCESS' and target_type='APPOINTMENT' and target_id=:target
                union all select * from audit_event_history where outcome='SUCCESS' and target_type='APPOINTMENT' and target_id=:target
            ) both_sources order by id,occurred_at),combined as (select
            """+AUDIT_COLUMNS+"""
            from retained
            union all select 'checkpoint:'||id::text,occurred_at,event_type,actor_id,
                details_json #>> '{_activity,actor,name}',details_json #>> '{_activity,actor,role}',null,null,department_id::text,null,null,null
            from visitor_checkpoint_event where appointment_id=:id
            union all select 'notification:'||id::text,COALESCE(sent_at,created_at),'VISIT_EMAIL_NOTIFICATION',null,null,null,null,null,null,null,
                CASE status WHEN 'SENT' THEN 'SENT' WHEN 'DEAD' THEN 'FAILED' ELSE 'QUEUED' END,null
            from notification_outbox where event_key='appointment-requested:'||:target
                or event_key like 'appointment-approval:'||:target||':%'
                or event_key='appointment-approval:'||:target
                or event_key like 'appointment-cancellation-otp:'||:target||':%'
            )
            """;
        return read(relation,id,page,size);
    }
    private Page read(String relation,UUID id,int page,int size) {
        if(page<0||page>100000||size<1||size>100)throw new BusinessException("ACTIVITY_PAGE_INVALID","Activity page must be nonnegative and size between 1 and 100",HttpStatus.BAD_REQUEST);
        var parameters=new MapSqlParameterSource("id",id).addValue("target",id.toString()).addValue("limit",size+1).addValue("offset",(long)page*size);
        var rows=jdbc.query(relation+" select * from combined order by occurred_at,event_id limit :limit offset :offset",parameters,(rs,n)->new Event(rs.getString("event_id"),rs.getTimestamp("occurred_at").toInstant(),rs.getString("event_type"),title(rs.getString("event_type")),
                new Actor(rs.getString("actor_id"),rs.getString("actor_name"),rs.getString("actor_role"),rs.getString("actor_name")!=null),number(rs,"cycle"),longNumber(rs,"evidence_version"),uuid(rs,"department_id"),rs.getString("correlation_id"),rs.getString("delivery_status"),rs.getString("note")));
        return new Page(List.copyOf(rows.subList(0,Math.min(size,rows.size()))),page,size,rows.size()>size);
    }
    private static Integer number(ResultSet rs,String column)throws SQLException {String value=rs.getString(column);return value==null?null:Integer.valueOf(value);}
    private static Long longNumber(ResultSet rs,String column)throws SQLException {String value=rs.getString(column);return value==null?null:Long.valueOf(value);}
    private static UUID uuid(ResultSet rs,String column)throws SQLException {String value=rs.getString(column);return value==null?null:UUID.fromString(value);}
    private static String title(String type) {
        return switch(type) {
            case "TASK_CREATED","WORK_TASK_CREATED","WORK_TASK_ASSIGNED" -> "Worksheet assigned";
            case "RESPONSIBILITY_REASSIGNED","WORK_TASK_REASSIGNED" -> "Responsibility reassigned";
            case "STATUS_CHANGED" -> "Delivery stage changed";
            case "TASK_UPDATED" -> "Worksheet updated";
            case "WORK_TASK_COMMENT_CREATED" -> "Comment added";
            case "WORK_TASK_COMMENT_EDITED" -> "Comment edited";
            case "WORK_TASK_COMMENT_REMOVED" -> "Comment removed";
            case "COMMENT_NOTIFICATION" -> "Comment notification";
            case "VISIT_EMAIL_NOTIFICATION" -> "Visit email notification";
            case "CHECKED_IN" -> "Visitor checked in";
            case "CHECKED_OUT" -> "Visitor checked out";
            default -> {
                String label=type.replaceFirst("^(WORK_TASK_|WORK_INSIGHT_|VISITOR_|APPOINTMENT_)","").replace('_',' ').toLowerCase(Locale.ROOT);
                yield label.isBlank()?"Activity recorded":Character.toUpperCase(label.charAt(0))+label.substring(1);
            }
        };
    }
}
