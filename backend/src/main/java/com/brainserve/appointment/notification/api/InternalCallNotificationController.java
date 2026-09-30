package com.brainserve.appointment.notification.api;

import com.brainserve.appointment.iam.api.StaffCommunicationDirectory;
import com.brainserve.appointment.notification.application.InternalCallNotificationService;
import com.brainserve.appointment.notification.domain.InternalCallNotification;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/internal-notifications")
@PreAuthorize("hasAuthority('INTERNAL_NOTIFICATION_READ')")
public class InternalCallNotificationController {
    private final InternalCallNotificationService service;
    private final StaffCommunicationDirectory staff;

    public InternalCallNotificationController(InternalCallNotificationService service,
                                              StaffCommunicationDirectory staff) {
        this.service = service;
        this.staff = staff;
    }

    @GetMapping("/recipients")
    List<RecipientResponse> recipients(@AuthenticationPrincipal Jwt jwt) {
        return service.eligibleRecipients(userId(jwt)).stream().map(RecipientResponse::from).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize("hasAuthority('INTERNAL_NOTIFICATION_SEND')")
    NotificationResponse send(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody SendRequest request) {
        return response(service.send(userId(jwt), request.recipientUserId(), request.message(),
                request.priority() == null ? InternalCallNotification.MessagePriority.NORMAL : request.priority(),
                request.category() == null ? InternalCallNotification.MessageCategory.GENERAL : request.category()));
    }

    @GetMapping("/inbox")
    List<NotificationResponse> inbox(@AuthenticationPrincipal Jwt jwt) {
        return responses(service.inbox(userId(jwt)));
    }

    @GetMapping("/sent")
    List<NotificationResponse> sent(@AuthenticationPrincipal Jwt jwt) {
        return responses(service.sent(userId(jwt)));
    }

    @GetMapping("/archive")
    List<NotificationResponse> archive(@AuthenticationPrincipal Jwt jwt,
                                       @RequestParam(defaultValue = "0") int page,
                                       @RequestParam(defaultValue = "50") int size) {
        return responses(service.archive(userId(jwt), page, size));
    }

    @DeleteMapping("/{notificationId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deleteArchived(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID notificationId) {
        service.deleteArchived(userId(jwt), notificationId);
    }

    @GetMapping("/unread-count")
    UnreadCountResponse unreadCount(@AuthenticationPrincipal Jwt jwt) {
        return new UnreadCountResponse(service.unreadCount(userId(jwt)));
    }

    @PostMapping("/{notificationId}/read")
    NotificationResponse markRead(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID notificationId) {
        return response(service.markRead(userId(jwt), notificationId));
    }

    private UUID userId(Jwt jwt) { return UUID.fromString(jwt.getSubject()); }

    private NotificationResponse response(InternalCallNotification value) {
        return responses(List.of(value)).getFirst();
    }

    private List<NotificationResponse> responses(List<InternalCallNotification> values) {
        Set<UUID> userIds = values.stream()
                .flatMap(value -> java.util.stream.Stream.of(value.getSenderUserId(), value.getRecipientUserId()))
                .collect(java.util.stream.Collectors.toSet());
        Map<UUID, StaffCommunicationDirectory.StaffMember> currentMembers = new LinkedHashMap<>();
        staff.findByUserIds(userIds).forEach(member -> currentMembers.put(member.userId(), member));
        return values.stream().map(value -> NotificationResponse.from(value,
                currentMembers.get(value.getSenderUserId()),
                currentMembers.get(value.getRecipientUserId()))).toList();
    }

    public record SendRequest(@NotNull UUID recipientUserId,
                              @NotBlank @Size(min = 2, max = 500) String message,
                              InternalCallNotification.MessagePriority priority,
                              InternalCallNotification.MessageCategory category) {}
    public record UnreadCountResponse(long unreadCount) {}
    public record RecipientResponse(UUID userId, String fullName, String email, Set<String> roles) {
        static RecipientResponse from(StaffCommunicationDirectory.StaffMember value) {
            return new RecipientResponse(value.userId(), value.fullName(), value.email(), value.roles());
        }
    }
    public record NotificationResponse(UUID id, UUID senderUserId, UUID recipientUserId, String senderName,
                                       String recipientName, String senderEmail, String recipientEmail,
                                       Set<String> senderRoles, Set<String> recipientRoles, String message,
                                       InternalCallNotification.MessagePriority priority,
                                       InternalCallNotification.MessageCategory category,
                                       String conversationKey,
                                       InternalCallNotification.DeliveryStatus deliveryStatus,
                                       Instant sentAt, Instant deliveredAt, Instant readAt,
                                       Instant archivedAt) {
        static NotificationResponse from(InternalCallNotification value,
                                         StaffCommunicationDirectory.StaffMember sender,
                                         StaffCommunicationDirectory.StaffMember recipient) {
            return new NotificationResponse(value.getId(), value.getSenderUserId(), value.getRecipientUserId(),
                    sender == null ? value.getSenderName() : sender.fullName(),
                    recipient == null ? value.getRecipientName() : recipient.fullName(),
                    sender == null ? null : sender.email(), recipient == null ? null : recipient.email(),
                    sender == null ? Set.of() : sender.roles(), recipient == null ? Set.of() : recipient.roles(),
                    value.getMessage(), value.getPriority(), value.getCategory(), value.getConversationKey(), value.getDeliveryStatus(),
                    value.getSentAt(), value.getDeliveredAt(), value.getReadAt(), value.getArchivedAt());
        }
    }
}
