"use client";

import { brainServeApi, type EssentialLogRecord, isBackendConfigured } from "../../services/brainserve-api";
import { formatOfficeDate, formatOfficeTime } from "../../lib/appointments";
import { readDemoEssentialLogs } from "../../preview/governance";
import { PageTitle } from "../../components/ui/page-title";
import { nextIsoDay } from "../reports/report-utils";
import { FileText, Search } from "lucide-react";
import { useCallback, useEffect, useState } from "react";

export function EssentialLogsView() {
    const [records, setRecords] = useState<EssentialLogRecord[]>(() => isBackendConfigured ? [] : readDemoEssentialLogs());
    const [query, setQuery] = useState("");
    const [category, setCategory] = useState("");
    const [status, setStatus] = useState("");
    const [from, setFrom] = useState("");
    const [to, setTo] = useState("");
    const [nextCursor, setNextCursor] = useState<string | null>(null);
    const [hasMore, setHasMore] = useState(false);
    const [total, setTotal] = useState(0);
    const [busy, setBusy] = useState(false);
    const [error, setError] = useState("");
    const load = useCallback(async (append = false, cursor?: string) => {
        if (!isBackendConfigured) return;
        setBusy(true); setError("");
        try {
            const page = await brainServeApi.essentialLogs({
                query: query.trim() || undefined, category: category.trim() || undefined,
                status: status.trim() || undefined,
                from: from ? `${from}T00:00:00Z` : undefined,
                to: to ? `${nextIsoDay(to)}T00:00:00Z` : undefined,
                cursor, size: 50,
            });
            setRecords((current) => append ? [...current, ...page.items] : page.items);
            setNextCursor(page.nextCursor); setHasMore(page.hasMore); setTotal(page.total);
        } catch (reason) {
            setError(reason instanceof Error ? reason.message : "Essential logs could not be loaded.");
        } finally { setBusy(false); }
    }, [category, from, query, status, to]);
    useEffect(() => {
        if (!isBackendConfigured) return;
        const timer = window.setTimeout(() => void load(), 250);
        return () => window.clearTimeout(timer);
    }, [load]);
    const categories = [...new Set(records.map((item) => item.category))].sort();
    const filtered = isBackendConfigured ? records : records.filter((item) =>
        (!category || item.category === category) && (!status || item.status === status)
        && `${item.title} ${item.detail} ${item.eventType} ${item.subjectType} ${item.subjectId} ${item.referenceId ?? ""}`
            .toLowerCase().includes(query.trim().toLowerCase()));
    return <><PageTitle eyebrow="SYSTEM ADMIN RECORDS" title="Essential business logs"
                        detail="Database-backed lifecycle decisions retained for governance, investigations and future audits." />
        <section className="employee-summary"><div><strong>{isBackendConfigured ? total : records.length}</strong><span>Matching events</span></div><i /><div><strong>{records.length}</strong><span>Loaded safely</span></div><i /><div><strong>{records.filter((item) => item.status.includes("PENDING")).length}</strong><span>Pending on page</span></div><i /><div><strong>{categories.length}</strong><span>Loaded categories</span></div></section>
        <div className="toolbar glass-panel bounded-filter-bar"><div className="toolbar-search wide"><Search size={17} /><input value={query} onChange={(event) => setQuery(event.target.value)} placeholder="Search employee, reference, action or detail" /></div><input value={category} onChange={(event) => setCategory(event.target.value)} placeholder="Category" aria-label="Essential log category" /><input value={status} onChange={(event) => setStatus(event.target.value)} placeholder="Status" aria-label="Essential log status" /><input type="date" value={from} max={to || undefined} onChange={(event) => setFrom(event.target.value)} aria-label="From date" /><input type="date" value={to} min={from || undefined} onChange={(event) => setTo(event.target.value)} aria-label="To date" /></div>
        {error && <div className="login-error" role="alert">{error}</div>}
        <article className="panel glass-panel records-panel"><div className="panel-heading"><div><span>IMMUTABLE BUSINESS REGISTER</span><h2>Governance events</h2><p>Newest-first cursor paging keeps multi-year history responsive.</p></div><b>{filtered.length} / {isBackendConfigured ? total : filtered.length}</b></div><div className="records-table-wrap"><table className="records-table essential-logs-table"><thead><tr><th>Occurred</th><th>Category & event</th><th>Subject</th><th>Business detail</th><th>Decision</th></tr></thead><tbody>{filtered.map((item) => <tr key={item.id}><td><strong>{formatOfficeDate(item.occurredAt)}</strong><small>{formatOfficeTime(item.occurredAt)}</small></td><td><strong>{item.category.replaceAll("_", " ")}</strong><small>{item.eventType.replaceAll("_", " ")}</small><code>{item.referenceId ?? item.id}</code></td><td><strong>{item.subjectType.replaceAll("_", " ")}</strong><small>{item.subjectId}</small></td><td><strong>{item.title}</strong><small>{item.detail}</small></td><td><span className={`business-status business-${item.status.toLowerCase()}`}>{item.status.replaceAll("_", " ")}</span><small>Actor {item.actorUserId ?? "system"}</small>{item.approverUserId && <small>Approver {item.approverUserId}</small>}</td></tr>)}{filtered.length === 0 && <tr><td colSpan={5}><div className="empty-state table-empty"><FileText size={28} /><strong>No essential records found</strong><small>Change the database filters or date range.</small></div></td></tr>}</tbody></table></div>{isBackendConfigured && hasMore && <div className="bounded-pagination"><button className="button button-secondary" disabled={busy || !nextCursor} onClick={() => void load(true, nextCursor ?? undefined)}>{busy ? "Loading…" : "Load 50 more"}</button></div>}</article>
    </>;
}

