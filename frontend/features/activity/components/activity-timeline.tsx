'use client';
import { deliveryLabel } from '../activity-model';
import { useActivityTimeline } from '../hooks/use-activity-timeline';
import styles from '../activity.module.css';
export function ActivityTimeline({ kind, recordId, refreshKey = 0 }: { kind: 'task' | 'appointment'; recordId: string; refreshKey?: number }) {
    return <Timeline key={`${kind}:${recordId}:${refreshKey}`} kind={kind} recordId={recordId} />;
}
function Timeline({ kind, recordId }: { kind: 'task' | 'appointment'; recordId: string }) {
    const activity = useActivityTimeline(kind, recordId);
    return <section className={styles.section} aria-label={`${kind === 'task' ? 'Worksheet' : 'Visit'} activity timeline`}>
        <div className={styles.heading}><h3>Activity timeline</h3><button className="button button-secondary" type="button" disabled={activity.busy} onClick={() => void activity.reload()}>Reload activity</button></div>
        <p className={styles.muted}>Events appear in recorded order. Older events may have no actor snapshot or submission version.</p>
        {activity.error && <p className="login-error" role="alert">{activity.error}</p>}{activity.busy && <p role="status">Loading recorded activity…</p>}
        <ol className={styles.timeline}>{activity.events.map(event => <li key={event.id} data-event-id={event.id}>
            <div className={styles.heading}><strong>{event.title}</strong><time dateTime={event.occurredAt}>{new Date(event.occurredAt).toLocaleString('en-IN')}</time></div>
            <p className={styles.muted}>{event.actor.snapshotRecorded ? event.actor.name : 'Actor snapshot not recorded'}{event.actor.role && ` · ${event.actor.role.replace(/^ROLE_/, '').replaceAll('_', ' ')}`}{event.cycle !== null && ` · Cycle ${event.cycle}`}{event.evidenceVersion !== null && ` · Evidence version ${event.evidenceVersion}`}</p>
            {event.deliveryStatus && <span className={styles.receipt}>{deliveryLabel(event.deliveryStatus)}</span>}{event.note && <p className={styles.body}>{event.note}</p>}
            <details><summary>Event reference</summary><small>{event.id}{event.correlationId && ` · Correlation ${event.correlationId}`}{event.departmentId && ` · Department ${event.departmentId}`}</small></details>
        </li>)}</ol>
        {!activity.busy && !activity.error && activity.events.length === 0 && <p>No retained activity is available yet.</p>}
        {activity.hasMore && <button className="button button-secondary" type="button" disabled={activity.busy} onClick={() => void activity.more()}>Load more activity</button>}
    </section>;
}
