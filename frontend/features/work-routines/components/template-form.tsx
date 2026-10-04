'use client';
import { useEffect, useRef } from 'react';
import { WorkDialog } from '../../workboard/components/work-dialog';
import type { RoutineSession, RoutineState } from '../routine-session';
import styles from '../routines.module.css';
export function RoutineTemplateForm({ state, session, onClose }: { state: RoutineState; session: RoutineSession; onClose: () => void }) {
    const errorRef = useRef<HTMLDivElement>(null), error = state.errors.template;
    useEffect(() => { if (error) errorRef.current?.focus(); }, [error]);
    const draft = state.templateDraft; if (!draft) return null;
    const fields = draft.fields, busy = state.busy.includes('mutation'), locked = busy || draft.unknown || state.busy.includes('template-conflict');
    const change = (values: Partial<typeof fields>) => session.changeTemplate({ ...fields, ...values });
    return <WorkDialog className={`work-create-dialog ${styles.dialog}`} titleId="routine-template-title" onClose={onClose}>
        <form className={styles.form} onSubmit={event => { event.preventDefault(); void session.saveTemplate(); }}>
            <header className={styles.heading}><div><span className={styles.eyebrow}>DEPARTMENT TEMPLATE</span><h2 id="routine-template-title">{draft.id ? 'Edit routine template' : 'Create routine template'}</h2><p>Versioned instructions and requirements for future worksheets. Existing work keeps its recorded version.</p></div><button type="button" className="button button-secondary" onClick={onClose} disabled={locked}>Close template form</button></header>
            {error && <div role="alert" tabIndex={-1} ref={errorRef} className={styles.error}>{error}</div>}
            {draft.conflict && <div className={styles.callout}><p>This template changed. Your fields are kept. Load the current version, review it, then explicitly save again.</p><button type="button" className="button button-secondary" disabled={locked} onClick={() => void session.reloadTemplateConflict()}>Reload template version and retain fields</button></div>}
            {draft.current && <section className={styles.callout} aria-label="Current saved template comparison"><h3>Current saved version {draft.current.version}</h3><dl className={styles.facts}><div><dt>Saved title</dt><dd>{draft.current.title}</dd></div><div><dt>Saved assignee role</dt><dd>{draft.current.assigneeRule === 'TEAM_LEAD' ? 'Team Lead' : 'Employee'}</dd></div><div><dt>Saved due offset</dt><dd>{draft.current.dueOffsetDays} calendar days</dd></div></dl><p>{draft.current.instructions}</p><ul>{draft.current.checklist.map((item, i) => <li key={i}>{item.title} · {item.required ? 'Required' : 'Optional'}</li>)}</ul>{draft.current.checklist.length === 0 && <p>No saved checklist requirements.</p>}<p>Your local fields remain below. Saving replaces the current requirements with those fields.</p></section>}
            {draft.unknown && <p className={styles.callout}>The creation response was not confirmed. Retry this same request to check its receipt before changing or closing the form.</p>}
            {busy && <p role="status">Saving routine template…</p>}{state.busy.includes('template-conflict') && <p role="status">Finding the current template version…</p>}
            <fieldset disabled={locked} className={styles.fieldset}>
                <div className={styles.grid}><label className={styles.full}>Template title<input aria-label="Template title" data-initial-focus required minLength={3} maxLength={160} value={fields.title} onChange={event => change({ title: event.target.value })} /></label>
                    <label className={styles.full}>Routine instructions<textarea aria-label="Routine instructions" required minLength={5} maxLength={1000} rows={4} value={fields.instructions} onChange={event => change({ instructions: event.target.value })} /></label>
                    <label>Assignee role<select aria-label="Assignee role" value={fields.assigneeRule} onChange={event => change({ assigneeRule: event.target.value as typeof fields.assigneeRule })}><option value="EMPLOYEE">Employee</option>{session.role === 'HR Admin' && <option value="TEAM_LEAD">Team Lead</option>}</select></label>
                    <label>Due offset (calendar days)<input aria-label="Due offset (calendar days)" required type="number" min={0} max={365} step={1} value={Number.isNaN(fields.dueOffsetDays) ? '' : fields.dueOffsetDays} onChange={event => change({ dueOffsetDays: event.target.value === '' ? NaN : Number(event.target.value) })} /><small>0 means due on the occurrence date.</small></label></div>
                <section className={styles.section}><h3>Checklist requirements · {fields.checklist.length}/50</h3><p>Required items must be completed before delivery submission.</p>
                    {fields.checklist.length === 0 && <p>No checklist requirements added.</p>}
                    {fields.checklist.map((item, index) => <div className={styles.checklist} key={index}><label>Requirement {index + 1}<input aria-label={`Requirement ${index + 1}`} required maxLength={300} value={item.title} onChange={event => change({ checklist: fields.checklist.map((value, i) => i === index ? { ...value, title: event.target.value } : value) })} /></label><label className={styles.checkbox}><input type="checkbox" checked={item.required} onChange={event => change({ checklist: fields.checklist.map((value, i) => i === index ? { ...value, required: event.target.checked } : value) })} />Required {index + 1}</label><button type="button" className="button button-secondary" onClick={() => change({ checklist: fields.checklist.filter((_, i) => i !== index) })}>Remove requirement {index + 1}</button></div>)}
                    <button type="button" className="button button-secondary" disabled={fields.checklist.length >= 50} onClick={() => change({ checklist: [...fields.checklist, { title: '', required: true }] })}>Add routine requirement</button></section>
            </fieldset>
            <footer className={styles.actions}><span>{draft.id ? `Observed version ${draft.expectedVersion}` : 'Creates the first template version'}</span><button type="submit" className="button button-primary" disabled={busy || draft.conflict || state.busy.includes('template-conflict')}>{busy ? 'Saving template…' : draft.unknown ? 'Retry same template request' : 'Save routine template'}</button></footer>
        </form>
    </WorkDialog>;
}
