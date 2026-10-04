import { canonicalSchedule, createRoutineOperationGuard, emptySchedule, emptyTemplate, previewKey, ROUTINE_PAGE_SIZE, routineFailure, templateFields, validateSchedule, validateTemplate } from './routine-model';
import type { RoutineContext, RoutineOccurrence, RoutinePage, RoutinePreview, RoutineSchedule, RoutineTemplate, RoutineTransport, ScheduleDefinition, TemplateFields } from './types';

type TemplateDraft = { id: string | null; expectedVersion: number; fields: TemplateFields; requestId: string; conflict: boolean; unknown: boolean; current: RoutineTemplate | null };
type ScheduleDraft = { fields: ScheduleDefinition; template: RoutineTemplate | null; requestId: string; unknown: boolean };
type OperationScope = { request(): { signal: AbortSignal; current(): boolean; finish(): void } };
export type RoutineState = {
    open: boolean; denied: boolean; context: RoutineContext | null;
    templates: RoutinePage<RoutineTemplate> | null; templatePage: number;
    schedules: RoutinePage<RoutineSchedule> | null; schedulePage: number;
    templateDraft: TemplateDraft | null; scheduleDraft: ScheduleDraft | null;
    preview: { key: string; value: RoutinePreview } | null;
    history: { schedule: RoutineSchedule; page: number; data: RoutinePage<RoutineOccurrence> | null; conflict: boolean } | null;
    busy: string[]; errors: { workspace: string; template: string; schedule: string; history: string }; notice: string; stateConflict: string | null;
};
const initial = (): RoutineState => ({ open: false, denied: false, context: null, templates: null, templatePage: 0, schedules: null, schedulePage: 0, templateDraft: null, scheduleDraft: null, preview: null, history: null, busy: [], errors: { workspace: '', template: '', schedule: '', history: '' }, notice: '', stateConflict: null });

