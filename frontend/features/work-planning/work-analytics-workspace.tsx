'use client';
import { useEffect, useRef } from 'react';
import { BarChart3, RefreshCw } from 'lucide-react';
import { isBackendConfigured } from '../../lib/api-client';
import { useAnalyticsSession } from './use-planning-session';
import { WorkloadPanel } from './components/workload-panel';
import { AnalyticsSummary } from './components/analytics-summary';
import { MetricRecords } from './components/metric-records';
import styles from './planning.module.css';
export function WorkAnalyticsWorkspace({ role, identityKey }: { role: string; identityKey: string }) {
    const { state, session } = useAnalyticsSession(identityKey, role); const returnFocus = useRef<HTMLElement | null>(null);
    useEffect(() => { returnFocus.current = null; }, [session.identityKey]);
    useEffect(() => { if (state.records) return; if (returnFocus.current?.isConnected) returnFocus.current.focus({ preventScroll: true }); returnFocus.current = null; }, [state.records]);
    if (!['CEO', 'HR Admin', 'Team Lead', 'Manager', 'Employee'].includes(role)) return null;
    const loading = state.busy.includes('context') || state.busy.includes('summary') || state.busy.includes('workload');
    return <section className={styles.workspace} aria-label="Workload and work analytics"><header className={styles.heading}><div><span className={styles.eyebrow}>ASSIGNMENT & DELIVERY</span><h2>Work planning</h2><p>{['HR Admin', 'Team Lead'].includes(role) ? 'Current workload, controlled handovers, and delivery measures.' : 'Delivery cohorts, review stages, and original commitment measures.'}</p></div><button type="button" className="button button-secondary" aria-expanded={state.open} aria-controls="work-analytics-content" disabled={!isBackendConfigured || Boolean(state.records)} onClick={() => state.open ? session.cancel() : void session.open()}><BarChart3 size={17} />{state.open ? 'Close work planning' : 'Workload & analytics'}</button></header>
        {!isBackendConfigured && <p>Connect the secure backend to read current work planning data.</p>}{state.error && <div className={styles.error} role="alert">{state.error}</div>}
        {state.open && <div className={styles.content} id="work-analytics-content">{state.busy.includes('context') && <p role="status">Loading authorized work planning scope…</p>}{!state.context && !state.busy.includes('context') && <button type="button" className="button button-secondary" onClick={() => void session.open()}>Retry work planning</button>}
            {state.context && <><div className={styles.callout}><strong>{state.context.scope.toLowerCase()} scope · {state.context.officeZone}</strong><p>Current office date: {state.context.officeDate}. Available department filters come from your current authorized account.</p></div><form className={styles.filterForm} onSubmit={event => { event.preventDefault(); void session.load(); }}><label>Metric start office date<input aria-label="Metric start office date" type="date" required value={state.filters.from} onChange={event => session.changeFilters({ ...state.filters, from: event.target.value })} /></label><label>Metric end office date<input aria-label="Metric end office date" type="date" required value={state.filters.to} onChange={event => session.changeFilters({ ...state.filters, to: event.target.value })} /></label><label>Metric department<select aria-label="Metric department" value={state.filters.departmentId} onChange={event => session.changeFilters({ ...state.filters, departmentId: event.target.value })}><option value="">All authorized scope</option>{state.context.departmentOptions.map(department => <option key={department.id} value={department.id}>{department.name}</option>)}</select></label><button type="submit" className="button button-primary" disabled={loading}><RefreshCw size={16} />Apply metric period</button></form><p className={styles.note}>Choose up to 366 inclusive office dates. Workload is a current snapshot; the metric period controls historical flow and due-date cohorts.</p>
                {loading && <p role="status">Loading current workload and metrics…</p>}{!loading && !state.applied && <p className={styles.empty}>Apply the metric period to load its authorized measurements.</p>}
                {state.workload && <WorkloadPanel data={state.workload} />}{state.summary && <AnalyticsSummary data={state.summary} onRecords={metric => { returnFocus.current = document.activeElement as HTMLElement | null; session.openRecords(metric); }} />}
            </>}
        </div>}{state.records && <MetricRecords state={state} session={session} />}
    </section>;
}
