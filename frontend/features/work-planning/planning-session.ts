import { defaultPeriod, operationGuard, planningFailure, validateHandover, validatePeriod } from './planning-model';
import type { AnalyticsContext, AnalyticsFilters, HandoverView, PlanningTransport, Workload, WorkMetric, WorkMetricPage, WorkSummary } from './types';
type SharedScope = { request(): { signal: AbortSignal; current(): boolean; finish(): void } };
type RecordsView = { metric: WorkMetric; page: number; data: WorkMetricPage | null };
export type AnalyticsState = { open: boolean; context: AnalyticsContext | null; filters: AnalyticsFilters; applied: AnalyticsFilters | null; workload: Workload | null; summary: WorkSummary | null; records: RecordsView | null; busy: string[]; error: string; recordsError: string };
const empty = (): AnalyticsState => ({ open: false, context: null, filters: { from: '', to: '', departmentId: '' }, applied: null, workload: null, summary: null, records: null, busy: [], error: '', recordsError: '' });
/** Data and export cohorts live only inside one account's current operation scope. */
export class WorkAnalyticsSession {
    state = empty(); private guard = operationGuard(); private listeners = new Set<() => void>();
    constructor(private api: PlanningTransport, readonly identityKey: string, readonly role: string, private scope?: SharedScope) {}
    subscribe = (listener: () => void) => { this.listeners.add(listener); return () => { this.listeners.delete(listener); }; };
    getSnapshot = () => this.state;
    private change(value: Partial<AnalyticsState>) { this.state = { ...this.state, ...value }; this.listeners.forEach(listener => listener()); }
    cancel() { this.guard.invalidate(); this.change(empty()); }
    invalidate() { this.guard.invalidate(); this.change({ ...empty(), error: 'Work planning access changed. Reopen it in your current workspace.' }); }
    private begin(channel: string) {
        const local = this.guard.begin(channel), shared = this.scope?.request(); this.change({ busy: [...this.state.busy.filter(item => item !== channel), channel] });
        return { signal: shared ? AbortSignal.any([local.signal, shared.signal]) : local.signal, current: () => local.current() && (!shared || shared.current()),
            finish: () => { const current = local.current() && (!shared || shared.current()); local.finish(); shared?.finish(); if (current) this.change({ busy: this.state.busy.filter(item => item !== channel) }); } };
    }
    private failure(reason: unknown, records = false) { const problem = planningFailure(reason); if (problem.denied) this.invalidate(); else this.change(records ? { recordsError: problem.message } : { error: problem.message }); }
    async open() {
        if (!this.identityKey || !['CEO', 'HR Admin', 'Team Lead', 'Manager', 'Employee'].includes(this.role)) return;
        this.change({ open: true, error: '' }); const request = this.begin('context');
        try { const context = await this.api.context(request.signal); if (!request.current()) return;
            if (context.metricVersion !== 'sprint9.v1' || !['OWN', 'DEPARTMENT', 'COMPANY'].includes(context.scope) || !Array.isArray(context.departmentOptions) || validatePeriod(defaultPeriod(context.officeDate))) throw new Error('Work planning context is invalid. Reload the current workspace.');
            this.change({ context, filters: defaultPeriod(context.officeDate) }); await this.load();
        } catch (reason) { if (request.current()) this.failure(reason); } finally { request.finish(); }
    }
    changeFilters(filters: AnalyticsFilters) {
        if (!this.state.context) return;
        ['summary', 'workload', 'records', 'export'].forEach(channel => this.guard.cancel(channel));
        this.change({ filters: { ...filters }, applied: null, summary: null, workload: null, records: null, busy: this.state.busy.filter(item => item === 'context'), error: '', recordsError: '' });
    }
    async load() {
        const context = this.state.context; if (!context || !this.state.open) return;
        const filters = { ...this.state.filters }, error = validatePeriod(filters);
        if (error || (filters.departmentId && !context.departmentOptions.some(item => item.id === filters.departmentId))) { this.change({ error: error || 'Choose an authorized department.' }); return; }
        this.guard.cancel('records'); this.guard.cancel('export'); this.change({ summary: null, workload: null, records: null, applied: filters, error: '', recordsError: '', busy: this.state.busy.filter(item => !['records', 'export'].includes(item)) });
        await Promise.all([this.loadSummary(context, filters), ...(context.canReadWorkload ? [this.loadWorkload(context, filters.departmentId)] : [])]);
    }
    private validScope(context: AnalyticsContext, scope: string, departmentId: string | null, selected: string) {
        return scope === context.scope && (selected ? departmentId === selected : departmentId === null || context.departmentOptions.some(item => item.id === departmentId));
    }
    private async loadSummary(context: AnalyticsContext, filters: AnalyticsFilters) {
        const request = this.begin('summary');
        try { const summary = await this.api.summary(filters, request.signal); if (!request.current()) return;
            if (summary.metricVersion !== context.metricVersion || summary.from !== filters.from || summary.to !== filters.to || !this.validScope(context, summary.scope, summary.departmentId, filters.departmentId)) throw new Error('The returned metric period or scope changed. Apply the period again.');
            this.change({ summary });
        } catch (reason) { if (request.current()) this.failure(reason); } finally { request.finish(); }
    }
    private async loadWorkload(context: AnalyticsContext, selected: string) {
        const request = this.begin('workload');
        try { const workload = await this.api.workload(selected, request.signal); if (!request.current()) return;
            if (workload.metricVersion !== context.metricVersion || !this.validScope(context, workload.scope, workload.departmentId, selected)) throw new Error('The returned workload scope changed. Reload current workload.');
            this.change({ workload });
        } catch (reason) { if (request.current()) this.failure(reason); } finally { request.finish(); }
    }
    openRecords(metric: WorkMetric) { if (!this.state.summary || !this.state.applied) return; this.change({ records: { metric, page: 0, data: null }, recordsError: '' }); void this.loadRecords(0); }
    closeRecords() { this.guard.cancel('records'); this.guard.cancel('export'); this.change({ records: null, recordsError: '', busy: this.state.busy.filter(item => !['records', 'export'].includes(item)) }); }
    async loadRecords(page = this.state.records?.page ?? 0) {
        const records = this.state.records, summary = this.state.summary, filters = this.state.applied;
        if (!records || !summary || !filters || !Number.isInteger(page) || page < 0) return;
        const request = this.begin('records'); this.change({ records: { ...records, page, data: null }, recordsError: '' });
        try { const data = await this.api.records(records.metric.id, filters, summary.metricVersion, page, request.signal); if (!request.current()) return;
            if (data.metricVersion !== summary.metricVersion || data.number !== page) throw new Error('The metric record version or page changed. Reload the metric summary.');
            if (page > 0 && page >= data.totalPages) { request.finish(); await this.loadRecords(Math.max(0, data.totalPages - 1)); return; }
            this.change({ records: { metric: records.metric, page, data } });
        } catch (reason) { if (request.current()) this.failure(reason, true); } finally { request.finish(); }
    }
    async exportRecords(): Promise<{ blob: Blob; filename: string } | null> {
        const records = this.state.records, summary = this.state.summary, filters = this.state.applied;
        if (!records?.data || !summary || !filters || this.state.busy.includes('export')) return null;
        const request = this.begin('export'); this.change({ recordsError: '' });
        try { const blob = await this.api.export(records.metric.id, { ...filters }, summary.metricVersion, request.signal); if (!request.current()) return null;
            return { blob, filename: `${records.metric.id}-${filters.from}-${filters.to}-${summary.metricVersion}.csv` };
        } catch (reason) { if (request.current()) this.failure(reason, true); return null; } finally { request.finish(); }
    }
}

