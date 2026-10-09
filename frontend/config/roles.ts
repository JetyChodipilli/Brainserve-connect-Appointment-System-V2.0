import { type Role, type View } from "../types/workspace";

export function roleFromAuthority(authority: string): Role | null {
    const roleMap: Record<string, Role> = {
        ROLE_CEO: "CEO", ROLE_MANAGER: "Manager", ROLE_HR_ADMIN: "HR Admin", ROLE_TEAM_LEAD: "Team Lead",
        ROLE_EMPLOYEE: "Employee", ROLE_RECEPTIONIST: "Reception", ROLE_SECURITY: "Security",
        ROLE_SYSTEM_ADMIN: "System Admin",
    };
    return roleMap[authority] ?? null;
}

export const ROLE_AUTHORITY_BY_LABEL: Record<Role, string> = {
    "HR Admin": "ROLE_HR_ADMIN", Manager: "ROLE_MANAGER", "Team Lead": "ROLE_TEAM_LEAD",
    CEO: "ROLE_CEO", Employee: "ROLE_EMPLOYEE", Reception: "ROLE_RECEPTIONIST", Security: "ROLE_SECURITY",
    "System Admin": "ROLE_SYSTEM_ADMIN",
};

export const SUPPORTED_ROLE_AUTHORITIES = [
    "ROLE_SYSTEM_ADMIN",
    "ROLE_CEO",
    "ROLE_MANAGER",
    "ROLE_HR_ADMIN",
    "ROLE_TEAM_LEAD",
    "ROLE_EMPLOYEE",
    "ROLE_RECEPTIONIST",
    "ROLE_SECURITY",
] as const;

export function primaryRoleFromAuthorities(authorities: string[]): Role | null {
    const supportedAuthorities = SUPPORTED_ROLE_AUTHORITIES.filter((authority) =>
        authorities.includes(authority),
    );
    if (supportedAuthorities.length !== 1) return null;
    return roleFromAuthority(supportedAuthorities[0]);
}

export function roleBadge(role: Role) {
    return { "System Admin": "SA", CEO: "CE", Manager: "MG", "HR Admin": "HR", "Team Lead": "TL",
        Employee: "EM", Reception: "RE", Security: "SE" }[role];
}

export const rolePermissions: Record<Role, View[]> = {
    "HR Admin": ["overview", "appointments", "work", "performance", "insights", "employees", "terminations", "account-lifecycle", "visitors", "notifications", "organization", "reports", "audit", "settings", "profile"],
    CEO: ["overview", "appointments", "insights", "employees", "terminations", "account-lifecycle", "visitors", "notifications", "organization", "reports", "audit", "settings", "profile"],
    Manager: ["overview", "appointments", "work", "insights", "employees", "visitors", "notifications", "organization", "reports", "profile"],
    "Team Lead": ["overview", "work", "employees", "notifications", "organization", "reports", "profile"],
    Employee: ["overview", "work", "employees", "notifications", "organization", "reports", "profile"],
    Reception: ["overview", "appointments", "visitors", "notifications", "reports", "profile"],
    Security: ["overview", "appointments", "visitors", "reports", "profile"],
    "System Admin": ["overview", "insights", "account-lifecycle", "notifications", "reports", "audit", "logs", "integrations", "support", "kiosk-devices", "release", "settings", "profile"],
};
