package com.brainserve.appointment.employee.domain;

import com.brainserve.appointment.shared.application.BusinessException;
import com.brainserve.appointment.shared.domain.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.springframework.http.HttpStatus;

import java.time.LocalDate;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "employee")
public class Employee extends AuditableEntity {
    private static final Map<EmployeeStatus, Set<EmployeeStatus>> TRANSITIONS = transitions();

    @Column(name = "employee_number", nullable = false, unique = true, updatable = false, length = 30)
    private String employeeNumber;
    @Column(name = "first_name", nullable = false, length = 80)
    private String firstName;
    @Column(name = "last_name", nullable = false, length = 80)
    private String lastName;
    @Column(name = "display_name", nullable = false, length = 170)
    private String displayName;
    @Column(name = "official_email", nullable = false, unique = true, length = 180)
    private String officialEmail;
    @Column(name = "phone_number", length = 30)
    private String phoneNumber;
    @Column(name = "department_id", nullable = false)
    private UUID departmentId;
    @Column(name = "designation", nullable = false, length = 120)
    private String designation;
    @Column(name = "joining_date", nullable = false)
    private LocalDate joiningDate;
    @Column(name = "relieving_date")
    private LocalDate relievingDate;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private EmployeeStatus status = EmployeeStatus.ONBOARDING;

    protected Employee() {}

    public Employee(String employeeNumber, String firstName, String lastName, String officialEmail, String phoneNumber,
                    UUID departmentId, String designation, LocalDate joiningDate) {
        this.employeeNumber = employeeNumber;
        this.firstName = firstName.trim();
        this.lastName = normalizeOptionalName(lastName);
        this.displayName = (this.firstName + " " + this.lastName).trim();
        this.officialEmail = officialEmail.trim().toLowerCase();
        this.phoneNumber = phoneNumber;
        this.departmentId = departmentId;
        this.designation = designation.trim();
        this.joiningDate = joiningDate;
    }

    public void transitionTo(EmployeeStatus next) {
        if (!TRANSITIONS.getOrDefault(status, Set.of()).contains(next)) {
            throw new BusinessException("INVALID_EMPLOYEE_STATUS_TRANSITION", "Cannot change employee status from " + status + " to " + next, HttpStatus.UNPROCESSABLE_ENTITY);
        }
        status = next;
        if (next == EmployeeStatus.RESIGNED || next == EmployeeStatus.TERMINATED) relievingDate = LocalDate.now();
    }

    public void terminate(LocalDate effectiveDate) {
        if (effectiveDate == null || effectiveDate.isAfter(LocalDate.now())) {
            throw new BusinessException("INVALID_TERMINATION_EFFECTIVE_DATE",
                    "The termination effective date must be today or earlier", HttpStatus.UNPROCESSABLE_ENTITY);
        }
        transitionTo(EmployeeStatus.TERMINATED);
        relievingDate = effectiveDate;
    }

    public void update(String firstName, String lastName, String phoneNumber, String designation) {
        this.firstName = firstName.trim(); this.lastName = normalizeOptionalName(lastName);
        this.displayName = (this.firstName + " " + this.lastName).trim();
        this.phoneNumber = phoneNumber; this.designation = designation.trim();
    }

    public void transferDepartment(UUID nextDepartmentId) {
        if (nextDepartmentId == null) throw new IllegalArgumentException("Department ID is required");
        departmentId = nextDepartmentId;
    }

    public void transitionOperationalPosition(UUID nextDepartmentId, String nextDesignation) {
        transferDepartment(nextDepartmentId);
        if (nextDesignation != null && !nextDesignation.isBlank()) {
            designation = nextDesignation.trim();
        }
    }

    public void deactivateForAccountArchive() {
        if (status == EmployeeStatus.TERMINATED || status == EmployeeStatus.RESIGNED) return;
        status = EmployeeStatus.INACTIVE;
    }

    public void restoreAfterAccountRecovery(UUID nextDepartmentId, String nextDesignation) {
        if (status == EmployeeStatus.TERMINATED || status == EmployeeStatus.RESIGNED) {
            throw new BusinessException("EMPLOYEE_RECOVERY_NOT_ALLOWED",
                    "A resigned or terminated employee cannot be restored through account recovery",
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }
        status = EmployeeStatus.ACTIVE;
        relievingDate = null;
        transitionOperationalPosition(nextDepartmentId, nextDesignation);
    }

    public String getEmployeeNumber() { return employeeNumber; }
    public String getFirstName() { return firstName; }
    public String getLastName() { return lastName; }
    public String getDisplayName() { return displayName; }
    public String getOfficialEmail() { return officialEmail; }
    public String getPhoneNumber() { return phoneNumber; }
    public UUID getDepartmentId() { return departmentId; }
    public String getDesignation() { return designation; }
    public LocalDate getJoiningDate() { return joiningDate; }
    public LocalDate getRelievingDate() { return relievingDate; }
    public EmployeeStatus getStatus() { return status; }
    public boolean isActiveForManagement() { return status == EmployeeStatus.ACTIVE || status == EmployeeStatus.ON_LEAVE || status == EmployeeStatus.NOTICE_PERIOD; }
    public boolean isAvailableAsHost() { return status == EmployeeStatus.ACTIVE; }

    private static String normalizeOptionalName(String value) { return value == null ? "" : value.trim(); }

    private static Map<EmployeeStatus, Set<EmployeeStatus>> transitions() {
        Map<EmployeeStatus, Set<EmployeeStatus>> map = new EnumMap<>(EmployeeStatus.class);
        map.put(EmployeeStatus.DRAFT, EnumSet.of(EmployeeStatus.ONBOARDING, EmployeeStatus.INACTIVE));
        map.put(EmployeeStatus.ONBOARDING, EnumSet.of(EmployeeStatus.ACTIVE, EmployeeStatus.SUSPENDED, EmployeeStatus.INACTIVE));
        map.put(EmployeeStatus.ACTIVE, EnumSet.of(EmployeeStatus.ON_LEAVE, EmployeeStatus.NOTICE_PERIOD, EmployeeStatus.SUSPENDED, EmployeeStatus.TERMINATED));
        map.put(EmployeeStatus.ON_LEAVE, EnumSet.of(EmployeeStatus.ACTIVE, EmployeeStatus.NOTICE_PERIOD, EmployeeStatus.SUSPENDED));
        map.put(EmployeeStatus.NOTICE_PERIOD, EnumSet.of(EmployeeStatus.RESIGNED, EmployeeStatus.ACTIVE, EmployeeStatus.TERMINATED));
        map.put(EmployeeStatus.SUSPENDED, EnumSet.of(EmployeeStatus.ACTIVE, EmployeeStatus.TERMINATED, EmployeeStatus.INACTIVE));
        map.put(EmployeeStatus.RESIGNED, EnumSet.of(EmployeeStatus.INACTIVE));
        map.put(EmployeeStatus.TERMINATED, EnumSet.of(EmployeeStatus.INACTIVE));
        map.put(EmployeeStatus.INACTIVE, EnumSet.noneOf(EmployeeStatus.class));
        return Map.copyOf(map);
    }
}
