package com.brainserve.appointment.notification.application;

import com.brainserve.appointment.iam.api.StaffCommunicationDirectory;
import com.brainserve.appointment.notification.api.InternalCallEvent;
import com.brainserve.appointment.notification.api.InternalNotificationGateway;
import com.brainserve.appointment.notification.domain.InternalCallNotification;
import com.brainserve.appointment.notification.infrastructure.InternalCallNotificationRepository;
import com.brainserve.appointment.shared.application.BusinessException;
import com.brainserve.appointment.departmenthr.api.DepartmentHrDirectory;
import com.brainserve.appointment.employee.api.EmployeeDirectory;
import com.brainserve.appointment.manager.api.ManagerDirectory;
import com.brainserve.appointment.essentiallog.api.EssentialLogService;
import org.springframework.data.domain.PageRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;

import java.util.LinkedHashSet;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

@Service
public class InternalCallNotificationService implements InternalNotificationGateway {
    private static final String CEO = "ROLE_CEO";
    private static final String HR = "ROLE_HR_ADMIN";
    private static final String MANAGER = "ROLE_MANAGER";
    private static final String TEAM_LEAD = "ROLE_TEAM_LEAD";
    private static final String EMPLOYEE = "ROLE_EMPLOYEE";
    private static final String RECEPTIONIST = "ROLE_RECEPTIONIST";
    private static final String SECURITY = "ROLE_SECURITY";
    private static final String SYSTEM_ADMIN = "ROLE_SYSTEM_ADMIN";
    private final ZoneId officeZone;

    private final InternalCallNotificationRepository notifications;
    private final StaffCommunicationDirectory staff;
    private final KafkaTemplate<String, InternalCallEvent> kafka;
    private final String topic;
    private final DepartmentHrDirectory departmentHrs;
    private final ManagerDirectory managers;
    private final EmployeeDirectory employees;
    private final EssentialLogService essentialLogs;
    private final long deliveryRetrySeconds;
    private final TransactionOperations transactions;

    public InternalCallNotificationService(InternalCallNotificationRepository notifications,
                                           StaffCommunicationDirectory staff,
                                           KafkaTemplate<String, InternalCallEvent> kafka,
                                           @Value("${brainserve.notification.internal-call-topic}") String topic,
                                           DepartmentHrDirectory departmentHrs,
                                           ManagerDirectory managers,
                                           EmployeeDirectory employees,
                                           EssentialLogService essentialLogs,
                                           @Value("${brainserve.appointment.office-zone:Asia/Kolkata}") String officeZone,
                                           @Value("${brainserve.notification.internal-call-retry-seconds:30}")
                                           long deliveryRetrySeconds,
                                           TransactionOperations transactions) {
        this.notifications = notifications; this.staff = staff; this.kafka = kafka; this.topic = topic;
        this.departmentHrs = departmentHrs;
        this.managers = managers;
        this.employees = employees;
        this.essentialLogs = essentialLogs;
        this.officeZone = ZoneId.of(officeZone);
        this.deliveryRetrySeconds = Math.max(5, deliveryRetrySeconds);
        this.transactions = transactions;
    }

    @Transactional(readOnly = true)
    public List<StaffCommunicationDirectory.StaffMember> eligibleRecipients(UUID senderUserId) {
        var sender = staff.requireActive(senderUserId);
        Set<String> targetRoles = allowedRecipientRoles(sender.roles());
        if (targetRoles.isEmpty()) return List.of();
        List<StaffCommunicationDirectory.StaffMember> candidates = staff.activeWithAnyRole(targetRoles).stream()
                .filter(recipient -> !recipient.userId().equals(senderUserId))
                .toList();
        Map<UUID, UUID> departmentsByEmployee = recipientDepartmentScope(sender, candidates);
        return candidates.stream()
                .filter(recipient -> isWithinManualMessageScope(sender, recipient, departmentsByEmployee))
                .toList();
    }

    public InternalCallNotification send(UUID senderUserId, UUID recipientUserId, String message) {
        return send(senderUserId, recipientUserId, message,
                InternalCallNotification.MessagePriority.NORMAL, InternalCallNotification.MessageCategory.GENERAL);
    }

    public InternalCallNotification send(UUID senderUserId, UUID recipientUserId, String message,
                                         InternalCallNotification.MessagePriority priority,
                                         InternalCallNotification.MessageCategory category) {
        if (senderUserId.equals(recipientUserId)) {
            throw new BusinessException("INTERNAL_CALL_SELF_NOT_ALLOWED",
                    "You cannot send an internal call to yourself", HttpStatus.UNPROCESSABLE_ENTITY);
        }
        String normalizedMessage = message == null ? "" : message.trim().replaceAll("\\s+", " ");
        if (normalizedMessage.length() < 2 || normalizedMessage.length() > 500) {
            throw new BusinessException("INVALID_INTERNAL_CALL_MESSAGE",
                    "Internal call messages must contain 2 to 500 characters", HttpStatus.UNPROCESSABLE_ENTITY);
        }
        var sender = staff.requireActive(senderUserId);
        var recipient = staff.requireActive(recipientUserId);
        if (!isAllowed(sender.roles(), recipient.roles())) {
            throw new BusinessException("INTERNAL_CALL_ROLE_NOT_ALLOWED",
                    "Your role cannot send an internal call to this recipient", HttpStatus.FORBIDDEN);
        }
        if (!isWithinManualMessageScope(sender, recipient)) {
            throw new BusinessException("INTERNAL_CALL_DEPARTMENT_NOT_ALLOWED",
                    "Internal calls between these roles are limited to the same department",
                    HttpStatus.FORBIDDEN);
        }
        InternalCallNotification notification = notifications.saveAndFlush(new InternalCallNotification(
                sender.userId(), recipient.userId(), sender.fullName(), recipient.fullName(), normalizedMessage,
                priority, category));
        return notification;
    }

