"use client";

import { brainServeApi, isBackendConfigured, type StaffAccount } from "../../../services/brainserve-api";
import { hashDemoPassword, mergeDemoStaffAccounts, readDemoAccounts, writeDemoAccounts } from "../../../preview/accounts";
import { fail } from "../../../utils/errors";
import { newClientId } from "../../../utils/ids";
import { type WorkspaceState } from "../../../hooks/workspace/use-workspace-state";

export function createStaffActions(workspace: Pick<WorkspaceState, "staffAccounts" | "setStaffAccounts" | "role" | "setOperationError">) {
    const { staffAccounts, setStaffAccounts, role, setOperationError } = workspace;

    const createStaffAccount = async (email: string, temporaryPassword: string, accountRole: string) => {
        let created: StaffAccount;
        if (isBackendConfigured) created = await brainServeApi.createStaffAccount(email, temporaryPassword, accountRole);
        else {
            const normalizedEmail = email.trim().toLowerCase();
            if (readDemoAccounts().some((item) => item.email === normalizedEmail)
                || staffAccounts.some((item) => item.email === normalizedEmail)) {
                fail("A login account already uses this email address.");
            }
            const id = newClientId();
            const fullName = normalizedEmail.split("@")[0].replaceAll(".", " ");
            created = { userId: id, fullName, email: normalizedEmail, roles: [accountRole], enabled: false,
                forcePasswordChange: true, status: "PENDING_HR_APPROVAL", grantedPermissions: [],
                deniedPermissions: [], effectivePermissions: [] };
            writeDemoAccounts([...readDemoAccounts(), { id, fullName, email: normalizedEmail, role: accountRole,
                status: "PENDING_HR_APPROVAL", createdByUserId: null, approvedByUserId: null,
                createdAt: new Date().toISOString(), approvedAt: null,
                forcePasswordChange: true,
                passwordHash: await hashDemoPassword(temporaryPassword) }]);
        }
        setStaffAccounts((items) => [...items, created]);
    };
    const changeStaffEmail = async (userId: string, email: string) => {
        if (isBackendConfigured) await brainServeApi.changeStaffEmail(userId, email);
        else writeDemoAccounts(readDemoAccounts().map((item) => item.id === userId ? { ...item, email } : item));
        setStaffAccounts((items) => items.map((item) => item.userId === userId ? { ...item, email } : item));
    };
    const resetStaffPassword = async (userId: string, password: string) => {
        if (isBackendConfigured) await brainServeApi.resetStaffPassword(userId, password);
        else {
            const passwordHash = await hashDemoPassword(password);
            writeDemoAccounts(readDemoAccounts().map((item) => item.id === userId
                ? { ...item, passwordHash, forcePasswordChange: true } : item));
        }
        setStaffAccounts((items) => items.map((item) => item.userId === userId ? { ...item, forcePasswordChange: true } : item));
    };
    const setStaffEnabled = async (userId: string, enabled: boolean) => {
        if (isBackendConfigured) await brainServeApi.setStaffEnabled(userId, enabled);
        else writeDemoAccounts(readDemoAccounts().map((item) => item.id === userId
            ? { ...item, status: enabled ? "ACTIVE" : "DISABLED" } : item));
        setStaffAccounts((items) => items.map((item) => item.userId === userId
            ? { ...item, enabled, status: enabled ? "ACTIVE" : "DISABLED" } : item));
    };
    const updateStaffPermissions = async (userId: string, grants: string[], denies: string[]) => {
        const updated = isBackendConfigured
            ? await brainServeApi.permissionOverrides(userId, grants, denies)
            : { userId, grantedOverrides: grants, deniedOverrides: denies,
                effectivePermissions: [...new Set([...grants, ...(staffAccounts.find((item) => item.userId === userId)?.effectivePermissions ?? [])])]
                    .filter((permission) => !denies.includes(permission)) };
        setStaffAccounts((items) => items.map((item) => item.userId === userId ? { ...item,
            grantedPermissions: updated.grantedOverrides, deniedPermissions: updated.deniedOverrides,
            effectivePermissions: updated.effectivePermissions } : item));
    };
    const refreshStaffAccounts = async () => {
        if (!isBackendConfigured) {
            setStaffAccounts(mergeDemoStaffAccounts);
            return;
        }
        if (role !== "HR Admin") return;
        try { setStaffAccounts(await brainServeApi.staffAccounts()); }
        catch (reason) { setOperationError(reason instanceof Error ? reason.message : "Staff accounts could not be refreshed."); }
    };
    return { createStaffAccount, changeStaffEmail, resetStaffPassword, setStaffEnabled, updateStaffPermissions, refreshStaffAccounts };
}
