import { apiRequest } from '../../../lib/api-client';
import type { RoutineTransport } from '../types';
const base = '/work-routines';
const idPath = (id: string) => `${base}/schedules/${encodeURIComponent(id)}`;
const get = <T>(path: string, signal: AbortSignal) => apiRequest<T>(path, { signal, cache: 'no-store' });
const write = <T>(path: string, method: string, body: unknown, signal: AbortSignal) => apiRequest<T>(path, { method, signal, body: JSON.stringify(body) }, false);
export const routinesApi: RoutineTransport = {
    context: signal => get(`${base}/context`, signal),
    templates: (page, size, signal) => get(`${base}/templates?page=${page}&size=${size}`, signal),
    template: (id, signal) => get(`${base}/templates/${encodeURIComponent(id)}`, signal),
    createTemplate: (body, signal) => write(`${base}/templates`, 'POST', body, signal),
    updateTemplate: (id, body, signal) => write(`${base}/templates/${encodeURIComponent(id)}`, 'PUT', body, signal),
    schedules: (page, size, signal) => get(`${base}/schedules?page=${page}&size=${size}`, signal),
    preview: (body, signal) => write(`${base}/preview`, 'POST', body, signal),
    createSchedule: (body, signal) => write(`${base}/schedules`, 'POST', body, signal),
    setState: (id, expectedVersion, paused, signal) => write(`${idPath(id)}/state`, 'POST', { expectedVersion, paused }, signal),
    occurrences: (id, page, size, signal) => get(`${idPath(id)}/occurrences?page=${page}&size=${size}`, signal),
    retry: (id, date, expectedVersion, signal) => write(`${idPath(id)}/occurrences/${encodeURIComponent(date)}/retry`, 'POST', { expectedVersion }, signal),
};
