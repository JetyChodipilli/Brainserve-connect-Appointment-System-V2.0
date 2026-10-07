"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { ArrowRight, CalendarDays, ChevronLeft, ChevronRight, Clock3, RefreshCw, X } from "lucide-react";
import { PageTitle } from "../../components/ui/page-title";
import { type DashboardMetrics, type View } from "../../types/workspace";
import { dashboardApi } from "./api/dashboard-api";
import { DashboardFreshness } from "./dashboard-freshness-label";
import { dashboardFreshness, type DashboardFreshnessMetadata } from "./dashboard-freshness";
import { dashboardFirstIds, dashboardPeriods, dashboardQuery, dashboardTimestamp,
    metricDisplay, metricFreshness, unavailableCard, validateDashboardDates, validateDashboardRecords, validateDashboardScope,
    type DashboardCards, type DashboardRange, type DashboardRecords, type DashboardRole, type MetricCard } from "./administration-dashboard-model";
import { useDashboardResource } from "./use-dashboard-resource";
import styles from "./administration-dashboard.module.css";
import { WorkAnalyticsWorkspace } from "../work-planning/work-analytics-workspace";

export const dashboardCardsEnabled = process.env.NEXT_PUBLIC_DASHBOARD_CARDS_ENABLED !== "false";

export function MetricMeasurement({ card, data, now, retained = false, loading = false, onRecords }: {
    card: MetricCard; data: DashboardCards | null; now: number; retained?: boolean; loading?: boolean; onRecords?: () => void;
}) {
    const zone = data?.officeZone ?? "UTC";
    const freshness = metricFreshness(card, data?.sourceGeneration ?? null, now, retained);
    const label = card.clock === "PERIOD" ? `Period · ${data ? `${data.from} to ${data.to}` : "office dates pending"}`
        : card.clock === "HISTORY" ? "Retained history · see source coverage" : `Now · as of ${dashboardTimestamp(data?.asOf ?? null, zone)}`;
    return <article className={`${styles.card} glass-panel`} aria-label={card.title} data-metric-id={card.id} data-state={loading ? "LOADING" : card.state}>
        <div className={styles.cardHeading}><h3>{card.title}</h3><span>{card.id}</span></div>
        <p className={styles.clock}><Clock3 size={14} aria-hidden="true" />{label}{card.clock === "NOW" && <small>Independent of selected dates</small>}</p>
        <strong className={styles.value}>{loading && !data ? "Loading…" : metricDisplay(card)}</strong>
        <p className={styles.definition}>{card.definition}</p>
        {card.reason && !loading && <p className={styles.reason}>{card.reason}</p>}
        {card.sampleSize !== null && card.state !== "RESTRICTED" && <p className={styles.sample}>Raw observations n={card.sampleSize}{card.eligibleCount !== null && ` · eligible ${card.eligibleCount}`}
            {card.coveragePercent !== null && ` · coverage ${card.coveragePercent}%`}{card.excludedCount !== null && ` · excluded ${card.excludedCount}`}</p>}
        {card.sampleSize !== null && card.sampleSize < 20 && card.state === "AVAILABLE" && card.id === "VIS09"
            && <p className={styles.reason}>Small sample (n &lt; 20): interpret p95 with caution.</p>}
        <div className={styles.source} data-freshness={freshness.state}>
            <span>{freshness.label}</span>
            <span>Source refreshed <time dateTime={card.sourceRefreshedAt ?? undefined}>{dashboardTimestamp(card.sourceRefreshedAt, zone)}</time></span>
            {card.freshUntil && <span>Fresh until <time dateTime={card.freshUntil}>{dashboardTimestamp(card.freshUntil, zone)}</time></span>}
        </div>
        {onRecords && card.drillDownAvailable && card.state === "AVAILABLE" && <button className={styles.recordButton} type="button" onClick={onRecords}>
            View records<span className={styles.srOnly}> for {card.title}</span><ArrowRight size={15} aria-hidden="true" /></button>}
    </article>;
}

