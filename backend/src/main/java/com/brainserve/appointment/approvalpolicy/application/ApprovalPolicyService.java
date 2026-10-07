package com.brainserve.appointment.approvalpolicy.application;

import com.brainserve.appointment.approvalpolicy.api.ReviewDelegations;
import com.brainserve.appointment.audit.api.AuditService;
import com.brainserve.appointment.iam.api.CurrentAccountAuthority;
import com.brainserve.appointment.notification.api.PolicyNotifications;
import com.brainserve.appointment.shared.application.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
public class ApprovalPolicyService {
    private final JdbcTemplate jdbc;
    private final CurrentAccountAuthority authority;
    private final ReviewDelegations reviews;
    private final PolicyNotifications notifications;
    private final TransactionOperations transactions;
    private final AuditService audit;
    private final boolean enabled;
    public ApprovalPolicyService(JdbcTemplate jdbc, CurrentAccountAuthority authority, ReviewDelegations reviews,
            PolicyNotifications notifications, TransactionOperations transactions, AuditService audit,
            @Value("${brainserve.approval-reminders.enabled:true}") boolean enabled) {
        this.jdbc=jdbc; this.authority=authority; this.reviews=reviews; this.notifications=notifications;
        this.transactions=transactions; this.audit=audit; this.enabled=enabled;
    }
    public record Policy(long id,String kind,String stage,long version,boolean enabled,int deadlineMinutes,
                         int reminderMinutes,String escalationRole) {}
    public record QueueItem(UUID id,String kind,UUID resourceId,UUID departmentId,String stage,Instant enteredAt,
                            boolean entryKnown,Instant deadlineAt,long policyVersion,String clock,String escalationRole,
                            String title,String detail,String status,long resourceVersion,boolean canReview,
                            boolean canDelegate,boolean canRevokeDelegation,boolean delegated,UUID delegationId,UUID delegateId,Instant delegationExpiresAt) {}
    public record Queue(List<QueueItem> items,int page,boolean hasMore,Instant generatedAt) {}
    public record Candidate(UUID userId,String name) {}
    public record Grant(UUID id,UUID stageId,UUID delegatorId,UUID delegateId,Instant expiresAt,String reason,Instant revokedAt) {}

    @Transactional(readOnly=true)
    public List<Policy> policies(UUID actor) {
        requireAdmin(actor);
        return jdbc.query("select distinct on(kind,stage) * from approval_policy order by kind,stage,version desc",(rs,n)->
                new Policy(rs.getLong("id"),rs.getString("kind"),rs.getString("stage"),rs.getLong("version"),rs.getBoolean("enabled"),
                        rs.getInt("deadline_minutes"),rs.getInt("reminder_minutes"),rs.getString("escalation_role")));
    }
    @Transactional
    public List<Policy> savePolicy(UUID actor,Policy value) {
        requireAdmin(actor);
        if(value==null || !Set.of("WORK","VISIT").contains(value.kind()) || !Set.of("HOST","TEAM_LEAD","HR_ADMIN","MANAGER","CEO").contains(value.stage())
                || value.kind().equals("WORK")&&value.stage().equals("HOST") || value.deadlineMinutes()<5 || value.deadlineMinutes()>43200
                || value.reminderMinutes()<5 || value.reminderMinutes()>10080 || !Set.of("HR_ADMIN","MANAGER","CEO").contains(value.escalationRole())) invalid("Use valid stage, elapsed deadline, reminder cadence and escalation role.");
        jdbc.query("select id from iam_user_account where id=? for update",(rs,n)->0,actor);
        requireAdmin(actor);
        jdbc.queryForObject("select pg_advisory_xact_lock(hashtextextended(?,11))",Object.class,value.kind()+":"+value.stage());
        Long current=jdbc.queryForObject("select max(version) from approval_policy where kind=? and stage=?",Long.class,value.kind(),value.stage());
        if(current==null||current!=value.version()) conflict();
        jdbc.update("insert into approval_policy(kind,stage,version,enabled,deadline_minutes,reminder_minutes,escalation_role,created_by) values(?,?,?,?,?,?,?,?)",
                value.kind(),value.stage(),current+1,value.enabled(),value.deadlineMinutes(),value.reminderMinutes(),value.escalationRole(),actor);
        audit.record("APPROVAL_POLICY_VERSION_CREATED","APPROVAL_POLICY",value.kind()+":"+value.stage(),"{\"clock\":\"ELAPSED\",\"version\":"+(current+1)+"}");
        return policies(actor);
    }

