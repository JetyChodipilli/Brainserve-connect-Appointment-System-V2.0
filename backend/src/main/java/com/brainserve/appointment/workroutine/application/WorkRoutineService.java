package com.brainserve.appointment.workroutine.application;

import com.brainserve.appointment.audit.api.AuditService;
import com.brainserve.appointment.iam.api.CurrentAccountAuthority;
import com.brainserve.appointment.shared.application.BusinessException;
import com.brainserve.appointment.worktask.api.ScheduledWorkMaterializer;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;

/** Durable routine definitions, captured occurrence receipts and bounded catch-up processing. */
@Service
public class WorkRoutineService {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(WorkRoutineService.class);
    private final JdbcTemplate jdbc;
    private final CurrentAccountAuthority authority;
    private final ScheduledWorkMaterializer work;
    private final ObjectMapper mapper;
    private final TransactionTemplate transactions;
    private final TransactionTemplate policyTransactions;
    private final AuditService audit;
    private final ZoneId officeZone;

    public WorkRoutineService(JdbcTemplate jdbc, CurrentAccountAuthority authority, ScheduledWorkMaterializer work,
            ObjectMapper mapper, PlatformTransactionManager transactionManager, AuditService audit,
            @Value("${brainserve.appointment.office-zone:Asia/Kolkata}") String officeZone) {
        this.jdbc=jdbc; this.authority=authority; this.work=work; this.mapper=mapper; this.audit=audit;
        this.officeZone=ZoneId.of(officeZone);
        transactions=new TransactionTemplate(transactionManager);
        transactions.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        policyTransactions=new TransactionTemplate(transactionManager);
        policyTransactions.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        policyTransactions.setReadOnly(true);
    }

    @Transactional(readOnly=true)
    public Context context(UUID actor) {
        var scope=scope(actor); var workspace=work.routineWorkspace(actor);
        Context result=new Context(workspace.departmentId(),workspace.departmentName(),officeZone.getId(),today(),workspace.eligibleAssignees());
        unchanged(actor,scope); return result;
    }

    @Transactional(readOnly=true)
    public Page<Template> templates(UUID actor,int page,int size) {
        bounds(page,size); var scope=scope(actor);
        List<Template> items=jdbc.query("select * from work_routine_template where department_id=? order by updated_at desc,id limit ? offset ?",
                this::template,scope.departmentId(),size,(long)page*size);
        long total=count("select count(*) from work_routine_template where department_id=?",scope.departmentId());
        unchanged(actor,scope); return page(items,total,page,size);
    }

    @Transactional(readOnly=true)
    public Template template(UUID actor,UUID id) {
        var before=scope(actor); Template result=requireTemplate(id,before.departmentId(),false);
        unchanged(actor,before); return result;
    }

    @Transactional
    public Template createTemplate(UUID actor,TemplateWrite request) {
        var before=scope(actor); if(request==null||request.requestId()==null) invalid("A request ID is required");
        TemplateWrite normalized=normalize(request); rule(before,normalized.assigneeRule());
        advisory("template:"+actor+":"+request.requestId());
        lockPolicy(actor,before.departmentId(),null); unchanged(actor,before);
        List<Template> existing=jdbc.query("select * from work_routine_template where owner_user_id=? and request_id=?",this::template,actor,request.requestId());
        if(!existing.isEmpty()) {
            Template result=existing.getFirst(); if(!before.departmentId().equals(result.departmentId())) notFound();
            identical("select request_json::text from work_routine_template where id=?",result.id(),normalized);
            return result;
        }
        UUID id=UUID.randomUUID(); Instant now=Instant.now();
        jdbc.update("""
                insert into work_routine_template(id,department_id,owner_user_id,request_id,request_json,title,instructions,checklist_json,assignee_rule,due_offset_days,created_at,updated_at)
                values (?,?,?,?,?::jsonb,?,?,?::jsonb,?,?,?,?)
                """,id,before.departmentId(),actor,request.requestId(),json(normalized),normalized.title(),normalized.instructions(),json(normalized.checklist()),normalized.assigneeRule(),normalized.dueOffsetDays(),ts(now),ts(now));
        Template result=requireTemplate(id,before.departmentId(),false); retain(result);
        unchanged(actor,before); record(actor,"TEMPLATE_CREATED",id,Map.of("version",result.version())); return result;
    }