    @Override
    public void sendResourceDiscussionUpdate(UUID senderUserId, UUID recipientUserId, String message) {
        send(senderUserId, recipientUserId, systemMessage(message),
                InternalCallNotification.MessagePriority.HIGH,
                InternalCallNotification.MessageCategory.ACTION_REQUIRED);
    }

    @Override
    public void sendWorkTaskUpdate(UUID senderUserId, UUID recipientUserId, String message) {
        var sender = staff.requireActive(senderUserId);
        var recipient = staff.requireActive(recipientUserId);
        boolean teamLeadToEmployee = sender.roles().contains(TEAM_LEAD) && recipient.roles().contains(EMPLOYEE);
        boolean employeeToTeamLead = sender.roles().contains(EMPLOYEE) && recipient.roles().contains(TEAM_LEAD);
        boolean hrToWorker = sender.roles().contains(HR)
                && (recipient.roles().contains(EMPLOYEE) || recipient.roles().contains(TEAM_LEAD));
        if (!teamLeadToEmployee && !employeeToTeamLead && !hrToWorker) throw new BusinessException(
                "WORK_TASK_NOTIFICATION_ROUTE_DENIED",
                "Work task updates are limited to HR, the assigned Employee and Team Lead", HttpStatus.FORBIDDEN);
        persistAndPublish(sender, recipient, message, InternalCallNotification.MessagePriority.NORMAL,
                InternalCallNotification.MessageCategory.WORK);
    }

    @Override
    public void notifyHrOfWorkTaskUpdate(UUID actorUserId, UUID departmentId, String message) {
        var sender = staff.requireActive(actorUserId);
        if (!sender.roles().contains(TEAM_LEAD) && !sender.roles().contains(EMPLOYEE)) {
            throw new BusinessException("WORK_TASK_UPDATE_ROLE_REQUIRED",
                    "Only an Employee or Team Lead can publish a work update to HR", HttpStatus.FORBIDDEN);
        }
        var assigned = departmentHrs.requireForDepartment(departmentId);
        if (assigned.hrUserId().equals(sender.userId())) return;
        persistAndPublish(sender, staff.requireActive(assigned.hrUserId()), message,
                InternalCallNotification.MessagePriority.HIGH, InternalCallNotification.MessageCategory.WORK);
    }

    @Override
    public void notifyWorkerOfCeoWorkInsightApproval(
            UUID ceoUserId,
            UUID recipientUserId,
            String message
    ) {
        var sender = staff.requireActive(ceoUserId);

        if (!sender.roles().contains(CEO)) {
            throw new BusinessException(
                    "CEO_ROLE_REQUIRED",
                    "Only CEO can publish final work-insight approval",
                    HttpStatus.FORBIDDEN
            );
        }

        var recipient = staff.requireActive(recipientUserId);

        if (!recipient.roles().contains(EMPLOYEE)
                && !recipient.roles().contains(TEAM_LEAD)) {
            throw new BusinessException(
                    "WORK_INSIGHT_FINAL_APPROVAL_ROUTE_DENIED",
                    "Final work-insight approval can only notify the assigned Employee or Team Lead",
                    HttpStatus.FORBIDDEN
            );
        }

        persistAndPublish(
                sender,
                recipient,
                message,
                InternalCallNotification.MessagePriority.HIGH,
                InternalCallNotification.MessageCategory.INSIGHT
        );
    }

    @Override
    public void notifyManagerOfWorkInsightAudit(UUID hrUserId, UUID managerUserId, String message) {
        var sender = staff.requireActive(hrUserId);
        if (!sender.roles().contains(HR)) throw new BusinessException("HR_ROLE_REQUIRED",
                "Only HR can submit a work audit to a Manager", HttpStatus.FORBIDDEN);
        var recipient = staff.requireActive(managerUserId);
        if (!recipient.roles().contains(MANAGER)) throw new BusinessException("MANAGER_ROLE_REQUIRED",
                "The work audit recipient is not an active Manager", HttpStatus.CONFLICT);
        persistAndPublish(sender, recipient, message, InternalCallNotification.MessagePriority.HIGH,
                InternalCallNotification.MessageCategory.INSIGHT);
    }

