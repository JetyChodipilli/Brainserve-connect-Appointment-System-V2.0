'use client';
import { useEffect, useLayoutEffect, useMemo, useSyncExternalStore } from 'react';
import { useOperationScope, useSessionRevision } from '../setup-imports/use-operation-scope';
import { planningApi } from './api/planning-api';
import { HandoverSession, WorkAnalyticsSession } from './planning-session';
export function useAnalyticsSession(identityKey: string, role: string) {
    const revision = useSessionRevision(), scope = useOperationScope();
    const session = useMemo(() => new WorkAnalyticsSession(planningApi, `${identityKey}:${revision}`, role, scope), [identityKey, revision, role, scope]);
    const state = useSyncExternalStore(session.subscribe, session.getSnapshot, session.getSnapshot);
    useScopedSession(session); return { state, session };
}
export function useHandoverSession(taskId: string) {
    const revision = useSessionRevision(), scope = useOperationScope();
    const session = useMemo(() => new HandoverSession(planningApi, taskId, `${taskId}:${revision}`, scope), [taskId, revision, scope]);
    const state = useSyncExternalStore(session.subscribe, session.getSnapshot, session.getSnapshot);
    useScopedSession(session); return { state, session };
}
function useScopedSession(session: WorkAnalyticsSession | HandoverSession) {
    useLayoutEffect(() => () => { session.cancel(); }, [session]);
    useEffect(() => { const changed = () => session.invalidate(); window.addEventListener('brainserve:auth-session-changed', changed); window.addEventListener('brainserve:auth-session-expired', changed);
        return () => { window.removeEventListener('brainserve:auth-session-changed', changed); window.removeEventListener('brainserve:auth-session-expired', changed); }; }, [session]);
}