    @Transactional
    public Template updateTemplate(UUID actor,UUID id,TemplateUpdate request) {
        var before=scope(actor); requireTemplate(id,before.departmentId(),false);
        lockPolicy(actor,before.departmentId(),null); unchanged(actor,before);
        Template old=requireTemplate(id,before.departmentId(),true);
        if(request==null) invalid("Template values are required"); version(request.expectedVersion(),old.version());
        TemplateWrite normalized=normalize(new TemplateWrite(UUID.randomUUID(),request.title(),request.instructions(),request.checklist(),request.assigneeRule(),request.dueOffsetDays()));
        rule(before,normalized.assigneeRule());
        jdbc.update("update work_routine_template set version=version+1,title=?,instructions=?,checklist_json=?::jsonb,assignee_rule=?,due_offset_days=?,updated_at=? where id=?",
                normalized.title(),normalized.instructions(),json(normalized.checklist()),normalized.assigneeRule(),normalized.dueOffsetDays(),ts(Instant.now()),id);
        Template result=requireTemplate(id,before.departmentId(),false); retain(result);
        unchanged(actor,before); record(actor,"TEMPLATE_UPDATED",id,Map.of("version",result.version())); return result;
    }

    @Transactional(readOnly=true)
    public Page<Schedule> schedules(UUID actor,int page,int size) {
        bounds(page,size); var before=scope(actor);
        List<StoredSchedule> rows=jdbc.query("select * from work_routine_schedule where department_id=? order by updated_at desc,id limit ? offset ?",this::storedSchedule,before.departmentId(),size,(long)page*size);
        List<Schedule> result=rows.stream().map(this::schedule).toList();
        long total=count("select count(*) from work_routine_schedule where department_id=?",before.departmentId());
        unchanged(actor,before); return page(result,total,page,size);
    }

    @Transactional
    public Schedule createSchedule(UUID actor,ScheduleWrite request) {
        var before=scope(actor); if(request==null||request.requestId()==null) invalid("A request ID is required");
        ScheduleDefinition definition=definition(request); calendar(definition,officeZone);
        advisory("schedule:"+actor+":"+request.requestId());
        lockPolicy(actor,before.departmentId(),definition.employeeId()); unchanged(actor,before);
        List<StoredSchedule> existing=jdbc.query("select * from work_routine_schedule where creator_user_id=? and request_id=?",this::storedSchedule,actor,request.requestId());
        if(!existing.isEmpty()) {
            StoredSchedule row=existing.getFirst(); if(!row.departmentId().equals(before.departmentId())) notFound();
            identical("select request_json::text from work_routine_schedule where id=?",row.id(),request); return schedule(row);
        }
        if(definition.startDate().isBefore(today())) invalid("The start date cannot be before the office date");
        Template template=requireTemplate(definition.templateId(),before.departmentId(),true);
        rule(before,template.assigneeRule()); validateDraft(actor,before,definition,template);
        RecurrenceCalendar.Definition calendar=calendar(definition,officeZone);
        LocalDate next=RecurrenceCalendar.nextDate(calendar,definition.startDate()).orElse(null);
        UUID id=UUID.randomUUID(); Instant now=Instant.now();
        jdbc.update("""
                insert into work_routine_schedule(id,department_id,creator_user_id,creator_role,request_id,request_json,template_id,employee_id,definition_json,office_zone,next_occurrence_date,next_occurrence_at,created_at,updated_at)
                values (?,?,?,?,?,?::jsonb,?,?,?::jsonb,?,?,?,?,?)
                """,id,before.departmentId(),actor,before.authority().role(),request.requestId(),json(request),definition.templateId(),definition.employeeId(),json(definition),officeZone.getId(),next,
                next==null?null:ts(RecurrenceCalendar.scheduledAt(calendar,next)),ts(now),ts(now));
        unchanged(actor,before); record(actor,"SCHEDULE_CREATED",id,Map.of("templateId",template.id()));
        return schedule(requireSchedule(id,before.departmentId(),false));
    }