    @Override
    public void notifyCeoOfManagerWorkInsightApproval(UUID managerUserId, String message) {
        var sender = staff.requireActive(managerUserId);
        if (!sender.roles().contains(MANAGER)) throw new BusinessException("MANAGER_ROLE_REQUIRED",
                "Only a Manager can route a work audit to the CEO", HttpStatus.FORBIDDEN);
        persistAndPublish(sender, staff.requireChiefExecutive(), message,
                InternalCallNotification.MessagePriority.HIGH,
                InternalCallNotification.MessageCategory.INSIGHT);
    }

    @Override
    public void notifyHrOfManagerWorkInsightDecision(UUID managerUserId, UUID hrUserId, String message) {
        var sender = staff.requireActive(managerUserId);
        if (!sender.roles().contains(MANAGER)) throw new BusinessException("MANAGER_ROLE_REQUIRED",
                "Only a Manager can publish a Manager work-audit decision", HttpStatus.FORBIDDEN);
        persistAndPublish(sender, staff.requireActive(hrUserId), message,
                InternalCallNotification.MessagePriority.HIGH,
                InternalCallNotification.MessageCategory.INSIGHT);
    }

    @Override
    public void notifyHrOfWorkInsightDecision(UUID ceoUserId, UUID hrUserId, String message) {
        var sender = staff.requireActive(ceoUserId);
        if (!sender.roles().contains(CEO)) throw new BusinessException("CEO_ROLE_REQUIRED",
                "Only CEO can publish a work audit decision", HttpStatus.FORBIDDEN);
        persistAndPublish(sender, staff.requireActive(hrUserId), message,
                InternalCallNotification.MessagePriority.HIGH, InternalCallNotification.MessageCategory.INSIGHT);
    }

    @Override
    public void notifyCeoOfManagerVisitApproval(UUID managerUserId, String message) {
        var sender = staff.requireActive(managerUserId);
        if (!sender.roles().contains(MANAGER)) throw new BusinessException("MANAGER_ROLE_REQUIRED",
                "Only a Manager can route a CEO visit for final approval", HttpStatus.FORBIDDEN);
        persistAndPublish(sender, staff.requireChiefExecutive(), systemMessage(message),
                InternalCallNotification.MessagePriority.URGENT,
                InternalCallNotification.MessageCategory.VISITOR);
    }

    @Override
    public void notifyManagerOfCeoVisitDecision(UUID ceoUserId, UUID departmentId, String message) {
        var sender = staff.requireActive(ceoUserId);
        if (!sender.roles().contains(CEO)) throw new BusinessException("CEO_ROLE_REQUIRED",
                "Only the CEO can publish the final CEO-visit decision", HttpStatus.FORBIDDEN);
        var assignedManager = managers.requireForDepartment(departmentId);
        persistAndPublish(sender, staff.requireActive(assignedManager.managerUserId()), systemMessage(message),
                InternalCallNotification.MessagePriority.HIGH,
                InternalCallNotification.MessageCategory.VISITOR);
    }

    @Override
    public void notifyTeamLeadOfWorkInsightRework(UUID reviewerUserId, UUID teamLeadUserId, String message) {
        var sender = staff.requireActive(reviewerUserId);
        var recipient = staff.requireActive(teamLeadUserId);
        if ((!sender.roles().contains(HR) && !sender.roles().contains(MANAGER)
                && !sender.roles().contains(CEO))
                || !recipient.roles().contains(TEAM_LEAD)) {
            throw new BusinessException("WORK_INSIGHT_REWORK_ROUTE_DENIED",
                    "Insights rework can only be routed by HR, Manager or CEO to the assigned Team Lead",
                    HttpStatus.FORBIDDEN);
        }
        persistAndPublish(sender, recipient, systemMessage(message),
                InternalCallNotification.MessagePriority.URGENT, InternalCallNotification.MessageCategory.INSIGHT);
    }

    @Override
    public void notifyRoleDepartmentChangeApprover(UUID requesterUserId, String requesterRole,
                                                   UUID targetDepartmentId, String message) {
        var sender = staff.requireActive(requesterUserId);
        if ("HR_ADMIN".equals(requesterRole)) {
            broadcast(sender, Set.of(CEO), message, InternalCallNotification.MessagePriority.HIGH,
                    InternalCallNotification.MessageCategory.ACTION_REQUIRED);
            return;
        }
        if ("TEAM_LEAD".equals(requesterRole)) {
            var assignedHr = departmentHrs.requireForDepartment(targetDepartmentId);
            persistAndPublish(sender, staff.requireActive(assignedHr.hrUserId()), message,
                    InternalCallNotification.MessagePriority.HIGH,
                    InternalCallNotification.MessageCategory.ACTION_REQUIRED);
            return;
        }
        throw new BusinessException("ROLE_DEPARTMENT_CHANGE_ROUTE_DENIED",
                "Only HR Admin and Team Lead department changes are supported", HttpStatus.FORBIDDEN);
    }

