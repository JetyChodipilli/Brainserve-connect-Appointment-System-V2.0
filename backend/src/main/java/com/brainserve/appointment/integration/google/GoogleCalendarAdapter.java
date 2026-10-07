package com.brainserve.appointment.integration.google;

import com.brainserve.appointment.shared.application.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** One-way provider worker. Caller holds account, connection and delivery locks in that order.
 * At most three native requests (refresh, read, write), each capped at five seconds.
 * Remote changes and database commits are not atomic; deterministic IDs repair unknown outcomes.
 */
@Component
public class GoogleCalendarAdapter {
    private final JdbcTemplate jdbc;
    private final GoogleHttpTransport http;
    private final GoogleTokenCodec codec;
    private final GoogleCalendarConfiguration config;
    private final GoogleAccountGuard guard;
    public GoogleCalendarAdapter(JdbcTemplate jdbc,GoogleHttpTransport http,GoogleTokenCodec codec,
                                 GoogleCalendarConfiguration config,GoogleAccountGuard guard) {
        this.jdbc=jdbc;this.http=http;this.codec=codec;this.config=config;this.guard=guard;
    }
    public record Result(String code,int retryAfterSeconds,String externalId) {}
    @Transactional(propagation=Propagation.MANDATORY)
    public Result deliver(UUID connectionId,long credentialVersion,String eventId,long revision,String action,Instant start,Instant end) {
        if(eventId==null || !eventId.matches("[0-9a-v]{5,200}") || revision<1 || !("UPSERT".equals(action)||"DELETE".equals(action)))return result("PERMANENT_FAILURE",0,null);
        if(action.equals("UPSERT") && (start==null || end==null || !end.isAfter(start) || end.isAfter(start.plusSeconds(86400))))return result("PERMANENT_FAILURE",0,null);
        var rows=jdbc.queryForList("""
                select c.owner_id,c.status,c.credential_version,c.credential_ciphertext,c.credential_expires_at,g.calendar_id,g.provisioning_status
                from integration_connection c join integration_google_calendar g on g.connection_id=c.id where c.id=? and c.provider='GOOGLE_CALENDAR'
                """,connectionId);
        if(rows.size()!=1)return result("REAUTH_REQUIRED",0,null);
        var row=rows.getFirst();
        try { guard.requireOwner((UUID)row.get("owner_id")); }
        catch(BusinessException denied) { return result("REAUTH_REQUIRED",0,null); }
        if(!"ACTIVE".equals(row.get("status")) || credentialVersion!=((Number)row.get("credential_version")).longValue()
                || !((Timestamp)row.get("credential_expires_at")).toInstant().isAfter(Instant.now()) || !"READY".equals(row.get("provisioning_status")))return result("REAUTH_REQUIRED",0,null);
        GoogleTokenCodec.Tokens tokens=codec.decode((String)row.get("credential_ciphertext"));
        if(tokens==null || !GoogleTokenCodec.token(tokens.refresh()))return reauth(connectionId);
        if(!tokens.expiresAt().isAfter(Instant.now().plusSeconds(30))) {
            var refresh=http.token(Map.of("grant_type","refresh_token","refresh_token",tokens.refresh(),"client_id",config.clientId(),"client_secret",config.clientSecret()));
            if(!refresh.success()) { if(refresh.status()==400||refresh.status()==401)return reauth(connectionId);return failure(refresh); }
            tokens=GoogleTokenCodec.response(refresh.body(),tokens.refresh(),false);
            if(tokens==null)return reauth(connectionId);
            jdbc.update("update integration_connection set credential_ciphertext=?,updated_at=now() where id=? and credential_version=?",codec.encode(tokens),connectionId,credentialVersion);
        }
        String calendar=(String)row.get("calendar_id");
        if(!validCalendarId(calendar))return reauth(connectionId);
        var current=http.calendar("GET",calendar,eventId,tokens.access(),null,null);
        if(current.status()==404 || current.status()==410) {
            if(action.equals("DELETE"))return result("SUCCESS",0,eventId);
            if(current.status()==410)return result("PERMANENT_FAILURE",0,null);
            var inserted=http.calendar("POST",calendar,"",tokens.access(),event(connectionId,eventId,revision,start,end),null);
            return written(inserted,eventId);
        }
        if(!current.success())return current.status()==401?reauth(connectionId):failure(current);
        JsonNode body=current.body();
        boolean managed=managed(body,connectionId,eventId);
        var mapped=jdbc.query("select business_revision from integration_external_mapping where connection_id=? and external_id=?",(rs,n)->rs.getLong(1),connectionId,eventId);
        boolean mappedTombstone=body!=null && eventId.equals(body.path("id").asText()) && "cancelled".equals(body.path("status").asText())
                && !body.path("extendedProperties").has("private") && !body.has("recurringEventId")
                && mapped.size()==1;
        if(!managed && !mappedTombstone)return result("PERMANENT_FAILURE",0,null);
        long remote=managed?remoteRevision(body):mapped.getFirst();
        if(remote<0)return result("PERMANENT_FAILURE",0,null);
        if(remote>revision)return result("NEWER_REVISION",0,eventId);
        String etag=current.etag();
        if(etag==null && body.path("etag").isTextual())etag=body.path("etag").asText();
        if(etag==null || etag.length()>512 || !etag.matches("[\\x20-\\x7e]+"))return result("PERMANENT_FAILURE",0,null);
        if(action.equals("DELETE")) {
            var deleted=http.calendar("DELETE",calendar,eventId,tokens.access(),null,etag);
            if(deleted.status()==404||deleted.status()==410||deleted.success())return result("SUCCESS",0,eventId);
            return deleted.status()==401?reauth(connectionId):failure(deleted);
        }
        if(remote==revision && matches(body,start,end))return result("SUCCESS",0,eventId);
        var updated=http.calendar("PUT",calendar,eventId,tokens.access(),event(connectionId,eventId,revision,start,end),etag);
        return written(updated,eventId);
    }
    private Result written(GoogleHttpTransport.Reply reply,String eventId) {
        if(reply.success() && reply.body()!=null && eventId.equals(reply.body().path("id").asText())
                && (!reply.body().has("status")||"confirmed".equals(reply.body().path("status").asText())))return result("SUCCESS",0,eventId);
        if(reply.success())return result("OUTAGE",60,null);
        return failure(reply);
    }
    private Result reauth(UUID id) {
        jdbc.update("update integration_connection set status='NEEDS_RECONNECT',last_result_code='REAUTH_REQUIRED',version=version+1,updated_at=now() where id=? and status='ACTIVE'",id);
        jdbc.update("update integration_google_calendar set last_result_code='REAUTH_REQUIRED' where connection_id=?",id);
        return result("REAUTH_REQUIRED",0,null);
    }
    static Result failure(GoogleHttpTransport.Reply reply) {
        if(reply.status()==429 || reply.status()==403&&java.util.Set.of("rateLimitExceeded","userRateLimitExceeded").contains(reply.errorCode()==null?"":reply.errorCode()))return result("RATE_LIMITED",reply.retryAfterSeconds(),null);
        if(reply.status()==401)return result("REAUTH_REQUIRED",0,null);
        if(reply.status()==0 || reply.status()>=500 || reply.status()==409 || reply.status()==412)return result("OUTAGE",60,null);
        return result("PERMANENT_FAILURE",0,null);
    }
    private static Result result(String code,int retry,String external) { return new Result(code,Math.max(0,Math.min(3600,retry)),external); }
    static boolean validCalendarId(String id) { return id!=null && id.length()<=512 && id.matches("[A-Za-z0-9._%+@-]{3,512}") && id.contains("@"); }
    static String calendarMarker(UUID connection) { return "BrainServe application calendar "+connection; }
    static boolean managed(JsonNode event,UUID connection,String id) {
        return event!=null && id.equals(event.path("id").asText())
                && connection.toString().equals(event.path("extendedProperties").path("private").path("brainserveConnection").asText())
                && id.equals(event.path("extendedProperties").path("private").path("brainserveEvent").asText());
    }
    static long remoteRevision(JsonNode event) {
        String value=event.path("extendedProperties").path("private").path("brainserveRevision").asText();
        if(!value.matches("[0-9]{1,19}"))return -1;
        try { return Long.parseLong(value); }catch(NumberFormatException invalid) {return -1;}
    }
    private static boolean matches(JsonNode body,Instant start,Instant end) {
        try { return "BrainServe appointment".equals(body.path("summary").asText()) && !body.has("attendees")
                && !body.has("description") && !body.has("location") && (!body.has("status")||"confirmed".equals(body.path("status").asText()))
                && !body.has("conferenceData") && !body.has("attachments") && !body.has("recurrence") && !body.has("source")
                && !body.has("gadget") && !body.has("hangoutLink") && !body.path("extendedProperties").has("shared")
                && body.path("extendedProperties").path("private").size()==3
                && (!body.has("visibility") || "private".equals(body.path("visibility").asText()))
                && (!body.has("reminders") || !body.path("reminders").path("useDefault").asBoolean(true) && body.path("reminders").path("overrides").size()==0)
                && Instant.parse(body.path("start").path("dateTime").asText()).equals(start)
                && Instant.parse(body.path("end").path("dateTime").asText()).equals(end); }
        catch(RuntimeException invalid) { return false; }
    }
    static Map<String,Object> event(UUID connection,String id,long revision,Instant start,Instant end) {
        Map<String,Object> event=new java.util.LinkedHashMap<>();
        event.put("id",id);event.put("summary","BrainServe appointment");event.put("status","confirmed");event.put("visibility","private");
        event.put("start",Map.of("dateTime",start.toString(),"timeZone","UTC"));event.put("end",Map.of("dateTime",end.toString(),"timeZone","UTC"));
        event.put("extendedProperties",Map.of("private",Map.of("brainserveConnection",connection.toString(),"brainserveEvent",id,"brainserveRevision",Long.toString(revision))));
        event.put("reminders",Map.of("useDefault",false));
        // Full replacement removes externally added sensitive fields without copying them back.
        event.put("conferenceData",null);event.put("attachments",java.util.List.of());event.put("recurrence",java.util.List.of());
        return event;
    }
    /** Must be called before the local generation's encrypted credential is cleared. No network I/O. */
    @Transactional(propagation=Propagation.MANDATORY)
    public void disconnect(UUID connectionId,long credentialVersion) {
        var rows=jdbc.queryForList("select credential_ciphertext,credential_version from integration_connection where id=? and provider='GOOGLE_CALENDAR'",connectionId);
        if(rows.size()!=1 || credentialVersion!=((Number)rows.getFirst().get("credential_version")).longValue())return;
        var tokens=codec.decode((String)rows.getFirst().get("credential_ciphertext"));
        if(tokens!=null)queueRevocation(connectionId,credentialVersion,tokens.refresh()!=null?tokens.refresh():tokens.access());
        jdbc.update("""
                update integration_google_consent set status='CANCELLED',last_result_code='CONNECTION_REVOKED',ticket_hash=null,state_hash=null,
                ticket_ciphertext=null,state_ciphertext=null,verifier_ciphertext=null,browser_hash=null,code_ciphertext=null
                where connection_id=? and status in ('INITIATED','AUTHORIZED','CALLBACK_RECEIVED','EXCHANGING','EXCHANGE_UNKNOWN')
                """,connectionId);
    }
    void queueRevocation(UUID id,long generation,String token) {
        if(!GoogleTokenCodec.token(token))return;
        jdbc.update("insert into integration_google_revocation(id,connection_id,credential_version,token_ciphertext,status) values(?,?,?,?,'PENDING') on conflict(connection_id,credential_version) do nothing",
                UUID.randomUUID(),id,generation,codec.encrypt(token));
        jdbc.update("update integration_google_calendar set revocation_status='PENDING' where connection_id=?",id);
    }
}
