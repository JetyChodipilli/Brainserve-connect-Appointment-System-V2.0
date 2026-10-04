"use client";
import { useEffect, useMemo, useSyncExternalStore } from "react";
import { isBackendConfigured } from "../../lib/api-client";
import { useOperationScope, useSessionRevision } from "../setup-imports/use-operation-scope";
import { DraftSession, type DraftFields, type DraftForm } from "./draft-session";
import { draftTransport } from "./drafts-api";

export function useFormDraft(form: DraftForm, context: string, accountScope: string, active: boolean, fields: DraftFields) {
    const revision = useSessionRevision();
    const scope = useOperationScope();
    const session = useMemo(() => new DraftSession(draftTransport(form, context, scope), `${accountScope}:${revision}:${active}`), [form, context, scope, accountScope, revision, active]);
    const state = useSyncExternalStore(session.subscribe, session.getSnapshot, session.getSnapshot);
    useEffect(() => {
        if (!active || !isBackendConfigured) return;
        const invalidate = () => session.invalidate();
        window.addEventListener("brainserve:auth-session-changed", invalidate); window.addEventListener("brainserve:auth-session-expired", invalidate);
        void session.load();
        return () => { session.dispose(); window.removeEventListener("brainserve:auth-session-changed", invalidate); window.removeEventListener("brainserve:auth-session-expired", invalidate); };
    }, [session, active]);
    const key = JSON.stringify(fields);
    useEffect(() => { if (active) session.setFields(JSON.parse(key) as DraftFields); }, [session, active, key]);
    useEffect(() => {
        if (!active || !isBackendConfigured || !["ready", "unsaved"].includes(state.phase)) return;
        const timer = window.setTimeout(() => void session.save(), 750); return () => window.clearTimeout(timer);
    }, [session, state.phase, active, key]);
    return { state, session, enabled: active && isBackendConfigured };
}

export const draftBlocksSubmit = (phase: string) => ["loading", "pending", "conflict", "denied", "unknown", "submitting", "submitted", "offline"].includes(phase);
export const draftLocksFields = (phase: string) => ["denied", "unknown", "submitting", "submitted"].includes(phase);
