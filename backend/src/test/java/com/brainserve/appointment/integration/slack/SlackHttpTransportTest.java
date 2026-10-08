package com.brainserve.appointment.integration.slack;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class SlackHttpTransportTest {
    private static final String TOKEN = "xoxb-private-test-credential";
    private static final String CHANNEL = "C12345678";
    private static final String LINK = "https://brainserve.test/";
    private final ObjectMapper json = new ObjectMapper();

    @Test void authReadsExactScopeHeadersAndDiscardsAllUnneededProviderFields() {
        try (var server = new TestServer()) {
            server.reply(200, "{\"ok\":true,\"team_id\":\"T12345678\",\"bot_id\":\"B12345678\",\"url\":\"https://private.test\",\"user\":\"private-user\",\"access_token\":\"" + TOKEN + "\"}",
                    Map.of("X-OAuth-Scopes", "chat:write, users:read"));
            var reply = server.transport(json).auth(TOKEN);
            assertThat(reply.ok()).isTrue();
            assertThat(reply.scopes()).containsExactlyInAnyOrder("chat:write", "users:read");
            assertThatThrownBy(() -> reply.scopes().add("chat:write.public")).isInstanceOf(UnsupportedOperationException.class);
            assertThat(reply.body().fieldNames()).toIterable().containsExactlyInAnyOrder("ok", "team_id", "bot_id");
            assertThat(reply.body().toString()).doesNotContain("private", "access_token", TOKEN);
            assertThat(reply.toString()).isEqualTo("SlackReply[status=200]");
            var request = server.requests().getFirst();
            assertThat(request.method()).isEqualTo("POST");
            assertThat(request.path()).isEqualTo("/api/auth.test");
            assertThat(request.authorization()).isEqualTo("Bearer " + TOKEN);
            assertThat(request.body()).isEqualTo("{}");
            assertThat(request.toString()).doesNotContain(TOKEN);
        }
    }

    @Test void postSendsOnlyFixedGenericNoticeAndDisablesMentionsAndUnfurling() throws Exception {
        try (var server = new TestServer()) {
            server.reply(200, "{\"ok\":true,\"channel\":\"C12345678\",\"ts\":\"1710000000.000100\",\"message\":{\"text\":\"private-user\"}}");
            var reply = server.transport(json).post(TOKEN, CHANNEL, LINK, false);
            assertThat(reply.ok()).isTrue();
            assertThat(reply.body().path("channel").textValue()).isEqualTo(CHANNEL);
            assertThat(reply.body().path("ts").textValue()).isEqualTo("1710000000.000100");
            assertThat(reply.body().has("message")).isFalse();
            var request = server.requests().getFirst();
            assertThat(request.path()).isEqualTo("/api/chat.postMessage");
            var payload = json.readTree(request.body());
            assertThat(payload.fieldNames()).toIterable().containsExactlyInAnyOrder("channel", "text", "mrkdwn", "parse", "unfurl_links", "unfurl_media");
            assertThat(payload.path("channel").textValue()).isEqualTo(CHANNEL);
            assertThat(payload.path("text").textValue()).isEqualTo("A visitor has arrived. Open BrainServe reception: " + LINK);
            assertThat(payload.path("mrkdwn").booleanValue()).isFalse();
            assertThat(payload.path("parse").textValue()).isEqualTo("none");
            assertThat(payload.path("unfurl_links").booleanValue()).isFalse();
            assertThat(payload.path("unfurl_media").booleanValue()).isFalse();
            assertThat(request.body()).doesNotContain(TOKEN, "metadata", "appointment", "visitorId", "businessEventId");
            server.reply(200, "{\"ok\":true,\"channel\":\"C12345678\",\"ts\":\"1710000001.000100\"}");
            server.transport(json).post(TOKEN, CHANNEL, LINK, true);
            assertThat(json.readTree(server.requests().get(1).body()).path("text").textValue())
                    .isEqualTo("BrainServe Slack connection test. Open BrainServe reception: " + LINK);
        }
    }

    @Test void httpSuccessRequiresAnExplicitBooleanSlackAcknowledgement() {
        try (var server = new TestServer()) {
            var transport = server.transport(json);
            for (String response : List.of("{}", "{\"ok\":\"true\"}", "{\"ok\":1}", "[]", "null", "not-json", "")) {
                server.reply(200, response);
                var reply = transport.post(TOKEN, CHANNEL, LINK, false);
                assertThat(reply.ok()).isFalse();
                assertThat(reply.body()).isNull();
                assertThat(reply.errorCode()).isEqualTo("TRANSPORT_UNKNOWN");
            }
            server.reply(200, "{\"ok\":false,\"error\":\"not_in_channel\"}");
            var rejected = transport.post(TOKEN, CHANNEL, LINK, false);
            assertThat(rejected.ok()).isFalse();
            assertThat(rejected.errorCode()).isEqualTo("not_in_channel");
            assertThat(rejected.body()).isNull();
        }
    }

    @Test void errorBodiesNeverEscapeAndOnlyAllowlistedCodesAreReturned() {
        try (var server = new TestServer()) {
            var transport = server.transport(json);
            server.reply(200, "{\"ok\":false,\"error\":\"" + TOKEN + "\",\"response_metadata\":{\"messages\":[\"private\"]}}");
            var unknownCode = transport.post(TOKEN, CHANNEL, LINK, false);
            assertThat(unknownCode.errorCode()).isEqualTo("SLACK_REJECTED");
            assertThat(unknownCode.body()).isNull();
            assertThat(unknownCode.toString()).doesNotContain(TOKEN, "private");
            server.reply(403, "{\"ok\":false,\"error\":\"missing_scope\",\"needed\":\"private\"}");
            assertThat(transport.auth(TOKEN).errorCode()).isEqualTo("missing_scope");
            server.reply(400, "private malformed error");
            assertThat(transport.post(TOKEN, CHANNEL, LINK, false).errorCode()).isEqualTo("HTTP_REJECTED");
            server.reply(503, "{\"ok\":true,\"channel\":\"C12345678\",\"ts\":\"1710000000.000100\"}");
            var serverFailure = transport.post(TOKEN, CHANNEL, LINK, false);
            assertThat(serverFailure.ok()).isFalse();
            assertThat(serverFailure.body()).isNull();
            assertThat(serverFailure.errorCode()).isEqualTo("TRANSPORT_UNKNOWN");
            server.reply(200, "{\"ok\":false,\"error\":\"internal_error\"}");
            assertThat(transport.post(TOKEN, CHANNEL, LINK, false).errorCode()).isEqualTo("internal_error");
        }
    }

    @Test void rateLimitNeedsNoJsonAndNeverShortensValidRetryAfterWithinTheRetryHorizon() {
        try (var server = new TestServer()) {
            var transport = server.transport(json);
            for (var entry : Map.of("90000", 90_000, "1", 1, "2147483647", Integer.MAX_VALUE, "9999999999", Integer.MAX_VALUE,
                    "10000000000", Integer.MAX_VALUE, "99999999999999999999999", Integer.MAX_VALUE).entrySet()) {
                server.reply(429, "private error text", Map.of("Retry-After", entry.getKey()));
                var reply = transport.post(TOKEN, CHANNEL, LINK, false);
                assertThat(reply.errorCode()).isEqualTo("rate_limited");
                assertThat(reply.retryAfterSeconds()).isEqualTo(entry.getValue());
                assertThat(reply.body()).isNull();
            }
            for (String invalid : List.of("0", "-1", "not-seconds", "Wed, 21 Oct 2026 07:28:00 GMT")) {
                server.reply(429, "", Map.of("Retry-After", invalid));
                assertThat(transport.post(TOKEN, CHANNEL, LINK, false).retryAfterSeconds()).isEqualTo(60);
            }
            server.reply(200, "{\"ok\":false,\"error\":\"ratelimited\"}", Map.of("Retry-After", "7"));
            var slackRateLimit = transport.post(TOKEN, CHANNEL, LINK, false);
            assertThat(slackRateLimit.errorCode()).isEqualTo("rate_limited");
            assertThat(slackRateLimit.retryAfterSeconds()).isEqualTo(7);
        }
    }

    @Test void redirectNeverForwardsBotCredential() {
        try (var server = new TestServer()) {
            server.reply(302, "private provider body", Map.of("Location", server.uri("/stolen").toString()));
            var reply = server.transport(json).post(TOKEN, CHANNEL, LINK, false);
            assertThat(reply.status()).isEqualTo(302);
            assertThat(reply.ok()).isFalse();
            assertThat(reply.errorCode()).isEqualTo("TRANSPORT_UNKNOWN");
            assertThat(reply.body()).isNull();
            assertThat(server.requests()).hasSize(1);
            assertThat(server.requests().getFirst().path()).isEqualTo("/api/chat.postMessage");
        }
    }

    @Test void finalResponseByteIsBoundedEvenAfterHeadersAndBodyHaveStarted() {
        assertTimeoutPreemptively(Duration.ofSeconds(7), () -> {
            try (var server = new TestServer()) {
                server.stall("{\"ok\":true,\"channel\":\"C12345678\",\"ts\":\"1710000000.000100\"}", 10_000);
                Instant began = Instant.now();
                var reply = server.transport(json).post(TOKEN, CHANNEL, LINK, false);
                assertThat(Duration.between(began, Instant.now())).isLessThan(Duration.ofSeconds(6));
                assertThat(reply.status()).isZero();
                assertThat(reply.errorCode()).isEqualTo("TRANSPORT_UNKNOWN");
                assertThat(reply.body()).isNull();
                assertThat(server.requests()).hasSize(1);
            }
        });
    }

    @Test void oversizeResponseIsUncertainAndInvalidRequestNeverReachesSlack() {
        try (var server = new TestServer()) {
            var transport = server.transport(json);
            server.reply(200, "{\"ok\":true,\"secret\":\"" + "界".repeat(23_000) + "\"}");
            var oversized = transport.post(TOKEN, CHANNEL, LINK, false);
            assertThat(oversized.status()).isZero();
            assertThat(oversized.errorCode()).isEqualTo("TRANSPORT_UNKNOWN");
            assertThat(oversized.body()).isNull();
            assertThat(transport.auth(TOKEN + "\r\nInjected: true").errorCode()).isEqualTo("INVALID_REQUEST");
            assertThat(transport.auth("x".repeat(4_097)).errorCode()).isEqualTo("INVALID_REQUEST");
            assertThat(transport.post(TOKEN, "@here", LINK, false).errorCode()).isEqualTo("INVALID_REQUEST");
            assertThat(transport.post(TOKEN, "D12345678", LINK, false).errorCode()).isEqualTo("INVALID_REQUEST");
            for (String unsafe : List.of("http://brainserve.test/", "https://private:secret@brainserve.test/",
                    LINK + "?visitorId=private", LINK + "#private", LINK + "other", "https://brainserve.test/ <@here>")) {
                assertThat(transport.post(TOKEN, CHANNEL, unsafe, false).errorCode()).isEqualTo("INVALID_REQUEST");
            }
            assertThat(server.requests()).hasSize(1);
        }
    }

    @Test void scopeHeadersFailClosedWhenMalformedOrMissing() {
        try (var server = new TestServer()) {
            var transport = server.transport(json);
            for (String header : List.of("chat:write,private token", "chat:write," + "x".repeat(4_100))) {
                server.reply(200, "{\"ok\":true}", Map.of("X-OAuth-Scopes", header));
                assertThat(transport.auth(TOKEN).scopes()).isEmpty();
            }
            server.reply(200, "{\"ok\":true}");
            assertThat(transport.auth(TOKEN).scopes()).isEmpty();
        }
    }

    @Test void revokeUsesBearerAuthenticationAndRetainsOnlyExplicitRevocationAcknowledgement() {
        try (var server = new TestServer()) {
            server.reply(200, "{\"ok\":true,\"revoked\":true,\"token\":\"" + TOKEN + "\",\"private\":\"details\"}");
            var reply = server.transport(json).revoke(TOKEN);
            assertThat(reply.ok()).isTrue();
            assertThat(reply.body().path("revoked").booleanValue()).isTrue();
            assertThat(reply.body().fieldNames()).toIterable().containsExactlyInAnyOrder("ok", "revoked");
            assertThat(reply.toString()).doesNotContain(TOKEN);
            assertThat(server.requests().getFirst().path()).isEqualTo("/api/auth.revoke");
            assertThat(server.requests().getFirst().body()).isEqualTo("{}");
        }
    }

    @Test void testConstructorRefusesExternalOrAmbiguousDestinations() {
        for (String unsafe : List.of("https://slack.com/api/", "http://attacker.test/api/", "http://127.0.0.1/api",
                "http://private:secret@127.0.0.1/api/", "http://127.0.0.1/api/?private=secret", "http://127.0.0.1/api/#secret")) {
            assertThatThrownBy(() -> new SlackHttpTransport(json, URI.create(unsafe))).isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Slack test endpoint must be a loopback HTTP directory");
        }
    }

    @Test void replyOkCannotCoerceStringsOrIgnoreHttpRejection() throws Exception {
        assertThat(new SlackHttpTransport.Reply(200, json.readTree("{\"ok\":\"true\"}"), Set.of(), 0, null).ok()).isFalse();
        assertThat(new SlackHttpTransport.Reply(500, json.readTree("{\"ok\":true}"), Set.of(), 0, null).ok()).isFalse();
        assertThat(new SlackHttpTransport.Reply(200, null, Set.of(), 0, null).ok()).isFalse();
    }

    private static final class TestServer implements AutoCloseable {
        private final HttpServer server;
        private final ExecutorService executor = Executors.newCachedThreadPool();
        private final ConcurrentLinkedQueue<Response> responses = new ConcurrentLinkedQueue<>();
        private final List<Request> requests = new CopyOnWriteArrayList<>();

        TestServer() {
            try {
                server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                server.setExecutor(executor);
                server.createContext("/", exchange -> {
                    requests.add(new Request(exchange.getRequestMethod(), exchange.getRequestURI().toString(),
                            exchange.getRequestHeaders().getFirst("Authorization"),
                            new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
                    Response response = responses.poll();
                    if (response == null) response = new Response(500, "{}", Map.of(), 0);
                    response.headers().forEach((key, value) -> exchange.getResponseHeaders().add(key, value));
                    byte[] bytes = response.body().getBytes(StandardCharsets.UTF_8);
                    try {
                        exchange.sendResponseHeaders(response.status(), bytes.length == 0 ? -1 : bytes.length);
                        if (response.stallMillis() > 0) {
                            exchange.getResponseBody().write(bytes, 0, 1);
                            exchange.getResponseBody().flush();
                            Thread.sleep(response.stallMillis());
                            exchange.getResponseBody().write(bytes, 1, bytes.length - 1);
                        } else if (bytes.length > 0) exchange.getResponseBody().write(bytes);
                    } catch (IOException disconnected) {
                        // Cancellation is the expected client behavior for stalled/oversize bodies.
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    } finally {
                        exchange.close();
                    }
                });
                server.start();
            } catch (IOException unavailable) {
                throw new IllegalStateException("Local transport test server unavailable");
            }
        }

        SlackHttpTransport transport(ObjectMapper json) { return new SlackHttpTransport(json, uri("/api/")); }
        URI uri(String path) { return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path); }
        void reply(int status, String body) { reply(status, body, Map.of()); }
        void reply(int status, String body, Map<String, String> headers) { responses.add(new Response(status, body, headers, 0)); }
        void stall(String body, long millis) { responses.add(new Response(200, body, Map.of(), millis)); }
        List<Request> requests() { return List.copyOf(requests); }
        public void close() { server.stop(0); executor.shutdownNow(); }
        private record Response(int status, String body, Map<String, String> headers, long stallMillis) {}
        private record Request(String method, String path, String authorization, String body) {
            @Override public String toString() { return "TestRequest[" + method + " " + path + "]"; }
        }
    }
}
