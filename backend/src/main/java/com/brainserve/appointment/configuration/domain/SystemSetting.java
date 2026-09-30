package com.brainserve.appointment.configuration.domain;

import com.brainserve.appointment.shared.domain.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "system_setting")
public class SystemSetting extends AuditableEntity {
    @Column(name = "setting_key", nullable = false, unique = true, length = 120)
    private String key;
    @Column(name = "setting_value", nullable = false, length = 2000)
    private String value;
    @Column(name = "value_type", nullable = false, length = 20)
    private String valueType;
    @Column(nullable = false, length = 500)
    private String description;

    protected SystemSetting() {}
    public SystemSetting(String key, String value, String valueType, String description) {
        this.key = key; this.value = value; this.valueType = valueType; this.description = description;
    }
    public void update(String value) { this.value = value; }
    public String getKey() { return key; }
    public String getValue() { return value; }
    public String getValueType() { return valueType; }
    public String getDescription() { return description; }
}
