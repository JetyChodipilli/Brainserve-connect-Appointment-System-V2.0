import assert from "node:assert/strict";
import test from "node:test";
import { readFileSync } from "node:fs";
import { createRequire } from "node:module";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import vm from "node:vm";
import ts from "typescript";
import React from "react";
import { renderToStaticMarkup } from "react-dom/server";

const nativeRequire = createRequire(import.meta.url);
const root = fileURLToPath(new URL("../", import.meta.url));
// Execute production modules; only platform boundaries are replaced for controlled requests/effects.
function modules(overrides = {}) {
    const cache = new Map();
    return function load(path) {
        const filename = resolve(root, path);
        if (cache.has(filename)) return cache.get(filename);
        const exports = {};
        cache.set(filename, exports);
        const source = ts.transpileModule(readFileSync(filename, "utf8"), { compilerOptions: {
            target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS, jsx: ts.JsxEmit.ReactJSX,
            esModuleInterop: true,
        } }).outputText;
        vm.runInNewContext(source, { exports, process: { env: overrides.env ?? {} }, AbortController, URLSearchParams, Error,
            Intl, Date, window: overrides.window, document: overrides.document, require(name) {
                if (name === "react" && overrides.react) return overrides.react;
                if (name.includes("lib/api-client") && overrides.api) return overrides.api;
                if (name.endsWith("use-dashboard-resource") && overrides.resource) return { useDashboardResource: overrides.resource };
                if (name.endsWith(".module.css")) return { __esModule: true, default: new Proxy({}, { get: (_target, key) => key }) };
                if (!name.startsWith(".")) return nativeRequire(name);
                const target = resolve(dirname(filename), name);
                return load(target + (target.endsWith(".tsx") || target.endsWith(".ts") ? "" :
                    (() => { try { readFileSync(target + ".ts"); return ".ts"; } catch { return ".tsx"; } })()));
            } }, { filename });
        return exports;
    };
}
const load = modules();
const model = load("features/dashboard/administration-dashboard-model.ts");
const now = Date.parse("2026-10-02T12:00:00Z");
const range = { period: "CUSTOM", from: "2026-10-01", to: "2026-10-02" };
const card = (id = "VIS02", patch = {}) => ({ ...model.unavailableCard(id, "No source"), state: "AVAILABLE", value: 0,
    reason: null, sourceRefreshedAt: "2026-10-02T11:59:55Z", freshUntil: "2026-10-02T12:00:10Z", freshness: "FRESH", ...patch });
const dashboard = (patch = {}) => ({ metricVersion: "sprint3.v1", role: "ROLE_CEO", scope: "COMPANY", departmentId: null,
    from: range.from, to: range.to, officeZone: "Asia/Kolkata", asOf: "2026-10-02T12:00:00Z", sourceGeneration: 0,
    cards: model.dashboardFirstIds.CEO.map((id) => card(id)), supplementary: [card("VIS14")], coverage: [], ...patch });

test("date validation uses real dates and the inclusive 366-day bound", () => {
    assert.equal(model.validateDashboardDates("2024-01-01", "2024-12-31"), null);
    assert.match(model.validateDashboardDates("2024-01-01", "2025-01-01"), /366/);
    assert.match(model.validateDashboardDates("2026-02-30", "2026-03-01"), /valid/);
    assert.match(model.validateDashboardDates("2026-10-03", "2026-10-02"), /on or after/);
    assert.match(model.validateDashboardDates("", ""), /valid/);
    assert.equal(model.dashboardQuery({ period: "TODAY", from: "ignored", to: "ignored" }).toString(), "period=TODAY");
});

test("dashboard rejects wrong role/company/custom dates and malformed measurement bodies", () => {
    assert.doesNotThrow(() => model.validateDashboardScope(dashboard(), "CEO", range));
    for (const response of [null, [], dashboard({ role: "ROLE_SYSTEM_ADMIN" }), dashboard({ scope: "DEPARTMENT" }),
        dashboard({ departmentId: "another" }), dashboard({ from: "2026-09-30" }), dashboard({ cards: [null] }),
        dashboard({ cards: [card("VIS02", { sampleSize: undefined })] }), dashboard({ officeZone: "Invalid/Zone" })]) {
        assert.throws(() => model.validateDashboardScope(response, "CEO", range), model.DashboardScopeError);
    }
});

test("missing, restricted and not-applicable measurements never become numeric zero", () => {
    assert.equal(model.metricDisplay(card()), "0");
    assert.equal(model.metricDisplay(card("VIS09")), "0 s");
    assert.equal(model.metricDisplay(card("WORK07")), "0%");
    for (const [state, expected] of [["UNAVAILABLE", "Unavailable"], ["RESTRICTED", "Restricted"], ["NOT_APPLICABLE", "Not applicable"]]) {
        assert.equal(model.metricDisplay(card("VIS02", { state, value: 777, displayValue: "777" })), expected);
    }
    assert.equal(model.metricDisplay(card("VIS02", { value: null })), "Unavailable");
});

