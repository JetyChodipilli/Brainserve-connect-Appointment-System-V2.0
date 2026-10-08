package com.brainserve.appointment.integration.slack;

import com.brainserve.appointment.shared.application.SensitiveStringConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Provider revocation remains durable even after the owner loses access. */
@Component
public class SlackRevocationWorker {
    private final JdbcTemplate jdbc;
    private final SlackHttpTransport http;
    private final SensitiveStringConverter secrets;
    private final TransactionTemplate transactions;
    private final boolean enabled;
    public SlackRevocationWorker(JdbcTemplate jdbc,SlackHttpTransport http,SensitiveStringConverter secrets,PlatformTransactionManager manager,@Value("${brainserve.integrations.enabled:true}") boolean enabled) {
        this.jdbc=jdbc;this.http=http;this.secrets=secrets;this.enabled=enabled;
        transactions=new TransactionTemplate(manager); transactions.setTimeout(12);
    }
    @Scheduled(fixedDelayString="${brainserve.integrations.slack.revocation-poll-ms:30000}")
    public void tick() {
        if (!enabled) return;
        for (UUID job:jdbc.query("select id from integration_slack_revocation where status in ('PENDING','RETRYING') and next_attempt_at<=now() order by next_attempt_at,id limit 5",(rs,n)->rs.getObject(1,UUID.class))) {
            try { process(job); } catch (RuntimeException unavailable) { /* Durable job remains due; no provider material in logs. */ }
        }
    }
    public void process(UUID job) {
        transactions.executeWithoutResult(tx->{
            var observed=jdbc.queryForList("select connection_id from integration_slack_revocation where id=?",job); if(observed.isEmpty())return;
            UUID id=(UUID)observed.getFirst().get("connection_id");
            jdbc.query("select a.id from iam_user_account a join integration_connection c on c.owner_id=a.id where c.id=? for update of a",(rs,n)->rs.getObject(1),id);
            jdbc.query("select id from integration_connection where id=? for update",(rs,n)->rs.getObject(1),id);
            var rows=jdbc.queryForList("select * from integration_slack_revocation where id=? for update",job); if(rows.isEmpty())return;
            var row=rows.getFirst();
            if(!List.of("PENDING","RETRYING").contains(row.get("status")) || ((Timestamp)row.get("next_attempt_at")).toInstant().isAfter(Instant.now()))return;
            String workspace=jdbc.queryForObject("select workspace_id from integration_slack_destination where connection_id=?",String.class,id);
            jdbc.queryForObject("select pg_advisory_xact_lock(hashtextextended(?,13070))",Object.class,workspace);
            var fences=jdbc.query("select next_attempt_at from integration_slack_rate_limit where workspace_id=? and channel_id='@revoke'",(rs,n)->rs.getTimestamp(1).toInstant(),workspace);
            if(!fences.isEmpty() && fences.getFirst().isAfter(Instant.now())) {
                jdbc.update("update integration_slack_revocation set next_attempt_at=? where id=?",Timestamp.from(fences.getFirst()),job);return;
            }
            SlackHttpTransport.Reply reply;
            try { reply=http.revoke(secrets.convertToEntityAttribute((String)row.get("token_ciphertext"))); }
            catch(RuntimeException unavailable) { reply=null; }
            int attempts=((Number)row.get("attempts")).intValue()+1;
            boolean complete=reply!=null && (reply.ok() && reply.body().path("revoked").asBoolean(false)
                    || reply.status()>=200 && reply.status()<500 && reply.errorCode()!=null && Set.of("invalid_auth","token_revoked","token_expired").contains(reply.errorCode()));
            String status=complete?"COMPLETE":attempts>=10?"FAILED":"RETRYING";
            String code=complete?"REVOKED_REMOTE":status.equals("FAILED")?"REVOCATION_FAILED":"REVOCATION_RETRYING";
            boolean limited=reply!=null && (reply.status()==429 || "rate_limited".equals(reply.errorCode()));
            long delay=limited?Math.max(1,reply.retryAfterSeconds()):Math.min(3600,30L<<Math.min(attempts,6));
            Instant next=Instant.now().plusSeconds(delay);
            if(limited) jdbc.update("insert into integration_slack_rate_limit(workspace_id,channel_id,next_attempt_at) values(?,'@revoke',?) on conflict(workspace_id,channel_id) do update set next_attempt_at=greatest(integration_slack_rate_limit.next_attempt_at,excluded.next_attempt_at)",workspace,Timestamp.from(next));
            jdbc.update("update integration_slack_revocation set status=?,attempts=?,token_ciphertext=case when ? then '' else token_ciphertext end,last_result_code=?,next_attempt_at=? where id=?",status,attempts,complete,code,Timestamp.from(next),job);
            jdbc.update("update integration_slack_destination set revocation_status=?,last_result_code=? where connection_id=?",status,code,id);
            jdbc.update("update integration_connection set version=version+1,updated_at=now() where id=?",id);
        });
    }
}
