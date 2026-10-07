"use client";

import { onNotificationSoundChange, applyNotificationSoundPreference } from "../../services/notification-sounds";
import { isBackendConfigured } from '../../lib/api-client';
import { notificationPolicyApi } from '../../features/notifications/api/notification-policy-api';
import { type WorkspaceState } from "./use-workspace-state";
import { useEffect } from "react";

export function useNotificationPreferences(workspace: Pick<WorkspaceState, "setSoundEnabled" | 'role' | 'userEmail'>) {
    const { setSoundEnabled, role, userEmail } = workspace;

    useEffect(() => onNotificationSoundChange(setSoundEnabled), [setSoundEnabled]);
    useEffect(() => {
        if (!isBackendConfigured) return;
        const controller = new AbortController();
        const clear = () => { controller.abort(); applyNotificationSoundPreference(false); };
        const timer = window.setTimeout(() => { void notificationPolicyApi.preferences(controller.signal).then(value => {
            if (!controller.signal.aborted && typeof value.soundEnabled === 'boolean') applyNotificationSoundPreference(value.soundEnabled);
        }).catch(() => { /* Delivery remains available if the preference service needs recovery. */ }); }, 0);
        window.addEventListener('brainserve:auth-session-changed', clear);
        window.addEventListener('brainserve:auth-session-expired', clear);
        return () => { window.clearTimeout(timer); controller.abort(); window.removeEventListener('brainserve:auth-session-changed', clear); window.removeEventListener('brainserve:auth-session-expired', clear); };
    }, [role, userEmail]);
}
