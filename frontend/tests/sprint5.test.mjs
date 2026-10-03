import assert from "node:assert/strict";
import test from "node:test";
import { readFileSync } from "node:fs";
import { createRequire } from "node:module";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import vm from "node:vm";
import ts from "typescript";

const root = fileURLToPath(new URL("../", import.meta.url)), nativeRequire = createRequire(import.meta.url);
function modules(overrides = {}) {
    const cache = new Map();
    return function load(path) {
        const filename = resolve(root, path); if (cache.has(filename)) return cache.get(filename);
        const exports = {}; cache.set(filename, exports);
        const code = ts.transpileModule(readFileSync(filename, "utf8"), { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS, jsx: ts.JsxEmit.ReactJSX } }).outputText;
        vm.runInNewContext(code, { exports, process: { env: {} }, AbortController, URLSearchParams, TextDecoder, Date, Intl, Error, Map, Set, console,
            window: overrides.window, document: overrides.document, localStorage: overrides.window?.localStorage,
            require(name) {
                if (name === "react" && overrides.react) return overrides.react;
                if (name.includes("lib/api-client") && overrides.api) return overrides.api;
                if (name.endsWith("workboard-api") && overrides.workboardApi) return { workboardApi: overrides.workboardApi };
                if (name.endsWith("organization-api") && overrides.organizationApi) return { organizationApi: overrides.organizationApi };
                if (!name.startsWith(".")) return nativeRequire(name);
                const target = resolve(dirname(filename), name); let extension = ".ts";
                try { readFileSync(target + extension); } catch { extension = ".tsx"; }
                return load(target + extension);
            } }, { filename }); return exports;
    };
}
const model = modules()("features/workboard/utils/workboard-model.ts");
const item = (id, patch = {}) => ({ id, departmentId: "dep", employeeId: "emp", teamLeadUserId: "lead", assignedByUserId: "lead", assignedByRole: "TEAM_LEAD", assigneeRole: "EMPLOYEE",
    title: "Worksheet " + id, description: "Instructions %_ literal", departmentBranch: "Engineering", dueDate: "2026-10-03", status: "ASSIGNED", employeeUpdate: null, teamLeadReview: null,
    startedAt: null, completedAt: null, approvedAt: null, acknowledgedAt: null, createdAt: "2026-10-03T00:00:00Z", version: 2, assigneeName: "Scoped employee", auditStatus: "NOT_AUDITED",
    auditRecordId: null, auditVersion: null, updatedAt: "2026-10-03T00:00:00Z", submissionVersion: null, priority: null, blocked: null, allowedActions: ["start", "complete"], nextActor: "Employee", lane: "DELIVERY", ...patch });
