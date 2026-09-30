import { readFrontendSource } from "./frontend-source.mjs";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";

const read = (path) => readFileSync(new URL(`../${path}`, import.meta.url), "utf8");
const frontend = readFrontendSource();
const roles = read("../backend/src/main/java/com/brainserve/appointment/iam/domain/SystemRole.java");
const permissions = read("../backend/src/main/java/com/brainserve/appointment/iam/domain/Permission.java");
const reception = read("../backend/src/main/java/com/brainserve/appointment/reception/api/ReceptionController.java");

test("CEO can read occupancy records while check-in and check-out remain operational-role actions", () => {
    const ceo = roles.match(/ROLE_CEO\(EnumSet\.of\(([\s\S]*?)\)\),\s+ROLE_HR_ADMIN/)?.[1] ?? "";
    assert.match(permissions, /VISITOR_OCCUPANCY_READ/);
    assert.match(ceo, /VISITOR_OCCUPANCY_READ/);
    assert.match(
        reception,
        /@GetMapping\(\{"\/visitors-inside", "\/emergency-list"\}\)[\s\S]*?VISITOR_OCCUPANCY_READ/,
    );
    assert.doesNotMatch(ceo, /VISITOR_CHECK_IN/);
    assert.doesNotMatch(ceo, /VISITOR_CHECK_OUT/);
});

test("CEO workspace loads the occupancy list and renders an arrival total", () => {
    assert.match(frontend, /if \(\["Reception", "Security", "CEO"\]\.includes\(role\)\)[\s\S]*?brainServeApi\.visitorsInside\(\)/);
    assert.match(frontend, /<span>Arrived today<\/span><strong>\{metrics\.arrivedVisits\}<\/strong>/);
    assert.match(frontend, /const canProcessAccess = \["Reception", "Security"\]\.includes\(role\);/);
    assert.match(frontend, /arrivedVisits: initialAppointments\.filter\(\(item\) => Boolean\(item\.securityIntakeAt\)/);
});

test("connection recovery can retry the authenticated workspace without forcing a token bypass", () => {
    const recovery = read("components/shared/connection-recovery.tsx");
    const app = readFrontendSource();
    assert.match(recovery, /onRetry\?: \(\) => void \| Promise<void>/);
    assert.match(recovery, /if \(onRetry\) \{[\s\S]*?await onRetry\(\)/);
    assert.match(app, /workspaceConnectionFailure/);
    assert.match(app, /title="Reconnecting to your workspace"[\s\S]*?setWorkspaceRevision/);
});

