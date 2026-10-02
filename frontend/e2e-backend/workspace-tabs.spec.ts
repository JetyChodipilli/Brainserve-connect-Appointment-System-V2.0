import { expect, test, type BrowserContext, type Page } from "@playwright/test";

type TabAudit = { active: number; updates: number; states: string[]; stream?: ReadableStreamDefaultController<Uint8Array>; stop?: () => void };
type AuditWindow = Window & { tabAudit: TabAudit };

async function subscribe(page: Page, account = "tab-audit") {
    await page.goto("/");
    await page.evaluate(async (subject) => {
        const modulePath = "/lib/api-client.ts";
        const api = await import(/* @vite-ignore */ modulePath);
        api.setAuthTokens(`audit.${btoa(JSON.stringify({ sub: subject }))}.signature`, "tab-audit-refresh");
        const state = (window as unknown as AuditWindow).tabAudit;
        state.stop = api.subscribeToWorkspaceUpdates(() => state.updates++, (value: string) => state.states.push(value));
    }, account);
}

async function fixture(context: BrowserContext, fallback: boolean) {
    await context.addInitScript((useFallback) => {
        Object.defineProperty(document, "visibilityState", { value: "visible", configurable: true });
        if (useFallback) Object.defineProperty(navigator, "locks", { value: undefined, configurable: true });
        const state: TabAudit = { active: 0, updates: 0, states: [] };
        (window as unknown as AuditWindow).tabAudit = state;
        const originalFetch = window.fetch.bind(window);
        window.fetch = (input, init) => {
            const url = typeof input === "string" ? input : input instanceof URL ? input.href : input.url;
            if (!url.endsWith("/realtime/stream")) return originalFetch(input, init);
            state.active++;
            return Promise.resolve(new Response(new ReadableStream<Uint8Array>({ start(controller) {
                state.stream = controller;
                controller.enqueue(new TextEncoder().encode("event: heartbeat\ndata: {}\n\n"));
                init?.signal?.addEventListener("abort", () => {
                    state.active--;
                    controller.error(new DOMException("Stopped", "AbortError"));
                }, { once: true });
            } }), { headers: { "Content-Type": "text/event-stream" } }));
        };
    }, fallback);
    await context.route("http://backend.invalid/api/v1/**", (route) => route.fulfill({ json: {
        name: "BrainServe", emailDomain: "brainserve.in", hqAddress: "Hyderabad", supportEmail: "support@brainserve.in",
    } }));
}

test("a sleeping leader releases its stream and a visible follower takes over", async ({ context }) => {
    await fixture(context, false);
    const pages = await Promise.all([context.newPage(), context.newPage()]);
    await Promise.all(pages.map((page) => subscribe(page)));
    const counts = () => Promise.all(pages.map((page) => page.evaluate(() => (window as unknown as AuditWindow).tabAudit.active)));
    await expect.poll(async () => (await counts()).reduce((sum, value) => sum + value, 0)).toBe(1);
    const first = (await counts()).findIndex((value) => value === 1);
    await pages[first].evaluate(() => {
        Object.defineProperty(document, "visibilityState", { value: "hidden", configurable: true });
        document.dispatchEvent(new Event("visibilitychange"));
    });
    await expect.poll(() => pages[first].evaluate(() => (window as unknown as AuditWindow).tabAudit.active)).toBe(0);
    await expect.poll(() => pages[1 - first].evaluate(() => (window as unknown as AuditWindow).tabAudit.active)).toBe(1);
    for (const page of pages) await page.evaluate(() => (window as unknown as AuditWindow).tabAudit.stop?.());
});

test("different accounts coordinate independently and cannot receive each other's refresh signals", async ({ context }) => {
    await fixture(context, false);
    const a = await context.newPage(), b = await context.newPage();
    await subscribe(a, "account-a"); await subscribe(b, "account-b");
    for (const page of [a, b]) await expect.poll(() => page.evaluate(() => (window as unknown as AuditWindow).tabAudit.active)).toBe(1);
    await a.evaluate(() => (window as unknown as AuditWindow).tabAudit.stream?.enqueue(new TextEncoder().encode("event: workspace-refresh\ndata: {}\n\n")));
    await expect.poll(() => a.evaluate(() => (window as unknown as AuditWindow).tabAudit.updates)).toBe(1);
    expect(await b.evaluate(() => (window as unknown as AuditWindow).tabAudit.updates)).toBe(0);
    for (const page of [a, b]) await page.evaluate(() => (window as unknown as AuditWindow).tabAudit.stop?.());
});

test("logout aborts the existing stream before it can update the next session", async ({ context }) => {
    await fixture(context, false); const page = await context.newPage(); await subscribe(page);
    await expect.poll(() => page.evaluate(() => (window as unknown as AuditWindow).tabAudit.active)).toBe(1);
    await page.evaluate(async () => {
        const modulePath = "/lib/api-client.ts"; const api = await import(/* @vite-ignore */ modulePath);
        api.setAccessToken(null);
    });
    await expect.poll(() => page.evaluate(() => (window as unknown as AuditWindow).tabAudit.active)).toBe(0);
    expect(await page.evaluate(() => (window as unknown as AuditWindow).tabAudit.updates)).toBe(0);
});

for (const fallback of [false, true]) {
    test(`${fallback ? "storage lease fallback" : "Web Locks"}: three tabs use one stream, share refreshes, and transfer leadership`, async ({ context }) => {
        await fixture(context, fallback);
        const pages = await Promise.all([context.newPage(), context.newPage(), context.newPage()]);
        await Promise.all(pages.map((page) => subscribe(page)));
        const activeCounts = () => Promise.all(pages.map((page) => page.evaluate(() => (window as unknown as AuditWindow).tabAudit.active)));
        await expect.poll(async () => (await activeCounts()).reduce((sum, value) => sum + value, 0)).toBe(1);
        const leaderIndex = (await activeCounts()).findIndex((value) => value === 1);
        const leader = pages[leaderIndex];
        await leader.evaluate(() => (window as unknown as AuditWindow).tabAudit.stream?.enqueue(
            new TextEncoder().encode("event: workspace-refresh\ndata: {}\n\n")));
        for (const page of pages) await expect.poll(() => page.evaluate(() => (window as unknown as AuditWindow).tabAudit.updates)).toBe(1);
        await leader.close();
        pages.splice(leaderIndex, 1);
        await expect.poll(async () => (await activeCounts()).reduce((sum, value) => sum + value, 0), { timeout: 22_000 }).toBe(1);
        for (const page of pages) await page.evaluate(() => (window as unknown as AuditWindow).tabAudit.stop?.());
        await expect.poll(async () => (await activeCounts()).reduce((sum, value) => sum + value, 0)).toBe(0);
    });
}
