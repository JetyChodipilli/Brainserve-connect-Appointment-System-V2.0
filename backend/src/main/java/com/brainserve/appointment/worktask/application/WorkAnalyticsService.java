package com.brainserve.appointment.worktask.application;

import com.brainserve.appointment.iam.api.CurrentAccountAuthority;
import com.brainserve.appointment.iam.api.CurrentAccountAuthority.Authority;
import com.brainserve.appointment.iam.api.CurrentAccountAuthority.WorkScope;
import com.brainserve.appointment.shared.application.BusinessException;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/** Live measurements from the established task, immutable commitment, submission and audit facts. */
@Service
public class WorkAnalyticsService {
    public static final String VERSION = "sprint9.v1";
    private static final List<String> STAGES = List.of("TEAM_LEAD","HR_ADMIN","MANAGER","CEO");
    private static final Set<String> ROLES = Set.of("ROLE_EMPLOYEE","ROLE_TEAM_LEAD","ROLE_HR_ADMIN","ROLE_MANAGER","ROLE_CEO");
    private static final int EXPORT_LIMIT = 5000;
    private final NamedParameterJdbcTemplate jdbc;
    private final CurrentAccountAuthority authority;
    private final ZoneId officeZone;
    private final Clock clock;

    @Autowired
    public WorkAnalyticsService(NamedParameterJdbcTemplate jdbc, CurrentAccountAuthority authority,
            @Value("${brainserve.appointment.office-zone:Asia/Kolkata}") String officeZone) {
        this(jdbc,authority,ZoneId.of(officeZone),Clock.systemUTC());
    }
    public WorkAnalyticsService(NamedParameterJdbcTemplate jdbc, CurrentAccountAuthority authority, ZoneId officeZone, Clock clock) {
        this.jdbc=jdbc;this.authority=authority;this.officeZone=officeZone;this.clock=clock;
    }

    @Transactional(readOnly=true)
    public Context context(UUID actor) {
        Scope a=authorize(actor,null); Instant now=clock.instant();
        List<DepartmentOption> departments=jdbc.query("select id,name from org_department where "
                +(a.workScope()==null ? "true" : "id=:departmentId")+" order by name,id",params(a,now,null,null),
                (rs,n)->new DepartmentOption(rs.getObject("id",UUID.class),rs.getString("name")));
        revalidate(actor,a);
        return new Context(VERSION,officeZone.getId(),date(now),a.scope(),departments,canWorkload(a),canHandover(a));
    }

    @Transactional(readOnly=true)
    public Workload workload(UUID actor,UUID departmentId) {
        Scope a=authorize(actor,departmentId); if(!canWorkload(a)) denied();
        Instant now=clock.instant();long generation=generation();var p=params(a,now,null,null);
        String sql=base()+"""
             , outstanding as (select * from scoped where not closed), participants as (
              select e.id,e.display_name,e.status,e.department_id,u.id user_id,u.enabled,u.account_status,u.archived,
               (select min(role_name) from iam_user_role where user_id=u.id) role,
               (select count(*) from iam_user_role where user_id=u.id) role_count,
               exists(select 1 from employee_leave_request l where l.employee_id=e.id and l.status='APPROVED'
                and l.start_date<=:today and l.end_date>=:today) on_leave
              from employee e left join iam_user_account u on u.employee_id=e.id
              where e.department_id=:departmentId or exists(select 1 from outstanding o where o.employee_id=e.id)
             ) select m.*,
              count(o.id) active_tasks,
              count(o.id) filter(where o.due_date=:today) due_today,
              count(o.id) filter(where o.due_date>:today) upcoming,
              count(o.id) filter(where o.due_date<:today and o.status in ('ASSIGNED','IN_PROGRESS','CHANGES_REQUESTED','INSIGHT_REWORK_REQUESTED') and o.review_stage is null) overdue_delivery,
              count(o.id) filter(where o.review_stage is not null) pending_review,
              count(o.id) filter(where o.blocked) blocked_tasks,
              sum(o.estimate_minutes) estimated_minutes,
              count(o.id) filter(where o.estimate_minutes is not null) estimated_tasks,
              count(o.id) filter(where o.estimate_minutes is null) unestimated_tasks
             from participants m left join outstanding o on o.employee_id=m.id
             where m.role in ('ROLE_EMPLOYEE','ROLE_TEAM_LEAD') or o.id is not null
             group by m.id,m.display_name,m.status,m.department_id,m.user_id,m.enabled,m.account_status,m.archived,m.role,m.role_count,m.on_leave
             order by lower(m.display_name),m.id
            """;
        List<Member> members=jdbc.query(sql,p,(rs,n)->{
            List<String> reasons=new ArrayList<>();
            if(!"ACTIVE".equals(rs.getString("status"))) reasons.add("EMPLOYEE_"+rs.getString("status"));
            UUID user=rs.getObject("user_id",UUID.class);
            if(user==null)reasons.add("NO_ACCOUNT");
            else {if(rs.getBoolean("archived"))reasons.add("ACCOUNT_ARCHIVED");if(!rs.getBoolean("enabled"))reasons.add("ACCOUNT_DISABLED");
                if(!"ACTIVE".equals(rs.getString("account_status")))reasons.add("ACCOUNT_"+rs.getString("account_status"));}
            String role=rs.getString("role");
            if(rs.getLong("role_count")!=1||!Set.of("ROLE_EMPLOYEE","ROLE_TEAM_LEAD").contains(role==null?"":role))reasons.add("ROLE_UNAVAILABLE");
            if(!Objects.equals(rs.getObject("department_id",UUID.class),a.departmentId()))reasons.add("DEPARTMENT_CHANGED");
            if(rs.getBoolean("on_leave"))reasons.add("APPROVED_LEAVE");
            return new Member(rs.getObject("id",UUID.class),user,rs.getString("display_name"),role,reasons.isEmpty(),List.copyOf(reasons),
                    rs.getLong("active_tasks"),rs.getLong("due_today"),rs.getLong("upcoming"),rs.getLong("overdue_delivery"),rs.getLong("pending_review"),
                    rs.getLong("blocked_tasks"),rs.getObject("estimated_minutes",Long.class),rs.getLong("estimated_tasks"),rs.getLong("unestimated_tasks"),null);
        });
        Totals totals=new Totals(members.size(),members.stream().filter(Member::eligible).count(),sum(members,Member::activeTasks),sum(members,Member::dueToday),
                sum(members,Member::upcoming),sum(members,Member::overdueDelivery),sum(members,Member::pendingReview),sum(members,Member::blocked),
                members.stream().noneMatch(m->m.estimatedMinutes()!=null)?null:members.stream().map(Member::estimatedMinutes).filter(Objects::nonNull).mapToLong(Long::longValue).sum(),
                sum(members,Member::estimatedTasks),sum(members,Member::unestimatedTasks),null);
        complete(actor,a,generation);
        return new Workload(VERSION,now,officeZone.getId(),date(now),a.scope(),a.departmentId(),departmentName(a.departmentId()),members,totals);
    }

