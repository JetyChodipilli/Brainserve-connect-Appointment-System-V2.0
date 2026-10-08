package com.brainserve.appointment.integration.slack;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

/** Native Slack transport. Deployment settings cannot replace the provider endpoints. */
@Component
public class SlackHttpTransport {
    static final int MAX_BYTES = 65_536;
    private static final int MAX_REQUEST_BYTES = 16_384;
    private static final URI API = URI.create("https://slack.com/api/");
    private static final Set<String> ALLOWED_ERRORS = Set.of(
            "not_authed", "invalid_auth", "token_revoked", "token_expired", "account_inactive", "missing_scope",
            "channel_not_found", "not_in_channel", "is_archived", "restricted_action",
            "no_permission", "invalid_arguments", "invalid_arg_name", "msg_too_long", "no_text",
            "ekm_access_denied", "internal_error", "fatal_error", "service_unavailable");

    private final HttpClient client;
    private final ObjectMapper json;
    private final URI api;

    @Autowired
    public SlackHttpTransport(ObjectMapper json) {
        this.json = json;
        api = API;
        client = client();
    }

    /** Test-only seam accepts loopback HTTP servers, never a deployment override. */
    SlackHttpTransport(ObjectMapper json, URI api) {
        if (!"http".equals(api.getScheme()) || api.getHost() == null
                || !Set.of("127.0.0.1", "localhost", "[::1]").contains(api.getHost())
                || api.getUserInfo() != null || api.getQuery() != null || api.getFragment() != null
                || !api.getPath().endsWith("/")) {
            throw new IllegalArgumentException("Slack test endpoint must be a loopback HTTP directory");
        }
        this.json = json;
        this.api = api;
        client = client();
    }

