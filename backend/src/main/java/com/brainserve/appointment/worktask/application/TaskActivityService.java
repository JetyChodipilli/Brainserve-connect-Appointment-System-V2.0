package com.brainserve.appointment.worktask.application;

import com.brainserve.appointment.audit.api.ActivityHistory;
import com.brainserve.appointment.audit.api.AuditService;
import com.brainserve.appointment.document.api.TaskEvidenceStore;
import com.brainserve.appointment.iam.api.StaffCommunicationDirectory;
import com.brainserve.appointment.shared.application.BusinessException;
import com.brainserve.appointment.worktask.api.TaskActivityAccess;
import com.brainserve.appointment.worktask.api.TaskActivityController.CommentNotificationRequested;
import com.brainserve.appointment.worktask.domain.TaskPlanningState;
import com.brainserve.appointment.worktask.domain.TaskPlanningState.Evidence;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.MDC;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.*;

@Service
public class TaskActivityService {
    private final TaskActivityAccess access;
    private final ActivityHistory history;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final StaffCommunicationDirectory staff;
    private final AuditService audit;
    private final ApplicationEventPublisher events;
    private final TaskEvidenceStore documents;
    public TaskActivityService(TaskActivityAccess access,ActivityHistory history,JdbcTemplate jdbc,ObjectMapper mapper,
            StaffCommunicationDirectory staff,AuditService audit,ApplicationEventPublisher events,TaskEvidenceStore documents) {
        this.access=access;this.history=history;this.jdbc=jdbc;this.mapper=mapper;this.staff=staff;this.audit=audit;this.events=events;this.documents=documents;
    }
    @Transactional(readOnly=true)
    public ActivityHistory.Page timeline(UUID actor,UUID task,int page,int size) {
        var scope=access.require(actor,task);var result=history.task(task,page,size);access.revalidate(actor,scope);return result;
    }
    @Transactional(readOnly=true)
    public Discussion comments(UUID actor,UUID task,int page,int size) {
        bounds(page,size);var scope=access.require(actor,task);
        var rows=jdbc.query("select * from task_comment where work_task_id=? order by created_at,id limit ? offset ?",(rs,n)->row(rs),task,size+1,(long)page*size);
        var participants=participants(actor,scope);var evidence=evidence(task);
        access.revalidate(actor,scope);
        return new Discussion(rows.subList(0,Math.min(size,rows.size())).stream().map(r->view(actor,r)).toList(),page,size,rows.size()>size,
                !"ROLE_CEO".equals(scope.access().authority().role()),participants,evidence);
    }
    @Transactional
    public Comment create(UUID actor,UUID task,Create request) {
        var scope=lock(actor,task);writable(scope);if(request==null)invalid("Comment contents are required");validate(request.body(),request.mentionUserIds(),request.evidenceIds());
        if(request.clientRequestId()==null)invalid("A client request ID is required");
        String text=request.body().trim();List<UUID> mentionIds=normalized(request.mentionUserIds()),evidenceIds=normalized(request.evidenceIds());
        String hash=hash(json(List.of(text,mentionIds,evidenceIds)));
        var retained=jdbc.query("select * from task_comment where work_task_id=? and author_user_id=? and client_request_id=?",(rs,n)->row(rs),task,actor,request.clientRequestId());
        if(!retained.isEmpty()) {
            var existing=retained.getFirst();if(!hash.equals(existing.requestHash()))conflict("This request ID was already used for different comment contents");
            access.revalidate(actor,scope);return view(actor,existing);
        }
        List<Participant> mentions=mentions(actor,scope,mentionIds);List<Evidence> attachments=attachments(task,evidenceIds);
        var author=staff.requireActive(actor);UUID id=UUID.randomUUID();Instant at=Instant.now();
        jdbc.update("""
            insert into task_comment(id,work_task_id,author_user_id,author_name,author_role,body,mentions,attachments,client_request_id,request_hash,created_at)
            values(?,?,?,?,?,?,cast(? as jsonb),cast(? as jsonb),?,?,?)
            """,id,task,actor,author.fullName(),scope.access().authority().role(),text,json(mentions),json(attachments),request.clientRequestId(),hash,Timestamp.from(at));
        Row row=new Row(id,task,actor,author.fullName(),scope.access().authority().role(),text,mentions,attachments,hash,0,at,null,null);
        revision(row,actor,author.fullName(),scope.access().authority().role(),"CREATED",at);
        Set<UUID> targets=new LinkedHashSet<>(mentionIds);
        targets.add(scope.teamLeadUserId());targets.add(scope.assignedByUserId());
        staff.activeByEmployeeId(scope.employeeId()).map(StaffCommunicationDirectory.StaffMember::userId).ifPresent(targets::add);
        Set<UUID> eligible=participants(actor,scope).stream().map(Participant::id).collect(java.util.stream.Collectors.toSet());
        targets.remove(actor);targets.retainAll(eligible);
        record(task,id,"CREATED",targets.size(),0,scope);
        access.revalidate(actor,scope);
        for(UUID target:targets)events.publishEvent(new CommentNotificationRequested(task,id,actor,target));
        return view(actor,row);
    }
    @Transactional
    public Comment edit(UUID actor,UUID task,UUID comment,Edit request) {
        var scope=lock(actor,task);writable(scope);if(request==null)invalid("Comment contents are required");Row old=owned(actor,task,comment,request.expectedVersion());
        validate(request.body(),request.mentionUserIds(),request.evidenceIds());
        List<Participant> mentions=mentions(actor,scope,normalized(request.mentionUserIds()));
        // Existing comment links remain valid after they are removed from a draft.
        Map<UUID,Evidence> allowed=new LinkedHashMap<>();evidence(task).forEach(e->allowed.put(e.id(),e));old.attachments().forEach(e->allowed.putIfAbsent(e.id(),e));
        List<Evidence> attachments=normalized(request.evidenceIds()).stream().map(id->{Evidence e=allowed.get(id);if(e==null)invalid("Choose existing private evidence from this worksheet");return e;}).toList();
        Instant at=Instant.now();Row next=new Row(old.id(),task,old.authorId(),old.authorName(),old.authorRole(),request.body().trim(),mentions,attachments,old.requestHash(),old.version()+1,old.createdAt(),at,null);
        jdbc.update("update task_comment set body=?,mentions=cast(? as jsonb),attachments=cast(? as jsonb),version=version+1,edited_at=? where id=?",next.body(),json(mentions),json(attachments),Timestamp.from(at),comment);
        var author=staff.requireActive(actor);revision(next,actor,author.fullName(),scope.access().authority().role(),"EDITED",at);
        record(task,comment,"EDITED",0,next.version(),scope);access.revalidate(actor,scope);return view(actor,next);
    }
    @Transactional
    public Comment remove(UUID actor,UUID task,UUID comment,long expectedVersion) {
        var scope=lock(actor,task);writable(scope);Row old=owned(actor,task,comment,expectedVersion);Instant at=Instant.now();
        Row next=new Row(old.id(),task,old.authorId(),old.authorName(),old.authorRole(),old.body(),old.mentions(),old.attachments(),old.requestHash(),old.version()+1,old.createdAt(),old.editedAt(),at);
        jdbc.update("update task_comment set deleted_at=?,version=version+1 where id=?",Timestamp.from(at),comment);
        var author=staff.requireActive(actor);revision(next,actor,author.fullName(),scope.access().authority().role(),"REMOVED",at);
        record(task,comment,"REMOVED",0,next.version(),scope);access.revalidate(actor,scope);return view(actor,next);
    }
    @Transactional
    public TaskEvidenceStore.Download download(UUID actor,UUID task,UUID comment,UUID evidenceId) {
        var scope=access.require(actor,task);Row row=find(task,comment,false);
        if(row.deletedAt()!=null)missingComment();
        Evidence evidence=row.attachments().stream().filter(e->e.id().equals(evidenceId)).findFirst().orElseThrow(()->new BusinessException("WORK_TASK_EVIDENCE_NOT_FOUND","Evidence was not found",HttpStatus.NOT_FOUND));
        var result=documents.download(task,evidence.documentId());access.revalidate(actor,scope);
        audit.record("WORK_TASK_COMMENT_EVIDENCE_READ","WORK_TASK",task.toString(),json(Map.of("commentId",comment,"evidenceId",evidenceId)));return result;
    }
    private TaskActivityAccess.Snapshot lock(UUID actor,UUID task) {
        var scope=access.require(actor,task);
        jdbc.queryForObject("select id from department_work_task where id=? for update",UUID.class,task);
        access.revalidate(actor,scope);return scope;
    }
    private static void writable(TaskActivityAccess.Snapshot scope) {if("ROLE_CEO".equals(scope.access().authority().role()))TaskActivityAccess.denied();}
    private Row owned(UUID actor,UUID task,UUID comment,Long version) {
        Row r=find(task,comment,true);
        if(!actor.equals(r.authorId()))TaskActivityAccess.denied();
        if(r.deletedAt()!=null)conflict("This comment has been removed");
        if(version==null||version<0||version!=r.version())conflict("This comment changed. Reload before editing or removing it");return r;
    }
    private Row find(UUID task,UUID comment,boolean lock) {
        var rows=jdbc.query("select * from task_comment where id=? and work_task_id=?"+(lock?" for update":""),(rs,n)->row(rs),comment,task);
        if(rows.size()!=1)missingComment();return rows.getFirst();
    }
    private List<Participant> participants(UUID actor,TaskActivityAccess.Snapshot scope) {
        List<Participant> values=new ArrayList<>();
        for(var member:staff.activeWithAnyRoleInDepartment(Set.of("ROLE_EMPLOYEE","ROLE_TEAM_LEAD","ROLE_HR_ADMIN","ROLE_MANAGER"),scope.departmentId(),200)) {
            if(!member.userId().equals(scope.teamLeadUserId())&&!Objects.equals(member.employeeId(),scope.employeeId())&&!member.roles().contains("ROLE_HR_ADMIN")&&!member.roles().contains("ROLE_MANAGER"))continue;
            try {access.require(member.userId(),scope.taskId());values.add(new Participant(member.userId(),member.fullName()));}
            catch(BusinessException denied) { /* Removed, unrelated and foreign users are absent from mention options. */ }
        }
        return List.copyOf(values);
    }
    private List<Participant> mentions(UUID actor,TaskActivityAccess.Snapshot scope,List<UUID> ids) {
        var options=participants(actor,scope);List<Participant> result=new ArrayList<>();
        for(UUID id:ids) {if(actor.equals(id))invalid("You cannot mention yourself");result.add(options.stream().filter(p->p.id().equals(id)).findFirst().orElseThrow(()->new BusinessException("WORK_TASK_COMMENT_INVALID","Mentions must be current worksheet participants",HttpStatus.UNPROCESSABLE_ENTITY)));}
        return List.copyOf(result);
    }
    private List<Evidence> evidence(UUID task) {
        String value=jdbc.queryForObject("select planning_state::text from department_work_task where id=?",String.class,task);
        TaskPlanningState state=read(value,new TypeReference<TaskPlanningState>(){});
        var values=new LinkedHashMap<UUID,Evidence>();state.evidence.forEach(e->values.put(e.id(),e));state.submissions.subList(Math.max(0,state.submissions.size()-50),state.submissions.size()).forEach(s->s.evidence().forEach(e->values.putIfAbsent(e.id(),e)));
        return List.copyOf(values.values());
    }
    private List<Evidence> attachments(UUID task,List<UUID> ids) {
        List<Evidence> values=evidence(task);return ids.stream().map(id->values.stream().filter(e->e.id().equals(id)).findFirst().orElseThrow(()->new BusinessException("WORK_TASK_COMMENT_INVALID","Choose existing private evidence from this worksheet",HttpStatus.UNPROCESSABLE_ENTITY))).toList();
    }
    private Comment view(UUID actor,Row r) {
        boolean removed=r.deletedAt()!=null;
        return new Comment(r.id(),new ActivityHistory.Actor(r.authorId().toString(),r.authorName(),r.authorRole(),true),removed?null:r.body(),removed?List.of():r.mentions(),removed?List.of():r.attachments(),r.version(),r.createdAt(),r.editedAt(),r.deletedAt(),!removed&&actor.equals(r.authorId()));
    }
    private void revision(Row row,UUID actor,String name,String role,String operation,Instant at) {
        jdbc.update("""
            insert into task_comment_revision(comment_id,version,operation,body,mentions,attachments,actor_user_id,actor_name,actor_role,occurred_at,correlation_id)
            values(?,?,?,?,cast(? as jsonb),cast(? as jsonb),?,?,?,?,?)
            """,row.id(),row.version(),operation,row.body(),json(row.mentions()),json(row.attachments()),actor,name,role,Timestamp.from(at),MDC.get("correlationId"));
    }
    private void record(UUID task,UUID comment,String operation,int notified,long version,TaskActivityAccess.Snapshot scope) {
        audit.record("WORK_TASK_COMMENT_"+operation,"WORK_TASK",task.toString(),json(Map.of("commentId",comment,"commentVersion",version,"notificationRequested",notified>0,"_activity",Map.of("departmentId",scope.departmentId()))));
    }
    private Row row(ResultSet rs)throws SQLException {
        return new Row(rs.getObject("id",UUID.class),rs.getObject("work_task_id",UUID.class),rs.getObject("author_user_id",UUID.class),rs.getString("author_name"),rs.getString("author_role"),rs.getString("body"),read(rs.getString("mentions"),new TypeReference<List<Participant>>(){}),read(rs.getString("attachments"),new TypeReference<List<Evidence>>(){}),rs.getString("request_hash"),rs.getLong("version"),rs.getTimestamp("created_at").toInstant(),instant(rs,"edited_at"),instant(rs,"deleted_at"));
    }
    private static Instant instant(ResultSet rs,String name)throws SQLException {Timestamp t=rs.getTimestamp(name);return t==null?null:t.toInstant();}
    private <T>T read(String value,TypeReference<T> type) {try{return mapper.readValue(value,type);}catch(Exception e){throw new IllegalStateException("Stored activity data is invalid",e);}}
    private String json(Object value){try{return mapper.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException("Activity could not be serialized",e);}}
    private static String hash(String value) {try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    private static List<UUID> normalized(List<UUID> ids) {return ids.stream().sorted().toList();}
    private static void validate(String body,List<UUID> mentions,List<UUID> evidence) {
        if(body==null||body.isBlank()||body.trim().length()>4000||body.chars().anyMatch(c->c==0||c<32&&c!='\n'&&c!='\r'&&c!='\t'))invalid("A plain-text comment of 1–4000 characters is required");
        if(mentions==null||evidence==null||mentions.size()>20||evidence.size()>5||mentions.stream().anyMatch(Objects::isNull)||evidence.stream().anyMatch(Objects::isNull)||new HashSet<>(mentions).size()!=mentions.size()||new HashSet<>(evidence).size()!=evidence.size())invalid("Choose at most 20 distinct participants and five existing evidence files");
    }
    private static void bounds(int page,int size) {if(page<0||page>100000||size<1||size>100)invalid("Comment page size must be between 1 and 100");}
    private static void invalid(String detail){throw new BusinessException("WORK_TASK_COMMENT_INVALID",detail,HttpStatus.UNPROCESSABLE_ENTITY);}
    private static void conflict(String detail){throw new BusinessException("WORK_TASK_COMMENT_CONFLICT",detail,HttpStatus.CONFLICT);}
    private static void missingComment(){throw new BusinessException("WORK_TASK_COMMENT_NOT_FOUND","Comment was not found",HttpStatus.NOT_FOUND);}
    private record Row(UUID id,UUID taskId,UUID authorId,String authorName,String authorRole,String body,List<Participant> mentions,List<Evidence> attachments,String requestHash,long version,Instant createdAt,Instant editedAt,Instant deletedAt) {}
    public record Participant(UUID id,String name) {}
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
    public record Comment(UUID id,ActivityHistory.Actor author,String body,List<Participant> mentions,List<Evidence> attachments,long version,Instant createdAt,Instant editedAt,Instant deletedAt,boolean canEdit) {}
    public record Discussion(List<Comment> comments,int page,int size,boolean hasMore,boolean canComment,List<Participant> participants,List<Evidence> evidence) {}
    public record Create(UUID clientRequestId,String body,List<UUID> mentionUserIds,List<UUID> evidenceIds) {}
    public record Edit(Long expectedVersion,String body,List<UUID> mentionUserIds,List<UUID> evidenceIds) {}
}
