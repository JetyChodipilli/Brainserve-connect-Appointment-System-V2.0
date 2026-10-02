import assert from "node:assert/strict";
import test from "node:test";
import { dashboardFreshness } from "../features/dashboard/dashboard-freshness.ts";

const now = Date.parse("2026-09-30T12:00:00Z");
const fresh = { freshness: "FRESH", sourceGeneration: 0, sourceRefreshedAt: "2026-09-30T11:59:00Z",
    freshUntil: "2026-09-30T12:01:00Z", generatedAt: "2026-09-30T12:00:00Z" };

test("source freshness accepts generation zero and expires without another network response", () => {
    assert.equal(dashboardFreshness(fresh, now).state, "fresh");
    assert.equal(dashboardFreshness(fresh, now + 60_000).state, "stale");
});
test("a new response timestamp never makes a stale or missing source fresh", () => {
    assert.equal(dashboardFreshness({ ...fresh, freshness: "STALE" }, now).state, "stale");
    assert.equal(dashboardFreshness({ generatedAt: fresh.generatedAt }, now).state, "unknown");
    assert.equal(dashboardFreshness({ ...fresh, sourceRefreshedAt: null }, now).state, "unknown");
    assert.equal(dashboardFreshness({ ...fresh, sourceGeneration: null }, now).state, "unknown");
});
test("partial or malformed source metadata stays unknown", () => {
    for (const override of [{ sourceRefreshedAt: "invalid" }, { freshUntil: "invalid" },
        { sourceRefreshedAt: "2026-10-01T00:00:00Z" }, { freshness: "UNKNOWN" }]) {
        assert.equal(dashboardFreshness({ ...fresh, ...override }, now).state, "unknown");
    }
});
test("refresh loading and errors honestly retain source time instead of claiming fresh data", () => {
    const loading = dashboardFreshness({ ...fresh, metricsLoadState: "loading" }, now);
    assert.equal(loading.state, "loading");
    assert.equal(loading.stamp, fresh.sourceRefreshedAt);
    assert.match(loading.label, /last values retained/);
    const error = dashboardFreshness({ ...fresh, metricsLoadState: "error" }, now);
    assert.equal(error.state, "error");
    assert.match(error.label, /Refresh unavailable/);
    assert.match(dashboardFreshness({ metricsLoadState: "error" }, now).label, /metrics unavailable/);
    assert.match(dashboardFreshness({ metricsLoadState: "loading" }, now).label, /Loading/);
});

// Execute the actual loading hook with controlled effects and API promises. This
// observes state transitions without needing a browser or matching source text.
import { readFileSync } from "node:fs";
import vm from "node:vm";
import ts from "typescript";
const hookSource = ts.transpileModule(readFileSync(new URL("../hooks/workspace/use-workspace-data.ts", import.meta.url), "utf8"), {
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS },
}).outputText;
class ApiError extends Error {
    constructor(status, problem) { super(problem.detail); this.status = status; this.problem = problem; }
}
function workspaceHarness() {
    let department = "department-a", nextSummary, metrics, completed, cleanup;
    const refs = [];
    let refIndex = 0;
    const api = {
        employees: async () => ({ content: [] }),
        visibleDepartments: async () => [{ id: department, name: department }],
        appointments: async () => ({ content: [] }),
        dashboard: async () => nextSummary(),
    };
    const exports = {};
    vm.runInNewContext(hookSource, { exports, require: (name) => {
        if (name === "react") return {
            useRef: (value) => refs[refIndex++] ??= { current: value },
            useEffect: (effect) => { cleanup?.(); cleanup = effect(); },
        };
        if (name.includes("brainserve-api")) return { ApiError, brainServeApi: api, isBackendConfigured: true };
        if (name.includes("utils/errors")) return { fail: (message) => { throw new Error(message); }, isConnectivityFailure: (error) => error.status >= 500 };
        return {};
    } });
    const setters = new Proxy({}, { get: (_target, name) => (value) => {
        if (name === "setMetrics") metrics = typeof value === "function" ? value(metrics) : value;
        if (name === "setLastLiveUpdate") completed();
    } });
    return {
        metrics: () => metrics,
        department: (value) => { department = value; },
        async load(result) {
            nextSummary = result;
            const done = new Promise((resolve) => { completed = resolve; });
            refIndex = 0;
            exports.useWorkspaceData(new Proxy({ role: "Employee", workspaceRevision: 1 }, {
                get: (target, name) => name in target ? target[name] : setters[name],
            }));
            await done;
        },
        summary: () => ({ ...fresh, departmentId: department, awaitingApproval: 7, activeVisits: 3,
            visitorsInside: 0, totalEmployees: 1, activeEmployees: 1, arrivedVisits: 2 }),
    };
}

test("background dashboard failure retains values within the current department", { timeout: 2000 }, async () => {
    const workspace = workspaceHarness();
    await workspace.load(workspace.summary);
    assert.equal(workspace.metrics().awaitingApproval, 7);
    await workspace.load(() => { throw new ApiError(503, { detail: "Temporarily unavailable" }); });
    assert.equal(workspace.metrics().awaitingApproval, 7);
    assert.equal(workspace.metrics().metricsLoadState, "error");
});

test("department transition clears prior metrics before a failing refresh can retain them", { timeout: 2000 }, async () => {
    const workspace = workspaceHarness();
    await workspace.load(workspace.summary);
    workspace.department("department-b");
    await workspace.load(() => {
        assert.equal(workspace.metrics().awaitingApproval, 0);
        assert.equal(workspace.metrics().metricsLoadState, "loading");
        throw new ApiError(503, { detail: "Temporarily unavailable" });
    });
    assert.equal(workspace.metrics().awaitingApproval, 0);
    assert.equal(workspace.metrics().freshness, "UNKNOWN");
    assert.equal(workspace.metrics().sourceRefreshedAt, undefined);
});

test("authorization loss clears loaded dashboard values", { timeout: 2000 }, async () => {
    const workspace = workspaceHarness();
    await workspace.load(workspace.summary);
    await workspace.load(() => { throw new ApiError(403, { detail: "Reporting permission revoked" }); });
    assert.equal(workspace.metrics().activeVisits, 0);
    assert.equal(workspace.metrics().metricsLoadState, "error");
    assert.equal(workspace.metrics().sourceGeneration, undefined);
});

test("a response from a changed reporting scope is rejected instead of relabelled", { timeout: 2000 }, async () => {
    const workspace = workspaceHarness();
    await workspace.load(workspace.summary);
    await workspace.load(() => ({ ...workspace.summary(), departmentId: "unexpected-department", awaitingApproval: 99 }));
    assert.equal(workspace.metrics().awaitingApproval, 0);
    assert.equal(workspace.metrics().metricsLoadState, "error");
});
