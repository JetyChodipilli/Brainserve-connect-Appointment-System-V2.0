package com.brainserve.appointment.appointment.application;

import com.brainserve.appointment.appointment.api.AppointmentSearch;
import com.brainserve.appointment.iam.api.SearchActorScope;
import com.brainserve.appointment.shared.api.SearchContract;
import com.brainserve.appointment.shared.api.SearchSqlPage;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class AppointmentSearchService implements AppointmentSearch {
    private static final String COVERAGE = "Appointment references, visitor names, companies and purposes in your current workspace";
    private final NamedParameterJdbcTemplate jdbc;
    private final SearchActorScope scopes;
    public AppointmentSearchService(NamedParameterJdbcTemplate jdbc, SearchActorScope scopes) { this.jdbc=jdbc; this.scopes=scopes; }
    @Override public SearchContract.Group search(UUID actor, String query, int page, int size) {
        query=SearchContract.query(query); SearchContract.bounds(page,size); var scope=scopes.resolve(actor);
        if (!allowed(scope)) return SearchContract.Group.unavailable("appointments","Appointments",COVERAGE,page,size);
        var result=SearchSqlPage.read(jdbc,"appointments","Appointments",COVERAGE,
                "select a.id,a.visitor_name title,a.reference_number subtitle,a.status from appointment a where " + filter(scope)
                + " and lower(a.reference_number || ' ' || a.visitor_name || ' ' || coalesce(a.visitor_company,'') || ' ' || a.purpose) like :query escape '!'",
                parameters(scope).addValue("query",SearchContract.literal(query)),page,size);
        scopes.revalidate(actor,scope); return result;
    }
    @Override public Record visible(UUID actor, UUID id) {
        var scope=scopes.resolve(actor); if (!allowed(scope)) throw SearchContract.notFound();
        var records=jdbc.query("select a.* from appointment a where " + filter(scope) + " and a.id=:id",parameters(scope).addValue("id",id),
                (rs,n)->new Record(rs.getObject("id",UUID.class),rs.getString("reference_number"),rs.getString("visitor_name"),rs.getString("status"),rs.getString("type"),rs.getString("purpose"),rs.getTimestamp("slot_start").toInstant(),rs.getTimestamp("slot_end").toInstant(),rs.getObject("routing_department_id",UUID.class),rs.getObject("host_employee_id",UUID.class)));
        scopes.revalidate(actor,scope); if(records.size()!=1) throw SearchContract.notFound(); return records.getFirst();
    }
    public Record requireVisible(UUID actor, UUID id) { return visible(actor,id); }
    @Override public SearchContract.OpenRecord open(UUID actor, UUID id) {
        var r=visible(actor,id);
        return new SearchContract.OpenRecord("appointments",r.id(),r.visitorName(),r.referenceNumber(),r.status(),"appointments",
                Map.of("Reference",r.referenceNumber(),"Visit type",r.type(),"Purpose",r.purpose(),"Starts",r.slotStart().toString(),"Ends",r.slotEnd().toString()));
    }
    private boolean allowed(SearchActorScope.Scope s) {
        return switch(s.role()) {
            case "ROLE_HR_ADMIN" -> s.has("HR_VISIT_APPROVE");
            case "ROLE_TEAM_LEAD" -> s.has("TEAM_LEAD_VISIT_APPROVE");
            case "ROLE_MANAGER" -> s.has("MANAGER_VISIT_APPROVE");
            case "ROLE_EMPLOYEE" -> s.has("APPOINTMENT_APPROVE");
            case "ROLE_CEO" -> s.has("CEO_VISIT_APPROVE");
            case "ROLE_RECEPTIONIST" -> s.has("RECEPTION_VISIT_VERIFY") || s.has("VISITOR_REGISTER");
            case "ROLE_SECURITY" -> s.has("SECURITY_VISITOR_INTAKE");
            default -> false;
        };
    }
    private String filter(SearchActorScope.Scope s) {
        if(s.role().equals("ROLE_EMPLOYEE")) return "a.routing_department_id=:department and ((a.type='EMPLOYEE_VISIT' and a.requested_employee_id=:employee and a.hr_approval_actor_id is not null and a.status in ('PENDING_TEAM_LEAD_APPROVAL','APPROVED','REJECTED','CHECKED_IN','IN_MEETING','CHECKED_OUT','COMPLETED','CANCELLED','EXPIRED')) or (a.type<>'EMPLOYEE_VISIT' and a.host_employee_id=:employee and a.status in ('PENDING_APPROVAL','APPROVED','RESCHEDULED','CHECKED_IN','IN_MEETING','CHECKED_OUT','COMPLETED','REJECTED','CANCELLED','EXPIRED')))";
        if(s.role().equals("ROLE_HR_ADMIN")) return "a.routing_department_id=:department and (a.type not in ('HR_VISIT','INTERVIEW') or a.status<>'PENDING_HR_APPROVAL' or a.host_employee_id=:employee)";
        if(s.departmentId()!=null) return "a.routing_department_id=:department";
        return "true";
    }
    private MapSqlParameterSource parameters(SearchActorScope.Scope s) { return new MapSqlParameterSource("employee",s.employeeId()).addValue("department",s.departmentId()); }
}
