import {
    type AccountClosureRequest,
    type AccountLifecycleRecord,
    type ArchivedAccount,
    type ArchivedRecoveryChallenge,
    type DirectArchiveChallenge,
    type EmployeeTerminationRequest,
    type EssentialLogRecord,
    type RoleDepartmentChangeRequest,
} from "../lib/api";
import { fail, rethrow } from "../shared/utils/errors";
import { newClientId } from "../shared/utils/ids";
import {
    DEMO_ACCOUNT_CLOSURES_KEY,
    DEMO_ACCOUNT_LIFECYCLE_KEY,
    DEMO_ARCHIVED_ACCOUNTS_KEY,
    DEMO_ESSENTIAL_LOGS_KEY,
    DEMO_ROLE_DEPARTMENT_CHANGES_KEY,
    DEMO_TERMINATIONS_KEY,
    PREVIEW_ARCHIVED_RECOVERY_CHALLENGE_KEY,
    PREVIEW_DIRECT_ARCHIVE_CHALLENGE_KEY,
    PREVIEW_DIRECT_ARCHIVE_PASSWORD_FAILURES_KEY,
} from "./storage-keys";

export function readDemoRoleDepartmentChanges(): RoleDepartmentChangeRequest[] {
    if (typeof window === "undefined") return [];
    try {
        const value = JSON.parse(window.localStorage.getItem(DEMO_ROLE_DEPARTMENT_CHANGES_KEY) ?? "[]");
        return Array.isArray(value) ? value : [];
    } catch { return []; }
}

export function writeDemoRoleDepartmentChanges(items: RoleDepartmentChangeRequest[]) {
    if (typeof window !== "undefined") window.localStorage.setItem(DEMO_ROLE_DEPARTMENT_CHANGES_KEY, JSON.stringify(items));
}

export function readDemoTerminations(): EmployeeTerminationRequest[] {
    if (typeof window === "undefined") return [];
    try {
        const value = JSON.parse(window.localStorage.getItem(DEMO_TERMINATIONS_KEY) ?? "[]");
        return Array.isArray(value) ? value : [];
    } catch { return []; }
}

export function writeDemoTerminations(items: EmployeeTerminationRequest[]) {
    if (typeof window !== "undefined") window.localStorage.setItem(DEMO_TERMINATIONS_KEY, JSON.stringify(items));
}

export function readDemoEssentialLogs(): EssentialLogRecord[] {
    if (typeof window === "undefined") return [];
    try {
        const value = JSON.parse(window.localStorage.getItem(DEMO_ESSENTIAL_LOGS_KEY) ?? "[]");
        return Array.isArray(value) ? value : [];
    } catch { return []; }
}

export function writeDemoEssentialLogs(items: EssentialLogRecord[]) {
    if (typeof window !== "undefined") window.localStorage.setItem(DEMO_ESSENTIAL_LOGS_KEY, JSON.stringify(items));
}

export function readDemoAccountClosures(): AccountClosureRequest[] {
    if (typeof window === "undefined") return [];
    try {
        const value = JSON.parse(window.localStorage.getItem(DEMO_ACCOUNT_CLOSURES_KEY) ?? "[]");
        return Array.isArray(value) ? value : [];
    } catch { return []; }
}

export function writeDemoAccountClosures(items: AccountClosureRequest[]) {
    if (typeof window !== "undefined") window.localStorage.setItem(DEMO_ACCOUNT_CLOSURES_KEY, JSON.stringify(items));
}

export function readDemoArchivedAccounts(): ArchivedAccount[] {
    if (typeof window === "undefined") return [];
    try {
        const value = JSON.parse(window.localStorage.getItem(DEMO_ARCHIVED_ACCOUNTS_KEY) ?? "[]");
        return Array.isArray(value) ? value : [];
    } catch { return []; }
}

export function writeDemoArchivedAccounts(items: ArchivedAccount[]) {
    if (typeof window !== "undefined") window.localStorage.setItem(DEMO_ARCHIVED_ACCOUNTS_KEY, JSON.stringify(items));
}

export function readDemoAccountLifecycle(): AccountLifecycleRecord[] {
    if (typeof window === "undefined") return [];
    try {
        const value = JSON.parse(window.localStorage.getItem(DEMO_ACCOUNT_LIFECYCLE_KEY) ?? "[]");
        return Array.isArray(value) ? value : [];
    } catch { return []; }
}

export function writeDemoAccountLifecycle(items: AccountLifecycleRecord[]) {
    if (typeof window !== "undefined") window.localStorage.setItem(DEMO_ACCOUNT_LIFECYCLE_KEY, JSON.stringify(items));
}

