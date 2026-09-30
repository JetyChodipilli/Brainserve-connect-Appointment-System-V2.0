package com.brainserve.appointment.employee.application;

import com.brainserve.appointment.employee.domain.Employee;
import com.brainserve.appointment.employee.domain.EmployeeStatus;
import com.brainserve.appointment.employee.infrastructure.EmployeeRepository;
import com.brainserve.appointment.iam.api.AppointmentHostDirectory;
import com.brainserve.appointment.organization.api.OrganizationDirectory;
import jakarta.persistence.EntityManager;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

@Component
@Order(30)
public class PrivilegedHostBootstrap implements ApplicationRunner {
    private final EmployeeRepository employees;
    private final AppointmentHostDirectory appointmentHosts;
    private final EntityManager entityManager;
    private final OrganizationDirectory organization;

    public PrivilegedHostBootstrap(EmployeeRepository employees, AppointmentHostDirectory appointmentHosts,
                                   EntityManager entityManager, OrganizationDirectory organization) {
        this.employees = employees; this.appointmentHosts = appointmentHosts; this.entityManager = entityManager;
        this.organization = organization;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        synchronizePrivilegedHosts();
    }

    @Scheduled(fixedDelayString = "${brainserve.appointment.host-sync-ms:60000}")
    @Transactional
    public void synchronizePrivilegedHosts() {
        synchronize(appointmentHosts.activeChiefExecutives(), "EXEC", "Chief Executive Officer", "CEO");
        synchronize(appointmentHosts.activeHumanResourcesAdministrators(), "HR", "HR Administrator", "HR Admin");
    }

    private void synchronize(java.util.List<AppointmentHostDirectory.HostAccount> accounts, String departmentCode,
                             String designation, String fallbackLastName) {
        for (AppointmentHostDirectory.HostAccount account : accounts) {
            if (account.employeeId() != null) {
                employees.findById(account.employeeId()).ifPresent(this::activateNewHostProfile);
                continue;
            }
            Employee employee = employees.findByOfficialEmailIgnoreCase(account.email())
                    .orElseGet(() -> createProfile(account, departmentCode, designation, fallbackLastName));
            activateNewHostProfile(employee);
            appointmentHosts.linkEmployee(account.userId(), employee.getId());
        }
    }

    private Employee createProfile(AppointmentHostDirectory.HostAccount account, String departmentCode,
                                   String designation, String fallbackLastName) {
        long sequence = ((Number) entityManager.createNativeQuery("select nextval('employee_number_seq')")
                .getSingleResult()).longValue();
        String normalizedName = account.fullName().trim().replaceAll("\\s+", " ");
        int split = normalizedName.lastIndexOf(' ');
        String firstName = split > 0 ? normalizedName.substring(0, split) : normalizedName;
        String lastName = split > 0 ? normalizedName.substring(split + 1) : fallbackLastName;
        UUID departmentId = organization.requireActiveDepartmentByCode(departmentCode).id();
        return employees.saveAndFlush(new Employee("BSPL-" + departmentCode + "-" + String.format("%04d", sequence),
                firstName, lastName, account.email(), null, departmentId,
                designation, LocalDate.now(ZoneId.of("Asia/Kolkata"))));
    }

    private void activateNewHostProfile(Employee employee) {
        if (employee.getStatus() == EmployeeStatus.ONBOARDING) employee.transitionTo(EmployeeStatus.ACTIVE);
    }
}
