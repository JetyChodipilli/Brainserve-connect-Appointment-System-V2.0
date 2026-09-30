import { roleFromAuthority } from "../shared/config/roles";
import { type Role } from "../shared/types/workspace";
import { readDemoAccounts, writeDemoAccounts } from "./accounts";
import { BROWSER_PREVIEW_ACCOUNT_TEMPLATES } from "./fixtures/accounts";
import { PREVIEW_WORKSPACE_SESSION_KEY } from "./storage-keys";

export function startBrowserPreviewRole(role: Role) {
    const accounts = readDemoAccounts();
    const existing = accounts.find((account) => account.status === "ACTIVE"
        && roleFromAuthority(account.role) === role
        && (role !== "Employee" || Boolean(account.employeeId)));
    if (existing) return existing;
    const template = BROWSER_PREVIEW_ACCOUNT_TEMPLATES[role];
    const withoutTemplateIdentity = accounts.filter((account) => account.id !== template.id
        && account.email.toLowerCase() !== template.email.toLowerCase());
    writeDemoAccounts([...withoutTemplateIdentity, template]);
    return template;
}

export function resetBrowserPreviewWorkspace() {
    if (typeof window === "undefined") return;
    for (const storage of [window.localStorage, window.sessionStorage]) {
        const keys = Array.from({ length: storage.length }, (_, index) => storage.key(index))
            .filter((key): key is string => Boolean(key?.startsWith("brainserve.")));
        keys.forEach((key) => storage.removeItem(key));
    }
    window.location.reload();
}

export function readPreviewWorkspaceSession(): { role: Role; email: string } | null {
    if (typeof window === "undefined") return null;
    try {
        const parsed = JSON.parse(window.sessionStorage.getItem(PREVIEW_WORKSPACE_SESSION_KEY) ?? "null") as
            { role?: Role; email?: string } | null;
        if (!parsed?.role || !parsed.email) return null;
        const account = readDemoAccounts().find((item) => item.email === parsed.email
            && item.status === "ACTIVE");
        if (!account || roleFromAuthority(account.role) !== parsed.role || account.forcePasswordChange) {
            window.sessionStorage.removeItem(PREVIEW_WORKSPACE_SESSION_KEY);
            return null;
        }
        return { role: parsed.role, email: parsed.email };
    } catch {
        window.sessionStorage.removeItem(PREVIEW_WORKSPACE_SESSION_KEY);
        return null;
    }
}

export function writePreviewWorkspaceSession(session: { role: Role; email: string } | null) {
    if (typeof window === "undefined") return;
    if (session) window.sessionStorage.setItem(PREVIEW_WORKSPACE_SESSION_KEY, JSON.stringify(session));
    else window.sessionStorage.removeItem(PREVIEW_WORKSPACE_SESSION_KEY);
}

