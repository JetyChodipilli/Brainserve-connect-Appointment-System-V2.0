'use client';
import { useCallback, useEffect, useRef, useState } from 'react';
import { isBackendConfigured } from '../../../lib/api-client';
import { activityApi } from '../api/activity-api';
import { orderedActivity, validateActivityPage } from '../activity-model';
import type { ActivityEvent } from '../types';

export function useActivityTimeline(kind: 'task' | 'appointment', id: string) {
    const [events, setEvents] = useState<ActivityEvent[]>([]), [error, setError] = useState('');
    const [busy, setBusy] = useState(false), [hasMore, setHasMore] = useState(false);
    const page = useRef(-1), serial = useRef(0), alive = useRef(false), blocked = useRef(false), abort = useRef<AbortController | null>(null);
    const load = useCallback(async (reset = false) => {
        if (!alive.current || blocked.current) return;
        const token = ++serial.current; abort.current?.abort(); const controller = new AbortController(); abort.current = controller;
        const requestedPage = reset ? 0 : page.current + 1;
        setBusy(true); setError('');
        try {
            if (!isBackendConfigured) { setEvents([]); setHasMore(false); setError('Activity is available in the connected workspace.'); return; }
            const value = validateActivityPage(await activityApi.timeline(kind, id, requestedPage, controller.signal), requestedPage);
            if (!alive.current || token !== serial.current) return;
            setEvents(previous => orderedActivity(reset ? [] : previous, value.events)); page.current = requestedPage; setHasMore(value.hasMore);
        } catch (cause) {
            if (!alive.current || token !== serial.current) return;
            setEvents([]); setHasMore(false); page.current = -1; setError(cause instanceof Error ? cause.message : 'Activity is unavailable.');
        } finally { if (alive.current && token === serial.current) setBusy(false); }
    }, [id, kind]);
    const cancelRequests = useCallback(() => { serial.current++; abort.current?.abort(); }, []);
    useEffect(() => {
        alive.current = true; blocked.current = false; page.current = -1;
        const timer = setTimeout(() => { setEvents([]); void load(true); }, 0);
        const changed = () => { cancelRequests(); blocked.current = true; setEvents([]); setHasMore(false); setBusy(false); setError('Session changed. Reopen this record in the current workspace.'); };
        window.addEventListener('brainserve:auth-session-changed', changed);
        window.addEventListener('brainserve:auth-session-expired', changed);
        return () => { clearTimeout(timer); alive.current = false; cancelRequests(); window.removeEventListener('brainserve:auth-session-changed', changed); window.removeEventListener('brainserve:auth-session-expired', changed); };
    }, [load, cancelRequests]);
    return { events, error, busy, hasMore, reload: () => load(true), more: () => load(false) };
}
