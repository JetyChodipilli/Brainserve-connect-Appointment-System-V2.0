import { readFrontendSource } from "./frontend-source.mjs";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";

const app = readFrontendSource();
const service = readFileSync(new URL(
    "../../backend/src/main/java/com/brainserve/appointment/iam/application/AccountRecoveryService.java",
    import.meta.url,
), "utf8");
const controller = readFileSync(new URL(
    "../../backend/src/main/java/com/brainserve/appointment/iam/api/SystemAdminAccountRecoveryController.java",
    import.meta.url,
), "utf8");
const writer = readFileSync(new URL(
    "../../backend/src/main/java/com/brainserve/appointment/iam/application/AccountRecoveryRequestWriter.java",
    import.meta.url,
), "utf8");

test("valid role-changed accounts can request recovery and the admin queue refreshes automatically", () => {
  assert.match(service, /\.filter\(user -> user\.getRoles\(\)\.contains\(role\)\)/);
  assert.doesNotMatch(service, /getRoles\(\)\.size\(\) == 1 && user\.getRoles\(\)\.contains\(role\)/);
  assert.match(service, /normalized\.contains\("@"\)/);
  assert.match(service, /findByEmailIgnoreCase\(normalized\)[\s\S]*?filter\(UserAccount::isEnabled\)/);
  assert.match(app, /identifier\.includes\("@"\)[\s\S]*?account\.email\.toLowerCase\(\) === normalizedIdentifier/);
  assert.match(app,
      /setInterval\(\(\) => \{[\s\S]*?isWorkspaceUpdateLeader\(\)[\s\S]*?loadRequests\(false\)[\s\S]*?\}, 10000\)/);
  assert.match(app, /addEventListener\("focus", refreshWhenVisible\)/);
  assert.match(app, /addEventListener\("visibilitychange", refreshWhenVisible\)/);
  assert.match(controller, /private static SystemRole displayRole/);
});

test("preview recovery requests synchronize into an already-open System Admin tab", () => {
  assert.match(app, /dispatchEvent\(new CustomEvent\("brainserve:demo-recovery-updated"\)\)/);
  assert.match(app, /setRequests\(readDemoRecoveryRequests\(\)\.filter\(\(item\) => item\.status === "PENDING"\)\)/);
  assert.match(app, /addEventListener\("storage", refreshPreviewStorage\)/);
  assert.match(app, /event\.key === DEMO_RECOVERY_REQUESTS_KEY/);
  assert.match(app, /Open System Admin in this same browser profile/);
});

test("a secondary audit failure cannot roll back the recovery queue row", () => {
  assert.match(writer, /@Transactional\(propagation = Propagation\.REQUIRES_NEW\)/);
  assert.match(writer, /saveAndFlush\(new AccountRecoveryRequest\(user, type\)\)/);
  assert.match(service, /catch \(RuntimeException auditFailure\)/);
});