    @Transactional(readOnly=true)
    public Preview preview(UUID actor,ScheduleDefinition definition) {
        var before=scope(actor); RecurrenceCalendar.Definition calendar=calendar(definition,officeZone);
        if(definition.startDate().isBefore(today())) invalid("The start date cannot be before the office date");
        Template template=requireTemplate(definition.templateId(),before.departmentId(),false);
        rule(before,template.assigneeRule()); validateDraft(actor,before,definition,template);
        var occurrences=RecurrenceCalendar.preview(calendar,today(),template.dueOffsetDays(),10);
        unchanged(actor,before);
        return new Preview(officeZone.getId(),occurrences,"Office wall-clock dates; inclusive end date. Weekends: "+definition.weekendPolicy()+
                "; supplied holidays: "+definition.holidayPolicy()+". Monthly days clamp to month end. DST gaps advance and overlaps use the earlier offset. Due dates add calendar days.");
    }

    @Transactional
    public Schedule setState(UUID actor,UUID id,State request) {
        var before=scope(actor); StoredSchedule row=requireSchedule(id,before.departmentId(),true);
        lockPolicy(actor,before.departmentId(),row.employeeId()); unchanged(actor,before);
        if(request==null||request.paused()==null) invalid("Schedule state is required"); version(request.expectedVersion(),row.version());
        if(row.paused()==request.paused()) return schedule(row);
        LocalDate next=row.nextDate(); Instant now=Instant.now();
        if(!request.paused() && next!=null) {
            RecurrenceCalendar.Definition calendar=calendar(row.definition(),ZoneId.of(row.officeZone()));
            LocalDate localToday=now.atZone(calendar.officeZone()).toLocalDate();
            LocalDate from=next.isAfter(localToday)?next:localToday;
            next=RecurrenceCalendar.nextDate(calendar,from).orElse(null);
            if(next!=null && !RecurrenceCalendar.scheduledAt(calendar,next).isAfter(now))
                next=RecurrenceCalendar.nextDate(calendar,next.plusDays(1)).orElse(null);
        }
        RecurrenceCalendar.Definition calendar=calendar(row.definition(),ZoneId.of(row.officeZone()));
        jdbc.update("update work_routine_schedule set paused=?,version=version+1,next_occurrence_date=?,next_occurrence_at=?,updated_at=? where id=?",
                request.paused(),next,next==null?null:ts(RecurrenceCalendar.scheduledAt(calendar,next)),ts(now),id);
        unchanged(actor,before); record(actor,request.paused()?"SCHEDULE_PAUSED":"SCHEDULE_RESUMED",id,
                Map.of("elapsedDatesSkipped",!request.paused(),"resumedAt",now.toString()));
        return schedule(requireSchedule(id,before.departmentId(),false));
    }

    @Transactional(readOnly=true)
    public Page<Occurrence> occurrences(UUID actor,UUID id,int page,int size) {
        bounds(page,size); var before=scope(actor); requireSchedule(id,before.departmentId(),false);
        List<Occurrence> items=jdbc.query("select * from work_routine_occurrence where schedule_id=? order by occurrence_date desc limit ? offset ?",this::occurrence,id,size,(long)page*size);
        long total=count("select count(*) from work_routine_occurrence where schedule_id=?",id);
        unchanged(actor,before); return page(items,total,page,size);
    }

    @Transactional
    public Occurrence retry(UUID actor,UUID id,LocalDate date,Retry request) {
        var before=scope(actor); StoredSchedule row=requireSchedule(id,before.departmentId(),true);
        lockPolicy(actor,before.departmentId(),row.employeeId()); unchanged(actor,before);
        if(row.paused()) throw new BusinessException("ROUTINE_PAUSED","Resume this schedule before retrying an occurrence",HttpStatus.CONFLICT);
        List<Occurrence> rows=jdbc.query("select * from work_routine_occurrence where schedule_id=? and occurrence_date=? for update",this::occurrence,id,date);
        if(rows.isEmpty()) notFound(); Occurrence existing=rows.getFirst();
        if(request==null||request.expectedVersion()==null) invalid("The observed occurrence version is required");
        if("CREATED".equals(existing.status())) return existing;
        version(request.expectedVersion(),existing.version());
        Captured snapshot=read(jdbc.queryForObject("select snapshot_json::text from work_routine_occurrence where schedule_id=? and occurrence_date=?",String.class,id,date),Captured.class);
        attempt(row,date,existing.scheduledAt(),snapshot,existing);
        unchanged(actor,before); record(actor,"OCCURRENCE_RETRIED",id,Map.of("occurrenceDate",date.toString()));
        return jdbc.queryForObject("select * from work_routine_occurrence where schedule_id=? and occurrence_date=?",this::occurrence,id,date);
    }