    @Transactional(readOnly=true)
    public Queue queue(UUID actor,boolean overdue,int page) {
        var current=authority.requireActive(actor);
        if(page<0||page>10000) invalid("Queue page is outside the supported range.");
        boolean admin=current.role().equals("ROLE_SYSTEM_ADMIN");
        UUID department=current.employeeId()==null?null:jdbc.queryForObject("select department_id from employee where id=?",UUID.class,current.employeeId());
        // Pages scan a bounded source window. Eligibility filters can yield an empty page with more source pages.
        var ids=jdbc.query("""
                select s.id from approval_stage s where s.closed_at is null
                and (? or ((s.stage=? or s.stage='HOST') and (s.department_id=? or s.stage='CEO')))
                and (not ? or s.deadline_at<=now())
                order by s.deadline_at nulls last,s.entered_at,s.id limit 21 offset ?
                """,(rs,n)->rs.getObject(1,UUID.class),admin,current.role().replace("ROLE_",""),department,overdue,page*20);
        List<QueueItem> items=ids.stream().limit(20).map(id->item(actor,id,admin)).filter(Objects::nonNull).toList();
        return new Queue(items,page,ids.size()>20,Instant.now());
    }
    private QueueItem item(UUID actor,UUID stageId,boolean admin) {
        var s=reviews.stage(stageId); if(s==null) return null;
        boolean owner=Objects.equals(actor,reviews.reviewer(s));
        boolean delegated=!owner && reviews.allows(actor,s.kind(),s.resourceId(),s.stage());
        if(!admin&&!owner&&!delegated) return null;
        var grants=jdbc.query("select * from approval_delegation where stage_id=? and revoked_at is null and expires_at>now()",(rs,n)->
                new Grant(rs.getObject("id",UUID.class),stageId,rs.getObject("delegator_id",UUID.class),rs.getObject("delegate_id",UUID.class),
                        rs.getTimestamp("expires_at").toInstant(),rs.getString("reason"),null),stageId);
        Grant grant=grants.isEmpty()?null:grants.getFirst();
        return jdbc.queryForObject("""
                select s.*,p.version,p.escalation_role,
                coalesce(t.title,a.reference_number) title,coalesce(t.description,a.purpose) description,
                coalesce(t.employee_update,a.visitor_name) update_text,coalesce(t.status,a.status) status,
                coalesce(t.version,a.version) resource_version
                from approval_stage s join approval_policy p on p.id=s.policy_id
                left join department_work_task t on s.kind='WORK' and t.id=s.resource_id
                left join appointment a on s.kind='VISIT' and a.id=s.resource_id where s.id=?
                """,(rs,n)->new QueueItem(s.id(),s.kind(),s.resourceId(),s.departmentId(),s.stage(),rs.getTimestamp("entered_at").toInstant(),
                        rs.getBoolean("entry_known"),rs.getTimestamp("deadline_at")==null?null:rs.getTimestamp("deadline_at").toInstant(),
                        rs.getLong("version"),"ELAPSED",rs.getString("escalation_role"),admin?s.kind()+" "+s.resourceId():rs.getString("title"),
                        admin?"Business details require the eligible reviewer.":rs.getString("description")+"\n"+Objects.toString(rs.getString("update_text"),""),
                        rs.getString("status"),rs.getLong("resource_version"),owner||delegated,
                        owner&&!Set.of("CEO","HOST").contains(s.stage()),grant!=null&&(owner||admin),delegated,grant==null?null:grant.id(),
                        grant==null?null:grant.delegateId(),grant==null?null:grant.expiresAt()),stageId);
    }

