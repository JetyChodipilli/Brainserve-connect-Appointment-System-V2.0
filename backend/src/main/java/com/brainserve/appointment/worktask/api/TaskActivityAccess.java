package com.brainserve.appointment.worktask.api;

import com.brainserve.appointment.iam.api.CurrentAccountAuthority;
import com.brainserve.appointment.shared.application.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.*;

/** One current account/task policy for planning, activity and scoped search. */
@Service
public class TaskActivityAccess {
    private final CurrentAccountAuthority authority;
    private final JdbcTemplate jdbc;
    private com.brainserve.appointment.approvalpolicy.api.ReviewDelegations reviewDelegations;
    @org.springframework.beans.factory.annotation.Autowired
    public void reviewDelegations(com.brainserve.appointment.approvalpolicy.api.ReviewDelegations value) {reviewDelegations=value;}
    public TaskActivityAccess(CurrentAccountAuthority authority, JdbcTemplate jdbc) { this.authority=authority; this.jdbc=jdbc; }

    public void preauthorize(UUID actor) {
        var a=authority.requireActive(actor);
        if ("ROLE_CEO".equals(a.role())) { if(!a.permissions().contains("WORK_INSIGHT_CEO_APPROVE")) denied(); return; }
        if(!Set.of("ROLE_EMPLOYEE","ROLE_TEAM_LEAD","ROLE_HR_ADMIN","ROLE_MANAGER").contains(a.role()) || !a.permissions().contains("WORK_TASK_READ")) denied();
    }
    public Snapshot require(UUID actor,UUID id) {
        preauthorize(actor);
        var rows=jdbc.query("select id,version,department_id,employee_id,team_lead_user_id,assigned_by_user_id,assignee_role,status from department_work_task where id=?",(rs,n)->
                new Snapshot(rs.getObject("id",UUID.class),rs.getLong("version"),rs.getObject("department_id",UUID.class),rs.getObject("employee_id",UUID.class),rs.getObject("team_lead_user_id",UUID.class),rs.getObject("assigned_by_user_id",UUID.class),rs.getString("assignee_role"),rs.getString("status"),null),id);
        if(rows.size()!=1) missing();
        var t=rows.getFirst();
        return new Snapshot(t.taskId(),t.taskVersion(),t.departmentId(),t.employeeId(),t.teamLeadUserId(),t.assignedByUserId(),t.assigneeRole(),t.status(),policy(actor,t.taskId(),t.departmentId(),t.employeeId(),t.teamLeadUserId(),t.assigneeRole(),t.status()));
    }
    public void revalidate(UUID actor,Snapshot before) {
        if(!before.equals(require(actor,before.taskId()))) throw new BusinessException("WORK_TASK_VERSION_CONFLICT","This worksheet or your access changed. Reload before continuing",HttpStatus.CONFLICT);
    }
    public Access policy(UUID actor,UUID id,UUID department,UUID employeeId,UUID leadUserId,String assigneeRole,String status) {
        var current=authority.requireActive(actor);
        if(current.permissions().contains("WORK_TASK_READ") && reviewDelegations!=null
                && reviewDelegations.allows(actor,"WORK",id,current.role().replace("ROLE_","")))
            return new Access(current,null,false,false,false,false,false);
        if("ROLE_CEO".equals(current.role())) {
            if(!current.permissions().contains("WORK_INSIGHT_CEO_APPROVE")) denied();
            if(!Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from work_task_audit_record where work_task_id=? and audit_status in ('PENDING_CEO_APPROVAL','CEO_APPROVED','CEO_REWORK_REQUESTED'))",Boolean.class,id))) missing();
            return new Access(current,null,false,false,false,false,false);
        }
        var scope=authority.requireWorkScope(actor);
        if(!scope.authority().equals(current)||!current.permissions().contains("WORK_TASK_READ")) denied();
        if(!scope.departmentId().equals(department)) missing();
        boolean lead="ROLE_TEAM_LEAD".equals(current.role())&&actor.equals(leadUserId);
        boolean hr="ROLE_HR_ADMIN".equals(current.role());
        boolean employee="ROLE_EMPLOYEE".equals(current.role())&&"EMPLOYEE".equals(assigneeRole)&&Objects.equals(current.employeeId(),employeeId);
        boolean ownLead=lead&&"TEAM_LEAD".equals(assigneeRole)&&Objects.equals(current.employeeId(),employeeId);
        if(!lead&&!hr&&!employee&&!"ROLE_MANAGER".equals(current.role())) missing();
        boolean closed=Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from work_task_audit_record where work_task_id=? and audit_status='CEO_APPROVED')",Boolean.class,id));
        boolean editable=Set.of("ASSIGNED","IN_PROGRESS","CHANGES_REQUESTED","COMPLETED").contains(status)&&!closed;
        boolean manage=!closed&&(lead||hr)&&current.permissions().contains("WORK_TASK_CREATE");
        boolean progress=editable&&(employee||ownLead)&&current.permissions().contains("WORK_TASK_PROGRESS");
        return new Access(current,scope,manage,progress,manage||progress,manage,progress);
    }
    public static void missing() { throw new BusinessException("WORK_TASK_NOT_FOUND","Work task was not found",HttpStatus.NOT_FOUND); }
    public static void denied() { throw new BusinessException("WORK_TASK_PERMISSION_DENIED","Your current permissions do not allow access to this task",HttpStatus.FORBIDDEN); }
    public record Access(CurrentAccountAuthority.Authority authority,CurrentAccountAuthority.WorkScope scope,boolean manage,boolean progress,boolean blocker,boolean resolveBlocker,boolean upload) {}
    public record Snapshot(UUID taskId,long taskVersion,UUID departmentId,UUID employeeId,UUID teamLeadUserId,UUID assignedByUserId,String assigneeRole,String status,Access access) {}
}