    @Transactional(readOnly=true)
    public Summary summary(UUID actor,LocalDate from,LocalDate to,UUID departmentId) {
        Scope a=authorize(actor,departmentId);Instant now=clock.instant();Range r=range(from,to,now);long generation=generation();var p=params(a,now,r.from(),r.to());
        List<Card> cards=new ArrayList<>();for(int i=1;i<=13;i++)cards.add(card("WORK%02d".formatted(i),p));
        List<Stage> stages=stages(p);List<Trend> trend=trend(p);
        List<DepartmentTrend> departments=jdbc.query(base()+"select department_id,department_name,count(*) denominator,count(*) filter(where on_time) numerator from ("
                +observations("WORK07")+") o group by department_id,department_name order by department_name,department_id",p,
                (rs,n)->new DepartmentTrend(rs.getObject("department_id",UUID.class),rs.getString("department_name"),rs.getLong("denominator"),rs.getLong("numerator"),
                        WorkMetricCalculator.rate(rs.getLong("numerator"),rs.getLong("denominator")),List.of()));
        List<DepartmentTrend> withTrends=new ArrayList<>();for(DepartmentTrend d:departments){var dp=new MapSqlParameterSource(p.getValues()).addValue("trendDepartment",d.departmentId());
            withTrends.add(new DepartmentTrend(d.departmentId(),d.departmentName(),d.denominator(),d.numerator(),d.value(),trend(dp)));}
        complete(actor,a,generation);
        return new Summary(VERSION,now,officeZone.getId(),r.from(),r.to(),a.departmentId(),a.scope(),List.copyOf(cards),stages,trend,List.copyOf(withTrends));
    }

    @Transactional(readOnly=true)
    public Records records(UUID actor,String metricId,LocalDate from,LocalDate to,UUID departmentId,int page,int size) {
        return records(actor,metricId,from,to,departmentId,page,size,null);
    }
    @Transactional(readOnly=true)
    public Records records(UUID actor,String metricId,LocalDate from,LocalDate to,UUID departmentId,int page,int size,String expectedMetricVersion) {
        version(expectedMetricVersion);metric(metricId);
        if(page<0||page>10000||size<1||size>100)invalid("Choose page 0–10000 and size 1–100");
        Scope a=authorize(actor,departmentId);Instant now=clock.instant();Range r=range(from,to,now);long generation=generation();
        var p=params(a,now,r.from(),r.to()).addValue("limit",size).addValue("offset",(long)page*size);
        long total=count(metricId,p);List<RecordItem> items=recordItems(metricId,p);
        complete(actor,a,generation);
        return new Records(VERSION,now,page,size,total,(int)((total+size-1)/size),items);
    }