    @Override
    public void notifyRoleDepartmentChangeDecision(UUID approverUserId, UUID requesterUserId, String message) {
        persistAndPublish(staff.requireActive(approverUserId), staff.requireActive(requesterUserId), message,
                InternalCallNotification.MessagePriority.HIGH,
                InternalCallNotification.MessageCategory.ACTION_REQUIRED);
    }

    @Override
    public void notifyCeoOfTerminationRequest(UUID hrUserId, String message) {
        var sender = staff.requireActive(hrUserId);
        if (!sender.roles().contains(HR)) throw new BusinessException("HR_ROLE_REQUIRED",
                "Only HR can submit an employee termination request", HttpStatus.FORBIDDEN);
        broadcast(sender, Set.of(CEO), systemMessage(message),
                InternalCallNotification.MessagePriority.URGENT,
                InternalCallNotification.MessageCategory.ACTION_REQUIRED);
    }

    @Override
    public void notifyHrOfTerminationDecision(UUID ceoUserId, UUID hrUserId, String message) {
        var sender = staff.requireActive(ceoUserId);
        if (!sender.roles().contains(CEO)) throw new BusinessException("CEO_ROLE_REQUIRED",
                "Only CEO can publish an employee termination decision", HttpStatus.FORBIDDEN);
        persistAndPublish(sender, staff.requireActive(hrUserId), systemMessage(message),
                InternalCallNotification.MessagePriority.URGENT,
                InternalCallNotification.MessageCategory.ACTION_REQUIRED);
    }

    @Override
    public void notifyAccountClosureReview(UUID requesterUserId, String targetRole, UUID departmentId,
                                           String message) {
        var sender = staff.requireActive(requesterUserId);
        String content = systemMessage(message);
        if (CEO.equals(targetRole)) {
            broadcast(sender, Set.of(SYSTEM_ADMIN), content, InternalCallNotification.MessagePriority.URGENT,
                    InternalCallNotification.MessageCategory.ACTION_REQUIRED);
            return;
        }
        if (HR.equals(targetRole) || MANAGER.equals(targetRole)) {
            broadcast(sender, Set.of(CEO), content, InternalCallNotification.MessagePriority.URGENT,
                    InternalCallNotification.MessageCategory.ACTION_REQUIRED);
            return;
        }
        if (TEAM_LEAD.equals(targetRole)) {
            if (departmentId == null) throw new BusinessException("ACCOUNT_CLOSURE_DEPARTMENT_REQUIRED",
                    "Team Lead closure requires a department assignment", HttpStatus.CONFLICT);
            var assignment = departmentHrs.requireForDepartment(departmentId);
            persistAndPublish(sender, staff.requireActive(assignment.hrUserId()), content,
                    InternalCallNotification.MessagePriority.URGENT,
                    InternalCallNotification.MessageCategory.ACTION_REQUIRED);
            return;
        }
        if (RECEPTIONIST.equals(targetRole) || SECURITY.equals(targetRole)) {
            broadcast(sender, Set.of(HR), content, InternalCallNotification.MessagePriority.HIGH,
                    InternalCallNotification.MessageCategory.ACTION_REQUIRED);
            return;
        }
        throw new BusinessException("ACCOUNT_CLOSURE_ROUTE_DENIED",
                "This role does not use the account closure notification route", HttpStatus.FORBIDDEN);
    }

    @Override
    public void notifySystemAdminOfAccountClosure(UUID actorUserId, String message) {
        broadcast(staff.requireActive(actorUserId), Set.of(SYSTEM_ADMIN), systemMessage(message),
                InternalCallNotification.MessagePriority.URGENT,
                InternalCallNotification.MessageCategory.ACTION_REQUIRED);
    }

    @Override
    public void notifyAccountClosureDecision(UUID actorUserId, UUID targetUserId, String message) {
        persistAndPublish(staff.requireActive(actorUserId), staff.requireActive(targetUserId),
                systemMessage(message), InternalCallNotification.MessagePriority.URGENT,
                InternalCallNotification.MessageCategory.ACTION_REQUIRED);
    }

    @Override
    public void notifyReportExportReady(UUID userId, String message, boolean failed) {
        var recipient = staff.requireActive(userId);
        staff.activeWithAnyRole(Set.of(SYSTEM_ADMIN)).stream()
                .filter(sender -> !sender.userId().equals(recipient.userId()))
                .findFirst()
                .ifPresent(sender -> persistAndPublish(sender, recipient, systemMessage(message),
                        failed ? InternalCallNotification.MessagePriority.HIGH : InternalCallNotification.MessagePriority.NORMAL,
                        failed ? InternalCallNotification.MessageCategory.ACTION_REQUIRED : InternalCallNotification.MessageCategory.INSIGHT));
    }

