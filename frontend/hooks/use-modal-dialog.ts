import { useEffect } from "react";

export function useModalDialog(onClose: () => void) {
    useEffect(() => {
        const dialogs = document.querySelectorAll<HTMLElement>("[role='dialog']");
        const dialog = dialogs.item(dialogs.length - 1);
        if (!dialog) return;
        const previousFocus = document.activeElement instanceof HTMLElement ? document.activeElement : null;
        const previousOverflow = document.body.style.overflow;
        const selector = "button:not([disabled]), [href], input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex='-1'])";
        const focusable = () => Array.from(dialog.querySelectorAll<HTMLElement>(selector))
            .filter((element) => element.offsetParent !== null);
        window.requestAnimationFrame(() => (focusable()[0] ?? dialog).focus());
        document.body.style.overflow = "hidden";
        const handleKeyDown = (event: KeyboardEvent) => {
            if (event.key === "Escape") {
                event.preventDefault();
                onClose();
                return;
            }
            if (event.key !== "Tab") return;
            const items = focusable();
            if (!items.length) { event.preventDefault(); dialog.focus(); return; }
            const first = items[0];
            const last = items[items.length - 1];
            if (event.shiftKey && document.activeElement === first) {
                event.preventDefault(); last.focus();
            } else if (!event.shiftKey && document.activeElement === last) {
                event.preventDefault(); first.focus();
            }
        };
        document.addEventListener("keydown", handleKeyDown);
        return () => {
            document.removeEventListener("keydown", handleKeyDown);
            document.body.style.overflow = previousOverflow;
            previousFocus?.focus();
        };
    }, [onClose]);
}

