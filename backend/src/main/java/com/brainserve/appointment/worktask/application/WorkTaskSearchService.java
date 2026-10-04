package com.brainserve.appointment.worktask.application;
import com.brainserve.appointment.iam.api.SearchActorScope;

import com.brainserve.appointment.worktask.api.WorkTaskSearch;
import com.brainserve.appointment.worktask.api.TaskActivityAccess;
import com.brainserve.appointment.shared.api.*;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.stereotype.Service;
import java.util.Map;
import java.util.UUID;

@Service
public class WorkTaskSearchService implements WorkTaskSearch {
    private static final String COVERAGE="Worksheet titles, descriptions and branches in your Work board scope";
    private final NamedParameterJdbcTemplate jdbc;private final SearchActorScope scopes;private final TaskActivityAccess access;
    public WorkTaskSearchService(NamedParameterJdbcTemplate jdbc,SearchActorScope scopes,TaskActivityAccess access){this.jdbc=jdbc;this.scopes=scopes;this.access=access;}
    @Override public SearchContract.Group search(UUID actor,String query,int page,int size){
        query=SearchContract.query(query);SearchContract.bounds(page,size);var s=scopes.resolve(actor);
        if(!allowed(s))return SearchContract.Group.unavailable("worksheets","Worksheets",COVERAGE,page,size);
        var result=SearchSqlPage.read(jdbc,"worksheets","Worksheets",COVERAGE,"select t.id,t.title,t.department_branch subtitle,t.status from department_work_task t where "+filter(s)+" and lower(t.title || ' ' || t.description || ' ' || t.department_branch) like :query escape '!'",params(actor,s).addValue("query",SearchContract.literal(query)),page,size);
        scopes.revalidate(actor,s);return result;
    }
    @Override public SearchContract.OpenRecord open(UUID actor,UUID id){
        var s=scopes.resolve(actor);if(!allowed(s))throw SearchContract.notFound();
        var before=access.require(actor,id);
        var rows=jdbc.query("select t.id,t.title,t.department_branch,t.status,t.description,t.due_date from department_work_task t where "+filter(s)+" and t.id=:id",params(actor,s).addValue("id",id),(rs,n)->new SearchContract.OpenRecord("worksheets",rs.getObject("id",UUID.class),rs.getString("title"),rs.getString("department_branch"),rs.getString("status"),"work",Map.of("Description",rs.getString("description"),"Due date",rs.getDate("due_date").toLocalDate().toString(),"Branch",rs.getString("department_branch"))));
        scopes.revalidate(actor,s);access.revalidate(actor,before);if(rows.size()!=1)throw SearchContract.notFound();return rows.getFirst();
    }
    private boolean allowed(SearchActorScope.Scope s){return s.work()!=null && s.has("WORK_TASK_READ");}
    private String filter(SearchActorScope.Scope s){return "t.department_id=:department"+(s.role().equals("ROLE_EMPLOYEE")?" and t.employee_id=:employee and t.assignee_role='EMPLOYEE'":s.role().equals("ROLE_TEAM_LEAD")?" and t.team_lead_user_id=:actor":"");}
    private MapSqlParameterSource params(UUID actor,SearchActorScope.Scope s){return new MapSqlParameterSource("department",s.departmentId()).addValue("employee",s.employeeId()).addValue("actor",actor);}
}
