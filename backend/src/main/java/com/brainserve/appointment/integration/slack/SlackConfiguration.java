package com.brainserve.appointment.integration.slack;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.net.URI;

/** Fixed deployment link; channel credentials never select a network destination. */
@Component
public class SlackConfiguration {
    private final String arrivalLink;
    public SlackConfiguration(@Value("${brainserve.integrations.slack.app-base-url:}") String base) {
        String link = "";
        try {
            URI uri = URI.create(base);
            if ("https".equals(uri.getScheme()) && uri.getHost() != null && uri.getUserInfo() == null
                    && uri.getQuery() == null && uri.getFragment() == null && (uri.getPath().isEmpty() || uri.getPath().equals("/")))
                link = uri.resolve("/").toString();
        } catch (IllegalArgumentException ignored) { /* Unconfigured is an explicit supported state. */ }
        arrivalLink = link;
    }
    public boolean configured() { return !arrivalLink.isEmpty(); }
    public String arrivalLink() { return arrivalLink; }
}
