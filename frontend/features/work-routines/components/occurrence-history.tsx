import { WorkDialog } from '../../workboard/components/work-dialog';
import { officeTimestamp } from '../routine-model';
import type { RoutineSession, RoutineState } from '../routine-session';
import { RoutinePagination } from './routine-pagination';
import styles from '../routines.module.css';
export function RoutineOccurrenceHistory({ state, session }: { state: RoutineState; session: RoutineSession }) {
    const history = state.history; if (!history) return null;
    const busy = state.busy.includes('mutation'), loading = state.busy.includes('history');
    return <WorkDialog className={`work-create-dialog ${styles.dialog}`} titleId="routine-history-title" onClose={() => session.closeHistory()}><div className={styles.form}>
        <header className={styles.heading}><div><span className={styles.eyebrow}>OCCURRENCES & EXCEPTIONS</span><h2 id="routine-history-title">Routine history: {history.schedule.templateTitle}</h2><p>{history.schedule.assigneeName} · {history.schedule.officeZone}. Every occurrence retains its template version.</p></div><button type="button" className="button button-secondary" disabled={busy} onClick={() => session.closeHistory()}>Close routine history</button></header>
        {state.errors.history && <div className={styles.error} role="alert">{state.errors.history}</div>}
        {state.errors.workspace && <div className={styles.error} role="alert">{state.errors.workspace}{state.stateConflict === history.schedule.id && <p>This schedule changed. Reload routine history to review its current state before applying the change again.</p>}</div>}
        {history.conflict && <p className={styles.callout}>An occurrence changed. Reload history before explicitly retrying with the current version.</p>}
        {history.schedule.paused && <p className={styles.callout}>This schedule is paused. Resume it before retrying exceptions. Existing worksheets and evidence are preserved.</p>}
        <div className={styles.actions}><button type="button" className="button button-secondary" disabled={busy || loading} onClick={() => { void session.loadSchedules(); void session.loadHistory(); }}>Reload routine history</button>{history.schedule.paused && <button type="button" className="button button-primary" disabled={busy || state.stateConflict === history.schedule.id} onClick={() => void session.toggleSchedule(history.schedule)}>Resume schedule</button>}</div>
        {loading && <p role="status">Loading occurrence history…</p>}{busy && <p role="status">Checking the occurrence against current eligibility…</p>}
        {state.notice && <p role="status" className={styles.notice}>{state.notice}</p>}
        <section className={styles.list} aria-label="Routine occurrences" aria-busy={loading}>{history.data?.items.map(item => <article className={styles.card} key={item.occurrenceDate} aria-label={`Occurrence ${item.occurrenceDate}`}><div className={styles.heading}><h3>{item.occurrenceDate}</h3><span className={item.status === 'BLOCKED' ? styles.exceptionBadge : styles.badge}>{item.status === 'BLOCKED' ? 'Blocked exception' : 'Worksheet created'}</span></div>
            <p>{officeTimestamp(item.scheduledAt, history.schedule.officeZone)} · Template v{item.templateVersion} · {item.attempts} {item.attempts === 1 ? 'attempt' : 'attempts'} · Occurrence v{item.version}</p>
            {item.status === 'CREATED' ? <p>Worksheet ID: <span className={styles.identifier}>{item.taskId ?? 'Recorded receipt'}</span></p> : <><p><strong>{item.exceptionCode ?? 'Eligibility exception'}</strong>{item.message ? ` · ${item.message}` : ''}</p><p>Correct the creator or assignee’s current role, active login, or department eligibility as described above, then retry this retained occurrence.</p><button type="button" className="button button-primary" disabled={busy || loading || history.conflict || history.schedule.paused} onClick={() => void session.retryOccurrence(item)}>Retry occurrence {item.occurrenceDate}</button></>}
        </article>)}</section>
        {!loading && history.data?.items.length === 0 && <p className={styles.empty}>No occurrences recorded yet. Accepted dates and any blocked exceptions will appear here.</p>}
        <RoutinePagination label="Occurrences" data={history.data} page={history.page} busy={busy || loading} onPage={page => void session.loadHistory(page)} />
    </div></WorkDialog>;
}
