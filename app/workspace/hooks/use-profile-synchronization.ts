"use client";

import { brainServeApi, isBackendConfigured } from "../../lib/api";
import { type WorkspaceState } from "./use-workspace-state";
import { useEffect } from "react";

export function useProfileSynchronization(workspace: Pick<WorkspaceState, "setProfilePhotoUrl" | "userEmail" | "setProfileName">) {
    const { setProfilePhotoUrl, userEmail, setProfileName } = workspace;

    useEffect(() => {
        if (!isBackendConfigured) {
            const timer = window.setTimeout(() => setProfilePhotoUrl(
                window.localStorage.getItem(`brainserve.demo.profile.photo.${userEmail.toLowerCase()}`)), 0);
            return () => window.clearTimeout(timer);
        }
        let active = true;
        brainServeApi.myProfile().then((profile) => {
            if (active) {
                setProfilePhotoUrl(profile.photoUrl);
                setProfileName(profile.fullName);
            }
        })
            .catch(() => { /* My Profile displays the recoverable error when opened. */ });
        return () => { active = false; };
    }, [setProfileName, setProfilePhotoUrl, userEmail]);
}