function RecordDialog({ card, range, scope, refreshKey, zone, onClose }: {
    card: MetricCard; range: DashboardRange; scope: string; refreshKey: number; zone: string; onClose: () => void;
}) {
    const dialog = useRef<HTMLDialogElement>(null);
    const [page, setPage] = useState(0);
    const [retry, setRetry] = useState(0);
    const load = useCallback(async (signal: AbortSignal) => {
        const result = await dashboardApi.cardRecords(card.id, range, page, 50, signal);
        validateDashboardRecords(result, card.id, range, page);
        return result;
    }, [card.id, range, page]);
    const records = useDashboardResource<DashboardRecords>(`${scope}:${dashboardQuery(range)}:${card.id}:${page}`, refreshKey + retry, load);

    useEffect(() => {
        const element = dialog.current;
        const trigger = document.activeElement as HTMLElement | null;
        element?.showModal();
        return () => { element?.close(); if (trigger?.isConnected) trigger.focus(); };
    }, []);

    const result = records.data;
    return <dialog className={styles.dialog} ref={dialog} aria-labelledby="dashboard-records-title" aria-describedby="dashboard-records-definition"
        onKeyDown={(event) => {
            if (event.key !== "Tab") return;
            const controls = Array.from(event.currentTarget.querySelectorAll<HTMLElement>("button:not(:disabled), a[href], input:not(:disabled), select:not(:disabled), textarea:not(:disabled), [tabindex]:not([tabindex='-1'])"))
                .filter((element) => element.getClientRects().length > 0);
            const first = controls[0], last = controls[controls.length - 1];
            if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last?.focus(); }
            else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first?.focus(); }
        }}
        onCancel={(event) => { event.preventDefault(); onClose(); }} onClick={(event) => { if (event.target === event.currentTarget) {
            const bounds = event.currentTarget.getBoundingClientRect();
            if (event.clientX < bounds.left || event.clientX > bounds.right || event.clientY < bounds.top || event.clientY > bounds.bottom) onClose();
        } }}>
        <header className={styles.dialogHeader}><div><span>{card.id} · AUTHORIZED SOURCE RECORDS</span><h2 id="dashboard-records-title">{card.title}</h2></div>
            <button type="button" className={styles.closeButton} aria-label="Close dashboard records" onClick={onClose} autoFocus><X size={20} aria-hidden="true" /></button></header>
        <p id="dashboard-records-definition">{card.definition}</p>
        <p className={styles.metadata}>{card.clock === "NOW" ? "Live snapshot; selected dates do not filter this metric." : card.clock === "HISTORY" ? "Retained history" : "Selected office dates"}
            {result && <> · {result.from} to {result.to} · As of {dashboardTimestamp(result.asOf, zone)}</>} · {zone}</p>
        {records.phase === "loading" && <p role="status">Loading records…{result && " Last received page is stale."}</p>}
        {records.error && <div className={styles.error} role="alert"><p>{records.error}{result && " Last received page is stale."}</p>
            <button type="button" className={styles.recordButton} onClick={() => setRetry((value) => value + 1)}>Retry records</button></div>}
        {result && result.state !== "AVAILABLE" && <p role="status">{result.state === "NOT_APPLICABLE" ? "Not applicable" : result.state === "RESTRICTED" ? "Restricted" : "Unavailable"}: {result.reason ?? "Source measurement is unavailable."}</p>}
        {result?.state === "AVAILABLE" && <>
            {result.items.length === 0 ? <p role="status">No matching records in this scope. Choose another period to inspect its source records.</p> : <ul className={styles.records}>
                {result.items.map((item) => <li key={item.id}><strong>{item.label}</strong><p>{item.detail}</p><small>{item.kind}{item.status && ` · ${item.status}`}
                    {item.occurredAt && <> · <time dateTime={item.occurredAt}>{dashboardTimestamp(item.occurredAt, zone)}</time></>}</small></li>)}</ul>}
            <nav className={styles.pagination} aria-label="Dashboard record pages"><button type="button" onClick={() => setPage((value) => value - 1)} disabled={page === 0 || records.phase === "loading"}><ChevronLeft size={17} aria-hidden="true" />Previous</button>
                <span aria-live="polite">{result.totalElements} records · Page {result.totalPages === 0 ? 0 : page + 1} of {result.totalPages}</span>
                <button type="button" onClick={() => setPage((value) => value + 1)} disabled={page + 1 >= result.totalPages || page >= 10_000 || records.phase === "loading"}>Next<ChevronRight size={17} aria-hidden="true" /></button></nav>
        </>}
    </dialog>;
}

