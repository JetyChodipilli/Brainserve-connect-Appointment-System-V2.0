package com.brainserve.appointment.configuration.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/public/company-profile")
public class PublicCompanyProfileController {
    private final WorkspacePolicy policy;

    public PublicCompanyProfileController(WorkspacePolicy policy) { this.policy = policy; }

    @GetMapping
    CompanyProfile profile() {
        return new CompanyProfile(policy.stringValue("COMPANY.NAME", "BrainServe Connect"),
                policy.stringValue("COMPANY.EMAIL_DOMAIN", "brainserve.in"),
                policy.stringValue("COMPANY.HQ_ADDRESS", "Hyderabad, Telangana, India"),
                policy.stringValue("COMPANY.SUPPORT_EMAIL", "support@brainserve.in"),
                policy.stringValue("PRIVACY.CONSENT_VERSION", "2026.1"));
    }

    public record CompanyProfile(String name, String emailDomain, String hqAddress, String supportEmail,
                                 String consentVersion) {}
}
