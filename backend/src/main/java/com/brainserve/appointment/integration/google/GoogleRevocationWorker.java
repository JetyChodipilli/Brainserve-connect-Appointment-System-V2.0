package com.brainserve.appointment.integration.google;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/** Local disconnect is immediate; remote revocation survives provider outages and process restarts. */
@Component
public class GoogleRevocationWorker {
    private final JdbcTemplate jdbc;
    private final GoogleHttpTransport http;
    private final GoogleTokenCodec codec;
    private final TransactionTemplate transactions;
    private final boolean enabled;
    public GoogleRevocationWorker(JdbcTemplate jdbc,GoogleHttpTransport http,GoogleTokenCodec codec,PlatformTransactionManager manager,
                                 @Value("${brainserve.integrations.enabled:true}") boolean enabled) {
        this.jdbc=jdbc;this.http=http;this.codec=codec;this.enabled=enabled;transactions=new TransactionTemplate(manager);transactions.setTimeout(12);
    }
    @Scheduled(fixedDelayString="${brainserve.integrations.google-calendar.revocation-poll-ms:30000}")
    public void tick() {
        if(!enabled)return;
        for(UUID id:jdbc.query("select id from integration_google_revocation where status in ('PENDING','RETRYING') and next_attempt_at<=now() order by next_attempt_at,id limit 5",(rs,n)->rs.getObject(1,UUID.class))) {
            try { process(id); }catch(RuntimeException unavailable) { /* The encrypted durable job remains due; never log provider material. */ }
        }
        transactions.executeWithoutResult(tx->{
            jdbc.update("update integration_google_consent set status=case when status='EXCHANGING' then 'EXCHANGE_UNKNOWN' else 'EXPIRED' end,last_result_code=case when status='EXCHANGING' then 'EXCHANGE_UNKNOWN' else 'CONSENT_EXPIRED' end,ticket_hash=null,state_hash=null,ticket_ciphertext=null,state_ciphertext=null,verifier_ciphertext=null,browser_hash=null,code_ciphertext=null where expires_at<now() and status in ('INITIATED','AUTHORIZED','CALLBACK_RECEIVED','EXCHANGING')");
            jdbc.update("delete from integration_google_consent where created_at<now()-interval '90 days' and status not in ('INITIATED','AUTHORIZED','CALLBACK_RECEIVED','EXCHANGING')");
        });
    }
    public void process(UUID job) {
        transactions.executeWithoutResult(tx->{
            var lookup=jdbc.queryForList("select connection_id from integration_google_revocation where id=?",job);if(lookup.size()!=1)return;
            UUID id=(UUID)lookup.getFirst().get("connection_id");
            // Account precedes connection, matching every integration worker and administrative write.
            jdbc.query("select a.id from iam_user_account a join integration_connection c on c.owner_id=a.id where c.id=? for update of a",(rs,n)->rs.getObject(1),id);
            jdbc.query("select id from integration_connection where id=? for update",(rs,n)->rs.getObject(1),id);
            var rows=jdbc.queryForList("select * from integration_google_revocation where id=? for update",job);if(rows.size()!=1)return;var row=rows.getFirst();
            if(!java.util.List.of("PENDING","RETRYING").contains(row.get("status")) || ((Timestamp)row.get("next_attempt_at")).toInstant().isAfter(Instant.now()))return;
            String token;
            try {token=codec.decrypt((String)row.get("token_ciphertext"));}catch(RuntimeException corrupt){token=null;}
            var reply=GoogleTokenCodec.token(token)?http.revoke(token):new GoogleHttpTransport.Reply(-1,null,null,0);
            int attempts=((Number)row.get("attempts")).intValue()+1;
            // Only an explicit safe invalid_token response proves the remote token is unusable.
            // An arbitrary 400 may describe a bad request and must retain the recovery credential.
            boolean complete=reply.success() || reply.status()==400&&"invalid_token".equals(reply.errorCode());
            String status=complete?"COMPLETE":attempts>=10||reply.status()==-1||reply.status()>=300&&reply.status()<500&&reply.status()!=429?"FAILED":"RETRYING";
            String result=complete?"REVOKED_REMOTE":status.equals("FAILED")?"REVOCATION_FAILED":"REVOCATION_RETRYING";
            jdbc.update("update integration_google_revocation set status=?,attempts=?,token_ciphertext=case when ? then '' else token_ciphertext end,last_result_code=?,next_attempt_at=? where id=?",
                    status,attempts,complete,result,Timestamp.from(Instant.now().plusSeconds(reply.status()==429?reply.retryAfterSeconds():Math.min(3600,30L<<Math.min(attempts,6)))),job);
            String aggregate=jdbc.queryForObject("select case when bool_or(status='FAILED') then 'FAILED' when bool_or(status in ('PENDING','RETRYING')) then 'RETRYING' else 'COMPLETE' end from integration_google_revocation where connection_id=?",String.class,id);
            jdbc.update("update integration_google_calendar set revocation_status=?,last_result_code=? where connection_id=?",aggregate,result,id);
        });
    }
}
