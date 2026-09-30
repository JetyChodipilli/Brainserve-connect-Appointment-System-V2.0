package com.brainserve.appointment.notification.application;

import com.brainserve.appointment.iam.api.StaffCommunicationDirectory;
import com.brainserve.appointment.notification.api.InternalCallEvent;
import com.brainserve.appointment.notification.domain.InternalCallNotification;
import com.brainserve.appointment.notification.infrastructure.InternalCallNotificationRepository;
import com.brainserve.appointment.shared.application.BusinessException;
import com.brainserve.appointment.departmenthr.api.DepartmentHrDirectory;
import com.brainserve.appointment.employee.api.EmployeeDirectory;
import com.brainserve.appointment.manager.api.ManagerDirectory;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import com.brainserve.appointment.essentiallog.api.EssentialLogService;

class InternalCallNotificationServiceTest {
    private final InternalCallNotificationRepository notifications = mock(InternalCallNotificationRepository.class);
    private final StaffCommunicationDirectory staff = mock(StaffCommunicationDirectory.class);
    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, InternalCallEvent> kafka = mock(KafkaTemplate.class);
    private final DepartmentHrDirectory departmentHrs = mock(DepartmentHrDirectory.class);
    private final ManagerDirectory managers = mock(ManagerDirectory.class);
    private final EmployeeDirectory employees = mock(EmployeeDirectory.class);
    private final EssentialLogService essentialLogs = mock(EssentialLogService.class);
    private final TransactionOperations transactions = new TransactionOperations() {
        @Override
        public <T> T execute(TransactionCallback<T> action) {
            return action.doInTransaction(mock(TransactionStatus.class));
        }
    };
    private final InternalCallNotificationService service = new InternalCallNotificationService(
            notifications, staff, kafka, "brainserve.internal-calls.v1", departmentHrs, managers, employees, essentialLogs,
            "Asia/Kolkata", 30, transactions);

    @Test
    void exposesOnlyRecipientsAllowedByTheSenderRole() {
        UUID ceoId = UUID.randomUUID();
        var ceo = member(ceoId, "CEO", "ROLE_CEO");
        var hr = member(UUID.randomUUID(), "HR Admin", "ROLE_HR_ADMIN");
        var receptionist = member(UUID.randomUUID(), "Reception", "ROLE_RECEPTIONIST");
        when(staff.requireActive(ceoId)).thenReturn(ceo);
        when(staff.activeWithAnyRole(Set.of("ROLE_MANAGER", "ROLE_HR_ADMIN", "ROLE_TEAM_LEAD", "ROLE_RECEPTIONIST")))
                .thenReturn(List.of(hr, receptionist));

        assertThat(service.eligibleRecipients(ceoId)).containsExactly(hr, receptionist);
        verify(staff).activeWithAnyRole(Set.of("ROLE_MANAGER", "ROLE_HR_ADMIN", "ROLE_TEAM_LEAD", "ROLE_RECEPTIONIST"));
    }

