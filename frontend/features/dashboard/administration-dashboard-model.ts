import { dashboardFreshness } from "./dashboard-freshness";

export type DashboardRole = "CEO" | "System Admin";
export type DashboardPeriod = "TODAY" | "YESTERDAY" | "LAST_7_DAYS" | "THIS_MONTH" | "PREVIOUS_MONTH" | "CUSTOM";
export type DashboardRange = { period: DashboardPeriod; from?: string; to?: string };
export type MetricCard = {
    id: string; title: string; definition: string;
    kind: "FLOW" | "STOCK" | "COHORT" | "DISTRIBUTION" | "RATE" | "CUMULATIVE";
    clock: "PERIOD" | "NOW" | "HISTORY"; unit: "COUNT" | "SECONDS" | "PERCENT" | "STATUS";
    state: "AVAILABLE" | "UNAVAILABLE" | "RESTRICTED" | "NOT_APPLICABLE";
    value: number | null; displayValue: string | null; reason: string | null;
    sourceRefreshedAt: string | null; freshUntil: string | null; freshness: "FRESH" | "STALE" | "UNKNOWN";
    sampleSize: number | null; eligibleCount: number | null; coveragePercent: number | null; excludedCount: number | null;
    drillDownAvailable: boolean; comparison: null;
};
export type CoverageItem = { id: string; title: string; state: "AVAILABLE" | "PARTIAL" | "UNAVAILABLE";
    since: string | null; reason: string };
export type DashboardCards = {
    metricVersion: "sprint3.v1"; role: "ROLE_CEO" | "ROLE_SYSTEM_ADMIN"; scope: "COMPANY"; departmentId: null;
    from: string; to: string; officeZone: string; asOf: string; sourceGeneration: number | null;
    cards: MetricCard[]; supplementary: MetricCard[]; coverage: CoverageItem[];
};
export type DashboardRecords = {
    metricId: string; metricVersion: "sprint3.v1"; state: MetricCard["state"]; reason: string | null;
    asOf: string; from: string; to: string; page: number; size: number; totalElements: number; totalPages: number;
    items: Array<{ id: string; label: string; detail: string; status: string | null; occurredAt: string | null; kind: string }>;
};

export const dashboardPeriods: Array<[DashboardPeriod, string]> = [
    ["TODAY", "Today"], ["YESTERDAY", "Yesterday"], ["LAST_7_DAYS", "Last 7 days"],
    ["THIS_MONTH", "This month"], ["PREVIOUS_MONTH", "Previous month"], ["CUSTOM", "Custom dates"],
];
export const dashboardFirstIds = {
    "System Admin": ["OPS01", "OPS04", "IAM03", "VIS08", "NTF03", "OPS07"],
    CEO: ["VIS02", "VIS05", "VIS07", "VIS09", "WORK03", "WORK07"],
} as const;

const catalogue: Record<string, [string, MetricCard["clock"], MetricCard["kind"], MetricCard["unit"]]> = {
    OPS01: ["Core workflow availability", "PERIOD", "RATE", "PERCENT"],
    OPS04: ["Critical dependencies", "NOW", "STOCK", "STATUS"],
    IAM03: ["Pending account approvals", "NOW", "STOCK", "COUNT"],
    VIS08: ["Overdue approval stages", "NOW", "STOCK", "COUNT"],
    NTF03: ["Unresolved dead-letter jobs", "NOW", "STOCK", "COUNT"],
    OPS07: ["Verified backup age", "NOW", "STOCK", "SECONDS"],
    OPS08: ["Verified restore evidence", "NOW", "STOCK", "STATUS"],
    OPS09: ["Freshness and source coverage", "NOW", "STOCK", "STATUS"],
    VIS02: ["Distinct first arrivals", "PERIOD", "FLOW", "COUNT"],
    VIS05: ["Visitors currently inside", "NOW", "STOCK", "COUNT"],
    VIS07: ["Pending CEO appointment stages", "NOW", "STOCK", "COUNT"],
    VIS09: ["Check-in wait p95", "PERIOD", "DISTRIBUTION", "SECONDS"],
    WORK03: ["Overdue execution delivery", "NOW", "STOCK", "COUNT"],
    WORK07: ["Original commitment acceptance", "PERIOD", "COHORT", "PERCENT"],
    VIS14: ["Retained appointments", "HISTORY", "CUMULATIVE", "COUNT"],
};

export function unavailableCard(id: string, reason: string): MetricCard {
    const [title, clock, kind, unit] = catalogue[id];
    return { id, title, clock, kind, unit, definition: "Source measurement has not been returned.", state: "UNAVAILABLE",
        value: null, displayValue: null, reason, sourceRefreshedAt: null, freshUntil: null, freshness: "UNKNOWN",
        sampleSize: null, eligibleCount: null, coveragePercent: null, excludedCount: null, drillDownAvailable: false, comparison: null };
}

export function dashboardQuery(range: DashboardRange) {
    return new URLSearchParams(range.period === "CUSTOM"
        ? { period: range.period, from: range.from ?? "", to: range.to ?? "" } : { period: range.period });
}