const pageOf = (items) => model.previewWorkboardPage(items, model.defaultCriteria, 0, 20, "2026-10-03", true);
const plain = (value) => JSON.parse(JSON.stringify(value));
test("more than one page retains exact totals, stable priority ties, literal search and zero counts", () => {
    const items = Array.from({ length: 43 }, (_, index) => item(String(42 - index).padStart(3, "0")));
    const criteria = { ...model.defaultCriteria, sort: "PRIORITY", query: "%_" };
    const first = model.previewWorkboardPage(items, criteria, 0, 20, "2026-10-03", true), second = model.previewWorkboardPage(items, criteria, 1, 20, "2026-10-03", true);
    assert.equal(first.totalElements, 43); assert.equal(first.totalPages, 3); assert.equal(first.laneCounts.DELIVERY, 43);
    assert.equal(first.items[0].id, "000"); assert.equal(second.items[0].id, "020");
    assert.equal(first.counts.scopes.TODAY, 43); assert.equal(first.counts.scopes.HISTORY, 0);
    const absent = model.previewWorkboardPage(items, { ...criteria, query: "missing" }, 0, 20, "2026-10-03", true);
    assert.equal(absent.totalElements, 0); assert.equal(absent.counts.quickFilters.ALL, 0);
});
test("quick counts ignore the selected quick filter; closed acknowledgement stays in carry-forward", () => {
    const items = [item("delivery"), item("plan", { lane: "REWORK", allowedActions: ["insight-rework"] }), item("old-ack", { createdAt: "2026-10-01T00:00:00Z", auditStatus: "CEO_APPROVED", status: "APPROVED", lane: "REVIEW", allowedActions: ["acknowledge"] }), item("old-closed", { createdAt: "2026-10-01T00:00:00Z", lane: "CLOSED", allowedActions: [] })];
    const filtered = model.previewWorkboardPage(items, { ...model.defaultCriteria, quickFilter: "AWAITING_MY_REVIEW" }, 0, 20, "2026-10-03", true);
    assert.deepEqual(plain(filtered.items.map((value) => value.id)), ["plan"]); assert.equal(filtered.counts.quickFilters.ALL, 2); assert.equal(filtered.counts.quickFilters.DUE_TODAY, 2);
    assert.equal(filtered.counts.scopes.CARRY_FORWARD, 1); assert.equal(filtered.counts.scopes.HISTORY, 1);
    const overdue = model.previewWorkboardPage([item("insight-rework", { dueDate: "2026-10-02", status: "INSIGHT_REWORK_REQUESTED", lane: "REWORK" }), item("review", { dueDate: "2026-10-02", status: "COMPLETED", lane: "REVIEW" })], { ...model.defaultCriteria, quickFilter: "OVERDUE_DELIVERY" }, 0, 20, "2026-10-03", true);
    assert.deepEqual(plain(overdue.items.map((value) => value.id)), ["insight-rework"]); assert.equal(overdue.counts.quickFilters.OVERDUE_DELIVERY, 1);
});
test("demo preferences are isolated per account, use CAS and contain criteria only", () => {
    const stored = new Map(), window = { localStorage: { getItem: (key) => stored.get(key) ?? null, setItem: (key, value) => stored.set(key, value) } };
    const pref = modules({ window })("features/workboard/utils/workboard-model.ts");
    const saved = pref.writePreviewPreferences("Employee:first", { ...pref.defaultPreferences, layout: "BOARD", savedFilters: [{ ...pref.defaultCriteria, id: "first-filter", name: "Delivery" }] });
    assert.equal(saved.revision, 1); assert.equal(pref.readPreviewPreferences("Employee:first").layout, "BOARD"); assert.equal(pref.readPreviewPreferences("Employee:second").layout, "LIST");
    assert.throws(() => pref.writePreviewPreferences("Employee:first", pref.defaultPreferences), /changed/);
    assert.doesNotMatch([...stored.values()].join(""), /Scoped employee|Instructions|employeeId|departmentId/);
    for (const value of [{}, [], "bad", { ...pref.defaultPreferences, savedFilters: [null] }, { ...pref.defaultPreferences, revision: -1 },
        { ...pref.defaultPreferences, layout: "UNKNOWN" }, { ...pref.defaultPreferences, savedFilters: [{ id: "bad id", name: "x" }] },
        { ...pref.defaultPreferences, savedFilters: [{ ...pref.defaultCriteria, id: "valid", name: "Valid", employeeId: "must-not-persist" }] }]) {
        stored.set(pref.previewPreferenceKey("Employee:malformed"), JSON.stringify(value));
        assert.deepEqual(plain(pref.readPreviewPreferences("Employee:malformed")), plain(pref.defaultPreferences));
    }
});
test("read API uses explicit cancellable fresh queries and all mutations send observed versions", async () => {
    const calls = [], { workboardApi: api } = modules({ api: { apiRequest: async (path, init) => { calls.push({ path, init }); return {}; } } })("features/workboard/api/workboard-api.ts");
    const signal = new AbortController().signal;
    await api.workboard({ ...model.defaultCriteria, query: "a%_ &", branch: "Engineering" }, 2, 20, signal); await api.workboardDetail("task /1", signal); await api.workboardPreferences(signal);
    await api.saveWorkboardPreferences({ ...model.defaultPreferences, revision: 7 }, signal);
    await api.updateWorkTask("t", "complete", "done", 3); await api.acknowledgeWorkTask("t", 4); await api.reviseEmployeeWorkTaskRework("t", "corrected", 5);
    await api.auditWorkInsight("t", 6); await api.requestWorkInsightRework("t", "findings", 7); await api.assignWorkInsightRework("t", "plan", 8); await api.reviseWorkInsightRework("t", "evidence", 9);
    const query = new URLSearchParams(calls[0].path.split("?")[1]); assert.equal(query.get("query"), "a%_ &"); assert.equal(query.get("page"), "2"); assert.equal(query.get("size"), "20");
    assert.equal(calls[0].init.signal, signal); assert.equal(calls[0].init.cache, "no-store"); assert.equal(calls[1].path, "/workboard/task%20%2F1");
    assert.equal(JSON.parse(calls[3].init.body).expectedRevision, 7);
    for (let index = 4; index < 7; index++) assert.equal(JSON.parse(calls[index].init.body).expectedVersion, index - 1);
    for (let index = 7; index < 11; index++) assert.equal(JSON.parse(calls[index].init.body).expectedTaskVersion, index - 1);
});

