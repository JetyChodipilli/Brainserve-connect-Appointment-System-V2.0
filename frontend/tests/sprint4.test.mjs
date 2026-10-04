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

const nativeRequire = createRequire(import.meta.url), root = fileURLToPath(new URL("../", import.meta.url));
function modules(overrides = {}) {
    const cache = new Map();
    return function load(path) {
        const filename = resolve(root, path); if (cache.has(filename)) return cache.get(filename);
        const exports = {}; cache.set(filename, exports);
        const code = ts.transpileModule(readFileSync(filename, "utf8"), { compilerOptions: { target: ts.ScriptTarget.ES2022,
            module: ts.ModuleKind.CommonJS, jsx: ts.JsxEmit.ReactJSX, esModuleInterop: true } }).outputText;
        vm.runInNewContext(code, { exports, process: { env: {} }, AbortController, URLSearchParams, TextDecoder, Blob,
            Error, Intl, Date, window: overrides.window, document: overrides.document, require(name) {
                if (name === "react" && overrides.react) return overrides.react;
                if (name.includes("lib/api-client") && overrides.api) return overrides.api;
                if (name.endsWith(".module.css")) return { __esModule: true, default: new Proxy({}, { get: (_target, key) => key }) };
                if (!name.startsWith(".")) return nativeRequire(name);
                const target = resolve(dirname(filename), name);
                return load(target + (() => { try { readFileSync(target + ".ts"); return ".ts"; } catch { return ".tsx"; } })());
            } }, { filename }); return exports;
    };
}
const load = modules(), model = load("features/setup-imports/import-model.ts");
const job = (patch = {}) => ({ id: "00000000-0000-4000-8000-000000000001", kind: "DEPARTMENTS", duplicatePolicy: "SKIP", status: "PREVIEW",
    checksum: "sha256-preview", createdAt: "2026-10-03T00:00:00Z", expiresAt: "2026-10-04T00:00:00Z", totalRows: 1,
    applied: 0, skipped: 0, failed: 0, rows: [{ rowNumber: 2, status: "VALID", values: { code: "ENG", name: "Engineering" }, errors: [], recordId: null }],
    rollbackNotice: "Created records and downstream effects are not automatically reversible.", ...patch });

test("logical CSV count accepts UTF-8 BOM, quoted comma/newline and doubled quote but rejects malformed fields", () => {
    assert.equal(model.csvRowCount('\uFEFFcode,name\r\nENG,"Engineering, R&D"\r\nOPS,"Office\noperations ""team"""\r\n'), 2);
    assert.equal(model.csvRowCount("code,name\nENG,Engineering\n\n"), 1);
    for (const csv of ['', 'code,name\nENG,"open', 'code,name\nEN"G,Engineering', 'code,name\nENG,"name"suffix', 'code,name\nENG,bad\0cell']) assert.throws(() => model.csvRowCount(csv));
});

test("file validation uses actual UTF-8 bytes and enforces 1,000 logical rows before transport", async () => {
    const limits = { maxBytes: 2_097_152, maxRows: 1_000 };
    const valid = new File(["code,name\n" + Array.from({ length: 1_000 }, (_, index) => `D${index},Department ${index}`).join("\n")], "departments.csv");
    assert.equal((await model.readImportFile(valid, limits)).rows, 1_000);
    await assert.rejects(model.readImportFile(new File([await valid.text(), "\nEXTRA,Extra"], "departments.csv"), limits), /1000|1,000/);
    await assert.rejects(model.readImportFile(new File([new Uint8Array(2_097_153)], "departments.csv"), limits), /2 MiB/);
    await assert.rejects(model.readImportFile(new File([new Uint8Array([0xff, 0xfe])], "departments.csv"), limits), /UTF-8/);
    await assert.rejects(model.readImportFile(new File(["code,name\n"], "departments.csv"), limits), /1 to/);
    await assert.rejects(model.readImportFile(new File(["code,name\nENG,Engineering"], "departments.xlsx"), limits), /\.csv/);
    await assert.rejects(model.readImportFile(valid, { ...limits, maxRows: 3 }), /1 to 3/);
});

test("execution binds a stable job key and requires exact current preview, confirmation, unexpired valid rows", () => {
    assert.equal(model.executionKey(job().id), model.executionKey(job().id));
    assert.notEqual(model.executionKey(job().id), model.executionKey("another-job"));
    assert.equal(model.executable(job(), true, Date.parse("2026-10-03")), true);
    for (const [value, confirmed, now] of [[job(), false, Date.parse("2026-10-03")], [job(), true, Date.parse("2026-10-04")],
        [job({ status: "COMPLETED" }), true, 0], [job({ rows: [{ ...job().rows[0], status: "FAILED" }] }), true, 0]]) assert.equal(model.executable(value, confirmed, now), false);
});

test("current server capability and exact job shape are required before showing or executing records", () => {
    const options = { allowedKinds: ["DEPARTMENTS"], maxRows: 1_000, maxBytes: 2_097_152, duplicatePolicies: ["SKIP", "FAIL"] };
    assert.equal(model.validateImportOptions(options), options); assert.equal(model.validateImportJob(job(), job().id, "DEPARTMENTS").totalRows, 1);
    for (const patch of [{ allowedKinds: ["ACCOUNTS"] }, { maxRows: 0 }, { maxBytes: -1 }, { duplicatePolicies: [] }]) assert.throws(() => model.validateImportOptions({ ...options, ...patch }));
    for (const value of [null, job({ id: "another-job" }), job({ kind: "EMPLOYEES" }), job({ totalRows: 2 }), job({ rows: [null] }), job({ status: "DONE" }),
        job({ checksum: null }), job({ expiresAt: "invalid" }), job({ applied: 2 }), job({ rows: [{ ...job().rows[0], values: { name: 12 } }] })])
        assert.throws(() => model.validateImportJob(value, job().id, "DEPARTMENTS"));
});

