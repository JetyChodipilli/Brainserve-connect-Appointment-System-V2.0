package com.brainserve.appointment.integration.google;

import com.brainserve.appointment.shared.application.SensitiveStringConverter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import java.time.Instant;

@Component
final class GoogleTokenCodec {
    private final SensitiveStringConverter secrets;
    private final ObjectMapper json;
    GoogleTokenCodec(SensitiveStringConverter secrets,ObjectMapper json) { this.secrets=secrets;this.json=json; }
    String encrypt(String value) { return secrets.convertToDatabaseColumn(value); }
    String decrypt(String value) { return secrets.convertToEntityAttribute(value); }
    String encode(Tokens tokens) {
        try { return encrypt(json.writeValueAsString(tokens)); }
        catch(Exception invalid) { throw new IllegalStateException("Provider credential storage failed"); }
    }
    Tokens decode(String ciphertext) {
        try {
            Tokens value=json.readValue(decrypt(ciphertext),Tokens.class);
            return value!=null && token(value.access()) && (value.refresh()==null||token(value.refresh())) && value.expiresAt()!=null?value:null;
        }
        catch(Exception invalid) { return null; }
    }
    static Tokens response(JsonNode body,String previousRefresh,boolean requireScope) {
        if(body==null || !"Bearer".equalsIgnoreCase(body.path("token_type").asText())
                || !token(body.path("access_token").asText()))return null;
        String refresh=body.has("refresh_token")?body.path("refresh_token").asText():previousRefresh;
        if(refresh!=null && !token(refresh))return null;
        JsonNode expiry=body.path("expires_in");
        if(!expiry.isIntegralNumber() || !expiry.canConvertToLong() || expiry.asLong()<60 || expiry.asLong()>86400)return null;
        if((requireScope || body.has("scope")) && !GoogleCalendarConfiguration.SCOPE.equals(body.path("scope").asText().strip()))return null;
        return new Tokens(body.path("access_token").asText(),refresh,Instant.now().plusSeconds(expiry.asLong()));
    }
    static boolean token(String value) { return value!=null && value.matches("[A-Za-z0-9._~+/-]{1,4096}"); }
    record Tokens(String access,String refresh,Instant expiresAt) { @Override public String toString() { return "GoogleTokens[REDACTED]"; } }
}