    @Test
    void receptionistCanReplyToLeadershipButNotEmployees() {
        UUID receptionId = UUID.randomUUID(); UUID hrId = UUID.randomUUID(); UUID employeeId = UUID.randomUUID();
        when(staff.requireActive(receptionId)).thenReturn(member(receptionId, "Reception", "ROLE_RECEPTIONIST"));
        when(staff.requireActive(hrId)).thenReturn(member(hrId, "HR Admin", "ROLE_HR_ADMIN"));
        when(staff.requireActive(employeeId)).thenReturn(member(employeeId, "Employee", "ROLE_EMPLOYEE"));
        InternalCallNotification persisted = mock(InternalCallNotification.class);
        when(persisted.getId()).thenReturn(UUID.randomUUID());
        when(persisted.getMessage()).thenReturn("Acknowledged. Reception will coordinate this.");
        when(persisted.getSentAt()).thenReturn(Instant.now());
        when(persisted.getRecipientUserId()).thenReturn(hrId);
        when(notifications.saveAndFlush(any(InternalCallNotification.class))).thenReturn(persisted);
        var sent = new CompletableFuture<org.springframework.kafka.support.SendResult<String, InternalCallEvent>>();
        sent.complete(null);
        when(kafka.send(eq("brainserve.internal-calls.v1"), eq(hrId.toString()), any(InternalCallEvent.class)))
                .thenReturn(sent);

        assertThat(service.send(receptionId, hrId, "Acknowledged. Reception will coordinate this."))
                .isSameAs(persisted);
        assertThatThrownBy(() -> service.send(receptionId, employeeId, "Please come to Reception"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("cannot send");
    }

    @Test
    void employeeCannotMessageHrFromAnotherDepartment() {
        UUID employeeUserId = UUID.randomUUID();
        UUID hrUserId = UUID.randomUUID();
        var employee = member(employeeUserId, "Employee", "ROLE_EMPLOYEE");
        var hr = member(hrUserId, "HR Admin", "ROLE_HR_ADMIN");
        when(staff.requireActive(employeeUserId)).thenReturn(employee);
        when(staff.requireActive(hrUserId)).thenReturn(hr);
        when(employees.departmentIdForEmployee(employee.employeeId())).thenReturn(UUID.randomUUID());
        when(employees.departmentIdForEmployee(hr.employeeId())).thenReturn(UUID.randomUUID());

        assertThatThrownBy(() -> service.send(employeeUserId, hrUserId, "Please review my request"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("same department");
    }

    @Test
    void queuesAnAllowedHrToReceptionistCallDurably() {
        UUID hrId = UUID.randomUUID(); UUID receptionId = UUID.randomUUID(); UUID notificationId = UUID.randomUUID();
        when(staff.requireActive(hrId)).thenReturn(member(hrId, "HR Admin", "ROLE_HR_ADMIN"));
        when(staff.requireActive(receptionId)).thenReturn(member(receptionId, "Reception", "ROLE_RECEPTIONIST"));
        InternalCallNotification persisted = mock(InternalCallNotification.class);
        when(persisted.getId()).thenReturn(notificationId);
        when(persisted.getMessage()).thenReturn("Please come to my cabin");
        when(persisted.getSentAt()).thenReturn(Instant.now());
        when(persisted.getRecipientUserId()).thenReturn(receptionId);
        when(notifications.saveAndFlush(any(InternalCallNotification.class))).thenReturn(persisted);
        var sent = new CompletableFuture<org.springframework.kafka.support.SendResult<String, InternalCallEvent>>();
        sent.complete(null);
        when(kafka.send(eq("brainserve.internal-calls.v1"), eq(receptionId.toString()), any(InternalCallEvent.class)))
                .thenReturn(sent);

        assertThat(service.send(hrId, receptionId, "Please come to my cabin")).isSameAs(persisted);
        verify(notifications).saveAndFlush(any(InternalCallNotification.class));
    }

    @Test
    void queuesAnHrReplyToTheCeoDurably() {
        UUID hrId = UUID.randomUUID(); UUID ceoId = UUID.randomUUID(); UUID notificationId = UUID.randomUUID();
        when(staff.requireActive(hrId)).thenReturn(member(hrId, "HR Admin", "ROLE_HR_ADMIN"));
        when(staff.requireActive(ceoId)).thenReturn(member(ceoId, "CEO", "ROLE_CEO"));
        InternalCallNotification persisted = mock(InternalCallNotification.class);
        when(persisted.getId()).thenReturn(notificationId);
        when(persisted.getMessage()).thenReturn("I am coming to your cabin");
        when(persisted.getSentAt()).thenReturn(Instant.now());
        when(persisted.getRecipientUserId()).thenReturn(ceoId);
        when(notifications.saveAndFlush(any(InternalCallNotification.class))).thenReturn(persisted);
        var sent = new CompletableFuture<org.springframework.kafka.support.SendResult<String, InternalCallEvent>>();
        sent.complete(null);
        when(kafka.send(eq("brainserve.internal-calls.v1"), eq(ceoId.toString()), any(InternalCallEvent.class)))
                .thenReturn(sent);

        assertThat(service.send(hrId, ceoId, "I am coming to your cabin")).isSameAs(persisted);
        verify(notifications).saveAndFlush(any(InternalCallNotification.class));
    }

    @Test
    void rejectsEmployeeToReceptionistCalls() {
        UUID employeeId = UUID.randomUUID(); UUID receptionId = UUID.randomUUID();
        when(staff.requireActive(employeeId)).thenReturn(member(employeeId, "Employee", "ROLE_EMPLOYEE"));
        when(staff.requireActive(receptionId)).thenReturn(member(receptionId, "Reception", "ROLE_RECEPTIONIST"));

        assertThatThrownBy(() -> service.send(employeeId, receptionId, "Please meet me"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("cannot send");
    }

    @Test
    void securityArrivalQueuesReceptionNotification() {
        UUID securityId = UUID.randomUUID(); UUID receptionId = UUID.randomUUID(); UUID notificationId = UUID.randomUUID();
        when(staff.requireActive(securityId)).thenReturn(member(securityId, "Security Desk", "ROLE_SECURITY"));
        when(staff.activeWithAnyRole(Set.of("ROLE_RECEPTIONIST")))
                .thenReturn(List.of(member(receptionId, "Reception Desk", "ROLE_RECEPTIONIST")));
        InternalCallNotification persisted = mock(InternalCallNotification.class);
        when(persisted.getId()).thenReturn(notificationId);
        when(persisted.getMessage()).thenReturn("Security intake BSA-TEST-1234");
        when(persisted.getSentAt()).thenReturn(Instant.now());
        when(persisted.getRecipientUserId()).thenReturn(receptionId);
        when(persisted.getSenderUserId()).thenReturn(securityId);
        when(notifications.saveAndFlush(any(InternalCallNotification.class))).thenReturn(persisted);
        var sent = new CompletableFuture<org.springframework.kafka.support.SendResult<String, InternalCallEvent>>();
        sent.complete(null);
        when(kafka.send(eq("brainserve.internal-calls.v1"), eq(receptionId.toString()), any(InternalCallEvent.class)))
                .thenReturn(sent);

        service.sendSecurityArrival(securityId, "BSA-TEST-1234", "Visitor Name", "Product meeting");

        verify(notifications).saveAndFlush(any(InternalCallNotification.class));
    }

    @Test
    void receptionForwardQueuesAnHrCabinNotification() {
        UUID receptionId = UUID.randomUUID(); UUID hrId = UUID.randomUUID(); UUID hrEmployeeId = UUID.randomUUID();
        UUID notificationId = UUID.randomUUID();
        when(staff.requireActive(receptionId)).thenReturn(member(receptionId, "Reception Desk", "ROLE_RECEPTIONIST"));
        when(staff.activeByEmployeeId(hrEmployeeId))
                .thenReturn(Optional.of(member(hrId, "HR Admin", "ROLE_HR_ADMIN")));
        InternalCallNotification persisted = mock(InternalCallNotification.class);
        when(persisted.getId()).thenReturn(notificationId);
        when(persisted.getMessage()).thenReturn("Reception forwarding Visitor to the HR cabin");
        when(persisted.getSentAt()).thenReturn(Instant.now());
        when(persisted.getRecipientUserId()).thenReturn(hrId);
        when(notifications.saveAndFlush(any(InternalCallNotification.class))).thenReturn(persisted);
        var sent = new CompletableFuture<org.springframework.kafka.support.SendResult<String, InternalCallEvent>>();
        sent.complete(null);
        when(kafka.send(eq("brainserve.internal-calls.v1"), eq(hrId.toString()), any(InternalCallEvent.class)))
                .thenReturn(sent);

        service.sendReceptionForward(receptionId, "BSA-TEST-1234", hrEmployeeId,
                "HR_VISIT", "Visitor", "Proceeding now");

        verify(notifications).saveAndFlush(any(InternalCallNotification.class));
    }

    @Test
    void receptionVerificationTargetsTheRequestedHrHost() {
        UUID receptionId = UUID.randomUUID(); UUID hrEmployeeId = UUID.randomUUID();
        UUID hrUserId = UUID.randomUUID(); UUID notificationId = UUID.randomUUID(); UUID departmentId = UUID.randomUUID();
        when(staff.requireActive(receptionId)).thenReturn(member(receptionId, "Reception Desk", "ROLE_RECEPTIONIST"));
        when(staff.requireActive(hrUserId)).thenReturn(member(hrUserId, "Requested HR", "ROLE_HR_ADMIN"));
        when(departmentHrs.requireForDepartment(departmentId)).thenReturn(new DepartmentHrDirectory.Assignment(
                UUID.randomUUID(), departmentId, hrUserId, hrEmployeeId, "Requested HR", "hr@brainserve.in"));
        InternalCallNotification persisted = mock(InternalCallNotification.class);
        when(persisted.getId()).thenReturn(notificationId);
        when(persisted.getMessage()).thenReturn("Reception verified Visitor");
        when(persisted.getSentAt()).thenReturn(Instant.now());
        when(persisted.getRecipientUserId()).thenReturn(hrUserId);
        when(notifications.saveAndFlush(any(InternalCallNotification.class))).thenReturn(persisted);
        var sent = new CompletableFuture<org.springframework.kafka.support.SendResult<String, InternalCallEvent>>();
        sent.complete(null);
        when(kafka.send(eq("brainserve.internal-calls.v1"), eq(hrUserId.toString()), any(InternalCallEvent.class)))
                .thenReturn(sent);

        service.sendReceptionVerification(receptionId, hrEmployeeId, departmentId, "HR_VISIT",
                "BSA-TEST-4321", "Visitor", "Meet requested HR");

        verify(notifications).saveAndFlush(any(InternalCallNotification.class));
    }

    @Test
    void hrVisitorCardTargetsTheEmployeeLinkedToTheSelectedHost() {
        UUID hrId = UUID.randomUUID(); UUID hostEmployeeId = UUID.randomUUID();
        UUID employeeUserId = UUID.randomUUID(); UUID notificationId = UUID.randomUUID();
        when(staff.requireActive(hrId)).thenReturn(member(hrId, "HR Admin", "ROLE_HR_ADMIN"));
        when(staff.activeByEmployeeId(hostEmployeeId))
                .thenReturn(Optional.of(member(employeeUserId, "Host Employee", "ROLE_EMPLOYEE")));
        InternalCallNotification persisted = mock(InternalCallNotification.class);
        when(persisted.getId()).thenReturn(notificationId);
        when(persisted.getMessage()).thenReturn("Visitor card BSA-TEST-2041");
        when(persisted.getSentAt()).thenReturn(Instant.now());
        when(persisted.getRecipientUserId()).thenReturn(employeeUserId);
        when(notifications.saveAndFlush(any(InternalCallNotification.class))).thenReturn(persisted);
        var sent = new CompletableFuture<org.springframework.kafka.support.SendResult<String, InternalCallEvent>>();
        sent.complete(null);
        when(kafka.send(eq("brainserve.internal-calls.v1"), eq(employeeUserId.toString()),
                any(InternalCallEvent.class))).thenReturn(sent);

        service.notifyEmployeeOfVisitorCard(hrId, hostEmployeeId, "BSA-TEST-2041", "Visitor Name",
                "visitor@example.com", "+919876543210", "Example Ltd", "Project review", Instant.now(),
                "PENDING_TEAM_LEAD_APPROVAL");

        verify(staff).activeByEmployeeId(hostEmployeeId);
        verify(notifications).saveAndFlush(any(InternalCallNotification.class));
    }

    @Test
    void dispatcherPublishesOnlyCommittedQueuedRowsAndRecordsTheAttempt() {
        UUID senderId = UUID.randomUUID(); UUID recipientId = UUID.randomUUID(); UUID notificationId = UUID.randomUUID();
        InternalCallNotification persisted = mock(InternalCallNotification.class);
        when(persisted.getId()).thenReturn(notificationId);
        when(persisted.getSenderUserId()).thenReturn(senderId);
        when(persisted.getRecipientUserId()).thenReturn(recipientId);
        when(persisted.getMessage()).thenReturn("Durable delivery");
        when(persisted.getSentAt()).thenReturn(Instant.now());
        when(persisted.getDeliveryAttempts()).thenReturn(1);
        when(notifications.lockReadyForDelivery(
                eq(Set.of(InternalCallNotification.DeliveryStatus.FAILED,
                        InternalCallNotification.DeliveryStatus.QUEUED)),
                any(Instant.class), any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(List.of(persisted));
        var sent = new CompletableFuture<org.springframework.kafka.support.SendResult<String, InternalCallEvent>>();
        sent.complete(null);
        when(kafka.send(eq("brainserve.internal-calls.v1"), eq(recipientId.toString()),
                any(InternalCallEvent.class))).thenReturn(sent);

        service.dispatchPending();

        verify(persisted).beginDeliveryAttempt(any(Instant.class));
        verify(kafka).send(eq("brainserve.internal-calls.v1"), eq(recipientId.toString()),
                any(InternalCallEvent.class));
        verify(notifications).markPublishedIfUnacknowledged(eq(notificationId), eq(1),
                any(Instant.class), any(Instant.class));
    }

    @Test
    void automaticVisitorMessagesAreBoundedToTheDatabaseColumn() {
        UUID securityId = UUID.randomUUID(); UUID receptionId = UUID.randomUUID();
        when(staff.requireActive(securityId)).thenReturn(member(securityId, "Security Desk", "ROLE_SECURITY"));
        when(staff.activeWithAnyRole(Set.of("ROLE_RECEPTIONIST")))
                .thenReturn(List.of(member(receptionId, "Reception Desk", "ROLE_RECEPTIONIST")));
        when(notifications.saveAndFlush(any(InternalCallNotification.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        var sent = new CompletableFuture<org.springframework.kafka.support.SendResult<String, InternalCallEvent>>();
        sent.complete(null);
        when(kafka.send(eq("brainserve.internal-calls.v1"), eq(receptionId.toString()), any(InternalCallEvent.class)))
                .thenReturn(sent);

        service.sendSecurityArrival(securityId, "BSA-TEST-9999", "Visitor", "x".repeat(1000));

        ArgumentCaptor<InternalCallNotification> saved = ArgumentCaptor.forClass(InternalCallNotification.class);
        verify(notifications, atLeastOnce()).saveAndFlush(saved.capture());
        assertThat(saved.getAllValues()).allSatisfy(value -> assertThat(value.getMessage()).hasSizeLessThanOrEqualTo(500));
        assertThat(saved.getAllValues()).allSatisfy(value -> {
            assertThat(value.getPriority()).isEqualTo(InternalCallNotification.MessagePriority.URGENT);
            assertThat(value.getCategory()).isEqualTo(InternalCallNotification.MessageCategory.VISITOR);
            assertThat(value.getConversationKey()).isNotBlank();
        });
    }

    private StaffCommunicationDirectory.StaffMember member(UUID id, String name, String role) {
        return new StaffCommunicationDirectory.StaffMember(id, UUID.randomUUID(), name,
                name.toLowerCase().replace(' ', '.') + "@brainserve.in", Set.of(role));
    }
}