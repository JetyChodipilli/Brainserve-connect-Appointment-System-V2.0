import { SYSTEM_ADMIN_EMAIL } from "../../shared/config/identity";
import { type Role } from "../../shared/types/workspace";
import { type DemoProvisioningAccount } from "../types";

// Legacy browser-only fixtures remain solely for local UI development. They
// contain no usable credential and are unreachable when the backend is absent.
export const DEMO_SYSTEM_ADMIN: DemoProvisioningAccount = {
    id: "00000000-0000-4000-8000-000000000001",
    fullName: "Jety Chodipilli",
    email: SYSTEM_ADMIN_EMAIL,
    role: "ROLE_SYSTEM_ADMIN",
    status: "ACTIVE",
    createdByUserId: null,
    approvedByUserId: null,
    createdAt: "2026-07-14T00:00:00.000Z",
    approvedAt: null,
    forcePasswordChange: false,
    passwordHash: "",
};

// This deliberately unusable local fixture supports layout-only development.
// It cannot authenticate or authorize any hosted workflow.
export const DEMO_CEO_ACCOUNT: DemoProvisioningAccount = {
    id: "00000000-0000-4000-8000-000000000002",
    fullName: "Althuf",
    email: "althuf@brainserve.in",
    role: "ROLE_CEO",
    status: "ACTIVE",
    createdByUserId: DEMO_SYSTEM_ADMIN.id,
    approvedByUserId: DEMO_SYSTEM_ADMIN.id,
    createdAt: "2026-07-14T00:00:00.000Z",
    approvedAt: "2026-07-14T00:00:00.000Z",
    forcePasswordChange: false,
    passwordHash: "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
};

export const BROWSER_PREVIEW_ROLE_ORDER: Role[] = [
    "System Admin", "CEO", "Manager", "HR Admin", "Team Lead", "Employee", "Reception", "Security",
];

export const BROWSER_PREVIEW_ACCOUNT_TEMPLATES: Record<Role, DemoProvisioningAccount> = {
    "System Admin": DEMO_SYSTEM_ADMIN,
    CEO: DEMO_CEO_ACCOUNT,
    Manager: {
        id: "demo-manager", fullName: "Aarav Mehta", email: "aarav.mehta@brainserve.in",
        role: "ROLE_MANAGER", status: "ACTIVE", employeeId: "BSPL-OP-0027",
        createdByUserId: DEMO_SYSTEM_ADMIN.id, approvedByUserId: DEMO_CEO_ACCOUNT.id,
        createdAt: "2026-07-20T04:30:00.000Z", approvedAt: "2026-07-20T04:30:00.000Z",
        forcePasswordChange: false, passwordHash: "",
    },
    "HR Admin": {
        id: "demo-hr-admin", fullName: "Kavya Reddy", email: "kavya.reddy@brainserve.in",
        role: "ROLE_HR_ADMIN", status: "ACTIVE", employeeId: "BSPL-HR-0018",
        createdByUserId: DEMO_SYSTEM_ADMIN.id, approvedByUserId: DEMO_CEO_ACCOUNT.id,
        createdAt: "2026-07-16T04:30:00.000Z", approvedAt: "2026-07-16T04:30:00.000Z",
        forcePasswordChange: false, passwordHash: "",
    },
    "Team Lead": {
        id: "demo-team-lead", fullName: "Riya Sharma", email: "riya.sharma@brainserve.in",
        role: "ROLE_TEAM_LEAD", status: "ACTIVE", employeeId: "BSPL-IT-0042",
        createdByUserId: DEMO_SYSTEM_ADMIN.id, approvedByUserId: "demo-hr-admin",
        createdAt: "2026-07-15T04:30:00.000Z", approvedAt: "2026-07-15T04:30:00.000Z",
        forcePasswordChange: false, passwordHash: "",
    },
    Employee: {
        id: "demo-employee", fullName: "Kalyan Reddy", email: "kalyan@brainserve.in",
        role: "ROLE_EMPLOYEE", status: "ACTIVE", employeeId: "BSPL-IT-0071",
        createdByUserId: "demo-hr-admin", approvedByUserId: "demo-hr-admin",
        createdAt: "2026-07-17T04:30:00.000Z", approvedAt: "2026-07-17T04:30:00.000Z",
        forcePasswordChange: false, passwordHash: "",
    },
    Reception: {
        id: "reception-preview", fullName: "Reception Desk", email: "reception@brainserve.in",
        role: "ROLE_RECEPTIONIST", status: "ACTIVE", createdByUserId: "demo-hr-admin",
        approvedByUserId: "demo-hr-admin", createdAt: "2026-07-17T04:30:00.000Z",
        approvedAt: "2026-07-17T04:30:00.000Z", forcePasswordChange: false, passwordHash: "",
    },
    Security: {
        id: "security-preview", fullName: "Security Desk", email: "security@brainserve.in",
        role: "ROLE_SECURITY", status: "ACTIVE", createdByUserId: "demo-hr-admin",
        approvedByUserId: "demo-hr-admin", createdAt: "2026-07-17T04:30:00.000Z",
        approvedAt: "2026-07-17T04:30:00.000Z", forcePasswordChange: false, passwordHash: "",
    },
};

