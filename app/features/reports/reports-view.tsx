"use client";

import { isBackendConfigured } from "../../lib/api";
import ReportsOverview from "../../reports-overview";
import { type AccessRecord, type Appointment, type DashboardMetrics, type Role } from "../../shared/types/workspace";
import { LegacyExploreRecordsView } from "./legacy-explore-records-view";
import { LegacyReportsView } from "./legacy-reports-view";
import { ScalableReportsView } from "./scalable-reports-view";
import { FileClock, Search } from "lucide-react";
import { useState } from "react";

export function ReportsView(props: { role: Role; metrics: DashboardMetrics; appointments: Appointment[];
    accessRecords: AccessRecord[]; refreshKey: number; onRefresh: () => void }) {
    const [workspace, setWorkspace] = useState<"overview" | "explore">("overview");

    return <>
        <nav className="report-workspace-nav" aria-label="Reports navigation">
            <button type="button" className={workspace === "overview" ? "active" : ""} onClick={() => setWorkspace("overview")}>
                <FileClock size={17} /><span><strong>Reports overview</strong><small>Current operational totals</small></span>
            </button>
            <button type="button" className={workspace === "explore" ? "active" : ""} onClick={() => setWorkspace("explore")}>
                <Search size={17} /><span><strong>Explore Records</strong><small>Previous, monthly and custom-range data</small></span>
            </button>
        </nav>
        {workspace === "overview"
            ? (isBackendConfigured ? <ReportsOverview role={props.role} refreshKey={props.refreshKey} /> : <LegacyReportsView {...props} />)
            : isBackendConfigured ? <ScalableReportsView role={props.role} refreshKey={props.refreshKey} /> : <LegacyExploreRecordsView role={props.role} appointments={props.appointments}
                                                                                                                                        accessRecords={props.accessRecords} onRefresh={props.onRefresh} />}
    </>;
}

