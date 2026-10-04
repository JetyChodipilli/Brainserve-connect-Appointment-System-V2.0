import type { RoutineContext, RoutineTemplate, ScheduleDefinition, TemplateFields } from './types';

export const ROUTINE_PAGE_SIZE = 20;
export const WEEKDAYS = ['Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday', 'Sunday'];
export function emptyTemplate(): TemplateFields { return { title: '', instructions: '', checklist: [], assigneeRule: 'EMPLOYEE', dueOffsetDays: 0 }; }
export function templateFields(template: RoutineTemplate): TemplateFields {
    return { title: template.title, instructions: template.instructions, checklist: template.checklist.map(item => ({ ...item })), assigneeRule: template.assigneeRule, dueOffsetDays: template.dueOffsetDays };
}
export function emptySchedule(officeDate: string, templateId = ''): ScheduleDefinition {
    return { templateId, employeeId: '', frequency: 'DAILY', interval: 1, startDate: officeDate, endDate: null, localTime: '09:00', weekdays: [], monthDay: null, weekendPolicy: 'SKIP', holidayPolicy: 'SKIP', holidays: [] };
}
export function canonicalSchedule(value: ScheduleDefinition): ScheduleDefinition {
    return { ...value, endDate: value.endDate || null, weekdays: value.frequency === 'WEEKLY' ? [...new Set(value.weekdays)].sort((a, b) => a - b) : [], monthDay: value.frequency === 'MONTHLY' ? value.monthDay : null, holidays: [...new Set(value.holidays)].sort() };
}
export function previewKey(value: ScheduleDefinition, templateVersion: number | undefined) { return JSON.stringify([canonicalSchedule(value), templateVersion]); }
const integer = (value: number, min: number, max: number) => Number.isInteger(value) && value >= min && value <= max;
export function validIsoDate(value: string) {
    if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) return false;
    const date = new Date(`${value}T00:00:00Z`);
    return Number.isFinite(date.getTime()) && date.toISOString().slice(0, 10) === value;
}
export function validateTemplate(value: TemplateFields, role: string): string[] {
    const errors: string[] = [];
    if (value.title.trim().length < 3 || value.title.length > 160) errors.push('Template title must contain 3–160 characters.');
    if (value.instructions.trim().length < 5 || value.instructions.length > 1000) errors.push('Instructions must contain 5–1000 characters.');
    if (!integer(value.dueOffsetDays, 0, 365)) errors.push('Due offset must be a whole number from 0 to 365 calendar days.');
    if (value.checklist.length > 50 || value.checklist.some(item => !item.title.trim() || item.title.length > 300 || typeof item.required !== 'boolean')) errors.push('Use up to 50 checklist requirements, each with 1–300 characters.');
    if (!['EMPLOYEE', 'TEAM_LEAD'].includes(value.assigneeRule) || role === 'Team Lead' && value.assigneeRule !== 'EMPLOYEE') errors.push('Choose an assignee role allowed in your current workspace.');
    return errors;
}
export function validateSchedule(value: ScheduleDefinition, context: RoutineContext | null, template: RoutineTemplate | undefined): string[] {
    const errors: string[] = [];
    if (!context || !template) errors.push('Choose a current department template.');
    if (!context?.eligibleAssignees.some(person => person.employeeId === value.employeeId && person.role === template?.assigneeRule)) errors.push('Choose an eligible assignee matching the template role.');
    if (!['DAILY', 'WEEKLY', 'MONTHLY'].includes(value.frequency)) errors.push('Choose daily, weekly or monthly recurrence.');
    if (!integer(value.interval, 1, 12)) errors.push('Repeat interval must be a whole number from 1 to 12.');
    if (!validIsoDate(value.startDate) || context && value.startDate < context.officeDate) errors.push('Start date must be today or later in the office calendar.');
    if (value.endDate && (!validIsoDate(value.endDate) || value.endDate < value.startDate)) errors.push('End date must be on or after the start date.');
    if (validIsoDate(value.startDate) && value.endDate) {
        const year = Number(value.startDate.slice(0, 4)) + 5;
        const monthDay = value.startDate.slice(5) === '02-29' ? '02-28' : value.startDate.slice(5);
        if (value.endDate > `${year}-${monthDay}`) errors.push('End date must be within five years of the start date.');
    }
    if (!/^([01]\d|2[0-3]):[0-5]\d$/.test(value.localTime)) errors.push('Choose a valid office time.');
    if (value.frequency === 'WEEKLY' && (!value.weekdays.length || value.weekdays.some(day => !integer(day, 1, 7)))) errors.push('Choose at least one weekday.');
    if (value.frequency === 'MONTHLY' && (value.monthDay === null || !integer(value.monthDay, 1, 31))) errors.push('Day of month must be a whole number from 1 to 31.');
    if (![value.weekendPolicy, value.holidayPolicy].every(policy => ['INCLUDE', 'SKIP'].includes(policy))) errors.push('Choose weekend and holiday policies.');
    if (value.holidays.length > 366 || value.holidays.some(date => !validIsoDate(date))) errors.push('Use up to 366 valid holiday dates.');
    return errors;
}
export function officeTimestamp(value: string | null, zone: string) {
    if (!value) return 'None scheduled';
    try { return new Intl.DateTimeFormat('en-GB', { dateStyle: 'medium', timeStyle: 'short', timeZone: zone }).format(new Date(value)); }
    catch { return value; }
}
export function routineFailure(reason: unknown) {
    const error = reason as { status?: number; problem?: { errorCode?: string }; message?: string };
    return { denied: [401, 403, 404].includes(Number(error?.status)), conflict: Number(error?.status) === 409 || error?.problem?.errorCode === 'ROUTINE_VERSION_CONFLICT', ambiguous: !error?.status || Number(error.status) >= 500,
        message: error instanceof Error ? error.message : 'Work routines are temporarily unavailable. Try again.' };
}

/** A request channel prevents an older preview/list/mutation from publishing over its replacement. */
export function createRoutineOperationGuard() {
    let generation = 0;
    const active = new Map<string, AbortController>();
    return {
        begin(channel: string) {
            active.get(channel)?.abort(); const controller = new AbortController(); active.set(channel, controller); const observed = generation;
            return { signal: controller.signal, current: () => generation === observed && active.get(channel) === controller && !controller.signal.aborted,
                finish: () => { if (active.get(channel) === controller) active.delete(channel); } };
        },
        cancel(channel: string) { active.get(channel)?.abort(); active.delete(channel); },
        invalidate() { generation++; active.forEach(controller => controller.abort()); active.clear(); },
    };
}
