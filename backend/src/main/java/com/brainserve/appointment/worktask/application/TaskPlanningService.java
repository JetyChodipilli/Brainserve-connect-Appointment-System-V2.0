package com.brainserve.appointment.worktask.application;

import com.brainserve.appointment.audit.api.AuditService;
import com.brainserve.appointment.document.api.TaskEvidenceStore;
import com.brainserve.appointment.iam.api.CurrentAccountAuthority;
import com.brainserve.appointment.iam.api.StaffCommunicationDirectory;
import com.brainserve.appointment.shared.application.BusinessException;
import com.brainserve.appointment.worktask.domain.DepartmentWorkTask;
import com.brainserve.appointment.worktask.domain.TaskPlanningState;
import com.brainserve.appointment.worktask.domain.TaskPlanningState.*;
import com.brainserve.appointment.worktask.infrastructure.DepartmentWorkTaskRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

@Service
public class TaskPlanningService {
    private final DepartmentWorkTaskRepository tasks;
    private final CurrentAccountAuthority authority;
    private final EntityManager em;
    private final AuditService audit;
    private final StaffCommunicationDirectory staff;
    private final TaskEvidenceStore documents;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper;
    private final org.springframework.context.ApplicationEventPublisher events;
    public TaskPlanningService(DepartmentWorkTaskRepository tasks, CurrentAccountAuthority authority, EntityManager em,
            AuditService audit, StaffCommunicationDirectory staff, TaskEvidenceStore documents, org.springframework.jdbc.core.JdbcTemplate jdbc, com.fasterxml.jackson.databind.ObjectMapper mapper, org.springframework.context.ApplicationEventPublisher events) {
        this.tasks=tasks; this.authority=authority; this.em=em; this.audit=audit; this.staff=staff; this.documents=documents; this.jdbc=jdbc; this.mapper=mapper; this.events=events;
    }
    @Transactional(readOnly=true)
    public Planning get(UUID actor, UUID id) {
        preauthorize(actor); DepartmentWorkTask task=require(id); var before=access(actor,task); Planning result=view(actor,task,before);
        if (!before.equals(access(actor,task))) deny(); unchangedTask(task); return result;
    }
    @Transactional
    public Planning update(UUID actor, UUID id, Update request) {
        DepartmentWorkTask task=lock(actor,id,request.expectedVersion()); Access a=access(actor,task); if(!a.manage()) deny();
        reason(request.reason());
        if (request.priority()==null || !Set.of("LOW","NORMAL","HIGH","URGENT").contains(request.priority()) || request.dueDate()==null
                || (request.estimateMinutes()!=null && (request.estimateMinutes()<1||request.estimateMinutes()>525600))) invalid("Invalid planning values");
        if(request.checklist()==null||request.checklist().size()>50) invalid("Checklist must contain at most 50 items");
        Set<UUID> ids=new HashSet<>(); List<ChecklistItem> next=new ArrayList<>();
        for(Definition d:request.checklist()) {
            if(d==null||d.id()==null||!ids.add(d.id())||d.title()==null||d.title().isBlank()||d.title().trim().length()>300) invalid("Each checklist item needs a unique ID and a title of at most 300 characters");
            ChecklistItem old=task.getPlanning().checklist.stream().filter(i->i.id().equals(d.id())).findFirst().orElse(null);
            boolean completed=old!=null&&old.title().equals(d.title().trim())&&old.required()==d.required()&&old.completed();
            next.add(new ChecklistItem(d.id(),d.title().trim(),next.size(),d.required(),completed));
        }
        task.getPlanning().checklist=next;
        LocalDate previous=task.getDueDate(); String previousPriority=task.getPriority();
        task.updatePlanning(request.priority(),request.estimateMinutes(),request.evidenceRequired(),request.dueDate());
        try { audit.record("WORK_TASK_PLANNING_UPDATED","WORK_TASK",id.toString(),mapper.writeValueAsString(Map.of("previousDueDate",previous.toString(),"dueDate",request.dueDate().toString(),"previousPriority",previousPriority,"priority",request.priority(),"reason",request.reason().trim(),"checklist",next,"evidenceRequired",request.evidenceRequired()))); }
        catch(com.fasterxml.jackson.core.JsonProcessingException ex) { throw new IllegalStateException("Planning audit could not be serialized",ex); }
        if(!previousPriority.equals(request.priority())) notify(task,actor,"Task priority changed to "+request.priority());
        return finish(actor,task,a);
    }
    @Transactional
    public Planning tick(UUID actor,UUID id,Tick request) {
        DepartmentWorkTask task=lock(actor,id,request.expectedVersion()); Access a=access(actor,task); if(!a.progress()) deny();
        if(request.completedIds()==null||request.completedIds().size()>50) invalid("Invalid completed checklist items");
        Set<UUID> ids=new HashSet<>(request.completedIds());
        if(ids.size()!=request.completedIds().size()||!task.getPlanning().checklist.stream().map(ChecklistItem::id).collect(java.util.stream.Collectors.toSet()).containsAll(ids)) invalid("Unknown checklist item");
        task.getPlanning().checklist=task.getPlanning().checklist.stream().map(i->new ChecklistItem(i.id(),i.title(),i.position(),i.required(),ids.contains(i.id()))).toList();
        record(id,"CHECKLIST_UPDATED");return finish(actor,task,a);
    }
    @Transactional
    public Planning raise(UUID actor,UUID id,Raise request) {
        DepartmentWorkTask task=lock(actor,id,request.expectedVersion()); Access a=access(actor,task); if(!a.blocker()) deny();reason(request.reason());
        if(task.isBlocked()) throw new BusinessException("WORK_TASK_ALREADY_BLOCKED","Resolve the current blocker before raising another",HttpStatus.CONFLICT);
        UUID contact=request.contactUserId()==null?task.getTeamLeadUserId():request.contactUserId(); requireContact(task,contact);
        task.getPlanning().blockers.add(new Blocker(UUID.randomUUID(),request.reason().trim(),contact,Instant.now(),null,actor,null,null));
        task.setBlocked(true);notify(task,actor,"Blocker raised for task");recordReason(id,"BLOCKER_RAISED",request.reason());return finish(actor,task,a);
    }
    @Transactional
    public Planning resolve(UUID actor,UUID id,UUID blockerId,Resolve request) {
        DepartmentWorkTask task=lock(actor,id,request.expectedVersion()); Access a=access(actor,task);if(!a.resolveBlocker())deny();reason(request.reason());
        Blocker old=activeBlocker(task,blockerId);replaceBlocker(task,new Blocker(old.id(),old.reason(),old.contactUserId(),old.raisedAt(),Instant.now(),old.raisedBy(),actor,request.reason().trim()));
        task.setBlocked(false);notify(task,actor,"Task blocker resolved");recordReason(id,"BLOCKER_RESOLVED",request.reason());return finish(actor,task,a);
    }
    @Transactional
    public Planning contact(UUID actor,UUID id,UUID blockerId,Contact request) {
        DepartmentWorkTask task=lock(actor,id,request.expectedVersion());Access a=access(actor,task);if(!a.resolveBlocker())deny();reason(request.reason());requireContact(task,request.contactUserId());
        Blocker old=activeBlocker(task,blockerId);replaceBlocker(task,new Blocker(old.id(),old.reason(),request.contactUserId(),old.raisedAt(),null,old.raisedBy(),null,null));
        notify(task,actor,"Task blocker contact updated");recordReason(id,"BLOCKER_CONTACT_UPDATED",request.reason());return finish(actor,task,a);
    }
    @Transactional
    public Planning upload(UUID actor,UUID id,long version,MultipartFile file) {
        DepartmentWorkTask task=lock(actor,id,version);Access a=access(actor,task);if(!a.upload())deny();
        if(task.getPlanning().evidence.size()>=20)invalid("At most 20 draft attachments are allowed");
        var d=documents.store(id,file);
        if(!a.equals(access(actor,task)))deny();
        task.getPlanning().evidence.add(new Evidence(UUID.randomUUID(),d.id(),d.filename(),d.contentType(),d.sizeBytes(),d.sha256(),d.createdAt()));
        record(id,"EVIDENCE_UPLOADED");return finish(actor,task,a);
    }
    @Transactional
    public Planning remove(UUID actor,UUID id,UUID evidenceId,long version) {
        DepartmentWorkTask task=lock(actor,id,version);Access a=access(actor,task);if(!a.upload())deny();
        if(!task.getPlanning().evidence.removeIf(e->e.id().equals(evidenceId)))notFound();
        record(id,"EVIDENCE_UNLINKED");return finish(actor,task,a);
    }
    @Transactional
    public TaskEvidenceStore.Download download(UUID actor,UUID id,UUID evidenceId) {
        preauthorize(actor); DepartmentWorkTask task=require(id);Access a=access(actor,task);
        Evidence evidence=java.util.stream.Stream.concat(task.getPlanning().evidence.stream(),task.getPlanning().submissions.stream().flatMap(s->s.evidence().stream()))
                .filter(e->e.id().equals(evidenceId)).findFirst().orElseThrow(()->new BusinessException("WORK_TASK_EVIDENCE_NOT_FOUND","Evidence was not found",HttpStatus.NOT_FOUND));
        var result=documents.download(id,evidence.documentId());if(!a.equals(access(actor,task)))deny();unchangedTask(task);record(id,"EVIDENCE_READ");return result;
    }
    private DepartmentWorkTask lock(UUID actor,UUID id,Long version) {
        preauthorize(actor); DepartmentWorkTask task=require(id);Access before=access(actor,task);
        em.refresh(task,LockModeType.PESSIMISTIC_WRITE);if(!before.equals(access(actor,task)))deny();
        if(version==null)invalid("The observed task version is required");
        if(version<0||version!=task.getVersion())throw new BusinessException("WORK_TASK_VERSION_CONFLICT","This worksheet changed. Reload it before submitting your update",HttpStatus.CONFLICT);
        return task;
    }
    private Planning finish(UUID actor,DepartmentWorkTask task,Access before) {
        if(!before.equals(access(actor,task)))deny();
        // Flush the JSON/scalar mutation before reading the revision. Combining a forced
        // increment with a dirty entity schedules another increment during transaction commit,
        // making the response stale immediately. A changed planning value uses normal @Version.
        task.getPlanning().touch();
        tasks.flush();return view(actor,task,before);
    }
    private Access access(UUID actor,DepartmentWorkTask task) {
        var current=authority.requireActive(actor);
        if("ROLE_CEO".equals(current.role())) {
            if(!current.permissions().contains("WORK_INSIGHT_CEO_APPROVE"))deny();
            if(!Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from work_task_audit_record where work_task_id=? and audit_status in ('PENDING_CEO_APPROVAL','CEO_APPROVED','CEO_REWORK_REQUESTED'))",Boolean.class,task.getId())))notFoundTask();
            return new Access(current,null,false,false,false,false,false);
        }
        var scope=authority.requireWorkScope(actor);
        if(!scope.authority().equals(current)||!current.permissions().contains("WORK_TASK_READ"))deny();
        if(!scope.departmentId().equals(task.getDepartmentId()))notFoundTask();
        boolean lead="ROLE_TEAM_LEAD".equals(current.role())&&actor.equals(task.getTeamLeadUserId());
        boolean hr="ROLE_HR_ADMIN".equals(current.role());
        boolean employee="ROLE_EMPLOYEE".equals(current.role())&&"EMPLOYEE".equals(task.getAssigneeRole())&&Objects.equals(current.employeeId(),task.getEmployeeId());
        boolean ownLead=lead&&"TEAM_LEAD".equals(task.getAssigneeRole())&&Objects.equals(current.employeeId(),task.getEmployeeId());
        if(!lead&&!hr&&!employee&&!"ROLE_MANAGER".equals(current.role()))notFoundTask();
        boolean finalClosed=Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from work_task_audit_record where work_task_id=? and audit_status='CEO_APPROVED')",Boolean.class,task.getId()));
        boolean editable=Set.of("ASSIGNED","IN_PROGRESS","CHANGES_REQUESTED","COMPLETED").contains(task.getStatus().name())&&!finalClosed;
        boolean manage=!finalClosed&&(lead||hr)&&current.permissions().contains("WORK_TASK_CREATE");
        boolean progress=editable&&(employee||ownLead)&&current.permissions().contains("WORK_TASK_PROGRESS");
        return new Access(current,scope,manage,progress,manage||progress,manage,progress);
    }
    private Planning view(UUID actor,DepartmentWorkTask task,Access a) {
        TaskPlanningState p=task.getPlanning();
        return new Planning(task.getId(),task.getVersion(),task.getPriority(),task.getOriginalDueDate(),task.isOriginalDueDateKnown(),task.getDueDate(),task.getEstimateMinutes(),task.isEvidenceRequired(),
                List.copyOf(p.checklist),tail(p.blockers,100),List.copyOf(p.evidence),tail(p.submissions,50),p.blockers.size()>100,p.submissions.size()>50,
                new Permissions(a.manage(),a.progress(),a.blocker(),a.resolveBlocker(),a.upload()),contacts(task));
    }
    private List<ContactOption> contacts(DepartmentWorkTask task) {
        return staff.activeWithAnyRoleInDepartment(Set.of("ROLE_EMPLOYEE","ROLE_TEAM_LEAD","ROLE_HR_ADMIN","ROLE_MANAGER"),task.getDepartmentId(),200).stream()
                .map(m->new ContactOption(m.userId(),m.fullName())).toList();
    }
    private void requireContact(DepartmentWorkTask task,UUID contact) {if(contact==null||contacts(task).stream().noneMatch(c->c.id().equals(contact)))invalid("Choose an active department participant");}
    private static <T> List<T> tail(List<T> values,int limit){return List.copyOf(values.subList(Math.max(0,values.size()-limit),values.size()));}
    private Blocker activeBlocker(DepartmentWorkTask task,UUID id){return task.getPlanning().blockers.stream().filter(b->b.id().equals(id)&&b.resolvedAt()==null).findFirst().orElseThrow(()->new BusinessException("WORK_TASK_BLOCKER_NOT_FOUND","Active blocker was not found",HttpStatus.NOT_FOUND));}
    private void replaceBlocker(DepartmentWorkTask task,Blocker value){List<Blocker> list=task.getPlanning().blockers;for(int i=0;i<list.size();i++)if(list.get(i).id().equals(value.id()))list.set(i,value);}
    private DepartmentWorkTask require(UUID id){return tasks.findById(id).orElseThrow(()->new BusinessException("WORK_TASK_NOT_FOUND","Work task was not found",HttpStatus.NOT_FOUND));}
    private void preauthorize(UUID actor) {
        var a=authority.requireActive(actor);
        if("ROLE_CEO".equals(a.role())) {if(!a.permissions().contains("WORK_INSIGHT_CEO_APPROVE"))deny();return;}
        if(!Set.of("ROLE_EMPLOYEE","ROLE_TEAM_LEAD","ROLE_HR_ADMIN","ROLE_MANAGER").contains(a.role())||!a.permissions().contains("WORK_TASK_READ"))deny();
    }
    private void unchangedTask(DepartmentWorkTask task) {
        Long version=jdbc.queryForObject("select version from department_work_task where id=?",Long.class,task.getId());
        if(version==null||version!=task.getVersion())throw new BusinessException("WORK_TASK_VERSION_CONFLICT","This worksheet changed. Reload before continuing",HttpStatus.CONFLICT);
    }
    private void notify(DepartmentWorkTask task,UUID actor,String message) {
        Set<UUID> targets=new HashSet<>(); targets.add(task.getTeamLeadUserId());targets.add(task.getAssignedByUserId());
        staff.activeByEmployeeId(task.getEmployeeId()).map(StaffCommunicationDirectory.StaffMember::userId).ifPresent(targets::add);
        task.getPlanning().blockers.stream().filter(b->b.resolvedAt()==null).map(Blocker::contactUserId).forEach(targets::add);
        Set<UUID> active=contacts(task).stream().map(ContactOption::id).collect(java.util.stream.Collectors.toSet());
        for(UUID target:targets)if(!actor.equals(target)&&active.contains(target))events.publishEvent(new com.brainserve.appointment.worktask.api.WorkTaskEvents.DirectNotificationRequested(actor,target,message+": "+task.getTitle()+". Open Workboard for details."));
    }
    private static void notFoundTask(){throw new BusinessException("WORK_TASK_NOT_FOUND","Work task was not found",HttpStatus.NOT_FOUND);}
    private void recordReason(UUID id,String action,String reason) {
        try {audit.record("WORK_TASK_"+action,"WORK_TASK",id.toString(),mapper.writeValueAsString(Map.of("reason",reason.trim())));}
        catch(com.fasterxml.jackson.core.JsonProcessingException ex){throw new IllegalStateException("Planning audit could not be serialized",ex);}
    }
    private void record(UUID id,String action){audit.record("WORK_TASK_"+action,"WORK_TASK",id.toString(),"{}");}
    private static void reason(String r){if(r==null||r.isBlank()||r.trim().length()>1000)invalid("A reason of at most 1000 characters is required");}
    private static void invalid(String detail){throw new BusinessException("WORK_TASK_PLANNING_INVALID",detail,HttpStatus.UNPROCESSABLE_ENTITY);}
    private static void deny(){throw new BusinessException("WORK_TASK_PERMISSION_DENIED","Your current permissions do not allow access to this task",HttpStatus.FORBIDDEN);}
    private static void notFound(){throw new BusinessException("WORK_TASK_EVIDENCE_NOT_FOUND","Draft evidence was not found",HttpStatus.NOT_FOUND);}
    private record Access(CurrentAccountAuthority.Authority authority,CurrentAccountAuthority.WorkScope scope,boolean manage,boolean progress,boolean blocker,boolean resolveBlocker,boolean upload){}
    public record Definition(UUID id,String title,boolean required){}
    public record Update(@jakarta.validation.constraints.NotNull @jakarta.validation.constraints.PositiveOrZero Long expectedVersion,String priority,Integer estimateMinutes,boolean evidenceRequired,LocalDate dueDate,String reason,List<Definition> checklist){}
    public record Tick(@jakarta.validation.constraints.NotNull @jakarta.validation.constraints.PositiveOrZero Long expectedVersion,List<UUID> completedIds){}
    public record Raise(@jakarta.validation.constraints.NotNull @jakarta.validation.constraints.PositiveOrZero Long expectedVersion,String reason,UUID contactUserId){}
    public record Resolve(@jakarta.validation.constraints.NotNull @jakarta.validation.constraints.PositiveOrZero Long expectedVersion,String reason){}
    public record Contact(@jakarta.validation.constraints.NotNull @jakarta.validation.constraints.PositiveOrZero Long expectedVersion,UUID contactUserId,String reason){}
    public record Permissions(boolean manage,boolean progress,boolean blocker,boolean resolveBlocker,boolean upload){}
    public record ContactOption(UUID id,String name){}
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
    public record Planning(UUID taskId,long taskVersion,String priority,LocalDate originalDueDate,boolean originalDueDateKnown,LocalDate dueDate,Integer estimateMinutes,boolean evidenceRequired,List<ChecklistItem> checklist,List<Blocker> blockers,List<Evidence> evidence,List<Submission> submissions,boolean blockersTruncated,boolean submissionsTruncated,Permissions permissions,List<ContactOption> contactOptions){}
}
