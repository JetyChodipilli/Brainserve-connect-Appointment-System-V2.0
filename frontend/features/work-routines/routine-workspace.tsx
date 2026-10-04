'use client';
import { useEffect, useRef, useState } from 'react';
import type { Role } from '../../types/workspace';
import { WorkDialog } from '../workboard/components/work-dialog';
import { isBackendConfigured } from '../../lib/api-client';
import { officeTimestamp } from './routine-model';
import { useRoutineWorkspace } from './use-routine-workspace';
import { RoutinePagination } from './components/routine-pagination';
import { RoutineTemplateForm } from './components/template-form';
import { RoutineScheduleForm } from './components/schedule-form';
import { RoutineOccurrenceHistory } from './components/occurrence-history';
import styles from './routines.module.css';

export function RoutineWorkspace({ role, identityKey }: { role: Role; identityKey: string }) {
    const { state, session } = useRoutineWorkspace(identityKey, role);
    const [discard, setDiscard] = useState<{ identity: string; kind: 'template' | 'schedule' } | null>(null);
    const returnFocus = useRef<HTMLElement | null>(null);
    useEffect(() => { returnFocus.current = null; }, [session.identityKey]);
    useEffect(() => {
        if (state.templateDraft || state.scheduleDraft || state.history || state.busy.includes('mutation')) return;
        if (returnFocus.current?.isConnected) returnFocus.current.focus({ preventScroll: true });
        returnFocus.current = null;
    }, [state.templateDraft, state.scheduleDraft, state.history, state.busy]);
    const rememberFocus = () => { returnFocus.current = document.activeElement as HTMLElement | null; };
    if (!['HR Admin', 'Team Lead'].includes(role)) return null;
    const mutationBusy = state.busy.includes('mutation'), loading = state.busy.includes('context');
    const closeTemplate = () => { if (mutationBusy || state.templateDraft?.unknown) return; setDiscard({ identity: session.identityKey, kind: 'template' }); };
    const closeSchedule = () => { if (mutationBusy || state.scheduleDraft?.unknown) return; setDiscard({ identity: session.identityKey, kind: 'schedule' }); };
    const discardVisible = discard?.identity === session.identityKey && (discard.kind === 'template' ? state.templateDraft : state.scheduleDraft);
    return <section className={styles.workspace} aria-label="Department work routines">
        <div className={styles.heading}><div><span className={styles.eyebrow}>RECURRING WORK</span><h2>Department routines</h2><p>Reusable requirements, office-calendar schedules, and retained exceptions.</p></div><button type="button" className="button button-secondary" aria-expanded={state.open} aria-controls="routine-workspace-content" disabled={!isBackendConfigured || mutationBusy || Boolean(state.templateDraft || state.scheduleDraft || state.history)} onClick={() => state.open ? session.close() : void session.open()}>{state.open ? 'Close work routines' : 'Work routines'}</button></div>
        {!isBackendConfigured && <p>Connect the secure backend to manage department routines.</p>}
        {state.errors.workspace && <div className={styles.error} role="alert">{state.errors.workspace}{state.stateConflict && <p>The schedule changed. Reload schedules, review its current state, then explicitly apply the change.</p>}</div>}
        {state.open && <div id="routine-workspace-content" className={styles.content}>
            {loading && <p role="status">Loading current department and eligible assignees…</p>}
            {!state.context && !loading && <button type="button" className="button button-secondary" onClick={() => void session.open()}>Retry routine workspace</button>}
            {state.notice && <p role="status" className={styles.notice}>{state.notice}</p>}
            {state.context && <><div className={styles.callout}><strong>{state.context.departmentName} · {state.context.officeZone}</strong><p>Current office date: {state.context.officeDate}. {role === 'HR Admin' ? 'Assign an active Employee or your current department Team Lead.' : 'Assign active Employees in your department.'} Eligibility is checked for every occurrence.</p></div>
                <section className={styles.section} aria-label="Routine templates" aria-busy={state.busy.includes('templates')}><div className={styles.heading}><div><h3>Routine templates</h3><p>Edits affect future first attempts. Blocked retries keep their captured version.</p></div><div className={styles.actions}><button type="button" className="button button-secondary" disabled={mutationBusy || state.busy.includes('templates')} onClick={() => void session.loadTemplates()}>Reload routine templates</button><button type="button" className="button button-primary" disabled={mutationBusy} onClick={() => { rememberFocus(); session.openTemplate(); }}>Create routine template</button></div></div>
                    {state.busy.includes('templates') && <p role="status">Loading routine templates…</p>}
                    <div className={styles.list}>{state.templates?.items.map(template => <article className={styles.card} key={template.id} aria-label={`Template ${template.title}`}><div className={styles.heading}><h4>{template.title}</h4><span className={styles.badge}>Version {template.version}</span></div><p>{template.instructions}</p><dl className={styles.facts}><div><dt>Assignee role</dt><dd>{template.assigneeRule === 'TEAM_LEAD' ? 'Team Lead' : 'Employee'}</dd></div><div><dt>Due offset</dt><dd>{template.dueOffsetDays} calendar days</dd></div><div><dt>Checklist</dt><dd>{template.checklist.length} requirements</dd></div></dl><div className={styles.actions}><button type="button" className="button button-secondary" disabled={mutationBusy} onClick={() => { rememberFocus(); session.openTemplate(template); }}>Edit template {template.title}</button><button type="button" className="button button-primary" disabled={mutationBusy} onClick={() => { rememberFocus(); session.openSchedule(template); }}>Schedule template {template.title}</button></div></article>)}</div>
                    {!state.busy.includes('templates') && state.templates?.items.length === 0 && <p className={styles.empty}>No routine templates yet. Create reusable instructions and a checklist to begin.</p>}
                    <RoutinePagination label="Templates" data={state.templates} page={state.templatePage} busy={mutationBusy || state.busy.includes('templates')} onPage={page => void session.loadTemplates(page)} />
                </section>
                <section className={styles.section} aria-label="Routine schedules" aria-busy={state.busy.includes('schedules')}><div className={styles.heading}><div><h3>Routine schedules</h3><p>Pausing preserves existing worksheets and evidence. Resume skips elapsed paused dates.</p></div><div className={styles.actions}><button type="button" className="button button-secondary" disabled={mutationBusy || state.busy.includes('schedules')} onClick={() => void session.loadSchedules()}>Reload routine schedules</button><button type="button" className="button button-primary" disabled={mutationBusy || !state.templates?.totalElements} onClick={() => { rememberFocus(); session.openSchedule(); }}>Create routine schedule</button></div></div>
                    {state.busy.includes('schedules') && <p role="status">Loading routine schedules…</p>}{mutationBusy && <p role="status">Saving routine change…</p>}
                    <div className={styles.list}>{state.schedules?.items.map(schedule => <article className={styles.card} key={schedule.id} aria-label={`Schedule ${schedule.templateTitle} for ${schedule.assigneeName}`}><div className={styles.heading}><h4>{schedule.templateTitle}</h4><span className={styles.badge}>{schedule.paused ? 'Paused' : 'Active'} · v{schedule.version}</span></div><p>{schedule.assigneeName} · Every {schedule.interval} {schedule.frequency === 'DAILY' ? 'day(s)' : schedule.frequency === 'WEEKLY' ? 'week(s)' : 'month(s)'} at {schedule.localTime} · {schedule.officeZone}</p><dl className={styles.facts}><div><dt>Date range</dt><dd>{schedule.startDate} – {schedule.endDate ?? 'No end date'}</dd></div><div><dt>Next occurrence</dt><dd>{officeTimestamp(schedule.nextOccurrenceAt, schedule.officeZone)}</dd></div><div><dt>Exceptions</dt><dd>{schedule.exceptionsCount} blocked</dd></div></dl>
                        <details><summary>Schedule calendar policy</summary><p>Weekend: {schedule.weekendPolicy === 'SKIP' ? 'skip Saturday and Sunday' : 'include'}. Holiday: {schedule.holidayPolicy === 'SKIP' ? 'skip listed dates' : 'include listed dates'}.</p>{schedule.frequency === 'WEEKLY' && <p>ISO weekdays: {schedule.weekdays.join(', ')} (Monday = 1).</p>}{schedule.frequency === 'MONTHLY' && <p>Day {schedule.monthDay}; short months clamp to their final day.</p>}<p>Holiday dates: {schedule.holidays.length ? schedule.holidays.join(', ') : 'None listed'}.</p></details>
                        <div className={styles.actions}><button type="button" className="button button-secondary" disabled={mutationBusy || state.stateConflict === schedule.id} onClick={() => void session.toggleSchedule(schedule)}>{schedule.paused ? 'Resume' : 'Pause'} schedule {schedule.templateTitle}</button><button type="button" className="button button-primary" disabled={mutationBusy} onClick={() => { rememberFocus(); void session.openHistory(schedule); }}>View history & exceptions for {schedule.templateTitle}</button></div></article>)}</div>
                    {!state.busy.includes('schedules') && state.schedules?.items.length === 0 && <p className={styles.empty}>No routine schedules yet. Choose a template, assignee, and office-calendar dates.</p>}
                    <RoutinePagination label="Schedules" data={state.schedules} page={state.schedulePage} busy={mutationBusy || state.busy.includes('schedules')} onPage={page => void session.loadSchedules(page)} />
                </section>
            </>}
        </div>}
        {state.templateDraft && <RoutineTemplateForm state={state} session={session} onClose={closeTemplate} />}
        {state.scheduleDraft && <RoutineScheduleForm state={state} session={session} onClose={closeSchedule} />}
        {state.history && <RoutineOccurrenceHistory state={state} session={session} />}
        {discardVisible && <WorkDialog className={`work-create-dialog ${styles.dialog}`} titleId="routine-discard-title" onClose={() => setDiscard(null)}><div className={styles.form}><h2 id="routine-discard-title">Keep unsaved routine changes?</h2><p>This form has not been saved.</p><div className={styles.actions}><button type="button" data-initial-focus className="button button-primary" onClick={() => setDiscard(null)}>Keep editing routine</button><button type="button" className="button button-secondary" onClick={() => { if (discard?.kind === 'template') session.closeTemplate(); else session.closeSchedule(); setDiscard(null); }}>Discard routine form</button></div></div></WorkDialog>}
    </section>;
}
