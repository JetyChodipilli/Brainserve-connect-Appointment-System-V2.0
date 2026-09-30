import { apiRequest } from "../../../lib/api-client";
import type { InternalNotificationRecipient, InternalNotification } from "../../../types/api";

export const notificationsApi = {
internalNotificationRecipients() {
    return apiRequest<InternalNotificationRecipient[]>("/internal-notifications/recipients");
  },
internalNotificationInbox() {
    return apiRequest<InternalNotification[]>("/internal-notifications/inbox");
  },
internalNotificationSent() {
    return apiRequest<InternalNotification[]>("/internal-notifications/sent");
  },
internalNotificationArchive(page = 0, size = 50) {
    return apiRequest<InternalNotification[]>(`/internal-notifications/archive?page=${page}&size=${size}`);
  },
deleteInternalNotification(notificationId: string) {
    return apiRequest<void>(`/internal-notifications/${notificationId}`, { method: "DELETE" });
  },
internalNotificationUnreadCount() {
    return apiRequest<{ unreadCount: number }>("/internal-notifications/unread-count");
  },
sendInternalNotification(recipientUserId: string, message: string,
                           priority: InternalNotification["priority"] = "NORMAL",
                           category: InternalNotification["category"] = "GENERAL") {
    return apiRequest<InternalNotification>("/internal-notifications", {
      method: "POST", body: JSON.stringify({ recipientUserId, message, priority, category }),
    });
  },
markInternalNotificationRead(notificationId: string) {
    return apiRequest<InternalNotification>(`/internal-notifications/${notificationId}/read`, { method: "POST" });
  },
};
