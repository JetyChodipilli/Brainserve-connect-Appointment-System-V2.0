package com.brainserve.appointment.appointment.api;

import com.brainserve.appointment.appointment.application.AppointmentService;
import com.brainserve.appointment.appointment.application.VisitorPassService;
import com.brainserve.appointment.appointment.domain.Appointment;
import com.brainserve.appointment.appointment.domain.AppointmentStatus;
import com.brainserve.appointment.appointment.domain.AppointmentType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/public/appointments")
public class PublicAppointmentController {
    private final AppointmentService service;
    private final VisitorPassService visitorPasses;
    public PublicAppointmentController(AppointmentService service, VisitorPassService visitorPasses) {
        this.service = service; this.visitorPasses = visitorPasses;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    PublicAppointmentResponse create(@RequestHeader("Idempotency-Key") @Size(min = 8, max = 100) String idempotencyKey,
                                     @Valid @RequestBody PublicAppointmentRequest request) {
        return PublicAppointmentResponse.from(service.request(idempotencyKey, new AppointmentService.CreateAppointment(
                request.type(), request.visitorName(), request.visitorEmail(), request.visitorPhone(), request.visitorCompany(),
                request.hostEmployeeId(), request.routingDepartmentId(), request.requestedEmployeeId(),
                request.slotStart(), request.slotEnd(), request.purpose())));
    }

    @GetMapping("/{reference}")
    PublicAppointmentResponse track(@PathVariable @Pattern(regexp = "BSA-[A-Z0-9]{4}-[A-Z0-9]{4}") String reference) {
        return PublicAppointmentResponse.from(service.byReference(reference));
    }

    @PostMapping("/{reference}/verify-otp")
    PublicAppointmentResponse verify(@PathVariable String reference, @Valid @RequestBody OtpRequest request) {
        return PublicAppointmentResponse.from(service.verify(reference, request.otp()));
    }

    @PostMapping("/{reference}/cancel/request-otp")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void requestCancellationOtp(
            @PathVariable @Pattern(regexp = "BSA-[A-Z0-9]{4}-[A-Z0-9]{4}") String reference) {
        service.requestCancellationOtp(reference);
    }

    @PostMapping("/{reference}/cancel")
    PublicAppointmentResponse cancel(
            @PathVariable @Pattern(regexp = "BSA-[A-Z0-9]{4}-[A-Z0-9]{4}") String reference,
            @Valid @RequestBody OtpRequest request) {
        return PublicAppointmentResponse.from(service.cancelPublic(reference, request.otp()));
    }

    @GetMapping("/{reference}/pass")
    VisitorPassService.VisitorPass visitorPass(
            @PathVariable @Pattern(regexp = "BSA-[A-Z0-9]{4}-[A-Z0-9]{4}") String reference) {
        return visitorPasses.issue(reference);
    }

    public record PublicAppointmentRequest(@NotNull AppointmentType type, @NotBlank @Size(max = 170) String visitorName,
                                           @NotBlank @Email @Size(max = 180) String visitorEmail,
                                           @NotBlank @Size(max = 30) String visitorPhone,
                                           @Size(max = 160) String visitorCompany, @NotNull UUID hostEmployeeId,
                                           UUID routingDepartmentId, UUID requestedEmployeeId,
                                           @NotNull @Future Instant slotStart, @NotNull @Future Instant slotEnd,
                                           @NotBlank @Size(max = 1000) String purpose) {}
    public record OtpRequest(@NotBlank @Pattern(regexp = "\\d{6}") String otp) {}
    public record PublicAppointmentResponse(String referenceNumber, AppointmentType type, AppointmentStatus status,
                                            String hostReference, Instant slotStart, Instant slotEnd, String visitorDisplayName) {
        static PublicAppointmentResponse from(Appointment value) {
            String maskedName = value.getVisitorName().isBlank() ? "Visitor" : value.getVisitorName().charAt(0) + "***";
            return new PublicAppointmentResponse(value.getReferenceNumber(), value.getType(), value.getStatus(),
                    value.getHostEmployeeId().toString(), value.getSlotStart(), value.getSlotEnd(), maskedName);
        }
    }
}
