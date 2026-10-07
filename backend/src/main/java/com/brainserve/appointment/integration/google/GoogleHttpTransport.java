package com.brainserve.appointment.integration.google;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CompletableFuture;

/** Native bounded transport. Production destinations cannot be replaced by configuration. */
@Component
public class GoogleHttpTransport {
    static final int MAX_BYTES=65536;
    private final HttpClient client;
    private final ObjectMapper json;
    private final URI tokenEndpoint, revokeEndpoint, calendarEndpoint;
    @Autowired public GoogleHttpTransport(ObjectMapper json) {
        this(json,URI.create("https://oauth2.googleapis.com/token"),URI.create("https://oauth2.googleapis.com/revoke"),
                URI.create("https://www.googleapis.com/calendar/v3/calendars"));
    }
    /** Package-private seam used only by native transport tests; no deployment override exists. */
    GoogleHttpTransport(ObjectMapper json,URI tokenEndpoint,URI revokeEndpoint,URI calendarEndpoint) {
        this.json=json; this.tokenEndpoint=tokenEndpoint; this.revokeEndpoint=revokeEndpoint; this.calendarEndpoint=calendarEndpoint;
        client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build();
    }
    Reply token(Map<String,String> fields) { return send("POST",tokenEndpoint,null,form(fields),null,"application/x-www-form-urlencoded"); }
    Reply revoke(String token) { return send("POST",revokeEndpoint,null,form(Map.of("token",token)),null,"application/x-www-form-urlencoded"); }
    Reply calendar(String method,String calendarId,String eventId,String access,Object body,String etag) {
        String path=calendarEndpoint.toString()+(calendarId==null?"":"/"+encode(calendarId));
        if(eventId!=null) path+="/events"+(eventId.isEmpty()?"":"/"+encode(eventId))+"?sendUpdates=none&conferenceDataVersion=1&supportsAttachments=true";
        String payload=null;
        try { if(body!=null)payload=json.writeValueAsString(body); }
        catch(Exception invalid) { return new Reply(0,null,null,0); }
        return send(method,URI.create(path),access,payload,etag,"application/json");
    }
    private Reply send(String method,URI uri,String access,String body,String etag,String type) {
        CompletableFuture<HttpResponse<byte[]>> request=null;
        try {
            if(body!=null && body.getBytes(StandardCharsets.UTF_8).length>16384)return new Reply(0,null,null,0);
            HttpRequest.Builder builder=HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5)).header("Accept","application/json");
            if(access!=null)builder.header("Authorization","Bearer "+access);
            if(etag!=null)builder.header("If-Match",etag);
            if(body!=null)builder.header("Content-Type",type);
            builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body));
            request=client.sendAsync(builder.build(),info->new LimitedBody());
            // This deadline includes the final response byte, including slow/stalled bodies.
            HttpResponse<byte[]> response=request.get(5,TimeUnit.SECONDS);
            JsonNode parsed=null;
            // Retain successful JSON only. Error JSON can contribute one fixed whitelisted code;
            // messages, descriptions and arbitrary body fields are never retained or exposed.
            if(response.statusCode()>=200 && response.statusCode()<300 && response.body().length>0) parsed=json.readTree(response.body());
            String errorCode=null;
            if(response.statusCode()>=400 && response.body().length>0) {
                try {errorCode=safeErrorCode(json.readTree(response.body()));}catch(Exception invalid) { /* Unknown error body confers no semantic success. */ }
            }
            String tag=response.headers().firstValue("etag").orElse(null);
            if(tag!=null && (tag.length()>512 || tag.chars().anyMatch(c->c<32||c>126)))tag=null;
            int retry=60;
            try { retry=Math.max(1,Math.min(3600,Integer.parseInt(response.headers().firstValue("retry-after").orElse("60")))); }
            catch(NumberFormatException ignored) { /* HTTP-date and malformed hints use a bounded default. */ }
            return new Reply(response.statusCode(),parsed,tag,retry,errorCode);
        } catch(InterruptedException interrupted) { if(request!=null)request.cancel(true);Thread.currentThread().interrupt();return new Reply(0,null,null,0); }
        catch(Exception unavailable) { if(request!=null)request.cancel(true);return new Reply(0,null,null,0); }
    }
    static String encode(String value) { return URLEncoder.encode(value,StandardCharsets.UTF_8).replace("+","%20"); }
    static String form(Map<String,String> fields) { return fields.entrySet().stream().map(e->encode(e.getKey())+"="+encode(e.getValue())).collect(java.util.stream.Collectors.joining("&")); }
    private static String safeErrorCode(JsonNode body) {
        var allowed=java.util.Set.of("invalid_token","invalid_grant","rateLimitExceeded","userRateLimitExceeded");
        String direct=body.path("error").asText();
        if(allowed.contains(direct))return direct;
        JsonNode errors=body.path("error").path("errors");
        if(errors.isArray() && errors.size()<=8)for(JsonNode error:errors) {
            String reason=error.path("reason").asText();if(allowed.contains(reason))return reason;
        }
        return null;
    }
    record Reply(int status,JsonNode body,String etag,int retryAfterSeconds,String errorCode) {
        Reply(int status,JsonNode body,String etag,int retryAfterSeconds) {this(status,body,etag,retryAfterSeconds,null);}
        boolean success() { return status>=200 && status<300; }
        @Override public String toString() { return "GoogleReply[status="+status+"]"; }
    }
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final HttpResponse.BodySubscriber<byte[]> delegate=HttpResponse.BodySubscribers.ofByteArray();
        private Flow.Subscription subscription;
        private int bytes;
        public CompletionStage<byte[]> getBody() { return delegate.getBody(); }
        public void onSubscribe(Flow.Subscription value) { subscription=value; delegate.onSubscribe(value); }
        public void onNext(List<ByteBuffer> buffers) {
            long incoming=buffers.stream().mapToLong(ByteBuffer::remaining).sum();
            if(incoming>MAX_BYTES-bytes) { subscription.cancel(); delegate.onError(new IllegalStateException("Provider response exceeded limit")); return; }
            bytes+=(int)incoming; delegate.onNext(buffers);
        }
        public void onError(Throwable error) { delegate.onError(error); }
        public void onComplete() { delegate.onComplete(); }
    }
}