test("wizard readiness requires real server revision and all seven current steps", () => {
    const state = { revision: 4, policyVersion: "setup.v1", status: "IN_PROGRESS", currentStep: "company", completedAt: null, officeZone: "Asia/Kolkata",
        steps: ["company", "departments", "roles", "policy", "notifications", "privacy", "review"].map(id => ({ id, title: id, complete: false, issues: ["Current configuration required"], settingKeys: [] })) };
    assert.equal(model.validateSetupState(state), state);
    for (const patch of [{ revision: -1 }, { currentStep: "unknown" }, { steps: state.steps.slice(1) }, { status: "READY" }, { steps: [{ ...state.steps[0], issues: null }, ...state.steps.slice(1)] }])
        assert.throws(() => model.validateSetupState({ ...state, ...patch }));
});

test("transport uses shared request/session guards, exact checksums and the stable retry key", async () => {
    const calls = [], { setupImportsApi: api } = modules({ api: { isBackendConfigured: true, apiRequest: async (path, init) => { calls.push({ path, init }); return job(); } } })("features/setup-imports/api/setup-imports-api.ts");
    const signal = new AbortController().signal;
    await api.setup(signal); await api.progress(4, "review", signal); await api.complete(5, signal); await api.options(signal);
    await api.template("VISITORS", signal); await api.preview("DEPARTMENTS", "FAIL", "code,name\nENG,Engineering", signal);
    await api.execute(job().id, job().checksum, model.executionKey(job().id), signal);
    await api.execute(job().id, job().checksum, model.executionKey(job().id), signal); await api.job(job().id, signal); await api.errors(job().id, signal);
    assert.equal(calls[0].path, "/company-setup"); assert.equal(calls[0].init.cache, "no-store");
    assert.deepEqual(JSON.parse(calls[1].init.body), { expectedRevision: 4, stepId: "review" });
    assert.equal(calls[4].path, "/bulk-imports/templates/VISITORS");
    assert.deepEqual(JSON.parse(calls[5].init.body), { kind: "DEPARTMENTS", duplicatePolicy: "FAIL", csv: "code,name\nENG,Engineering" });
    assert.equal(calls[6].init.body, calls[7].init.body); assert.equal(JSON.parse(calls[6].init.body).checksum, job().checksum);
    for (const call of calls) assert.equal(call.init.signal, signal);
});

test("preview deployment never sends setup or import requests including attempted execution", () => {
    let requests = 0;
    const { setupImportsApi: api } = modules({ api: { isBackendConfigured: false, apiRequest: () => { requests++; } } })("features/setup-imports/api/setup-imports-api.ts");
    for (const request of [() => api.setup(), () => api.progress(1, "company"), () => api.complete(1), () => api.options(), () => api.template("DEPARTMENTS"),
        () => api.preview("DEPARTMENTS", "SKIP", ""), () => api.execute("id", "checksum", "key"), () => api.job("id"), () => api.errors("id")]) assert.throws(request, /Demo preview only/);
    assert.equal(requests, 0);
    const components = modules({ api: { isBackendConfigured: false } });
    const html = renderToStaticMarkup(React.createElement(components("features/setup-imports/bulk-imports.tsx").BulkImports, { accountScope: "System Admin:preview" }));
    assert.match(html, /Demo preview only/); assert.doesNotMatch(html, /Confirm &amp; create|CSV UTF-8 file/);
    const setupHtml = renderToStaticMarkup(React.createElement(components("features/setup-imports/company-setup.tsx").CompanySetup, { role: "System Admin", userEmail: "preview", onConfigure() {} }));
    assert.match(setupHtml, /Demo preview only/); assert.doesNotMatch(setupHtml, /Currently ready/);
});

test("selection, auth-change and unmount invalidate all late file/read/mutation results", () => {
    const refs = [], effects = [], listeners = new Map(); let index = 0;
    const react = { useCallback: fn => fn, useMemo: fn => fn(), useRef(initial) { return refs[index++] ??= { current: initial }; }, useEffect(effect) { effects.push(effect()); } };
    const window = { addEventListener(name, callback) { listeners.set(name, callback); }, removeEventListener(name) { listeners.delete(name); } };
    const scope = modules({ react, window })("features/setup-imports/use-operation-scope.ts").useOperationScope();
    const first = scope.request(), second = scope.request(); assert.equal(first.current(), true);
    scope.invalidate(); assert.equal(first.current(), false); assert.equal(first.signal.aborted, true); assert.equal(second.current(), false);
    const third = scope.request(); listeners.get("brainserve:auth-session-changed")(); assert.equal(third.current(), false);
    const fourth = scope.request(); listeners.get("brainserve:auth-session-expired")(); assert.equal(fourth.current(), false);
    const fifth = scope.request(); effects.forEach(cleanup => cleanup()); assert.equal(fifth.current(), false); assert.equal(fifth.signal.aborted, true);
});