    @Transactional(readOnly=true)
    public String exportCsv(UUID actor,String metricId,LocalDate from,LocalDate to,UUID departmentId,String expectedMetricVersion) {
        version(expectedMetricVersion);metric(metricId);Scope a=authorize(actor,departmentId);Instant now=clock.instant();Range r=range(from,to,now);long generation=generation();
        var p=params(a,now,r.from(),r.to()).addValue("limit",EXPORT_LIMIT+1).addValue("offset",0L);
        long total=count(metricId,p);if(total>EXPORT_LIMIT)throw new BusinessException("WORK_ANALYTICS_EXPORT_TOO_LARGE","Narrow the filters to export at most 5000 observations",HttpStatus.UNPROCESSABLE_ENTITY);
        List<RecordItem> rows=recordItems(metricId,p);if(rows.size()!=total)changed();
        StringBuilder csv=new StringBuilder("metricVersion,metricId,from,to,scope,generatedAt,id,title,departmentId,departmentName,employeeId,assigneeName,status,originalDueDate,dueDate,occurredAt,detail\r\n");
        for(RecordItem row:rows){List<Object> cells=Arrays.asList(VERSION,metricId,r.from(),r.to(),a.scope(),now,row.id(),row.title(),row.departmentId(),row.departmentName(),row.employeeId(),row.assigneeName(),row.status(),row.originalDueDate(),row.dueDate(),row.occurredAt(),row.detail());
            csv.append(cells.stream().map(WorkMetricCalculator::csv).collect(Collectors.joining(","))).append("\r\n");}
        complete(actor,a,generation);return csv.toString();
    }
    private List<RecordItem> recordItems(String id,MapSqlParameterSource p){
        // Scope joins use CURRENT department, participant and authority. Historical event
        // details never grant a departed employee access to another employee's worksheet.
        return jdbc.query(base()+", observations as materialized ("+observations(id)+") select * from observations order by occurred_at desc nulls last,id,observation_key limit :limit offset :offset",p,
                (rs,n)->new RecordItem(rs.getObject("id",UUID.class),rs.getString("title"),rs.getObject("department_id",UUID.class),rs.getString("department_name"),rs.getObject("employee_id",UUID.class),
                        rs.getString("assignee_name"),rs.getString("status"),rs.getObject("original_due_date",LocalDate.class),rs.getObject("due_date",LocalDate.class),instant(rs,"occurred_at"),rs.getString("detail")));
    }
    private long count(String id,MapSqlParameterSource p){return Objects.requireNonNull(jdbc.queryForObject(base()+"select count(*) from ("+observations(id)+") o",p,Long.class));}
    private Card card(String id,MapSqlParameterSource p){
        Definition d=metric(id);
        Stats stats=jdbc.queryForObject(base()+"select count(*) total,count(*) filter(where on_time) numerator,count(measure) samples,percentile_cont(0.95) within group(order by measure) p95 from ("+observations(id)+") o",p,
                (rs,n)->new Stats(rs.getLong("total"),rs.getLong("numerator"),rs.getLong("samples"),rs.getObject("p95",Double.class)));
        long known=stats.total(),coverage=stats.total(),excluded=0;String reason=d.reason();Double value=(double)stats.total();
        if(id.equals("WORK07")){
            var c=jdbc.queryForObject(base()+"select count(original_due_date_fact) known,count(*) total from scoped",p,(rs,n)->new long[]{rs.getLong("known"),rs.getLong("total")});
            known=c[0];coverage=c[1];excluded=coverage-known;value=WorkMetricCalculator.rate(stats.numerator(),stats.total());
            reason="Current valid evidence acceptance by original office cutoff; late and unfinished work remain due denominator. Unknown originals are excluded. Coverage describes all retained scoped tasks.";
            if(((OffsetDateTime)p.getValue("end")).toInstant().isAfter(((OffsetDateTime)p.getValue("asOf")).toInstant()))reason+=" Preliminary: the selected due cohort has not fully elapsed.";
        }else if(id.equals("WORK09")){value=WorkMetricCalculator.rate(stats.numerator(),stats.total());long backlog=Objects.requireNonNull(jdbc.queryForObject(base()+"select count(*) from scoped where not closed and status in ('ASSIGNED','IN_PROGRESS','CHANGES_REQUESTED','INSIGHT_REWORK_REQUESTED') and (had_rework or status in ('CHANGES_REQUESTED','INSIGHT_REWORK_REQUESTED'))",p,Long.class));reason="Closed cohort only. Current unresolved rework backlog: "+backlog+" tasks.";
        }else if(id.equals("WORK08")){value=stats.p95();known=stats.samples();coverage=stats.total();excluded=coverage-known;reason="Exited review intervals in this period, all stages combined; per-stage median/p95 and unresolved queue ages are provided. V62 captures actual entries; legacy entries are unknown.";
        }else if(id.equals("WORK10")){value=stats.p95();known=stats.samples();coverage=stats.total();excluded=coverage-known;
        }else if(id.equals("WORK06")){long unique=Objects.requireNonNull(jdbc.queryForObject(base()+"select count(distinct id) from ("+observations(id)+") o",p,Long.class));reason="Submission attempts: "+stats.total()+"; unique tasks: "+unique+". Historical authorship stays attached to each evidence version; legacy missing versions cannot be reconstructed.";
        }else if(id.equals("WORK11")){long estimated=Objects.requireNonNull(jdbc.queryForObject(base()+"select count(*) from scoped where not closed and estimate_minutes is not null",p,Long.class));known=estimated;coverage=stats.total();excluded=coverage-known;reason="Outstanding assignments are counted once per current member. Estimates cover "+estimated+" of "+stats.total()+" tasks. Capacity is unknown; no maintained capacity input exists. No ranking or utilization is inferred.";}
        if(id.equals("WORK01")||id.equals("WORK02")){
            var split=jdbc.queryForObject(base()+"select count(*) filter(where review_stage is null) delivery,count(*) filter(where review_stage is not null) review from scoped where not closed"+(id.equals("WORK02")?" and due_date=:today":""),p,
                    (rs,n)->new long[]{rs.getLong("delivery"),rs.getLong("review")});
            reason="Delivery/rework: "+split[0]+"; pending review: "+split[1]+". Detail observations retain the current review stage.";
        }else if(id.equals("WORK03")){
            long waiting=Objects.requireNonNull(jdbc.queryForObject(base()+"select count(*) from scoped where not closed and review_stage is not null and due_date<:today",p,Long.class));
            reason="Execution overdue only. "+waiting+" overdue-date submitted tasks are awaiting review separately.";
        }else if(id.equals("WORK05")&&p.getValue("actorStage")==null)reason="The current actor has no eligible actionable governance review stage.";
        if(stats.total()==0)reason=(reason==null?"":reason+" ")+"No observations in this cohort.";
        return new Card(id,d.title(),d.kind(),d.unit(),value,(id.equals("WORK07")||id.equals("WORK09"))?stats.numerator():null,
                (id.equals("WORK07")||id.equals("WORK09"))?stats.total():null,id.equals("WORK08")||id.equals("WORK10")?stats.samples():stats.total(),known,coverage,excluded,(id.equals("WORK08")||id.equals("WORK10")?stats.samples():stats.total())<5,d.definition(),reason);
    }
    private List<Stage> stages(MapSqlParameterSource p){
        List<Stage> result=new ArrayList<>();for(String stage:STAGES){var sp=new MapSqlParameterSource(p.getValues()).addValue("stage",stage);
            result.add(jdbc.queryForObject(base()+", observations as ("+observations("WORK08")+") select count(measure) n,percentile_cont(0.5) within group(order by measure) median,percentile_cont(0.95) within group(order by measure) p95,"
                    +"count(*) filter(where unresolved) unresolved,max(age_seconds) filter(where unresolved) oldest,count(*) filter(where measure is not null or (unresolved and age_seconds is not null)) known,count(*) total from observations where stage=:stage",sp,
                    (rs,n)->new Stage(stage,rs.getLong("n"),rs.getObject("median",Double.class),rs.getObject("p95",Double.class),rs.getLong("unresolved"),rs.getObject("oldest",Double.class),rs.getLong("known"),rs.getLong("total"))));}
        return List.copyOf(result);
    }
    private List<Trend> trend(MapSqlParameterSource p){
        String extra=p.hasValue("trendDepartment")?" and department_id=:trendDepartment":"";
        var byDate=jdbc.query(base()+"select original_due_date,count(*) denominator,count(*) filter(where on_time) numerator from ("+observations("WORK07")+") o where true"+extra+" group by original_due_date order by original_due_date",p,
                (rs,n)->new Trend(rs.getObject("original_due_date",LocalDate.class),rs.getLong("denominator"),rs.getLong("numerator"),WorkMetricCalculator.rate(rs.getLong("numerator"),rs.getLong("denominator"))));
        Map<LocalDate,Trend> dates=byDate.stream().collect(Collectors.toMap(Trend::date,t->t));List<Trend> result=new ArrayList<>();
        for(LocalDate date=(LocalDate)p.getValue("from");!date.isAfter((LocalDate)p.getValue("to"));date=date.plusDays(1))result.add(dates.getOrDefault(date,new Trend(date,0,0,null)));
        return List.copyOf(result);
    }

