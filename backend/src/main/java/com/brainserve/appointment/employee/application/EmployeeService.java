package com.brainserve.appointment.employee.application;

import com.brainserve.appointment.audit.api.AuditService;
import com.brainserve.appointment.audit.api.RejectedSecurityAuditService;
import com.brainserve.appointment.departmenthr.api.DepartmentHrDirectory;
import com.brainserve.appointment.employee.api.EmployeeDirectory;
import com.brainserve.appointment.employee.api.EmployeeEvents;
import com.brainserve.appointment.employee.api.EmployeeStatistics;
import com.brainserve.appointment.employee.domain.Employee;
import com.brainserve.appointment.employee.domain.EmployeeStatus;
import com.brainserve.appointment.employee.infrastructure.EmployeeRepository;
import com.brainserve.appointment.iam.api.AppointmentHostDirectory;
import com.brainserve.appointment.iam.api.IdentityProvisioningService;
import com.brainserve.appointment.iam.api.StaffCommunicationDirectory;
import com.brainserve.appointment.manager.api.ManagerDirectory;
import com.brainserve.appointment.organization.api.OrganizationDirectory;
import com.brainserve.appointment.shared.application.BusinessException;
import com.brainserve.appointment.teamlead.api.TeamLeadDirectory;
import jakarta.persistence.EntityManager;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class EmployeeService implements EmployeeDirectory, EmployeeStatistics {

    private final EmployeeRepository employees;
    private final OrganizationDirectory organization;
    private final IdentityProvisioningService identity;
    private final EntityManager entityManager;
    private final ApplicationEventPublisher events;
    private final AppointmentHostDirectory appointmentHosts;
    private final StaffCommunicationDirectory staff;
    private final AuditService audit;
    private final RejectedSecurityAuditService rejectedSecurityAudit;
    private final DepartmentHrDirectory departmentHrs;
    private final TeamLeadDirectory teamLeads;
    private final ManagerDirectory managers;
    private final SecureRandom random = new SecureRandom();

    public EmployeeService(
            EmployeeRepository employees,
            OrganizationDirectory organization,
            IdentityProvisioningService identity,
            EntityManager entityManager,
            ApplicationEventPublisher events,
            AppointmentHostDirectory appointmentHosts,
            StaffCommunicationDirectory staff,
            AuditService audit,
            RejectedSecurityAuditService rejectedSecurityAudit,
            @Lazy DepartmentHrDirectory departmentHrs,
            @Lazy TeamLeadDirectory teamLeads,
            @Lazy ManagerDirectory managers
    ) {
        this.employees = employees;
        this.organization = organization;
        this.identity = identity;
        this.entityManager = entityManager;
        this.events = events;
        this.appointmentHosts = appointmentHosts;
        this.staff = staff;
        this.audit = audit;
        this.rejectedSecurityAudit = rejectedSecurityAudit;
        this.departmentHrs = departmentHrs;
        this.teamLeads = teamLeads;
        this.managers = managers;
    }

    @Transactional
    public CreatedEmployee create(UUID actorId, CreateEmployee command) {
        if (employees.existsByOfficialEmailIgnoreCase(command.officialEmail())) {
            throw new BusinessException(
                    "EMPLOYEE_EMAIL_EXISTS",
                    "Official email is already assigned",
                    HttpStatus.CONFLICT
            );
        }

        var department =
                organization.requireActiveDepartment(command.departmentId());

        long sequence = nextEmployeeNumberSequence();

        String employeeNumber =
                "BSPL-"
                        + department.code()
                        + "-"
                        + String.format("%04d", sequence);

        Employee employee = employees.saveAndFlush(
                new Employee(
                        employeeNumber,
                        command.firstName(),
                        command.lastName(),
                        command.officialEmail(),
                        command.phoneNumber(),
                        command.departmentId(),
                        command.designation(),
                        command.joiningDate()
                )
        );

        String temporaryPassword = generateTemporaryPassword();

        identity.createEmployeeAccount(
                employee.getId(),
                employee.getDisplayName(),
                employee.getOfficialEmail(),
                temporaryPassword,
                actorId
        );

        events.publishEvent(
                new EmployeeEvents.EmployeeCreated(
                        employee.getId(),
                        employee.getEmployeeNumber(),
                        employee.getOfficialEmail(),
                        Instant.now()
                )
        );

        return new CreatedEmployee(employee, temporaryPassword);
    }

    @Transactional(readOnly = true)
    public Page<Employee> list(
            UUID actorUserId,
            String query,
            UUID requestedDepartmentId,
            EmployeeStatus status,
            Pageable pageable
    ) {
        UUID departmentId =
                effectiveDepartmentScope(
                        actorUserId,
                        requestedDepartmentId
                );

        /*
         * Never pass null to the PostgreSQL search query.
         * Passing null previously caused:
         *
         * function lower(bytea) does not exist
         */
        String normalizedQuery =
                query == null
                        ? ""
                        : query.trim().toLowerCase(Locale.ROOT);

        boolean hasQuery = !normalizedQuery.isBlank();

        return employees.search(
                departmentId,
                status,
                hasQuery,
                normalizedQuery,
                pageable
        );
    }

    @Transactional(readOnly = true)
    public List<DepartmentEmployeeSummary> departmentSummaries(
            UUID actorUserId
    ) {
        UUID departmentScope =
                assignedDepartmentScope(actorUserId);

        return employees.summarizeByDepartment()
                .stream()
                .filter(value ->
                        departmentScope == null
                                || departmentScope.equals(
                                value.getDepartmentId()
                        )
                )
                .map(value ->
                        new DepartmentEmployeeSummary(
                                value.getDepartmentId(),
                                value.getTotalEmployees(),
                                value.getActiveEmployees(),
                                value.getOnLeaveEmployees(),
                                value.getOnboardingEmployees()
                        )
                )
                .toList();
    }

    @Transactional(readOnly = true)
    public Employee getVisible(
            UUID actorUserId,
            UUID employeeId
    ) {
        Employee employee = get(employeeId);

        requireWithinActorDepartment(
                actorUserId,
                employee
        );

        return employee;
    }

    @Transactional
    public CreatedEmployee createScoped(
            UUID actorUserId,
            CreateEmployee command
    ) {
        requireRequestedDepartment(
                actorUserId,
                command.departmentId()
        );

        return create(actorUserId, command);
    }

    @Transactional
    public Employee changeStatusScoped(
            UUID actorUserId,
            UUID employeeId,
            EmployeeStatus next
    ) {
        Employee employee = get(employeeId);

        requireWithinActorDepartment(
                actorUserId,
                employee
        );

        requireLifecycleAuthority(
                actorUserId,
                employee
        );

        return changeStatus(employeeId, next);
    }

    @Transactional(readOnly = true)
    public Employee get(UUID id) {
        return employees.findById(id)
                .orElseThrow(this::notFound);
    }

    @Override
    @Transactional(readOnly = true)
    public void requireEmployee(UUID employeeId) {
        get(employeeId);
    }

    @Override
    @Transactional(readOnly = true)
    public void requireActiveEmployee(UUID employeeId) {
        if (get(employeeId).getStatus() != EmployeeStatus.ACTIVE) {
            throw new BusinessException(
                    "TEAM_LEAD_EMPLOYEE_NOT_ACTIVE",
                    "Only an active employee can be promoted to Team Lead",
                    HttpStatus.UNPROCESSABLE_ENTITY
            );
        }
    }

    @Override
    @Transactional(readOnly = true)
    public void requireActiveHost(UUID employeeId) {
        if (!get(employeeId).isAvailableAsHost()
                || !appointmentHosts.isAccountEligible(employeeId)) {
            throw new BusinessException(
                    "HOST_NOT_ACTIVE",
                    "Appointment host must be an active employee",
                    HttpStatus.UNPROCESSABLE_ENTITY
            );
        }
    }

    @Override
    @Transactional(readOnly = true)
    public HostCategory hostCategory(UUID employeeId) {
        requireActiveHost(employeeId);

        return HostCategory.valueOf(
                appointmentHosts.categoryForEmployee(employeeId)
        );
    }

    @Override
    @Transactional(readOnly = true)
    public UUID departmentIdForEmployee(UUID employeeId) {
        return get(employeeId).getDepartmentId();
    }

    @Override
    @Transactional
    public void transferDepartment(
            UUID employeeId,
            UUID departmentId
    ) {
        organization.requireActiveDepartment(departmentId);

        Employee employee = employees
                .findByIdForUpdate(employeeId)
                .orElseThrow(this::notFound);

        if (employee.getStatus() != EmployeeStatus.ACTIVE) {
            throw new BusinessException(
                    "ROLE_TRANSITION_EMPLOYEE_NOT_ACTIVE",
                    "Only an active employee can receive a department leadership assignment",
                    HttpStatus.UNPROCESSABLE_ENTITY
            );
        }

        employee.transferDepartment(departmentId);
        employees.saveAndFlush(employee);
    }

    @Override
    @Transactional
    public void transitionOperationalPosition(
            UUID employeeId,
            UUID departmentId,
            String designation
    ) {
        organization.requireActiveDepartment(departmentId);

        Employee employee = employees
                .findByIdForUpdate(employeeId)
                .orElseThrow(this::notFound);

        if (employee.getStatus() != EmployeeStatus.ACTIVE) {
            throw new BusinessException(
                    "ROLE_TRANSITION_EMPLOYEE_NOT_ACTIVE",
                    "Only an active employee can receive a new operational position",
                    HttpStatus.UNPROCESSABLE_ENTITY
            );
        }

        employee.transitionOperationalPosition(
                departmentId,
                designation
        );

        employees.saveAndFlush(employee);
    }

    @Override
    @Transactional
    public void deactivateForAccountArchive(UUID employeeId) {
        Employee employee = employees
                .findByIdForUpdate(employeeId)
                .orElseThrow(this::notFound);

        employee.deactivateForAccountArchive();
        employees.saveAndFlush(employee);
    }

    @Override
    @Transactional
    public void restoreAfterAccountRecovery(
            UUID employeeId,
            UUID departmentId,
            String designation
    ) {
        organization.requireActiveDepartment(departmentId);

        Employee employee = employees
                .findByIdForUpdate(employeeId)
                .orElseThrow(this::notFound);

        employee.restoreAfterAccountRecovery(
                departmentId,
                designation
        );

        employees.saveAndFlush(employee);
    }

    @Transactional
    public Employee upsertExecutiveProfile(
            UUID actorUserId,
            ExecutiveProfile command
    ) {
        var account = staff.requireActive(actorUserId);

        if (!account.roles().contains("ROLE_CEO")) {
            throw new BusinessException(
                    "CEO_ROLE_REQUIRED",
                    "Only the CEO can register an executive department profile",
                    HttpStatus.FORBIDDEN
            );
        }

        var department =
                organization.requireActiveDepartment(
                        command.departmentId()
                );

        Employee employee;

        if (account.employeeId() != null) {
            employee = get(account.employeeId());

            employee.transferDepartment(department.id());

            employee.update(
                    employee.getFirstName(),
                    employee.getLastName(),
                    command.phoneNumber(),
                    command.designation()
            );
        } else {
            if (employees.existsByOfficialEmailIgnoreCase(
                    account.email()
            )) {
                throw new BusinessException(
                        "EXECUTIVE_PROFILE_EMAIL_EXISTS",
                        "An employee profile already uses the CEO email",
                        HttpStatus.CONFLICT
                );
            }

            String[] names = splitName(account.fullName());

            long sequence = nextEmployeeNumberSequence();

            String employeeNumber =
                    "BSPL-"
                            + department.code()
                            + "-"
                            + String.format("%04d", sequence);

            employee = new Employee(
                    employeeNumber,
                    names[0],
                    names[1],
                    account.email(),
                    command.phoneNumber(),
                    department.id(),
                    command.designation(),
                    command.joiningDate()
            );

            employee.transitionTo(EmployeeStatus.ACTIVE);
            employee = employees.saveAndFlush(employee);

            appointmentHosts.linkEmployee(
                    actorUserId,
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
        }

        audit.record(
                "CEO_DEPARTMENT_PROFILE_UPDATED",
                "EMPLOYEE",
                employee.getId().toString(),
                "{\"departmentId\":\""
                        + department.id()
                        + "\",\"departmentCode\":\""
                        + department.code()
                        + "\"}"
        );

        return employee;
    }

    @Override
    @Transactional(readOnly = true)
    public EmployeeSummary employeeSummary(UUID employeeId) {
        Employee employee = get(employeeId);
        return summary(employee);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, EmployeeSummary> employeeSummaries(Set<UUID> employeeIds) {
        if (employeeIds == null || employeeIds.isEmpty()) return Map.of();
        java.util.LinkedHashMap<UUID, EmployeeSummary> summaries = new java.util.LinkedHashMap<>();
        employees.findAllById(employeeIds).forEach(employee ->
                summaries.put(employee.getId(), summary(employee)));
        return Map.copyOf(summaries);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<DepartmentMember> departmentMembers(
            UUID departmentId,
            Pageable pageable
    ) {
        return employees.findAllByDepartmentId(
                        departmentId,
                        pageable
                )
                .map(employee ->
                        new DepartmentMember(
                                employee.getId(),
                                employee.getEmployeeNumber(),
                                employee.getDisplayName(),
                                employee.getOfficialEmail(),
                                employee.getDepartmentId(),
                                employee.getDesignation(),
                                employee.getStatus().name()
                        )
                );
    }

    private EmployeeSummary summary(Employee employee) {
        return new EmployeeSummary(
                employee.getId(),
                employee.getEmployeeNumber(),
                employee.getDisplayName(),
                employee.getOfficialEmail(),
                employee.getDepartmentId(),
                employee.getDesignation(),
                employee.getStatus().name()
        );
    }

    @Override
    @Transactional(readOnly = true)
    public Page<PublicEmployee> publicActiveEmployees(
            UUID departmentId,
            String query,
            Pageable pageable
    ) {
        organization.requireActiveDepartment(departmentId);

        /*
         * Never pass null to EmployeeRepository.search().
         */
        String normalizedQuery =
                query == null
                        ? ""
                        : query.trim().toLowerCase(Locale.ROOT);

        boolean hasQuery = !normalizedQuery.isBlank();

        return employees.search(
                        departmentId,
                        EmployeeStatus.ACTIVE,
                        hasQuery,
                        normalizedQuery,
                        pageable
                )
                .map(employee ->
                        new PublicEmployee(
                                employee.getId(),
                                employee.getDisplayName(),
                                employee.getDesignation(),
                                employee.getDepartmentId()
                        )
                );
    }

    @Override
    @Transactional(readOnly = true)
    public List<HostSummary> activeHosts() {
        java.util.LinkedHashMap<UUID, HostSummary> hosts =
                new java.util.LinkedHashMap<>();

        appointmentHosts.activeChiefExecutives()
                .forEach(account ->
                        addLeadershipHost(
                                hosts,
                                account.employeeId(),
                                HostCategory.CEO
                        )
                );

        appointmentHosts.activeHumanResourcesAdministrators()
                .forEach(account ->
                        addLeadershipHost(
                                hosts,
                                account.employeeId(),
                                HostCategory.HR
                        )
                );

        teamLeads.activeAssignments()
                .forEach(assignment ->
                        addLeadershipHost(
                                hosts,
                                assignment.teamLeadEmployeeId(),
                                HostCategory.TEAM_LEAD
                        )
                );

        return hosts.values()
                .stream()
                .sorted(
                        java.util.Comparator.comparing(
                                HostSummary::displayName
                        )
                )
                .toList();
    }

    private void addLeadershipHost(
            java.util.Map<UUID, HostSummary> hosts,
            UUID employeeId,
            HostCategory category
    ) {
        if (employeeId == null
                || hosts.containsKey(employeeId)) {
            return;
        }

        employees.findById(employeeId)
                .filter(employee ->
                        employee.getStatus() == EmployeeStatus.ACTIVE
                )
                .ifPresent(employee -> {
                    var department =
                            organization.requireActiveDepartment(
                                    employee.getDepartmentId()
                            );

                    hosts.put(
                            employeeId,
                            new HostSummary(
                                    employeeId,
                                    employee.getDisplayName(),
                                    employee.getDesignation(),
                                    department.id(),
                                    department.name(),
                                    category
                            )
                    );
                });
    }

    @Override
    @Transactional(readOnly = true)
    public long totalEmployees() {
        return employees.count();
    }

    @Override
    @Transactional(readOnly = true)
    public long activeEmployees() {
        return employees.countByStatus(EmployeeStatus.ACTIVE);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isChiefExecutive(UUID employeeId) {
        return chiefExecutiveEmployeeIds()
                .contains(employeeId);
    }

    @Transactional(readOnly = true)
    public Set<UUID> chiefExecutiveEmployeeIds() {
        Set<UUID> protectedEmployeeIds =
                new HashSet<>();

        staff.activeWithAnyRole(Set.of("ROLE_CEO"))
                .forEach(account -> {
                    if (account.employeeId() != null) {
                        protectedEmployeeIds.add(
                                account.employeeId()
                        );
                    }

                    employees.findByOfficialEmailIgnoreCase(
                                    account.email()
                            )
                            .map(Employee::getId)
                            .ifPresent(
                                    protectedEmployeeIds::add
                            );
                });

        return Set.copyOf(protectedEmployeeIds);
    }

    @Transactional
    public Employee changeStatus(
            UUID id,
            EmployeeStatus next
    ) {
        if (next == EmployeeStatus.TERMINATED) {
            throw new BusinessException(
                    "TERMINATION_APPROVAL_REQUIRED",
                    "Employee termination requires an HR request and CEO approval",
                    HttpStatus.CONFLICT
            );
        }

        Employee employee = get(id);
        EmployeeStatus previous = employee.getStatus();

        employee.transitionTo(next);

        if (next == EmployeeStatus.RESIGNED
                || next == EmployeeStatus.INACTIVE) {
            identity.disableEmployeeAccount(
                    employee.getId()
            );
        }

        events.publishEvent(
                new EmployeeEvents.EmployeeStatusChanged(
                        id,
                        previous.name(),
                        next.name(),
                        Instant.now()
                )
        );

        return employee;
    }

    private UUID effectiveDepartmentScope(
            UUID actorUserId,
            UUID requestedDepartmentId
    ) {
        UUID assigned =
                assignedDepartmentScope(actorUserId);

        if (assigned != null) {
            if (requestedDepartmentId != null
                    && !assigned.equals(
                    requestedDepartmentId
            )) {
                throw departmentScopeDenied();
            }

            return assigned;
        }

        return requestedDepartmentId;
    }

    private UUID assignedDepartmentScope(
            UUID actorUserId
    ) {
        var actor = staff.requireActive(actorUserId);

        /*
         * Department-scoped roles take precedence over any
         * broader employee permission on the same account.
         */
        if (actor.roles().contains("ROLE_HR_ADMIN")) {
            return departmentHrs
                    .requireForUser(actorUserId)
                    .departmentId();
        }

        if (actor.roles().contains("ROLE_TEAM_LEAD")) {
            return teamLeads
                    .requireForUser(actorUserId)
                    .departmentId();
        }

        if (actor.roles().contains("ROLE_MANAGER")) {
            return managers
                    .requireForUser(actorUserId)
                    .departmentId();
        }

        if (actor.roles().contains("ROLE_EMPLOYEE")) {
            if (actor.employeeId() == null) {
                throw new BusinessException(
                        "EMPLOYEE_PROFILE_NOT_LINKED",
                        "This Employee login is not linked to an employee profile",
                        HttpStatus.UNPROCESSABLE_ENTITY
                );
            }
            return departmentIdForEmployee(actor.employeeId());
        }

        return null;
    }

    private void requireRequestedDepartment(
            UUID actorUserId,
            UUID requestedDepartmentId
    ) {
        UUID assigned =
                assignedDepartmentScope(actorUserId);

        if (assigned != null
                && !assigned.equals(
                requestedDepartmentId
        )) {
            throw departmentScopeDenied();
        }
    }

    private void requireWithinActorDepartment(
            UUID actorUserId,
            Employee employee
    ) {
        UUID assigned =
                assignedDepartmentScope(actorUserId);

        if (assigned != null
                && !assigned.equals(
                employee.getDepartmentId()
        )) {
            throw departmentScopeDenied();
        }
    }

    private void requireLifecycleAuthority(
            UUID actorUserId,
            Employee employee
    ) {
        if (!isChiefExecutive(employee.getId())) {
            return;
        }

        UUID targetUserId =
                staff.activeWithAnyRole(Set.of("ROLE_CEO"))
                        .stream()
                        .filter(account ->
                                employee.getId().equals(
                                        account.employeeId()
                                )
                                        || account.email()
                                        .equalsIgnoreCase(
                                                employee.getOfficialEmail()
                                        )
                        )
                        .map(
                                StaffCommunicationDirectory
                                        .StaffMember::userId
                        )
                        .findFirst()
                        .orElse(null);

        rejectedSecurityAudit.record(
                "CEO_LIFECYCLE_CHANGE_BLOCKED",
                "EMPLOYEE",
                employee.getId().toString(),
                "{\"actorUserId\":\""
                        + actorUserId
                        + "\",\"targetUserId\":\""
                        + targetUserId
                        + "\",\"reason\":\"SYSTEM_ADMIN_GOVERNANCE_REQUIRED\"}"
        );

        throw new BusinessException(
                "CEO_LIFECYCLE_PROTECTED",
                "The CEO is a company-wide authority. "
                        + "Department HR cannot change the CEO lifecycle; "
                        + "use the System Admin CEO succession workflow.",
                HttpStatus.FORBIDDEN
        );
    }

    private BusinessException departmentScopeDenied() {
        return new BusinessException(
                "EMPLOYEE_DEPARTMENT_SCOPE_DENIED",
                "This employee belongs to another department",
                HttpStatus.FORBIDDEN
        );
    }

    @Override
    @Transactional
    public void terminateAfterApproval(
            UUID employeeId,
            LocalDate effectiveDate
    ) {
        Employee employee = get(employeeId);
        EmployeeStatus previous = employee.getStatus();

        employee.terminate(effectiveDate);

        identity.disableEmployeeAccount(
                employee.getId()
        );

        events.publishEvent(
                new EmployeeEvents.EmployeeStatusChanged(
                        employeeId,
                        previous.name(),
                        EmployeeStatus.TERMINATED.name(),
                        Instant.now()
                )
        );
    }

    @SuppressWarnings({"SqlResolve", "SqlDialectInspection", "SqlNoDataSourceInspection"})
    private long nextEmployeeNumberSequence() {
        return ((Number) entityManager
                .createNativeQuery(
                        "select nextval(to_regclass(:sequenceName))"
                )
                .setParameter("sequenceName", "employee_number_seq")
                .getSingleResult())
                .longValue();
    }

    @SuppressWarnings("SpellCheckingInspection")
    private String generateTemporaryPassword() {
        final String alphabet =
                "ABCDEFGHJKLMNPQRSTUVWXYZ"
                        + "abcdefghijkmnopqrstuvwxyz"
                        + "23456789";

        StringBuilder value =
                new StringBuilder("Bs!")
                        .append(random.nextInt(10));

        while (value.length() < 16) {
            value.append(
                    alphabet.charAt(
                            random.nextInt(
                                    alphabet.length()
                            )
                    )
            );
        }

        return value.toString();
    }

    private String[] splitName(String fullName) {
        String normalized =
                fullName == null
                        ? "CEO"
                        : fullName.trim()
                        .replaceAll("\\s+", " ");

        int separator = normalized.indexOf(' ');

        return separator < 0
                ? new String[]{
                normalized,
                ""
        }
                : new String[]{
                normalized.substring(
                        0,
                        separator
                ),
                normalized.substring(
                        separator + 1
                )
        };
    }

    private BusinessException notFound() {
        return new BusinessException(
                "EMPLOYEE_NOT_FOUND",
                "Employee was not found",
                HttpStatus.NOT_FOUND
        );
    }

    public record CreateEmployee(
            String firstName,
            String lastName,
            String officialEmail,
            String phoneNumber,
            UUID departmentId,
            String designation,
            LocalDate joiningDate
    ) {
    }

    public record ExecutiveProfile(
            UUID departmentId,
            String phoneNumber,
            String designation,
            LocalDate joiningDate
    ) {
    }

    public record CreatedEmployee(
            Employee employee,
            String temporaryPassword
    ) {
    }

    public record DepartmentEmployeeSummary(
            UUID departmentId,
            long totalEmployees,
            long activeEmployees,
            long onLeaveEmployees,
            long onboardingEmployees
    ) {
    }
}
