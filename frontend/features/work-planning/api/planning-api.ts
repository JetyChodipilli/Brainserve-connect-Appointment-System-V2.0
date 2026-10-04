import { apiDownload, apiRequest } from '../../../lib/api-client';
import { filterQuery, WORK_RECORD_PAGE_SIZE } from '../planning-model';
import type { PlanningTransport } from '../types';
const base = '/work-analytics';
const handoverPath = (id: string) => `/work-tasks/${encodeURIComponent(id)}/handover`;
const get = <T>(path: string, signal: AbortSignal) => apiRequest<T>(path, { signal, cache: 'no-store' });
export const planningApi: PlanningTransport = {
    context: signal => get(`${base}/context`, signal),
    workload: (departmentId, signal) => get(`${base}/workload${departmentId ? `?departmentId=${encodeURIComponent(departmentId)}` : ''}`, signal),
    summary: (filters, signal) => get(`${base}/summary?${filterQuery(filters)}`, signal),
    records: (metric, filters, version, page, signal) => { const query = filterQuery(filters, version); query.set('page', String(page)); query.set('size', String(WORK_RECORD_PAGE_SIZE)); return get(`${base}/${encodeURIComponent(metric)}/records?${query}`, signal); },
    export: (metric, filters, version, signal) => apiDownload(`${base}/${encodeURIComponent(metric)}/export.csv?${filterQuery(filters, version)}`, signal),
    handover: (id, signal) => get(handoverPath(id), signal),
    changeHandover: (id, body, signal) => apiRequest(handoverPath(id), { method: 'POST', signal, body: JSON.stringify(body) }, false),
};