    private static HttpClient client() {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    public Reply auth(String token) {
        return send("auth.test", token, Map.of());
    }

    public Reply post(String token, String channel, String link, boolean test) {
        if (channel == null || !channel.matches("[CG][A-Z0-9]{8,31}") || !validLink(link)) {
            return invalidRequest();
        }
        String text = (test ? "BrainServe Slack connection test. " : "A visitor has arrived. ")
                + "Open BrainServe reception: " + link;
        return send("chat.postMessage", token, Map.of("channel", channel, "text", text,
                "mrkdwn", false, "parse", "none", "unfurl_links", false, "unfurl_media", false));
    }

    public Reply revoke(String token) {
        return send("auth.revoke", token, Map.of());
    }

    private static boolean validLink(String link) {
        if (link == null || link.length() > 2_048 || link.chars().anyMatch(c -> c <= 32 || c > 126)) return false;
        try {
            URI uri = URI.create(link);
            return "https".equals(uri.getScheme()) && uri.getHost() != null && uri.getUserInfo() == null
                    && uri.getQuery() == null && uri.getFragment() == null
                    && "/".equals(uri.getPath());
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    private Reply send(String method, String token, Object fields) {
        CompletableFuture<HttpResponse<byte[]>> pending = null;
        try {
            if (token == null || token.length() < 16 || token.length() > 4_096
                    || token.chars().anyMatch(c -> c <= 32 || c > 126)) return invalidRequest();
            byte[] requestBody = json.writeValueAsBytes(fields);
            if (requestBody.length > MAX_REQUEST_BYTES) return invalidRequest();
            HttpRequest request = HttpRequest.newBuilder(api.resolve(method)).timeout(Duration.ofSeconds(5))
                    .header("Authorization", "Bearer " + token).header("Accept", "application/json")
                    .header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(requestBody)).build();
            pending = client.sendAsync(request, info -> new LimitedBody());
            // A header timeout alone does not bound a stalled final response byte.
            HttpResponse<byte[]> response = pending.get(5, TimeUnit.SECONDS);
            int status = response.statusCode();
            Set<String> scopes = scopes(response.headers().allValues("x-oauth-scopes"));
            int retryAfter = retryAfter(response.headers().firstValue("retry-after").orElse(null));
            if (status == 429) return new Reply(status, null, scopes, retryAfter, "rate_limited");
            if (status >= 500) return new Reply(status, null, scopes, retryAfter, "TRANSPORT_UNKNOWN");
            if (status >= 300 && status < 400) return new Reply(status, null, scopes, retryAfter, "TRANSPORT_UNKNOWN");

            JsonNode body;
            try {
                body = json.readTree(response.body());
            } catch (Exception malformed) {
                return new Reply(status, null, scopes, retryAfter,
                        status >= 400 ? "HTTP_REJECTED" : "TRANSPORT_UNKNOWN");
            }
            if (status >= 400) return new Reply(status, null, scopes, retryAfter, safeError(body, "HTTP_REJECTED"));
            if (status < 200 || body == null || !body.isObject() || !body.path("ok").isBoolean()) {
                return new Reply(status, null, scopes, retryAfter, "TRANSPORT_UNKNOWN");
            }
            if (!body.path("ok").booleanValue()) return new Reply(status, null, scopes, retryAfter, safeError(body, "SLACK_REJECTED"));
            return new Reply(status, acknowledgement(body), scopes, retryAfter, null);
        } catch (InterruptedException interrupted) {
            if (pending != null) pending.cancel(true);
            Thread.currentThread().interrupt();
            return unknown();
        } catch (Exception unavailable) {
            if (pending != null) pending.cancel(true);
            // Exception text can contain provider-controlled data or request credentials.
            return unknown();
        }
    }

    private ObjectNode acknowledgement(JsonNode body) {
        ObjectNode safe = json.createObjectNode().put("ok", true);
        for (String field : List.of("team_id", "bot_id", "channel", "ts")) {
            JsonNode value = body.path(field);
            if (value.isTextual() && value.textValue().matches("[A-Za-z0-9._:-]{1,128}")) {
                safe.put(field, value.textValue());
            }
        }
        if (body.path("revoked").isBoolean()) safe.put("revoked", body.path("revoked").booleanValue());
        return safe;
    }

    private static String safeError(JsonNode body, String fallback) {
        if (body == null || !body.isObject() || !body.path("error").isTextual()) return fallback;
        String code = body.path("error").textValue();
        if ("ratelimited".equals(code) || "rate_limited".equals(code)) return "rate_limited";
        return ALLOWED_ERRORS.contains(code) ? code : fallback;
    }

    private static Set<String> scopes(List<String> headers) {
        String value = String.join(",", headers);
        if (value.length() > 4_096) return Set.of();
        Set<String> result = new java.util.HashSet<>();
        for (String entry : value.split(",")) {
            String scope = entry.trim();
            if (!scope.isEmpty()) {
                if (!scope.matches("[a-z][a-z0-9:._-]{0,127}") || result.size() >= 64) return Set.of();
                result.add(scope);
            }
        }
        return Set.copyOf(result);
    }

    private static int retryAfter(String header) {
        if (header != null) header = header.trim();
        if (header == null || !header.matches("[0-9]+")) return 60;
        try {
            long seconds = Long.parseLong(header);
            // Saturation never schedules before a valid provider boundary within our 24-hour horizon.
            return seconds <= 0 ? 60 : (int) Math.min(Integer.MAX_VALUE, seconds);
        } catch (NumberFormatException greaterThanLong) {
            return Integer.MAX_VALUE;
        }
    }

    private static Reply invalidRequest() { return new Reply(0, null, Set.of(), 0, "INVALID_REQUEST"); }
    private static Reply unknown() { return new Reply(0, null, Set.of(), 0, "TRANSPORT_UNKNOWN"); }

    public record Reply(int status, JsonNode body, Set<String> scopes, int retryAfterSeconds, String errorCode) {
        public Reply { scopes = scopes == null ? Set.of() : Set.copyOf(scopes); }
        public boolean ok() { return status >= 200 && status < 300 && body != null && body.path("ok").isBoolean() && body.path("ok").booleanValue(); }
        @Override public String toString() { return "SlackReply[status=" + status + "]"; }
    }

    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final HttpResponse.BodySubscriber<byte[]> delegate = HttpResponse.BodySubscribers.ofByteArray();
        private Flow.Subscription subscription;
        private int bytes;
        public CompletionStage<byte[]> getBody() { return delegate.getBody(); }
        public void onSubscribe(Flow.Subscription value) { subscription = value; delegate.onSubscribe(value); }
        public void onNext(List<ByteBuffer> buffers) {
            long incoming = buffers.stream().mapToLong(ByteBuffer::remaining).sum();
            if (incoming > MAX_BYTES - bytes) {
                subscription.cancel();
                delegate.onError(new IllegalStateException("Slack response exceeded limit"));
                return;
            }
            bytes += (int) incoming;
            delegate.onNext(buffers);
        }
        public void onError(Throwable error) { delegate.onError(error); }
        public void onComplete() { delegate.onComplete(); }
    }
}
