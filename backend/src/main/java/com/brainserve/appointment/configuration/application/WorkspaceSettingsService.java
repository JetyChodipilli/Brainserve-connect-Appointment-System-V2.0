package com.brainserve.appointment.configuration.application;

import com.brainserve.appointment.audit.api.AuditService;
import com.brainserve.appointment.configuration.api.WorkspacePolicy;
import com.brainserve.appointment.configuration.domain.SystemSetting;
import com.brainserve.appointment.configuration.infrastructure.SystemSettingRepository;
import com.brainserve.appointment.shared.application.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class WorkspaceSettingsService implements WorkspacePolicy {
    private final SystemSettingRepository settings;
    private final AuditService audit;

    public WorkspaceSettingsService(SystemSettingRepository settings, AuditService audit) {
        this.settings = settings;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<SystemSetting> list() {
        return settings.findAll(org.springframework.data.domain.Sort.by("key"));
    }

    @Transactional
    public SystemSetting update(String key, String value) {
        SystemSetting setting = require(key);
        validate(setting.getValueType(), value);
        String normalized = value.trim();
        validateKey(key, normalized);
        setting.update(normalized);
        audit.record("WORKSPACE_SETTING_UPDATED", "SETTING", key, "{\"changed\":true}");
        return setting;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean booleanValue(String key, boolean fallback) {
        return settings.findByKey(key).map(SystemSetting::getValue).map(Boolean::parseBoolean).orElse(fallback);
    }

    @Override
    @Transactional(readOnly = true)
    public int integerValue(String key, int fallback) {
        return settings.findByKey(key).map(SystemSetting::getValue).map(value -> {
            try { return Integer.parseInt(value); } catch (NumberFormatException ignored) { return fallback; }
        }).orElse(fallback);
    }

    @Override
    @Transactional(readOnly = true)
    public String stringValue(String key, String fallback) {
        return settings.findByKey(key).map(SystemSetting::getValue).orElse(fallback);
    }

    private SystemSetting require(String key) {
        return settings.findByKey(key).orElseThrow(() -> new BusinessException(
                "SETTING_NOT_FOUND", "Workspace setting was not found", HttpStatus.NOT_FOUND));
    }

    private void validate(String type, String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty() || normalized.length() > 2000) {
            throw new BusinessException("INVALID_SETTING_VALUE", "Setting value is required and cannot exceed 2000 characters", HttpStatus.BAD_REQUEST);
        }
        try {
            if (type.equals("INTEGER")) Integer.parseInt(normalized);
            if (type.equals("BOOLEAN") && !normalized.equalsIgnoreCase("true") && !normalized.equalsIgnoreCase("false")) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException ex) {
            throw new BusinessException("INVALID_SETTING_VALUE", "Value does not match setting type " + type, HttpStatus.BAD_REQUEST);
        }
    }

    private void validateKey(String key, String value) {
        if (key.equals("COMPANY.EMAIL_DOMAIN")
                && !value.toLowerCase(java.util.Locale.ROOT).matches("^(?!-)[a-z0-9-]+(\\.[a-z0-9-]+)+$")) {
            invalid("Company email domain must be a valid domain such as brainserve.in");
        }
        if (key.equals("COMPANY.SUPPORT_EMAIL")
                && !value.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
            invalid("Company support email must be valid");
        }
        if (!key.startsWith("APPOINTMENT.")) return;
        int number;
        try { number = Integer.parseInt(value); }
        catch (NumberFormatException ex) { return; }
        int minimum = switch (key) {
            case "APPOINTMENT.SLOT_MINUTES" -> 10;
            case "APPOINTMENT.MAX_ADVANCE_DAYS" -> 1;
            default -> 0;
        };
        int maximum = switch (key) {
            case "APPOINTMENT.SLOT_MINUTES" -> 240;
            case "APPOINTMENT.MAX_ADVANCE_DAYS" -> 365;
            case "APPOINTMENT.MIN_LEAD_MINUTES" -> 1440;
            case "APPOINTMENT.CHECK_IN_EARLY_MINUTES" -> 240;
            case "APPOINTMENT.QR_EXPIRY_MINUTES_AFTER_END" -> 2880;
            default -> Integer.MAX_VALUE;
        };
        if (number < minimum || number > maximum) {
            invalid("Setting " + key + " must be between " + minimum + " and " + maximum);
        }
    }

    private void invalid(String message) {
        throw new BusinessException("INVALID_SETTING_VALUE", message, HttpStatus.BAD_REQUEST);
    }
}
