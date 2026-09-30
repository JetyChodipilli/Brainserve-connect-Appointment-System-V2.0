"use client";

import { brainServeApi, type HistoryDataset, type HistoryRow, type ReportExportJob } from "../../services/brainserve-api";
import { formatOfficeDate, formatOfficeTime, officeToday } from "../../lib/appointments";
import { PageTitle } from "../../components/ui/page-title";
import { type Role } from "../../types/workspace";
import { HistoryDatasetOptions } from "./components/history-dataset-options";
import { historyDatasetLabels, historyDatasetsByRole, nextIsoDay } from "./report-utils";
import { Archive, FileClock, FileText, RotateCcw, Search } from "lucide-react";
import { useCallback, useEffect, useState } from "react";

export function ScalableReportsView({ role, refreshKey }: { role: Role; refreshKey: number }) {
    const today = officeToday();
    const [dataset, setDataset] = useState<HistoryDataset>(historyDatasetsByRole[role][0]);
    const [from, setFrom] = useState(`${today.slice(0, 8)}01`);
    const [to, setTo] = useState(today);
    const [status, setStatus] = useState("");
    const [query, setQuery] = useState("");
    const [applied, setApplied] = useState({ dataset, from, to, status, query });
    const [rows, setRows] = useState<HistoryRow[]>([]);
    const [nextCursor, setNextCursor] = useState<string | null>(null);
    const [hasMore, setHasMore] = useState(false);
    const [exports, setExports] = useState<ReportExportJob[]>([]);
    const [busy, setBusy] = useState("");
    const [error, setError] = useState("");
    const [section, setSection] = useState<"explore" | "exports">("explore");

    const filters = useCallback((cursor?: string) => ({ dataset: applied.dataset,
        from: `${applied.from}T00:00:00+05:30`, to: `${nextIsoDay(applied.to)}T00:00:00+05:30`,
        status: applied.status || undefined, query: applied.query.trim() || undefined, cursor, size: 50,
    }), [applied]);

    const load = useCallback(async (append = false, cursor?: string) => {
        setBusy(append ? "more" : "history"); setError("");
        try {
            const page = await brainServeApi.history(filters(cursor));
            setRows((current) => append ? [...current, ...page.items] : page.items);
            setNextCursor(page.nextCursor); setHasMore(page.hasMore);
        } catch (reason) { setError(reason instanceof Error ? reason.message : "History could not be loaded."); }
        finally { setBusy(""); }
    }, [filters]);

    const refreshExports = useCallback(async () => {
        try { setExports(await brainServeApi.reportExports()); } catch { /* History remains usable. */ }
    }, []);

    useEffect(() => {
        const timer = window.setTimeout(() => { void load(); void refreshExports(); }, 0);
        return () => window.clearTimeout(timer);
    }, [load, refreshExports, refreshKey]);
    useEffect(() => {
        if (!exports.some((item) => item.status === "QUEUED" || item.status === "RUNNING")) return;
        const timer = window.setInterval(() => void refreshExports(), 4000);
        return () => window.clearInterval(timer);
    }, [exports, refreshExports]);

    const requestExport = async (format: "CSV" | "XLSX") => {
        setBusy(format); setError("");
        try {
            await brainServeApi.requestReportExport({ dataset: applied.dataset, format,
                from: `${applied.from}T00:00:00+05:30`, to: `${nextIsoDay(applied.to)}T00:00:00+05:30`,
                status: applied.status || undefined, query: applied.query.trim() || undefined });
            await refreshExports();
            setSection("exports");
        } catch (reason) { setError(reason instanceof Error ? reason.message : "Export could not be queued."); }
        finally { setBusy(""); }
    };

    const retry = async (job: ReportExportJob) => {
        setBusy(job.id); setError("");
        try { await brainServeApi.retryReportExport(job.id); await refreshExports(); }
        catch (reason) { setError(reason instanceof Error ? reason.message : "Export could not be retried."); }
        finally { setBusy(""); }
    };

    const applyPreset = (days: number) => {
        const start = new Date(`${today}T00:00:00Z`); start.setUTCDate(start.getUTCDate() - days + 1);
        setFrom(start.toISOString().slice(0, 10)); setTo(today);
    };

    const download = async (job: ReportExportJob) => {
        setBusy(job.id); setError("");
        try { const access = await brainServeApi.reportExportDownload(job.id); window.location.assign(access.url); }
        catch (reason) { setError(reason instanceof Error ? reason.message : "Download link could not be created."); }
        finally { setBusy(""); }
    };

    const detailPreview = (row: HistoryRow) => Object.entries(row.details).filter(([, value]) => value !== null && value !== "")
        .slice(0, 3).map(([key, value]) => `${key.replaceAll(/([A-Z])/g, " $1")}: ${String(value)}`).join(" · ");

    return <><PageTitle eyebrow="ROLE-SCOPED DATA EXPLORER" title="Fast daily and historical records"
                        detail="Server-side filters, cursor pagination and background exports keep multi-year records responsive." />
        <nav className="report-section-tabs" aria-label="Report workspace">
            <button className={section === "explore" ? "active" : ""} onClick={() => setSection("explore")}><Search size={16} /><span><strong>Explore records</strong><small>Filter and review authorized history</small></span></button>
            <button className={section === "exports" ? "active" : ""} onClick={() => setSection("exports")}><FileText size={16} /><span><strong>Export centre</strong><small>{exports.filter((item) => item.status === "QUEUED" || item.status === "RUNNING").length} processing · {exports.filter((item) => item.status === "COMPLETED").length} ready</small></span></button>
        </nav>
        {section === "explore" && <>
            <div className="history-presets" aria-label="Quick date ranges"><span>Quick range</span><button type="button" onClick={() => applyPreset(1)}>Today</button><button type="button" onClick={() => applyPreset(7)}>7 days</button><button type="button" onClick={() => applyPreset(30)}>30 days</button></div>
            <form className="history-filter-panel glass-panel" onSubmit={(event) => { event.preventDefault(); setRows([]); setApplied({ dataset, from, to, status, query }); }}>
                <label>Dataset<select value={dataset} onChange={(event) => { const next = event.target.value as HistoryDataset; setDataset(next); setStatus(""); setRows([]); setApplied({ dataset: next, from, to, status: "", query }); }}>
                    <HistoryDatasetOptions role={role} /></select></label>
                <label>From<input type="date" value={from} max={to} onChange={(event) => setFrom(event.target.value)} required /></label>
                <label>To<input type="date" value={to} min={from} max={today} onChange={(event) => setTo(event.target.value)} required /></label>
                <label>Status<input value={status} onChange={(event) => setStatus(event.target.value)} placeholder="All statuses" /></label>
                <label className="history-query">Search<input value={query} onChange={(event) => setQuery(event.target.value)} placeholder="Reference, visitor, employee or event" /></label>
                <div className="history-filter-actions"><button className="button button-primary" disabled={Boolean(busy)}><Search size={15} />{busy === "history" ? "Loading…" : "Apply filters"}</button>
                    <button type="button" className="button button-secondary" disabled={Boolean(busy)}
                            onClick={() => void load()}><RotateCcw size={15} />Refresh</button></div>
            </form>
            {error && <div className="login-error" role="alert">{error}</div>}
            <section className="history-summary employee-summary"><div><strong>{rows.length}</strong><span>Rows loaded</span></div><i /><div><strong>{hasMore ? "More available" : "All loaded"}</strong><span>Pagination status</span></div><i /><div><strong>{applied.from}</strong><span>Applied start</span></div><i /><div><strong>{applied.to}</strong><span>Applied end</span></div></section>
            <article className="panel glass-panel records-panel"><div className="panel-heading"><div><span>{historyDatasetLabels[dataset]}</span><h2>Role-authorized history</h2><p>Only the fields and department scope permitted for {role} are returned by the server.</p></div><div className="history-export-actions"><button className="button button-secondary" disabled={Boolean(busy)} onClick={() => void requestExport("CSV")}><FileText size={15} />{busy === "CSV" ? "Queuing…" : "Export CSV"}</button><button className="button button-primary" disabled={Boolean(busy)} onClick={() => void requestExport("XLSX")}><FileText size={15} />{busy === "XLSX" ? "Queuing…" : "Export XLSX"}</button></div></div>
                <div className="records-table-wrap"><table className="records-table history-table"><thead><tr><th>Occurred</th><th>Record</th><th>Context</th><th>Status</th></tr></thead><tbody>{rows.map((row) => <tr key={`${row.occurredAt}:${row.id}`}><td><strong>{formatOfficeDate(row.occurredAt)}</strong><small>{formatOfficeTime(row.occurredAt)}</small></td><td><strong>{row.primaryLabel}</strong><code>{row.id}</code></td><td><strong>{row.secondaryLabel}</strong><small>{detailPreview(row) || "No additional detail"}</small></td><td><span className="business-status">{row.status.replaceAll("_", " ")}</span></td></tr>)}{rows.length === 0 && <tr><td colSpan={4}><div className="empty-state table-empty"><FileClock size={28} /><strong>No records match these filters</strong><small>Choose another date range, status or search term.</small></div></td></tr>}</tbody></table></div>
                {hasMore && <div className="history-load-more"><button className="button button-secondary" disabled={Boolean(busy)} onClick={() => void load(true, nextCursor ?? undefined)}>{busy === "more" ? "Loading…" : "Load next 50 records"}</button></div>}
            </article></>}
        {section === "exports" && <article className="panel glass-panel records-panel export-jobs-panel"><div className="panel-heading"><div><span>BACKGROUND EXPORTS</span><h2>Export centre</h2><p>Files are generated securely in the background. Completed downloads expire automatically.</p></div><button className="icon-button" onClick={() => void refreshExports()} aria-label="Refresh exports"><RotateCcw size={17} /></button></div>
            <div className="export-help-strip"><Archive size={18} /><span><strong>No need to keep this page open.</strong><small>You receive an Internal Delivery alert when the file is ready.</small></span></div>
            <div className="export-job-list">{exports.slice(0, 20).map((job) => <div key={job.id}><span className={`export-job-state export-${job.status.toLowerCase()}`}>{job.status}</span><span><strong>{job.dataset.replaceAll("_", " ")} · {job.format}</strong><small>{job.rowCount ? `${job.rowCount.toLocaleString("en-IN")} rows · ` : ""}{formatOfficeDate(job.createdAt)}{job.expiresAt ? ` · expires ${formatOfficeDate(job.expiresAt)}` : ""}</small>{job.errorMessage && <small className="export-error">{job.errorMessage}</small>}</span><div className="export-row-actions">{job.status === "COMPLETED" && <button className="button button-primary" disabled={busy === job.id} onClick={() => void download(job)}>{busy === job.id ? "Preparing…" : "Download"}</button>}{job.status === "FAILED" && <button className="button button-secondary" disabled={busy === job.id} onClick={() => void retry(job)}><RotateCcw size={14} />{busy === job.id ? "Retrying…" : "Retry"}</button>}</div></div>)}{exports.length === 0 && <div className="empty-state"><Archive size={25} /><strong>No exports requested yet</strong><small>Return to Explore records and export the currently applied filters.</small></div>}</div>
        </article>}
    </>;
}

