package com.brainserve.appointment.integration.google;

import com.brainserve.appointment.audit.api.AuditService;
import com.brainserve.appointment.integration.api.IntegrationModels;
import com.brainserve.appointment.shared.application.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class GoogleCalendarConsentService {
    private static final SecureRandom RANDOM=new SecureRandom();
    private final JdbcTemplate jdbc;
    private final GoogleAccountGuard guard;
    private final GoogleTokenCodec codec;
    private final GoogleHttpTransport http;
    private final GoogleCalendarConfiguration config;
    private final GoogleCalendarAdapter adapter;
    private final AuditService audit;
    private final TransactionTemplate transactions;
    public GoogleCalendarConsentService(JdbcTemplate jdbc,GoogleAccountGuard guard,GoogleTokenCodec codec,GoogleHttpTransport http,
                                       GoogleCalendarConfiguration config,GoogleCalendarAdapter adapter,AuditService audit,PlatformTransactionManager manager) {
        this.jdbc=jdbc;this.guard=guard;this.codec=codec;this.http=http;this.config=config;this.adapter=adapter;this.audit=audit;
        transactions=new TransactionTemplate(manager);transactions.setTimeout(35);
    }
    public record Start(UUID requestId,String label,UUID connectionId,Long expectedVersion) {}
    public record Recover(Long expectedVersion,String calendarId) { @Override public String toString() {return "Recover[calendarId=REDACTED]";} }
    public record Consent(UUID id,UUID connectionId,String status,String authorizationUrl,Instant expiresAt,String lastResultCode) {
        @Override public String toString() {return "Consent[id="+id+",status="+status+"]";}
    }
    public record Metadata(String provisioningStatus,String revocationStatus,String lastResultCode) {}
    record Authorization(String url,String browserToken) { @Override public String toString(){return "Authorization[REDACTED]";} }
    public Consent start(GoogleAccountGuard.Auth auth,Start command) {
        if(!config.configured())throw problem("GOOGLE_NOT_CONFIGURED",HttpStatus.CONFLICT);
        if(command==null || command.requestId()==null || command.label()==null || command.label().isBlank()
                || command.label().strip().length()>80 || command.label().codePoints().anyMatch(Character::isISOControl)
                || (command.connectionId()==null)!=(command.expectedVersion()==null))throw invalid();
        return transactions.execute(tx->{
            guard.require(auth,true);
            jdbc.queryForObject("select pg_advisory_xact_lock(hashtextextended(?,11065))",Object.class,command.requestId().toString());
            var retained=jdbc.queryForList("select * from integration_google_consent where request_id=?",command.requestId());
            if(!retained.isEmpty()) {
                var row=retained.getFirst();
                if(!auth.actor().equals(row.get("owner_id")) || !auth.session().equals(row.get("session_id"))
                        || !command.label().strip().equals(row.get("label")) || (command.connectionId()!=null)!=(Boolean)row.get("is_reconsent") || (command.connectionId()!=null && (!command.connectionId().equals(row.get("connection_id"))
                        || command.expectedVersion()!=((Number)row.get("observed_version")).longValue())))throw conflict();
                return consent(row,true);
            }
            UUID id=command.connectionId();long version;
            if(id==null) {
                if(count("select count(*) from integration_connection where request_id=?",command.requestId())>0)throw conflict();
                jdbc.queryForObject("select pg_advisory_xact_lock(1106511000)",Object.class);
                if(count("select count(*) from integration_connection where owner_id=?",auth.actor())>=100 || count("select count(*) from integration_connection")>=1000)throw problem("INTEGRATION_CONNECTION_LIMIT",HttpStatus.CONFLICT);
                id=UUID.randomUUID();version=0;
                jdbc.update("""
                        insert into integration_connection(id,request_id,provider,kind,label,owner_id,minimum_scopes,status,credential_expires_at,last_result_code)
                        values(?,?,'GOOGLE_CALENDAR','CALENDAR',?,?,'["https://www.googleapis.com/auth/calendar.app.created"]'::jsonb,'NEEDS_RECONNECT',?,'CONSENT_REQUIRED')
                        """,id,command.requestId(),command.label().strip(),auth.actor(),time(Instant.now().plusSeconds(90*86400L)));
                jdbc.update("insert into integration_google_calendar(connection_id) values(?)",id);
            } else {
                var existing=owned(auth.actor(),id,true);
                version=((Number)existing.get("version")).longValue();expected(command.expectedVersion(),version);
                if(!command.label().strip().equals(existing.get("label")))throw invalid();
                if(count("select count(*) from integration_google_revocation where connection_id=? and status<>'COMPLETE'",id)>0)throw problem("REVOCATION_PENDING",HttpStatus.CONFLICT);
            }
            if(count("select count(*) from integration_google_consent where owner_id=? and created_at>now()-interval '1 day'",auth.actor())>=100)throw problem("CONSENT_LIMIT",HttpStatus.CONFLICT);
            cancelPending(id,"CONSENT_REPLACED");
            String ticket=random(),state=random(),verifier=random();UUID consentId=UUID.randomUUID();
            jdbc.update("""
                    insert into integration_google_consent(id,request_id,connection_id,owner_id,session_id,observed_version,is_reconsent,label,ticket_hash,state_hash,
                    ticket_ciphertext,state_ciphertext,verifier_ciphertext,status,expires_at) values(?,?,?,?,?,?,?,?,?,?,?,?,?,'INITIATED',?)
                    """,consentId,command.requestId(),id,auth.actor(),auth.session(),version,command.connectionId()!=null,command.label().strip(),hash(ticket),hash(state),codec.encrypt(ticket),codec.encrypt(state),codec.encrypt(verifier),time(Instant.now().plusSeconds(600)));
            audit.record("GOOGLE_CONSENT_STARTED","INTEGRATION_CONNECTION",id.toString(),"{}");
            return consent(jdbc.queryForMap("select * from integration_google_consent where id=?",consentId),true);
        });
    }
    Authorization authorize(String ticket) {
        if(!secret(ticket))throw invalid();
        return transactions.execute(tx->{
            var rows=jdbc.queryForList("select * from integration_google_consent where ticket_hash=? for update",hash(ticket));
            if(rows.size()!=1)throw problem("CONSENT_REPLAY_OR_EXPIRED",HttpStatus.CONFLICT);
            var row=rows.getFirst();
            if(!"INITIATED".equals(row.get("status")) || !instant(row,"expires_at").isAfter(Instant.now()))throw problem("CONSENT_REPLAY_OR_EXPIRED",HttpStatus.CONFLICT);
            String browser=random(),state=codec.decrypt((String)row.get("state_ciphertext")),verifier=codec.decrypt((String)row.get("verifier_ciphertext"));
            jdbc.update("update integration_google_consent set status='AUTHORIZED',ticket_hash=null,ticket_ciphertext=null,state_ciphertext=null,browser_hash=?,last_result_code='AWAITING_CALLBACK' where id=?",hash(browser),row.get("id"));
            String url="https://accounts.google.com/o/oauth2/v2/auth?"+GoogleHttpTransport.form(Map.of(
                    "client_id",config.clientId(),"redirect_uri",config.redirectUri(),"response_type","code","scope",GoogleCalendarConfiguration.SCOPE,
                    "access_type","offline","prompt","consent","include_granted_scopes","false","state",state,"code_challenge",challenge(verifier),"code_challenge_method","S256"));
            return new Authorization(url,browser);
        });
    }
    void callback(String state,String code,String error,String browser) {
        if(!secret(state) || !secret(browser))throw problem("CONSENT_BROWSER_MISMATCH",HttpStatus.BAD_REQUEST);
        transactions.executeWithoutResult(tx->{
            var rows=jdbc.queryForList("select * from integration_google_consent where state_hash=? for update",hash(state));
            if(rows.size()!=1)throw problem("CONSENT_REPLAY_OR_EXPIRED",HttpStatus.CONFLICT);
            var row=rows.getFirst();
            if(!"AUTHORIZED".equals(row.get("status")) || !instant(row,"expires_at").isAfter(Instant.now()))throw problem("CONSENT_REPLAY_OR_EXPIRED",HttpStatus.CONFLICT);
            if(!constantEquals(hash(browser),(String)row.get("browser_hash")))throw problem("CONSENT_BROWSER_MISMATCH",HttpStatus.BAD_REQUEST);
            boolean denied=error!=null || code==null || code.isBlank() || code.length()>4096 || code.chars().anyMatch(c->c<33||c>126);
            jdbc.update("update integration_google_consent set status=?,last_result_code=?,code_ciphertext=?,state_hash=null,browser_hash=null where id=?",
                    denied?"DENIED":"CALLBACK_RECEIVED",denied?"CONSENT_DENIED":"READY_TO_FINISH",denied?null:codec.encrypt(code),row.get("id"));
            if(denied)jdbc.update("update integration_google_consent set verifier_ciphertext=null where id=?",row.get("id"));
        });
    }
    public List<Consent> consents(GoogleAccountGuard.Auth auth) {
        return transactions.execute(tx->{guard.require(auth,false);return jdbc.queryForList("select * from integration_google_consent where owner_id=? order by created_at desc,id limit 100",auth.actor()).stream().map(row->consent(row,false)).toList();});
    }
    public Metadata metadata(GoogleAccountGuard.Auth auth,UUID id) {
        return transactions.execute(tx->{guard.require(auth,false);owned(auth.actor(),id,false);var row=jdbc.queryForMap("select * from integration_google_calendar where connection_id=?",id);
            return new Metadata((String)row.get("provisioning_status"),(String)row.get("revocation_status"),(String)row.get("last_result_code"));});
    }
    /** Phase one commits consumption before the first token request. Unknown exchange is never replayed. */
    public IntegrationModels.Connection complete(GoogleAccountGuard.Auth auth,UUID id) {
        Exchange exchange=transactions.execute(tx->{
            guard.require(auth,true);
            var lookup=jdbc.queryForList("select connection_id from integration_google_consent where id=? and owner_id=?",id,auth.actor());
            if(lookup.size()!=1)throw missing();UUID connection=(UUID)lookup.getFirst().get("connection_id");
            var current=owned(auth.actor(),connection,true);
            var row=jdbc.queryForMap("select * from integration_google_consent where id=? for update",id);
            if(!auth.session().equals(row.get("session_id")))throw problem("CONSENT_SESSION_MISMATCH",HttpStatus.FORBIDDEN);
            if(!"CALLBACK_RECEIVED".equals(row.get("status")) || !instant(row,"expires_at").isAfter(Instant.now()))throw problem("CONSENT_REPLAY_OR_EXPIRED",HttpStatus.CONFLICT);
            if(((Number)row.get("observed_version")).longValue()!=((Number)current.get("version")).longValue())throw conflict();
            Exchange value=new Exchange(id,connection,((Number)current.get("version")).longValue(),
                    codec.decrypt((String)row.get("code_ciphertext")),codec.decrypt((String)row.get("verifier_ciphertext")));
            jdbc.update("update integration_google_consent set status='EXCHANGE_UNKNOWN',last_result_code='EXCHANGE_UNKNOWN',code_ciphertext=null,verifier_ciphertext=null where id=?",id);
            return value;
        });
        Prepared prepared=transactions.execute(tx->{
            guard.require(auth,true);var current=owned(auth.actor(),exchange.connection(),true);
            var consent=jdbc.queryForMap("select * from integration_google_consent where id=? for update",id);
            if(!"EXCHANGE_UNKNOWN".equals(consent.get("status")) || ((Number)current.get("version")).longValue()!=exchange.version())throw conflict();
            var response=http.token(Map.of("grant_type","authorization_code","client_id",config.clientId(),"client_secret",config.clientSecret(),
                    "redirect_uri",config.redirectUri(),"code",exchange.code(),"code_verifier",exchange.verifier()));
            if(!response.success()) {
                String result=response.status()==0||response.status()>=500?"EXCHANGE_UNKNOWN":"REAUTH_REQUIRED";
                failConsent(exchange,result,result.equals("EXCHANGE_UNKNOWN")?"EXCHANGE_UNKNOWN":"FAILED");return null;
            }
            var tokens=GoogleTokenCodec.response(response.body(),null,true);
            if(tokens==null || tokens.refresh()==null) {
                String token=response.body()==null?null:response.body().path("refresh_token").asText(null);
                if(!GoogleTokenCodec.token(token) && response.body()!=null)token=response.body().path("access_token").asText(null);
                // Generation advances even for a partial grant; its revocation is independent of future consents.
                long generation=((Number)current.get("credential_version")).longValue()+1;
                if(GoogleTokenCodec.token(token))adapter.queueRevocation(exchange.connection(),generation,token);
                jdbc.update("update integration_connection set credential_ciphertext=null,credential_version=?,status='NEEDS_RECONNECT',version=version+1,updated_at=now() where id=?",generation,exchange.connection());
                String result=tokens==null?"SCOPE_UNVERIFIED":"MISSING_REFRESH_TOKEN";
                failConsent(exchange,result,"FAILED");return null;
            }
            jdbc.update("update integration_connection set credential_ciphertext=?,credential_version=credential_version+1,status='NEEDS_RECONNECT',credential_expires_at=?,version=version+1,updated_at=now() where id=?",
                    codec.encode(tokens),time(Instant.now().plusSeconds(90*86400L)),exchange.connection());
            var calendar=jdbc.queryForMap("select * from integration_google_calendar where connection_id=?",exchange.connection());
            if("PROVISIONING_UNKNOWN".equals(calendar.get("provisioning_status"))) {
                failConsent(exchange,"PROVISIONING_UNKNOWN","FAILED");return null;
            }
            String calendarId=(String)calendar.get("calendar_id");
            if(calendarId==null) {
                // This transaction commits before calendar insertion. An unknown outcome can never
                // return the durable calendar to UNPROVISIONED and trigger another automatic insert.
                jdbc.update("update integration_google_calendar set provisioning_status='PROVISIONING_UNKNOWN',last_result_code='PROVISIONING_UNKNOWN' where connection_id=?",exchange.connection());
                jdbc.update("update integration_connection set last_result_code='PROVISIONING_UNKNOWN' where id=?",exchange.connection());
                jdbc.update("update integration_google_consent set last_result_code='PROVISIONING_UNKNOWN' where id=?",id);
            }
            return new Prepared(tokens.access(),calendarId,((Number)current.get("credential_version")).longValue()+1);
        });
        if(prepared==null)return transactions.execute(tx->{guard.require(auth,false);owned(auth.actor(),exchange.connection(),false);return connection(exchange.connection());});
        return transactions.execute(tx->{
            guard.require(auth,true);var current=owned(auth.actor(),exchange.connection(),true);
            var consent=jdbc.queryForMap("select * from integration_google_consent where id=? for update",id);
            if(!"EXCHANGE_UNKNOWN".equals(consent.get("status")) || "REVOKED".equals(current.get("status"))
                    || prepared.generation()!=((Number)current.get("credential_version")).longValue())throw conflict();
            String calendarId=prepared.calendar();
            var provision=calendarId==null?http.calendar("POST",null,null,prepared.access(),Map.of("summary","BrainServe appointments","description",GoogleCalendarAdapter.calendarMarker(exchange.connection()),"timeZone","UTC"),null)
                    :http.calendar("GET",calendarId,null,prepared.access(),null,null);
            if(calendarId==null && provision.success() && provision.body()!=null)calendarId=provision.body().path("id").asText(null);
            if(!provision.success() || !verifiedCalendar(provision.body(),exchange.connection(),calendarId)) {
                String result=prepared.calendar()==null?"PROVISIONING_UNKNOWN":provision.status()==401||provision.status()==403?"REAUTH_REQUIRED":"CALENDAR_UNAVAILABLE";
                failConsent(exchange,result,"FAILED");return connection(exchange.connection());
            }
            activate(exchange.connection(),calendarId);
            jdbc.update("update integration_google_consent set status='COMPLETED',last_result_code='SUCCESS' where id=?",id);
            audit.record("GOOGLE_CALENDAR_CONNECTED","INTEGRATION_CONNECTION",exchange.connection().toString(),"{}");
            return connection(exchange.connection());
        });
    }
    public IntegrationModels.Connection recover(GoogleAccountGuard.Auth auth,UUID id,Recover command) {
        if(command==null || !GoogleCalendarAdapter.validCalendarId(command.calendarId()))throw invalid();
        return transactions.execute(tx->{
            guard.require(auth,true);var current=owned(auth.actor(),id,true);expected(command.expectedVersion(),((Number)current.get("version")).longValue());
            var calendar=jdbc.queryForMap("select * from integration_google_calendar where connection_id=?",id);
            if(!"PROVISIONING_UNKNOWN".equals(calendar.get("provisioning_status")) || "REVOKED".equals(current.get("status")))throw conflict();
            var tokens=codec.decode((String)current.get("credential_ciphertext"));
            if(tokens==null || tokens.refresh()==null)throw problem("REAUTH_REQUIRED",HttpStatus.CONFLICT);
            if(!tokens.expiresAt().isAfter(Instant.now().plusSeconds(30))) {
                var refreshed=http.token(Map.of("grant_type","refresh_token","refresh_token",tokens.refresh(),"client_id",config.clientId(),"client_secret",config.clientSecret()));
                if(!refreshed.success())throw problem("CALENDAR_RECOVERY_UNAVAILABLE",HttpStatus.CONFLICT);
                tokens=GoogleTokenCodec.response(refreshed.body(),tokens.refresh(),false);
                if(tokens==null)throw problem("REAUTH_REQUIRED",HttpStatus.CONFLICT);
                jdbc.update("update integration_connection set credential_ciphertext=? where id=?",codec.encode(tokens),id);
            }
            var verified=http.calendar("GET",command.calendarId(),null,tokens.access(),null,null);
            if(!verified.success() || !verifiedCalendar(verified.body(),id,command.calendarId()))throw problem("CALENDAR_RECOVERY_UNVERIFIED",HttpStatus.CONFLICT);
            activate(id,command.calendarId());audit.record("GOOGLE_CALENDAR_RECOVERED","INTEGRATION_CONNECTION",id.toString(),"{}");return connection(id);
        });
    }
    public Metadata retryRevocation(GoogleAccountGuard.Auth auth,UUID id,Long version) {
        transactions.executeWithoutResult(tx->{
            guard.require(auth,true);var current=owned(auth.actor(),id,true);expected(version,((Number)current.get("version")).longValue());
            int changed=jdbc.update("update integration_google_revocation set status='PENDING',attempts=0,recovery_count=recovery_count+1,next_attempt_at=now(),last_result_code='REVOKE_PENDING' where connection_id=? and status='FAILED' and recovery_count<3",id);
            if(changed==0)throw conflict();
            jdbc.update("update integration_google_calendar set revocation_status='PENDING',last_result_code='REVOKE_PENDING' where connection_id=?",id);
            jdbc.update("update integration_connection set version=version+1,updated_at=now() where id=?",id);
            audit.record("GOOGLE_REVOCATION_RETRIED","INTEGRATION_CONNECTION",id.toString(),"{}");
        });return metadata(auth,id);
    }
    private void activate(UUID id,String calendar) {
        jdbc.update("update integration_google_calendar set calendar_id=?,provisioning_status='READY',last_result_code='SUCCESS' where connection_id=?",calendar,id);
        jdbc.update("update integration_connection set status='ACTIVE',last_result_code='SUCCESS',version=version+1,last_checked_at=now(),updated_at=now() where id=?",id);
    }
    static boolean verifiedCalendar(com.fasterxml.jackson.databind.JsonNode body,UUID connection,String calendar) {
        return GoogleCalendarAdapter.validCalendarId(calendar) && body!=null && calendar.equals(body.path("id").asText())
                && GoogleCalendarAdapter.calendarMarker(connection).equals(body.path("description").asText());
    }
    private void failConsent(Exchange exchange,String code,String status) {
        jdbc.update("update integration_google_consent set status=?,last_result_code=? where id=?",status,code,exchange.id());
        jdbc.update("update integration_google_calendar set last_result_code=? where connection_id=?",code,exchange.connection());
        jdbc.update("update integration_connection set last_result_code=?,updated_at=now() where id=?",code,exchange.connection());
    }
    private void cancelPending(UUID id,String code) {
        jdbc.update("update integration_google_consent set status='CANCELLED',last_result_code=?,ticket_hash=null,state_hash=null,ticket_ciphertext=null,state_ciphertext=null,verifier_ciphertext=null,browser_hash=null,code_ciphertext=null where connection_id=? and status in ('INITIATED','AUTHORIZED','CALLBACK_RECEIVED','EXCHANGING','EXCHANGE_UNKNOWN')",code,id);
    }
    private Map<String,Object> owned(UUID actor,UUID id,boolean lock) {
        var rows=jdbc.queryForList("select * from integration_connection where id=? and owner_id=? and provider='GOOGLE_CALENDAR'"+(lock?" for update":""),id,actor);
        if(rows.size()!=1)throw missing();return rows.getFirst();
    }
    private IntegrationModels.Connection connection(UUID id) {
        var row=jdbc.queryForMap("select * from integration_connection where id=?",id);var provider=IntegrationModels.Provider.valueOf("GOOGLE_CALENDAR");
        return new IntegrationModels.Connection(id,provider,provider.kind(),(String)row.get("label"),(UUID)row.get("owner_id"),provider.scopes(),(String)row.get("status"),
                ((Number)row.get("credential_version")).longValue(),instant(row,"credential_expires_at"),((Number)row.get("version")).longValue(),instant(row,"last_checked_at"),(String)row.get("last_result_code"),instant(row,"created_at"),instant(row,"updated_at"));
    }
    private Consent consent(Map<String,Object> row,boolean includeTicket) {
        String status=(String)row.get("status");Instant expiry=instant(row,"expires_at");
        if(!expiry.isAfter(Instant.now()) && List.of("INITIATED","AUTHORIZED","CALLBACK_RECEIVED").contains(status))status="EXPIRED";
        String url=null;
        if(includeTicket && status.equals("INITIATED"))url=config.authorizeUri()+"?ticket="+GoogleHttpTransport.encode(codec.decrypt((String)row.get("ticket_ciphertext")));
        return new Consent((UUID)row.get("id"),(UUID)row.get("connection_id"),status,url,expiry,(String)row.get("last_result_code"));
    }
    private long count(String sql,Object...args) {return jdbc.queryForObject(sql,Long.class,args);}
    private static void expected(Long value,long actual) {if(value==null||value<0)throw invalid();if(value!=actual)throw conflict();}
    private static Timestamp time(Instant value) {return Timestamp.from(value);}
    private static Instant instant(Map<String,Object> row,String field) {return row.get(field)==null?null:((Timestamp)row.get(field)).toInstant();}
    static String random() {byte[] bytes=new byte[32];RANDOM.nextBytes(bytes);return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);}
    static String hash(String value) {try{return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception unavailable){throw new IllegalStateException("Consent hashing unavailable");}}
    static String challenge(String verifier) {return Base64.getUrlEncoder().withoutPadding().encodeToString(java.util.HexFormat.of().parseHex(hash(verifier)));}
    private static boolean secret(String value) {return value!=null&&value.matches("[A-Za-z0-9_-]{43}");}
    private static boolean constantEquals(String a,String b) {return b!=null&&MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8),b.getBytes(StandardCharsets.UTF_8));}
    private static BusinessException invalid() {return problem("INVALID_GOOGLE_CALENDAR_REQUEST",HttpStatus.BAD_REQUEST);}
    private static BusinessException conflict() {return problem("INTEGRATION_VERSION_CONFLICT",HttpStatus.CONFLICT);}
    private static BusinessException missing() {return problem("INTEGRATION_NOT_FOUND",HttpStatus.NOT_FOUND);}
    private static BusinessException problem(String code,HttpStatus status) {return new BusinessException(code,"Reload Google Calendar connection details and follow the available recovery action",status);}
    private record Exchange(UUID id,UUID connection,long version,String code,String verifier) { @Override public String toString(){return "Exchange[REDACTED]";} }
    private record Prepared(String access,String calendar,long generation) { @Override public String toString(){return "Prepared[REDACTED]";} }
}