    @Transactional(readOnly=true)
    public List<Candidate> candidates(UUID actor,UUID stageId) {
        var s=ownDelegatableStage(actor,stageId);
        return jdbc.query("""
                select u.id,u.full_name from iam_user_account u join employee e on e.id=u.employee_id
                join iam_user_role r on r.user_id=u.id where e.department_id=? and r.role_name=?
                and u.id<>? and u.enabled and u.account_status='ACTIVE' and not u.archived order by u.full_name,u.id limit 100
                """,(rs,n)->new Candidate(rs.getObject(1,UUID.class),rs.getString(2)),s.departmentId(),"ROLE_"+s.stage(),actor)
                .stream().filter(c->reviews.eligible(c.userId(),s)).toList();
    }
    @Transactional
    public Grant delegate(UUID actor,UUID stageId,UUID delegate,Instant expires,String reason) {
        var s=ownDelegatableStage(actor,stageId);
        reviews.lockPolicy(s,actor);
        jdbc.query("select id from approval_stage where id=? for update",(rs,n)->0,stageId);
        s=ownDelegatableStage(actor,stageId);
        Instant now=Instant.now();
        if(delegate==null||delegate.equals(actor)||expires==null||!expires.isAfter(now)||expires.isAfter(now.plusSeconds(30*86400L))
                ||reason==null||reason.trim().length()<5||reason.trim().length()>500||!reviews.eligible(delegate,s)) invalid("Choose an eligible reviewer, a reason and an expiry within 30 days.");
        jdbc.update("update approval_delegation set revoked_at=now() where stage_id=? and revoked_at is null and expires_at<=now()",stageId);
        if(Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from approval_delegation where stage_id=? and revoked_at is null)",Boolean.class,stageId))) conflict();
        UUID id=UUID.randomUUID();
        jdbc.update("insert into approval_delegation(id,stage_id,delegator_id,delegate_id,expires_at,reason) values(?,?,?,?,?,?)",id,stageId,actor,delegate,Timestamp.from(expires),reason.trim());
        audit.record("APPROVAL_DELEGATION_CREATED","APPROVAL_DELEGATION",id.toString(),"{\"stageId\":\""+stageId+"\"}");
        return new Grant(id,stageId,actor,delegate,expires,reason.trim(),null);
    }
    @Transactional
    public void revoke(UUID actor,UUID id) {
        var current=authority.requireActive(actor);
        var owners=jdbc.query("select delegator_id from approval_delegation where id=? for update",(rs,n)->rs.getObject(1,UUID.class),id);
        if(owners.size()!=1||!owners.getFirst().equals(actor)&&!current.role().equals("ROLE_SYSTEM_ADMIN")) denied();
        jdbc.update("update approval_delegation set revoked_at=coalesce(revoked_at,now()) where id=?",id);
        audit.record("APPROVAL_DELEGATION_REVOKED","APPROVAL_DELEGATION",id.toString(),"{}");
    }
    private ReviewDelegations.Stage ownDelegatableStage(UUID actor,UUID stageId) {
        authority.requireActive(actor); var s=reviews.stage(stageId);
        if(s==null||Set.of("CEO","HOST").contains(s.stage())||!Objects.equals(actor,reviews.reviewer(s))) denied();
        return s;
    }