    public void sendSecurityArrival(UUID securityUserId, String reference, String visitorName, String purpose) {
        var sender = staff.requireActive(securityUserId);
        if (!sender.roles().contains(SECURITY)) {
            throw new BusinessException("SECURITY_ROLE_REQUIRED",
                    "Only Security can submit a visitor arrival notice", HttpStatus.FORBIDDEN);
        }
        String message = systemMessage("Security intake " + reference + ": " + visitorName + " arrived for " + purpose
                + ". Verify the visit and route it for approval.");
        staff.activeWithAnyRole(Set.of(RECEPTIONIST)).forEach(recipient -> {
            InternalCallNotification notification = notifications.saveAndFlush(new InternalCallNotification(
                    sender.userId(), recipient.userId(), sender.fullName(), recipient.fullName(), message,
                    InternalCallNotification.MessagePriority.URGENT,
                    InternalCallNotification.MessageCategory.VISITOR));
        });
    }

    public void sendReceptionForward(UUID receptionistUserId, String reference, UUID hostEmployeeId,
                                     String appointmentType, String visitorName, String remarks) {
        var sender = staff.requireActive(receptionistUserId);
        if (!sender.roles().contains(RECEPTIONIST)) {
            throw new BusinessException("RECEPTION_ROLE_REQUIRED",
                    "Only Reception can forward an approved visitor", HttpStatus.FORBIDDEN);
        }
        var directHost = staff.activeByEmployeeId(hostEmployeeId);
        boolean ceoVisit = directHost.map(member -> member.roles().contains(CEO))
                .orElse(appointmentType.equals("CEO_VISIT"));
        String destination = ceoVisit ? "CEO cabin" : "HR cabin";
        String message = "Reception forwarding " + visitorName + " (" + reference + ") to the " + destination
                + (remarks == null || remarks.isBlank() ? "." : ": " + remarks.trim());
        String targetRole = ceoVisit ? CEO : HR;
        var targeted = directHost
                .filter(member -> member.roles().contains(targetRole));
        if (targeted.isPresent()) {
            persistAndPublish(sender, targeted.get(), message, InternalCallNotification.MessagePriority.HIGH,
                    InternalCallNotification.MessageCategory.VISITOR);
            return;
        }
        broadcast(sender, Set.of(targetRole), message, InternalCallNotification.MessagePriority.HIGH,
                InternalCallNotification.MessageCategory.VISITOR);
    }

    public void sendReceptionVerification(UUID receptionistUserId, UUID hostEmployeeId, UUID routingDepartmentId, String appointmentType,
                                          String reference, String visitorName, String purpose) {
        var sender = staff.requireActive(receptionistUserId);
        if (!sender.roles().contains(RECEPTIONIST)) {
            throw new BusinessException("RECEPTION_ROLE_REQUIRED",
                    "Only Reception can route a verified visitor", HttpStatus.FORBIDDEN);
        }
        String message = systemMessage("Reception verified " + visitorName + " (" + reference + ") for " + purpose
                + ". Review the visitor request.");
        if (routingDepartmentId == null) throw new BusinessException("VISIT_DEPARTMENT_REQUIRED",
                "The verified visit has no routing department", HttpStatus.CONFLICT);
        if ("CEO_VISIT".equals(appointmentType)) {
            var assignment = managers.requireForDepartment(routingDepartmentId);
            persistAndPublish(sender, staff.requireActive(assignment.managerUserId()), message,
                    InternalCallNotification.MessagePriority.HIGH, InternalCallNotification.MessageCategory.VISITOR);
        } else {
            var assignment = departmentHrs.requireForDepartment(routingDepartmentId);
            persistAndPublish(sender, staff.requireActive(assignment.hrUserId()), message,
                    InternalCallNotification.MessagePriority.HIGH, InternalCallNotification.MessageCategory.VISITOR);
        }
    }

    public void notifyHrOfLeaveRequest(UUID employeeUserId, UUID departmentId, String employeeName,
                                       java.time.LocalDate from, java.time.LocalDate to, String reason) {
        var sender = staff.requireActive(employeeUserId);
        String message = systemMessage("Leave request from " + employeeName + " for " + from + " to " + to + ": " + reason);
        var assignment = departmentHrs.requireForDepartment(departmentId);
        persistAndPublish(sender, staff.requireActive(assignment.hrUserId()), message,
                InternalCallNotification.MessagePriority.NORMAL, InternalCallNotification.MessageCategory.LEAVE);
    }

    public void notifyEmployeeOfLeaveDecision(UUID decidedByUserId, UUID employeeUserId, String decision,
                                              java.time.LocalDate from, java.time.LocalDate to) {
        var decidingHr = staff.requireActive(decidedByUserId);
        if (!decidingHr.roles().contains(HR)) {
            throw new BusinessException("HR_ROLE_REQUIRED", "Only HR can send a leave decision",
                    HttpStatus.FORBIDDEN);
        }
        var recipient = staff.requireActive(employeeUserId);
        persistAndPublish(decidingHr, recipient,
                "Your leave request for " + from + " to " + to + " was " + decision.toLowerCase() + ".",
                InternalCallNotification.MessagePriority.NORMAL, InternalCallNotification.MessageCategory.LEAVE);
    }

