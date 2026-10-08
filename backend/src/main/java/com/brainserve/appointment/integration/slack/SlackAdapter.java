package com.brainserve.appointment.integration.slack;

import com.brainserve.appointment.shared.application.BusinessException;
import com.brainserve.appointment.shared.application.SensitiveStringConverter;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Minimal Slack adapter; no source business transaction performs network work. */
@Component
public class SlackAdapter {
    public record Identity(String workspaceId, String botId, String fingerprint) {}
    public record Metadata(String workspaceId, String channelId, String botId, String revocationStatus, String lastResultCode) {}
    public record Result(String code, Instant nextAttemptAt, String externalId) {}
    private static final Set<String> INVALID_TOKENS = Set.of("invalid_auth","token_revoked","token_expired");
    private final JdbcTemplate jdbc;
    private final SlackHttpTransport http;
    private final SlackConfiguration config;
    private final SensitiveStringConverter secrets;
    public SlackAdapter(JdbcTemplate jdbc, SlackHttpTransport http, SlackConfiguration config, SensitiveStringConverter secrets) {
        this.jdbc=jdbc; this.http=http; this.config=config; this.secrets=secrets;
    }
    public boolean configured() { return config.configured(); }
    public Identity authenticate(String token) {
        if (!configured()) throw failure("SLACK_NOT_CONFIGURED", "Slack is not configured for this deployment");
        if (token == null || !token.matches("xoxb-[A-Za-z0-9-]{12,250}")) throw failure("SLACK_INVALID_TOKEN", "Supply a dedicated nonrotating Slack bot token");
        var r = http.auth(token);
        if (!r.ok() || !r.scopes().equals(Set.of("chat:write"))) throw failure("SLACK_AUTH_REJECTED", "Slack must verify a bot with only chat:write permission");
        String team=r.body().path("team_id").asText(), bot=r.body().path("bot_id").asText();
        if (!team.matches("T[A-Z0-9]{8,31}") || !bot.matches("B[A-Z0-9]{8,31}")) throw failure("SLACK_AUTH_REJECTED", "Slack did not verify a workspace bot");
        return new Identity(team,bot,fingerprint(token));
    }
    public static boolean channel(String value) { return value != null && value.matches("[CG][A-Z0-9]{8,31}"); }
    public void install(UUID id, Identity identity, String channel) {
        jdbc.queryForObject("select pg_advisory_xact_lock(hashtextextended(?,13069))", Object.class, identity.workspaceId()+":"+identity.botId());
        if (!jdbc.queryForList("select connection_id from integration_slack_destination where workspace_id=? and bot_id=? or credential_fingerprint=?",identity.workspaceId(),identity.botId(),identity.fingerprint()).isEmpty())
            throw failure("SLACK_DEDICATED_BOT_REQUIRED", "This bot is already retained by another connection; use its renewal controls");
        jdbc.update("insert into integration_slack_destination(connection_id,workspace_id,channel_id,bot_id,credential_fingerprint) values(?,?,?,?,?)",id,identity.workspaceId(),channel,identity.botId(),identity.fingerprint());
    }
    public Metadata metadata(UUID id) {
        return jdbc.queryForObject("select * from integration_slack_destination where connection_id=?", (rs,n)->new Metadata(rs.getString("workspace_id"),rs.getString("channel_id"),rs.getString("bot_id"),rs.getString("revocation_status"),rs.getString("last_result_code")), id);
    }
    public void renew(UUID id, String oldToken, String newToken) {
        var current=metadata(id);
        if (List.of("PENDING","RETRYING","FAILED").contains(current.revocationStatus())) throw failure("SLACK_REVOCATION_PENDING", "Finish remote revocation before renewing this connection");
        if (oldToken != null) {
            var old=http.auth(oldToken);
            if (old.errorCode()==null || !INVALID_TOKENS.contains(old.errorCode())) throw failure("SLACK_ROTATION_REQUIRED", "Invalidate the old token in Slack before supplying its replacement");
        } else if (!current.revocationStatus().equals("COMPLETE")) throw failure("SLACK_ROTATION_REQUIRED", "Verify remote revocation before renewing this connection");
        Identity identity=authenticate(newToken);
        if (!current.workspaceId().equals(identity.workspaceId()) || !current.botId().equals(identity.botId())) throw failure("SLACK_DESTINATION_CHANGED", "Renew with a token for the same workspace and bot");
        jdbc.update("update integration_slack_destination set credential_fingerprint=?,revocation_status='NONE',last_result_code='CREDENTIAL_RENEWED' where connection_id=?",identity.fingerprint(),id);
    }
    public void disconnect(UUID id, long generation) {
        String encrypted=jdbc.queryForObject("select credential_ciphertext from integration_connection where id=?",String.class,id);
        if (encrypted != null) {
            jdbc.update("insert into integration_slack_revocation(id,connection_id,credential_version,token_ciphertext) values(?,?,?,?) on conflict(connection_id,credential_version) do nothing",UUID.randomUUID(),id,generation,encrypted);
            jdbc.update("update integration_slack_destination set revocation_status='PENDING',last_result_code='REVOCATION_PENDING' where connection_id=?",id);
        }
    }
    public void retryRevocation(UUID id) {
        int changed=jdbc.update("update integration_slack_revocation set status='PENDING',attempts=0,manual_retries=manual_retries+1,next_attempt_at=now(),last_result_code='REVOCATION_PENDING' where connection_id=? and status='FAILED' and manual_retries<3",id);
        if (changed==0) throw failure("SLACK_REVOCATION_RETRY_LIMIT", "Reload revocation status; at most three manual retries are allowed");
        jdbc.update("update integration_slack_destination set revocation_status='PENDING',last_result_code='REVOCATION_PENDING' where connection_id=?",id);
    }
    /** A workspace lock serializes postMessage across tokens and channels; network work is <=5 seconds. */
    public Result deliver(UUID id, boolean test, Instant now) {
        if (!configured()) return new Result("NOT_CONFIGURED",now,null);
        var target=metadata(id);
        jdbc.queryForObject("select pg_advisory_xact_lock(hashtextextended(?,13070))",Object.class,target.workspaceId());
        Instant started=Instant.now().isAfter(now)?Instant.now():now;
        List<Timestamp> fences=jdbc.query("select next_attempt_at from integration_slack_rate_limit where workspace_id=? and channel_id in ('',?)",(rs,n)->rs.getTimestamp(1),target.workspaceId(),target.channelId());
        Instant fence=fences.stream().map(Timestamp::toInstant).max(Instant::compareTo).orElse(started);
        if (fence.isAfter(started)) return new Result("RATE_WAIT",fence,null);
        pace(target.workspaceId(),target.channelId(),started.plusSeconds(1));
        String token;
        try { token=secrets.convertToEntityAttribute(jdbc.queryForObject("select credential_ciphertext from integration_connection where id=?",String.class,id)); }
        catch (RuntimeException invalid) { return new Result("REAUTH_REQUIRED",started,null); }
        var r=http.post(token,target.channelId(),config.arrivalLink(),test);
        Instant finished=Instant.now().isAfter(now)?Instant.now():now;
        pace(target.workspaceId(),target.channelId(),finished.plusSeconds(1));
        if (r.status()==429 || "rate_limited".equals(r.errorCode())) {
            Instant until=finished.plusSeconds(Math.max(1,r.retryAfterSeconds()));
            pace(target.workspaceId(),"",until);
            return new Result("RATE_LIMITED",until,null);
        }
        if (r.ok()) {
            String channel=r.body().path("channel").asText(), ts=r.body().path("ts").asText();
            if (target.channelId().equals(channel) && ts.matches("[0-9]{1,20}\\.[0-9]{1,20}")) return new Result("SUCCESS",finished,channel+":"+ts);
            return new Result("DELIVERY_UNKNOWN",finished,null);
        }
        if (r.errorCode()!=null && (INVALID_TOKENS.contains(r.errorCode()) || r.errorCode().equals("account_inactive"))) return new Result("REAUTH_REQUIRED",finished,null);
        if (r.errorCode()==null || Set.of("TRANSPORT_UNKNOWN","internal_error","fatal_error","service_unavailable").contains(r.errorCode()) || r.status()>=500)
            return new Result("DELIVERY_UNKNOWN",finished,null);
        return new Result("PERMANENT_FAILURE",finished,null);
    }
    private void pace(String workspace, String channel, Instant until) {
        jdbc.update("insert into integration_slack_rate_limit(workspace_id,channel_id,next_attempt_at) values(?,?,?) on conflict(workspace_id,channel_id) do update set next_attempt_at=greatest(integration_slack_rate_limit.next_attempt_at,excluded.next_attempt_at)",workspace,channel,Timestamp.from(until));
    }
    private static String fingerprint(String token) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException unavailable) { throw new IllegalStateException("Credential fingerprint unavailable"); }
    }
    private static BusinessException failure(String code,String text) { return new BusinessException(code,text,HttpStatus.CONFLICT); }
}
