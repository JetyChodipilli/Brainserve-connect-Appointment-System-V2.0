import { readFrontendSource } from "./frontend-source.mjs";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";

const app = readFrontendSource();
const api = readFileSync(new URL("../app/lib/api.ts", import.meta.url), "utf8");
const authController = readFileSync(new URL("../backend/src/main/java/com/brainserve/appointment/iam/api/AuthController.java", import.meta.url), "utf8");
const authentication = readFileSync(new URL("../backend/src/main/java/com/brainserve/appointment/iam/application/AuthenticationService.java", import.meta.url), "utf8");

test("sidebar profile is clickable and exposes live identity plus logout", () => {
  assert.match(app, /aria-haspopup="menu"/);
  assert.match(app, /<strong>\{profileName\}<\/strong><small>\{userEmail\}<\/small>/);
  assert.match(app, /brainServeApi\.myProfile\(\)/);
  assert.match(app, /profile\.fullName/);
  assert.match(app, /End this secure session/);
  assert.match(app, /event\.key === "Escape"/);
  assert.match(app, /contains\(event\.target as Node\)/);
});

test("logout revokes the backend refresh session before leaving the app", () => {
  assert.match(app, /try \{\s*await brainServeApi\.logout\(\);\s*\}\s*finally \{\s*writePreviewWorkspaceSession\(null\);\s*setScreen\("welcome"\);/);
  assert.match(api, /apiRequest<void>\("\/auth\/logout"/);
  assert.match(authController, /@PostMapping\("\/logout"\)/);
  assert.match(authController, /@PostMapping\("\/logout"\)\s*@ResponseStatus\(HttpStatus\.NO_CONTENT\)/);
  assert.match(authentication, /securityState\.revokePresentedRefreshToken\(hash\(refreshToken\), Instant\.now\(\)\)/);
  assert.match(api, /const token = refreshToken;\s*setAccessToken\(null\)/);
  assert.match(api, /keepalive: true/);
  assert.match(api, /response\.status === 204 \|\| !contentType\.includes\("json"\)/);
});

