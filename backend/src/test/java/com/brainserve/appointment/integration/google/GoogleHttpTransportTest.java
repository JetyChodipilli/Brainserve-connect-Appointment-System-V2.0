package com.brainserve.appointment.integration.google;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class GoogleHttpTransportTest {
    @Test void nativeClientRefusesRedirectsAndNeverForwardsCredentialToRedirectTarget() {
        try(var server=new GoogleCalendarTestServer()) {
            server.reply(302,"private-provider-error",Map.of("Location",server.uri("/stolen").toString()));
            var reply=server.transport(new ObjectMapper()).token(Map.of("refresh_token","private-refresh"));
            assertThat(reply.status()).isEqualTo(302);assertThat(reply.body()).isNull();assertThat(server.requests()).hasSize(1);
            assertThat(server.requests().getFirst().body()).contains("refresh_token=private-refresh");
            assertThat(reply.toString()).doesNotContain("private");
        }
    }
    @Test void completeBodyDeadlineCancelsAnAlreadyStartedStalledResponse() {
        assertTimeoutPreemptively(Duration.ofSeconds(7),()->{
            try(var server=new GoogleCalendarTestServer()) {
                server.stall("{\"access_token\":\"private-token\"}",10000);
                Instant began=Instant.now();var reply=server.transport(new ObjectMapper()).token(Map.of("code","private-code"));
                assertThat(reply.status()).isZero();assertThat(Duration.between(began,Instant.now())).isLessThan(Duration.ofSeconds(6));
                assertThat(reply.body()).isNull();assertThat(server.requests()).hasSize(1);
            }
        });
    }
    @Test void responseAndRequestByteCapsAreRealAndErrorBodiesAreNeverParsed() {
        try(var server=new GoogleCalendarTestServer()) {
            var transport=server.transport(new ObjectMapper());
            server.reply(200,"{\"secret\":\""+"x".repeat(70000)+"\"}");
            assertThat(transport.token(Map.of("code","a")).status()).isZero();
            assertThat(transport.token(Map.of("code","界".repeat(20000))).status()).isZero();assertThat(server.requests()).hasSize(1);
            server.reply(500,"{\"error\":\"private token purpose name\"}");
            var unavailable=transport.token(Map.of("code","b"));assertThat(unavailable.body()).isNull();assertThat(unavailable.status()).isEqualTo(500);
        }
    }
    @Test void calendarPathIsEncodedEtagsAndNoInvitationFlagAreSentAndRetryHintsBounded() {
        try(var server=new GoogleCalendarTestServer()) {
            server.reply(429,"private error",Map.of("Retry-After","999999"));
            var reply=server.transport(new ObjectMapper()).calendar("PUT","app@group.calendar.google.com","bs12345","private-access",Map.of("summary","BrainServe appointment"),"\"revision-3\"");
            var request=server.requests().getFirst();assertThat(request.path()).contains("app%40group.calendar.google.com/events/bs12345?sendUpdates=none");
            assertThat(request.authorization()).isEqualTo("Bearer private-access");assertThat(request.ifMatch()).isEqualTo("\"revision-3\"");
            assertThat(reply.retryAfterSeconds()).isEqualTo(3600);assertThat(reply.body()).isNull();
        }
    }
    @Test void deploymentConfigurationCannotOverrideFixedEndpointsOrUseAnInsecureCallback() {
        org.assertj.core.api.Assertions.assertThatThrownBy(()->new GoogleCalendarConfiguration("id","secret","http://attacker.test/api/v1/integrations/google-calendar/callback")).isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(()->new GoogleCalendarConfiguration("id","secret","https://user@brainserve.test/api/v1/integrations/google-calendar/callback")).isInstanceOf(IllegalArgumentException.class);
        assertThat(new GoogleCalendarConfiguration("","","").configured()).isFalse();
        var config=new GoogleCalendarConfiguration("id","secret","https://brainserve.test/api/v1/integrations/google-calendar/callback");
        assertThat(config.authorizeUri()).isEqualTo("https://brainserve.test/api/v1/integrations/google-calendar/authorize");
    }
    @Test void tokenScopeExpirySizeAndCalendarMarkerValidationFailClosed() throws Exception {
        ObjectMapper json=new ObjectMapper();
        var valid=json.readTree("{\"access_token\":\"private-access\",\"refresh_token\":\"private-refresh\",\"token_type\":\"Bearer\",\"expires_in\":3600,\"scope\":\""+GoogleCalendarConfiguration.SCOPE+"\"}");
        assertThat(GoogleTokenCodec.response(valid,null,true)).isNotNull();
        ((com.fasterxml.jackson.databind.node.ObjectNode)valid).put("scope","https://www.googleapis.com/auth/calendar");assertThat(GoogleTokenCodec.response(valid,null,true)).isNull();
        assertThat(GoogleCalendarAdapter.validCalendarId("https://evil.test/calendar")).isFalse();
        assertThat(GoogleCalendarAdapter.validCalendarId("valid@group.calendar.google.com")).isTrue();
    }
}
