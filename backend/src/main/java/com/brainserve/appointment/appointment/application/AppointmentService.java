package com.brainserve.appointment.appointment.application;

import com.brainserve.appointment.appointment.api.AppointmentEvents;
import com.brainserve.appointment.appointment.api.AppointmentAccess;
import com.brainserve.appointment.appointment.api.AppointmentAvailability;
import com.brainserve.appointment.appointment.api.AppointmentStatistics;
import com.brainserve.appointment.appointment.api.AppointmentRecords;
import com.brainserve.appointment.appointment.domain.Appointment;
import com.brainserve.appointment.appointment.domain.AppointmentStatus;
import com.brainserve.appointment.appointment.domain.AppointmentType;
import com.brainserve.appointment.appointment.infrastructure.AppointmentRepository;
import com.brainserve.appointment.employee.api.EmployeeDirectory;
import com.brainserve.appointment.configuration.api.WorkspacePolicy;
import com.brainserve.appointment.audit.api.AuditService;
import com.brainserve.appointment.teamlead.api.TeamLeadDirectory;
import com.brainserve.appointment.departmenthr.api.DepartmentHrDirectory;
import com.brainserve.appointment.manager.api.ManagerDirectory;
import com.brainserve.appointment.shared.application.BusinessException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HexFormat;
import java.util.Locale;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

@Service
public class AppointmentService implements AppointmentAvailability, AppointmentAccess, AppointmentStatistics, AppointmentRecords {
    private final AppointmentRepository appointments;
    private final EmployeeDirectory employees;
    private final StringRedisTemplate redis;
    private final ApplicationEventPublisher events;
    private final ZoneId officeZone;
    private final int slotMinutes;
    private final int maxAdvanceDays;
    private final WorkspacePolicy workspacePolicy;
    private final AuditService audit;
    private final TeamLeadDirectory teamLeads;
    private final DepartmentHrDirectory departmentHrs;
    private final ManagerDirectory managers;
    private final SecureRandom random = new SecureRandom();

    public AppointmentService(AppointmentRepository appointments, EmployeeDirectory employees,
                              StringRedisTemplate redis, ApplicationEventPublisher events,
                              @Value("${brainserve.appointment.office-zone}") String officeZone,
                              @Value("${brainserve.appointment.slot-minutes}") int slotMinutes,
                              @Value("${brainserve.appointment.max-advance-days:90}") int maxAdvanceDays,
                              WorkspacePolicy workspacePolicy, AuditService audit, TeamLeadDirectory teamLeads,
                              DepartmentHrDirectory departmentHrs, ManagerDirectory managers) {
        this.appointments = appointments; this.employees = employees; this.redis = redis; this.events = events;
        this.officeZone = ZoneId.of(officeZone); this.slotMinutes = slotMinutes; this.maxAdvanceDays = maxAdvanceDays;
        this.workspacePolicy = workspacePolicy;
        this.audit = audit;
        this.teamLeads = teamLeads;
        this.departmentHrs = departmentHrs;
        this.managers = managers;
    }

    @Transactional
    public Appointment request(String idempotencyKey, CreateAppointment command) {
        return appointments.findByIdempotencyKey(idempotencyKey).orElseGet(() -> create(idempotencyKey, command));
    }

    @Transactional
    public Appointment registerAtReception(String idempotencyKey, UUID receptionistUserId, CreateAppointment command) {
        return appointments.findByIdempotencyKey(idempotencyKey)
                .orElseGet(() -> createAtReception(idempotencyKey, receptionistUserId, command));
    }

    @Transactional
    public Appointment registerAtSecurity(String idempotencyKey, UUID securityUserId,
                                          CreateAppointment command, SecurityIntake intake) {
        return appointments.findByIdempotencyKey(idempotencyKey)
                .orElseGet(() -> createAtSecurity(idempotencyKey, securityUserId, command, intake));
    }

    private Appointment create(String idempotencyKey, CreateAppointment command) {
        Appointment appointment = save(newAppointment(idempotencyKey, command));
        String otp = String.format("%06d", random.nextInt(1_000_000));
        redis.opsForValue().set(otpKey(appointment.getReferenceNumber()), hash(otp), Duration.ofMinutes(10));
        events.publishEvent(new AppointmentEvents.AppointmentRequested(appointment.getId(), appointment.getReferenceNumber(),
                command.visitorEmail(), otp, Instant.now()));
        audit.record("APPOINTMENT_REQUESTED", "APPOINTMENT", appointment.getId().toString(),
                "{\"reference\":\"" + appointment.getReferenceNumber() + "\"}");
        return appointment;
    }

    private Appointment createAtReception(String idempotencyKey, UUID receptionistUserId, CreateAppointment command) {
        Appointment appointment = newAppointment(idempotencyKey, command);
        appointment.submitByReception(receptionistUserId);
        appointment = save(appointment);
        events.publishEvent(new AppointmentEvents.AppointmentStatusChanged(appointment.getId(),
                appointment.getReferenceNumber(), appointment.getStatus().name(), Instant.now()));
        audit.record("VISITOR_RECEPTION_REGISTERED", "APPOINTMENT", appointment.getId().toString(),
                "{\"reference\":\"" + appointment.getReferenceNumber() + "\"}");
        return appointment;
    }

    private Appointment createAtSecurity(String idempotencyKey, UUID securityUserId,
                                         CreateAppointment command, SecurityIntake intake) {
        Appointment appointment = newAppointment(idempotencyKey, command);
        appointment.submitByReception(securityUserId);
        appointment.recordSecurityIntake(securityUserId, intake.visitorName(), intake.purpose(),
                intake.identityDocumentType(), intake.identityDocumentLastFour(), intake.notes());
        appointment = save(appointment);
        audit.record("VISITOR_SECURITY_WALK_IN_CREATED", "APPOINTMENT", appointment.getId().toString(),
                "{\"reference\":\"" + appointment.getReferenceNumber() + "\"}");
        events.publishEvent(new AppointmentEvents.SecurityIntakeRecorded(appointment.getId(),
                appointment.getReferenceNumber(), securityUserId, appointment.getArrivalVisitorName(),
                appointment.getArrivalPurpose(), Instant.now()));
        publishStatus(appointment);
        return appointment;
    }

