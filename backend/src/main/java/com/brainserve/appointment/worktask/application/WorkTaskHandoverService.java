package com.brainserve.appointment.worktask.application;

import com.brainserve.appointment.audit.api.AuditService;
import com.brainserve.appointment.iam.api.CurrentAccountAuthority;
import com.brainserve.appointment.notification.api.HandoverNotifications;
import com.brainserve.appointment.shared.application.BusinessException;
import com.brainserve.appointment.worktask.api.TaskActivityAccess;
import com.brainserve.appointment.worktask.api.WorkTaskHandoverController.HandoverInvalidated;
import com.brainserve.appointment.worktask.domain.DepartmentWorkTask;
import com.brainserve.appointment.worktask.infrastructure.DepartmentWorkTaskRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Serializes a revised assignment with current account, department, leave and audit policy. */
@Service
public class WorkTaskHandoverService {
    private final DepartmentWorkTaskRepository tasks;
    private final CurrentAccountAuthority authority;
    private final TaskActivityAccess access;
    private final EntityManager em;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final AuditService audit;
    private final HandoverNotifications notices;
    private final ApplicationEventPublisher events;
    private final ZoneId officeZone;
    public WorkTaskHandoverService(DepartmentWorkTaskRepository tasks,CurrentAccountAuthority authority,
            TaskActivityAccess access,EntityManager em,JdbcTemplate jdbc,ObjectMapper mapper,AuditService audit,
            HandoverNotifications notices,ApplicationEventPublisher events,
            @Value("${brainserve.appointment.office-zone:Asia/Kolkata}") String officeZone) {
        this.tasks=tasks;this.authority=authority;this.access=access;this.em=em;this.jdbc=jdbc;this.mapper=mapper;
        this.audit=audit;this.notices=notices;this.events=events;this.officeZone=ZoneId.of(officeZone);
    }
    @Transactional(readOnly=true)
    public View get(UUID actor,UUID id) {
        var before=access.require(actor,id);
        var task=require(id);
        if(before.taskVersion()!=task.getVersion()) conflict();
        View result=view(actor,task);
        access.revalidate(actor,before);
        return result;
    }
    @Transactional
    public View handover(UUID actor,UUID id,Change request) {
        var scope=requireWriter(actor);
        var before=access.require(actor,id);
        if(!scope.departmentId().equals(before.departmentId())) TaskActivityAccess.missing();
        DepartmentWorkTask task=require(id);
        // The same deterministic policy locks are used by recurring work creation. The task
        // lock then serializes competing transfers, delivery changes and all audit decisions.
        lockPolicy(actor,task.getDepartmentId(),task.getEmployeeId(),request==null?null:request.targetEmployeeId());
        em.refresh(task,LockModeType.PESSIMISTIC_WRITE);
        if(!scope.equals(requireWriter(actor))) denied();
        access.require(actor,id);
        if(request==null||request.expectedVersion()==null||request.expectedVersion()<0) invalid("The observed task version is required");
        if(request.expectedVersion()!=task.getVersion()) conflict();
        String unavailable=unavailable(actor,task);
        if(unavailable!=null) throw new BusinessException("WORK_TASK_HANDOVER_UNAVAILABLE",unavailable,HttpStatus.CONFLICT);
        if(request.targetEmployeeId()==null||request.reason()==null||request.reason().isBlank()||request.reason().trim().length()>1000)
            invalid("Choose an eligible assignee and provide a reason of at most 1000 characters");
        Instant now=Instant.now();
        if(request.effectiveAt()!=null&&request.effectiveAt().isAfter(now)) invalid("Only immediate handover is supported; future scheduling is unavailable");
        var candidate=eligible(actor,task).stream().filter(e->e.employeeId().equals(request.targetEmployeeId())).findFirst()
                .orElseThrow(()->new BusinessException("WORK_TASK_HANDOVER_TARGET_INELIGIBLE","The target must be an active eligible department assignee who is available today",HttpStatus.UNPROCESSABLE_ENTITY));
        UUID targetUser=userForEmployee(candidate.employeeId());
        String actorName=accountName(actor),oldName=employeeName(task.getEmployeeId());
        UUID previousEmployee=task.getEmployeeId();
        UUID previousUser=activeUserForEmployee(previousEmployee);
        UUID handoverId=UUID.randomUUID();
        String previousTask=jdbc.queryForObject("select to_jsonb(t)::text from department_work_task t where id=?",String.class,id);
        List<String> priorAudits=jdbc.query("select to_jsonb(a)::text from work_task_audit_record a where work_task_id=? for update",(rs,n)->rs.getString(1),id);
        String previousAudit=priorAudits.isEmpty()?null:priorAudits.getFirst();
        long revision=task.getAssignmentRevision()+1;
        jdbc.update("""
                insert into work_task_handover(id,task_id,from_employee_id,from_name,to_employee_id,to_name,
                  actor_user_id,actor_name,reason,effective_at,occurred_at,assignment_revision,
                  previous_submission_version,previous_task_snapshot,previous_audit_snapshot)
                values (?,?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?::jsonb)
                """,handoverId,id,previousEmployee,oldName,candidate.employeeId(),candidate.displayName(),actor,actorName,
                request.reason().trim(),Timestamp.from(now),Timestamp.from(now),revision,task.getSubmissionVersion(),previousTask,previousAudit);
        // This synchronous listener lives in workinsight and depends only on the public task
        // API. Its failure rolls back the snapshot and assignment together, without a module cycle.
        events.publishEvent(new HandoverInvalidated(handoverId,id));
        if(Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from work_task_audit_record where work_task_id=?)",Boolean.class,id)))
            throw new IllegalStateException("The prior audit was not invalidated; handover cannot proceed");
        task.handover(candidate.employeeId(),targetUser,now);
        tasks.flush();
        if(previousUser!=null) notices.enqueue("handover:"+handoverId+":from",actor,previousUser,actorName,accountName(previousUser),
                "Worksheet "+id+": your assignment for ‘"+task.getTitle()+"’ was handed over. Prior submissions remain retained; current participant access has ended.");
        notices.enqueue("handover:"+handoverId+":to",actor,targetUser,actorName,accountName(targetUser),
                "Worksheet "+id+": ‘"+task.getTitle()+"’ was handed over to you. Review retained submissions and submit a new delivery under your assignment.");
        try { audit.record("WORK_TASK_HANDED_OVER","WORK_TASK",id.toString(),mapper.writeValueAsString(Map.of(
                "handoverId",handoverId,"fromEmployeeId",previousEmployee,"toEmployeeId",candidate.employeeId(),
                "reason",request.reason().trim(),"assignmentRevision",revision,"reworkCycle",task.getReworkCycle()))); }
        catch(com.fasterxml.jackson.core.JsonProcessingException ex) {throw new IllegalStateException("Handover audit could not be serialized",ex);}
        if(!scope.equals(requireWriter(actor))) denied();
        return view(actor,task);
    }
    private View view(UUID actor,DepartmentWorkTask task) {
        access.policy(actor,task.getId(),task.getDepartmentId(),task.getEmployeeId(),task.getTeamLeadUserId(),task.getAssigneeRole(),task.getStatus().name());
        String reason=unavailable(actor,task);
        List<History> rows=jdbc.query("select * from work_task_handover where task_id=? order by assignment_revision desc limit 101",(rs,n)->
                new History(rs.getObject("id",UUID.class),rs.getObject("from_employee_id",UUID.class),rs.getString("from_name"),rs.getObject("to_employee_id",UUID.class),rs.getString("to_name"),
                    rs.getObject("actor_user_id",UUID.class),rs.getString("actor_name"),rs.getString("reason"),rs.getTimestamp("effective_at").toInstant(),rs.getTimestamp("occurred_at").toInstant(),
                    rs.getLong("assignment_revision"),rs.getObject("previous_submission_version",Long.class)),task.getId());
        return new View(task.getId(),task.getVersion(),task.getEmployeeId(),task.getOriginalEmployeeId(),employeeName(task.getEmployeeId()),employeeName(task.getOriginalEmployeeId()),
                reason==null,reason,reason==null?eligible(actor,task):List.of(),List.copyOf(rows.subList(0,Math.min(100,rows.size()))),rows.size()>100);
    }
    private String unavailable(UUID actor,DepartmentWorkTask task) {
        var current=authority.requireActive(actor);
        if(!Set.of("ROLE_HR_ADMIN","ROLE_TEAM_LEAD").contains(current.role())||!current.permissions().contains("WORK_TASK_CREATE"))
            return "Only the currently assigned HR or Team Lead with work creation permission can hand over work";
        var scope=authority.requireWorkScope(actor);
        if(!scope.departmentId().equals(task.getDepartmentId())) TaskActivityAccess.missing();
        if("ROLE_TEAM_LEAD".equals(current.role())&&(!actor.equals(task.getTeamLeadUserId())||!"EMPLOYEE".equals(task.getAssigneeRole())))
            return "A Team Lead can hand over only their assigned Employee tasks";
        if(Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from work_task_audit_record where work_task_id=? and audit_status='CEO_APPROVED')",Boolean.class,task.getId())))
            return "CEO-approved work is closed and cannot be handed over";
        return null;
    }
    private List<EligibleAssignee> eligible(UUID actor,DepartmentWorkTask task) {
        LocalDate today=LocalDate.now(officeZone);
        return jdbc.query("""
                select e.id,e.display_name,a.id user_id from employee e join iam_user_account a on a.employee_id=e.id
                where e.department_id=? and e.status='ACTIVE' and a.enabled and a.account_status='ACTIVE' and not a.archived
                  and e.id<>? and a.id<>? and (select count(*) from iam_user_role r where r.user_id=a.id)=1
                  and exists(select 1 from iam_user_role r where r.user_id=a.id and r.role_name=?)
                  and not exists(select 1 from iam_user_permission_deny p where p.user_id=a.id and p.permission_name in ('WORK_TASK_READ','WORK_TASK_PROGRESS'))
                  and not exists(select 1 from employee_leave_request l where l.employee_id=e.id and l.status='APPROVED' and ? between l.start_date and l.end_date)
                  and (?='EMPLOYEE' or exists(select 1 from department_team_lead d where d.department_id=e.department_id and d.active and d.team_lead_user_id=a.id and d.team_lead_employee_id=e.id))
                order by lower(e.display_name),e.id
                """,(rs,n)->new EligibleAssignee(rs.getObject("id",UUID.class),rs.getString("display_name"),task.getAssigneeRole()),task.getDepartmentId(),task.getEmployeeId(),actor,
                "ROLE_"+task.getAssigneeRole(),today,task.getAssigneeRole());
    }
    private CurrentAccountAuthority.WorkScope requireWriter(UUID actor) {
        var scope=authority.requireWorkScope(actor);
        if(!Set.of("ROLE_HR_ADMIN","ROLE_TEAM_LEAD").contains(scope.authority().role())||!scope.authority().permissions().contains("WORK_TASK_CREATE")) denied();
        return scope;
    }
    private void lockPolicy(UUID actor,UUID department,UUID previous,UUID target) {
        jdbc.query("select id from org_department where id=? for update",(rs,n)->0,department);
        jdbc.query("select id from department_hr_assignment where department_id=? order by id for update",(rs,n)->0,department);
        jdbc.query("select id from department_team_lead where department_id=? order by id for update",(rs,n)->0,department);
        List<UUID> users=jdbc.query("""
                select id from iam_user_account where id=? or employee_id in (select id from employee where department_id=?)
                or employee_id=? or employee_id=? order by id for update
                """,(rs,n)->rs.getObject(1,UUID.class),actor,department,previous,target);
        for(UUID user:users) {
            jdbc.query("select user_id from iam_user_role where user_id=? for update",(rs,n)->0,user);
            jdbc.query("select user_id from iam_user_permission_grant where user_id=? for update",(rs,n)->0,user);
            jdbc.query("select user_id from iam_user_permission_deny where user_id=? for update",(rs,n)->0,user);
        }
        // FK-backed new leave requests block on these employee locks; existing pending leave
        // decisions block on the explicit leave locks, so target availability cannot change.
        jdbc.query("select id from employee where department_id=? or id=? or id=? or id in(select employee_id from iam_user_account where id=?) order by id for update",(rs,n)->0,department,previous,target,actor);
        jdbc.query("select id from employee_leave_request where employee_id=? order by id for update",(rs,n)->0,target);
    }
    private DepartmentWorkTask require(UUID id) {return tasks.findById(id).orElseThrow(()->new BusinessException("WORK_TASK_NOT_FOUND","Work task was not found",HttpStatus.NOT_FOUND));}
    private String employeeName(UUID id) {var names=jdbc.query("select display_name from employee where id=?",(rs,n)->rs.getString(1),id);return names.isEmpty()?"Former assignee":names.getFirst();}
    private UUID userForEmployee(UUID id) {var users=jdbc.query("select id from iam_user_account where employee_id=?",(rs,n)->rs.getObject(1,UUID.class),id);return users.isEmpty()?null:users.getFirst();}
    private UUID activeUserForEmployee(UUID id) {var users=jdbc.query("select a.id from iam_user_account a join employee e on e.id=a.employee_id where e.id=? and e.status='ACTIVE' and a.enabled and a.account_status='ACTIVE' and not a.archived",(rs,n)->rs.getObject(1,UUID.class),id);return users.isEmpty()?null:users.getFirst();}
    private String accountName(UUID id) {var names=jdbc.query("select full_name from iam_user_account where id=?",(rs,n)->rs.getString(1),id);return names.isEmpty()?"Former account":names.getFirst();}
    private static void invalid(String message) {throw new BusinessException("WORK_TASK_HANDOVER_INVALID",message,HttpStatus.UNPROCESSABLE_ENTITY);}
    private static void conflict() {throw new BusinessException("WORK_TASK_VERSION_CONFLICT","This worksheet changed. Reload and review it before handing it over",HttpStatus.CONFLICT);}
    private static void denied() {throw new BusinessException("WORK_TASK_PERMISSION_DENIED","Your current permissions do not allow handover",HttpStatus.FORBIDDEN);}
    public record Change(@jakarta.validation.constraints.NotNull @jakarta.validation.constraints.PositiveOrZero Long expectedVersion,@jakarta.validation.constraints.NotNull UUID targetEmployeeId,
            @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max=1000) String reason,Instant effectiveAt) {}
    public record EligibleAssignee(UUID employeeId,String displayName,String role) {}
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
    public record History(UUID id,UUID fromEmployeeId,String fromName,UUID toEmployeeId,String toName,UUID actorUserId,String actorName,String reason,Instant effectiveAt,Instant occurredAt,long assignmentRevision,Long previousSubmissionVersion) {}
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
    public record View(UUID taskId,long taskVersion,UUID currentEmployeeId,UUID originalEmployeeId,String currentAssigneeName,String originalAssigneeName,boolean canHandover,String unavailableReason,List<EligibleAssignee> eligibleAssignees,List<History> history,boolean historyTruncated) {}
}
