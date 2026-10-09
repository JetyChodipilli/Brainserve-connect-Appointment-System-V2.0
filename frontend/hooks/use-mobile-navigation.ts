"use client";
import { useEffect, useState } from "react";
import { useModalDialog } from "./use-modal-dialog";

export function useMobileNavigation(open: boolean, onClose: () => void) {
    const [mobile, setMobile] = useState(false);
    useEffect(() => {
        const media = window.matchMedia("(max-width: 800px)");
        const changed = () => setMobile(media.matches);
        changed(); media.addEventListener("change", changed);
        return () => media.removeEventListener("change", changed);
    }, []);
    useModalDialog(onClose, open && mobile);
    return mobile;
}
