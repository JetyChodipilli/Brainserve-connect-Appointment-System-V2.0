"use client";

import { onNotificationSoundChange } from "../../services/notification-sounds";
import { type WorkspaceState } from "./use-workspace-state";
import { useEffect } from "react";

export function useNotificationPreferences(workspace: Pick<WorkspaceState, "setSoundEnabled">) {
    const { setSoundEnabled } = workspace;

    useEffect(() => onNotificationSoundChange(setSoundEnabled), [setSoundEnabled]);
}