/** Memory-only workspace: identities own all drafts, request keys, receipts and retained errors. */
export class RoutineSession {
    state = initial();
    private listeners = new Set<() => void>();
    private guard = createRoutineOperationGuard();
    constructor(private api: RoutineTransport, readonly identityKey: string, readonly role: string, private scope?: OperationScope) {}
    subscribe = (listener: () => void) => { this.listeners.add(listener); return () => { this.listeners.delete(listener); }; };
    getSnapshot = () => this.state;
    private change(values: Partial<RoutineState>) { this.state = { ...this.state, ...values }; this.listeners.forEach(listener => listener()); }
    private error(area: keyof RoutineState['errors'], message: string) { this.change({ errors: { ...this.state.errors, [area]: message } }); }
    invalidate(message = 'Your account or access changed. Reopen routines in your current workspace.') {
        this.guard.invalidate(); this.change({ ...initial(), denied: true, errors: { ...initial().errors, workspace: message } });
    }
    cancel() { this.guard.invalidate(); this.change(initial()); }
    private begin(channel: string) {
        const local = this.guard.begin(channel), shared = this.scope?.request();
        this.change({ busy: [...this.state.busy.filter(value => value !== channel), channel] });
        return { signal: shared ? AbortSignal.any([local.signal, shared.signal]) : local.signal,
            current: () => local.current() && (!shared || shared.current()),
            finish: () => { const current = local.current() && (!shared || shared.current()); local.finish(); shared?.finish(); if (current) this.change({ busy: this.state.busy.filter(value => value !== channel) }); } };
    }
    private failure(reason: unknown, area: keyof RoutineState['errors']) {
        const failure = routineFailure(reason);
        if (failure.denied) this.invalidate('Work routines are unavailable in your current account or department.');
        else this.error(area, failure.message);
        return failure;
    }
    async open() {
        if (!this.identityKey || !['HR Admin', 'Team Lead'].includes(this.role)) return;
        this.change({ open: true, denied: false, errors: initial().errors, notice: '' });
        const request = this.begin('context');
        try {
            const context = await this.api.context(request.signal); if (!request.current()) return;
            this.change({ context });
            await Promise.all([this.loadTemplates(0), this.loadSchedules(0)]);
        } catch (reason) { if (request.current()) this.failure(reason, 'workspace'); }
        finally { request.finish(); }
    }
    close() { if (this.state.busy.includes('mutation')) return; this.cancel(); }
    async loadTemplates(page = this.state.templatePage) {
        if (!this.state.open || this.state.denied || !Number.isInteger(page) || page < 0) return;
        const request = this.begin('templates'); this.error('workspace', '');
        this.change({ templatePage: page, templates: null });
        try {
            const templates = await this.api.templates(page, ROUTINE_PAGE_SIZE, request.signal); if (!request.current()) return;
            if (page > 0 && page >= templates.totalPages) { request.finish(); await this.loadTemplates(Math.max(0, templates.totalPages - 1)); return; }
            const selected = this.state.scheduleDraft?.template;
            const fresh = selected && templates.items.find(item => item.id === selected.id);
            this.change({ templates, ...(fresh && fresh.version !== selected.version ? { scheduleDraft: { ...this.state.scheduleDraft!, template: fresh }, preview: null } : {}) });
        } catch (reason) { if (request.current()) this.failure(reason, 'workspace'); }
        finally { request.finish(); }
    }
    async loadSchedules(page = this.state.schedulePage) {
        if (!this.state.open || this.state.denied || !Number.isInteger(page) || page < 0) return;
        const request = this.begin('schedules'); this.error('workspace', ''); this.change({ schedulePage: page, schedules: null });
        try {
            const schedules = await this.api.schedules(page, ROUTINE_PAGE_SIZE, request.signal); if (!request.current()) return;
            if (page > 0 && page >= schedules.totalPages) { request.finish(); await this.loadSchedules(Math.max(0, schedules.totalPages - 1)); return; }
            const history = this.state.history;
            const latest = history && schedules.items.find(item => item.id === history.schedule.id);
            this.change({ schedules, stateConflict: null, ...(latest ? { history: { ...history, schedule: latest } } : {}) });
        } catch (reason) { if (request.current()) this.failure(reason, 'workspace'); }
        finally { request.finish(); }
    }
    openTemplate(template?: RoutineTemplate) {
        if (!this.state.context || this.state.denied || this.state.busy.includes('mutation')) return;
        this.change({ templateDraft: { id: template?.id ?? null, expectedVersion: template?.version ?? 0, fields: template ? templateFields(template) : emptyTemplate(), requestId: crypto.randomUUID(), conflict: false, unknown: false, current: null }, notice: '' }); this.error('template', '');
    }
    changeTemplate(fields: TemplateFields) {
        const draft = this.state.templateDraft; if (!draft || draft.unknown || this.state.busy.includes('mutation')) return;
        this.change({ templateDraft: { ...draft, fields, requestId: crypto.randomUUID() } }); this.error('template', '');
    }
    closeTemplate() { if (!this.state.busy.includes('mutation') && !this.state.templateDraft?.unknown) { this.guard.cancel('template-conflict'); this.change({ templateDraft: null }); this.error('template', ''); } }
    async saveTemplate() {
        const draft = this.state.templateDraft;
        if (!draft || draft.conflict || this.state.busy.includes('mutation') || this.state.denied) return;
        const errors = validateTemplate(draft.fields, this.role); if (errors.length) { this.error('template', errors.join(' ')); return; }
        const request = this.begin('mutation'); this.error('template', '');
        try {
            const template = draft.id ? await this.api.updateTemplate(draft.id, { ...draft.fields, expectedVersion: draft.expectedVersion }, request.signal)
                : await this.api.createTemplate({ ...draft.fields, requestId: draft.requestId }, request.signal);
            if (!request.current()) return;
            const selected = this.state.scheduleDraft?.template;
            this.change({ templateDraft: null, notice: `Template “${template.title}” saved as version ${template.version}. Existing worksheets keep their recorded requirements.`, ...(selected?.id === template.id ? { scheduleDraft: { ...this.state.scheduleDraft!, template }, preview: null } : {}) });
            await this.loadTemplates(this.state.templatePage);
        } catch (reason) {
            if (!request.current()) return;
            const failure = this.failure(reason, 'template');
            if (!failure.denied) this.change({ templateDraft: { ...draft, conflict: failure.conflict, unknown: !draft.id && failure.ambiguous } });
        } finally { request.finish(); }
    }
    async reloadTemplateConflict() {
        const draft = this.state.templateDraft; if (!draft?.id || this.state.busy.includes('mutation')) return;
        const request = this.begin('template-conflict'); this.error('template', '');
        try {
            const current = await this.api.template(draft.id, request.signal); if (!request.current()) return;
            this.change({ templateDraft: { ...draft, expectedVersion: current.version, conflict: false, current } });
            this.error('template', `Current version ${current.version} loaded. Your fields are retained. Compare the current requirements below, then explicitly save when ready.`);
        } catch (reason) { if (request.current()) this.failure(reason, 'template'); }
        finally { request.finish(); }
    }
    openSchedule(template?: RoutineTemplate) {
        if (!this.state.context || this.state.denied || this.state.busy.includes('mutation')) return;
        this.change({ scheduleDraft: { fields: emptySchedule(this.state.context.officeDate, template?.id), template: template ?? null, requestId: crypto.randomUUID(), unknown: false }, preview: null, notice: '' }); this.error('schedule', '');
    }
    changeSchedule(fields: ScheduleDefinition) {
        const draft = this.state.scheduleDraft; if (!draft || draft.unknown || this.state.busy.includes('mutation')) return;
        this.guard.cancel('preview');
        const template = fields.templateId === draft.template?.id ? draft.template : this.state.templates?.items.find(item => item.id === fields.templateId) ?? null;
        this.change({ scheduleDraft: { ...draft, fields, template, requestId: crypto.randomUUID() }, preview: null, busy: this.state.busy.filter(value => value !== 'preview') }); this.error('schedule', '');
    }
    closeSchedule() { if (!this.state.busy.includes('mutation') && !this.state.scheduleDraft?.unknown) { this.guard.cancel('preview'); this.change({ scheduleDraft: null, preview: null, busy: this.state.busy.filter(value => value !== 'preview') }); this.error('schedule', ''); } }
    async previewSchedule() {
        const draft = this.state.scheduleDraft; if (!draft || draft.unknown || this.state.busy.includes('mutation')) return;
        const errors = validateSchedule(draft.fields, this.state.context, draft.template ?? undefined); if (errors.length) { this.error('schedule', errors.join(' ')); return; }
        const request = this.begin('preview'), key = previewKey(draft.fields, draft.template?.version); this.change({ preview: null }); this.error('schedule', '');
        try {
            const value = await this.api.preview(canonicalSchedule(draft.fields), request.signal);
            if (request.current() && this.state.scheduleDraft && key === previewKey(this.state.scheduleDraft.fields, this.state.scheduleDraft.template?.version)) this.change({ preview: { key, value } });
        } catch (reason) { if (request.current()) this.failure(reason, 'schedule'); }
        finally { request.finish(); }
    }
    async saveSchedule() {
        const draft = this.state.scheduleDraft;
        if (!draft || this.state.denied || this.state.busy.includes('mutation')) return;
        if (!this.state.preview || this.state.preview.key !== previewKey(draft.fields, draft.template?.version)) { this.error('schedule', 'Preview the current schedule before creating it.'); return; }
        const errors = validateSchedule(draft.fields, this.state.context, draft.template ?? undefined); if (errors.length) { this.error('schedule', errors.join(' ')); return; }
        const request = this.begin('mutation'); this.error('schedule', '');
        try {
            const schedule = await this.api.createSchedule({ ...canonicalSchedule(draft.fields), requestId: draft.requestId }, request.signal); if (!request.current()) return;
            this.change({ scheduleDraft: null, preview: null, notice: `Schedule created for ${schedule.assigneeName}. New worksheets appear on accepted office dates.` }); await this.loadSchedules(0);
        } catch (reason) {
            if (!request.current()) return; const failure = this.failure(reason, 'schedule');
            if (!failure.denied) this.change({ scheduleDraft: { ...draft, unknown: failure.ambiguous }, ...(failure.conflict ? { preview: null } : {}) });
        } finally { request.finish(); }
    }
    async toggleSchedule(schedule: RoutineSchedule) {
        if (this.state.denied || this.state.busy.includes('mutation') || this.state.stateConflict === schedule.id) return;
        const request = this.begin('mutation'); this.error('workspace', '');
        try {
            const updated = await this.api.setState(schedule.id, schedule.version, !schedule.paused, request.signal); if (!request.current()) return;
            const schedules = this.state.schedules;
            this.change({ schedules: schedules ? { ...schedules, items: schedules.items.map(item => item.id === updated.id ? updated : item) } : null,
                history: this.state.history?.schedule.id === updated.id ? { ...this.state.history, schedule: updated } : this.state.history,
                notice: updated.paused ? 'Schedule paused. Existing worksheets and evidence stay available.' : 'Schedule resumed. Elapsed paused dates are skipped; future dates continue.' });
        } catch (reason) { if (request.current()) { const failure = this.failure(reason, 'workspace'); if (failure.conflict && !failure.denied) this.change({ stateConflict: schedule.id }); } }
        finally { request.finish(); }
    }
    async openHistory(schedule: RoutineSchedule) { this.change({ history: { schedule, page: 0, data: null, conflict: false }, notice: '' }); await this.loadHistory(0); }
    closeHistory() { if (this.state.busy.includes('mutation')) return; this.guard.cancel('history'); this.change({ history: null, busy: this.state.busy.filter(value => value !== 'history') }); this.error('history', ''); }
    async loadHistory(page = this.state.history?.page ?? 0) {
        const history = this.state.history; if (!history || this.state.denied || !Number.isInteger(page) || page < 0) return;
        const request = this.begin('history'); this.change({ history: { ...history, page, data: null } }); this.error('history', '');
        try {
            const data = await this.api.occurrences(history.schedule.id, page, ROUTINE_PAGE_SIZE, request.signal); if (!request.current()) return;
            if (page > 0 && page >= data.totalPages) { request.finish(); await this.loadHistory(Math.max(0, data.totalPages - 1)); return; }
            this.change({ history: { ...this.state.history!, data, conflict: false } });
        } catch (reason) { if (request.current()) this.failure(reason, 'history'); }
        finally { request.finish(); }
    }
    async retryOccurrence(occurrence: RoutineOccurrence) {
        const history = this.state.history;
        if (!history || history.schedule.paused || history.conflict || occurrence.status !== 'BLOCKED' || this.state.denied || this.state.busy.includes('mutation')) return;
        const request = this.begin('mutation'); this.error('history', '');
        try {
            const result = await this.api.retry(history.schedule.id, occurrence.occurrenceDate, occurrence.version, request.signal); if (!request.current()) return;
            const current = this.state.history;
            this.change({ history: current?.data ? { ...current, data: { ...current.data, items: current.data.items.map(item => item.occurrenceDate === result.occurrenceDate ? result : item) } } : current,
                notice: result.status === 'CREATED' ? 'Occurrence confirmed. Its worksheet and notification are recorded once.' : 'Occurrence remains blocked. Review the current exception before retrying.' });
            await this.loadSchedules(this.state.schedulePage);
        } catch (reason) { if (request.current()) { const failure = this.failure(reason, 'history'); if (failure.conflict && this.state.history) this.change({ history: { ...this.state.history, conflict: true } }); } }
        finally { request.finish(); }
    }
}
