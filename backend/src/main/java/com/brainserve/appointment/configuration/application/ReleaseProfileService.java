package com.brainserve.appointment.configuration.application;

import com.brainserve.appointment.audit.api.AuditService;
import com.brainserve.appointment.configuration.api.ReleaseProfileModels;
import com.brainserve.appointment.configuration.api.ReleaseProfileModels.Profile;
import com.brainserve.appointment.configuration.api.ReleaseProfileModels.View;
import com.brainserve.appointment.configuration.domain.ReleaseProfile;
import com.brainserve.appointment.configuration.infrastructure.ReleaseProfileRepository;
import com.brainserve.appointment.shared.application.BusinessException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDate;
import java.time.ZoneId;

/** Commercial records are descriptive; operational authorization never reads this record. */
@Service
public class ReleaseProfileService {
    public static final java.util.UUID ID = java.util.UUID.fromString("00000000-0000-0000-0000-000000000271");
    private final ReleaseProfileRepository settings;
    private final ObjectMapper mapper;
    private final Validator validator;
    private final AuditService audit;
    private final ZoneId zone;
    public ReleaseProfileService(ReleaseProfileRepository settings, ObjectMapper mapper, Validator validator,
                                 AuditService audit, @Value("${brainserve.appointment.office-zone:Asia/Kolkata}") String officeZone) {
        this.settings = settings; this.mapper = mapper; this.validator = validator; this.audit = audit; this.zone = ZoneId.of(officeZone);
    }
    @Transactional(readOnly = true)
    public View read() { return view(require(false)); }

    @Transactional
    public View update(ReleaseProfileModels.Write request) {
        if (request == null || !validator.validate(request).isEmpty()) invalid();
        Profile profile = normalize(request.profile());
        ReleaseProfile setting = require(true);
        if (setting.getVersion() != request.expectedVersion()) {
            throw new BusinessException("RELEASE_PROFILE_CHANGED", "Reload the current release profile before saving", HttpStatus.CONFLICT);
        }
        try {
            String value = mapper.writeValueAsString(profile);
            if (value.length() > 2000) invalid();
            if (value.equals(setting.getJson())) return view(setting);
            setting.update(value);
            settings.flush();
            audit.record("RELEASE_PROFILE_UPDATED", "RELEASE_PROFILE", ID.toString(), "{\"changed\":true}");
            return view(setting);
        } catch (JsonProcessingException exception) {
            throw new BusinessException("RELEASE_PROFILE_INVALID", "The release profile could not be encoded", HttpStatus.BAD_REQUEST);
        }
    }
    private ReleaseProfile require(boolean lock) {
        return (lock ? settings.findForUpdate(ID) : settings.findById(ID)).orElseThrow(() ->
                new BusinessException("RELEASE_PROFILE_UNAVAILABLE", "Release configuration is unavailable", HttpStatus.SERVICE_UNAVAILABLE));
    }
    private View view(ReleaseProfile setting) {
        try {
            Profile profile = normalize(mapper.readerFor(Profile.class)
                    .with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).readValue(setting.getJson()));
            LocalDate today = LocalDate.now(zone);
            boolean due = profile.renewsOn() != null && !profile.renewsOn().isAfter(today)
                    && (profile.status() == ReleaseProfileModels.Status.ACTIVE || profile.status() == ReleaseProfileModels.Status.PILOT);
            return new View(setting.getVersion(), profile, zone.getId(), today, due);
        } catch (JsonProcessingException | BusinessException exception) {
            throw new BusinessException("RELEASE_PROFILE_UNAVAILABLE", "Release configuration needs an operator review", HttpStatus.SERVICE_UNAVAILABLE);
        }
    }
    private Profile normalize(Profile value) {
        if (value == null || !validator.validate(value).isEmpty()) invalid();
        Profile profile = new Profile(value.status(), value.reference().trim(), value.startsOn(), value.renewsOn(),
                value.supportOwner().trim(), value.supportEmail().trim(), value.supportHours().trim());
        boolean recordedTerm = profile.status() == ReleaseProfileModels.Status.ACTIVE || profile.status() == ReleaseProfileModels.Status.PILOT;
        if (recordedTerm && (profile.reference().isEmpty() || profile.supportOwner().isEmpty() || profile.supportEmail().isEmpty()
                || profile.supportHours().isEmpty() || profile.startsOn() == null || profile.renewsOn() == null)) invalid();
        if ((profile.startsOn() == null) != (profile.renewsOn() == null)
                || profile.startsOn() != null && profile.renewsOn().isBefore(profile.startsOn())) invalid();
        return profile;
    }
    private void invalid() {
        throw new BusinessException("RELEASE_PROFILE_INVALID", "Check agreement dates and required support details for an active or pilot term", HttpStatus.BAD_REQUEST);
    }
}