    public void notifyTeamLeadOfAppointment(UUID hrUserId, UUID teamLeadUserId, String reference,
                                            String visitorName, String purpose) {
        var sender = staff.requireActive(hrUserId);
        if (!sender.roles().contains(HR)) throw new BusinessException("HR_ROLE_REQUIRED",
                "Only HR can route an appointment to a Team Lead", HttpStatus.FORBIDDEN);
        var recipient = staff.requireActive(teamLeadUserId);
        if (!recipient.roles().contains(TEAM_LEAD)) throw new BusinessException("TEAM_LEAD_ROLE_REQUIRED",
                "The appointment recipient is not an active Team Lead", HttpStatus.UNPROCESSABLE_ENTITY);
        persistAndPublish(sender, recipient, "HR routed visitor " + visitorName + " (" + reference
                        + ") for Team Lead approval: " + purpose, InternalCallNotification.MessagePriority.HIGH,
                InternalCallNotification.MessageCategory.VISITOR);
    }

    public void notifyEmployeeOfVisitorCard(UUID actorUserId, UUID hostEmployeeId, String reference,
                                            String visitorName, String visitorEmail, String visitorPhone,
                                            String visitorCompany, String purpose, Instant slotStart,
                                            String status) {
        var sender = staff.requireActive(actorUserId);
        if (!sender.roles().contains(HR) && !sender.roles().contains(TEAM_LEAD)) {
            throw new BusinessException("VISITOR_CARD_SENDER_ROLE_REQUIRED",
                    "Only HR or the assigned Team Lead can update an employee visitor card", HttpStatus.FORBIDDEN);
        }
        staff.activeByEmployeeId(hostEmployeeId).ifPresent(recipient -> {
            if (!recipient.roles().contains(EMPLOYEE) && !recipient.roles().contains(TEAM_LEAD)) return;
            String company = visitorCompany == null || visitorCompany.isBlank() ? "Independent" : visitorCompany;
            String contact = (visitorEmail == null || visitorEmail.isBlank() ? "" : visitorEmail)
                    + (visitorPhone == null || visitorPhone.isBlank() ? "" : " / " + visitorPhone);
            String stage = status.equals("PENDING_TEAM_LEAD_APPROVAL")
                    ? "HR verified this visit; Team Lead approval is pending"
                    : status.equals("APPROVED") ? "Team Lead approved this visit"
                      : status.equals("REJECTED") ? "Team Lead rejected this visit" : status.replace('_', ' ');
            persistAndPublish(sender, recipient, "Visitor card " + reference + ": " + visitorName + " from "
                            + company + " is coming to meet you for " + purpose + ". Scheduled " + slotStart
                            + (contact.isBlank() ? ". " : ". Contact " + contact + ". ") + stage + ".",
                    InternalCallNotification.MessagePriority.HIGH,
                    InternalCallNotification.MessageCategory.VISITOR);
        });
    }

