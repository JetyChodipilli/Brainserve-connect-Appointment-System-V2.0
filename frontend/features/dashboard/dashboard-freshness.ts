export type DashboardFreshnessMetadata = {
    sourceRefreshedAt?: string | null;
    sourceGeneration?: number | null;
    freshness?: "FRESH" | "STALE" | "UNKNOWN";
    freshUntil?: string | null;
    generatedAt?: string;
    sourceType?: "SUMMARY" | "LIVE";
    metricsLoadState?: "loading" | "ready" | "error";
};

export function dashboardFreshness(summary: DashboardFreshnessMetadata, now = Date.now()) {
    const sourceTime = summary.sourceRefreshedAt ? Date.parse(summary.sourceRefreshedAt) : NaN;
    const expires = summary.freshUntil ? Date.parse(summary.freshUntil) : NaN;
    const known = Number.isFinite(sourceTime) && sourceTime <= now && summary.sourceGeneration != null;
    const stamp = known ? summary.sourceRefreshedAt! : null;
    if (summary.metricsLoadState === "loading") {
        return { state: "loading", label: stamp ? "Refreshing dashboard · last values retained" : "Loading dashboard metrics", stamp };
    }
    if (summary.metricsLoadState === "error") {
        return { state: "error", label: stamp ? "Refresh unavailable · last values retained" : "Dashboard metrics unavailable", stamp };
    }
    if (!known || summary.freshness === "UNKNOWN" || !summary.freshness || !Number.isFinite(expires)) {
        return { state: "unknown", label: "Dashboard freshness unknown", stamp };
    }
    if (summary.freshness === "STALE" || now >= expires) {
        return { state: "stale", label: "Dashboard may be out of date", stamp };
    }
    return { state: "fresh", label: "Dashboard up to date", stamp };
}
