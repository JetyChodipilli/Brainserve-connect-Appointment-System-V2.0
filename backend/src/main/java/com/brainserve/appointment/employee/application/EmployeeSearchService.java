package com.brainserve.appointment.employee.application;
import com.brainserve.appointment.iam.api.SearchActorScope;

import com.brainserve.appointment.employee.api.EmployeeSearch;
import com.brainserve.appointment.shared.api.*;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.stereotype.Service;
import java.util.Map;
import java.util.UUID;

@Service
public class EmployeeSearchService implements EmployeeSearch {
    private static final String COVERAGE="Active employees: names, employee numbers and designations in your current workspace";
    private final NamedParameterJdbcTemplate jdbc; private final SearchActorScope scopes;
    public EmployeeSearchService(NamedParameterJdbcTemplate jdbc, SearchActorScope scopes) { this.jdbc=jdbc;this.scopes=scopes; }
    @Override public SearchContract.Group search(UUID actor,String query,int page,int size) {
        query=SearchContract.query(query);SearchContract.bounds(page,size);var s=scopes.resolve(actor);
        if(!s.has("EMPLOYEE_READ"))return SearchContract.Group.unavailable("employees","Employees",COVERAGE,page,size);
        var result=SearchSqlPage.read(jdbc,"employees","Employees",COVERAGE,
                "select e.id,e.display_name title,e.employee_number || ' · ' || e.designation subtitle,e.status from employee e where " + filter(s)
                + " and lower(e.display_name || ' ' || e.employee_number || ' ' || e.designation) like :query escape '!'",params(s).addValue("query",SearchContract.literal(query)),page,size);
        scopes.revalidate(actor,s);return result;
    }
    @Override public SearchContract.OpenRecord open(UUID actor,UUID id) {
        var s=scopes.resolve(actor);if(!s.has("EMPLOYEE_READ"))throw SearchContract.notFound();
        var rows=jdbc.query("select e.id,e.display_name,e.employee_number,e.designation,e.status from employee e where "+filter(s)+" and e.id=:id",params(s).addValue("id",id),
                (rs,n)->new SearchContract.OpenRecord("employees",rs.getObject("id",UUID.class),rs.getString("display_name"),rs.getString("employee_number"),rs.getString("status"),"employees",Map.of("Employee number",rs.getString("employee_number"),"Designation",rs.getString("designation"))));
        scopes.revalidate(actor,s);if(rows.size()!=1)throw SearchContract.notFound();return rows.getFirst();
    }
    private String filter(SearchActorScope.Scope s) { return "e.status='ACTIVE'"+(s.departmentId()==null?"":" and e.department_id=:department"); }
    private MapSqlParameterSource params(SearchActorScope.Scope s) {return new MapSqlParameterSource("department",s.departmentId());}
}
