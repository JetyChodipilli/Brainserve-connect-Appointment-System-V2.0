"use client";

import { isBackendConfigured } from "../../lib/api";
import { type WorkspaceState } from "./use-workspace-state";
import { useEffect } from "react";

export function usePreviewSynchronization(workspace: Pick<WorkspaceState, "refreshPreviewWorkspace">) {
    const { refreshPreviewWorkspace } = workspace;

    useEffect(() => {
        if (isBackendConfigured) return;
        const synchronize = () => refreshPreviewWorkspace();
        const synchronizeStorage = (event: StorageEvent) => {
            if (event.key?.startsWith("brainserve.demo.")) synchronize();
        };
        window.addEventListener("storage", synchronizeStorage);
        window.addEventListener("brainserve:demo-appointments-updated", synchronize);
        window.addEventListener("focus", synchronize);
        return () => {
            window.removeEventListener("storage", synchronizeStorage);
            window.removeEventListener("brainserve:demo-appointments-updated", synchronize);
            window.removeEventListener("focus", synchronize);
        };
    }, [refreshPreviewWorkspace]);
}