test("card source generation zero is valid and freshness expires without a new response", () => {
    assert.equal(model.metricFreshness(card(), 0, now).state, "fresh");
    assert.equal(model.metricFreshness(card(), 0, now + 10_000).state, "stale");
    assert.equal(model.metricFreshness(card(), null, now).state, "unknown");
    assert.equal(model.metricFreshness(card(), 0, now, true).state, "stale");
});

test("record transport uses exact endpoints, source dates, bounded pages and caller cancellation", async () => {
    const calls = [];
    const { dashboardApi } = modules({ api: { apiRequest: async (path, init) => { calls.push({ path, init }); return {}; } } })("features/dashboard/api/dashboard-api.ts");
    const signal = new AbortController().signal;
    await dashboardApi.cards(range, signal);
    await dashboardApi.cardRecords("VIS/09", range, 10_000, 100, signal);
    assert.equal(calls[0].path, "/dashboard/cards?period=CUSTOM&from=2026-10-01&to=2026-10-02");
    assert.equal(calls[1].path, "/dashboard/cards/VIS%2F09/records?period=CUSTOM&from=2026-10-01&to=2026-10-02&page=10000&size=100");
    assert.equal(calls[1].init.signal, signal);
    assert.equal(calls[0].init.cache, "no-store");
    for (const [page, size] of [[-1, 50], [10_001, 50], [0.5, 50], [0, 0], [0, 101]]) {
        assert.throws(() => dashboardApi.cardRecords("VIS09", range, page, size), /bounds/);
    }
    assert.equal(calls.length, 2);
});

test("record response validation rejects another metric/page/range and malformed totals", () => {
    const records = { metricId: "VIS09", metricVersion: "sprint3.v1", state: "AVAILABLE", reason: null,
        asOf: dashboard().asOf, from: range.from, to: range.to, page: 2, size: 50, totalElements: 1, totalPages: 3,
        items: [{ id: "one", label: "Arrival", detail: "Raw wait 0 s", status: null, occurredAt: null, kind: "WAIT" }] };
    assert.doesNotThrow(() => model.validateDashboardRecords(records, "VIS09", range, 2));
    for (const patch of [{ metricId: "VIS05" }, { page: 1 }, { from: "2026-09-01" }, { items: [null] }, { totalElements: -1 }]) {
        assert.throws(() => model.validateDashboardRecords({ ...records, ...patch }, "VIS09", range, 2), model.DashboardScopeError);
    }
});

class ApiError extends Error {
    constructor(status, problem) { super(problem.detail); this.status = status; this.problem = problem; }
}
function deferred() {
    let resolve, reject;
    const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
    return { promise, resolve, reject };
}
const settle = async () => { await Promise.resolve(); await Promise.resolve(); await Promise.resolve(); };
function resourceHarness(configured = true) {
    const states = [], refs = [], effects = [], listeners = new Map();
    let stateIndex = 0, refIndex = 0, effectIndex = 0, pending = [];
    const react = {
        useState(initial) { const index = stateIndex++; if (!(index in states)) states[index] = typeof initial === "function" ? initial() : initial;
            return [states[index], (value) => { states[index] = typeof value === "function" ? value(states[index]) : value; }]; },
        useRef(initial) { const index = refIndex++; return refs[index] ??= { current: initial }; },
        useEffect(effect, dependencies) { const index = effectIndex++, old = effects[index];
            if (!old || dependencies.some((value, i) => !Object.is(value, old.dependencies[i]))) pending.push(() => {
                old?.cleanup?.(); effects[index] = { dependencies, cleanup: effect() };
            }); },
    };
    const window = { addEventListener(name, listener) { const list = listeners.get(name) ?? new Set(); list.add(listener); listeners.set(name, list); },
        removeEventListener(name, listener) { listeners.get(name)?.delete(listener); } };
    const harnessModules = modules({ react, window, api: { ApiError, isBackendConfigured: configured } });
    const executeResourceHook = harnessModules("features/dashboard/use-dashboard-resource.ts").useDashboardResource;
    return {
        render(scope, key, loader, commit = true) {
            stateIndex = refIndex = effectIndex = 0; pending = [];
            const value = executeResourceHook(scope, key, loader);
            if (commit) pending.forEach((effect) => effect());
            return value;
        },
        sessionEvent(name = "brainserve:auth-session-changed") { listeners.get(name)?.forEach((listener) => listener()); },
        scopeError() { return new (harnessModules("features/dashboard/administration-dashboard-model.ts").DashboardScopeError)("Scope changed"); },
        dispose() { effects.forEach((effect) => effect.cleanup?.()); },
    };
}

