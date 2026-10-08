package com.brainserve.appointment.reception.application;
import com.brainserve.appointment.appointment.api.VisitorBadgePass;
import com.brainserve.appointment.configuration.api.WorkspacePolicy;
import com.brainserve.appointment.iam.api.CurrentAccountAuthority;
import com.brainserve.appointment.audit.api.AuditService;
import com.brainserve.appointment.shared.application.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
@Service
public class VisitorBadgeService {
    private final JdbcTemplate jdbc;private final VisitorBadgePass passes;private final WorkspacePolicy policy;
    private final CurrentAccountAuthority authority;private final AuditService audit;
    public VisitorBadgeService(JdbcTemplate jdbc,VisitorBadgePass passes,WorkspacePolicy policy,CurrentAccountAuthority authority,AuditService audit) {
        this.jdbc=jdbc;this.passes=passes;this.policy=policy;this.authority=authority;this.audit=audit;
    }
    @Transactional public Badge prepare(UUID actor,UUID recordId) {
        var a=authority.requireActive(actor);
        if(!Set.of("ROLE_RECEPTIONIST","ROLE_SECURITY").contains(a.role())||!a.permissions().containsAll(Set.of("QR_PASS_VERIFY","VISITOR_CHECK_IN")))
            throw new BusinessException("BADGE_ACCESS_DENIED","Reception or Security check-in permission is required",HttpStatus.FORBIDDEN);
        var records=jdbc.queryForList("select appointment_id,badge_number from visit_access_record where id=? and checked_out_at is null for update",recordId);
        if(records.size()!=1) throw new BusinessException("BADGE_UNAVAILABLE","Reload the current visitor record",HttpStatus.CONFLICT);
        var pass=passes.badgePass((UUID)records.getFirst().get("appointment_id"));
        audit.record("VISITOR_BADGE_PREPARED","ACCESS_RECORD",recordId.toString(),"{\"templateVersion\":1}");
        return new Badge(records.getFirst().get("badge_number").toString(),pass.referenceNumber(),pass.visitorName(),policy.stringValue("COMPANY.NAME","BrainServe Connect"),pass.expiresAt(),pass.qrCodeDataUrl(),1);
    }
    public record Badge(String badgeNumber,String referenceNumber,String visitorName,String companyName,Instant expiresAt,String qrCodeDataUrl,int templateVersion) {}
}
