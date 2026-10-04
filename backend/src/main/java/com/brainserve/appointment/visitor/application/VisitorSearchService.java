package com.brainserve.appointment.visitor.application;
import com.brainserve.appointment.iam.api.SearchActorScope;

import com.brainserve.appointment.visitor.api.VisitorSearch;
import com.brainserve.appointment.shared.api.*;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.stereotype.Service;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class VisitorSearchService implements VisitorSearch {
    private static final String COVERAGE="Visitor registry names and companies. Identity documents, contact details and appointment guests are excluded";
    private final NamedParameterJdbcTemplate jdbc;private final SearchActorScope scopes;
    public VisitorSearchService(NamedParameterJdbcTemplate jdbc,SearchActorScope scopes){this.jdbc=jdbc;this.scopes=scopes;}
    @Override public SearchContract.Group search(UUID actor,String query,int page,int size){
        query=SearchContract.query(query);SearchContract.bounds(page,size);var s=scopes.resolve(actor);
        if(!allowed(s))return SearchContract.Group.unavailable("visitors","Visitors",COVERAGE,page,size);
        // Only government_id_encrypted is encrypted in this domain. Never decrypt or search that field.
        var result=SearchSqlPage.read(jdbc,"visitors","Visitors",COVERAGE,"select v.id,v.name title,coalesce(v.company,'Visitor registry') subtitle,case when v.identity_verified then 'VERIFIED' else 'UNVERIFIED' end status from visitor v where not v.restricted and lower(v.name || ' ' || coalesce(v.company,'')) like :query escape '!'",new MapSqlParameterSource("query",SearchContract.literal(query)),page,size);
        scopes.revalidate(actor,s);return result;
    }
    @Override public SearchContract.OpenRecord open(UUID actor,UUID id){
        var s=scopes.resolve(actor);if(!allowed(s))throw SearchContract.notFound();
        var rows=jdbc.query("select v.id,v.name,coalesce(v.company,'') company,case when v.identity_verified then 'VERIFIED' else 'UNVERIFIED' end status from visitor v where not v.restricted and v.id=:id",new MapSqlParameterSource("id",id),(rs,n)->new SearchContract.OpenRecord("visitors",rs.getObject("id",UUID.class),rs.getString("name"),rs.getString("company"),rs.getString("status"),"visitors",Map.of("Company",rs.getString("company"),"Source","Visitor registry")));
        scopes.revalidate(actor,s);if(rows.size()!=1)throw SearchContract.notFound();return rows.getFirst();
    }
    private boolean allowed(SearchActorScope.Scope s){
        // The registry has no department relationship. A department role's VISITOR_VERIFY grant must not widen its scope.
        return s.has("VISITOR_VERIFY") && Set.of("ROLE_RECEPTIONIST","ROLE_SECURITY").contains(s.role());
    }
}
