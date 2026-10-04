import type { RoutinePage } from '../types';
import styles from '../routines.module.css';
export function RoutinePagination({ label, data, page, busy, onPage }: { label: string; data: RoutinePage<unknown> | null; page: number; busy: boolean; onPage: (page: number) => void }) {
    return <nav className={styles.pagination} aria-label={`${label} pagination`}>
        <span>{data ? `${data.totalElements} ${label.toLowerCase()} · Page ${page + 1} of ${Math.max(1, data.totalPages)}` : `Loading ${label.toLowerCase()}…`}</span>
        <button type="button" className="button button-secondary" disabled={busy || !data || page === 0} onClick={() => onPage(page - 1)}>Previous {label.toLowerCase()} page</button>
        <button type="button" className="button button-secondary" disabled={busy || !data || page + 1 >= data.totalPages} onClick={() => onPage(page + 1)}>Next {label.toLowerCase()} page</button>
    </nav>;
}