test("same-scope network errors retain explicitly stale values; permission and scope errors clear", async () => {
    const harness = resourceHarness(); let response = deferred(); const loader = () => response.promise;
    harness.render("CEO:a:TODAY", 0, loader); response.resolve({ count: 7 }); await settle();
    assert.equal(harness.render("CEO:a:TODAY", 0, loader).data.count, 7);
    response = deferred(); const refreshing = harness.render("CEO:a:TODAY", 1, loader);
    assert.equal(refreshing.phase, "loading"); assert.equal(refreshing.data.count, 7);
    response.reject(new ApiError(503, { detail: "Service unavailable" })); await settle();
    let result = harness.render("CEO:a:TODAY", 1, loader);
    assert.equal(result.data.count, 7); assert.equal(result.phase, "error"); assert.match(result.error, /unavailable/);
    response = deferred(); harness.render("CEO:a:TODAY", 2, loader); response.reject(new ApiError(403, { detail: "Permission removed" })); await settle();
    assert.equal(harness.render("CEO:a:TODAY", 2, loader).data, null);
    response = deferred(); harness.render("CEO:a:TODAY", 3, loader); response.resolve({ count: 8 }); await settle();
    assert.equal(harness.render("CEO:a:TODAY", 3, loader).data.count, 8);
    response = deferred(); harness.render("CEO:a:TODAY", 4, loader); response.reject(new ApiError(401, { detail: "Account inactive" })); await settle();
    assert.equal(harness.render("CEO:a:TODAY", 4, loader).data, null);
    response = deferred(); harness.render("CEO:a:TODAY", 5, loader); response.resolve({ count: 9 }); await settle();
    response = deferred(); harness.render("CEO:a:TODAY", 6, loader); response.reject(harness.scopeError()); await settle();
    assert.equal(harness.render("CEO:a:TODAY", 6, loader).data, null);
    harness.dispose();
});

for (const [reason, oldScope, newScope] of [
    ["period", "CEO:a:TODAY", "CEO:a:YESTERDAY"], ["account", "CEO:a:TODAY", "CEO:b:TODAY"],
    ["role", "CEO:a:TODAY", "System Admin:a:TODAY"], ["metric", "CEO:a:VIS09:0", "CEO:a:VIS02:0"],
    ["page", "CEO:a:VIS09:0", "CEO:a:VIS09:1"],
]) {
    test(`late ${reason} responses cannot paint the current view, including before effect cleanup`, async () => {
        const harness = resourceHarness(), first = deferred(), second = deferred(); let request = 0;
        const loader = () => (++request === 1 ? first : second).promise;
        harness.render(oldScope, 0, loader);
        assert.equal(harness.render(newScope, 0, loader, false).data, null);
        first.resolve({ value: "old" }); await settle();
        assert.equal(harness.render(newScope, 0, loader).data, null);
        second.resolve({ value: "new" }); await settle();
        assert.equal(harness.render(newScope, 0, loader).data.value, "new"); harness.dispose();
    });
}

test("an applied new period clears old results even when its request fails", async () => {
    const harness = resourceHarness(); let response = deferred(); const loader = () => response.promise;
    harness.render("CEO:a:TODAY", 0, loader); response.resolve({ value: 99 }); await settle();
    assert.equal(harness.render("CEO:a:TODAY", 0, loader).data.value, 99);
    response = deferred(); assert.equal(harness.render("CEO:a:YESTERDAY", 0, loader).data, null);
    response.reject(new ApiError(503, { detail: "Unavailable" })); await settle();
    assert.equal(harness.render("CEO:a:YESTERDAY", 0, loader).data, null); harness.dispose();
});

for (const event of ["brainserve:auth-session-changed", "brainserve:auth-session-expired"]) {
    test(`${event} clears loaded values and rejects pending results within the same email`, async () => {
        const harness = resourceHarness(); let response = deferred(); const loader = () => response.promise;
        harness.render("CEO:a:TODAY", 0, loader); response.resolve({ value: 99 }); await settle();
        assert.equal(harness.render("CEO:a:TODAY", 0, loader).data.value, 99);
        response = deferred(); harness.render("CEO:a:TODAY", 1, loader); harness.sessionEvent(event);
        response.resolve({ value: 100 }); await settle();
        assert.equal(harness.render("CEO:a:TODAY", 1, loader, false).data, null); harness.dispose();
    });
}

test("preview is visibly unavailable and never requests or substitutes measurements", () => {
    const harness = resourceHarness(false); let calls = 0; const loader = async () => { calls++; return { value: 1 }; };
    harness.render("CEO:a:TODAY", 0, loader);
    const result = harness.render("CEO:a:TODAY", 0, loader);
    assert.equal(calls, 0); assert.equal(result.data, null); assert.equal(result.phase, "error"); assert.match(result.error, /unavailable in preview/);
    harness.dispose();
});