    /** Each claim is its own transaction; SKIP LOCKED permits independent JVM workers. */
    public int runDue(Instant now,int batch) {
        if(now==null||batch<1||batch>500) throw new IllegalArgumentException("Worker instant and batch 1..500 required");
        int completed=0; Set<UUID> failed=new HashSet<>();
        for(int n=0;n<batch;n++) {
            UUID[] claim={null};
            try {
                Boolean processed=transactions.execute(status -> {
                    String exclusions=failed.isEmpty()?"":" and id not in ("+String.join(",",Collections.nCopies(failed.size(),"?"))+")";
                    List<Object> args=new ArrayList<>(); args.add(ts(now)); args.addAll(failed);
                    List<StoredSchedule> due=jdbc.query("select * from work_routine_schedule where not paused and next_occurrence_at<=?"+exclusions+
                            " order by next_occurrence_at,id limit 1 for update skip locked",this::storedSchedule,args.toArray());
                    if(due.isEmpty()) return false;
                    StoredSchedule row=due.getFirst(); claim[0]=row.id();
                    withActor(row.creator(),() -> materialize(row)); return true;
                });
                if(!Boolean.TRUE.equals(processed)) break; completed++;
            } catch(RuntimeException failure) {
                if(claim[0]==null) { LOG.warn("Routine worker claim failed; next poll will retry",failure); break; }
                failed.add(claim[0]); LOG.warn("Routine {} rolled back; other schedules continue and next poll retries",claim[0],failure);
            }
        }
        return completed;
    }

    private void materialize(StoredSchedule row) {
        lockPolicy(row.creator(),row.departmentId(),row.employeeId());
        LocalDate date=row.nextDate();
        Template template=requireTemplate(row.templateId(),row.departmentId(),true);
        Captured snapshot=new Captured(template.id(),template.version(),template.title(),template.instructions(),template.checklist(),template.assigneeRule(),template.dueOffsetDays());
        // A prior committed receipt can only be revisited after an interrupted cursor migration.
        List<Occurrence> existing=jdbc.query("select * from work_routine_occurrence where schedule_id=? and occurrence_date=?",this::occurrence,row.id(),date);
        if(existing.isEmpty()) attempt(row,date,row.nextAt(),snapshot,null);
        RecurrenceCalendar.Definition calendar=calendar(row.definition(),ZoneId.of(row.officeZone()));
        LocalDate next=RecurrenceCalendar.nextDate(calendar,date.plusDays(1)).orElse(null);
        jdbc.update("update work_routine_schedule set next_occurrence_date=?,next_occurrence_at=? where id=?",
                next,next==null?null:ts(RecurrenceCalendar.scheduledAt(calendar,next)),row.id());
    }

