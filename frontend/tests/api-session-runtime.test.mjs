import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";
import ts from "typescript";

const source = readFileSync(new URL("../lib/api-client.ts", import.meta.url), "utf8");
const javascript = ts.transpileModule(source, { compilerOptions: { target: ts.ScriptTarget.ES2022,
    module: ts.ModuleKind.ESNext } }).outputText.replaceAll("process.env.NEXT_PUBLIC_API_BASE_URL", '"http://backend.test/api/v1"');
let instance = 0;
const client = () => import(`data:text/javascript;base64,${Buffer.from(javascript).toString("base64")}#${instance++}`);
const json = (body, status = 200) => new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
function deferred() {
    let resolve;
    const promise = new Promise((done) => { resolve = done; });
    return { promise, resolve };
}

test("simultaneous GETs in the same session share one in-flight request", async (t) => {
    const api = await client(), response = deferred();
    let calls = 0;
    t.mock.method(globalThis, "fetch", () => { calls++; return response.promise; });
    api.setAuthTokens("account-a", "refresh-a");
    const first = api.apiRequest("/profile/me"), second = api.apiRequest("/profile/me");
    assert.equal(first, second);
    response.resolve(json({ userId: "a" }));
    assert.deepEqual(await first, { userId: "a" });
    assert.equal(calls, 1);
});

test("a new login cannot reuse or receive a pending GET from the previous session", async (t) => {
    const api = await client(), oldResponse = deferred();
    const credentials = [];
    t.mock.method(globalThis, "fetch", (_url, init) => {
        credentials.push(init.headers.get("Authorization"));
        return credentials.length === 1 ? oldResponse.promise : Promise.resolve(json({ userId: "b" }));
    });
    api.setAuthTokens("account-a", "refresh-a");
    const old = api.apiRequest("/profile/me");
    const rejected = assert.rejects(old, /active session changed/);
    api.setAccessToken(null);
    api.setAuthTokens("account-b", "refresh-b");
    assert.deepEqual(await api.apiRequest("/profile/me"), { userId: "b" });
    oldResponse.resolve(json({ userId: "a" }));
    await rejected;
    assert.deepEqual(credentials, ["Bearer account-a", "Bearer account-b"]);
});

for (const status of [200, 401]) {
    test(`a delayed ${status} response body cannot reach a newer session`, async (t) => {
        const api = await client(), body = deferred(), started = deferred();
        t.mock.method(globalThis, "fetch", () => Promise.resolve({ status, ok: status === 200,
            clone: () => ({ json: () => { started.resolve(); return body.promise; } }),
            headers: new Headers({ "Content-Type": "application/json" }),
            json: () => { started.resolve(); return body.promise; } }));
        api.setAuthTokens("account-a", "refresh-a");
        const pending = api.apiRequest("/profile/me", {}, false);
        const rejected = assert.rejects(pending, /active session changed/);
        await started.promise;
        api.setAuthTokens("account-b", "refresh-b");
        body.resolve({ userId: "a", detail: "Expired" });
        await rejected;
    });
    test(`a delayed ${status} refresh from the old session cannot overwrite or revoke a new login`, async (t) => {
        const api = await client(), refreshResponse = deferred(), refreshStarted = deferred();
        t.mock.method(globalThis, "fetch", (url, init) => {
            if (url.endsWith("/auth/refresh")) { refreshStarted.resolve(); return refreshResponse.promise; }
            return Promise.resolve(init.headers.get("Authorization") === "Bearer account-a"
                ? json({ detail: "Expired" }, 401) : json({ userId: "b" }));
        });
        api.setAuthTokens("account-a", "refresh-a");
        const pending = api.apiRequest("/profile/me");
        const rejected = assert.rejects(pending, /active session changed/);
        await refreshStarted.promise;
        api.setAuthTokens("account-b", "refresh-b");
        refreshResponse.resolve(json({ accessToken: "renewed-a", refreshToken: "renewed-refresh-a" }, status));
        await rejected;
        assert.equal(api.hasAuthSession(), true);
        assert.deepEqual(await api.apiRequest("/profile/me"), { userId: "b" });
    });
}

test("concurrent expired GETs renew once and retry within the current session", async (t) => {
    const api = await client(), refreshResponse = deferred(), refreshStarted = deferred();
    let refreshes = 0;
    t.mock.method(globalThis, "fetch", (url, init) => {
        if (url.endsWith("/auth/refresh")) { refreshes++; refreshStarted.resolve(); return refreshResponse.promise; }
        return Promise.resolve(init.headers.get("Authorization") === "Bearer expired"
            ? json({ detail: "Expired" }, 401) : json({ current: true }));
    });
    api.setAuthTokens("expired", "refresh-current");
    const requests = [api.apiRequest("/profile/me"), api.apiRequest("/employees")];
    await refreshStarted.promise;
    refreshResponse.resolve(json({ accessToken: "renewed", refreshToken: "renewed-refresh" }));
    assert.deepEqual(await Promise.all(requests), [{ current: true }, { current: true }]);
    assert.equal(refreshes, 1);
});

test("rejected current refresh clears the session and preserves an authentication error", async (t) => {
    const api = await client();
    t.mock.method(globalThis, "fetch", (url) => Promise.resolve(json({ detail: "Revoked" },
        url.endsWith("/auth/refresh") ? 403 : 401)));
    api.setAuthTokens("expired", "revoked-refresh");
    await assert.rejects(api.apiRequest("/profile/me"), (reason) => reason instanceof api.ApiError && reason.status === 401);
    assert.equal(api.hasAuthSession(), false);
});

test("mutations are not coalesced and a 429 response is not automatically replayed", async (t) => {
    const api = await client();
    let calls = 0;
    t.mock.method(globalThis, "fetch", () => { calls++; return Promise.resolve(json({ detail: "Slow down" }, 429)); });
    const results = await Promise.allSettled([api.apiRequest("/work-tasks", { method: "POST" }),
        api.apiRequest("/work-tasks", { method: "POST" })]);
    assert.equal(calls, 2);
    assert.ok(results.every((result) => result.status === "rejected" && result.reason.status === 429));
});

test("changed account authority ends the workspace without silently changing role", async (t) => {
    const api = await client(); let calls = 0;
    t.mock.method(globalThis, "fetch", () => { calls++; return Promise.resolve(json({ errorCode: "ACCOUNT_AUTHORITY_CHANGED" }, 401)); });
    api.setAuthTokens("account-a", "refresh-a");
    await assert.rejects(api.apiRequest("/employees"), (error) => error.status === 401);
    assert.equal(calls, 1); assert.equal(api.hasAuthSession(), false);
});

test("fresh MFA rejection preserves credentials and points to account security", async (t) => {
    const api = await client();
    t.mock.method(globalThis, "fetch", () => Promise.resolve(json({ errorCode: "MFA_STEP_UP_REQUIRED" }, 403)));
    api.setAuthTokens("account-a", "refresh-a");
    await assert.rejects(api.apiRequest("/report-exports", { method: "POST" }), /My profile/);
    assert.equal(api.hasAuthSession(), true);
});