export function AdministrationDashboard({ role, userEmail, refreshKey, onNavigate, legacyMetrics }: {
    role: DashboardRole; userEmail: string; refreshKey: number; onNavigate: (view: View) => void;
    legacyMetrics?: DashboardMetrics & DashboardFreshnessMetadata;
}) {
    const [range, setRange] = useState<DashboardRange>({ period: "TODAY" });
    const [picker, setPicker] = useState<DashboardRange["period"]>("TODAY");
    const [from, setFrom] = useState(""); const [to, setTo] = useState("");
    const [dateError, setDateError] = useState<string | null>(null);
    const [retry, setRetry] = useState(0); const [, setNow] = useState(() => Date.now());
    // eslint-disable-next-line react-hooks/purity -- Evaluate new source timestamps immediately; the interval also advances their age.
    const now = Date.now();
    const [selected, setSelected] = useState<{ scope: string; card: MetricCard } | null>(null);
    const scope = `${role}:${userEmail}:${dashboardQuery(range)}`;
    const load = useCallback(async (signal: AbortSignal) => {
        const result = await dashboardApi.cards(range, signal);
        validateDashboardScope(result, role, range);
        return result;
    }, [range, role]);
    const resource = useDashboardResource<DashboardCards>(scope, refreshKey + retry, load);
    const data = resource.data;
    const retained = data !== null && resource.phase !== "ready";
    const recordRange = useMemo<DashboardRange>(() => data ? { period: "CUSTOM", from: data.from, to: data.to } : range,
        [data, range]);

    useEffect(() => {
        const timer = window.setInterval(() => setNow(Date.now()), 1_000);
        return () => window.clearInterval(timer);
    }, []);
    if (selected && resource.phase === "error" && !data) setSelected(null);
    const first = dashboardFirstIds[role].map((id) => data?.cards.find((card) => card.id === id)
        ?? unavailableCard(id, resource.error ?? "This source measurement has not been returned."));
    const supplementary = (role === "CEO" ? ["VIS14"] : ["OPS08", "OPS09"]).map((id) => data?.supplementary.find((card) => card.id === id)
        ?? unavailableCard(id, resource.error ?? "This source measurement has not been returned."));
    const metricsKnown = legacyMetrics && legacyMetrics.metricsLoadState === "ready"
        && legacyMetrics.sourceGeneration != null && legacyMetrics.sourceRefreshedAt;
    const legacyState = legacyMetrics ? dashboardFreshness(legacyMetrics, now) : null;

    return <section className={styles.dashboard} aria-label="Administration dashboard">
        <PageTitle eyebrow={`${role.toUpperCase()} · COMPANY OVERVIEW`} title={role === "CEO" ? "Executive governance centre" : "Administration command centre"}
            detail={role === "CEO" ? "Review visitor flow, live approval stages and delivery commitments." : "Review operating evidence, approval queues and source coverage."}
            action={<button type="button" className="button button-secondary" onClick={() => onNavigate(role === "CEO" ? "appointments" : "settings")}><ArrowRight size={17} aria-hidden="true" />{role === "CEO" ? "Open appointments" : "Open administration"}</button>} />
        <div className={`${styles.toolbar} glass-panel`}>
            <form className={styles.periodForm} noValidate onSubmit={(event) => { event.preventDefault(); const error = validateDashboardDates(from, to); setDateError(error);
                if (!error) { setSelected(null); setRange({ period: "CUSTOM", from, to }); } }}>
                <label><span><CalendarDays size={15} aria-hidden="true" />Dashboard period</span><select value={picker} onChange={(event) => {
                    const period = event.target.value as DashboardRange["period"]; setPicker(period); setDateError(null);
                    if (period !== "CUSTOM") { setSelected(null); setRange({ period }); }
                }}>{dashboardPeriods.map(([value, title]) => <option key={value} value={value}>{title}</option>)}</select></label>
                {picker === "CUSTOM" && <><label>Dashboard from<input type="date" value={from} max={to || undefined} onChange={(event) => setFrom(event.target.value)} required aria-invalid={Boolean(dateError)} aria-describedby="dashboard-date-help" /></label>
                    <label>Dashboard to<input type="date" value={to} min={from || undefined} onChange={(event) => setTo(event.target.value)} required aria-invalid={Boolean(dateError)} aria-describedby="dashboard-date-help" /></label>
                    <button type="submit" className={styles.controlButton}>Apply dashboard range</button><p id="dashboard-date-help" className={styles.dateHelp} role={dateError ? "alert" : undefined}>{dateError ?? "Office dates, inclusive. Maximum 366 days. Apply dates to change measurements."}</p></>}
            </form>
            <button type="button" className={styles.controlButton} onClick={() => setRetry((value) => value + 1)} disabled={resource.phase === "loading"}><RefreshCw size={16} aria-hidden="true" />Refresh</button>
        </div>
        <p className={styles.metadata}>Company scope · {data ? `${data.from} to ${data.to} · ${data.officeZone}` : `${dashboardPeriods.find(([period]) => period === range.period)?.[1]} · Office dates and timezone pending`}
            {data && <> · As of <time dateTime={data.asOf}>{dashboardTimestamp(data.asOf, data.officeZone)}</time> · Source generation {data.sourceGeneration ?? "unknown"} · {data.metricVersion}</>}</p>
        {resource.phase === "loading" && <p role="status">{data ? "Refreshing measurements. Last received values are stale." : "Loading source measurements…"}</p>}
        {resource.error && <div className={styles.error} role="alert"><p>{resource.error}{data && " Last received values are stale."}</p><span>Use Refresh to retry this period.</span></div>}
        <div className={styles.grid}>{first.map((card) => <MetricMeasurement key={card.id} card={card} data={data} now={now} retained={retained}
            loading={resource.phase === "loading"} onRecords={() => setSelected({ scope: resource.scope, card })} />)}</div>
        <section className={styles.supplementary} aria-label={role === "CEO" ? "Visitors" : "Operating evidence"}>
            <h2>{role === "CEO" ? "Visitors" : "Operating evidence"}</h2>
            {role === "CEO" && <p className={styles.metadata}>Selected-period first arrivals: {data ? metricDisplay(first[0]) : "Unavailable"} · Live inside: {data ? metricDisplay(first[1]) : "Unavailable"}. Retained appointments below use the declared source history.</p>}
            {role === "CEO" && legacyMetrics && <><h3 className={styles.legacyHeading}>Today’s visitor and workforce summary</h3><DashboardFreshness summary={legacyMetrics} />
                <dl className={styles.legacySummary} data-freshness={legacyState?.state}>{[
                    ["Active visits · today", legacyMetrics.activeVisits, "Approved or currently in progress"],
                    ["In workflow · today", legacyMetrics.awaitingApproval, "Approval workflow"],
                    ["Currently inside · live", legacyMetrics.visitorsInside, "Live access records"],
                    ["Active employees", legacyMetrics.activeEmployees, `${metricsKnown ? legacyMetrics.totalEmployees : "Unknown"} total profiles`],
                    ["Arrived today", legacyMetrics.arrivedVisits, "Security intake recorded"],
                ].map(([label, value, detail]) => <div key={label}><dt>{label}</dt><dd>{metricsKnown ? value : "Unavailable"}</dd><small>{detail}{metricsKnown && legacyState?.state !== "fresh" ? " · Stale or source freshness unknown" : ""}</small></div>)}</dl></>}
            <div className={styles.supplementaryGrid}>{supplementary.map((card) => <MetricMeasurement key={card.id} card={card} data={data} now={now} retained={retained}
                loading={resource.phase === "loading"} onRecords={() => setSelected({ scope: resource.scope, card })} />)}</div>
        </section>
        {data && <details className={`${styles.coverage} glass-panel`}><summary>Source coverage and history</summary>
            {data.coverage.length === 0 ? <p>No source coverage metadata was returned.</p> : <ul>{data.coverage.map((item) => <li key={item.id}><strong>{item.title} · {item.state}</strong>
                <p>{item.reason}</p><small>History since {item.since ? dashboardTimestamp(item.since, data.officeZone) : "unknown; legacy coverage is not confirmed"}</small></li>)}</ul>}</details>}
        {role === "CEO" && <WorkAnalyticsWorkspace role={role} identityKey={`${role}:${userEmail}`} />}
        {selected && data && selected.scope === resource.scope && <RecordDialog key={`${selected.scope}:${selected.card.id}`} card={selected.card} range={recordRange}
            scope={resource.scope} refreshKey={refreshKey} zone={data?.officeZone ?? "UTC"} onClose={() => setSelected(null)} />}
    </section>;
}