export type HandoverState = { view: HandoverView | null; draft: { targetEmployeeId: string; reason: string } | null; conflict: boolean; uncertain: boolean; busy: boolean; error: string; notice: string; reviewRequired: boolean };
const emptyHandover = (): HandoverState => ({ view: null, draft: null, conflict: false, uncertain: false, busy: false, error: '', notice: '', reviewRequired: false });
export class HandoverSession {
    state = emptyHandover(); private guard = operationGuard(); private listeners = new Set<() => void>();
    constructor(private api: PlanningTransport, readonly taskId: string, readonly identityKey: string, private scope?: SharedScope) {}
    subscribe = (listener: () => void) => { this.listeners.add(listener); return () => { this.listeners.delete(listener); }; };
    getSnapshot = () => this.state;
    private change(value: Partial<HandoverState>) { this.state = { ...this.state, ...value }; this.listeners.forEach(listener => listener()); }
    cancel() { this.guard.invalidate(); this.change(emptyHandover()); }
    invalidate() { this.guard.invalidate(); this.change({ ...emptyHandover(), error: 'Worksheet handover access changed. Reopen this worksheet in your current workspace.' }); }
    private begin() { const local = this.guard.begin('request'), shared = this.scope?.request(); this.change({ busy: true });
        return { signal: shared ? AbortSignal.any([local.signal, shared.signal]) : local.signal, current: () => local.current() && (!shared || shared.current()), finish: () => { const current = local.current() && (!shared || shared.current()); local.finish(); shared?.finish(); if (current) this.change({ busy: false }); } }; }
    async load() {
        if (this.state.busy) return;
        const reviewRequired = Boolean(this.state.draft && (this.state.conflict || this.state.uncertain)); const request = this.begin(); this.change({ error: '', notice: '' });
        try { const view = await this.api.handover(this.taskId, request.signal); if (!request.current()) return;
            if (view.taskId !== this.taskId || !Number.isInteger(view.taskVersion)) throw new Error('The current worksheet assignment could not be verified. Reload it.');
            this.change({ view, conflict: false, uncertain: false, reviewRequired, notice: reviewRequired ? 'Current assignment and history reloaded. Your fields are retained. Review the current assignee and version before explicitly applying another handover.' : '' });
        } catch (reason) { if (request.current()) { const problem = planningFailure(reason); if (problem.denied) this.invalidate(); else this.change({ error: problem.message }); } } finally { request.finish(); }
    }
    openDraft() { if (this.state.busy || !this.state.view?.canHandover) return; this.change({ draft: { targetEmployeeId: '', reason: '' }, conflict: false, uncertain: false, reviewRequired: false, error: '', notice: '' }); }
    changeDraft(value: { targetEmployeeId: string; reason: string }) { if (!this.state.draft || this.state.busy || this.state.uncertain) return; this.change({ draft: value }); }
    closeDraft() { if (this.state.busy || this.state.uncertain) return; this.change({ draft: null, conflict: false, reviewRequired: false, error: '' }); }
    acknowledgeCurrent() { if (this.state.view && !this.state.conflict && !this.state.uncertain) this.change({ reviewRequired: false }); }
    async save(): Promise<boolean> {
        const { view, draft } = this.state; if (!view || !draft || this.state.busy || this.state.conflict || this.state.uncertain || this.state.reviewRequired) return false;
        const error = validateHandover(view, draft.targetEmployeeId, draft.reason); if (error) { this.change({ error }); return false; }
        const request = this.begin(); this.change({ error: '', notice: '' });
        try { const value = await this.api.changeHandover(this.taskId, { expectedVersion: view.taskVersion, targetEmployeeId: draft.targetEmployeeId, reason: draft.reason.trim() }, request.signal); if (!request.current()) return false;
            if (value.taskId !== this.taskId || value.currentEmployeeId !== draft.targetEmployeeId || value.taskVersion <= view.taskVersion) { this.change({ uncertain: true, error: 'The saved assignment was not confirmed. Reload the current assignment and review its history.' }); return false; }
            this.change({ view: value, draft: null, notice: `Handover confirmed to ${value.currentAssigneeName}. Previous submissions retain their original authorship; the new assignee must submit their own delivery.`, reviewRequired: false }); return true;
        } catch (reason) { if (request.current()) { const problem = planningFailure(reason); if (problem.denied) this.invalidate(); else this.change({ conflict: problem.conflict, uncertain: problem.uncertain, error: problem.message }); } return false; } finally { request.finish(); }
    }
}
