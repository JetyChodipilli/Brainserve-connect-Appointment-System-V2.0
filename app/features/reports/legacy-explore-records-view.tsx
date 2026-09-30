"use client";

import { type HistoryDataset, type HistoryRow } from "../../lib/api";
import { formatOfficeDate, formatOfficeTime, officeToday } from "../../lib/appointments";
import { PageTitle } from "../../shared/components/page-title";
import { type AccessRecord, type Appointment, type Role } from "../../shared/types/workspace";
import { officeDateFromInstant } from "../work/work-utils";
import { HistoryDatasetOptions } from "./components/history-dataset-options";
import { historyDatasetLabels, historyDatasetsByRole, previewHistoryRows } from "./report-utils";
import { FileClock, RotateCcw } from "lucide-react";
import { useState } from "react";

export function LegacyExploreRecordsView({ role, appointments, accessRecords, onRefresh }: { role: Role;
    appointments: Appointment[]; accessRecords: AccessRecord[]; onRefresh: () => void }) {
    const today = officeToday();
    const [dataset, setDataset] = useState<HistoryDataset>(historyDatasetsByRole[role][0]);
    const [from, setFrom] = useState(`${today.slice(0, 8)}01`);
    const [to, setTo] = useState(today);
    const [status, setStatus] = useState("");
    const [query, setQuery] = useState("");
    const availableRows = previewHistoryRows(dataset, appointments, accessRecords);
    const matches = availableRows.filter((row) => {
        const occurred = officeDateFromInstant(row.occurredAt);
        const dateMatches = occurred >= from && occurred <= to;
        const statusMatches = !status || row.status.toLowerCase() === status.toLowerCase();
        const textMatches = !query.trim()
            || `${row.id} ${row.primaryLabel} ${row.secondaryLabel} ${JSON.stringify(row.details)}`
                .toLowerCase().includes(query.trim().toLowerCase());
        return dateMatches && statusMatches && textMatches;
    });
    const statuses = [...new Set(availableRows.map((row) => row.status))].sort();
    const detailPreview = (row: HistoryRow) => Object.entries(row.details)
        .filter(([, value]) => value !== null && value !== "")
        .slice(0, 3)
        .map(([key, value]) => `${key.replaceAll(/([A-Z])/g, " $1")}: ${
            typeof value === "object" ? JSON.stringify(value) : String(value)}`)
        .join(" · ");
    return <><PageTitle eyebrow="ROLE-SCOPED DATA EXPLORER" title="Explore historical records"
                        detail={`Visits, appointments and other authorized operational records available to ${role}.`} />
        <div className="history-presets" aria-label="Quick date ranges"><span>Quick range</span><button type="button" onClick={() => { setFrom(today); setTo(today); }}>Today</button><button type="button" onClick={() => { const start = new Date(`${today}T00:00:00Z`); start.setUTCDate(start.getUTCDate() - 6); setFrom(start.toISOString().slice(0, 10)); setTo(today); }}>7 days</button><button type="button" onClick={() => { setFrom(`${today.slice(0, 8)}01`); setTo(today); }}>This month</button></div>
        <div className="history-filter-panel glass-panel">
            <label>Dataset<select value={dataset} onChange={(event) => {
                setDataset(event.target.value as HistoryDataset); setStatus("");
            }}><HistoryDatasetOptions role={role} /></select></label>
            <label>From<input type="date" value={from} max={to} onChange={(event) => setFrom(event.target.value)} /></label>
            <label>To<input type="date" value={to} min={from} max={today} onChange={(event) => setTo(event.target.value)} /></label>
            <label>Status<select value={status} onChange={(event) => setStatus(event.target.value)}><option value="">All statuses</option>{statuses.map((value) => <option key={value}>{value}</option>)}</select></label>
            <label className="history-query">Search<input value={query} onChange={(event) => setQuery(event.target.value)} placeholder="Reference, visitor, host or purpose" /></label>
            <div className="history-filter-actions"><button type="button" className="button button-secondary"
                                                            onClick={onRefresh}><RotateCcw size={15} />Refresh records</button></div>
        </div>
        <article className="panel glass-panel records-panel"><div className="panel-heading"><div><span>AUTHORIZED HISTORY</span><h2>{historyDatasetLabels[dataset]}</h2><p>Showing records from {from} through {to}. Role restrictions are preserved.</p></div><b>{matches.length}</b></div>
            <div className="records-table-wrap"><table className="records-table history-table"><thead><tr><th>Occurred</th><th>Record</th><th>Context</th><th>Status</th></tr></thead><tbody>{matches.map((row) => <tr key={`${row.dataset}:${row.id}`}><td><strong>{formatOfficeDate(row.occurredAt)}</strong><small>{formatOfficeTime(row.occurredAt)}</small></td><td><strong>{row.primaryLabel}</strong><code>{row.id}</code></td><td><strong>{row.secondaryLabel}</strong><small>{detailPreview(row) || "No additional detail"}</small></td><td><span className="business-status">{row.status.replaceAll("_", " ")}</span></td></tr>)}{matches.length === 0 && <tr><td colSpan={4}><div className="empty-state table-empty"><FileClock size={28} /><strong>No {historyDatasetLabels[dataset].toLowerCase()} match these filters</strong><small>Try a wider date range, choose another dataset, or clear the status and search filters.</small></div></td></tr>}</tbody></table></div>
        </article></>;
}