    private Appointment newAppointment(String idempotencyKey, CreateAppointment command) {
        validateBookableSlot(command.type(), command.slotStart(), command.slotEnd());
        employees.requireActiveHost(command.hostEmployeeId());
        EmployeeDirectory.HostCategory category = employees.hostCategory(command.hostEmployeeId());
        if (command.type() == AppointmentType.CEO_VISIT && category != EmployeeDirectory.HostCategory.CEO) {
            throw new BusinessException("HOST_ROLE_MISMATCH",
                    "CEO visits must select an active CEO host", HttpStatus.UNPROCESSABLE_ENTITY);
        }
        if ((command.type() == AppointmentType.HR_VISIT || command.type() == AppointmentType.INTERVIEW)
                && category != EmployeeDirectory.HostCategory.HR) {
            throw new BusinessException("HOST_ROLE_MISMATCH",
                    "HR visits and interviews must select an active HR host", HttpStatus.UNPROCESSABLE_ENTITY);
        }
        UUID departmentId = command.routingDepartmentId();
        if (command.type() == AppointmentType.CLIENT_MEETING) {
            requireCategory(category, EmployeeDirectory.HostCategory.TEAM_LEAD,
                    "Client meetings must select the active Team Lead of a department");
            departmentId = requireHostDepartment(command.hostEmployeeId(), departmentId);
            var lead = teamLeads.requireAssignedForHost(
                    activeTeamLeadUser(command.hostEmployeeId()), command.hostEmployeeId());
            if (!lead.teamLeadEmployeeId().equals(command.hostEmployeeId())) {
                throw new BusinessException("HOST_ROLE_MISMATCH",
                        "Client meetings must select the active Team Lead of a department", HttpStatus.UNPROCESSABLE_ENTITY);
            }
            departmentHrs.requireForDepartment(departmentId);
        }
        if (command.type() == AppointmentType.EMERGENCY
                && category != EmployeeDirectory.HostCategory.CEO && category != EmployeeDirectory.HostCategory.HR) {
            throw new BusinessException("HOST_ROLE_MISMATCH",
                    "Emergency meetings must select an active CEO or department HR host", HttpStatus.UNPROCESSABLE_ENTITY);
        }
        if ((command.type() == AppointmentType.CEO_VISIT
                || (command.type() == AppointmentType.EMERGENCY && category == EmployeeDirectory.HostCategory.CEO))) {
            if (departmentId == null) throw new BusinessException("VISIT_DEPARTMENT_REQUIRED",
                    "Select the department Manager that must review this CEO visit",
                    HttpStatus.UNPROCESSABLE_ENTITY);
            managers.requireForDepartment(departmentId);
        }
        if (command.type() == AppointmentType.HR_VISIT || command.type() == AppointmentType.INTERVIEW
                || (command.type() == AppointmentType.EMERGENCY && category == EmployeeDirectory.HostCategory.HR)) {
            if (departmentId == null) departmentId = departmentHrs.activeForHrEmployee(command.hostEmployeeId())
                    .map(DepartmentHrDirectory.Assignment::departmentId).orElse(null);
            requireDepartmentHrHost(departmentId, command.hostEmployeeId());
        }
        if (command.type() == AppointmentType.EMPLOYEE_VISIT) {
            requireCategory(category, EmployeeDirectory.HostCategory.HR,
                    "Employee meetings must select the HR assigned to the employee department");
            if (command.requestedEmployeeId() == null) throw new BusinessException("REQUESTED_EMPLOYEE_REQUIRED",
                    "Select the employee the visitor wants to meet", HttpStatus.UNPROCESSABLE_ENTITY);
            employees.requireActiveEmployee(command.requestedEmployeeId());
            UUID employeeDepartment = employees.departmentIdForEmployee(command.requestedEmployeeId());
            if (departmentId == null) departmentId = employeeDepartment;
            if (!employeeDepartment.equals(departmentId)) throw new BusinessException("EMPLOYEE_DEPARTMENT_MISMATCH",
                    "The selected employee does not belong to the selected department", HttpStatus.UNPROCESSABLE_ENTITY);
            requireDepartmentHrHost(departmentId, command.hostEmployeeId());
        }
        if (command.type() == AppointmentType.VENDOR_VISIT || command.type() == AppointmentType.OTHER) {
            departmentId = requireHostDepartment(command.hostEmployeeId(), departmentId);
            departmentHrs.requireForDepartment(departmentId);
        }
        if (departmentId == null) {
            departmentId = requireHostDepartment(command.hostEmployeeId(), null);
        }
        if (command.type() == AppointmentType.CEO_VISIT
                || (command.type() == AppointmentType.EMERGENCY
                && category == EmployeeDirectory.HostCategory.CEO)) {
            managers.requireForDepartment(departmentId);
        } else {
            departmentHrs.requireForDepartment(departmentId);
        }
        return new Appointment("BSA-" + randomCharacters(4) + "-" + randomCharacters(4), idempotencyKey,
                command.type(), command.visitorName(), command.visitorEmail(), command.visitorPhone(),
                command.visitorCompany(), command.hostEmployeeId(), departmentId, command.requestedEmployeeId(),
                command.slotStart(), command.slotEnd(), command.purpose());
    }

    private Appointment save(Appointment appointment) {
        try {
            return appointments.saveAndFlush(appointment);
        } catch (DataIntegrityViolationException ex) {
            throw new BusinessException("SLOT_ALREADY_BOOKED", "The selected slot is no longer available", HttpStatus.CONFLICT);
        }
    }

