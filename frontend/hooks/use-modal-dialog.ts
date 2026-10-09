import { useEffect, useRef } from "react";

export function useModalDialog(onClose: () => void, enabled = true) {
    const closeRef = useRef(onClose);
    useEffect(() => { closeRef.current = onClose; }, [onClose]);
    useEffect(() => {
        if (!enabled) return;
        const dialogs = document.querySelectorAll<HTMLElement>("[role='dialog']");
        const dialog = dialogs.item(dialogs.length - 1);
        if (!dialog) return;
        const previousFocus = document.activeElement instanceof HTMLElement ? document.activeElement : null;
        const previousOverflow = document.body.style.overflow;
        const previousTabIndex = dialog.getAttribute("tabindex");
        dialog.tabIndex = -1;
        const isolated: { element: HTMLElement; inert: boolean }[] = [];
        for (let node: HTMLElement = dialog; node.parentElement; node = node.parentElement) {
            for (const sibling of node.parentElement.children) {
                if (sibling instanceof HTMLElement && sibling !== node && !sibling.hasAttribute("data-modal-backdrop") && !["SCRIPT", "STYLE", "LINK"].includes(sibling.tagName)) {
                    isolated.push({ element: sibling, inert: sibling.inert });
                    sibling.inert = true;
                }
            }
            if (node.parentElement === document.body) break;
        }
        const selector = "button:not(:disabled), a[href], input:not(:disabled), select:not(:disabled), textarea:not(:disabled), [tabindex]:not([tabindex='-1'])";
        const focusable = () => Array.from(dialog.querySelectorAll<HTMLElement>(selector))
            .filter((element) => !element.closest("[hidden], [inert]") && element.getClientRects().length > 0 && getComputedStyle(element).visibility !== "hidden");
        const frame = window.requestAnimationFrame(() => {
            if (!dialog.contains(document.activeElement)) (focusable()[0] ?? dialog).focus();
        });
        document.body.style.overflow = "hidden";
        const handleKeyDown = (event: KeyboardEvent) => {
            if (event.defaultPrevented || document.querySelector("dialog[open]")) return;
            const current = document.querySelectorAll("[role='dialog']");
            if (current.item(current.length - 1) !== dialog) return;
            if (event.key === "Escape") {
                event.preventDefault();
                event.stopPropagation();
                closeRef.current();
                return;
            }
            if (event.key !== "Tab") return;
            const items = focusable();
            if (!items.length) { event.preventDefault(); dialog.focus(); return; }
            const first = items[0];
            const last = items[items.length - 1];
            if (!dialog.contains(document.activeElement)) {
                event.preventDefault(); (event.shiftKey ? last : first).focus();
            } else if (event.shiftKey && document.activeElement === first) {
                event.preventDefault(); last.focus();
            } else if (!event.shiftKey && document.activeElement === last) {
                event.preventDefault(); first.focus();
            }
        };
        document.addEventListener("keydown", handleKeyDown);
        return () => {
            window.cancelAnimationFrame(frame);
            document.removeEventListener("keydown", handleKeyDown);
            for (const { element, inert } of isolated) element.inert = inert;
            document.body.style.overflow = previousOverflow;
            if (previousTabIndex === null) dialog.removeAttribute("tabindex");
            else dialog.setAttribute("tabindex", previousTabIndex);
            const usable = (element: HTMLElement | null): element is HTMLElement => Boolean(element?.isConnected
                && !element.closest("[hidden], [inert]") && !element.matches(":disabled")
                && element.getClientRects().length && getComputedStyle(element).visibility !== "hidden");
            const navigationOpener = document.querySelector<HTMLElement>("[aria-controls='workspace-navigation']");
            const target = usable(previousFocus) && !dialog.contains(previousFocus) ? previousFocus
                : usable(navigationOpener) ? navigationOpener : document.querySelector<HTMLElement>("#workspace-main");
            target?.focus({ preventScroll: true });
        };
    }, [enabled]);
}

