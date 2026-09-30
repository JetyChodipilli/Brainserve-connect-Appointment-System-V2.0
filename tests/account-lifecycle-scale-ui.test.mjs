import { readFrontendSource } from "./frontend-source.mjs";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";

const read = (path) => readFileSync(path, "utf8");
const app = readFrontendSource();
const css = read("app/globals.css");
const api = read("app/lib/api.ts");
const service = read(
  "backend/src/main/java/com/brainserve/appointment/iam/application/AccountClosureService.java");
const users = read(
  "backend/src/main/java/com/brainserve/appointment/iam/infrastructure/UserAccountRepository.java");

test("operational and archived account directories stay bounded", () => {
  assert.match(api, /accountLifecycleAccountPage\(filters/);
  assert.match(api, /archivedAccountPage\(filters/);
  assert.match(api, /filters\.size \?\? 25/);
  assert.match(service, /Page<AccountView> activeAccounts/);
  assert.match(service, /Page<ArchivedView> archivedAccounts/);
  assert.match(users, /findOperationalAccounts/);
  assert.doesNotMatch(service, /public List<AccountView> activeAccounts/);
});

test("account lifecycle provides server-side search, role and department filters, and numbered pages", () => {
  assert.match(app, /Search operational accounts/);
  assert.match(app, /Filter operational accounts by role/);
  assert.match(app, /Filter employees by department/);
  assert.match(app, /Page \{accountPage \+ 1\} of \{accountPageCount\}/);
  assert.match(app, /Only 25 matching accounts are loaded at once/);
});

test("deactivate and archive uses a persistent in-page verification section", () => {
  assert.match(app, /className="direct-archive-panel glass-panel"/);
  assert.match(app, /className="direct-archive-resume glass-panel"/);
  assert.match(app, /Enter OTP/);
  assert.match(app, /Minimize archive verification/);
  assert.match(app, /writePreviewDirectArchiveChallenge/);
  assert.match(css, /\.direct-archive-panel \{ overflow: hidden;/);
  assert.match(css, /\.direct-archive-resume \{ display: grid;/);
  assert.doesNotMatch(app, /modal-backdrop lifecycle-modal-backdrop/);
});

test("archived recovery keeps navigation visible and can be minimized and resumed", () => {
  assert.match(app, /className="direct-archive-panel recovery-panel glass-panel"/);
  assert.match(app, /className="direct-archive-resume recovery-resume glass-panel"/);
  assert.match(app, /setTab\("archived"\); setRecoveryPanelMinimized\(false\)/);
  assert.match(app, /Enter OTP/);
  assert.match(css, /\.recovery-panel \{/);
  assert.match(css, /\.recovery-resume \{/);
  assert.doesNotMatch(app, /modal-backdrop archived-recovery/);
});