function harness(apiPatch = {}) {
    let cursor = 0, dirty = true, result;
    const slots = [], pendingEffects = [], listeners = new Map(), timers = new Map(); let timer = 0;
    const same = (a, b) => a?.length === b?.length && a?.every((value, index) => Object.is(value, b[index]));
    const react = { useState(initial) { const index = cursor++; slots[index] ??= { value: typeof initial === "function" ? initial() : initial }; return [slots[index].value, (value) => { const next = typeof value === "function" ? value(slots[index].value) : value; if (!Object.is(next, slots[index].value)) { slots[index].value = next; dirty = true; } }]; },
        useRef(initial) { const index = cursor++; return (slots[index] ??= { value: { current: initial } }).value; },
        useCallback(callback, deps) { const index = cursor++; if (!slots[index] || !same(slots[index].deps, deps)) slots[index] = { value: callback, deps }; return slots[index].value; },
        useMemo(callback, deps) { const index = cursor++; if (!slots[index] || !same(slots[index].deps, deps)) slots[index] = { value: callback(), deps }; return slots[index].value; },
        useEffect(callback, deps) { const index = cursor++; if (!slots[index] || !same(slots[index].deps, deps)) { const previous = slots[index]; slots[index] = { deps }; pendingEffects.push(() => { previous?.cleanup?.(); slots[index].cleanup = callback(); }); } } };
    react.useLayoutEffect = react.useEffect;
    const window = { setTimeout: (callback) => { timers.set(++timer, callback); return timer; }, clearTimeout: (id) => timers.delete(id), setInterval: () => 0, clearInterval() {},
        addEventListener: (name, callback) => { if (!listeners.has(name)) listeners.set(name, new Set()); listeners.get(name).add(callback); }, removeEventListener: (name, callback) => listeners.get(name)?.delete(callback) };
    const document = { visibilityState: "visible", addEventListener() {}, removeEventListener() {} };
    class ApiError extends Error { constructor(status, problem) { super(problem.detail); this.status = status; } }
    let source = [item("first")];
    const calls = [];
    const api = { workboard: async (criteria, page, size, signal) => { calls.push({ criteria: plain(criteria), page, signal }); return { ...pageOf(source), number: page, size }; },
        workboardPreferences: async () => plain(model.defaultPreferences), workboardDetail: async (id) => ({ item: source.find((value) => value.id === id) ?? item(id), history: [], historyTruncated: false }), ...apiPatch };
    // This deterministic runner supplies its own hook dispatcher rather than a React component render.
    const runWorkboardHook = modules({ react, window, document, api: { isBackendConfigured: true, isWorkspaceUpdateLeader: () => true, ApiError }, workboardApi: api, organizationApi: { visibleDepartments: async () => [] } })("features/workboard/hooks/use-workboard.ts").useWorkboard;
    let props = { role: "Employee", userEmail: "first@example.test", employees: [], departments: [], staffAccounts: [], teamLeadAssignments: [], appointments: [], refreshKey: 0 };
    const render = () => { if (!dirty) return result; dirty = false; cursor = 0; result = runWorkboardHook(props); pendingEffects.splice(0).forEach((effect) => effect()); return result; };
    const flush = async () => { for (let step = 0; step < 20; step++) { render(); const batch = [...timers.values()]; timers.clear(); batch.forEach((callback) => callback()); await Promise.resolve(); await Promise.resolve(); } return render(); };
    return { flush, render, get value() { return result; }, calls, ApiError, change: (patch) => { props = { ...props, ...patch }; dirty = true; }, source: (values) => { source = values; }, event: (name) => listeners.get(name)?.forEach((callback) => callback()) };
}
test("criteria reset pages and authoritative server zero is retained", async () => {
    const h = harness(); await h.flush(); assert.equal(h.value.boardPage.totalElements, 1);
    h.value.setPage(3); await h.flush(); h.source([]); h.value.setQuery("missing"); await h.flush();
    assert.equal(h.calls.at(-1).page, 0); assert.equal(h.calls.at(-1).criteria.query, "missing"); assert.equal(h.value.boardPage.totalElements, 0);
});
test("late page and detail results cannot repopulate another account or overwrite newer criteria", async () => {
    const deferred = [], h = harness({ workboard: (criteria) => new Promise((resolve) => deferred.push({ criteria, resolve })) });
    await h.flush(); h.value.setQuery("new"); await h.flush(); deferred.at(-1).resolve(pageOf([item("new")])); await h.flush();
    deferred[0].resolve(pageOf([item("stale")])); await h.flush(); assert.equal(h.value.boardPage.items[0].id, "new");
    h.change({ userEmail: "second@example.test" }); h.render(); assert.equal(h.value.boardPage, null); await h.flush();
    h.event("brainserve:auth-session-changed"); await h.flush(); assert.equal(h.value.boardPage, null); assert.equal(h.value.preferences.layout, "LIST");
    const details = [], d = harness({ workboardDetail: (id) => new Promise((resolve) => details.push({ id, resolve })) });
    await d.flush(); d.value.setExpandedTaskId("first"); await d.flush(); d.value.setExpandedTaskId("second"); await d.flush();
    details.at(-1).resolve({ item: item("second"), history: [], historyTruncated: false }); await d.flush(); details[0].resolve({ item: item("first"), history: [], historyTruncated: false }); await d.flush();
    assert.equal(d.value.selectedDetail.item.id, "second"); d.event("brainserve:auth-session-expired"); await d.flush(); assert.equal(d.value.selectedDetail, null); assert.equal(d.value.expandedTaskId, null);
});
test("selected detail survives leaving a page; session invalidation clears page, preferences, action note and selection", async () => {
    const h = harness(); await h.flush(); h.value.setExpandedTaskId("first"); await h.flush();
    h.value.setPage(2); h.source([]); await h.flush(); assert.equal(h.value.selectedDetail.item.id, "first");
    h.value.openTaskAction(item("first"), "complete"); h.value.setActionNote("unsaved evidence"); await h.flush();
    h.event("brainserve:auth-session-changed"); h.render();
    assert.equal(h.value.boardPage, null); assert.equal(h.value.selectedDetail, null); assert.equal(h.value.actionDialog, null); assert.equal(h.value.actionNote, "");
});
test("permission denial closes independent details and notes; optional CREATE denial preserves verified READ data", async () => {
    let denied = false, h;
    h = harness({ workboardDetail: async (id) => { if (denied) throw new h.ApiError(403, { detail: "Scope removed" }); return { item: item(id), history: [], historyTruncated: false }; } });
    await h.flush(); h.value.setExpandedTaskId("first"); await h.flush(); h.value.openTaskAction(item("first"), "complete"); h.value.setActionNote("old draft"); await h.flush();
    denied = true; await h.value.reloadDetail(); h.render(); assert.equal(h.value.boardPage, null); assert.equal(h.value.actionDialog, null); assert.equal(h.value.actionNote, ""); assert.equal(h.value.expandedTaskId, null);
    let reader;
    reader = harness({ workTaskWorkspace: async () => { throw new reader.ApiError(403, { detail: "CREATE denied" }); } });
    reader.change({ role: "Team Lead" }); await reader.flush(); assert.equal(reader.value.boardPage.totalElements, 1); assert.equal(reader.value.canCreateTask, false); assert.deepEqual(plain(reader.value.eligibleAssignees), []);
    const owner = harness({ workTaskWorkspace: async () => ({ departmentId: "dep", departmentName: "Old scoped department", departmentCode: "OLD", eligibleAssignees: [{ employeeId: "emp", displayName: "Old assignee", designation: "Engineer", role: "EMPLOYEE" }] }) });
    owner.change({ role: "Team Lead" }); await owner.flush(); owner.value.setShowCreate(true); owner.value.setQuery("Old assignee"); await owner.flush();
    owner.change({ userEmail: "second@example.test" }); const firstRender = owner.render();
    assert.equal(firstRender.showCreate, false); assert.equal(firstRender.canCreateTask, false); assert.equal(firstRender.assignedDepartment, undefined); assert.deepEqual(plain(firstRender.eligibleAssignees), []); assert.equal(firstRender.query, "");
});
test("stale preferences cannot overwrite a newer save and conflict reload keeps draft with authoritative version/eligibility", async () => {
    let resolvePreferences;
    const p = harness({ workboardPreferences: () => new Promise((resolve) => { resolvePreferences = resolve; }), saveWorkboardPreferences: async (value) => ({ ...value, revision: 1 }) });
    await p.flush(); await p.value.savePreferences({ layout: "BOARD" }); await p.flush(); resolvePreferences({ ...model.defaultPreferences, layout: "LIST" }); await p.flush(); assert.equal(p.value.preferences.layout, "BOARD");
    let h;
    h = harness({ updateWorkTask: async () => { h.source([item("first", { version: 3, status: "IN_PROGRESS", allowedActions: ["complete"] })]); throw new h.ApiError(409, { detail: "Changed" }); } });
    await h.flush(); h.value.setExpandedTaskId("first"); await h.flush(); h.value.openTaskAction(item("first"), "start"); h.value.setActionNote("Keep this note"); await h.flush();
    await h.value.submitTaskAction({ preventDefault() {} }); await h.flush(); assert.equal(h.value.conflict, true); assert.equal(h.value.actionNote, "Keep this note");
    await h.value.reloadConflict(); await h.flush(); assert.equal(h.value.actionDialog.task.version, 3); assert.equal(h.value.actionNote, "Keep this note"); assert.deepEqual(plain(h.value.actionDialog.task.allowedActions), ["complete"]); assert.match(h.value.error, /no longer available/);
});
