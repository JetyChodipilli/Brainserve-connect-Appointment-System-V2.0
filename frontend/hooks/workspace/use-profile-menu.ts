"use client";

import { type WorkspaceState } from "./use-workspace-state";
import { useEffect } from "react";

export function useProfileMenu(workspace: Pick<WorkspaceState, "profileMenuOpen" | "profileMenuRef" | "setProfileMenuOpen">) {
    const { profileMenuOpen, profileMenuRef, setProfileMenuOpen } = workspace;

    useEffect(() => {
        if (!profileMenuOpen) return;
        const menu = profileMenuRef.current?.querySelector<HTMLElement>("[role='menu']");
        const trigger = profileMenuRef.current?.querySelector<HTMLButtonElement>("[aria-haspopup='menu']");
        const items = () => Array.from(menu?.querySelectorAll<HTMLButtonElement>("[role='menuitem']:not(:disabled), [role='menuitemcheckbox']:not(:disabled)") ?? []);
        const frame = requestAnimationFrame(() => { items().forEach((item, index) => { item.tabIndex = index === 0 ? 0 : -1; }); items()[0]?.focus(); });
        const closeWhenOutside = (event: PointerEvent) => {
            if (!profileMenuRef.current?.contains(event.target as Node)) setProfileMenuOpen(false);
        };
        const closeOnEscape = (event: KeyboardEvent) => {
            if (!menu?.contains(event.target as Node)) return;
            if (event.key === "Escape") {
                event.preventDefault(); event.stopPropagation(); setProfileMenuOpen(false); trigger?.focus();
            } else if (["ArrowDown", "ArrowUp", "Home", "End"].includes(event.key)) {
                event.preventDefault(); event.stopPropagation();
                const controls = items(), index = controls.findIndex(item => item === document.activeElement);
                const next = event.key === "Home" ? 0 : event.key === "End" ? controls.length - 1 : (index + (event.key === "ArrowDown" ? 1 : controls.length - 1)) % controls.length;
                controls.forEach((item, at) => { item.tabIndex = at === next ? 0 : -1; }); controls[next]?.focus();
            } else if (event.key === "Tab") { setProfileMenuOpen(false); trigger?.focus(); }
        };
        document.addEventListener("pointerdown", closeWhenOutside);
        document.addEventListener("keydown", closeOnEscape, true);
        return () => {
            cancelAnimationFrame(frame);
            document.removeEventListener("pointerdown", closeWhenOutside);
            document.removeEventListener("keydown", closeOnEscape, true);
            if (menu?.contains(document.activeElement) || document.activeElement === document.body) trigger?.focus({ preventScroll: true });
        };
    }, [profileMenuOpen, profileMenuRef, setProfileMenuOpen]);
}
