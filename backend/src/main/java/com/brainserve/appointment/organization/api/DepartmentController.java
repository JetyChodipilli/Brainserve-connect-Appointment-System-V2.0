package com.brainserve.appointment.organization.api;

import com.brainserve.appointment.audit.api.AuditService;
import com.brainserve.appointment.organization.domain.Department;
import com.brainserve.appointment.organization.infrastructure.DepartmentRepository;
import com.brainserve.appointment.shared.application.BusinessException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/departments")
public class DepartmentController {

    private static final Set<String> REQUIRED_ROUTING_DEPARTMENT_CODES =
            Set.of("EXEC", "HR");

    private final DepartmentRepository departments;
    private final AuditService audit;

    public DepartmentController(
            DepartmentRepository departments,
            AuditService audit
    ) {
        this.departments = departments;
        this.audit = audit;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN','CEO')")
    @Transactional(readOnly = true)
    public List<DepartmentResponse> list() {
        return departments.findAllByOrderByNameAsc()
                .stream()
                .map(DepartmentResponse::from)
                .toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("""
            hasAnyAuthority(
                'ROLE_SYSTEM_ADMIN',
                'ROLE_CEO',
                'DEPARTMENT_MANAGE'
            )
            """)
    @Transactional
    public DepartmentResponse create(
            @Valid @RequestBody DepartmentRequest request
    ) {
        if (departments.existsByCodeIgnoreCase(request.code())) {
            throw departmentCodeConflict();
        }

        final Department created;
        try {
            created = departments.saveAndFlush(
                    new Department(request.code(), request.name())
            );
        } catch (DataIntegrityViolationException exception) {
            // A database unique constraint is still required to close the
            // exists-check race between concurrent create requests.
            throw departmentCodeConflict();
        }

        audit.record(
                "DEPARTMENT_CREATED",
                "DEPARTMENT",
                created.getId().toString(),
                auditDetails(created)
        );

        return DepartmentResponse.from(created);
    }

    @PatchMapping("/{id}/status")
    @PreAuthorize("""
            hasAnyAuthority(
                'ROLE_SYSTEM_ADMIN',
                'ROLE_CEO',
                'DEPARTMENT_MANAGE'
            )
            """)
    @Transactional
    public DepartmentResponse changeStatus(
            @PathVariable UUID id,
            @Valid @RequestBody StatusRequest request
    ) {
        Department department = departments.findById(id)
                .orElseThrow(() -> new BusinessException(
                        "DEPARTMENT_NOT_FOUND",
                        "Department was not found",
                        HttpStatus.NOT_FOUND
                ));

        boolean requestedActive = Boolean.TRUE.equals(request.active());

        if (!requestedActive && isRequiredRoutingDepartment(department)) {
            throw new BusinessException(
                    "ROUTING_DEPARTMENT_REQUIRED",
                    "Executive Office and Human Resources must remain active for appointment routing",
                    HttpStatus.UNPROCESSABLE_ENTITY
            );
        }

        if (department.isActive() == requestedActive) {
            return DepartmentResponse.from(department);
        }

        department.changeStatus(requestedActive);

        try {
            departments.saveAndFlush(department);
        } catch (ObjectOptimisticLockingFailureException exception) {
            throw new BusinessException(
                    "DEPARTMENT_STATUS_CONFLICT",
                    "Department status changed in another request; reload and try again",
                    HttpStatus.CONFLICT
            );
        }

        audit.record(
                requestedActive
                        ? "DEPARTMENT_ACTIVATED"
                        : "DEPARTMENT_DEACTIVATED",
                "DEPARTMENT",
                department.getId().toString(),
                auditDetails(department)
        );

        return DepartmentResponse.from(department);
    }

    private static boolean isRequiredRoutingDepartment(Department department) {
        return REQUIRED_ROUTING_DEPARTMENT_CODES.contains(
                department.getCode().toUpperCase(Locale.ROOT)
        );
    }

    private static BusinessException departmentCodeConflict() {
        return new BusinessException(
                "DEPARTMENT_CODE_EXISTS",
                "Department code already exists",
                HttpStatus.CONFLICT
        );
    }

    private static String auditDetails(Department department) {
        // Department codes are restricted to [A-Z0-9_-], so this remains
        // valid JSON and cannot inject an additional property.
        return "{\"code\":\"" + department.getCode() + "\"}";
    }

    public record DepartmentRequest(
            @NotBlank
            @Pattern(
                    regexp = "[A-Z0-9_-]{2,20}",
                    message = "code must contain 2-20 letters, numbers, underscores, or hyphens"
            )
            String code,

            @NotBlank
            @Size(max = 120)
            String name
    ) {
        public DepartmentRequest {
            code = code == null
                    ? null
                    : code.trim().toUpperCase(Locale.ROOT);
            name = name == null ? null : name.trim();
        }
    }

    public record StatusRequest(
            @NotNull(message = "active is required")
            Boolean active
    ) {
    }

    public record DepartmentResponse(
            UUID id,
            String code,
            String name,
            boolean active,
            long version
    ) {
        static DepartmentResponse from(Department value) {
            return new DepartmentResponse(
                    value.getId(),
                    value.getCode(),
                    value.getName(),
                    value.isActive(),
                    value.getVersion()
            );
        }
    }
}
