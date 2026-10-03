import type { WorkboardCriteria, WorkboardItem, WorkboardPage, WorkboardPreferences, WorkboardQuickFilter, WorkboardScope } from "../types/workboard";
import { officeDateFromInstant } from "./work-utils";

export const defaultCriteria: WorkboardCriteria = { scope: "TODAY", quickFilter: "ALL", query: "", status: "ALL", branch: "", sort: "DUE_DATE" };
export const defaultPreferences: WorkboardPreferences = { revision: 0, layout: "LIST", density: "COMPACT", savedFilters: [] };
export const periodLabels: Record<WorkboardScope, string> = { TODAY: "Today", CARRY_FORWARD: "Open carry-forward", HISTORY: "History", ALL: "All worksheets" };
export const quickFilterLabels: Record<WorkboardQuickFilter, string> = { ALL: "All actions", MY_ACTIONS: "My actions", DUE_TODAY: "Due today",
  OVERDUE_DELIVERY: "Overdue delivery", AWAITING_MY_REVIEW: "Awaiting my review", RETURNED_FOR_REWORK: "Returned for rework" };
export function workActorName(value: string) { return value.replace(/^ROLE_/, "").replaceAll("_", " ").replace(/\b[A-Z]{2,}\b/g, (word) => ["HR", "CEO"].includes(word) ? word : word[0] + word.slice(1).toLowerCase()); }

// Demo-only: production counts, eligibility, dates and pagination always come from the server.
export function previewWorkboardPage(items: WorkboardItem[], criteria: WorkboardCriteria, page: number, size: number, today: string, own: boolean): WorkboardPage {
    const scopes = Object.keys(periodLabels) as WorkboardScope[], quicks = Object.keys(quickFilterLabels) as WorkboardQuickFilter[];
    const inScope = (item: WorkboardItem, scope: WorkboardScope) => {
        const isToday = officeDateFromInstant(item.createdAt) === today;
        const outstanding = item.lane !== "CLOSED" || item.allowedActions.length > 0;
        return scope === "ALL" || (scope === "TODAY" ? isToday : !isToday && (scope === "CARRY_FORWARD" ? outstanding : !outstanding));
    };
    const inQuick = (item: WorkboardItem, quick: WorkboardQuickFilter) => quick === "ALL"
        || (quick === "MY_ACTIONS" && item.allowedActions.length > 0)
        || (quick === "DUE_TODAY" && item.dueDate === today)
        || (quick === "OVERDUE_DELIVERY" && item.dueDate < today && ["ASSIGNED", "IN_PROGRESS", "CHANGES_REQUESTED", "INSIGHT_REWORK_REQUESTED"].includes(item.status))
        || (quick === "AWAITING_MY_REVIEW" && item.allowedActions.some((action) => ["approve", "request-changes", "insight-rework", "hr-audit", "hr-rework", "open-oversight"].includes(action)))
        || (quick === "RETURNED_FOR_REWORK" && item.lane === "REWORK");
    const base = items.filter((item) => (criteria.status === "ALL" || item.status === criteria.status)
        && (!criteria.branch || item.departmentBranch === criteria.branch)
        && `${item.title} ${item.description} ${item.departmentBranch} ${item.assigneeName}`.toLowerCase().includes(criteria.query.toLowerCase()));
    const period = base.filter((item) => inScope(item, criteria.scope));
    const filtered = period.filter((item) => inQuick(item, criteria.quickFilter)).sort((a, b) => {
        const compare = criteria.sort === "DUE_DATE" ? a.dueDate.localeCompare(b.dueDate)
            : criteria.sort === "UPDATED_AT" ? b.updatedAt.localeCompare(a.updatedAt)
                : criteria.sort === "TITLE" ? a.title.localeCompare(b.title) : 0;
        return compare || a.id.localeCompare(b.id);
    });
    return { policyVersion: "workboard.v1", generatedAt: new Date().toISOString(), officeDate: today, officeZone: "Asia/Kolkata",
        scope: own ? "OWN" : "DEPARTMENT", departmentId: own ? null : items[0]?.departmentId ?? null, number: page, size,
        totalElements: filtered.length, totalPages: Math.ceil(filtered.length / size),
        counts: { scopes: Object.fromEntries(scopes.map((scope) => [scope, base.filter((item) => inScope(item, scope)).length])) as WorkboardPage["counts"]["scopes"],
            quickFilters: Object.fromEntries(quicks.map((quick) => [quick, period.filter((item) => inQuick(item, quick)).length])) as WorkboardPage["counts"]["quickFilters"] },
        laneCounts: { DELIVERY: filtered.filter((item) => item.lane === "DELIVERY").length, REVIEW: filtered.filter((item) => item.lane === "REVIEW").length,
            REWORK: filtered.filter((item) => item.lane === "REWORK").length, CLOSED: filtered.filter((item) => item.lane === "CLOSED").length },
        items: filtered.slice(page * size, (page + 1) * size) };
}

export function previewPreferenceKey(account: string) { return `brainserve.demo.workboard.preferences.v1:${encodeURIComponent(account.toLowerCase())}`; }
export function validWorkboardPreferences(value: unknown): value is WorkboardPreferences {
    if (!value || typeof value !== "object") return false;
    const record = value as WorkboardPreferences;
    if (Object.keys(record).some((key) => !["revision", "layout", "density", "savedFilters"].includes(key))) return false;
    if (!Number.isSafeInteger(record.revision) || record.revision < 0 || !["LIST", "BOARD"].includes(record.layout)
        || !["COMPACT", "COMFORTABLE"].includes(record.density) || !Array.isArray(record.savedFilters) || record.savedFilters.length > 10) return false;
    const ids = new Set<string>();
    return record.savedFilters.every((filter) => {
        if (!filter || typeof filter !== "object" || typeof filter.id !== "string" || !/^[A-Za-z0-9_-]{1,64}$/.test(filter.id) || ids.has(filter.id)
            || Object.keys(filter).some((key) => !["id", "name", "scope", "quickFilter", "query", "status", "branch", "sort"].includes(key))
            || typeof filter.name !== "string" || !filter.name.trim() || filter.name.length > 60
            || !Object.hasOwn(periodLabels, filter.scope) || !Object.hasOwn(quickFilterLabels, filter.quickFilter)
            || typeof filter.query !== "string" || filter.query.length > 120 || typeof filter.branch !== "string" || filter.branch.length > 170
            || !["ALL", "ASSIGNED", "IN_PROGRESS", "COMPLETED", "CHANGES_REQUESTED", "INSIGHT_REWORK_REQUESTED", "APPROVED", "ACKNOWLEDGED"].includes(filter.status)
            || !["DUE_DATE", "UPDATED_AT", "TITLE", "PRIORITY"].includes(filter.sort)) return false;
        ids.add(filter.id); return true;
    });
}
export function readPreviewPreferences(account: string): WorkboardPreferences {
    try { const value: unknown = JSON.parse(localStorage.getItem(previewPreferenceKey(account)) ?? "null"); return validWorkboardPreferences(value) ? value : defaultPreferences; }
    catch { return defaultPreferences; }
}
export function writePreviewPreferences(account: string, value: WorkboardPreferences): WorkboardPreferences {
    if (!validWorkboardPreferences(value)) throw new Error("Workboard preferences are invalid.");
    const current = readPreviewPreferences(account);
    if (value.revision !== current.revision) throw new Error("Preferences changed in another window. Reload preferences before saving.");
    const updated = { ...value, revision: current.revision + 1 };
    localStorage.setItem(previewPreferenceKey(account), JSON.stringify(updated));
    return updated;
}
