"use client";

import { useEffect, useState } from "react";
import { brainServeApi } from "./lib/api";
import styles from "./reports-overview.module.css";

const colors = ["#8d0a20", "#c04763", "#496985", "#367563", "#92641f", "#73518b", "#63707d", "#ba7154"];
type Summary = Awaited<ReturnType<typeof brainServeApi.dashboard>>;
type VisitType = { type: string; total: number };
const today = () => new Intl.DateTimeFormat("en-CA", {
    timeZone: "Asia/Kolkata", year: "numeric", month: "2-digit", day: "2-digit",
}).format(new Date());

export default function ReportsOverview({ role, refreshKey }: { role: string; refreshKey: number }) {
    const [from, setFrom] = useState(today);
    const [to, setTo] = useState(today);
    const [range, setRange] = useState(() => ({ from: today(), to: today() }));
    const [revision, setRevision] = useState(0);
    const [summary, setSummary] = useState<Summary | null>(null);
    const [types, setTypes] = useState<VisitType[] | null>(null);
    const [error, setError] = useState("");
    const [loading, setLoading] = useState(true);
    useEffect(() => {
        let active = true;
        Promise.allSettled([brainServeApi.dashboard(range), brainServeApi.visitTypeCounts(range.from, range.to)])
            .then(([totals, mix]) => {
                if (!active) return;
                const errors: string[] = [];
                if (totals.status === "fulfilled") setSummary(totals.value);
                else errors.push("Report totals could not be refreshed.");
                if (mix.status === "fulfilled") setTypes(mix.value);
                else errors.push("Visit mix could not be refreshed.");
                setError(errors.join(" "));
                setLoading(false);
            });
        return () => { active = false; };
    }, [range, revision, refreshKey]);
    const apply = () => {
        // Clear old-period values; a failed request must not label old data as the new range.
        setSummary(null); setTypes(null); setError(""); setLoading(true);
        setRange({ from, to });
    };
    const total = (types ?? []).reduce((sum, item) => sum + item.total, 0);
    const workforce = !["Security", "Reception"].includes(role);
    const cards = summary ? [
        ["Scheduled visits", summary.scheduledVisits], ["Arrived", summary.arrivedVisits],
        ["Awaiting approval", summary.awaitingApproval], ["Active visits", summary.activeVisits],
        ["Completed visits", summary.completedVisits], ["Cancelled visits", summary.cancelledVisits],
        ["Rejected visits", summary.rejectedVisits],
        ...(workforce ? [["Active employees", summary.activeEmployees], ["Employee profiles", summary.totalEmployees]] : []),
    ] : [];
    return <section aria-label="Reports overview" className={styles.overview}>
        <header><span>REPORTING & INSIGHTS</span><h1>Current operational picture</h1>
            <p>Appointment metrics and visit mix for your authorized {summary?.scope === "DEPARTMENT" ? "department" : summary?.scope === "PERSONAL" ? "personal" : "reporting"} scope.</p></header>
        <form className={styles.filters} onSubmit={(event) => { event.preventDefault(); apply(); }}>
            <label>Report from<input type="date" value={from} max={to} required onChange={(event) => setFrom(event.target.value)} /></label>
            <label>Report to<input type="date" value={to} min={from} max={today()} required onChange={(event) => setTo(event.target.value)} /></label>
            <button className="button button-primary" disabled={loading}>Apply report range</button>
            <button className="button button-secondary" type="button" disabled={loading} onClick={() => { setLoading(true); setRevision((n) => n + 1); }}>Refresh report</button>
        </form>
        {loading && <p role="status">Loading report metrics…</p>}
        {error && <p role="alert">{error} {summary || types ? "Last loaded values are retained for the applied range." : "Retry to load your report."}</p>}
        <p>Applied range: {range.from} through {range.to}{summary?.generatedAt ? ` · Totals generated ${new Date(summary.generatedAt).toLocaleTimeString()}` : ""}</p>
        <div className={styles.metrics}>{cards.map(([label, value]) => <article className="panel glass-panel" key={label}>
            <span>{label}</span><strong>{typeof value === "number" ? value.toLocaleString() : "—"}</strong>
        </article>)}</div>
        <article className={`panel glass-panel ${styles.mix}`}><h2>Visit mix</h2>
            {types === null ? <p>Visit mix is unavailable until its report loads.</p> : total === 0 ? <p>No visits in this range.</p> : <div className={styles.chartRow}>
                <svg viewBox="0 0 120 120" role="img" aria-label={`Visit mix: ${total} scheduled visits`}>
                    {types.map((item, index) => {
                        const percent = item.total / total * 100;
                        const start = types.slice(0, index).reduce((sum, previous) => sum + previous.total, 0) / total * 100;
                        return <circle key={item.type} cx="60" cy="60" r="44" fill="none" stroke={colors[index % colors.length]}
                                       strokeWidth="16" pathLength="100" strokeDasharray={`${percent} ${100 - percent}`} strokeDashoffset={-start}
                                       transform="rotate(-90 60 60)" />;
                    })}
                    <text x="60" y="59" textAnchor="middle">{total}</text><text className={styles.totalLabel} x="60" y="73" textAnchor="middle">visits</text>
                </svg>
                <ul>{types.map((item, index) => <li key={item.type}><i style={{ background: colors[index % colors.length] }} />
                    <span>{item.type.replaceAll("_", " ").toLowerCase()}</span><strong>{item.total} · {(item.total / total * 100).toFixed(1)}%</strong></li>)}</ul>
            </div>}
        </article>
    </section>;
}
