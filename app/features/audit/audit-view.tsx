"use client";

import { ApiError, brainServeApi, isBackendConfigured } from "../../lib/api";
import { formatOfficeDate, formatOfficeTime } from "../../lib/appointments";
import { PageTitle } from "../../shared/components/page-title";
import { nextIsoDay } from "../reports/report-utils";
import { FileClock, Search, ShieldCheck } from "lucide-react";
import { useCallback, useEffect, useState } from "react";

export function AuditView() {
    const [events, setEvents] = useState<Array<{ id: string; occurredAt: string; actorId: string; eventType: string;
        targetType: string; targetId: string; outcome: string; correlationId: string | null }>>(() => !isBackendConfigured
        ? [{ id: "demo-audit", occurredAt: new Date().toISOString(), actorId: "demo-user",
            eventType: "DEMO_WORKSPACE_OPENED", targetType: "WORKSPACE", targetId: "brainserve-demo",
            outcome: "SUCCESS", correlationId: null }]
        : []);
    const [query, setQuery] = useState("");
    const [outcome, setOutcome] = useState("");
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
            const page = await brainServeApi.auditEvents({ query: query.trim() || undefined,
                outcome: outcome || undefined, from: from ? `${from}T00:00:00Z` : undefined,
                to: to ? `${nextIsoDay(to)}T00:00:00Z` : undefined, cursor, size: 50 });
            setEvents((current) => append ? [...current, ...page.items] : page.items);
            setNextCursor(page.nextCursor); setHasMore(page.hasMore); setTotal(page.total);
        } catch (reason) {
            setError(reason instanceof ApiError ? reason.message : "Audit events could not be loaded.");
        } finally { setBusy(false); }
    }, [from, outcome, query, to]);
    useEffect(() => {
        if (!isBackendConfigured) return;
        const timer = window.setTimeout(() => void load(), 250);
        return () => window.clearTimeout(timer);
    }, [load]);
    const filtered = isBackendConfigured ? events : events.filter((event) =>
        `${event.actorId} ${event.eventType} ${event.targetType} ${event.targetId}`.toLowerCase().includes(query.toLowerCase()));
    return <><PageTitle eyebrow="AUDIT & COMPLIANCE" title="Every privileged action, accountable" detail="Immutable security and business events with correlation-level traceability." /><section className="employee-summary"><div><strong>{isBackendConfigured ? total : filtered.length}</strong><span>Matching events</span></div><i /><div><strong>{filtered.length}</strong><span>Loaded safely</span></div><i /><div><strong>50</strong><span>Records per request</span></div></section><div className="toolbar glass-panel bounded-filter-bar"><div className="toolbar-search wide"><Search size={17} /><input value={query} onChange={(event) => setQuery(event.target.value)} placeholder="Search actor, action or reference" /></div><select value={outcome} onChange={(event) => setOutcome(event.target.value)} aria-label="Audit outcome"><option value="">All outcomes</option><option value="SUCCESS">Success</option><option value="FAILURE">Failure</option><option value="DENIED">Denied</option></select><input type="date" value={from} max={to || undefined} onChange={(event) => setFrom(event.target.value)} aria-label="From date" /><input type="date" value={to} min={from || undefined} onChange={(event) => setTo(event.target.value)} aria-label="To date" /></div>{error && <div className="login-error" role="alert">{error}</div>}<div className="audit-list glass-panel">{filtered.map((event) => <div key={event.id}><span className="audit-icon"><ShieldCheck size={19} /></span><span><strong>{event.eventType.replaceAll("_", " ")}</strong><small>{event.actorId} · {event.targetType} {event.targetId}</small></span><code>{event.outcome}</code><time>{formatOfficeTime(event.occurredAt)}<small>{formatOfficeDate(event.occurredAt)}</small></time></div>)}{filtered.length === 0 && <div className="empty-state"><FileClock size={28} /><strong>No audit events found</strong></div>}{isBackendConfigured && hasMore && <div className="bounded-pagination"><button className="button button-secondary" disabled={busy || !nextCursor} onClick={() => void load(true, nextCursor ?? undefined)}>{busy ? "Loading…" : "Load 50 more"}</button></div>}</div></>;
}

