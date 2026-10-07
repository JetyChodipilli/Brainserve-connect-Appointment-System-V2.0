package com.brainserve.appointment.approvalpolicy.api;

import com.brainserve.appointment.iam.api.CurrentAccountAuthority;
import com.brainserve.appointment.shared.application.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Delegation changes resource scope only. It never grants a role or a permission. */
@Service
public class ReviewDelegations {
    private final JdbcTemplate jdbc;
    private final CurrentAccountAuthority authority;
    private final ZoneId officeZone;
    public ReviewDelegations(JdbcTemplate jdbc, CurrentAccountAuthority authority,
            @Value("${brainserve.appointment.office-zone:Asia/Kolkata}") String zone) {
        this.jdbc=jdbc; this.authority=authority; this.officeZone=ZoneId.of(zone);
    }
    public record Stage(UUID id, String kind, UUID resourceId, UUID departmentId, String stage) {}
    /** Same department -> assignments -> accounts -> employees -> resource order as work writers. */
    public void lockPolicy(Stage s, UUID actor) {
        jdbc.query("select id from org_department where id=? for update",(rs,n)->0,s.departmentId());
        for (String table : List.of("department_hr_assignment","department_team_lead","department_manager_assignment"))
            jdbc.query("select id from "+table+" where department_id=? order by id for update",(rs,n)->0,s.departmentId());
        var users=jdbc.query("""
                select u.id from iam_user_account u where u.id=?
                or u.employee_id in(select id from employee where department_id=?)
                or exists(select 1 from iam_user_role r where r.user_id=u.id and r.role_name in('ROLE_SYSTEM_ADMIN','ROLE_CEO'))
                order by u.id for update
                """,(rs,n)->rs.getObject(1,UUID.class),actor,s.departmentId());
        for(UUID user:users) for(String table:List.of("iam_user_role","iam_user_permission_grant","iam_user_permission_deny"))
            jdbc.query("select user_id from "+table+" where user_id=? for update",(rs,n)->0,user);
        jdbc.query("select id from employee where department_id=? or id in(select employee_id from iam_user_account where id=?) order by id for update",(rs,n)->0,s.departmentId(),actor);
        String resource=s.kind().equals("WORK")?"department_work_task":"appointment";
        jdbc.query("select id from "+resource+" where id=? for update",(rs,n)->0,s.resourceId());
    }
    public Stage stage(UUID id) {
        var rows=jdbc.query("select * from approval_stage where id=? and closed_at is null", (rs,n) ->
                new Stage(rs.getObject("id",UUID.class),rs.getString("kind"),rs.getObject("resource_id",UUID.class),
                        rs.getObject("department_id",UUID.class),rs.getString("stage")), id);
        return rows.isEmpty()?null:rows.getFirst();
    }
    public UUID reviewer(Stage s) {
        String sql=switch(s.stage()) {
            case "TEAM_LEAD" -> s.kind().equals("WORK")
                    ? "select team_lead_user_id from department_work_task where id=?"
                    : "select team_lead_user_id from department_team_lead where department_id=? and active";
            case "HR_ADMIN" -> "select hr_user_id from department_hr_assignment where department_id=? and active";
            case "MANAGER" -> "select manager_user_id from department_manager_assignment where department_id=? and active";
            case "CEO" -> "select u.id from iam_user_account u join iam_user_role r on r.user_id=u.id where r.role_name='ROLE_CEO' and u.enabled and u.account_status='ACTIVE' and not u.archived";
            case "HOST" -> "select u.id from appointment a join iam_user_account u on u.employee_id=a.host_employee_id where a.id=? and u.enabled and u.account_status='ACTIVE' and not u.archived";
            default -> null;
        };
        if(sql==null) return null;
        Object[] args=s.stage().equals("CEO")?new Object[]{}:
                new Object[]{s.stage().equals("HOST")||s.stage().equals("TEAM_LEAD")&&s.kind().equals("WORK")?s.resourceId():s.departmentId()};
        List<UUID> users=jdbc.query(sql,(rs,n)->rs.getObject(1,UUID.class),args);
        if(users.size()!=1) return null;
        return eligible(users.getFirst(),s,false)?users.getFirst():null;
    }
    public String permission(Stage s) {
        if(s.kind().equals("WORK")) return switch(s.stage()) {
            case "TEAM_LEAD" -> "WORK_TASK_REVIEW"; case "HR_ADMIN" -> "WORK_INSIGHT_AUDIT";
            case "MANAGER" -> "WORK_INSIGHT_MANAGER_APPROVE"; case "CEO" -> "WORK_INSIGHT_CEO_APPROVE"; default -> "";
        };
        return switch(s.stage()) {
            case "TEAM_LEAD" -> "TEAM_LEAD_VISIT_APPROVE"; case "HR_ADMIN" -> "HR_VISIT_APPROVE";
            case "MANAGER" -> "MANAGER_VISIT_APPROVE"; case "CEO" -> "CEO_VISIT_APPROVE";
            case "HOST" -> "APPOINTMENT_APPROVE"; default -> "";
        };
    }
    public boolean eligible(UUID actor, Stage s) {
        return eligible(actor,s,true);
    }
    private boolean eligible(UUID actor, Stage s, boolean checkLeave) {
        try {
            var a=authority.requireActive(actor);
            if(!a.permissions().contains(permission(s)) || !s.stage().equals("HOST") && !a.role().equals("ROLE_"+s.stage())) return false;
            if(s.stage().equals("CEO")) return a.employeeId()!=null && Boolean.TRUE.equals(jdbc.queryForObject(
                    "select exists(select 1 from employee e join org_department d on d.id=e.department_id where e.id=? and e.status='ACTIVE' and d.active)",Boolean.class,a.employeeId()));
            if(a.employeeId()==null || s.departmentId()==null) return false;
            Boolean current=jdbc.queryForObject("""
                    select exists(select 1 from employee e join org_department d on d.id=e.department_id
                    where e.id=? and e.department_id=? and e.status='ACTIVE' and d.active
                    and (not ? or not exists(select 1 from employee_leave_request l where l.employee_id=e.id and l.status='APPROVED' and ? between l.start_date and l.end_date)))
                    """, Boolean.class,a.employeeId(),s.departmentId(),checkLeave,LocalDate.now(officeZone));
            if(!Boolean.TRUE.equals(current)) return false;
            if(s.kind().equals("WORK") && Boolean.TRUE.equals(jdbc.queryForObject(
                    "select exists(select 1 from department_work_task where id=? and employee_id=?)",Boolean.class,s.resourceId(),a.employeeId()))) return false;
            return true;
        } catch(BusinessException denied) { return false; }
    }
    public boolean allows(UUID actor,String kind,UUID resource,String stage) { return allows(actor,kind,resource,stage,false); }
    public boolean lockAllows(UUID actor,String kind,UUID resource,String stage) { return allows(actor,kind,resource,stage,true); }
    private boolean allows(UUID actor,String kind,UUID resource,String stage,boolean lock) {
        if(stage.equals("CEO")||stage.equals("HOST")) return false;
        var grants=jdbc.query("""
                select s.id,d.delegator_id from approval_stage s join approval_delegation d on d.stage_id=s.id
                where s.kind=? and s.resource_id=? and s.stage=? and s.closed_at is null
                and d.delegate_id=? and d.revoked_at is null and d.expires_at>now()
                """+(lock?" for share of d":""),(rs,n)->new UUID[]{rs.getObject(1,UUID.class),rs.getObject(2,UUID.class)},kind,resource,stage,actor);
        if(grants.size()!=1) return false;
        Stage s=stage(grants.getFirst()[0]);
        return s!=null && Objects.equals(reviewer(s),grants.getFirst()[1]) && eligible(actor,s);
    }

    public Stage requireReviewer(UUID actor, UUID stageId) {
        Stage s=stage(stageId);
        if(s==null || !(Objects.equals(actor,reviewer(s)) || lockAllows(actor,s.kind(),s.resourceId(),s.stage())))
            throw new BusinessException("APPROVAL_STAGE_UNAVAILABLE","This stage or your current review authority changed. Reload the queue.",org.springframework.http.HttpStatus.CONFLICT);
        return s;
    }
}
