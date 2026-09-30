package com.brainserve.appointment.organization.domain;

import com.brainserve.appointment.shared.domain.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "org_department")
public class Department extends AuditableEntity {
    @Column(nullable = false, unique = true, length = 20)
    private String code;
    @Column(nullable = false, length = 120)
    private String name;
    @Column(nullable = false)
    private boolean active = true;

    protected Department() {}
    public Department(String code, String name) {
        this.code = code.trim().toUpperCase(); this.name = name.trim();
    }
    public String getCode() { return code; }
    public String getName() { return name; }
    public boolean isActive() { return active; }
    public void update(String name) { this.name = name.trim(); }
    public void changeStatus(boolean active) { this.active = active; }
}
