'use client';
import { useEffect, useLayoutEffect, useMemo, useSyncExternalStore } from 'react';
import { useOperationScope, useSessionRevision } from '../setup-imports/use-operation-scope';
import { routinesApi } from './api/routines-api';
import { RoutineSession } from './routine-session';

export function useRoutineWorkspace(identityKey: string, role: string) {
    const revision = useSessionRevision(), scope = useOperationScope();
    const session = useMemo(() => new RoutineSession(routinesApi, `${identityKey}:${revision}`, role, scope), [identityKey, revision, role, scope]);
    const state = useSyncExternalStore(session.subscribe, session.getSnapshot, session.getSnapshot);
    useLayoutEffect(() => () => { session.cancel(); }, [session]);
    useEffect(() => {
        const changed = () => session.invalidate();
        window.addEventListener('brainserve:auth-session-changed', changed); window.addEventListener('brainserve:auth-session-expired', changed);
        return () => { window.removeEventListener('brainserve:auth-session-changed', changed); window.removeEventListener('brainserve:auth-session-expired', changed); };
    }, [session]);
    return { state, session };
}
