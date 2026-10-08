package com.brainserve.appointment.integration.google;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.net.URI;

/** The deployment callback is configuration, never a URL supplied by an operator. */
@Component
public final class GoogleCalendarConfiguration {
    public static final String SCOPE = "https://www.googleapis.com/auth/calendar.app.created";
    public static final String ROOT = "/api/v1/integrations/google-calendar";
    private final String clientId, clientSecret, redirectUri;
    public GoogleCalendarConfiguration(
            @Value("${brainserve.integrations.google-calendar.client-id:}") String clientId,
            @Value("${brainserve.integrations.google-calendar.client-secret:}") String clientSecret,
            @Value("${brainserve.integrations.google-calendar.redirect-uri:}") String redirectUri) {
        this.clientId = clientId; this.clientSecret = clientSecret; this.redirectUri = redirectUri;
        if (!clientId.isBlank() || !clientSecret.isBlank() || !redirectUri.isBlank()) {
            URI uri;
            try { uri = URI.create(redirectUri); }
            catch (RuntimeException invalid) { throw new IllegalArgumentException("Google Calendar callback configuration is invalid"); }
            if (clientId.isBlank() || clientId.length()>512 || clientSecret.isBlank() || clientSecret.length()>4096
                    || clientId.chars().anyMatch(Character::isISOControl) || clientSecret.chars().anyMatch(Character::isISOControl)
                    || !"https".equals(uri.getScheme()) || uri.getHost()==null || uri.getUserInfo()!=null
                    || uri.getPort()!=-1 || !uri.getPath().equals(ROOT+"/callback") || uri.getQuery()!=null || uri.getFragment()!=null)
                throw new IllegalArgumentException("Google Calendar requires credentials and an HTTPS callback at the fixed callback path");
        }
    }
    public boolean configured() { return !clientId.isBlank(); }
    String clientId() { return clientId; }
    String clientSecret() { return clientSecret; }
    String redirectUri() { return redirectUri; }
    String authorizeUri() { return redirectUri.substring(0,redirectUri.length()-"callback".length())+"authorize"; }
}