    private void attempt(StoredSchedule row,LocalDate date,Instant scheduledAt,Captured snapshot,Occurrence previous) {
        String eventKey="routine:"+row.id()+":"+date;
        var command=new ScheduledWorkMaterializer.Command(row.departmentId(),row.employeeId(),snapshot.assigneeRule(),snapshot.title(),snapshot.instructions(),date.plusDays(snapshot.dueOffsetDays()),
                snapshot.checklist().stream().map(c -> new ScheduledWorkMaterializer.Checklist(c.title(),c.required())).toList(),eventKey);
        BusinessException blocked=null;
        // Only business policy validation is converted to an exception receipt, before any write.
        // SQL, network, serialization and unexpected creation failures roll back the entire claim.
        try {
            // Legacy directory preflight methods have participating read transactions. Isolate
            // their business rollback markers from the outer transaction that retains BLOCKED.
            // Outer policy row locks remain held while this fresh, read-only transaction runs.
            policyTransactions.executeWithoutResult(status -> {
                var current=scope(row.creator()); if(!current.departmentId().equals(row.departmentId()))
                    throw new BusinessException("WORK_TASK_DEPARTMENT_MISMATCH","The creator moved out of this department",HttpStatus.FORBIDDEN);
                if(!current.authority().role().equals(row.creatorRole())) throw new BusinessException("ROUTINE_CREATOR_ROLE_CHANGED","The routine creator's original role changed",HttpStatus.FORBIDDEN);
                rule(current,snapshot.assigneeRule()); work.validateScheduled(row.creator(),command);
            });
        } catch(BusinessException policy) { blocked=policy; }
        UUID taskId=blocked==null?work.createScheduled(row.creator(),command):null;
        String state=blocked==null?"CREATED":"BLOCKED";
        String code=blocked==null?null:blocked.getErrorCode();
        String message=blocked==null?null:blocked.getMessage(); Instant now=Instant.now();
        if(previous==null) {
            jdbc.update("""
                    insert into work_routine_occurrence(schedule_id,occurrence_date,scheduled_at,template_version,snapshot_json,task_id,status,exception_code,message,attempts,created_at,updated_at)
                    values (?,?,?,?,?::jsonb,?,?,?,?,1,?,?)
                    """,row.id(),date,ts(scheduledAt),snapshot.templateVersion(),json(snapshot),taskId,state,code,message,ts(now),ts(now));
        } else {
            jdbc.update("update work_routine_occurrence set task_id=?,status=?,exception_code=?,message=?,attempts=attempts+1,version=version+1,updated_at=? where schedule_id=? and occurrence_date=?",
                    taskId,state,code,message,ts(now),row.id(),date);
        }
        record(row.creator(),"OCCURRENCE_"+state,row.id(),Map.of("occurrenceDate",date.toString(),"templateVersion",snapshot.templateVersion()));
    }

    private CurrentAccountAuthority.WorkScope scope(UUID actor) {
        var current=authority.requireWorkScope(actor);
        if(!Set.of("ROLE_HR_ADMIN","ROLE_TEAM_LEAD").contains(current.authority().role())
                || !current.authority().permissions().contains("WORK_TASK_CREATE")) denied();
        return current;
    }
    private void unchanged(UUID actor,CurrentAccountAuthority.WorkScope before) { if(!before.equals(scope(actor))) denied(); }
    private void rule(CurrentAccountAuthority.WorkScope scope,String rule) {
        if(!Set.of("EMPLOYEE","TEAM_LEAD").contains(rule==null?"":rule)) invalid("Choose an Employee or Team Lead assignee rule");
        if("TEAM_LEAD".equals(rule)&&!"ROLE_HR_ADMIN".equals(scope.authority().role())) invalid("Only HR can assign a routine to the active Team Lead");
    }
    private void validateDraft(UUID actor,CurrentAccountAuthority.WorkScope before,ScheduleDefinition definition,Template template) {
        work.validateScheduled(actor,new ScheduledWorkMaterializer.Command(before.departmentId(),definition.employeeId(),template.assigneeRule(),template.title(),template.instructions(),
                definition.startDate().plusDays(template.dueOffsetDays()),template.checklist().stream().map(c -> new ScheduledWorkMaterializer.Checklist(c.title(),c.required())).toList(),"preview"));
        unchanged(actor,before);
    }