export function readPreviewDirectArchiveChallenge(): DirectArchiveChallenge | null {
    if (typeof window === "undefined") return null;
    try {
        const value = JSON.parse(
            window.sessionStorage.getItem(PREVIEW_DIRECT_ARCHIVE_CHALLENGE_KEY) ?? "null") as DirectArchiveChallenge | null;
        if (!value?.challengeId || value.targetRole === "ROLE_CEO"
            || Date.parse(value.expiresAt) <= Date.now() || value.attemptsRemaining <= 0) {
            window.sessionStorage.removeItem(PREVIEW_DIRECT_ARCHIVE_CHALLENGE_KEY);
            return null;
        }
        return value;
    } catch {
        window.sessionStorage.removeItem(PREVIEW_DIRECT_ARCHIVE_CHALLENGE_KEY);
        return null;
    }
}

export function writePreviewDirectArchiveChallenge(value: DirectArchiveChallenge | null) {
    if (typeof window === "undefined") return;
    if (value) window.sessionStorage.setItem(PREVIEW_DIRECT_ARCHIVE_CHALLENGE_KEY, JSON.stringify(value));
    else window.sessionStorage.removeItem(PREVIEW_DIRECT_ARCHIVE_CHALLENGE_KEY);
}

export function readPreviewArchivedRecoveryChallenge(): ArchivedRecoveryChallenge | null {
    if (typeof window === "undefined") return null;
    try {
        const value = JSON.parse(
            window.sessionStorage.getItem(PREVIEW_ARCHIVED_RECOVERY_CHALLENGE_KEY) ?? "null") as
            ArchivedRecoveryChallenge | null;
        if (!value?.challengeId || Date.parse(value.expiresAt) <= Date.now()
            || value.attemptsRemaining <= 0) {
            window.sessionStorage.removeItem(PREVIEW_ARCHIVED_RECOVERY_CHALLENGE_KEY);
            return null;
        }
        return value;
    } catch {
        window.sessionStorage.removeItem(PREVIEW_ARCHIVED_RECOVERY_CHALLENGE_KEY);
        return null;
    }
}

export function writePreviewArchivedRecoveryChallenge(value: ArchivedRecoveryChallenge | null) {
    if (typeof window === "undefined") return;
    if (value) {
        window.sessionStorage.setItem(PREVIEW_ARCHIVED_RECOVERY_CHALLENGE_KEY, JSON.stringify(value));
    } else {
        window.sessionStorage.removeItem(PREVIEW_ARCHIVED_RECOVERY_CHALLENGE_KEY);
    }
}

export function previewArchivePasswordFailure(): number {
    if (typeof window === "undefined") return 0;
    try {
        const current = JSON.parse(
            window.sessionStorage.getItem(PREVIEW_DIRECT_ARCHIVE_PASSWORD_FAILURES_KEY) ?? "null") as
            { failures: number; lockedUntil: number } | null;
        if (current?.lockedUntil && current.lockedUntil > Date.now()) {
            fail("Too many incorrect password attempts. Try again later.");
        }
        const failures = (current?.failures ?? 0) + 1;
        window.sessionStorage.setItem(PREVIEW_DIRECT_ARCHIVE_PASSWORD_FAILURES_KEY,
            JSON.stringify({ failures, lockedUntil: failures >= 5 ? Date.now() + 15 * 60_000 : 0 }));
        return Math.max(0, 5 - failures);
    } catch (reason) {
        if (reason instanceof Error && reason.message.startsWith("Too many incorrect")) rethrow(reason);
        window.sessionStorage.removeItem(PREVIEW_DIRECT_ARCHIVE_PASSWORD_FAILURES_KEY);
        return 4;
    }
}

export function clearPreviewArchivePasswordFailures() {
    if (typeof window !== "undefined") {
        window.sessionStorage.removeItem(PREVIEW_DIRECT_ARCHIVE_PASSWORD_FAILURES_KEY);
    }
}

export function recordDemoClosureTransition(request: AccountClosureRequest, eventType: string,
                                     fromStatus: string | null, actorUserId: string | null, detail: string) {
    const occurredAt = new Date().toISOString();
    writeDemoAccountLifecycle([{ id: newClientId(), closureRequestId: request.id,
        targetUserId: request.targetUserId, eventType, fromStatus, toStatus: request.status,
        actorUserId, detail, occurredAt }, ...readDemoAccountLifecycle()]);
    writeDemoEssentialLogs([{ id: newClientId(), category: "ACCOUNT_LIFECYCLE", eventType,
        subjectType: "USER_ACCOUNT", subjectId: request.targetUserId, referenceId: request.id,
        actorUserId, approverUserId: actorUserId, status: request.status,
        title: `${request.targetName} · ${request.status.replaceAll("_", " ")}`, detail, occurredAt },
        ...readDemoEssentialLogs()]);
}

