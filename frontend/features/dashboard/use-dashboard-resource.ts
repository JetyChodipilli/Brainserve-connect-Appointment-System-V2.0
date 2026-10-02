"use client";

import { useEffect, useRef, useState } from "react";
import { ApiError, isBackendConfigured } from "../../lib/api-client";
import { DashboardScopeError } from "./administration-dashboard-model";

type Resource<T> = { scope: string; requestKey: string; load: (signal: AbortSignal) => Promise<T>;
    data: T | null; phase: "loading" | "ready" | "error"; error: string | null };

// Both cards and record pages use the same scope guard. No response can relabel older data.
export function useDashboardResource<T>(scope: string, refreshKey: number, load: (signal: AbortSignal) => Promise<T>) {
    const [sessionRevision, setSessionRevision] = useState(0);
    const resourceScope = `${sessionRevision}:${scope}`;
    const requestKey = `${resourceScope}:${refreshKey}`;
    const [state, setState] = useState<Resource<T>>({ scope: resourceScope, requestKey, load, data: null, phase: "loading", error: null });
    const generation = useRef(0);

    useEffect(() => {
        const changed = () => {
            generation.current += 1;
            setState({ scope: "", requestKey: "", load, data: null, phase: "loading", error: null });
            setSessionRevision((revision) => revision + 1);
        };
        window.addEventListener("brainserve:auth-session-changed", changed);
        window.addEventListener("brainserve:auth-session-expired", changed);
        return () => { window.removeEventListener("brainserve:auth-session-changed", changed); window.removeEventListener("brainserve:auth-session-expired", changed); };
    }, [load]);

    useEffect(() => {
        const requestGeneration = ++generation.current;
        const controller = new AbortController();
        const current = () => !controller.signal.aborted && generation.current === requestGeneration;
        if (isBackendConfigured) {
            void load(controller.signal).then((data) => {
                if (current()) setState({ scope: resourceScope, requestKey, load, data, phase: "ready", error: null });
            }).catch((reason: unknown) => {
                if (!current()) return;
                const retain = !(reason instanceof DashboardScopeError)
                    && !(reason instanceof ApiError && reason.status < 500);
                setState((previous) => ({ scope: resourceScope, requestKey, load,
                    data: retain && previous.scope === resourceScope ? previous.data : null,
                    phase: "error", error: reason instanceof Error ? reason.message : "The measurement could not be loaded. Please retry." }));
            });
        }
        return () => { controller.abort(); generation.current += 1; };
    }, [resourceScope, requestKey, load]);

    if (!isBackendConfigured) return { scope: resourceScope, data: null, phase: "error" as const,
        error: "Measurements unavailable in preview. Connect the secure backend to load source data." };
    const matches = state.scope === resourceScope;
    const completed = matches && state.requestKey === requestKey && state.load === load;
    return { scope: resourceScope, data: matches ? state.data : null, phase: completed ? state.phase : "loading" as const,
        error: completed ? state.error : null };
}
