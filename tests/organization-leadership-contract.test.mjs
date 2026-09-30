import { readFrontendSource } from "./frontend-source.mjs";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";

const read = (path) => readFileSync(new URL(`../${path}`, import.meta.url), "utf8");
const frontend = readFrontendSource();
const api = read("app/lib/api.ts");
const controller = read("backend/src/main/java/com/brainserve/appointment/reporting/api/OrganizationScopeController.java");

test("leadership display uses scoped names rather than incomplete staff pages", () => {
    assert.ok(api.includes('"/departments/leadership"'));
    assert.ok(frontend.includes("brainServeApi.departmentLeadership()"));
    for (const key of ["teamLead", "hr", "manager"]) {
        assert.ok(frontend.includes(`leaderLabel("${key}",`));
    }
    assert.ok(frontend.includes('leadershipStatus === "loading"'));
    assert.ok(frontend.includes('leadershipStatus === "error" || !departmentLeadership'));
    assert.ok(frontend.includes('return "Unavailable"'));
    assert.ok(frontend.includes('leader ? leader.fullName || "Assigned" : "Not assigned"'));
});

test("leadership read reuses authenticated department scope and exposes names only", () => {
    const endpoint = controller.slice(controller.indexOf('@GetMapping("/leadership")'));
    assert.ok(endpoint.includes("return visible(jwt).stream()"));
    assert.ok(endpoint.includes("hasAnyRole('CEO','HR_ADMIN','MANAGER','TEAM_LEAD','EMPLOYEE')"));
    assert.ok(endpoint.includes("record Leader(String fullName)"));
    for (const directory of ["teamLeads", "departmentHrs", "managers"]) {
        assert.ok(endpoint.includes(`${directory}.activeForDepartment(department.id())`));
    }
});

