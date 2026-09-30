"use client";

import { isBackendConfigured } from "../../lib/api";
import { readDemoAccounts } from "../../preview/accounts";
import { writePreviewWorkspaceSession } from "../../preview/session";
import { DEMO_ACCOUNTS_KEY } from "../../preview/storage-keys";
import { roleFromAuthority } from "../../shared/config/roles";
import { type WorkspaceState } from "./use-workspace-state";
import { useEffect } from "react";

export function usePreviewIdentity(workspace: Pick<WorkspaceState, "userEmail" | "role" | "onLogout">) {
    const { userEmail, role, onLogout } = workspace;

    useEffect(() => {
        if (isBackendConfigured) return;
        let endingSession = false;
        const enforceCurrentPreviewIdentity = () => {
            if (endingSession) return;
            const account = readDemoAccounts().find((item) =>
                item.email.toLowerCase() === userEmail.toLowerCase() && item.status === "ACTIVE");
            const currentRole = account ? roleFromAuthority(account.role) : null;
            if (currentRole === role) return;
            endingSession = true;
            writePreviewWorkspaceSession(null);
            void onLogout();
        };
        const accountStorageChanged = (event: StorageEvent) => {
            if (event.key === DEMO_ACCOUNTS_KEY) enforceCurrentPreviewIdentity();
        };
        window.addEventListener("storage", accountStorageChanged);
        window.addEventListener("brainserve:demo-accounts-updated", enforceCurrentPreviewIdentity);
        return () => {
            window.removeEventListener("storage", accountStorageChanged);
            window.removeEventListener("brainserve:demo-accounts-updated", enforceCurrentPreviewIdentity);
        };
    }, [onLogout, role, userEmail]);
}
