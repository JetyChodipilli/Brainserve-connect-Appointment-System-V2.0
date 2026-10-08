package com.brainserve.appointment.appointment.api;

import com.brainserve.appointment.appointment.application.GroupVisitService;
import com.brainserve.appointment.appointment.domain.AppointmentType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/appointments/groups")
@PreAuthorize("hasAuthority('VISITOR_REGISTER')")
public class GroupVisitController {
    private final GroupVisitService service;
    public GroupVisitController(GroupVisitService service) { this.service=service; }
    @GetMapping public ResponseEntity<?> list(Authentication auth) {
        return ResponseEntity.ok().header("Cache-Control","no-store").body(service.list(UUID.fromString(auth.getName())));
    }
    @PostMapping public ResponseEntity<?> create(Authentication auth,@Valid @RequestBody Request request) {
        return ResponseEntity.ok().header("Cache-Control","no-store").body(service.create(UUID.fromString(auth.getName()),request));
    }
    public record Member(@NotBlank @Size(max=170) String visitorName,@NotBlank @Email @Size(max=180) String visitorEmail,
                         @NotBlank @Size(max=30) String visitorPhone,@Size(max=160) String visitorCompany) {}
    public record Request(@NotNull UUID requestId,@NotBlank @Size(max=120) String label,@NotNull AppointmentType type,
                          @NotNull UUID hostEmployeeId,UUID routingDepartmentId,UUID requestedEmployeeId,
                          @NotNull Instant slotStart,@NotNull Instant slotEnd,@NotBlank @Size(max=1000) String purpose,
                          @NotNull @Size(min=2,max=50) List<@NotNull @Valid Member> members) {}
}