test("server-rendered cards expose raw zero/sample/coverage, source clocks and distinct states", () => {
    const { MetricMeasurement } = load("features/dashboard/administration-dashboard.tsx");
    const wait = card("VIS09", { sampleSize: 1, eligibleCount: 4, coveragePercent: 25, excludedCount: 3,
        definition: "Raw valid security arrival to check-in wait, including zero.", drillDownAvailable: true });
    const html = renderToStaticMarkup(React.createElement(MetricMeasurement, { card: wait, data: dashboard(), now, onRecords() {} }));
    assert.match(html, />0 s<|>0 s<!--/); assert.match(html, /Raw observations n=1/); assert.match(html, /eligible 4/);
    assert.match(html, /coverage 25%/); assert.match(html, /excluded 3/); assert.match(html, /n &lt; 20/);
    assert.match(html, /Period · 2026-10-01 to 2026-10-02/); assert.match(html, /Source refreshed/); assert.match(html, /View records/);
    const live = renderToStaticMarkup(React.createElement(MetricMeasurement, { card: card("VIS05"), data: dashboard(), now }));
    assert.match(live, /Independent of selected dates/); assert.match(live, /Now · as of/);
    for (const state of ["UNAVAILABLE", "RESTRICTED", "NOT_APPLICABLE"]) {
        const html = renderToStaticMarkup(React.createElement(MetricMeasurement, { card: card("VIS09", { state, reason: "No eligible source", value: 888, displayValue: "888" }), data: dashboard(), now, onRecords() {} }));
        assert.doesNotMatch(html, /888|View records/); assert.match(html, /No eligible source/);
    }
});

test("SSR renders the role's six first cards and supplemental visitor/operating evidence without demo figures", () => {
    const { AdministrationDashboard, dashboardCardsEnabled } = load("features/dashboard/administration-dashboard.tsx");
    assert.equal(dashboardCardsEnabled, true);
    for (const role of ["CEO", "System Admin"]) {
        const html = renderToStaticMarkup(React.createElement(AdministrationDashboard, { role, userEmail: "user@test.invalid", refreshKey: 0, onNavigate() {} }));
        for (const id of model.dashboardFirstIds[role]) assert.match(html, new RegExp(`data-metric-id="${id}"`));
        assert.match(html, role === "CEO" ? /data-metric-id="VIS14"/ : /data-metric-id="OPS08"/);
        if (role === "System Admin") assert.match(html, /data-metric-id="OPS09"/);
        assert.match(html, /Company scope/); assert.match(html, /Dashboard period/); assert.match(html, /Unavailable|Loading/);
    }
    const disabled = modules({ env: { NEXT_PUBLIC_DASHBOARD_CARDS_ENABLED: "false" } })("features/dashboard/administration-dashboard.tsx");
    assert.equal(disabled.dashboardCardsEnabled, false);
});

test("Visitors keeps all five existing totals and never confirms loading/error summary zeros", () => {
    const { AdministrationDashboard } = modules({ resource: () => ({ scope: "current", data: dashboard(), phase: "ready", error: null }) })("features/dashboard/administration-dashboard.tsx");
    const summary = { activeVisits: 7, awaitingApproval: 6, visitorsInside: 0, activeEmployees: 5, totalEmployees: 8,
        arrivedVisits: 9, sourceGeneration: 0, sourceRefreshedAt: "2026-10-02T11:59:55Z", freshUntil: "2026-10-02T12:00:10Z", freshness: "FRESH", metricsLoadState: "ready" };
    const visitorTotals = (legacyMetrics) => renderToStaticMarkup(React.createElement(AdministrationDashboard, {
        role: "CEO", userEmail: "ceo@test.invalid", refreshKey: 0, onNavigate() {}, legacyMetrics,
    })).match(/<dl[\s\S]*?<\/dl>/)[0];
    const html = visitorTotals(summary);
    for (const label of ["Active visits", "In workflow", "Currently inside", "Active employees", "Arrived today", "8 total profiles"]) assert.match(html, new RegExp(label));
    assert.match(html, /<dd>0<\/dd>/);
    for (const metricsLoadState of ["loading", "error"]) {
        const html = visitorTotals({ ...summary, metricsLoadState, activeVisits: 0, arrivedVisits: 0 });
        assert.doesNotMatch(html, /<dd>0<\/dd>/); assert.equal((html.match(/<dd>Unavailable<\/dd>/g) ?? []).length, 5);
    }
    assert.doesNotMatch(visitorTotals({ ...summary, sourceGeneration: null }), /<dd>0<\/dd>/);
});
