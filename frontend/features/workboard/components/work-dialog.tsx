"use client";
import { createPortal } from "react-dom";
import { useEffect, useRef, type ReactNode } from "react";

export function WorkDialog({ children, titleId, className, onClose }: { children: ReactNode; titleId: string; className: string; onClose: () => void }) {
    const ref = useRef<HTMLDialogElement>(null);
    useEffect(() => {
        const element = ref.current, trigger = document.activeElement as HTMLElement | null;
        element?.showModal();
        element?.querySelector<HTMLElement>("[data-initial-focus]")?.focus();
        return () => {
            element?.close();
            const target = trigger?.isConnected ? trigger : document.querySelector<HTMLElement>("[data-workboard-focus]");
            target?.focus({ preventScroll: true });
        };
    }, []);
    if (typeof document === "undefined") return null;
    return createPortal(<dialog ref={ref} className={className} aria-labelledby={titleId} onCancel={(event) => { event.preventDefault(); event.stopPropagation(); onClose(); }}
        onClick={(event) => {
            if (event.target !== event.currentTarget) return;
            const bounds = event.currentTarget.getBoundingClientRect();
            if (event.clientX < bounds.left || event.clientX > bounds.right || event.clientY < bounds.top || event.clientY > bounds.bottom) onClose();
        }} onKeyDown={(event) => {
            event.stopPropagation();
            if (event.key !== "Tab") return;
            const controls = Array.from(event.currentTarget.querySelectorAll<HTMLElement>("button:not(:disabled), a[href], input:not(:disabled), select:not(:disabled), textarea:not(:disabled), [tabindex]:not([tabindex='-1'])"))
                .filter((element) => element.getClientRects().length > 0);
            const first = controls[0], last = controls[controls.length - 1];
            if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last?.focus(); }
            else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first?.focus(); }
        }}>{children}</dialog>, document.body);
}
