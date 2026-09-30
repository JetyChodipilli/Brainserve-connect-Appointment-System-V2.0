"use client";

import { onNotificationSoundChange } from "../../lib/notification-sounds";
import { type WorkspaceState } from "./use-workspace-state";
import { useEffect } from "react";

export function useNotificationPreferences(workspace: Pick<WorkspaceState, "setSoundEnabled">) {
    const { setSoundEnabled } = workspace;

    useEffect(() => onNotificationSoundChange(setSoundEnabled), [setSoundEnabled]);
}