    @Scheduled(fixedDelayString="${brainserve.approval-reminders.poll-ms:15000}")
    public void dispatch() {
        if(!enabled) return;
        var ids=jdbc.query("select id from approval_stage where closed_at is null and deadline_at<=now() and coalesce(next_reminder_at,deadline_at)<=now() order by next_reminder_at nulls first,deadline_at,id limit 50",(rs,n)->rs.getObject(1,UUID.class));
        for(UUID id:ids) transactions.executeWithoutResult(status->remind(id));
    }
    private void remind(UUID id) {
        // Serialize worker retries, then use the writer's policy/resource order before any stage/FK writes.
        if(!Boolean.TRUE.equals(jdbc.queryForObject("select pg_try_advisory_xact_lock(hashtextextended(?::text,10))",Boolean.class,id))) return;
        var s=reviews.stage(id); if(s==null) return;
        reviews.lockPolicy(s,null);
        s=reviews.stage(id); if(s==null) return;
        jdbc.update("update approval_stage set next_reminder_at=now()+interval '1 minute' where id=? and closed_at is null",id);
        UUID reviewer=reviews.reviewer(s); if(reviewer==null) return;
        var senders=jdbc.query("select u.id from iam_user_account u join iam_user_role r on r.user_id=u.id where r.role_name='ROLE_SYSTEM_ADMIN' and u.enabled and u.account_status='ACTIVE' and not u.archived order by u.id limit 1",(rs,n)->rs.getObject(1,UUID.class));
        if(senders.isEmpty()) return;
        UUID sender=senders.getFirst();
        var policies=jdbc.query("select p.reminder_minutes,p.escalation_role,s.deadline_at from approval_stage s join approval_policy p on p.id=s.policy_id where s.id=? and s.closed_at is null and s.deadline_at<=now()",(rs,n)->
                new Object[]{rs.getInt(1),rs.getString(2),rs.getTimestamp(3).toInstant()},id);
        if(policies.isEmpty()) return;
        Object[] p=policies.getFirst();
        long window=Math.max(0,java.time.Duration.between((Instant)p[2],Instant.now()).toSeconds()/((Integer)p[0]*60L));
        Set<UUID> recipients=new LinkedHashSet<>(); recipients.add(reviewer);
        var delegates=jdbc.query("select delegate_id from approval_delegation where stage_id=? and revoked_at is null and expires_at>now()",(rs,n)->rs.getObject(1,UUID.class),id);
        for(UUID delegate:delegates) if(reviews.allows(delegate,s.kind(),s.resourceId(),s.stage())) recipients.add(delegate);
        UUID escalated=reviews.reviewer(new ReviewDelegations.Stage(id,s.kind(),s.resourceId(),s.departmentId(),(String)p[1]));
        if(escalated!=null) recipients.add(escalated);
        for(UUID recipient:recipients) {
            if(recipient.equals(sender)||Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from approval_reminder_receipt where stage_id=? and window_number=? and recipient_id=?)",Boolean.class,id,window,recipient))) continue;
            if(reviews.stage(id)==null) return;
            UUID notice=notifications.enqueue(sender,recipient,"Approval stage "+s.stage()+" is overdue for "+s.kind()+" "+s.resourceId()+". Open your authorized approval queue. Escalation does not change approval authority.");
            jdbc.update("insert into approval_reminder_receipt(stage_id,window_number,recipient_id,notification_id) values(?,?,?,?)",id,window,recipient,notice);
        }
        jdbc.update("update approval_stage set next_reminder_at=deadline_at+make_interval(mins=>?::integer) where id=? and closed_at is null",(window+1)*(Integer)p[0],id);
    }
    private void requireAdmin(UUID actor) {
        if(!authority.requireActive(actor).role().equals("ROLE_SYSTEM_ADMIN")) denied();
    }
    private static void invalid(String message) { throw new BusinessException("INVALID_APPROVAL_POLICY",message,HttpStatus.UNPROCESSABLE_ENTITY); }
    private static void conflict() { throw new BusinessException("APPROVAL_POLICY_CONFLICT","This policy or delegation changed. Reload before continuing.",HttpStatus.CONFLICT); }
    private static void denied() { throw new BusinessException("APPROVAL_POLICY_DENIED","Your current authority does not permit this action.",HttpStatus.FORBIDDEN); }
}