    @Transactional(readOnly = true)
    public List<InternalCallNotification> inbox(UUID recipientUserId) {
        staff.requireActive(recipientUserId);
        Instant[] range = todayRange();
        return notifications.findInboxForDay(recipientUserId, InternalCallNotification.DeliveryStatus.DELIVERED,
                        range[0], range[1]).stream()
                .sorted(Comparator.comparing(InternalCallNotification::isRead)
                        .thenComparing((InternalCallNotification value) -> priorityRank(value.getPriority()), Comparator.reverseOrder())
                        .thenComparing(InternalCallNotification::getSentAt, Comparator.reverseOrder()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<InternalCallNotification> sent(UUID senderUserId) {
        staff.requireActive(senderUserId);
        Instant[] range = todayRange();
        return notifications.findSentForDay(senderUserId, range[0], range[1]);
    }

    @Transactional(readOnly = true)
    public List<InternalCallNotification> archive(UUID userId, int page, int size) {
        staff.requireActive(userId);
        int safePage = Math.max(0, page); int safeSize = Math.min(Math.max(size, 1), 100);
        return notifications.findArchive(userId, todayRange()[0], PageRequest.of(safePage, safeSize));
    }

    @Transactional(readOnly = true)
    public long unreadCount(UUID recipientUserId) {
        staff.requireActive(recipientUserId);
        Instant[] range = todayRange();
        return notifications.countTodayUnread(recipientUserId, InternalCallNotification.DeliveryStatus.DELIVERED,
                range[0], range[1]);
    }

    @Transactional
    public InternalCallNotification markRead(UUID recipientUserId, UUID notificationId) {
        InternalCallNotification notification = notifications.findByIdAndRecipientUserIdAndDeliveryStatus(
                        notificationId, recipientUserId, InternalCallNotification.DeliveryStatus.DELIVERED)
                .orElseThrow(() -> new BusinessException("INTERNAL_CALL_NOT_FOUND",
                        "The internal notification was not found", HttpStatus.NOT_FOUND));
        notification.markRead();
        return notification;
    }

    @Transactional
    public void deleteArchived(UUID actorUserId, UUID notificationId) {
        staff.requireActive(actorUserId);
        InternalCallNotification notification = notifications.findByIdAndDeletedAtIsNull(notificationId)
                .orElseThrow(() -> new BusinessException("INTERNAL_CALL_NOT_FOUND",
                        "The archived notification was not found", HttpStatus.NOT_FOUND));
        if (!actorUserId.equals(notification.getSenderUserId()) && !actorUserId.equals(notification.getRecipientUserId())) {
            throw new BusinessException("INTERNAL_CALL_DELETE_FORBIDDEN",
                    "You can delete only your own archived messages", HttpStatus.FORBIDDEN);
        }
        if (!notification.getSentAt().isBefore(todayRange()[0])) {
            throw new BusinessException("TODAY_MESSAGE_DELETE_FORBIDDEN",
                    "Today's messages cannot be deleted", HttpStatus.CONFLICT);
        }
        notification.archive(); notification.softDelete(actorUserId);
        essentialLogs.record("INTERNAL_COMMUNICATION", "ARCHIVED_MESSAGE_DELETED", "INTERNAL_NOTIFICATION",
                notification.getId().toString(), notification.getConversationKey(), actorUserId, null, "DELETED",
                "Archived internal message deleted",
                "Sender: " + notification.getSenderName() + " · Recipient: " + notification.getRecipientName()
                        + " · Sent: " + notification.getSentAt() + " · Priority: " + notification.getPriority()
                        + " · Category: " + notification.getCategory() + " · Message snapshot: " + notification.getMessage());
    }

    private Instant[] todayRange() {
        ZonedDateTime start = ZonedDateTime.now(officeZone).toLocalDate().atStartOfDay(officeZone);
        return new Instant[]{start.toInstant(), start.plusDays(1).toInstant()};
    }

    @Scheduled(fixedDelayString = "${brainserve.notification.internal-call-dispatch-ms:1000}")
    public void dispatchPending() {
        claimReadyForDelivery().forEach(this::publishClaimed);
    }

    static Set<String> allowedRecipientRoles(Set<String> senderRoles) {
        Set<String> targets = new LinkedHashSet<>();
        if (senderRoles.contains(CEO)) { targets.add(MANAGER); targets.add(HR); targets.add(TEAM_LEAD); targets.add(RECEPTIONIST); }
        if (senderRoles.contains(MANAGER)) { targets.add(CEO); targets.add(HR); targets.add(RECEPTIONIST); }
        if (senderRoles.contains(HR)) { targets.add(CEO); targets.add(TEAM_LEAD); targets.add(EMPLOYEE); targets.add(RECEPTIONIST); }
        if (senderRoles.contains(TEAM_LEAD)) { targets.add(HR); targets.add(RECEPTIONIST); }
        if (senderRoles.contains(EMPLOYEE)) targets.add(HR);
        if (senderRoles.contains(RECEPTIONIST)) { targets.add(CEO); targets.add(MANAGER); targets.add(HR); targets.add(TEAM_LEAD); }
        return Set.copyOf(targets);
    }

    private boolean isAllowed(Set<String> senderRoles, Set<String> recipientRoles) {
        return recipientRoles.stream().anyMatch(allowedRecipientRoles(senderRoles)::contains);
    }

    private boolean isWithinManualMessageScope(StaffCommunicationDirectory.StaffMember sender,
                                               StaffCommunicationDirectory.StaffMember recipient) {
        if (!requiresDepartmentMatch(sender, recipient)) return true;
        if (sender.employeeId() == null || recipient.employeeId() == null) return false;
        return employees.departmentIdForEmployee(sender.employeeId())
                .equals(employees.departmentIdForEmployee(recipient.employeeId()));
    }

    private boolean isWithinManualMessageScope(StaffCommunicationDirectory.StaffMember sender,
                                               StaffCommunicationDirectory.StaffMember recipient,
                                               Map<UUID, UUID> departmentsByEmployee) {
        if (!requiresDepartmentMatch(sender, recipient)) return true;
        if (sender.employeeId() == null || recipient.employeeId() == null) return false;
        UUID senderDepartment = departmentsByEmployee.get(sender.employeeId());
        UUID recipientDepartment = departmentsByEmployee.get(recipient.employeeId());
        return senderDepartment != null && Objects.equals(senderDepartment, recipientDepartment);
    }

    private Map<UUID, UUID> recipientDepartmentScope(
            StaffCommunicationDirectory.StaffMember sender,
            List<StaffCommunicationDirectory.StaffMember> candidates) {
        if (sender.employeeId() == null
                || candidates.stream().noneMatch(recipient -> requiresDepartmentMatch(sender, recipient))) {
            return Map.of();
        }
        Set<UUID> employeeIds = new LinkedHashSet<>();
        employeeIds.add(sender.employeeId());
        candidates.stream()
                .filter(recipient -> requiresDepartmentMatch(sender, recipient))
                .map(StaffCommunicationDirectory.StaffMember::employeeId)
                .filter(Objects::nonNull)
                .forEach(employeeIds::add);
        return employees.employeeSummaries(employeeIds).values().stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        EmployeeDirectory.EmployeeSummary::id,
                        EmployeeDirectory.EmployeeSummary::departmentId));
    }

    private boolean requiresDepartmentMatch(StaffCommunicationDirectory.StaffMember sender,
                                            StaffCommunicationDirectory.StaffMember recipient) {
        return sender.roles().contains(MANAGER) && recipient.roles().contains(HR)
                || sender.roles().contains(HR)
                && (recipient.roles().contains(TEAM_LEAD) || recipient.roles().contains(EMPLOYEE))
                || sender.roles().contains(TEAM_LEAD) && recipient.roles().contains(HR)
                || sender.roles().contains(EMPLOYEE) && recipient.roles().contains(HR);
    }

    private void broadcast(StaffCommunicationDirectory.StaffMember sender, Set<String> roles, String message) {
        if (roles.equals(Set.of(CEO))) {
            persistAndPublish(sender, staff.requireChiefExecutive(), message);
            return;
        }
        staff.activeWithAnyRole(roles).forEach(recipient -> persistAndPublish(sender, recipient, message));
    }

    private void broadcast(StaffCommunicationDirectory.StaffMember sender, Set<String> roles, String message,
                           InternalCallNotification.MessagePriority priority,
                           InternalCallNotification.MessageCategory category) {
        if (roles.equals(Set.of(CEO))) {
            persistAndPublish(sender, staff.requireChiefExecutive(), message, priority, category);
            return;
        }
        staff.activeWithAnyRole(roles).forEach(recipient -> persistAndPublish(sender, recipient, message, priority, category));
    }

    private void persistAndPublish(StaffCommunicationDirectory.StaffMember sender,
                                   StaffCommunicationDirectory.StaffMember recipient, String message) {
        persistAndPublish(sender, recipient, message, InternalCallNotification.MessagePriority.NORMAL,
                InternalCallNotification.MessageCategory.GENERAL);
    }

    private void persistAndPublish(StaffCommunicationDirectory.StaffMember sender,
                                   StaffCommunicationDirectory.StaffMember recipient, String message,
                                   InternalCallNotification.MessagePriority priority,
                                   InternalCallNotification.MessageCategory category) {
        if (sender.userId().equals(recipient.userId())) return;
        InternalCallNotification notification = notifications.saveAndFlush(new InternalCallNotification(
                sender.userId(), recipient.userId(), sender.fullName(), recipient.fullName(), systemMessage(message),
                priority, category));
    }

    private int priorityRank(InternalCallNotification.MessagePriority priority) {
        return switch (priority) { case URGENT -> 3; case HIGH -> 2; case NORMAL -> 1; };
    }

    private String systemMessage(String message) {
        String normalized = message == null ? "" : message.trim().replaceAll("\\s+", " ");
        return normalized.length() <= 500 ? normalized : normalized.substring(0, 497) + "...";
    }

    private List<ClaimedInternalCall> claimReadyForDelivery() {
        List<ClaimedInternalCall> claimed =
                transactions.execute(status -> {
                    Instant retryAt = Instant.now()
                            .plusSeconds(deliveryRetrySeconds);

                    List<InternalCallNotification> ready =
                            notifications.lockReadyForDelivery(
                                    Set.of(
                                            InternalCallNotification
                                                    .DeliveryStatus.FAILED,
                                            InternalCallNotification
                                                    .DeliveryStatus.QUEUED
                                    ),
                                    Instant.now(),
                                    PageRequest.of(0, 25)
                            );

                    ready.forEach(notification ->
                            notification.beginDeliveryAttempt(retryAt)
                    );

                    notifications.flush();

                    return ready.stream()
                            .map(notification ->
                                    new ClaimedInternalCall(
                                            notification.getId(),
                                            notification.getSenderUserId(),
                                            notification.getRecipientUserId(),
                                            notification.getMessage(),
                                            notification.getSentAt(),
                                            notification.getDeliveryAttempts(),
                                            retryAt
                                    )
                            )
                            .toList();
                });

        return claimed == null ? List.of() : claimed;
    }

    private void publishClaimed(
            ClaimedInternalCall notification
    ) {
        String failure = null;

        try {
            kafka.send(
                    topic,
                    notification.recipientUserId().toString(),
                    new InternalCallEvent(
                            notification.id(),
                            notification.senderUserId(),
                            notification.recipientUserId(),
                            notification.message(),
                            notification.sentAt()
                    )
            ).get(5, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            failure = "Kafka publish interrupted";
        } catch (Exception exception) {
            failure = exception.getClass().getSimpleName();
        }

        String deliveryFailure = failure;

        transactions.executeWithoutResult(status -> {
            Instant completedAt = Instant.now();

            if (deliveryFailure == null) {
                notifications.markPublishedIfUnacknowledged(
                        notification.id(),
                        notification.deliveryAttempt(),
                        completedAt,
                        notification.retryAt()
                );
            } else {
                notifications.markFailedIfUnacknowledged(
                        notification.id(),
                        notification.deliveryAttempt(),
                        deliveryFailure,
                        notification.retryAt(),
                        completedAt
                );
            }
        });
    }

    private record ClaimedInternalCall(
            UUID id,
            UUID senderUserId,
            UUID recipientUserId,
            String message,
            Instant sentAt,
            int deliveryAttempt,
            Instant retryAt
    ) {}
}
