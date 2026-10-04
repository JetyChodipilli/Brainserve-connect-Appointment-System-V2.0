package com.brainserve.appointment.search.application;

import com.brainserve.appointment.appointment.api.AppointmentSearch;
import com.brainserve.appointment.employee.api.EmployeeSearch;
import com.brainserve.appointment.visitor.api.VisitorSearch;
import com.brainserve.appointment.worktask.api.WorkTaskSearch;
import com.brainserve.appointment.workinsight.api.WorkInsightSearch;
import com.brainserve.appointment.iam.api.SearchActorScope;
import com.brainserve.appointment.shared.api.SearchContract;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.UUID;

@Service
public class UnifiedSearchService {
    private final AppointmentSearch appointments; private final VisitorSearch visitors; private final EmployeeSearch employees;
    private final WorkTaskSearch worksheets; private final WorkInsightSearch oversight; private final SearchActorScope scopes;
    public UnifiedSearchService(AppointmentSearch appointments,VisitorSearch visitors,EmployeeSearch employees,WorkTaskSearch worksheets,WorkInsightSearch oversight,SearchActorScope scopes){
        this.appointments=appointments;this.visitors=visitors;this.employees=employees;this.worksheets=worksheets;this.oversight=oversight;this.scopes=scopes;
    }
    public SearchContract.Response search(UUID actor,String query,int appointmentsPage,int visitorsPage,int employeesPage,int worksheetsPage,int size){
        query=SearchContract.query(query);
        for(int page:new int[]{appointmentsPage,visitorsPage,employeesPage,worksheetsPage})SearchContract.bounds(page,size);
        var before=scopes.resolve(actor);
        var groups=List.of(appointments.search(actor,query,appointmentsPage,size),visitors.search(actor,query,visitorsPage,size),employees.search(actor,query,employeesPage,size),
                before.role().equals("ROLE_CEO")?oversight.search(actor,query,worksheetsPage,size):worksheets.search(actor,query,worksheetsPage,size));
        scopes.revalidate(actor,before);
        return new SearchContract.Response(query,groups);
    }
    public SearchContract.OpenRecord open(UUID actor,String type,UUID id){
        var before=scopes.resolve(actor);
        var result=switch(type){
            case "appointments" -> appointments.open(actor,id);
            case "visitors" -> visitors.open(actor,id);
            case "employees" -> employees.open(actor,id);
            case "worksheets" -> before.role().equals("ROLE_CEO")?oversight.open(actor,id):worksheets.open(actor,id);
            default -> throw SearchContract.notFound();
        };
        scopes.revalidate(actor,before);return result;
    }
}
