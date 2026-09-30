import assert from "node:assert/strict";
import test from "node:test";
import { fileURLToPath } from "node:url";
import { readFileSync, readdirSync } from "node:fs";
import { join } from "node:path";
import { Miniflare, convertV4MiniflareOptions } from "miniflare";

const developmentPreviewMeta =
  /<meta(?=[^>]*\bname=["']codex-preview["'])(?=[^>]*\bcontent=["']development["'])[^>]*>/i;

test("renders production product metadata without development-only markers", { timeout: 60_000 }, async (t) => {
  // Exercise the built Worker in its real runtime, including cloudflare: imports.
  const config = JSON.parse(readFileSync(new URL("../dist/server/wrangler.json", import.meta.url), "utf8"));
  const server = fileURLToPath(new URL("../dist/server", import.meta.url));
  const modules = ["index.js", ...readdirSync(server, { recursive: true })
    .filter(path => /\.(m?js)$/.test(path) && path !== "index.js")]
    .map(path => ({ type: "ESModule", path: join(server, path) }));
  const worker = new Miniflare(convertV4MiniflareOptions({
    modules,
    modulesRoot: server,
    compatibilityDate: config.compatibility_date,
    compatibilityFlags: config.compatibility_flags,
    assets: { directory: fileURLToPath(new URL("../dist/client", import.meta.url)), binding: "ASSETS",
      run_worker_first: true, routerConfig: { has_user_worker: true } },
    port: 0,
    inspectorPort: 0,
    telemetry: { enabled: false },
  }));
  t.after(() => worker.dispose());
  const response = await worker.dispatchFetch("http://localhost/", { headers: { accept: "text/html" } });

  const html = await response.text();
  assert.equal(response.status, 200, html.slice(0, 1000));
  assert.match(
    response.headers.get("content-type") ?? "",
    /^text\/html\b/i,
  );
  assert.doesNotMatch(html, developmentPreviewMeta);
  assert.match(html, /BrainServe Connect/i);
});
