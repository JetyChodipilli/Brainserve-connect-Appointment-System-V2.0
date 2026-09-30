package com.brainserve.appointment.appointment.api;

import com.brainserve.appointment.appointment.application.AppointmentService;
import com.brainserve.appointment.appointment.domain.Appointment;
import com.brainserve.appointment.appointment.domain.AppointmentStatus;
import com.brainserve.appointment.appointment.domain.AppointmentType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Pattern;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;

@RestController
@RequestMapping("/api/v1/appointments")
public class AppointmentController {
    private final AppointmentService service;
    private final ZoneId officeZone;
    public AppointmentController(AppointmentService service,
                                 @Value("${brainserve.appointment.office-zone}") String officeZone) {
        this.service = service;
        this.officeZone = ZoneId.of(officeZone);
    }

    @GetMapping
    @PreAuthorize("hasAnyAuthority('APPOINTMENT_APPROVE','VISITOR_REGISTER','SECURITY_VISITOR_INTAKE',"
            + "'RECEPTION_VISIT_VERIFY','HR_VISIT_APPROVE','TEAM_LEAD_VISIT_APPROVE',"
            + "'MANAGER_VISIT_APPROVE','CEO_VISIT_APPROVE')")
    Page<AppointmentResponse> list(@AuthenticationPrincipal Jwt jwt, Authentication authentication,
                                   @RequestParam(required = false) LocalDate date,
                                   Pageable pageable) {
        UUID employeeId = employeeId(jwt);
        LocalDate effectiveDate = date == null ? LocalDate.now(officeZone) : date;
        Instant from = effectiveDate.atStartOfDay(officeZone).toInstant();
        Instant to = effectiveDate.plusDays(1).atStartOfDay(officeZone).toInstant();
        boolean hrView = authentication.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("HR_VISIT_APPROVE"));
        boolean viewAll = authentication.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("VISITOR_REGISTER") ||
                a.getAuthority().equals("SECURITY_VISITOR_INTAKE") || a.getAuthority().equals("RECEPTION_VISIT_VERIFY") ||
                a.getAuthority().equals("CEO_VISIT_APPROVE"));
        boolean teamLeadView = authentication.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("TEAM_LEAD_VISIT_APPROVE"));
        boolean managerView = authentication.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("MANAGER_VISIT_APPROVE"));
        return service.list(UUID.fromString(jwt.getSubject()), employeeId, viewAll, hrView, teamLeadView,
                        managerView, from, to, pageable)
                .map(value -> AppointmentResponse.from(value, assignedToActor(value, employeeId, authentication)));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('VISITOR_REGISTER')")
    AppointmentResponse register(@RequestHeader("Idempotency-Key") @Size(min = 8, max = 100) String idempotencyKey,
                                 @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ReceptionAppointmentRequest request) {
        return AppointmentResponse.from(service.registerAtReception(idempotencyKey, UUID.fromString(jwt.getSubject()),
                new AppointmentService.CreateAppointment(request.type(), request.visitorName(), request.visitorEmail(),
                        request.visitorPhone(), request.visitorCompany(), request.hostEmployeeId(),
                        request.routingDepartmentId(), request.requestedEmployeeId(), request.slotStart(),
                        request.slotEnd(), request.purpose())));
    }

    @PostMapping("/security-walk-ins")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('SECURITY_VISITOR_INTAKE')")
    AppointmentResponse registerSecurityWalkIn(
            @RequestHeader("Idempotency-Key") @Size(min = 8, max = 100) String idempotencyKey,
            @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody SecurityWalkInRequest request) {
        UUID actor = UUID.fromString(jwt.getSubject());
        return AppointmentResponse.from(service.registerAtSecurity(idempotencyKey, actor,
                new AppointmentService.CreateAppointment(request.type(), request.visitorName(), request.visitorEmail(),
                        request.visitorPhone(), request.visitorCompany(), request.hostEmployeeId(),
                        request.routingDepartmentId(), request.requestedEmployeeId(), request.slotStart(),
                        request.slotEnd(), request.purpose()),
                new AppointmentService.SecurityIntake(request.visitorName(), request.purpose(),
                        request.identityDocumentType(), request.identityDocumentLastFour(), request.notes())));
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasAuthority('APPOINTMENT_APPROVE')")
    AppointmentResponse approve(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt, Authentication authentication,
                                @Valid @RequestBody DecisionRequest request) {
        return AppointmentResponse.from(service.approve(id, actorId(jwt), authentication, request.remarks()));
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("hasAuthority('APPOINTMENT_REJECT')")
    AppointmentResponse reject(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt, Authentication authentication,
                               @Valid @RequestBody DecisionRequest request) {
        return AppointmentResponse.from(service.reject(id, actorId(jwt), authentication, request.remarks()));
    }

    @PostMapping("/{id}/security-intake")
    @PreAuthorize("hasAuthority('SECURITY_VISITOR_INTAKE')")
    AppointmentResponse securityIntake(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt,
                                       @Valid @RequestBody SecurityIntakeRequest request) {
        return AppointmentResponse.from(service.recordSecurityIntake(id, UUID.fromString(jwt.getSubject()),
                new AppointmentService.SecurityIntake(request.visitorName(), request.purpose(),
                        request.identityDocumentType(), request.identityDocumentLastFour(), request.notes())));
    }

    @PostMapping("/{id}/reception-verify")
    @PreAuthorize("hasAuthority('RECEPTION_VISIT_VERIFY')")
    AppointmentResponse receptionVerify(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt,
                                         @Valid @RequestBody DecisionRequest request) {
        return AppointmentResponse.from(service.verifyByReception(id, UUID.fromString(jwt.getSubject()), request.remarks()));
    }

    @PostMapping("/{id}/reception-reject")
    @PreAuthorize("hasAuthority('RECEPTION_VISIT_VERIFY')")
    AppointmentResponse receptionReject(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt,
                                         @Valid @RequestBody DecisionRequest request) {
        return AppointmentResponse.from(service.rejectByReception(id, UUID.fromString(jwt.getSubject()), request.remarks()));
    }

    @PostMapping("/{id}/reception-forward")
    @PreAuthorize("hasAuthority('RECEPTION_VISIT_VERIFY')")
    AppointmentResponse receptionForward(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt,
                                          @Valid @RequestBody DecisionRequest request) {
        return AppointmentResponse.from(service.forwardByReception(id, UUID.fromString(jwt.getSubject()), request.remarks()));
    }

    @PostMapping("/{id}/hr-approve")
    @PreAuthorize("hasAuthority('HR_VISIT_APPROVE')")
    AppointmentResponse hrApprove(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt,
                                  @Valid @RequestBody DecisionRequest request) {
        return AppointmentResponse.from(service.approveByHr(id, UUID.fromString(jwt.getSubject()),
                employeeId(jwt), request.remarks()));
    }

    @PostMapping("/{id}/hr-reject")
    @PreAuthorize("hasAuthority('HR_VISIT_APPROVE')")
    AppointmentResponse hrReject(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt,
                                 @Valid @RequestBody DecisionRequest request) {
        return AppointmentResponse.from(service.rejectByHr(id, UUID.fromString(jwt.getSubject()),
                employeeId(jwt), request.remarks()));
    }

    @PostMapping("/{id}/ceo-approve")
    @PreAuthorize("hasAuthority('CEO_VISIT_APPROVE')")
    AppointmentResponse ceoApprove(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt,
                                   @Valid @RequestBody DecisionRequest request) {
        return AppointmentResponse.from(service.approveByCeo(id, UUID.fromString(jwt.getSubject()), request.remarks()));
    }

    @PostMapping("/{id}/ceo-reject")
    @PreAuthorize("hasAuthority('CEO_VISIT_APPROVE')")
    AppointmentResponse ceoReject(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt,
                                  @Valid @RequestBody DecisionRequest request) {
        return AppointmentResponse.from(service.rejectByCeo(id, UUID.fromString(jwt.getSubject()), request.remarks()));
    }

    @PostMapping("/{id}/team-lead-approve")
    @PreAuthorize("hasAuthority('TEAM_LEAD_VISIT_APPROVE')")
    AppointmentResponse teamLeadApprove(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt,
                                        @Valid @RequestBody DecisionRequest request) {
        return AppointmentResponse.from(service.approveByTeamLead(id, UUID.fromString(jwt.getSubject()), request.remarks()));
    }

    @PostMapping("/{id}/team-lead-reject")
    @PreAuthorize("hasAuthority('TEAM_LEAD_VISIT_APPROVE')")
    AppointmentResponse teamLeadReject(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt,
                                       @Valid @RequestBody DecisionRequest request) {
        return AppointmentResponse.from(service.rejectByTeamLead(id, UUID.fromString(jwt.getSubject()), request.remarks()));
    }

    @PostMapping("/{id}/manager-approve")
    @PreAuthorize("hasAuthority('MANAGER_VISIT_APPROVE')")
    AppointmentResponse managerApprove(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt,
                                       @Valid @RequestBody DecisionRequest request) {
        return AppointmentResponse.from(service.approveByManager(
                id, UUID.fromString(jwt.getSubject()), request.remarks()));
    }

    @PostMapping("/{id}/manager-reject")
    @PreAuthorize("hasAuthority('MANAGER_VISIT_APPROVE')")
    AppointmentResponse managerReject(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt,
                                      @Valid @RequestBody DecisionRequest request) {
        return AppointmentResponse.from(service.rejectByManager(
                id, UUID.fromString(jwt.getSubject()), request.remarks()));
    }

    private UUID actorId(Jwt jwt) {
        String employeeId = jwt.getClaimAsString("employeeId");
        return UUID.fromString(employeeId == null ? jwt.getSubject() : employeeId);
    }

    private UUID employeeId(Jwt jwt) {
        String value = jwt.getClaimAsString("employeeId");
        return value == null || value.isBlank() ? null : UUID.fromString(value);
    }

    private boolean assignedToActor(Appointment appointment, UUID employeeId, Authentication authentication) {
        boolean hr = authentication.getAuthorities().stream()
                .anyMatch(value -> value.getAuthority().equals("HR_VISIT_APPROVE"));
        if (!hr || appointment.getStatus() != AppointmentStatus.PENDING_HR_APPROVAL) return true;
        if (appointment.getType() != AppointmentType.HR_VISIT
                && appointment.getType() != AppointmentType.INTERVIEW) return true;
        return employeeId != null && employeeId.equals(appointment.getHostEmployeeId());
    }

    public record DecisionRequest(@Size(max = 500) String remarks) {}
    public record SecurityIntakeRequest(@NotBlank @Size(max = 170) String visitorName,
                                        @NotBlank @Size(min = 5, max = 1000) String purpose,
                                        @Size(max = 40) String identityDocumentType,
                                        @Pattern(regexp = "[A-Za-z0-9]{4}", message = "Identity document last four must contain exactly four letters or digits")
                                        String identityDocumentLastFour,
                                        @Size(max = 500) String notes) {}
    public record ReceptionAppointmentRequest(@NotNull AppointmentType type,
                                              @NotBlank @Size(max = 170) String visitorName,
                                              @NotBlank @Email @Size(max = 180) String visitorEmail,
                                              @NotBlank @Size(max = 30) String visitorPhone,
                                              @Size(max = 160) String visitorCompany,
                                              @NotNull UUID hostEmployeeId,
                                              UUID routingDepartmentId,
                                              UUID requestedEmployeeId,
                                              @NotNull @Future Instant slotStart,
                                              @NotNull @Future Instant slotEnd,
                                              @NotBlank @Size(max = 1000) String purpose) {}
    public record SecurityWalkInRequest(@NotNull AppointmentType type,
                                        @NotBlank @Size(max = 170) String visitorName,
                                        @NotBlank @Email @Size(max = 180) String visitorEmail,
                                        @NotBlank @Size(max = 30) String visitorPhone,
                                        @Size(max = 160) String visitorCompany,
                                        @NotNull UUID hostEmployeeId,
                                        UUID routingDepartmentId,
                                        UUID requestedEmployeeId,
                                        @NotNull @Future Instant slotStart,
                                        @NotNull @Future Instant slotEnd,
                                        @NotBlank @Size(max = 1000) String purpose,
                                        @Size(max = 40) String identityDocumentType,
                                        @Pattern(regexp = "[A-Za-z0-9]{4}", message = "Identity document last four must contain exactly four letters or digits")
                                        String identityDocumentLastFour,
                                        @Size(max = 500) String notes) {}
    public record AppointmentResponse(UUID id, String referenceNumber, AppointmentType type, AppointmentStatus status,
                                      String visitorName, String visitorEmail, String visitorPhone, String visitorCompany,
                                      UUID hostEmployeeId, UUID routingDepartmentId, UUID requestedEmployeeId, Instant slotStart,
                                      Instant slotEnd, String purpose, UUID registeredByUserId,
                                      UUID securityIntakeActorId, Instant securityIntakeAt, String arrivalVisitorName,
                                      String arrivalPurpose, String identityDocumentType, String identityDocumentLastFour,
                                      String securityNotes, UUID receptionVerificationActorId, Instant receptionVerifiedAt,
                                      String receptionVerificationRemarks,
                                      UUID hrApprovalActorId, Instant hrDecisionAt, String hrDecisionRemarks,
                                      UUID teamLeadApprovalActorId, Instant teamLeadDecisionAt,
                                      String teamLeadDecisionRemarks,
                                      UUID managerApprovalActorId, Instant managerDecisionAt,
                                      String managerDecisionRemarks,
                                      UUID ceoApprovalActorId, Instant ceoDecisionAt, String ceoDecisionRemarks,
                                      UUID receptionForwardActorId, Instant receptionForwardedAt,
                                      String receptionForwardRemarks, Instant createdAt,
                                      boolean assignedToCurrentActor, long version) {
        static AppointmentResponse from(Appointment value) { return from(value, true); }
        static AppointmentResponse from(Appointment value, boolean assignedToCurrentActor) { return new AppointmentResponse(value.getId(), value.getReferenceNumber(),
                value.getType(), value.getStatus(), value.getVisitorName(), value.getVisitorEmail(), value.getVisitorPhone(),
                value.getVisitorCompany(), value.getHostEmployeeId(), value.getRoutingDepartmentId(),
                value.getRequestedEmployeeId(),
                value.getSlotStart(), value.getSlotEnd(), value.getPurpose(), value.getRegisteredByUserId(),
                value.getSecurityIntakeActorId(), value.getSecurityIntakeAt(), value.getArrivalVisitorName(),
                value.getArrivalPurpose(), value.getIdentityDocumentType(), value.getIdentityDocumentLastFour(),
                value.getSecurityNotes(), value.getReceptionVerificationActorId(), value.getReceptionVerifiedAt(),
                value.getReceptionVerificationRemarks(),
                value.getHrApprovalActorId(), value.getHrDecisionAt(), value.getHrDecisionRemarks(),
                value.getTeamLeadApprovalActorId(), value.getTeamLeadDecisionAt(), value.getTeamLeadDecisionRemarks(),
                value.getManagerApprovalActorId(), value.getManagerDecisionAt(),
                value.getManagerDecisionRemarks(),
                value.getCeoApprovalActorId(), value.getCeoDecisionAt(), value.getCeoDecisionRemarks(),
                value.getReceptionForwardActorId(), value.getReceptionForwardedAt(),
                value.getReceptionForwardRemarks(), value.getCreatedAt(), assignedToCurrentActor, value.getVersion()); }
    }
}
