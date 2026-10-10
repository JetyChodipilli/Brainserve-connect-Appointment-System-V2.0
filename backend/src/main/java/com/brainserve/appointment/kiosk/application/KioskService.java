package com.brainserve.appointment.kiosk.application;

import com.brainserve.appointment.appointment.api.VisitorPassVerification;
import com.brainserve.appointment.iam.api.CurrentAccountAuthority;
import com.brainserve.appointment.audit.api.AuditService;
import com.brainserve.appointment.shared.application.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.Duration;
import java.util.*;

@Service
public class KioskService {
    private final JdbcTemplate jdbc; private final CurrentAccountAuthority authority;
    private final VisitorPassVerification passes; private final AuditService audit;
    private final StringRedisTemplate redis; private final boolean enabled;
    private static final DefaultRedisScript<Long> BUDGET=new DefaultRedisScript<>("local n=redis.call('INCR',KEYS[1]); if n==1 then redis.call('EXPIRE',KEYS[1],60) end; return n",Long.class);
    public KioskService(JdbcTemplate jdbc,CurrentAccountAuthority authority,VisitorPassVerification passes,AuditService audit,
                        StringRedisTemplate redis,@Value("${brainserve.kiosk.enabled:false}") boolean enabled) {
        this.jdbc=jdbc;this.authority=authority;this.passes=passes;this.audit=audit;this.redis=redis;this.enabled=enabled;
    }
    @Transactional public Provisioned provision(UUID actor,String label) {
        requireAdmin(actor,true);
        if(jdbc.queryForObject("select count(*) from kiosk_device where owner_id=? and revoked_at is null and expires_at>now()",Long.class,actor)>=20)
            throw new BusinessException("KIOSK_DEVICE_LIMIT","Revoke an active device before creating another",HttpStatus.CONFLICT);
        byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);
        String token=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        UUID id=UUID.randomUUID();Instant expiry=Instant.now().plus(Duration.ofHours(8));
        jdbc.update("insert into kiosk_device(id,owner_id,label,token_hash,expires_at) values(?,?,?,?,?)",id,actor,label.trim(),hash(token),Timestamp.from(expiry));
        audit.record("KIOSK_DEVICE_PROVISIONED","KIOSK_DEVICE",id.toString(),"{}");
        return new Provisioned(id,label.trim(),expiry,0,token);
    }
    @Transactional(readOnly=true) public List<Device> devices(UUID actor) {
        requireAdmin(actor,false);
        return jdbc.query("select * from kiosk_device where owner_id=? order by created_at desc,id limit 50",(rs,n)->new Device(rs.getObject("id",UUID.class),rs.getString("label"),rs.getTimestamp("expires_at").toInstant(),rs.getTimestamp("revoked_at")==null?null:rs.getTimestamp("revoked_at").toInstant(),rs.getLong("version")),actor);
    }
    @Transactional(readOnly=true) public boolean configured(UUID actor) {
        requireAdmin(actor,false); return enabled;
    }
    @Transactional public void revoke(UUID actor,UUID id,long version) {
        requireAdmin(actor,true);
        if(jdbc.update("update kiosk_device set revoked_at=now(),version=version+1 where id=? and owner_id=? and version=? and revoked_at is null",id,actor,version)!=1)
            throw new BusinessException("KIOSK_DEVICE_CHANGED","Reload the current device list",HttpStatus.CONFLICT);
        audit.record("KIOSK_DEVICE_REVOKED","KIOSK_DEVICE",id.toString(),"{}");
    }
    @Transactional public Session session(String token) {
        Device device=requireDevice(token); return new Session(device.label(),device.expiresAt(),60);
    }
    @Transactional public Accepted intake(String token,String pass) {
        Device device=requireDevice(token);
        VisitorPassVerification.VerifiedPass verified;
        try { verified=passes.verifyForArrival(pass); }
        catch(BusinessException e) { throw new BusinessException("KIOSK_ARRIVAL_UNCONFIRMED","Ask Reception or Security to check this pass",HttpStatus.UNPROCESSABLE_ENTITY); }
        if(!verified.appointmentStatus().equals("APPROVED")) throw new BusinessException("KIOSK_ARRIVAL_UNCONFIRMED","Ask Reception or Security to check this pass",HttpStatus.UNPROCESSABLE_ENTITY);
        UUID id=UUID.randomUUID();
        if(jdbc.update("insert into kiosk_arrival_intake(id,device_id,appointment_id) values(?,?,?) on conflict(appointment_id) do nothing",id,device.id(),verified.appointmentId())==1)
            audit.record("KIOSK_ARRIVAL_RECEIVED","KIOSK_INTAKE",id.toString(),"{}");
        return new Accepted(true);
    }
    @Transactional(readOnly=true) public List<Intake> pending(UUID actor) {
        requireStaff(actor,false);
        return jdbc.query("select id,appointment_id,received_at,version from kiosk_arrival_intake where resolved_at is null order by received_at,id limit 50",(rs,n)->new Intake(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getTimestamp(3).toInstant(),rs.getLong(4)));
    }
    @Transactional public void resolve(UUID actor,UUID id,long version) {
        requireStaff(actor,true);
        if(jdbc.update("update kiosk_arrival_intake set resolved_at=now(),resolved_by=?,version=version+1 where id=? and version=? and resolved_at is null",actor,id,version)!=1)
            throw new BusinessException("KIOSK_INTAKE_CHANGED","Reload the arrival requests",HttpStatus.CONFLICT);
        audit.record("KIOSK_ARRIVAL_RESOLVED","KIOSK_INTAKE",id.toString(),"{}");
    }
    private Device requireDevice(String token) {
        if(!enabled) throw new BusinessException("KIOSK_DISABLED","Kiosk intake is unavailable; ask Reception",HttpStatus.SERVICE_UNAVAILABLE);
        if(token==null||!token.matches("[A-Za-z0-9_-]{43}")) throw unavailable();
        var rows=jdbc.queryForList("select owner_id from kiosk_device where token_hash=?",hash(token));
        if(rows.size()!=1) throw unavailable();
        UUID owner=(UUID)rows.getFirst().get("owner_id");
        try {requireAdmin(owner,true);} catch(BusinessException e) {throw unavailable();}
        var devices=jdbc.query("select * from kiosk_device where token_hash=? and revoked_at is null and expires_at>now() for update",(rs,n)->new Device(rs.getObject("id",UUID.class),rs.getString("label"),rs.getTimestamp("expires_at").toInstant(),null,rs.getLong("version")),hash(token));
        if(devices.size()!=1) throw unavailable();
        Device device=devices.getFirst();Long count;
        try { count=redis.execute(BUDGET,List.of("kiosk:budget:"+device.id())); }
        catch(RuntimeException e) { throw new BusinessException("KIOSK_UNAVAILABLE","Kiosk intake is unavailable; ask Reception",HttpStatus.SERVICE_UNAVAILABLE); }
        if(count==null) throw new BusinessException("KIOSK_UNAVAILABLE","Kiosk intake is unavailable; ask Reception",HttpStatus.SERVICE_UNAVAILABLE);
        if(count>60) throw new BusinessException("KIOSK_RATE_LIMIT","Wait a minute or ask Reception",HttpStatus.TOO_MANY_REQUESTS);
        return device;
    }
    private CurrentAccountAuthority.Authority current(UUID actor,boolean lock) {
        if(lock) {
            jdbc.queryForList("select id from iam_user_account where id=? for update",actor);
            jdbc.queryForList("select user_id from iam_user_role where user_id=? for update",actor);
            jdbc.queryForList("select user_id from iam_user_permission_deny where user_id=? for update",actor);
        }
        return authority.requireActive(actor);
    }
    private void requireAdmin(UUID actor,boolean lock) {
        var a=current(actor,lock);
        if(!a.role().equals("ROLE_SYSTEM_ADMIN")||!a.permissions().contains("SYSTEM_CONFIGURE")) throw new BusinessException("KIOSK_ACCESS_DENIED","System administrator configuration permission is required",HttpStatus.FORBIDDEN);
    }
    private void requireStaff(UUID actor,boolean lock) {
        var a=current(actor,lock);
        if(!Set.of("ROLE_RECEPTIONIST","ROLE_SECURITY").contains(a.role())||!a.permissions().contains("QR_PASS_VERIFY")) throw new BusinessException("KIOSK_ACCESS_DENIED","Reception or Security pass verification permission is required",HttpStatus.FORBIDDEN);
    }
    private BusinessException unavailable() { return new BusinessException("KIOSK_SESSION_UNAVAILABLE","Reconnect this visitor device or ask Reception",HttpStatus.UNAUTHORIZED); }
    private String hash(String value) {
        try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException e) {throw new IllegalStateException(e);}
    }
    public record Provisioned(UUID id,String label,Instant expiresAt,long version,String token) {}
    public record Device(UUID id,String label,Instant expiresAt,Instant revokedAt,long version) {}
    public record Session(String label,Instant expiresAt,int resetSeconds) {}
    public record Accepted(boolean accepted) {}
    public record Intake(UUID id,UUID appointmentId,Instant receivedAt,long version) {}
}
