package com.brainserve.appointment.compensation.application;

import com.brainserve.appointment.audit.api.AuditService;
import com.brainserve.appointment.compensation.domain.CompensationPackage;
import com.brainserve.appointment.compensation.infrastructure.CompensationRepository;
import com.brainserve.appointment.employee.api.EmployeeDirectory;
import com.brainserve.appointment.shared.application.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class CompensationService {

    private final CompensationRepository compensation;
    private final EmployeeDirectory employees;
    private final AuditService audit;

    public CompensationService(
            CompensationRepository compensation,
            EmployeeDirectory employees,
            AuditService audit
    ) {
        this.compensation = compensation;
        this.employees = employees;
        this.audit = audit;
    }

    @Transactional
    public CompensationPackage create(
            UUID employeeId,
            CompensationPackage.Components components,
            String currency,
            LocalDate effectiveFrom,
            LocalDate effectiveTo
    ) {
        validateCreateRequest(
                components,
                currency,
                effectiveFrom,
                effectiveTo
        );

        employees.requireEmployee(employeeId);

        if (compensation.overlaps(
                employeeId,
                effectiveFrom,
                effectiveTo
        )) {
            throw new BusinessException(
                    "SALARY_PERIOD_OVERLAP",
                    "Compensation period overlaps an existing record",
                    HttpStatus.CONFLICT
            );
        }

        String normalizedCurrency = normalizeCurrency(currency);

        CompensationPackage saved = compensation.save(
                new CompensationPackage(
                        employeeId,
                        components,
                        normalizedCurrency,
                        effectiveFrom,
                        effectiveTo
                )
        );

        audit.record(
                "SALARY_WRITE",
                "EMPLOYEE",
                employeeId.toString(),
                "{\"action\":\"created\"}"
        );

        return saved;
    }

    /*
     * This method is intentionally not marked readOnly because
     * audit.record() writes a new audit record.
     */
    @Transactional
    public CompensationPackage current(UUID employeeId) {
        employees.requireEmployee(employeeId);

        CompensationPackage value = compensation
                .findCurrent(employeeId, LocalDate.now())
                .stream()
                .findFirst()
                .orElseThrow(() -> new BusinessException(
                        "COMPENSATION_NOT_FOUND",
                        "Current compensation was not found",
                        HttpStatus.NOT_FOUND
                ));

        audit.record(
                "SALARY_READ",
                "EMPLOYEE",
                employeeId.toString(),
                "{\"scope\":\"current\"}"
        );

        return value;
    }

    /*
     * This method is intentionally not marked readOnly because
     * audit.record() writes a new audit record.
     */
    @Transactional
    public List<CompensationPackage> history(UUID employeeId) {
        employees.requireEmployee(employeeId);

        List<CompensationPackage> values =
                compensation.findByEmployeeIdOrderByEffectiveFromDesc(
                        employeeId
                );

        audit.record(
                "SALARY_READ",
                "EMPLOYEE",
                employeeId.toString(),
                "{\"scope\":\"history\"}"
        );

        return values;
    }

    private void validateCreateRequest(
            CompensationPackage.Components components,
            String currency,
            LocalDate effectiveFrom,
            LocalDate effectiveTo
    ) {
        if (components == null) {
            throw new BusinessException(
                    "COMPENSATION_COMPONENTS_REQUIRED",
                    "Compensation components are required",
                    HttpStatus.UNPROCESSABLE_ENTITY
            );
        }

        if (effectiveFrom == null) {
            throw new BusinessException(
                    "EFFECTIVE_FROM_REQUIRED",
                    "Effective-from date is required",
                    HttpStatus.UNPROCESSABLE_ENTITY
            );
        }

        /*
         * This is the exact location for your period validation:
         * before compensation.overlaps(...) and before saving.
         */
        if (effectiveTo != null
                && effectiveTo.isBefore(effectiveFrom)) {
            throw new BusinessException(
                    "INVALID_SALARY_PERIOD",
                    "Effective-to date must not precede effective-from",
                    HttpStatus.UNPROCESSABLE_ENTITY
            );
        }

        normalizeCurrency(currency);
    }

    private String normalizeCurrency(String currency) {
        if (currency == null || currency.isBlank()) {
            throw new BusinessException(
                    "CURRENCY_REQUIRED",
                    "Currency is required",
                    HttpStatus.UNPROCESSABLE_ENTITY
            );
        }

        String normalized = currency.trim()
                .toUpperCase(Locale.ROOT);

        if (!normalized.matches("[A-Z]{3}")) {
            throw new BusinessException(
                    "INVALID_CURRENCY",
                    "Currency must be a three-letter ISO code such as INR",
                    HttpStatus.UNPROCESSABLE_ENTITY
            );
        }

        return normalized;
    }
}