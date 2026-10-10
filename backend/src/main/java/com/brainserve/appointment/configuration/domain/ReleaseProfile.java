package com.brainserve.appointment.configuration.domain;

import com.brainserve.appointment.shared.domain.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "release_profile")
public class ReleaseProfile extends AuditableEntity {
    @Column(name = "profile_json", nullable = false, length = 2000)
    private String json;
    protected ReleaseProfile() {}
    public String getJson() { return json; }
    public void update(String value) { json = value; }
}
