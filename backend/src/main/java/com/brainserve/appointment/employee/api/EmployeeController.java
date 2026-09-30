package com.brainserve.appointment.employee.api;

import com.brainserve.appointment.employee.application.EmployeeService;
import com.brainserve.appointment.employee.domain.Employee;
import com.brainserve.appointment.employee.domain.EmployeeStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/employees")
public class EmployeeController {
    private final EmployeeService service;
    public EmployeeController(EmployeeService service) { this.service = service; }

    @PostMapping
    @PreAuthorize("hasAuthority('EMPLOYEE_CREATE')")
    CreatedEmployeeResponse create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CreateEmployeeRequest request) {
        var created = service.createScoped(actor(jwt), new EmployeeService.CreateEmployee(request.firstName(), request.lastName(),
                request.officialEmail(), request.phoneNumber(), request.departmentId(), request.designation(),
                request.joiningDate()));
        return new CreatedEmployeeResponse(EmployeeResponse.from(created.employee(), false));
    }

    @GetMapping
    @PreAuthorize("hasAuthority('EMPLOYEE_READ')")
    Page<EmployeeResponse> list(@AuthenticationPrincipal Jwt jwt,
                                @RequestParam(required = false) String query,
                                @RequestParam(required = false) UUID departmentId,
                                @RequestParam(required = false) EmployeeStatus status,
                                Pageable pageable) {
        if (pageable.getPageSize() < 25 || pageable.getPageSize() > 100) {
            throw new com.brainserve.appointment.shared.application.BusinessException(
                    "INVALID_PAGE_SIZE", "Page size must be between 25 and 100",
                    org.springframework.http.HttpStatus.BAD_REQUEST);
        }
        var lifecycleProtectedEmployeeIds = service.chiefExecutiveEmployeeIds();
        return service.list(actor(jwt), query, departmentId, status, pageable)
                .map(value -> EmployeeResponse.from(value,
                        lifecycleProtectedEmployeeIds.contains(value.getId())));
    }

    @GetMapping("/department-summary")
    @PreAuthorize("hasAuthority('EMPLOYEE_READ')")
    java.util.List<DepartmentSummaryResponse> departmentSummary(@AuthenticationPrincipal Jwt jwt) {
        return service.departmentSummaries(actor(jwt)).stream().map(DepartmentSummaryResponse::from).toList();
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('EMPLOYEE_READ')")
    EmployeeResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        Employee employee = service.getVisible(actor(jwt), id);
        return EmployeeResponse.from(employee, service.isChiefExecutive(employee.getId()));
    }

    @GetMapping("/me")
    EmployeeResponse me(@AuthenticationPrincipal Jwt jwt) {
        String employeeId = jwt.getClaimAsString("employeeId");
        if (employeeId == null) {
            throw new com.brainserve.appointment.shared.application.BusinessException("EMPLOYEE_PROFILE_NOT_LINKED",
                    "This login is not linked to an employee profile", org.springframework.http.HttpStatus.NOT_FOUND);
        }
        Employee employee = service.get(UUID.fromString(employeeId));
        return EmployeeResponse.from(employee, service.isChiefExecutive(employee.getId()));
    }

    @PutMapping("/me/executive-profile")
    @PreAuthorize("hasRole('CEO')")
    EmployeeResponse executiveProfile(@AuthenticationPrincipal Jwt jwt,
                                       @Valid @RequestBody ExecutiveProfileRequest request) {
        Employee employee = service.upsertExecutiveProfile(UUID.fromString(jwt.getSubject()),
                new EmployeeService.ExecutiveProfile(request.departmentId(), request.phoneNumber(),
                        request.designation(), request.joiningDate()));
        return EmployeeResponse.from(employee, true);
    }

    @PatchMapping("/{id}/status")
    @PreAuthorize("hasAuthority('EMPLOYEE_STATUS_CHANGE')")
    EmployeeResponse status(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                            @Valid @RequestBody StatusRequest request) {
        Employee employee = service.changeStatusScoped(actor(jwt), id, request.status());
        return EmployeeResponse.from(employee, service.isChiefExecutive(employee.getId()));
    }

    private UUID actor(Jwt jwt) { return UUID.fromString(jwt.getSubject()); }

    public record CreateEmployeeRequest(@NotBlank @Size(max = 80) String firstName,
                                        @Size(max = 80) String lastName,
                                        @NotBlank @Email @Size(max = 180) String officialEmail,
                                        @Size(max = 30) String phoneNumber,
                                        @NotNull UUID departmentId,
                                        @NotBlank @Size(max = 120) String designation,
                                        @NotNull LocalDate joiningDate) {}
    public record StatusRequest(@NotNull EmployeeStatus status) {}
    public record ExecutiveProfileRequest(@NotNull UUID departmentId,
                                          @Size(max = 30) String phoneNumber,
                                          @NotBlank @Size(max = 120) String designation,
                                          @NotNull LocalDate joiningDate) {}
    public record CreatedEmployeeResponse(EmployeeResponse employee) {}
    public record DepartmentSummaryResponse(UUID departmentId, long totalEmployees, long activeEmployees,
                                            long onLeaveEmployees, long onboardingEmployees) {
        static DepartmentSummaryResponse from(EmployeeService.DepartmentEmployeeSummary value) {
            return new DepartmentSummaryResponse(value.departmentId(), value.totalEmployees(), value.activeEmployees(),
                    value.onLeaveEmployees(), value.onboardingEmployees());
        }
    }
    public record EmployeeResponse(UUID id, String employeeNumber, String displayName, String officialEmail,
                                   String phoneNumber, UUID departmentId, String designation,
                                   LocalDate joiningDate, LocalDate relievingDate, EmployeeStatus status,
                                   boolean lifecycleProtected, long version) {
        static EmployeeResponse from(Employee value, boolean lifecycleProtected) {
            return new EmployeeResponse(value.getId(), value.getEmployeeNumber(), value.getDisplayName(), value.getOfficialEmail(),
                    value.getPhoneNumber(), value.getDepartmentId(), value.getDesignation(),
                    value.getJoiningDate(), value.getRelievingDate(), value.getStatus(),
                    lifecycleProtected, value.getVersion());
        }
    }
}
