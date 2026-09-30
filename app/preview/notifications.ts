import { type InternalNotificationRecipient } from "../lib/api";
import { allowedRecipientAuthorities, currentNotificationRecipients } from "../lib/internal-notifications";
import { type Role } from "../shared/types/workspace";
import { readDemoAccounts } from "./accounts";
import { readDemoDepartmentHrAssignments, readDemoEmployees, readDemoTeamLeadAssignments } from "./directory";
import { DEMO_CEO_ACCOUNT } from "./fixtures/accounts";
import { readDemoManagerAssignments } from "./manager-assignments";
import { DEMO_INTERNAL_NOTIFICATIONS_KEY } from "./storage-keys";
import { type DemoInternalNotification } from "./types";

export function readDemoInternalNotifications(): DemoInternalNotification[] {
    if (typeof window === "undefined") return [];
    try {
        const value = JSON.parse(window.localStorage.getItem(DEMO_INTERNAL_NOTIFICATIONS_KEY) ?? "[]");
        const accounts = readDemoAccounts();
        return Array.isArray(value) ? value.map((item: DemoInternalNotification) => {
            const sender = accounts.find((account) => account.id === item.senderUserId
                || account.email.toLowerCase() === item.senderEmail?.toLowerCase());
            const recipient = accounts.find((account) => account.id === item.recipientUserId
                || account.email.toLowerCase() === item.recipientEmail?.toLowerCase());
            return { ...item,
                senderName: sender?.fullName ?? item.senderName,
                recipientName: recipient?.fullName ?? item.recipientName,
                senderEmail: sender?.email ?? item.senderEmail,
                recipientEmail: recipient?.email ?? item.recipientEmail,
                senderRoles: sender ? [sender.role] : item.senderRoles ?? [],
                recipientRoles: recipient ? [recipient.role] : item.recipientRoles ?? [],
                priority: item.priority ?? "NORMAL", category: item.category ?? "GENERAL",
                conversationKey: item.conversationKey ?? [item.senderUserId, item.recipientUserId].sort().join(":") };
        }) : [];
    } catch { return []; }
}

export function writeDemoInternalNotifications(items: DemoInternalNotification[]) {
    if (typeof window !== "undefined") {
        window.localStorage.setItem(DEMO_INTERNAL_NOTIFICATIONS_KEY, JSON.stringify(items));
        window.dispatchEvent(new CustomEvent("brainserve:demo-internal-notifications-updated"));
    }
}

export function demoAccountDepartment(userId: string, email: string) {
    const account = readDemoAccounts().find((item) => item.id === userId || item.email === email);
    const employee = readDemoEmployees().find((item) =>
        Boolean(account?.employeeId && (item.uuid ?? item.id) === account.employeeId)
        || item.email.toLowerCase() === email.toLowerCase());
    if (employee?.departmentId) return employee.departmentId;
    return readDemoDepartmentHrAssignments().find((item) => item.active && item.hrUserId === userId)?.departmentId
        ?? readDemoManagerAssignments().find((item) => item.active && item.managerUserId === userId)?.departmentId
        ?? readDemoTeamLeadAssignments().find((item) => item.active && item.teamLeadUserId === userId)?.departmentId
        ?? null;
}

export function demoInternalRecipients(role: Role, senderEmail: string): InternalNotificationRecipient[] {
    const allowed = allowedRecipientAuthorities(role);
    const activeAccounts = readDemoAccounts().filter((account) => account.status === "ACTIVE");
    const sender = activeAccounts.find((account) => account.email.toLowerCase() === senderEmail.toLowerCase());
    const stored = activeAccounts
        .map((account) => ({ userId: account.id, fullName: account.fullName, email: account.email, roles: [account.role] }));
    const defaults: InternalNotificationRecipient[] = [
        { userId: DEMO_CEO_ACCOUNT.id, fullName: DEMO_CEO_ACCOUNT.fullName,
            email: DEMO_CEO_ACCOUNT.email, roles: ["ROLE_CEO"] },
        { userId: "demo-manager", fullName: "Aarav Mehta", email: "aarav.mehta@brainserve.in", roles: ["ROLE_MANAGER"] },
        { userId: "demo-hr-admin", fullName: "Kavya Reddy", email: "hr.admin@brainserve.in", roles: ["ROLE_HR_ADMIN"] },
        { userId: "demo-team-lead", fullName: "Riya Sharma", email: "riya.sharma@brainserve.in", roles: ["ROLE_TEAM_LEAD"] },
        { userId: "demo-employee-riya", fullName: "Riya Sharma", email: "riya.sharma@brainserve.in", roles: ["ROLE_EMPLOYEE"] },
        { userId: "reception-preview", fullName: "Reception Desk", email: "reception@brainserve.in", roles: ["ROLE_RECEPTIONIST"] },
    ];
    const senderDepartment = demoAccountDepartment(sender?.id ?? senderEmail, senderEmail);
    return currentNotificationRecipients(allowed, stored, defaults, sender?.id, senderEmail)
        .filter((recipient) => {
            const departmentBound = role === "Manager" && recipient.roles.includes("ROLE_HR_ADMIN")
                || role === "HR Admin"
                && recipient.roles.some((authority) => ["ROLE_TEAM_LEAD", "ROLE_EMPLOYEE"].includes(authority))
                || role === "Team Lead" && recipient.roles.includes("ROLE_HR_ADMIN")
                || role === "Employee" && recipient.roles.includes("ROLE_HR_ADMIN");
            return !departmentBound || Boolean(senderDepartment
                && senderDepartment === demoAccountDepartment(recipient.userId, recipient.email));
        });
}

export function demoSenderName(role: Role, email: string) {
    return readDemoAccounts().find((account) => account.email === email)?.fullName
        ?? (role === "CEO" ? "BrainServe CEO" : role === "Manager" ? "Department Manager"
            : role === "HR Admin" ? "HR Admin" : role === "Team Lead" ? "Team Lead"
                : role === "Reception" ? "Reception Desk" : "BrainServe Employee");
}