    /** Freeze identities, department and assignment rows while checking current database policy.
     * Account FOR UPDATE also blocks FK-backed role/grant insertions; role/grant/deny rows are
     * locked explicitly so removal cannot race a successful occurrence. */
    private void lockPolicy(UUID actor,UUID department,UUID employee) {
        jdbc.query("select id from org_department where id=? for update",(rs,n)->0,department);
        jdbc.query("select id from department_hr_assignment where department_id=? order by id for update",(rs,n)->0,department);
        jdbc.query("select id from department_team_lead where department_id=? order by id for update",(rs,n)->0,department);
        List<UUID> users=jdbc.query("""
                select id from iam_user_account where id=? or employee_id=? or id in
                (select team_lead_user_id from department_team_lead where department_id=? and active)
                order by id for update
                """,(rs,n)->rs.getObject("id",UUID.class),actor,employee,department);
        for(UUID user:users) {
            jdbc.query("select user_id from iam_user_role where user_id=? for update",(rs,n)->0,user);
            jdbc.query("select user_id from iam_user_permission_grant where user_id=? for update",(rs,n)->0,user);
            jdbc.query("select user_id from iam_user_permission_deny where user_id=? for update",(rs,n)->0,user);
        }
        jdbc.query("""
                select id from employee where id=? or id in(select employee_id from iam_user_account where id=?)
                or id in(select team_lead_employee_id from department_team_lead where department_id=? and active)
                order by id for update
                """,(rs,n)->0,employee,actor,department);
    }

    private Template requireTemplate(UUID id,UUID department,boolean lock) {
        List<Template> values=jdbc.query("select * from work_routine_template where id=? and department_id=?"+(lock?" for update":""),this::template,id,department);
        if(values.isEmpty()) notFound(); return values.getFirst();
    }
    private StoredSchedule requireSchedule(UUID id,UUID department,boolean lock) {
        List<StoredSchedule> values=jdbc.query("select * from work_routine_schedule where id=? and department_id=?"+(lock?" for update":""),this::storedSchedule,id,department);
        if(values.isEmpty()) notFound(); return values.getFirst();
    }
    private void retain(Template t) { jdbc.update("insert into work_routine_template_version(template_id,version,snapshot_json,created_at) values (?,?,?::jsonb,?)",t.id(),t.version(),json(t),ts(Instant.now())); }
    private Template template(ResultSet rs,int n) throws SQLException {
        return new Template(rs.getObject("id",UUID.class),rs.getObject("department_id",UUID.class),rs.getLong("version"),rs.getString("title"),rs.getString("instructions"),
                checklist(rs.getString("checklist_json")),rs.getString("assignee_rule"),rs.getInt("due_offset_days"),instant(rs,"updated_at"));
    }
    private StoredSchedule storedSchedule(ResultSet rs,int n) throws SQLException {
        return new StoredSchedule(rs.getObject("id",UUID.class),rs.getObject("department_id",UUID.class),rs.getObject("creator_user_id",UUID.class),rs.getString("creator_role"),rs.getObject("template_id",UUID.class),
                rs.getObject("employee_id",UUID.class),read(rs.getString("definition_json"),ScheduleDefinition.class),rs.getString("office_zone"),rs.getBoolean("paused"),rs.getLong("version"),
                rs.getObject("next_occurrence_date",LocalDate.class),instant(rs,"next_occurrence_at"),instant(rs,"created_at"));
    }
    private Schedule schedule(StoredSchedule row) {
        Template t=requireTemplate(row.templateId(),row.departmentId(),false); ScheduleDefinition d=row.definition();
        String name=jdbc.queryForObject("select display_name from employee where id=?",String.class,row.employeeId());
        long exceptions=count("select count(*) from work_routine_occurrence where schedule_id=? and status='BLOCKED'",row.id());
        return new Schedule(row.id(),row.departmentId(),t.id(),t.title(),t.version(),row.employeeId(),name,d.frequency(),d.interval(),d.startDate(),d.endDate(),d.localTime(),d.weekdays(),d.monthDay(),
                d.weekendPolicy(),d.holidayPolicy(),d.holidays(),row.officeZone(),row.paused(),row.version(),row.paused()?null:row.nextAt(),exceptions,row.createdAt());
    }
    private Occurrence occurrence(ResultSet rs,int n) throws SQLException {
        return new Occurrence(rs.getObject("occurrence_date",LocalDate.class),instant(rs,"scheduled_at"),rs.getLong("template_version"),rs.getObject("task_id",UUID.class),
                rs.getString("status"),rs.getString("exception_code"),rs.getString("message"),rs.getInt("attempts"),rs.getLong("version"));
    }

