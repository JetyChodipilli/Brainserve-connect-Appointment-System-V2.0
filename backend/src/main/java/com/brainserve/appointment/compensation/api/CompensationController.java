package com.brainserve.appointment.compensation.api;

import com.brainserve.appointment.compensation.application.CompensationService;
import com.brainserve.appointment.compensation.domain.CompensationPackage;
import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import jakarta.validation.ReportAsSingleViolation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/employees/{employeeId}/compensation")
public class CompensationController {

    private final CompensationService service;

    public CompensationController(CompensationService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('SALARY_WRITE')")
    public CompensationResponse create(
            @PathVariable UUID employeeId,
            @Valid @RequestBody CompensationRequest request
    ) {
        CompensationPackage saved = service.create(
                employeeId,
                request.components().toDomain(),
                request.currency(),
                request.effectiveFrom(),
                request.effectiveTo()
        );

        return CompensationResponse.from(saved);
    }

    @GetMapping("/current")
    @PreAuthorize("hasAuthority('SALARY_READ')")
    public CompensationResponse current(
            @PathVariable UUID employeeId
    ) {
        return CompensationResponse.from(
                service.current(employeeId)
        );
    }

    @GetMapping("/history")
    @PreAuthorize("hasAuthority('SALARY_READ')")
    public List<CompensationResponse> history(
            @PathVariable UUID employeeId
    ) {
        return service.history(employeeId)
                .stream()
                .map(CompensationResponse::from)
                .toList();
    }

    public record CompensationRequest(
            @NotNull(message = "Compensation components are required")
            @Valid
            ComponentsRequest components,

            @NotBlank(message = "Currency is required")
            @Pattern(
                    regexp = "^[A-Z]{3}$",
                    message = "Currency must be a three-letter ISO code such as INR"
            )
            String currency,

            @NotNull(message = "Effective-from date is required")
            LocalDate effectiveFrom,

            LocalDate effectiveTo
    ) {
        public CompensationRequest {
            if (currency != null) {
                currency = currency.trim()
                        .toUpperCase(Locale.ROOT);
            }
        }
    }

    public record ComponentsRequest(
            @Money BigDecimal basicSalary,
            @Money BigDecimal hra,
            @Money BigDecimal transportAllowance,
            @Money BigDecimal medicalAllowance,
            @Money BigDecimal specialAllowance,
            @Money BigDecimal otherAllowance,
            @Money BigDecimal providentFundDeduction,
            @Money BigDecimal professionalTax,
            @Money BigDecimal incomeTaxEstimate,
            @Money BigDecimal otherDeductions
    ) {
        public CompensationPackage.Components toDomain() {
            return new CompensationPackage.Components(
                    basicSalary,
                    hra,
                    transportAllowance,
                    medicalAllowance,
                    specialAllowance,
                    otherAllowance,
                    providentFundDeduction,
                    professionalTax,
                    incomeTaxEstimate,
                    otherDeductions
            );
        }
    }

    public record CompensationResponse(
            UUID id,
            UUID employeeId,
            BigDecimal basicSalary,
            BigDecimal hra,
            BigDecimal grossSalary,
            BigDecimal totalDeductions,
            BigDecimal netSalary,
            BigDecimal annualCtc,
            String currency,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            long version
    ) {
        public static CompensationResponse from(
                CompensationPackage value
        ) {
            return new CompensationResponse(
                    value.getId(),
                    value.getEmployeeId(),
                    value.getBasicSalary(),
                    value.getHra(),
                    value.getGrossSalary(),
                    value.getTotalDeductions(),
                    value.getNetSalary(),
                    value.getAnnualCtc(),
                    value.getCurrency(),
                    value.getEffectiveFrom(),
                    value.getEffectiveTo(),
                    value.getVersion()
            );
        }
    }

    @Documented
    @Constraint(validatedBy = {})
    @ReportAsSingleViolation
    @NotNull
    @DecimalMin(value = "0.00", inclusive = true)
    @Digits(integer = 16, fraction = 2)
    @Target({
            ElementType.FIELD,
            ElementType.PARAMETER,
            ElementType.RECORD_COMPONENT,
            ElementType.ANNOTATION_TYPE,
            ElementType.TYPE_USE
    })
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Money {

        String message()
                default "must be a non-negative monetary amount with at most two decimal places";

        Class<?>[] groups() default {};

        Class<? extends Payload>[] payload() default {};
    }
}