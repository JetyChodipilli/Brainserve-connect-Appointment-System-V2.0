package com.brainserve.appointment.visitor.domain;

import com.brainserve.appointment.shared.domain.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "visitor")
public class Visitor extends AuditableEntity {
    @Column(name = "idempotency_key", nullable = false, unique = true, updatable = false, length = 100)
    private String idempotencyKey;
    @Column(nullable = false, length = 170)
    private String name;
    @Column(nullable = false, length = 180)
    private String email;
    @Column(nullable = false, length = 30)
    private String phone;
    @Column(length = 160)
    private String company;
    @Convert(converter = SensitiveStringConverter.class)
    @Column(name = "government_id_encrypted", length = 1000)
    private String governmentId;
    @Column(name = "government_id_last4", length = 4)
    private String governmentIdLast4;
    @Column(name = "identity_verified", nullable = false)
    private boolean identityVerified;
    @Column(name = "consent_version", nullable = false, length = 40)
    private String consentVersion;
    @Column(name = "consented_at", nullable = false)
    private Instant consentedAt;
    @Column(nullable = false)
    private boolean restricted;

    protected Visitor() {}
    public Visitor(String idempotencyKey, String name, String email, String phone, String company,
                   String governmentId, String consentVersion) {
        this.idempotencyKey = idempotencyKey;
        this.name = name.trim(); this.email = email.trim().toLowerCase(); this.phone = normalizePhone(phone);
        this.company = company; this.governmentId = governmentId;
        this.governmentIdLast4 = governmentId == null || governmentId.length() < 4 ? null : governmentId.substring(governmentId.length() - 4);
        this.consentVersion = consentVersion; this.consentedAt = Instant.now();
    }
    public void verify() { identityVerified = true; }
    public void restrict() { restricted = true; }
    public String getName() { return name; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getEmail() { return email; }
    public String getPhone() { return phone; }
    public String getCompany() { return company; }
    public String getGovernmentIdLast4() { return governmentIdLast4; }
    public boolean isIdentityVerified() { return identityVerified; }
    public String getConsentVersion() { return consentVersion; }
    public Instant getConsentedAt() { return consentedAt; }
    public boolean isRestricted() { return restricted; }
    private String normalizePhone(String value) { return value.replaceAll("[^+0-9]", ""); }
}
