import type { AnalyticsFilters, HandoverView, WorkMetric } from './types';
export const WORK_RECORD_PAGE_SIZE = 20;
export function validOfficeDate(value: string) { return /^\d{4}-\d{2}-\d{2}$/.test(value) && Number.isFinite(Date.parse(value)) && new Date(value).toISOString().slice(0, 10) === value; }
export function validatePeriod(filters: AnalyticsFilters): string | null {
    if (!validOfficeDate(filters.from) || !validOfficeDate(filters.to)) return 'Enter a valid start and end office date.';
    if (filters.to < filters.from) return 'End date must be on or after start date.';
    if ((Date.parse(filters.to) - Date.parse(filters.from)) / 86_400_000 + 1 > 366) return 'Choose an inclusive period of 366 days or fewer.';
    return null;
}
export function defaultPeriod(officeDate: string): AnalyticsFilters {
    const day = new Date(`${officeDate}T00:00:00Z`); day.setUTCDate(day.getUTCDate() - 29);
    return { from: day.toISOString().slice(0, 10), to: officeDate, departmentId: '' };
}
export function filterQuery(filters: AnalyticsFilters, version?: string) {
    const query = new URLSearchParams({ from: filters.from, to: filters.to });
    if (filters.departmentId) query.set('departmentId', filters.departmentId);
    if (version) query.set('metricVersion', version);
    return query;
}
export function planningFailure(cause: unknown) {
    const value = cause as { status?: number; message?: string }, status = value?.status;
    return { denied: [401, 403, 404].includes(status ?? 0), conflict: status === 409, uncertain: !status || status >= 500,
        message: value?.message || 'This request was not confirmed. Reload current data and review it before trying again.' };
}
export function operationGuard() {
    let generation = 0; const channels = new Map<string, AbortController>();
    return {
        begin(channel: string) { channels.get(channel)?.abort(); const controller = new AbortController(), token = generation; channels.set(channel, controller);
            return { signal: controller.signal, current: () => token === generation && !controller.signal.aborted && channels.get(channel) === controller,
                finish: () => { if (channels.get(channel) === controller) channels.delete(channel); } }; },
        cancel(channel: string) { channels.get(channel)?.abort(); channels.delete(channel); },
        invalidate() { generation++; channels.forEach(controller => controller.abort()); channels.clear(); },
    };
}
export function durationLabel(seconds: number | null) {
    if (seconds === null || !Number.isFinite(seconds)) return 'Unknown';
    if (seconds < 60) return `${Math.round(seconds)} sec`;
    if (seconds < 3600) return `${Math.round(seconds / 60)} min`;
    if (seconds < 86400) return `${(seconds / 3600).toFixed(1)} hr`;
    return `${(seconds / 86400).toFixed(1)} days`;
}
export function metricValue(card: WorkMetric) {
    if (card.value === null || !Number.isFinite(card.value)) return 'Unknown';
    if (card.unit === 'SECONDS') return durationLabel(card.value);
    if (card.unit === 'PERCENT') return `${card.value.toLocaleString('en-IN', { maximumFractionDigits: 1 })}%`;
    return `${card.value.toLocaleString('en-IN', { maximumFractionDigits: 1 })}${card.unit === 'MINUTES' ? ' min' : ''}`;
}
export function validateHandover(view: HandoverView, target: string, reason: string) {
    if (!view.canHandover) return view.unavailableReason || 'Handover is unavailable in the current worksheet state.';
    if (target === view.currentEmployeeId) return 'Choose a different current eligible assignee.';
    if (!view.eligibleAssignees.some(person => person.employeeId === target)) return 'Choose an eligible current department assignee.';
    if (reason.trim().length < 5 || reason.trim().length > 1000) return 'Enter a handover reason between 5 and 1,000 characters.';
    return null;
}
