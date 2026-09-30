package com.brainserve.appointment.iam.application;

import com.brainserve.appointment.shared.application.BusinessException;
import com.brainserve.appointment.configuration.api.WorkspacePolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.Locale;

@Component
public class CompanyEmailPolicy {
    private final String configuredDomain;
    private final WorkspacePolicy workspacePolicy;

    public CompanyEmailPolicy(@Value("${brainserve.identity.company-email-domain}") String domain,
                              WorkspacePolicy workspacePolicy) {
        this.configuredDomain = normalizeDomain(domain);
        this.workspacePolicy = workspacePolicy;
        if (this.configuredDomain.isBlank() || !this.configuredDomain.contains(".")) {
            throw new IllegalStateException("brainserve.identity.company-email-domain must be a valid company domain");
        }
    }

    public String requireCompanyEmail(String email) {
        String domain = domain();
        String normalized = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        if (!normalized.matches("^[^@\\s]+@" + java.util.regex.Pattern.quote(domain) + "$")) {
            throw new BusinessException("COMPANY_EMAIL_REQUIRED",
                    "Use an official company email ending in @" + domain, HttpStatus.UNPROCESSABLE_ENTITY);
        }
        return normalized;
    }

    public String domain() {
        String dynamic = normalizeDomain(workspacePolicy.stringValue("COMPANY.EMAIL_DOMAIN", configuredDomain));
        return dynamic.isBlank() || !dynamic.contains(".") ? configuredDomain : dynamic;
    }

    private String normalizeDomain(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replaceFirst("^@", "");
    }
}