    private String base(){return """
        with scoped as materialized (
         select t.*,d.name department_name,e.display_name assignee_name,c.original_due_date original_fact_due_date,
          c.original_due_date original_due_date_fact,c.committed_at,
          a.audit_status,a.rework_cycle audit_rework_cycle,
          coalesce(a.audit_status='CEO_APPROVED',false) closed,
          exists(select 1 from workboard_activity_event rework where rework.work_task_id=t.id
           and rework.event_type='STATUS_CHANGED' and rework.current_status in ('CHANGES_REQUESTED','INSIGHT_REWORK_REQUESTED')
           and not coalesce((rework.details_json->>'backfilled')::boolean,false) and rework.actor_id not like 'flyway%' and rework.occurred_at<=:asOf) had_rework,
          acceptance.accepted_at,
          work_current_review_stage(t.id) review_stage
         from department_work_task t join org_department d on d.id=t.department_id join employee e on e.id=t.employee_id
         left join work_original_commitment c on c.work_task_id=t.id
         left join work_task_audit_record a on a.work_task_id=t.id
         left join reporting_work_current_acceptance acceptance on acceptance.work_task_id=t.id
         where t.created_at<=:asOf and (:departmentId::uuid is null or t.department_id=:departmentId)
          and (:employeeId::uuid is null or (t.employee_id=:employeeId and t.assignee_role='EMPLOYEE'))
        ), final_approvals as materialized (
         select distinct on (t.id,e.target_id,coalesce(case when pg_input_is_valid(e.details_json->>'reworkCycle','integer')
             then (e.details_json->>'reworkCycle')::integer end,a.rework_cycle))
          t.id task_id,e.target_id audit_id,
          coalesce(case when pg_input_is_valid(e.details_json->>'reworkCycle','integer') then (e.details_json->>'reworkCycle')::integer end,a.rework_cycle) cycle,
          e.occurred_at,e.id event_id
         from scoped t join work_task_audit_record a on a.work_task_id=t.id
         join audit_event_history e on e.details_json->>'workTaskId'=t.id::text and e.target_id=a.id::text
         where e.event_type='WORK_INSIGHT_CEO_APPROVED' and e.target_type='WORK_TASK_AUDIT' and e.outcome='SUCCESS'
          and e.actor_id not like 'flyway%' and e.occurred_at<=:asOf
         order by t.id,e.target_id,coalesce(case when pg_input_is_valid(e.details_json->>'reworkCycle','integer')
             then (e.details_json->>'reworkCycle')::integer end,a.rework_cycle),e.occurred_at,e.id
        )
        """;}
    // All metrics expose the same fields for statistics, paginated observations and CSV.
    // Current due-date cohorts never substitute for the immutable original due fact.
    private String taskFields(){return "t.id,t.title,t.department_id,t.department_name,t.employee_id,t.assignee_name,t.status,t.original_due_date_fact original_due_date,t.due_date";}
    private String standard(String where,String occurred,String detail){return "select "+taskFields()+","+occurred+" occurred_at,"+detail+" detail,t.id::text observation_key,0::double precision measure,false on_time from scoped t where "+where;}
    private String observations(String id){return switch(id){
        case "WORK01","WORK11" -> standard("not closed","t.created_at","'Outstanding; stage: '||coalesce(t.review_stage,'delivery/rework')||'; estimate minutes: '||coalesce(t.estimate_minutes::text,'unknown')");
        case "WORK02" -> standard("not closed and t.due_date=:today","(t.due_date+1)::timestamp at time zone :officeZone","'Due today; stage: '||coalesce(t.review_stage,'delivery/rework')");
        case "WORK03" -> standard("not closed and t.due_date<:today and t.review_stage is null and t.status in ('ASSIGNED','IN_PROGRESS','CHANGES_REQUESTED','INSIGHT_REWORK_REQUESTED')",
                "(t.due_date+1)::timestamp at time zone :officeZone","'Execution overdue; current evidence has not been accepted. Submitted review queues are counted separately.'");
        case "WORK04" -> "select "+taskFields()+",(b->>'raisedAt')::timestamptz occurred_at,'Blocker: '||coalesce(b->>'reason','')||'; owner: '||coalesce(b->>'contactUserId','unknown')||'; age seconds: '||extract(epoch from (:asOf-(b->>'raisedAt')::timestamptz))::text detail,b->>'id' observation_key,0::double precision measure,false on_time from scoped t cross join lateral jsonb_array_elements(coalesce(t.planning_state->'blockers','[]'::jsonb)) b where b->>'resolvedAt' is null and pg_input_is_valid(b->>'raisedAt','timestamp with time zone') and (b->>'raisedAt')::timestamptz<=:asOf";
        case "WORK05" -> standard("not closed and t.review_stage=:actorStage and (:actorStage<>'TEAM_LEAD' or (t.team_lead_user_id=:actor and (t.assignee_role='TEAM_LEAD' or t.employee_id<>:actorEmployeeId)))","t.updated_at","'Actionable current review stage: '||t.review_stage");
        case "WORK06" -> "select distinct on(t.id,s->>'version',coalesce(s->>'assignmentRevision','0')) "+taskFields()+",(s->>'submittedAt')::timestamptz occurred_at,'Evidence version: '||(s->>'version')||'; assignment revision: '||coalesce(s->>'assignmentRevision','legacy 0')||'; authored by: '||coalesce(s->>'authorName','unknown legacy author') detail,t.id::text||':'||(s->>'version')||':'||coalesce(s->>'assignmentRevision','0') observation_key,0::double precision measure,false on_time from scoped t cross join lateral jsonb_array_elements(coalesce(t.planning_state->'submissions','[]'::jsonb)) s where pg_input_is_valid(s->>'version','bigint') and (s->>'version')::bigint>0 and pg_input_is_valid(s->>'submittedAt','timestamp with time zone') and (s->>'submittedAt')::timestamptz>=:start and (s->>'submittedAt')::timestamptz<:end and (s->>'submittedAt')::timestamptz<=:asOf order by t.id,s->>'version',coalesce(s->>'assignmentRevision','0'),(s->>'submittedAt')::timestamptz";
        case "WORK07" -> "select "+taskFields()+",(t.original_due_date_fact+1)::timestamp at time zone :officeZone occurred_at,'Original due date: '||t.original_due_date_fact::text||'; current valid acceptance: '||coalesce(t.accepted_at::text,'unfinished / unknown') detail,t.id::text observation_key,0::double precision measure,(t.accepted_at is not null and t.accepted_at<=:asOf and t.accepted_at<:end and t.accepted_at<(t.original_due_date_fact+1)::timestamp at time zone :officeZone) on_time from scoped t where t.original_due_date_fact>=:from and t.original_due_date_fact<=:to and t.committed_at<=:asOf";
        case "WORK08" -> stageObservations();
        case "WORK09" -> "select distinct on(t.id) "+taskFields()+",f.occurred_at,'Final approval; recorded rework: '||t.had_rework::text detail,t.id::text observation_key,0::double precision measure,t.had_rework on_time from scoped t join final_approvals f on f.task_id=t.id where t.closed and f.occurred_at>=:start and f.occurred_at<:end order by t.id,f.occurred_at desc";
        case "WORK10" -> "select "+taskFields()+",(b->>'resolvedAt')::timestamptz occurred_at,'Resolved blocker: '||coalesce(b->>'reason','')||'; raised: '||coalesce(b->>'raisedAt','unknown')||'; resolved: '||(b->>'resolvedAt') detail,b->>'id' observation_key,case when pg_input_is_valid(b->>'raisedAt','timestamp with time zone') and (b->>'resolvedAt')::timestamptz>=(b->>'raisedAt')::timestamptz then extract(epoch from ((b->>'resolvedAt')::timestamptz-(b->>'raisedAt')::timestamptz))::double precision else null end measure,false on_time from scoped t cross join lateral jsonb_array_elements(coalesce(t.planning_state->'blockers','[]'::jsonb)) b where pg_input_is_valid(b->>'resolvedAt','timestamp with time zone') and (b->>'resolvedAt')::timestamptz>=:start and (b->>'resolvedAt')::timestamptz<:end and (b->>'resolvedAt')::timestamptz<=:asOf";
        case "WORK12" -> standard("not closed and (t.created_at at time zone :officeZone)::date<:today","t.created_at","'Carried from an earlier office day; counted once regardless of overdue duration.'");
        case "WORK13" -> "select "+taskFields()+",f.occurred_at,'Final audit: '||f.audit_id||'; cycle: '||f.cycle::text detail,f.audit_id||':'||f.cycle::text observation_key,0::double precision measure,false on_time from scoped t join final_approvals f on f.task_id=t.id where f.occurred_at>=:start and f.occurred_at<:end";
        default -> throw new IllegalArgumentException("Unsupported metric");
    };}
    private String stageObservations(){return """
        select t.id,t.title,t.department_id,t.department_name,t.employee_id,t.assignee_name,t.status,
         t.original_due_date_fact original_due_date,t.due_date,x.occurred_at,
         'Stage: '||x.previous_stage||'; entry: '||coalesce(x.entry_at::text,'unknown')||'; exit: '||x.occurred_at::text detail,
         x.id::text observation_key,
         case when x.previous_stage=x.entry_stage and x.occurred_at>=x.entry_at
           then extract(epoch from (x.occurred_at-x.entry_at))::double precision end measure,
         false on_time,x.previous_stage stage,false unresolved,null::double precision age_seconds
        from scoped t join (
         select e.*,lag(e.occurred_at) over(partition by work_task_id order by id) entry_at,
          lag(e.stage) over(partition by work_task_id order by id) entry_stage
         from work_review_stage_event e where e.occurred_at<=:asOf
        ) x on x.work_task_id=t.id
        where x.previous_stage is not null and x.occurred_at>=:start and x.occurred_at<:end
        union all
        select t.id,t.title,t.department_id,t.department_name,t.employee_id,t.assignee_name,t.status,
         t.original_due_date_fact,t.due_date,last_entry.occurred_at,
         'Unresolved stage: '||t.review_stage||'; entry: '||coalesce(last_entry.occurred_at::text,'unknown legacy entry'),
         t.id::text||':open',null::double precision,false,t.review_stage,true,
         case when last_entry.occurred_at<=:asOf then extract(epoch from (:asOf-last_entry.occurred_at))::double precision end
        from scoped t left join lateral (
         select e.occurred_at from work_review_stage_event e where e.work_task_id=t.id
          and e.occurred_at<=:asOf and e.stage=t.review_stage and e.assignment_revision=t.assignment_revision
          and e.submission_version is not distinct from t.submission_version and e.audit_cycle=coalesce(t.audit_rework_cycle,t.rework_cycle)
          and not exists(select 1 from work_review_stage_event later where later.work_task_id=e.work_task_id
             and later.id>e.id and later.occurred_at<=:asOf)
         order by e.id desc limit 1
        ) last_entry on true where t.review_stage is not null
        """;}

