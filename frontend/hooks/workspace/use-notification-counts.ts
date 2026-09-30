"use client";

import { brainServeApi, isBackendConfigured, isWorkspaceUpdateLeader } from "../../services/brainserve-api";
import { playNotificationSound } from "../../services/notification-sounds";
import { readDemoInternalNotifications } from "../../preview/notifications";
import { rolePermissions } from "../../config/roles";
import { type WorkspaceState } from "./use-workspace-state";
import { useEffect } from "react";

export function useNotificationCounts(workspace: Pick<WorkspaceState, "role" | "userEmail" | "previousUnreadRef" | "setUnreadNotifications">) {
    const { role, userEmail, previousUnreadRef, setUnreadNotifications } = workspace;

    useEffect(() => {
        if (!rolePermissions[role].includes("notifications")) return;
        let active = true;
        let requestInFlight = false;
        const refresh = async () => {
            if (requestInFlight || document.visibilityState !== "visible") return;
            requestInFlight = true;
            try {
                const unread = isBackendConfigured
                    ? (await brainServeApi.internalNotificationUnreadCount()).unreadCount
                    : readDemoInternalNotifications().filter((item) => item.recipientEmail === userEmail && !item.readAt).length;
                if (active) {
                    const previousUnread = previousUnreadRef.current;
                    previousUnreadRef.current = unread;
                    setUnreadNotifications(unread);
                    if (previousUnread !== null && unread > previousUnread) void playNotificationSound("message");
                }
            } catch { /* The inbox itself presents recoverable service errors. */ }
            finally { requestInFlight = false; }
        };
        void refresh();
        const timer = window.setInterval(() => {
            if (isWorkspaceUpdateLeader()) void refresh();
        }, 15000);
        return () => { active = false; window.clearInterval(timer); };
    }, [previousUnreadRef, role, setUnreadNotifications, userEmail]);
}
