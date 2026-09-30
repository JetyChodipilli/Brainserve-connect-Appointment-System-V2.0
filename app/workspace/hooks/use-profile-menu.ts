"use client";

import { type WorkspaceState } from "./use-workspace-state";
import { useEffect } from "react";

export function useProfileMenu(workspace: Pick<WorkspaceState, "profileMenuOpen" | "profileMenuRef" | "setProfileMenuOpen">) {
    const { profileMenuOpen, profileMenuRef, setProfileMenuOpen } = workspace;

    useEffect(() => {
        if (!profileMenuOpen) return;
        const closeWhenOutside = (event: PointerEvent) => {
            if (!profileMenuRef.current?.contains(event.target as Node)) setProfileMenuOpen(false);
        };
        const closeOnEscape = (event: KeyboardEvent) => {
            if (event.key === "Escape") setProfileMenuOpen(false);
        };
        document.addEventListener("pointerdown", closeWhenOutside);
        document.addEventListener("keydown", closeOnEscape);
        return () => {
            document.removeEventListener("pointerdown", closeWhenOutside);
            document.removeEventListener("keydown", closeOnEscape);
        };
    }, [profileMenuOpen, profileMenuRef, setProfileMenuOpen]);
}
