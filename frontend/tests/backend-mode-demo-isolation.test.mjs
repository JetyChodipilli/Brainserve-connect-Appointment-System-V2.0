import { readFrontendSource } from "./frontend-source.mjs";
import assert from "node:assert/strict";
import test from "node:test";
import { sourceIncludes } from "./source-contract-utils.mjs";

const app = readFrontendSource();

test("backend mode starts with empty authoritative collections instead of preview records", () => {
    for (const expected of [
        "isBackendConfigured\n        ? [] : readPreviewWorkspaceAppointments()",
        "isBackendConfigured ? [] : readDemoEmployees()",
        "isBackendConfigured\n        ? [] : mergeDemoStaffAccounts(initialStaffAccounts)",
        "isBackendConfigured\n        ? [] : readDemoDepartments()",
        "isBackendConfigured\n        ? [] : readDemoTeamLeadAssignments()",
        "isBackendConfigured\n        ? [] : readDemoDepartmentHrAssignments()",
        "isBackendConfigured\n        ? [] : readDemoManagerAssignments()",
        "isBackendConfigured ? [] : initialAccessRecords",
    ]) {
        assert.ok(
            sourceIncludes(app, expected),
            `missing backend isolation guard: ${expected}`,
        );
    }

    assert.doesNotMatch(
        app,
        /(?<!!)\bisBackendConfigured\s*\?\s*initial(?:Appointments|Employees|StaffAccounts|Departments|TeamLeadAssignments|DepartmentHrAssignments|ManagerAssignments|AccessRecords)/,
    );
});

test("System Admin department state is loaded from the authenticated database endpoint", () => {
    assert.match(
        app,
        /if \(role === "System Admin"\) \{[\s\S]*?brainServeApi\.departments\(\)[\s\S]*?setDepartments\(departmentList\)/,
    );
    assert.match(
        app,
        /The System Admin department directory could not be loaded from the database/,
    );
});

test("backend mode never initializes public departments, profile identity or approval occupancy from preview storage", () => {
    assert.match(
        app,
        /setPublicDepartments\] = useState<Department\[\]>\(\(\) =>\s*isBackendConfigured \? \[\] : initialDepartments/,
    );
    assert.match(
        app,
        /isBackendConfigured \|\| typeof window === "undefined" \? null/,
    );
    assert.match(
        app,
        /isBackendConfigured \? role : readDemoAccounts\(\)/,
    );
    assert.match(
        app,
        /!isBackendConfigured \? readDemoDepartmentHrAssignments\(\) : \[\]/,
    );
    assert.match(
        app,
        /!isBackendConfigured \? readDemoManagerAssignments\(\) : \[\]/,
    );
});

test("backend metrics and Team Lead names cannot fall back to preview fixtures", () => {
    assert.match(
        app,
        /useState<DashboardMetrics>\(\(\) => isBackendConfigured \? \{\s*awaitingApproval: 0, activeVisits: 0, visitorsInside: 0, totalEmployees: 0, activeEmployees: 0,/,
    );
    assert.match(
        app,
        /!isBackendConfigured \? initialStaffAccounts\.find/,
    );
});

test("backend mode does not read or write preview booking, profile, organization or settings data", () => {
    assert.match(
        app,
        /if \(!isBackendConfigured\) window\.localStorage\.setItem\(DEMO_LAST_REFERENCE_KEY/,
    );
    assert.match(
        app,
        /useState\(\(\) => isBackendConfigured \|\| typeof window === "undefined"\s*\? "" : window\.localStorage\.getItem\(DEMO_LAST_REFERENCE_KEY\)/,
    );
    assert.match(
        app,
        /useState<MyProfile>\(\(\) => isBackendConfigured \? \{\s*userId: "", employeeId: null, fullName: role/,
    );
    assert.match(
        app,
        /const demoVisibleDepartments = useMemo\(\(\) => \{\s*if \(isBackendConfigured\) return \[\]/,
    );
    assert.match(
        app,
        /useState<WorkspaceSetting\[\]>\(\(\) => isBackendConfigured \? \[\] : fallbackSettings\)/,
    );
    assert.match(
        app,
        /useState<RoleDefinition\[\]>\(\(\) => isBackendConfigured \? \[\] : fallbackRoles\)/,
    );
});

test("backend public and authenticated identity state starts neutral until API data arrives", () => {
    assert.match(
        app,
        /useState<CompanyProfile>\(\(\) => isBackendConfigured\s*\? \{ name: "", emailDomain: "", hqAddress: "", supportEmail: "", consentVersion: "" \}/,
    );
    assert.match(
        app,
        /useState\(\(\) => isBackendConfigured \? "" : "2026\.1"\)/,
    );
    assert.match(
        app,
        /useState<Role \| null>\(\(\) => isBackendConfigured \? null : "HR Admin"\)/,
    );
    assert.match(
        app,
        /useState\(\(\) => isBackendConfigured \? "" : "hr\.admin@brainserve\.in"\)/,
    );
    assert.match(
        app,
        /isBackendConfigured \|\| targetRole !== "ROLE_HR_ADMIN" \|\| !readDemoDepartmentHrAssignments\(\)/,
    );
});
