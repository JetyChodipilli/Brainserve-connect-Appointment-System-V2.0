package com.brainserve.appointment.integration.google;

import com.brainserve.appointment.iam.api.CurrentAccountAuthority;
import com.brainserve.appointment.shared.application.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/** Revalidates the current account and the original active refresh family, under owner locks on writes. */
@Component
final class GoogleAccountGuard {
    private final JdbcTemplate jdbc;
    private final CurrentAccountAuthority authority;
    private final long stepUpSeconds;
    GoogleAccountGuard(JdbcTemplate jdbc,CurrentAccountAuthority authority,
                       @Value("${brainserve.security.mfa-step-up-seconds:300}") long stepUpSeconds) {
        this.jdbc=jdbc;this.authority=authority;this.stepUpSeconds=stepUpSeconds;
    }
    void require(Auth auth,boolean write) {
        if(auth==null || auth.actor()==null || auth.session()==null)throw forbidden("SESSION_REVOKED");
        if(write) {
            jdbc.query("select id from iam_user_account where id=? for update",(rs,n)->rs.getObject(1),auth.actor());
            jdbc.query("select role_name from iam_user_role where user_id=? for share",(rs,n)->rs.getString(1),auth.actor());
            jdbc.query("select permission_name from iam_user_permission_deny where user_id=? for share",(rs,n)->rs.getString(1),auth.actor());
        }
        requireOwner(auth.actor());
        var rows=jdbc.query("select mfa_verified_at from iam_refresh_token_session where user_id=? and family_id=? and revoked_at is null and expires_at>now()"+(write?" for share":""),
                (rs,n)-> {Timestamp proof=rs.getTimestamp(1);return proof==null?Instant.EPOCH:proof.toInstant();},auth.actor(),auth.session());
        if(rows.size()!=1)throw forbidden("SESSION_REVOKED");
        if(write) {
            Instant stored=rows.getFirst(), proof=auth.proof();
            if(proof==null || stored.equals(Instant.EPOCH) || proof.isAfter(stored) || proof.isAfter(Instant.now()))throw forbidden("MFA_STEP_UP_REQUIRED");
            Instant effective=proof;
            Long enrolled=jdbc.queryForObject("select count(*) from iam_mfa_credential where user_id=? and enrolled_at is not null",Long.class,auth.actor());
            if(enrolled==null || enrolled!=1 || !effective.plusSeconds(stepUpSeconds).isAfter(Instant.now()))throw forbidden("MFA_STEP_UP_REQUIRED");
        }
    }
    void requireOwner(UUID owner) {
        var current=authority.requireActive(owner);
        if(!current.role().equals("ROLE_SYSTEM_ADMIN") || !current.permissions().contains("SYSTEM_CONFIGURE"))throw forbidden("INTEGRATION_ADMIN_REQUIRED");
    }
    static BusinessException forbidden(String code) { return new BusinessException(code,"An active System Admin session and current verification are required",HttpStatus.FORBIDDEN); }
    record Auth(UUID actor,UUID session,Instant proof) {}
}
