package com.brainserve.appointment.appointment.application;

import com.brainserve.appointment.appointment.api.GroupVisitController.Request;
import com.brainserve.appointment.iam.api.CurrentAccountAuthority;
import com.brainserve.appointment.audit.api.AuditService;
import com.brainserve.appointment.shared.application.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

@Service
public class GroupVisitService {
    private final JdbcTemplate jdbc; private final CurrentAccountAuthority authority;
    private final AppointmentService appointments; private final ObjectMapper json; private final AuditService audit;
    public GroupVisitService(JdbcTemplate jdbc,CurrentAccountAuthority authority,AppointmentService appointments,ObjectMapper json,AuditService audit) {
        this.jdbc=jdbc;this.authority=authority;this.appointments=appointments;this.json=json;this.audit=audit;
    }
    @Transactional(readOnly=true) public List<Group> list(UUID actor) {
        require(actor,false);
        return jdbc.query("select * from appointment_visit_group where owner_id=? order by created_at desc,id limit 20",
                (rs,n)->read(rs.getObject("id",UUID.class),rs.getString("label"),rs.getTimestamp("created_at").toInstant()),actor);
    }
    @Transactional public Group create(UUID actor,Request request) {
        require(actor,true);
        if(request.members()==null || request.members().size()<2 || request.members().size()>50)
            throw new BusinessException("INVALID_VISIT_GROUP","A group needs 2–50 visitors",HttpStatus.UNPROCESSABLE_ENTITY);
        Set<String> emails=new HashSet<>();
        for(var member:request.members()) if(member==null || member.visitorEmail()==null || !emails.add(member.visitorEmail().trim().toLowerCase(Locale.ROOT)))
            throw new BusinessException("DUPLICATE_GROUP_VISITOR","Each group member needs a different email",HttpStatus.UNPROCESSABLE_ENTITY);
        String hash;
        try { hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(request))); }
        catch(Exception e) { throw new IllegalStateException("Cannot fingerprint group request",e); }
        jdbc.queryForObject("select pg_advisory_xact_lock(hashtextextended(?,0))",Object.class,actor+":"+request.requestId());
        var prior=jdbc.queryForList("select id,payload_hash from appointment_visit_group where owner_id=? and request_id=?",actor,request.requestId());
        if(!prior.isEmpty()) {
            if(!hash.equals(prior.getFirst().get("payload_hash"))) throw new BusinessException("GROUP_REQUEST_CONFLICT","Retry the original group or use a new request",HttpStatus.CONFLICT);
            UUID id=(UUID)prior.getFirst().get("id");return group(id);
        }
        UUID id=UUID.randomUUID();
        jdbc.update("insert into appointment_visit_group(id,owner_id,request_id,payload_hash,label,host_employee_id,type,slot_start,slot_end,member_count) values(?,?,?,?,?,?,?,?,?,?)",
                id,actor,request.requestId(),hash,request.label().trim(),request.hostEmployeeId(),request.type().name(),Timestamp.from(request.slotStart()),Timestamp.from(request.slotEnd()),request.members().size());
        for(int i=0;i<request.members().size();i++) {
            var m=request.members().get(i);
            appointments.registerGroupMember(id,"group:"+id+":"+i,actor,new AppointmentService.CreateAppointment(request.type(),m.visitorName(),m.visitorEmail(),m.visitorPhone(),m.visitorCompany(),request.hostEmployeeId(),request.routingDepartmentId(),request.requestedEmployeeId(),request.slotStart(),request.slotEnd(),request.purpose()));
        }
        audit.record("VISITOR_GROUP_REGISTERED","VISIT_GROUP",id.toString(),"{\"members\":"+request.members().size()+"}");return group(id);
    }
    private Group group(UUID id) { return jdbc.queryForObject("select * from appointment_visit_group where id=?",(rs,n)->read(id,rs.getString("label"),rs.getTimestamp("created_at").toInstant()),id); }
    private Group read(UUID id,String label,Instant created) {
        var members=jdbc.query("select id,reference_number,status from appointment where visit_group_id=? order by idempotency_key",(rs,n)->new Member(rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3)),id);
        return new Group(id,label,created,members);
    }
    private void require(UUID actor,boolean lock) {
        if(lock) {
            jdbc.queryForList("select id from iam_user_account where id=? for update",actor);
            jdbc.queryForList("select user_id from iam_user_role where user_id=? for update",actor);
            jdbc.queryForList("select user_id from iam_user_permission_deny where user_id=? for update",actor);
        }
        var a=authority.requireActive(actor);
        if(!a.role().equals("ROLE_RECEPTIONIST")||!a.permissions().contains("VISITOR_REGISTER")) throw new BusinessException("GROUP_ACCESS_DENIED","Reception registration permission is required",HttpStatus.FORBIDDEN);
    }
    public record Member(UUID appointmentId,String referenceNumber,String status) {}
    public record Group(UUID id,String label,Instant createdAt,List<Member> members) {}
}
