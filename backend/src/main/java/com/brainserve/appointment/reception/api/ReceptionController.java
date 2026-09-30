package com.brainserve.appointment.reception.api;

import com.brainserve.appointment.reception.application.ReceptionService;
import com.brainserve.appointment.reception.domain.VisitAccessRecord;
import com.brainserve.appointment.appointment.api.VisitorPassVerification;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import jakarta.validation.constraints.Pattern;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/reception")
public class ReceptionController {
    private final ReceptionService service;
    private final VisitorPassVerification visitorPasses;
    public ReceptionController(ReceptionService service, VisitorPassVerification visitorPasses) {
        this.service = service; this.visitorPasses = visitorPasses;
    }

    @PostMapping("/appointments/{appointmentId}/check-in")
    @PreAuthorize("hasAuthority('VISITOR_CHECK_IN')")
    AccessResponse checkIn(@PathVariable UUID appointmentId, Authentication authentication) {
        return AccessResponse.from(service.checkIn(appointmentId, authentication.getName()));
    }
    @PostMapping("/appointments/reference/{referenceNumber}/check-in")
    @PreAuthorize("hasAuthority('VISITOR_CHECK_IN')")
    AccessResponse checkInByReference(
            @PathVariable @Pattern(regexp = "BSA-[A-Z0-9]{4}-[A-Z0-9]{4}") String referenceNumber,
            Authentication authentication) {
        return AccessResponse.from(service.checkInByReference(referenceNumber, authentication.getName()));
    }
    @PostMapping("/access-records/{recordId}/check-out")
    @PreAuthorize("hasAuthority('VISITOR_CHECK_OUT')")
    AccessResponse checkOut(@PathVariable UUID recordId) { return AccessResponse.from(service.checkOut(recordId)); }
    @GetMapping({"/visitors-inside", "/emergency-list"})
    @PreAuthorize("hasAnyAuthority('VISITOR_CHECK_IN','VISITOR_CHECK_OUT','VISITOR_OCCUPANCY_READ')")
    List<AccessResponse> inside() { return service.inside().stream().map(AccessResponse::from).toList(); }

    @PostMapping("/passes/verify")
    @PreAuthorize("hasAnyAuthority('QR_PASS_VERIFY','VISITOR_VERIFY')")
    VisitorPassVerification.VerifiedPass verifyPass(@Valid @org.springframework.web.bind.annotation.RequestBody PassRequest request) {
        return visitorPasses.verify(request.token());
    }

    @PostMapping("/passes/check-in")
    @PreAuthorize("hasAuthority('VISITOR_CHECK_IN')")
    AccessResponse checkInWithPass(@Valid @org.springframework.web.bind.annotation.RequestBody PassRequest request,
                                   Authentication authentication) {
        var pass = visitorPasses.verify(request.token());
        return AccessResponse.from(service.checkInByReference(pass.referenceNumber(), authentication.getName()));
    }

    public record PassRequest(@NotBlank @Size(max = 500) String token) {}

    public record AccessResponse(UUID id, UUID appointmentId, String visitorName, String badgeNumber,
                                 Instant checkedInAt, Instant checkedOutAt, String processedBy) {
        static AccessResponse from(VisitAccessRecord value) { return new AccessResponse(value.getId(), value.getAppointmentId(),
                value.getVisitorName(), value.getBadgeNumber(), value.getCheckedInAt(), value.getCheckedOutAt(), value.getProcessedBy()); }
    }
}