    private Scope authorize(UUID actor,UUID requestedDepartment){
        Authority a=authority.requireActive(actor);if(!ROLES.contains(a.role()))denied();
        if(a.role().equals("ROLE_CEO")){
            if(!a.permissions().contains("WORK_INSIGHT_READ")||!a.permissions().contains("WORK_INSIGHT_CEO_APPROVE")||!a.permissions().contains("REPORT_VIEW")||a.employeeId()==null)denied();
            Boolean active=jdbc.queryForObject("select exists(select 1 from employee where id=:id and status='ACTIVE')",new MapSqlParameterSource("id",a.employeeId()),Boolean.class);if(!Boolean.TRUE.equals(active))denied();
            if(requestedDepartment!=null&&!Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from org_department where id=:id)",new MapSqlParameterSource("id",requestedDepartment),Boolean.class)))missing();
            return new Scope(a,null,"COMPANY",requestedDepartment,actor);
        }
        WorkScope w=authority.requireWorkScope(actor);if(!w.authority().equals(a)||!a.permissions().contains("WORK_TASK_READ"))denied();
        if(!a.role().equals("ROLE_EMPLOYEE")&&!a.permissions().contains("REPORT_VIEW"))denied();
        if(requestedDepartment!=null&&!requestedDepartment.equals(w.departmentId()))missing();
        return new Scope(a,w,a.role().equals("ROLE_EMPLOYEE")?"OWN":"DEPARTMENT",w.departmentId(),actor);
    }
    private void revalidate(UUID actor,Scope before){if(!before.equals(authorize(actor,before.departmentId())))denied();}
    private void complete(UUID actor,Scope before,long generation){revalidate(actor,before);if(generation!=generation())changed();}
    private long generation(){return Objects.requireNonNull(jdbc.queryForObject("select generation from reporting_source_revision where singleton",new MapSqlParameterSource(),Long.class));}
    private boolean canWorkload(Scope a){return Set.of("ROLE_HR_ADMIN","ROLE_TEAM_LEAD").contains(a.authority().role());}
    private boolean canHandover(Scope a){return canWorkload(a)&&a.authority().permissions().contains("WORK_TASK_CREATE");}
    private String departmentName(UUID id){return id==null?null:jdbc.queryForObject("select name from org_department where id=:id",new MapSqlParameterSource("id",id),String.class);}
    private MapSqlParameterSource params(Scope a,Instant now,LocalDate from,LocalDate to){
        String stage=switch(a.authority().role()){
            case "ROLE_TEAM_LEAD"->a.authority().permissions().contains("WORK_TASK_REVIEW")?"TEAM_LEAD":null;
            case "ROLE_HR_ADMIN"->a.authority().permissions().contains("WORK_INSIGHT_AUDIT")?"HR_ADMIN":null;
            case "ROLE_MANAGER"->a.authority().permissions().contains("WORK_INSIGHT_MANAGER_APPROVE")?"MANAGER":null;
            case "ROLE_CEO"->a.authority().permissions().contains("WORK_INSIGHT_CEO_APPROVE")?"CEO":null;default->null;};
        return new MapSqlParameterSource().addValue("departmentId",a.departmentId()).addValue("employeeId",a.scope().equals("OWN")?a.authority().employeeId():null)
                .addValue("actor",a.actor()).addValue("actorEmployeeId",a.authority().employeeId()).addValue("today",date(now)).addValue("officeZone",officeZone.getId())
                .addValue("actorStage",stage).addValue("asOf",now.atOffset(ZoneOffset.UTC)).addValue("from",from).addValue("to",to)
                .addValue("start",from==null?null:from.atStartOfDay(officeZone).toOffsetDateTime()).addValue("end",to==null?null:to.plusDays(1).atStartOfDay(officeZone).toOffsetDateTime());
    }
    private Range range(LocalDate from,LocalDate to,Instant now){
        if(from==null&&to==null){to=date(now);from=to.minusDays(6);}
        if(from==null||to==null||from.isAfter(to)||ChronoUnit.DAYS.between(from,to)>=366||to.equals(LocalDate.MAX))invalid("Choose an inclusive office-date range of at most 366 days");
        return new Range(from,to);
    }
    private LocalDate date(Instant now){return now.atZone(officeZone).toLocalDate();}
    private static Instant instant(ResultSet rs,String field)throws SQLException{return rs.getTimestamp(field)==null?null:rs.getTimestamp(field).toInstant();}
    private static long sum(List<Member> members,java.util.function.ToLongFunction<Member> value){return members.stream().mapToLong(value).sum();}
    private static void version(String supplied){if(supplied!=null&&!VERSION.equals(supplied))throw new BusinessException("WORK_ANALYTICS_VERSION_CONFLICT","Metric definitions changed. Reload the summary before drilling or exporting",HttpStatus.CONFLICT);}
    private static void invalid(String message){throw new BusinessException("WORK_ANALYTICS_INVALID",message,HttpStatus.BAD_REQUEST);}
    private static void missing(){throw new BusinessException("WORK_ANALYTICS_SCOPE_NOT_FOUND","Work scope was not found",HttpStatus.NOT_FOUND);}
    private static void denied(){throw new BusinessException("WORK_ANALYTICS_PERMISSION_DENIED","Your current role, department or permissions do not allow this work measurement",HttpStatus.FORBIDDEN);}
    private static void changed(){throw new BusinessException("WORK_ANALYTICS_CHANGED","Work or access changed while this view was measured. Reload before exporting",HttpStatus.CONFLICT);}
    private static Definition metric(String id){return switch(id){
        case "WORK01"->new Definition(id,"Outstanding work","COUNT","TASKS","Distinct scoped tasks still in delivery, review or rework, excluding final CEO approval.",null);
        case "WORK02"->new Definition(id,"Due today","COUNT","TASKS","Outstanding tasks due on the current office date; delivery and review stages remain separate.",null);
        case "WORK03"->new Definition(id,"Overdue delivery","COUNT","TASKS","Execution work past the current office-day delivery cutoff without accepted current evidence. Submitted review queues are separate.",null);
        case "WORK04"->new Definition(id,"Open blockers","COUNT","BLOCKERS","Unresolved blocker intervals with contact, reason and age, independent of approval state.",null);
        case "WORK05"->new Definition(id,"My review queue","COUNT","TASKS","Current tasks at the actor's eligible actionable review stage.","Employee delivery work has no governance review queue.");
        case "WORK06"->new Definition(id,"Submissions","COUNT","ATTEMPTS","Distinct task, assignment and evidence versions submitted within the selected office-date period, independent of approval.",null);
        case "WORK07"->new Definition(id,"On-time accepted delivery","RATE","PERCENT","Current valid delivery evidence accepted by Lead for Employee or HR for direct Lead before immutable original office cutoff, divided by all known original commitments due in period.",null);
        case "WORK08"->new Definition(id,"Review turnaround p95","DISTRIBUTION","SECONDS","Decision minus actual current-stage entry for exits within period, by Lead, HR, Manager and CEO; unresolved ages and coverage accompany the distribution.",null);
        case "WORK09"->new Definition(id,"Closed work requiring rework","RATE","PERCENT","Tasks finally approved by CEO in period with at least one rework cycle divided by all tasks finally approved in period. Current rework backlog is shown separately.",null);
        case "WORK10"->new Definition(id,"Blocker resolution p95","DISTRIBUTION","SECONDS","Resolved blocker interval durations whose resolution falls in period; missing and negative intervals are excluded and disclosed.","Open intervals remain in WORK04; zero-duration observations are retained.");
        case "WORK11"->new Definition(id,"Workload distribution","COUNT","TASKS","Outstanding current assignments per member, with partial effort estimates and unknown capacity; unavailable retained assignments remain visible.",null);
        case "WORK12"->new Definition(id,"Carried work","COUNT","TASKS","Outstanding tasks created before the current office date, each counted once.",null);
        case "WORK13"->new Definition(id,"Final approvals","COUNT","APPROVALS","Distinct task and audit cycle final CEO approval events in period; repeated delivery of the same event does not increase throughput.",null);
        default->throw new BusinessException("WORK_ANALYTICS_METRIC_NOT_FOUND","Work metric was not found",HttpStatus.NOT_FOUND);
    };}
    private record Definition(String id,String title,String kind,String unit,String definition,String reason){}
    private record Stats(long total,long numerator,long samples,Double p95){}
    private record Range(LocalDate from,LocalDate to){}
    private record Scope(Authority authority,WorkScope workScope,String scope,UUID departmentId,UUID actor){}
    public record DepartmentOption(UUID id,String name){}
    public record Context(String metricVersion,String officeZone,LocalDate officeDate,String scope,List<DepartmentOption> departmentOptions,boolean canReadWorkload,boolean canHandover){}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Member(UUID employeeId,UUID userId,String name,String role,boolean eligible,List<String> unavailableReasons,long activeTasks,long dueToday,long upcoming,long overdueDelivery,long pendingReview,long blocked,Long estimatedMinutes,long estimatedTasks,long unestimatedTasks,Long capacityMinutes){}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Totals(long memberCount,long eligibleMembers,long activeTasks,long dueToday,long upcoming,long overdueDelivery,long pendingReview,long blocked,Long estimatedMinutes,long estimatedTasks,long unestimatedTasks,Long capacityMinutes){}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Workload(String metricVersion,Instant generatedAt,String officeZone,LocalDate officeDate,String scope,UUID departmentId,String departmentName,List<Member> members,Totals totals){}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Card(String id,String title,String kind,String unit,Double value,Long numerator,Long denominator,long sampleCount,long coverageKnown,long coverageTotal,long excluded,boolean smallSample,String definition,String reason){}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Stage(String stage,long sampleCount,Double medianSeconds,Double p95Seconds,long unresolved,Double oldestUnresolvedSeconds,long coverageKnown,long coverageTotal){}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Trend(LocalDate date,long denominator,long numerator,Double value){}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record DepartmentTrend(UUID departmentId,String departmentName,long denominator,long numerator,Double value,List<Trend> trend){}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Summary(String metricVersion,Instant generatedAt,String officeZone,LocalDate from,LocalDate to,UUID departmentId,String scope,List<Card> cards,List<Stage> stages,List<Trend> trend,List<DepartmentTrend> departmentTrends){}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record RecordItem(UUID id,String title,UUID departmentId,String departmentName,UUID employeeId,String assigneeName,String status,LocalDate originalDueDate,LocalDate dueDate,Instant occurredAt,String detail){}
    public record Records(String metricVersion,Instant generatedAt,int number,int size,long totalElements,int totalPages,List<RecordItem> items){}
}