    private TemplateWrite normalize(TemplateWrite r) {
        String title=trim(r.title()),instructions=trim(r.instructions());
        if(title.length()<3||title.length()>160||instructions.length()<5||instructions.length()>1000||r.dueOffsetDays()<0||r.dueOffsetDays()>365
                ||r.checklist()==null||r.checklist().size()>50) invalid("Template title, instructions, due offset or checklist is invalid");
        List<Checklist> checklist=new ArrayList<>();
        for(Checklist c:r.checklist()) { if(c==null||c.required()==null||trim(c.title()).isEmpty()||trim(c.title()).length()>300) invalid("Checklist items need a required boolean and a title of 1 to 300 characters"); checklist.add(new Checklist(trim(c.title()),c.required())); }
        return new TemplateWrite(r.requestId(),title,instructions,List.copyOf(checklist),r.assigneeRule(),r.dueOffsetDays());
    }
    private RecurrenceCalendar.Definition calendar(ScheduleDefinition d,ZoneId zone) {
        if(d==null||d.templateId()==null||d.employeeId()==null||d.localTime()==null||!d.localTime().matches("[0-2][0-9]:[0-5][0-9]")) invalid("A template, assignee and HH:mm office time are required");
        try { return new RecurrenceCalendar.Definition(d.frequency(),d.interval(),d.startDate(),d.endDate(),LocalTime.parse(d.localTime()),d.weekdays(),d.monthDay(),d.weekendPolicy(),d.holidayPolicy(),d.holidays(),zone); }
        catch(IllegalArgumentException|DateTimeException failure) { invalid("Invalid recurrence dates, interval or exclusion policy"); return null; }
    }
    private static ScheduleDefinition definition(ScheduleWrite r) { return new ScheduleDefinition(r.templateId(),r.employeeId(),r.frequency(),r.interval(),r.startDate(),r.endDate(),r.localTime(),r.weekdays()==null?List.of():r.weekdays(),r.monthDay(),r.weekendPolicy(),r.holidayPolicy(),r.holidays()==null?List.of():r.holidays()); }
    private void identical(String sql,UUID id,Object request) {
        try { if(!mapper.readTree(jdbc.queryForObject(sql,String.class,id)).equals(mapper.valueToTree(request))) conflict(); }
        catch(JsonProcessingException e) { throw new IllegalStateException("Stored routine request is invalid",e); }
    }
    private String json(Object value) { try { return mapper.writeValueAsString(value); } catch(JsonProcessingException e) { throw new IllegalStateException("Routine serialization failed",e); } }
    private <T>T read(String value,Class<T> type) { try { return mapper.readValue(value,type); } catch(JsonProcessingException e) { throw new IllegalStateException("Stored routine data is invalid",e); } }
    private List<Checklist> checklist(String value) { try { return mapper.readValue(value,new TypeReference<List<Checklist>>(){}); } catch(JsonProcessingException e) { throw new IllegalStateException("Stored routine checklist is invalid",e); } }
    private void advisory(String key) { jdbc.query("select pg_advisory_xact_lock(hashtextextended(?,0))",(rs,n)->0,key); }
    private void record(UUID actor,String action,UUID id,Object details) { withActor(actor,() -> audit.record("WORK_ROUTINE_"+action,"WORK_ROUTINE",id.toString(),json(details))); }
    private static void withActor(UUID actor,Runnable operation) {
        var previous=SecurityContextHolder.getContext(); var scoped=SecurityContextHolder.createEmptyContext();
        scoped.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(actor.toString(),null,List.of()));
        SecurityContextHolder.setContext(scoped); try { operation.run(); } finally { SecurityContextHolder.setContext(previous); }
    }
    private LocalDate today() { return LocalDate.now(officeZone); }
    private long count(String sql,Object...args) { Long n=jdbc.queryForObject(sql,Long.class,args); return n==null?0:n; }
    private static String trim(String s) { return s==null?"":s.trim(); }
    private static Timestamp ts(Instant i) { return Timestamp.from(i); }
    private static Instant instant(ResultSet rs,String column) throws SQLException { Timestamp t=rs.getTimestamp(column); return t==null?null:t.toInstant(); }
    private static void bounds(int page,int size) { if(page<0||size<1||size>50) invalid("Page must be nonnegative and size must be 1 to 50"); }
    private static <T> Page<T> page(List<T> values,long total,int page,int size) { return new Page<>(List.copyOf(values),total,(int)((total+size-1)/size),page,size); }
    private static void version(Long expected,long actual) { if(expected==null) invalid("The observed routine version is required"); if(expected<0||expected!=actual) conflict(); }
    private static void conflict() { throw new BusinessException("ROUTINE_VERSION_CONFLICT","This routine changed or the request ID was used with different values. Reload before submitting",HttpStatus.CONFLICT); }
    private static void invalid(String detail) { throw new BusinessException("ROUTINE_INVALID",detail,HttpStatus.UNPROCESSABLE_ENTITY); }
    private static void denied() { throw new BusinessException("ROUTINE_PERMISSION_DENIED","An active HR or Team Lead with current department assignment and work creation permission is required",HttpStatus.FORBIDDEN); }
    private static void notFound() { throw new BusinessException("ROUTINE_NOT_FOUND","The routine was not found",HttpStatus.NOT_FOUND); }

    public record Checklist(String title,Boolean required) { public Checklist(String title,boolean required) { this(title,Boolean.valueOf(required)); } }
    public record TemplateWrite(UUID requestId,String title,String instructions,List<Checklist> checklist,String assigneeRule,int dueOffsetDays) {}
    public record TemplateUpdate(Long expectedVersion,String title,String instructions,List<Checklist> checklist,String assigneeRule,int dueOffsetDays) {
        public TemplateUpdate(long expectedVersion,String title,String instructions,List<Checklist> checklist,String assigneeRule,int dueOffsetDays) { this(Long.valueOf(expectedVersion),title,instructions,checklist,assigneeRule,dueOffsetDays); }
    }
    public record Template(UUID id,UUID departmentId,long version,String title,String instructions,List<Checklist> checklist,String assigneeRule,int dueOffsetDays,Instant updatedAt) {}
    public record ScheduleDefinition(UUID templateId,UUID employeeId,String frequency,int interval,LocalDate startDate,LocalDate endDate,String localTime,List<Integer> weekdays,Integer monthDay,String weekendPolicy,String holidayPolicy,List<LocalDate> holidays) {}
    public record ScheduleWrite(UUID requestId,UUID templateId,UUID employeeId,String frequency,int interval,LocalDate startDate,LocalDate endDate,String localTime,List<Integer> weekdays,Integer monthDay,String weekendPolicy,String holidayPolicy,List<LocalDate> holidays) {}
    public record State(Long expectedVersion,Boolean paused) { public State(long expectedVersion,boolean paused) { this(Long.valueOf(expectedVersion),Boolean.valueOf(paused)); } }
    public record Retry(Long expectedVersion) { public Retry(long expectedVersion) { this(Long.valueOf(expectedVersion)); } }
    public record Context(UUID departmentId,String departmentName,String officeZone,LocalDate officeDate,List<ScheduledWorkMaterializer.EligibleAssignee> eligibleAssignees) {}
    public record Page<T>(List<T> items,long totalElements,int totalPages,int page,int size) {}
    public record Preview(String officeZone,List<RecurrenceCalendar.Occurrence> occurrences,String policyText) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Schedule(UUID id,UUID departmentId,UUID templateId,String templateTitle,long templateVersion,UUID employeeId,String assigneeName,String frequency,int interval,LocalDate startDate,LocalDate endDate,
            String localTime,List<Integer> weekdays,Integer monthDay,String weekendPolicy,String holidayPolicy,List<LocalDate> holidays,String officeZone,boolean paused,long version,Instant nextOccurrenceAt,long exceptionsCount,Instant createdAt) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Occurrence(LocalDate occurrenceDate,Instant scheduledAt,long templateVersion,UUID taskId,String status,String exceptionCode,String message,int attempts,long version) {}
    private record Captured(UUID templateId,long templateVersion,String title,String instructions,List<Checklist> checklist,String assigneeRule,int dueOffsetDays) {}
    private record StoredSchedule(UUID id,UUID departmentId,UUID creator,String creatorRole,UUID templateId,UUID employeeId,ScheduleDefinition definition,String officeZone,boolean paused,long version,LocalDate nextDate,Instant nextAt,Instant createdAt) {}
}