export function validateDashboardDates(from: string, to: string): string | null {
    const valid = (date: string) => /^\d{4}-\d{2}-\d{2}$/.test(date)
        && Number.isFinite(Date.parse(date)) && new Date(date).toISOString().slice(0, 10) === date;
    if (!valid(from) || !valid(to)) return "Enter a valid start and end date.";
    if (to < from) return "End date must be on or after start date.";
    if ((Date.parse(to) - Date.parse(from)) / 86_400_000 + 1 > 366) return "Choose a range of 366 days or fewer.";
    return null;
}

export function validateDashboardScope(data: DashboardCards, role: DashboardRole, range: DashboardRange) {
    if (!data || data.metricVersion !== "sprint3.v1" || data.role !== (role === "CEO" ? "ROLE_CEO" : "ROLE_SYSTEM_ADMIN")
        || data.scope !== "COMPANY" || data.departmentId !== null || !Array.isArray(data.cards)
        || !Array.isArray(data.supplementary) || !Array.isArray(data.coverage)
        || !data.cards.every(validMetricCard) || !data.supplementary.every(validMetricCard)
        || !data.coverage.every((item) => item && typeof item.id === "string" && typeof item.title === "string"
            && typeof item.reason === "string" && ["AVAILABLE", "PARTIAL", "UNAVAILABLE"].includes(item.state))
        || typeof data.officeZone !== "string" || !Number.isFinite(Date.parse(data.asOf))
        || !(data.sourceGeneration === null || Number.isInteger(data.sourceGeneration) && data.sourceGeneration >= 0)
        || validateDashboardDates(data.from, data.to)
        || (range.period === "CUSTOM" && (data.from !== range.from || data.to !== range.to))) {
        throw new DashboardScopeError("Dashboard scope changed or its response is invalid. Refresh the current workspace.");
    }
    try { new Intl.DateTimeFormat("en", { timeZone: data.officeZone }); }
    catch { throw new DashboardScopeError("Dashboard office timezone is invalid. Refresh the current workspace."); }
}

export class DashboardScopeError extends Error {}

function validMetricCard(card: MetricCard) {
    const numberOrNull = (value: number | null) => value === null || typeof value === "number" && Number.isFinite(value);
    const stringOrNull = (value: string | null) => value === null || typeof value === "string";
    return card && typeof card.id === "string" && typeof card.title === "string" && typeof card.definition === "string"
        && ["FLOW", "STOCK", "COHORT", "DISTRIBUTION", "RATE", "CUMULATIVE"].includes(card.kind)
        && ["PERIOD", "NOW", "HISTORY"].includes(card.clock) && ["COUNT", "SECONDS", "PERCENT", "STATUS"].includes(card.unit)
        && ["AVAILABLE", "UNAVAILABLE", "RESTRICTED", "NOT_APPLICABLE"].includes(card.state)
        && ["FRESH", "STALE", "UNKNOWN"].includes(card.freshness) && typeof card.drillDownAvailable === "boolean"
        && [card.value, card.sampleSize, card.eligibleCount, card.coveragePercent, card.excludedCount].every(numberOrNull)
        && [card.displayValue, card.reason, card.sourceRefreshedAt, card.freshUntil].every(stringOrNull) && card.comparison === null;
}

export function validateDashboardRecords(result: DashboardRecords, metricId: string, range: DashboardRange, page: number) {
    if (!result || result.metricId !== metricId || result.metricVersion !== "sprint3.v1" || result.page !== page
        || !["AVAILABLE", "UNAVAILABLE", "RESTRICTED", "NOT_APPLICABLE"].includes(result.state)
        || !Array.isArray(result.items) || !result.items.every((item) => item && typeof item.id === "string"
            && typeof item.label === "string" && typeof item.detail === "string" && typeof item.kind === "string")
        || !Number.isInteger(result.totalElements) || result.totalElements < 0 || !Number.isInteger(result.totalPages) || result.totalPages < 0
        || result.size !== 50 || !Number.isFinite(Date.parse(result.asOf)) || validateDashboardDates(result.from, result.to)
        || (range.period === "CUSTOM" && (result.from !== range.from || result.to !== range.to))) {
        throw new DashboardScopeError("The record scope changed or its response is invalid. Close this dialog and refresh the dashboard.");
    }
}

export function metricDisplay(card: MetricCard) {
    if (card.state !== "AVAILABLE") return { UNAVAILABLE: "Unavailable", RESTRICTED: "Restricted", NOT_APPLICABLE: "Not applicable" }[card.state];
    if (card.displayValue != null) return card.displayValue;
    if (card.value == null || !Number.isFinite(card.value)) return "Unavailable";
    return `${new Intl.NumberFormat("en-IN", { maximumFractionDigits: 2 }).format(card.value)}${card.unit === "SECONDS" ? " s" : card.unit === "PERCENT" ? "%" : ""}`;
}

export function metricFreshness(card: MetricCard, sourceGeneration: number | null, now: number, retained = false) {
    const status = dashboardFreshness({ ...card, sourceGeneration }, now);
    return retained && card.state === "AVAILABLE" ? { ...status, state: "stale", label: "Stale · last received value" } : status;
}

export function dashboardTimestamp(stamp: string | null, zone: string) {
    if (!stamp || !Number.isFinite(Date.parse(stamp))) return "Unknown";
    try {
        return new Date(stamp).toLocaleString("en-IN", { timeZone: zone, day: "numeric", month: "short", year: "numeric", hour: "2-digit", minute: "2-digit", second: "2-digit", hour12: false });
    } catch { return new Date(stamp).toISOString(); }
}
