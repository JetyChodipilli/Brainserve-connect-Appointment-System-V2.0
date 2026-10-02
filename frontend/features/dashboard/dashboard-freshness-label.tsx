"use client";

import { useEffect, useState } from "react";
import { Clock3 } from "lucide-react";
import { dashboardFreshness, type DashboardFreshnessMetadata } from "./dashboard-freshness";
import styles from "./dashboard-freshness.module.css";

export function DashboardFreshness({ summary }: { summary: DashboardFreshnessMetadata }) {
    const [, setNow] = useState(() => Date.now());
    useEffect(() => {
        // An open or disconnected tab must age out of "up to date" on its own.
        const timer = window.setInterval(() => setNow(Date.now()), 15_000);
        return () => window.clearInterval(timer);
    }, []);
    const status = dashboardFreshness(summary);
    return <p className={styles.status} role="status" aria-atomic="true" data-freshness={status.state}>
        <Clock3 size={15} aria-hidden="true" />
        <span>{status.label}{status.stamp && <> · {summary.sourceType === "LIVE" ? "Data checked" : "Source refreshed"}{" "}
            <time dateTime={status.stamp}>{new Date(status.stamp).toLocaleString("en-IN", {
                day: "numeric", month: "short", hour: "2-digit", minute: "2-digit", timeZone: "Asia/Kolkata",
            })} IST</time></>}</span>
    </p>;
}
