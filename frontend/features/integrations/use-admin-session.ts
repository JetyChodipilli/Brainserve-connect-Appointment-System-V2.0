'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import { useOperationScope } from '../setup-imports/use-operation-scope';

// Reuse the account-generation fence; the synchronous lock also closes rapid double-submit races.
export function useAdminSession(clear: () => void) {
    const scope = useOperationScope();
    const [busy, setBusy] = useState(false), [sessionEnded, setSessionEnded] = useState(false);
    const locked = useRef(false), ended = useRef(false), clearRef = useRef(clear);
    clearRef.current = clear;
    useEffect(() => {
        const stop = () => { ended.current = true; scope.invalidate(); locked.current = false; clearRef.current(); setBusy(false); setSessionEnded(true); };
        window.addEventListener('brainserve:auth-session-changed', stop);
        window.addEventListener('brainserve:auth-session-expired', stop);
        return () => { window.removeEventListener('brainserve:auth-session-changed', stop); window.removeEventListener('brainserve:auth-session-expired', stop); };
    }, [scope]);
    const begin = useCallback(() => {
        if (locked.current || ended.current) return null;
        locked.current = true; setBusy(true);
        const request = scope.request();
        return { ...request, finish: () => { request.finish(); if (request.current()) { locked.current = false; setBusy(false); } } };
    }, [scope]);
    return { begin, busy, sessionEnded };
}
