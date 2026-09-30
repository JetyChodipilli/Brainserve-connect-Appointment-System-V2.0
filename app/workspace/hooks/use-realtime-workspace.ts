"use client";

import { isBackendConfigured, subscribeToWorkspaceUpdates } from "../../lib/api";
import { type WorkspaceState } from "./use-workspace-state";
import { useEffect } from "react";

export function useRealtimeWorkspace(workspace: Pick<WorkspaceState, "setWorkspaceRevision" | "setLastLiveUpdate" | "setLiveState" | "role" | "userEmail">) {
    const { setWorkspaceRevision, setLastLiveUpdate, setLiveState, role, userEmail } = workspace;

    useEffect(() => {
        if (!isBackendConfigured) return;
        let refreshTimer: number | null = null;
        let lastRefreshAt = Date.now();
        const minimumRefreshInterval = 15_000;
        const queueSafeRefresh = () => {
            if (refreshTimer) window.clearTimeout(refreshTimer);
            const applyWhenIdle = () => {
                if (document.visibilityState !== "visible") return;
                const active = document.activeElement;
                const editing = active instanceof HTMLElement
                    && (["INPUT", "TEXTAREA", "SELECT"].includes(active.tagName)
                        || Boolean(active.closest("[role='dialog']")));
                if (editing) {
                    refreshTimer = window.setTimeout(applyWhenIdle, 1_500);
                    return;
                }
                lastRefreshAt = Date.now();
                setWorkspaceRevision((revision) => revision + 1);
            };
            const elapsed = Date.now() - lastRefreshAt;
            refreshTimer = window.setTimeout(
                applyWhenIdle,
                Math.max(500, minimumRefreshInterval - elapsed),
            );
        };
        const unsubscribe = subscribeToWorkspaceUpdates(
            () => {
                setLastLiveUpdate(new Date());
                queueSafeRefresh();
            },
            setLiveState,
        );
        const refreshWhenVisible = () => {
            if (document.visibilityState === "visible") queueSafeRefresh();
        };
        document.addEventListener("visibilitychange", refreshWhenVisible);
        return () => {
            unsubscribe();
            document.removeEventListener("visibilitychange", refreshWhenVisible);
            if (refreshTimer) window.clearTimeout(refreshTimer);
        };
    }, [role, setLastLiveUpdate, setLiveState, setWorkspaceRevision, userEmail]);
}
