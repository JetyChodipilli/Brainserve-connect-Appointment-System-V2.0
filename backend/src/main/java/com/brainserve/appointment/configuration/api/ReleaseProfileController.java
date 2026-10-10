package com.brainserve.appointment.configuration.api;

import com.brainserve.appointment.configuration.application.ReleaseProfileService;
import com.brainserve.appointment.shared.application.BusinessException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/release-profile")
@PreAuthorize("hasRole('SYSTEM_ADMIN') and hasAuthority('SYSTEM_CONFIGURE')")
public class ReleaseProfileController {
    private static final int MAX_BODY_BYTES = 8192;
    private final ReleaseProfileService service;
    private final ObjectMapper mapper;
    public ReleaseProfileController(ReleaseProfileService service, ObjectMapper mapper) { this.service = service; this.mapper = mapper; }
    @GetMapping
    ResponseEntity<ReleaseProfileModels.View> read() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.read());
    }
    @PutMapping(consumes = {MediaType.APPLICATION_JSON_VALUE, "application/*+json"})
    @Operation(requestBody = @RequestBody(required = true, content = @Content(
            mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ReleaseProfileModels.Write.class))))
    ResponseEntity<ReleaseProfileModels.View> update(HttpServletRequest servletRequest) {
        if (servletRequest.getContentLengthLong() > MAX_BODY_BYTES) throw tooLarge();
        JsonNode request;
        try {
            byte[] body = servletRequest.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
            if (body.length > MAX_BODY_BYTES) throw tooLarge();
            request = mapper.readerFor(JsonNode.class).with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readValue(body);
        } catch (java.io.IOException exception) { throw invalid(); }
        if (request == null || !request.isObject() || !request.path("expectedVersion").isIntegralNumber() || !request.path("expectedVersion").canConvertToLong()) invalid();
        JsonNode profile = request.path("profile");
        if (!profile.isObject()) invalid();
        for (String field : java.util.List.of("status", "reference", "supportOwner", "supportEmail", "supportHours")) {
            if (!profile.path(field).isTextual()) invalid();
        }
        for (String field : java.util.List.of("startsOn", "renewsOn")) {
            JsonNode date = profile.path(field);
            if (!date.isNull() && (!date.isTextual() || !date.textValue().matches("\\d{4}-\\d{2}-\\d{2}"))) invalid();
        }
        try {
            var command = mapper.readerFor(ReleaseProfileModels.Write.class)
                    .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .with(DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
                    .without(DeserializationFeature.ACCEPT_FLOAT_AS_INT).<ReleaseProfileModels.Write>readValue(request);
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.update(command));
        } catch (java.io.IOException exception) { throw invalid(); }
    }
    private BusinessException tooLarge() {
        return new BusinessException("RELEASE_PROFILE_TOO_LARGE", "Keep the agreement record within 8 KiB", HttpStatus.PAYLOAD_TOO_LARGE);
    }
    private BusinessException invalid() {
        throw new BusinessException("RELEASE_PROFILE_INVALID", "Use the current version and supported agreement fields", org.springframework.http.HttpStatus.BAD_REQUEST);
    }
}
