package com.brainserve.appointment.workinsight.api;

import com.brainserve.appointment.worktask.api.WorkTaskHandoverController.HandoverInvalidated;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Synchronous audit invalidation after a full immutable handover snapshot is persisted. */
@Service
public class WorkTaskAuditBridge {
    private final JdbcTemplate jdbc;
    public WorkTaskAuditBridge(JdbcTemplate jdbc) {this.jdbc=jdbc;}
    @EventListener
    @Transactional(propagation=Propagation.MANDATORY)
    public void invalidate(HandoverInvalidated event) {
        Boolean retained=jdbc.queryForObject("select exists(select 1 from work_task_handover where id=? and task_id=?)",Boolean.class,event.handoverId(),event.taskId());
        if(!Boolean.TRUE.equals(retained)) throw new IllegalStateException("Handover cannot invalidate an audit without its retained snapshot");
        // Both task and audit rows are locked by the writer. Exact JSON equality also prevents
        // silently discarding a review that changed since the snapshot was captured.
        jdbc.update("""
                delete from work_task_audit_record a using work_task_handover h
                where h.id=? and h.task_id=? and a.work_task_id=h.task_id
                  and to_jsonb(a)=h.previous_audit_snapshot and a.audit_status<>'CEO_APPROVED'
                """,event.handoverId(),event.taskId());
        if(Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from work_task_audit_record where work_task_id=?)",Boolean.class,event.taskId())))
            throw new IllegalStateException("The current audit changed or is closed; the handover must be retried");
    }
}
