package com.brainserve.appointment.employee.application;

import com.brainserve.appointment.employee.api.EmployeeEvents;
import com.brainserve.appointment.employee.api.EmployeeProfileProvisioning;
import com.brainserve.appointment.employee.domain.Employee;
import com.brainserve.appointment.employee.domain.EmployeeStatus;
import com.brainserve.appointment.employee.infrastructure.EmployeeRepository;
import com.brainserve.appointment.iam.api.AppointmentHostDirectory;
import com.brainserve.appointment.organization.api.OrganizationDirectory;
import com.brainserve.appointment.shared.application.BusinessException;
import jakarta.persistence.EntityManager;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Locale;
import java.util.UUID;

@Service
public class HrEmployeeProfileProvisioningService
        implements EmployeeProfileProvisioning {

    private final EmployeeRepository employees;
    private final OrganizationDirectory organization;
    private final AppointmentHostDirectory appointmentHosts;
    private final EntityManager entityManager;
    private final ApplicationEventPublisher events;

    public HrEmployeeProfileProvisioningService(
            EmployeeRepository employees,
            OrganizationDirectory organization,
            AppointmentHostDirectory appointmentHosts,
            EntityManager entityManager,
            ApplicationEventPublisher events
    ) {
        this.employees = employees;
        this.organization = organization;
        this.appointmentHosts = appointmentHosts;
        this.entityManager = entityManager;
        this.events = events;
    }

    @Override
    @Transactional
    public ProvisionedEmployee createAndLink(
            UUID userAccountId,
            String fullName,
            String email,
            UUID departmentId,
            String phoneNumber,
            String designation,
            LocalDate joiningDate
    ) {
        if (userAccountId == null) {
            throw new BusinessException(
                    "USER_ACCOUNT_REQUIRED",
                    "A user account is required to create an employee profile",
                    HttpStatus.UNPROCESSABLE_ENTITY
            );
        }

        String normalizedEmail = normalizeEmail(email);

        if (employees.existsByOfficialEmailIgnoreCase(normalizedEmail)) {
            throw new BusinessException(
                    "EMPLOYEE_EMAIL_EXISTS",
                    "An employee profile already uses this account email",
                    HttpStatus.CONFLICT
            );
        }

        if (joiningDate == null || joiningDate.isAfter(LocalDate.now())) {
            throw new BusinessException(
                    "INVALID_JOINING_DATE",
                    "Joining date is required and cannot be in the future",
                    HttpStatus.UNPROCESSABLE_ENTITY
            );
        }

        String normalizedDesignation =
                designation == null ? "" : designation.trim();

        if (normalizedDesignation.length() < 2
                || normalizedDesignation.length() > 120) {
            throw new BusinessException(
                    "INVALID_DESIGNATION",
                    "Designation must contain 2-120 characters",
                    HttpStatus.UNPROCESSABLE_ENTITY
            );
        }

        String normalizedPhone = normalizePhone(phoneNumber);

        var department =
                organization.requireActiveDepartment(departmentId);

        String[] names = splitName(fullName);

        long sequence = ((Number) entityManager.createNativeQuery(
                "select nextval('employee_number_seq')"
        ).getSingleResult()).longValue();

        String employeeNumber =
                "BSPL-"
                        + department.code().trim().toUpperCase(Locale.ROOT)
                        + "-"
                        + String.format("%04d", sequence);

        Employee employee = new Employee(
                employeeNumber,
                names[0],
                names[1],
                normalizedEmail,
                normalizedPhone,
                departmentId,
                normalizedDesignation,
                joiningDate
        );

        employee = employees.saveAndFlush(employee);

        employee.transitionTo(EmployeeStatus.ACTIVE);

        employee = employees.saveAndFlush(employee);

        appointmentHosts.linkEmployee(
                userAccountId,
                employee.getId()
        );

        events.publishEvent(
                new EmployeeEvents.EmployeeCreated(
                        employee.getId(),
                        employee.getEmployeeNumber(),
                        employee.getOfficialEmail(),
                        Instant.now()
                )
        );

        return new ProvisionedEmployee(employee.getId());
    }

    private String normalizeEmail(String email) {
        String normalized = email == null
                ? ""
                : email.trim().toLowerCase(Locale.ROOT);

        if (normalized.isBlank() || normalized.length() > 180) {
            throw new BusinessException(
                    "INVALID_EMPLOYEE_EMAIL",
                    "A valid account email is required",
                    HttpStatus.UNPROCESSABLE_ENTITY
            );
        }

        return normalized;
    }

    private String normalizePhone(String phoneNumber) {
        if (phoneNumber == null || phoneNumber.isBlank()) {
            return null;
        }

        String normalized = phoneNumber.trim();

        if (normalized.length() > 30) {
            throw new BusinessException(
                    "INVALID_PHONE_NUMBER",
                    "Phone number cannot exceed 30 characters",
                    HttpStatus.UNPROCESSABLE_ENTITY
            );
        }

        return normalized;
    }

    private String[] splitName(String fullName) {
        String normalized = fullName == null
                ? ""
                : fullName.trim().replaceAll("\\s+", " ");

        int separator = normalized.indexOf(' ');

        String firstName = separator < 0
                ? normalized
                : normalized.substring(0, separator);

        String lastName = separator < 0
                ? ""
                : normalized.substring(separator + 1);

        if (firstName.length() < 2
                || firstName.length() > 80
                || lastName.length() > 80) {
            throw new BusinessException(
                    "INVALID_HR_EMPLOYEE_NAME",
                    "The account name must fit the employee profile name fields",
                    HttpStatus.UNPROCESSABLE_ENTITY
            );
        }

        return new String[]{firstName, lastName};
    }
}