    @Transactional
    public Appointment verify(String reference, String otp) {
        Appointment appointment = byReference(reference);
        String expected = redis.opsForValue().get(otpKey(reference));
        if (expected == null || !MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), hash(otp).getBytes(StandardCharsets.UTF_8)))
            throw new BusinessException("INVALID_OTP", "OTP is invalid or expired", HttpStatus.UNAUTHORIZED);
        appointment.verify();
        redis.delete(otpKey(reference));
        audit.record("APPOINTMENT_CONTACT_VERIFIED", "APPOINTMENT", appointment.getId().toString(),
                "{\"reference\":\"" + appointment.getReferenceNumber() + "\"}");
        events.publishEvent(new AppointmentEvents.AppointmentStatusChanged(appointment.getId(), reference, appointment.getStatus().name(), Instant.now()));
        return appointment;
    }

    @Transactional(readOnly = true)
    public Appointment byReference(String reference) {
        return appointments.findByReferenceNumber(reference.toUpperCase(Locale.ROOT))
                .orElseThrow(() -> new BusinessException("APPOINTMENT_NOT_FOUND", "Appointment was not found", HttpStatus.NOT_FOUND));
    }

    @Transactional(readOnly = true)
    public Appointment get(UUID id) { return appointments.findById(id).orElseThrow(() -> new BusinessException("APPOINTMENT_NOT_FOUND", "Appointment was not found", HttpStatus.NOT_FOUND)); }

    @Transactional(readOnly = true)
    public Page<Appointment> list(UUID userId, UUID employeeId, boolean viewAll, boolean hrView,
                                  boolean teamLeadView, boolean managerView,
                                  Instant from, Instant to, Pageable pageable) {
        if (from == null || to == null || !to.isAfter(from)) {
            throw new BusinessException("INVALID_APPOINTMENT_DATE_RANGE",
                    "A valid appointment date range is required", HttpStatus.BAD_REQUEST);
        }
        // Department-scoped roles can also hold VISITOR_REGISTER. Their department boundary must
        // take precedence over that operational permission; otherwise VISITOR_REGISTER would turn
        // an HR or Team Lead appointment query into a company-wide read.
        if (hrView) return appointments.findByRoutingDepartmentIdAndSlotStartGreaterThanEqualAndSlotStartLessThan(
                departmentHrs.requireForUser(userId).departmentId(), from, to, pageable);
        if (teamLeadView) {
            var assignment = teamLeads.requireForUser(userId);
            return appointments.findByRoutingDepartmentIdAndSlotStartGreaterThanEqualAndSlotStartLessThan(
                    assignment.departmentId(), from, to, pageable);
        }
        if (managerView) {
            var assignment = managers.requireForUser(userId);
            return appointments.findByRoutingDepartmentIdAndSlotStartGreaterThanEqualAndSlotStartLessThan(
                    assignment.departmentId(), from, to, pageable);
        }
        if (viewAll) return appointments.findBySlotStartGreaterThanEqualAndSlotStartLessThan(from, to, pageable);
        return appointments.findByHostEmployeeIdAndSlotStartGreaterThanEqualAndSlotStartLessThan(
                employeeId, from, to, pageable);
    }

    @Transactional
    public Appointment approve(UUID id, UUID actorEmployeeId, Authentication authentication, String remarks) {
        Appointment appointment = get(id);
        rejectSpecializedApprovalPath(appointment);
        boolean ceoAuthority = has(authentication, "CEO_VISIT_APPROVE");
        if (!appointment.getHostEmployeeId().equals(actorEmployeeId) && !ceoAuthority)
            throw new BusinessException("NOT_APPOINTMENT_HOST", "Only the host or an authorized delegate may approve", HttpStatus.FORBIDDEN);
        appointment.approve(actorEmployeeId, remarks);
        audit.record("APPOINTMENT_APPROVED", "APPOINTMENT", id.toString(),
                "{\"reference\":\"" + appointment.getReferenceNumber() + "\"}");
        events.publishEvent(new AppointmentEvents.AppointmentStatusChanged(id, appointment.getReferenceNumber(), appointment.getStatus().name(), Instant.now()));
        return appointment;
    }

    @Transactional
    public Appointment reject(UUID id, UUID actorEmployeeId, Authentication authentication, String remarks) {
        Appointment appointment = get(id);
        rejectSpecializedApprovalPath(appointment);
        if (!appointment.getHostEmployeeId().equals(actorEmployeeId) && !has(authentication, "CEO_VISIT_APPROVE"))
            throw new BusinessException("NOT_APPOINTMENT_HOST", "Only the host or an authorized delegate may reject", HttpStatus.FORBIDDEN);
        appointment.reject(actorEmployeeId, remarks);
        audit.record("APPOINTMENT_REJECTED", "APPOINTMENT", id.toString(),
                "{\"reference\":\"" + appointment.getReferenceNumber() + "\"}");
        events.publishEvent(new AppointmentEvents.AppointmentStatusChanged(id, appointment.getReferenceNumber(), appointment.getStatus().name(), Instant.now()));
        return appointment;
    }

    @Transactional
    public Appointment approveByHr(UUID id, UUID actorUserId, UUID actorEmployeeId, String remarks) {
        Appointment appointment = get(id);
        requireAssignedHr(appointment, actorUserId);
        TeamLeadDirectory.Assignment teamLead = null;
        if (appointment.getType() == AppointmentType.EMPLOYEE_VISIT) {
            teamLead = teamLeads.activeForHost(appointment.getRequestedEmployeeId()).orElseThrow(() ->
                    new BusinessException("TEAM_LEAD_NOT_ASSIGNED",
                            "Assign an active Team Lead to the host department before approving this visit",
                            HttpStatus.CONFLICT));
        } else if (appointment.getType() == AppointmentType.CLIENT_MEETING) {
            teamLead = teamLeads.activeForHost(appointment.getHostEmployeeId()).filter(value ->
                    value.teamLeadEmployeeId().equals(appointment.getHostEmployeeId())).orElseThrow(() ->
                    new BusinessException("TEAM_LEAD_NOT_ASSIGNED",
                            "The selected client-meeting host is no longer the active department Team Lead",
                            HttpStatus.CONFLICT));
        }
        boolean requiresManager = appointment.getType() == AppointmentType.CEO_VISIT
                || (appointment.getType() == AppointmentType.EMERGENCY
                && employees.hostCategory(appointment.getHostEmployeeId()) == EmployeeDirectory.HostCategory.CEO);
        if (requiresManager) managers.requireForDepartment(appointment.getRoutingDepartmentId());
        appointment.approveByHr(actorUserId, remarks, teamLead != null, requiresManager);
        audit.record("VISITOR_HR_APPROVED", "APPOINTMENT", id.toString(),
                "{\"routedTo\":\"" + appointment.getStatus().name() + "\"}");
        if (teamLead != null) {
            events.publishEvent(new AppointmentEvents.TeamLeadApprovalRequested(
                    appointment.getId(), appointment.getReferenceNumber(), actorUserId, teamLead.teamLeadUserId(),
                    teamLead.email(), effectiveVisitorName(appointment), effectivePurpose(appointment), Instant.now()));
            publishEmployeeVisitCard(appointment, actorUserId);
        }
        publishStatus(appointment);
        return appointment;
    }

    @Transactional
    public Appointment approveByTeamLead(UUID id, UUID actorUserId, String remarks) {
        Appointment appointment = get(id);
        teamLeads.requireAssignedForHost(actorUserId, appointment.getType() == AppointmentType.CLIENT_MEETING
                ? appointment.getHostEmployeeId() : appointment.getRequestedEmployeeId());
        appointment.approveByTeamLead(actorUserId, remarks);
        audit.record("TEAM_LEAD_VISIT_APPROVED", "APPOINTMENT", id.toString(),
                "{\"reference\":\"" + appointment.getReferenceNumber() + "\"}");
        publishEmployeeVisitCard(appointment, actorUserId);
        publishStatus(appointment); return appointment;
    }

    @Transactional
    public Appointment rejectByTeamLead(UUID id, UUID actorUserId, String remarks) {
        Appointment appointment = get(id);
        teamLeads.requireAssignedForHost(actorUserId, appointment.getType() == AppointmentType.CLIENT_MEETING
                ? appointment.getHostEmployeeId() : appointment.getRequestedEmployeeId());
        appointment.rejectByTeamLead(actorUserId, remarks);
        audit.record("TEAM_LEAD_VISIT_REJECTED", "APPOINTMENT", id.toString(),
                "{\"reference\":\"" + appointment.getReferenceNumber() + "\"}");
        publishEmployeeVisitCard(appointment, actorUserId);
        publishStatus(appointment); return appointment;
    }

    @Transactional
    public Appointment recordSecurityIntake(UUID id, UUID actorUserId, SecurityIntake command) {
        Appointment appointment = get(id);
        appointment.recordSecurityIntake(actorUserId, command.visitorName(), command.purpose(),
                command.identityDocumentType(), command.identityDocumentLastFour(), command.notes());
        audit.record("VISITOR_SECURITY_INTAKE", "APPOINTMENT", id.toString(),
                "{\"reference\":\"" + appointment.getReferenceNumber() + "\"}");
        events.publishEvent(new AppointmentEvents.SecurityIntakeRecorded(id, appointment.getReferenceNumber(),
                actorUserId, appointment.getArrivalVisitorName(), appointment.getArrivalPurpose(), Instant.now()));
        publishStatus(appointment);
        return appointment;
    }

    @Transactional
    public Appointment verifyByReception(UUID id, UUID actorUserId, String remarks) {
        Appointment appointment = get(id);
        requireCurrentOrFutureVisit(appointment);
        boolean managerRoute = appointment.getType() == AppointmentType.CEO_VISIT
                || (appointment.getType() == AppointmentType.EMERGENCY
                && employees.hostCategory(appointment.getHostEmployeeId())
                == EmployeeDirectory.HostCategory.CEO);
        if (managerRoute) managers.requireForDepartment(appointment.getRoutingDepartmentId());
        appointment.verifyByReception(actorUserId, remarks, managerRoute);
        audit.record("VISITOR_RECEPTION_VERIFIED", "APPOINTMENT", id.toString(),
                "{\"routedTo\":\"" + appointment.getStatus().name() + "\"}");
        events.publishEvent(new AppointmentEvents.ReceptionVerified(id, appointment.getReferenceNumber(),
                actorUserId, appointment.getHostEmployeeId(), appointment.getRoutingDepartmentId(),
                appointment.getType().name(), appointment.getStatus().name(),
                appointment.getArrivalVisitorName() == null ? appointment.getVisitorName()
                        : appointment.getArrivalVisitorName(),
                appointment.getArrivalPurpose() == null ? appointment.getPurpose() : appointment.getArrivalPurpose(),
                Instant.now()));
        publishStatus(appointment);
        return appointment;
    }

    @Transactional
    public Appointment rejectByReception(UUID id, UUID actorUserId, String remarks) {
        Appointment appointment = get(id);
        requireCurrentOrFutureVisit(appointment);
        appointment.rejectByReception(actorUserId, remarks);
        audit.record("VISITOR_RECEPTION_REJECTED", "APPOINTMENT", id.toString(), "{\"rejected\":true}");
        publishStatus(appointment);
        return appointment;
    }

    @Transactional
    public Appointment forwardByReception(UUID id, UUID actorUserId, String remarks) {
        Appointment appointment = get(id);
        requireCurrentOrFutureVisit(appointment);
        appointment.forwardByReception(actorUserId, remarks);
        audit.record("VISITOR_FORWARDED_BY_RECEPTION", "APPOINTMENT", id.toString(),
                "{\"reference\":\"" + appointment.getReferenceNumber() + "\"}");
        events.publishEvent(new AppointmentEvents.ReceptionForwarded(id, appointment.getReferenceNumber(),
                actorUserId, appointment.getHostEmployeeId(), appointment.getType().name(),
                appointment.getArrivalVisitorName() == null ? appointment.getVisitorName()
                        : appointment.getArrivalVisitorName(), remarks, Instant.now()));
        return appointment;
    }

    @Transactional
    public int cancelPastUnfinishedVisits() {
        Instant todayStart = ZonedDateTime.now(officeZone).toLocalDate().atStartOfDay(officeZone).toInstant();
        List<AppointmentStatus> cancellable = List.copyOf(EnumSet.of(
                AppointmentStatus.DRAFT, AppointmentStatus.PENDING_VERIFICATION,
                AppointmentStatus.PENDING_SECURITY_INTAKE, AppointmentStatus.PENDING_RECEPTION_VERIFICATION,
                AppointmentStatus.PENDING_HR_APPROVAL, AppointmentStatus.PENDING_TEAM_LEAD_APPROVAL,
                AppointmentStatus.PENDING_MANAGER_APPROVAL, AppointmentStatus.PENDING_CEO_APPROVAL,
                AppointmentStatus.PENDING_APPROVAL,
                AppointmentStatus.APPROVED, AppointmentStatus.RESCHEDULE_REQUESTED,
                AppointmentStatus.RESCHEDULED));
        List<Appointment> stale = appointments.findAllBySlotEndLessThanAndStatusIn(todayStart, cancellable);
        stale.forEach(appointment -> {
            appointment.cancel();
            audit.record("PAST_VISITOR_APPOINTMENT_CANCELLED", "APPOINTMENT", appointment.getId().toString(),
                    "{\"reference\":\"" + appointment.getReferenceNumber() + "\",\"reason\":\"PAST_VISIT_DATE\"}");
            publishStatus(appointment);
        });
        return stale.size();
    }

    private void requireCurrentOrFutureVisit(Appointment appointment) {
        Instant todayStart = ZonedDateTime.now(officeZone).toLocalDate().atStartOfDay(officeZone).toInstant();
        if (appointment.getSlotEnd().isBefore(todayStart)) {
            throw new BusinessException("PAST_VISIT_NOT_ACTIONABLE",
                    "Past visitor appointments cannot be verified or forwarded", HttpStatus.CONFLICT);
        }
    }

    @Transactional
    public Appointment rejectByHr(UUID id, UUID actorUserId, UUID actorEmployeeId, String remarks) {
        Appointment appointment = get(id);
        requireAssignedHr(appointment, actorUserId);
        appointment.rejectByHr(actorUserId, remarks);
        audit.record("VISITOR_HR_REJECTED", "APPOINTMENT", id.toString(),
                "{\"reference\":\"" + appointment.getReferenceNumber() + "\"}");
        publishStatus(appointment);
        return appointment;
    }

    @Transactional
    public Appointment approveByManager(UUID id, UUID actorUserId, String remarks) {
        Appointment appointment = get(id);
        requireAssignedManager(appointment, actorUserId);
        appointment.approveByManager(actorUserId, remarks);
        audit.record("VISITOR_MANAGER_APPROVED", "APPOINTMENT", id.toString(),
                "{\"reference\":\"" + appointment.getReferenceNumber()
                        + "\",\"routedTo\":\"" + appointment.getStatus().name() + "\"}");
        if (appointment.getStatus() == AppointmentStatus.PENDING_CEO_APPROVAL) {
            events.publishEvent(new AppointmentEvents.ManagerApprovalRequested(
                    appointment.getId(), appointment.getReferenceNumber(), actorUserId,
                    appointment.getRoutingDepartmentId(), effectiveVisitorName(appointment),
                    effectivePurpose(appointment), Instant.now()));
        }
        publishStatus(appointment);
        return appointment;
    }

    @Transactional
    public Appointment rejectByManager(UUID id, UUID actorUserId, String remarks) {
        Appointment appointment = get(id);
        requireAssignedManager(appointment, actorUserId);
        appointment.rejectByManager(actorUserId, remarks);
        audit.record("VISITOR_MANAGER_REJECTED", "APPOINTMENT", id.toString(),
                "{\"reference\":\"" + appointment.getReferenceNumber() + "\"}");
        publishStatus(appointment);
        return appointment;
    }

    @Transactional
    public Appointment approveByCeo(UUID id, UUID actorUserId, String remarks) {
        Appointment appointment = get(id);
        appointment.approveByCeo(actorUserId, remarks);
        audit.record("VISITOR_CEO_APPROVED", "APPOINTMENT", id.toString(),
                "{\"reference\":\"" + appointment.getReferenceNumber() + "\"}");
        events.publishEvent(new AppointmentEvents.CeoVisitDecisionRecorded(
                appointment.getId(), appointment.getReferenceNumber(), actorUserId,
                appointment.getRoutingDepartmentId(), true, remarks, Instant.now()));
        publishStatus(appointment);
        return appointment;
    }

    @Transactional
    public Appointment rejectByCeo(UUID id, UUID actorUserId, String remarks) {
        Appointment appointment = get(id);
        appointment.rejectByCeo(actorUserId, remarks);
        audit.record("VISITOR_CEO_REJECTED", "APPOINTMENT", id.toString(),
                "{\"reference\":\"" + appointment.getReferenceNumber() + "\"}");
        events.publishEvent(new AppointmentEvents.CeoVisitDecisionRecorded(
                appointment.getId(), appointment.getReferenceNumber(), actorUserId,
                appointment.getRoutingDepartmentId(), false, remarks, Instant.now()));
        publishStatus(appointment);
        return appointment;
    }

    @Transactional
    public void requestCancellationOtp(String reference) {
        Appointment appointment = byReference(reference);
        if (!EnumSet.of(AppointmentStatus.PENDING_VERIFICATION,
                AppointmentStatus.PENDING_SECURITY_INTAKE,
                AppointmentStatus.PENDING_RECEPTION_VERIFICATION,
                AppointmentStatus.PENDING_APPROVAL,
                AppointmentStatus.PENDING_HR_APPROVAL,
                AppointmentStatus.PENDING_TEAM_LEAD_APPROVAL,
                AppointmentStatus.PENDING_MANAGER_APPROVAL,
                AppointmentStatus.PENDING_CEO_APPROVAL,
                AppointmentStatus.APPROVED,
                AppointmentStatus.RESCHEDULED).contains(appointment.getStatus())) {
            throw new BusinessException("APPOINTMENT_CANCELLATION_NOT_ALLOWED",
                    "This appointment can no longer be cancelled", HttpStatus.CONFLICT);
        }
        if (Boolean.TRUE.equals(redis.hasKey(cancellationOtpCooldownKey(appointment.getReferenceNumber())))) {
            throw new BusinessException("APPOINTMENT_CANCELLATION_OTP_COOLDOWN",
                    "Wait before requesting another cancellation code", HttpStatus.TOO_MANY_REQUESTS);
        }
        String otp = String.format("%06d", random.nextInt(1_000_000));
        redis.opsForValue().set(cancellationOtpKey(appointment.getReferenceNumber()),
                hash(otp), Duration.ofMinutes(10));
        redis.opsForValue().set(cancellationOtpAttemptsKey(appointment.getReferenceNumber()),
                "5", Duration.ofMinutes(10));
        redis.opsForValue().set(cancellationOtpCooldownKey(appointment.getReferenceNumber()),
                "1", Duration.ofSeconds(60));
        events.publishEvent(new AppointmentEvents.AppointmentCancellationOtpRequested(
                appointment.getId(), appointment.getReferenceNumber(),
                appointment.getVisitorEmail(), otp, Instant.now()));
    }

    @Transactional
    public Appointment cancelPublic(String reference, String otp) {
        Appointment appointment = byReference(reference);
        String key = cancellationOtpKey(appointment.getReferenceNumber());
        String expected = redis.opsForValue().get(key);
        if (expected == null || !MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                hash(otp).getBytes(StandardCharsets.UTF_8))) {
            if (expected != null) {
                Long remaining = redis.opsForValue().decrement(
                        cancellationOtpAttemptsKey(appointment.getReferenceNumber()));
                if (remaining != null && remaining <= 0) {
                    redis.delete(List.of(key, cancellationOtpAttemptsKey(appointment.getReferenceNumber())));
                }
            }
            throw new BusinessException("INVALID_CANCELLATION_OTP",
                    "Cancellation code is invalid or expired", HttpStatus.UNAUTHORIZED);
        }
        appointment.cancel();
        redis.delete(List.of(key, cancellationOtpAttemptsKey(appointment.getReferenceNumber()),
                cancellationOtpCooldownKey(appointment.getReferenceNumber())));
        audit.record("APPOINTMENT_CANCELLED", "APPOINTMENT", appointment.getId().toString(),
                "{\"reference\":\"" + appointment.getReferenceNumber() + "\"}");
        publishStatus(appointment);
        return appointment;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isSlotReserved(UUID hostEmployeeId, Instant slotStart, Instant slotEnd) {
        return appointments.existsByHostEmployeeIdAndSlotStartLessThanAndSlotEndGreaterThanAndStatusIn(
                hostEmployeeId, slotEnd, slotStart,
                java.util.EnumSet.of(AppointmentStatus.PENDING_VERIFICATION, AppointmentStatus.PENDING_SECURITY_INTAKE,
                        AppointmentStatus.PENDING_RECEPTION_VERIFICATION, AppointmentStatus.PENDING_APPROVAL,
                        AppointmentStatus.PENDING_HR_APPROVAL, AppointmentStatus.PENDING_TEAM_LEAD_APPROVAL,
                        AppointmentStatus.PENDING_MANAGER_APPROVAL,
                        AppointmentStatus.PENDING_CEO_APPROVAL,
                        AppointmentStatus.APPROVED, AppointmentStatus.RESCHEDULED, AppointmentStatus.CHECKED_IN,
                        AppointmentStatus.IN_MEETING));
    }

    @Override
    @Transactional(readOnly = true)
    public AccessAppointment requireForCheckIn(UUID appointmentId) {
        Appointment appointment = get(appointmentId);
        return new AccessAppointment(appointment.getId(), appointment.getVisitorName(), readyForCheckIn(appointment));
    }

    @Override
    @Transactional(readOnly = true)
    public AccessAppointment requireForCheckInByReference(String referenceNumber) {
        Appointment appointment = byReference(referenceNumber);
        return new AccessAppointment(appointment.getId(), appointment.getVisitorName(),
                readyForCheckIn(appointment));
    }

    @Override
    @Transactional
    public void markCheckedIn(UUID appointmentId) { get(appointmentId).checkIn(); }

    @Override
    @Transactional
    public void markCheckedOut(UUID appointmentId) {
        Appointment appointment = get(appointmentId);
        appointment.checkOut();
        appointment.complete();
    }

    @Override
    @Transactional(readOnly = true)
    public long awaitingApproval() { return appointments.countByStatusIn(java.util.List.of(
            AppointmentStatus.PENDING_SECURITY_INTAKE, AppointmentStatus.PENDING_RECEPTION_VERIFICATION,
            AppointmentStatus.PENDING_APPROVAL, AppointmentStatus.PENDING_HR_APPROVAL,
            AppointmentStatus.PENDING_TEAM_LEAD_APPROVAL,
            AppointmentStatus.PENDING_MANAGER_APPROVAL,
            AppointmentStatus.PENDING_CEO_APPROVAL)); }

    @Override
    @Transactional(readOnly = true)
    public long activeVisits() { return appointments.countByStatusIn(java.util.List.of(AppointmentStatus.APPROVED, AppointmentStatus.CHECKED_IN, AppointmentStatus.IN_MEETING)); }

    @Override
    @Transactional(readOnly = true)
    public java.util.List<VisitRecord> receptionVisitsBetween(Instant fromInclusive, Instant toExclusive) {
        return appointments.findAllByReceptionVerifiedAtGreaterThanEqualAndReceptionVerifiedAtLessThanOrderByReceptionVerifiedAtAsc(
                        fromInclusive, toExclusive)
                .stream().map(value -> new VisitRecord(value.getId(), value.getReferenceNumber(), value.getType().name(),
                        value.getStatus().name(), value.getVisitorName(), value.getVisitorEmail(), value.getVisitorPhone(),
                        value.getVisitorCompany(), value.getHostEmployeeId(), value.getRoutingDepartmentId(),
                        value.getRequestedEmployeeId(), value.getSlotStart(), value.getSlotEnd(),
                        value.getPurpose(), value.getSecurityIntakeActorId(), value.getSecurityIntakeAt(),
                        value.getArrivalVisitorName(), value.getArrivalPurpose(), value.getIdentityDocumentType(),
                        value.getIdentityDocumentLastFour(), value.getSecurityNotes(),
                        value.getReceptionVerificationActorId(), value.getReceptionVerifiedAt(),
                        value.getReceptionVerificationRemarks(), value.getHrApprovalActorId(), value.getHrDecisionAt(),
                        value.getTeamLeadApprovalActorId(), value.getTeamLeadDecisionAt(),
                        value.getManagerApprovalActorId(), value.getManagerDecisionAt(),
                        value.getCeoApprovalActorId(), value.getCeoDecisionAt(), value.getReceptionForwardActorId(),
                        value.getReceptionForwardedAt(), value.getReceptionForwardRemarks())).toList();
    }

    private boolean has(Authentication authentication, String authority) {
        return authentication.getAuthorities().stream().anyMatch(value -> value.getAuthority().equals(authority));
    }
    private boolean readyForCheckIn(Appointment appointment) {
        if (appointment.getStatus() != AppointmentStatus.APPROVED) return false;
        boolean requiresCabinForward = appointment.getType() == AppointmentType.HR_VISIT
                || appointment.getType() == AppointmentType.INTERVIEW
                || appointment.getType() == AppointmentType.CEO_VISIT
                || appointment.getType() == AppointmentType.EMERGENCY;
        return !requiresCabinForward || appointment.getReceptionForwardedAt() != null;
    }
    private void rejectSpecializedApprovalPath(Appointment appointment) {
        if (appointment.getStatus() == AppointmentStatus.PENDING_SECURITY_INTAKE ||
                appointment.getStatus() == AppointmentStatus.PENDING_RECEPTION_VERIFICATION ||
                appointment.getStatus() == AppointmentStatus.PENDING_HR_APPROVAL ||
                appointment.getStatus() == AppointmentStatus.PENDING_TEAM_LEAD_APPROVAL ||
                appointment.getStatus() == AppointmentStatus.PENDING_MANAGER_APPROVAL ||
                appointment.getStatus() == AppointmentStatus.PENDING_CEO_APPROVAL) {
            throw new BusinessException("STAGED_APPROVAL_REQUIRED",
                    "Use the Security, Reception, HR, Team Lead, Manager or CEO workflow action assigned to this appointment",
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }
    }
    private void publishStatus(Appointment appointment) {
        events.publishEvent(new AppointmentEvents.AppointmentStatusChanged(appointment.getId(),
                appointment.getReferenceNumber(), appointment.getStatus().name(), Instant.now()));
    }
    private void publishEmployeeVisitCard(Appointment appointment, UUID actorUserId) {
        if (appointment.getType() != AppointmentType.EMPLOYEE_VISIT) return;
        events.publishEvent(new AppointmentEvents.EmployeeVisitCardUpdated(appointment.getId(),
                appointment.getReferenceNumber(), actorUserId, appointment.getRequestedEmployeeId(),
                effectiveVisitorName(appointment), appointment.getVisitorEmail(), appointment.getVisitorPhone(),
                appointment.getVisitorCompany(), effectivePurpose(appointment), appointment.getSlotStart(),
                appointment.getStatus().name(), Instant.now()));
    }
    private String effectiveVisitorName(Appointment appointment) {
        return appointment.getArrivalVisitorName() == null ? appointment.getVisitorName()
                : appointment.getArrivalVisitorName();
    }
    private String effectivePurpose(Appointment appointment) {
        return appointment.getArrivalPurpose() == null ? appointment.getPurpose() : appointment.getArrivalPurpose();
    }
    private void requireAssignedHr(Appointment appointment, UUID actorUserId) {
        if (appointment.getRoutingDepartmentId() == null) throw new BusinessException("VISIT_DEPARTMENT_REQUIRED",
                "This visitor request has no routing department", HttpStatus.CONFLICT);
        departmentHrs.requireAssignedReviewer(appointment.getRoutingDepartmentId(), actorUserId);
    }
    private void requireAssignedManager(Appointment appointment, UUID actorUserId) {
        if (appointment.getRoutingDepartmentId() == null) throw new BusinessException(
                "VISIT_DEPARTMENT_REQUIRED", "This CEO visit has no routing department",
                HttpStatus.CONFLICT);
        managers.requireAssignedReviewer(appointment.getRoutingDepartmentId(), actorUserId);
    }
    private UUID requireHostDepartment(UUID hostEmployeeId, UUID requestedDepartmentId) {
        UUID hostDepartment = employees.departmentIdForEmployee(hostEmployeeId);
        if (requestedDepartmentId != null && !requestedDepartmentId.equals(hostDepartment)) {
            throw new BusinessException("HOST_DEPARTMENT_MISMATCH",
                    "The selected host does not belong to the selected department", HttpStatus.UNPROCESSABLE_ENTITY);
        }
        return hostDepartment;
    }
    private void requireDepartmentHrHost(UUID departmentId, UUID hostEmployeeId) {
        if (departmentId == null) throw new BusinessException("VISIT_DEPARTMENT_REQUIRED",
                "Select the department for this visit", HttpStatus.UNPROCESSABLE_ENTITY);
        var assignment = departmentHrs.requireForDepartment(departmentId);
        if (!assignment.hrEmployeeId().equals(hostEmployeeId)) throw new BusinessException("HOST_ROLE_MISMATCH",
                "Select the HR Admin assigned to the selected department", HttpStatus.UNPROCESSABLE_ENTITY);
    }
    private void requireCategory(EmployeeDirectory.HostCategory actual, EmployeeDirectory.HostCategory required,
                                 String message) {
        if (actual != required) throw new BusinessException("HOST_ROLE_MISMATCH", message,
                HttpStatus.UNPROCESSABLE_ENTITY);
    }
    private UUID activeTeamLeadUser(UUID hostEmployeeId) {
        return teamLeads.activeForHost(hostEmployeeId)
                .filter(value -> value.teamLeadEmployeeId().equals(hostEmployeeId))
                .map(TeamLeadDirectory.Assignment::teamLeadUserId)
                .orElseThrow(() -> new BusinessException("TEAM_LEAD_NOT_ASSIGNED",
                        "Select the active Team Lead assigned to a department", HttpStatus.CONFLICT));
    }
    private void validateBookableSlot(AppointmentType type, Instant slotStart, Instant slotEnd) {
        int effectiveSlotMinutes = workspacePolicy.integerValue("APPOINTMENT.SLOT_MINUTES", slotMinutes);
        int effectiveMaxAdvanceDays = workspacePolicy.integerValue("APPOINTMENT.MAX_ADVANCE_DAYS", maxAdvanceDays);
        int minimumLeadMinutes = Math.max(0, workspacePolicy.integerValue("APPOINTMENT.MIN_LEAD_MINUTES", 10));
        Instant now = Instant.now();
        if (!slotStart.isAfter(now.plus(Duration.ofMinutes(minimumLeadMinutes)))) {
            throw new BusinessException("PAST_APPOINTMENT_SLOT",
                    "Appointment slot must be at least " + minimumLeadMinutes + " minutes in the future",
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }
        if (!slotEnd.equals(slotStart.plus(Duration.ofMinutes(effectiveSlotMinutes)))) {
            throw new BusinessException("INVALID_APPOINTMENT_DURATION",
                    "Appointment duration must be exactly " + effectiveSlotMinutes + " minutes",
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }
        ZonedDateTime localStart = slotStart.atZone(officeZone);
        ZonedDateTime localEnd = slotEnd.atZone(officeZone);
        DayOfWeek day = localStart.getDayOfWeek();
        boolean emergencyToday = type == AppointmentType.EMERGENCY
                && localStart.toLocalDate().equals(now.atZone(officeZone).toLocalDate());
        if ((!emergencyToday && (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY))
                || localStart.toLocalTime().isBefore(LocalTime.of(9, 30))
                || localEnd.toLocalTime().isAfter(LocalTime.of(17, 30))) {
            throw new BusinessException("OUTSIDE_BOOKING_HOURS",
                    "Appointments must use office hours between 09:30 and 17:30 " + officeZone
                            + "; weekend same-day booking is reserved for emergencies",
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }
        long minutesFromOpening = Duration.between(LocalTime.of(9, 30), localStart.toLocalTime()).toMinutes();
        if (minutesFromOpening % (effectiveSlotMinutes + 10L) != 0) {
            throw new BusinessException("INVALID_APPOINTMENT_START",
                    "Select one of the published available slots", HttpStatus.UNPROCESSABLE_ENTITY);
        }
        if (localStart.toLocalDate().isAfter(now.atZone(officeZone).toLocalDate().plusDays(effectiveMaxAdvanceDays))) {
            throw new BusinessException("APPOINTMENT_TOO_FAR_AHEAD",
                    "Appointments can be booked up to " + effectiveMaxAdvanceDays + " days ahead",
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }
    }
    private String otpKey(String reference) { return "appointment:otp:" + reference; }
    private String cancellationOtpKey(String reference) {
        return "appointment:cancellation-otp:" + reference.toUpperCase(Locale.ROOT);
    }
    private String cancellationOtpAttemptsKey(String reference) {
        return "appointment:cancellation-otp-attempts:" + reference.toUpperCase(Locale.ROOT);
    }
    private String cancellationOtpCooldownKey(String reference) {
        return "appointment:cancellation-otp-cooldown:" + reference.toUpperCase(Locale.ROOT);
    }
    private String randomCharacters(int length) {
        String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        StringBuilder value = new StringBuilder(length);
        for (int i = 0; i < length; i++) value.append(alphabet.charAt(random.nextInt(alphabet.length())));
        return value.toString();
    }
    private String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }

    public record CreateAppointment(AppointmentType type, String visitorName, String visitorEmail, String visitorPhone,
                                    String visitorCompany, UUID hostEmployeeId, UUID routingDepartmentId,
                                    UUID requestedEmployeeId, Instant slotStart, Instant slotEnd, String purpose) {
        public CreateAppointment(AppointmentType type, String visitorName, String visitorEmail, String visitorPhone,
                                 String visitorCompany, UUID hostEmployeeId, Instant slotStart, Instant slotEnd,
                                 String purpose) {
            this(type, visitorName, visitorEmail, visitorPhone, visitorCompany, hostEmployeeId, null, null,
                    slotStart, slotEnd, purpose);
        }
    }
    public record SecurityIntake(String visitorName, String purpose, String identityDocumentType,
                                 String identityDocumentLastFour, String notes) {}
}
