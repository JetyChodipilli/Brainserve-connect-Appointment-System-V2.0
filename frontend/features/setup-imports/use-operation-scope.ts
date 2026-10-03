"use client";
import { useEffect, useRef, useState } from "react";

export function useSessionRevision() {
    const [revision, setRevision] = useState(0);
    useEffect(() => {
        const changed = () => setRevision((value) => value + 1);
        window.addEventListener("brainserve:auth-session-changed", changed);
        window.addEventListener("brainserve:auth-session-expired", changed);
        return () => { window.removeEventListener("brainserve:auth-session-changed", changed); window.removeEventListener("brainserve:auth-session-expired", changed); };
    }, []);
    return revision;
}

// Selection changes invalidate reads, file decoding and mutations before another result can paint.
export function useOperationScope() {
    const generation = useRef(0), controllers = useRef(new Set<AbortController>());
    const invalidate = () => { generation.current++; controllers.current.forEach((controller) => controller.abort()); controllers.current.clear(); };
    useEffect(() => {
        window.addEventListener("brainserve:auth-session-changed", invalidate);
        window.addEventListener("brainserve:auth-session-expired", invalidate);
        return () => { invalidate(); window.removeEventListener("brainserve:auth-session-changed", invalidate); window.removeEventListener("brainserve:auth-session-expired", invalidate); };
    }, []);
    const request = () => {
        const token = generation.current, controller = new AbortController(); controllers.current.add(controller);
        return { signal: controller.signal, current: () => token === generation.current && !controller.signal.aborted,
            finish: () => controllers.current.delete(controller) };
    };
    return { request, invalidate };
}
