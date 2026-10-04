package com.brainserve.appointment.workinsight.application;
import com.brainserve.appointment.iam.api.SearchActorScope;

import com.brainserve.appointment.workinsight.api.WorkInsightSearch;
import com.brainserve.appointment.shared.api.*;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.stereotype.Service;
import java.util.Map;
import java.util.UUID;

/** CEO search sees published oversight snapshots, never unsubmitted live worksheet descriptions. */
@Service
public class WorkInsightSearchService implements WorkInsightSearch {
    private static final String COVERAGE="Worksheets published to CEO oversight; names and titles are retained audit snapshots";
    private final NamedParameterJdbcTemplate jdbc;private final SearchActorScope scopes;
    public WorkInsightSearchService(NamedParameterJdbcTemplate jdbc,SearchActorScope scopes){this.jdbc=jdbc;this.scopes=scopes;}
    @Override public SearchContract.Group search(UUID actor,String query,int page,int size){
        query=SearchContract.query(query);SearchContract.bounds(page,size);var s=scopes.resolve(actor);
        if(!allowed(s))return SearchContract.Group.unavailable("worksheets","Worksheets",COVERAGE,page,size);
        var result=SearchSqlPage.read(jdbc,"worksheets","Worksheets",COVERAGE,
                "select a.work_task_id id,a.task_title title,a.department_name subtitle,a.audit_status status from work_task_audit_record a where "+filter()+" and lower(a.task_title || ' ' || a.department_name) like :query escape '!'",new MapSqlParameterSource("query",SearchContract.literal(query)),page,size);
        scopes.revalidate(actor,s);return result;
    }
    @Override public SearchContract.OpenRecord open(UUID actor,UUID id){
        var s=scopes.resolve(actor);if(!allowed(s))throw SearchContract.notFound();
        var rows=jdbc.query("select a.work_task_id,a.task_title,a.department_name,a.audit_status,a.employee_name,a.week_start from work_task_audit_record a where "+filter()+" and a.work_task_id=:id",new MapSqlParameterSource("id",id),
                (rs,n)->new SearchContract.OpenRecord("worksheets",rs.getObject("work_task_id",UUID.class),rs.getString("task_title"),rs.getString("department_name"),rs.getString("audit_status"),"insights",Map.of("Assignee",rs.getString("employee_name"),"Branch",rs.getString("department_name"),"Week starts",rs.getDate("week_start").toLocalDate().toString(),"Source","Published oversight snapshot")));
        scopes.revalidate(actor,s);if(rows.size()!=1)throw SearchContract.notFound();return rows.getFirst();
    }
    private boolean allowed(SearchActorScope.Scope s){return s.role().equals("ROLE_CEO") && s.has("WORK_INSIGHT_READ") && s.has("WORK_INSIGHT_CEO_APPROVE");}
    private String filter(){return "a.audit_status in ('PENDING_CEO_APPROVAL','CEO_APPROVED','CEO_REWORK_REQUESTED')";}
}
