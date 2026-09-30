import { fallbackRoles } from "../features/settings/defaults";
import { type StaffAccount } from "../services/brainserve-api";
import { SYSTEM_ADMIN_EMAIL } from "../config/identity";
import { DEMO_CEO_ACCOUNT, DEMO_SYSTEM_ADMIN } from "./fixtures/accounts";
import { readDemoManagerAssignments } from "./manager-assignments";
import { DEMO_ACCOUNTS_KEY } from "./storage-keys";
import { type DemoProvisioningAccount } from "./types";

export function newDemoTemporaryPassword() {
    const alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";
    const random = Array.from(crypto.getRandomValues(new Uint8Array(15)),
        (value) => alphabet[value % alphabet.length]).join("");
    return `Bs!7${random}`;
}

export function readDemoAccounts(): DemoProvisioningAccount[] {
    if (typeof window === "undefined") return [DEMO_SYSTEM_ADMIN, DEMO_CEO_ACCOUNT];
    try {
        const parsed = JSON.parse(window.localStorage.getItem(DEMO_ACCOUNTS_KEY) ?? "[]");
        const accounts = Array.isArray(parsed) ? parsed : [];
        const normalizedAccounts = accounts.map((account: DemoProvisioningAccount) => ({
            ...account,
            forcePasswordChange: account.forcePasswordChange ?? false,
            status: account.status === "PENDING_APPROVAL"
            && ["ROLE_EMPLOYEE", "ROLE_RECEPTIONIST", "ROLE_SECURITY"].includes(account.role)
                ? "PENDING_HR_APPROVAL"
                : account.status,
        }));
        const activeManagerUserIds = new Set(readDemoManagerAssignments()
            .filter((assignment) => assignment.active).map((assignment) => assignment.managerUserId));
        const managerIdentityConflictExists = normalizedAccounts.some((account: DemoProvisioningAccount) =>
            account.role === "ROLE_CEO"
            && activeManagerUserIds.has(account.id)
            && normalizedAccounts.some((candidate: DemoProvisioningAccount) =>
                candidate.id !== account.id && candidate.role === "ROLE_CEO"));
        const normalized = normalizedAccounts.map((account: DemoProvisioningAccount) =>
            account.role === "ROLE_CEO"
            && activeManagerUserIds.has(account.id)
            && normalizedAccounts.some((candidate: DemoProvisioningAccount) =>
                candidate.id !== account.id && candidate.role === "ROLE_CEO")
                ? { ...account, role: "ROLE_MANAGER", status: "ACTIVE",
                    rejectedAt: null, forcePasswordChange: account.forcePasswordChange ?? false }
                : account);
        const persistedCeoAccounts = normalized.filter((account: DemoProvisioningAccount) =>
            account.role === "ROLE_CEO");
        const activeCeoCandidates = persistedCeoAccounts.filter((account: DemoProvisioningAccount) =>
            ["ACTIVE", "PENDING_APPROVAL"].includes(account.status));
        const persistedSeedCeo = activeCeoCandidates.find((account: DemoProvisioningAccount) =>
            account.id === DEMO_CEO_ACCOUNT.id
            || account.email.toLowerCase() === DEMO_CEO_ACCOUNT.email);
        const persistedCompanyCeo = activeCeoCandidates.find((account: DemoProvisioningAccount) =>
            account.id !== DEMO_CEO_ACCOUNT.id
            && account.email.toLowerCase() !== DEMO_CEO_ACCOUNT.email);
        const historicalCompanyCeo = persistedCeoAccounts
            .filter((account: DemoProvisioningAccount) => account.id !== DEMO_CEO_ACCOUNT.id
                && account.email.toLowerCase() !== DEMO_CEO_ACCOUNT.email)
            .sort((left: DemoProvisioningAccount, right: DemoProvisioningAccount) =>
                Date.parse(right.createdAt) - Date.parse(left.createdAt))[0];
        // The seed is only a fresh-browser fallback. A real active CEO created by
        // System Admin must remain authoritative even when its email differs from
        // the original preview seed.
        const canonicalCeo = persistedCompanyCeo ?? persistedSeedCeo
            ?? (managerIdentityConflictExists && historicalCompanyCeo
                ? { ...historicalCompanyCeo, status: "ACTIVE" as const, rejectedAt: null }
                : null)
            ?? (persistedCeoAccounts.length === 0 ? DEMO_CEO_ACCOUNT : null);
        const historicalDuplicateCeos = normalized
            .filter((account: DemoProvisioningAccount) => account.role === "ROLE_CEO"
                && (!canonicalCeo || (account.id !== canonicalCeo.id
                    && account.email.toLowerCase() !== canonicalCeo.email.toLowerCase())))
            .map((account: DemoProvisioningAccount) => ({
                ...account,
                status: canonicalCeo && ["ACTIVE", "PENDING_APPROVAL"].includes(account.status)
                    ? "REJECTED" : account.status,
            }));
        const withoutSeedIdentities = normalized.filter((account: DemoProvisioningAccount) =>
            account.role !== "ROLE_SYSTEM_ADMIN"
            && account.role !== "ROLE_CEO"
            && account.email !== SYSTEM_ADMIN_EMAIL);
        return [DEMO_SYSTEM_ADMIN, ...(canonicalCeo ? [canonicalCeo] : []),
            ...historicalDuplicateCeos, ...withoutSeedIdentities];
    } catch {
        return [DEMO_SYSTEM_ADMIN, DEMO_CEO_ACCOUNT];
    }
}

export function writeDemoAccounts(accounts: DemoProvisioningAccount[]) {
    if (typeof window !== "undefined") {
        window.localStorage.setItem(DEMO_ACCOUNTS_KEY, JSON.stringify(accounts));
        window.dispatchEvent(new CustomEvent("brainserve:demo-accounts-updated"));
    }
}

export async function hashDemoPassword(password: string) {
    if (crypto.subtle?.digest) {
        const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(password));
        return Array.from(new Uint8Array(digest), (byte) => byte.toString(16).padStart(2, "0")).join("");
    }
    // Standards-correct SHA-256 for non-secure local review contexts where
    // WebCrypto is unavailable. Production passwords remain Bcrypt-hashed.
    const constants = [
        0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
        0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
        0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
        0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
        0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
        0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
        0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
        0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2,
    ];
    const state = new Uint32Array([
        0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a,
        0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19,
    ]);
    const bytes = new TextEncoder().encode(password);
    const paddedLength = Math.ceil((bytes.length + 9) / 64) * 64;
    const padded = new Uint8Array(paddedLength);
    padded.set(bytes);
    padded[bytes.length] = 0x80;
    const view = new DataView(padded.buffer);
    const bitLength = bytes.length * 8;
    view.setUint32(paddedLength - 8, Math.floor(bitLength / 0x100000000));
    view.setUint32(paddedLength - 4, bitLength >>> 0);
    const rotateRight = (value: number, bits: number) =>
        ((value >>> bits) | (value << (32 - bits))) >>> 0;
    const words = new Uint32Array(64);
    for (let offset = 0; offset < paddedLength; offset += 64) {
        for (let index = 0; index < 16; index += 1) words[index] = view.getUint32(offset + index * 4);
        for (let index = 16; index < 64; index += 1) {
            const sigma0 = rotateRight(words[index - 15], 7) ^ rotateRight(words[index - 15], 18)
                ^ (words[index - 15] >>> 3);
            const sigma1 = rotateRight(words[index - 2], 17) ^ rotateRight(words[index - 2], 19)
                ^ (words[index - 2] >>> 10);
            words[index] = (words[index - 16] + sigma0 + words[index - 7] + sigma1) >>> 0;
        }
        let [a, b, c, d, e, f, g, h] = state;
        for (let index = 0; index < 64; index += 1) {
            const upperSigma1 = rotateRight(e, 6) ^ rotateRight(e, 11) ^ rotateRight(e, 25);
            const choice = (e & f) ^ (~e & g);
            const temporary1 = (h + upperSigma1 + choice + constants[index] + words[index]) >>> 0;
            const upperSigma0 = rotateRight(a, 2) ^ rotateRight(a, 13) ^ rotateRight(a, 22);
            const majority = (a & b) ^ (a & c) ^ (b & c);
            const temporary2 = (upperSigma0 + majority) >>> 0;
            h = g; g = f; f = e; e = (d + temporary1) >>> 0;
            d = c; c = b; b = a; a = (temporary1 + temporary2) >>> 0;
        }
        state[0] = (state[0] + a) >>> 0; state[1] = (state[1] + b) >>> 0;
        state[2] = (state[2] + c) >>> 0; state[3] = (state[3] + d) >>> 0;
        state[4] = (state[4] + e) >>> 0; state[5] = (state[5] + f) >>> 0;
        state[6] = (state[6] + g) >>> 0; state[7] = (state[7] + h) >>> 0;
    }
    return Array.from(state, (word) => word.toString(16).padStart(8, "0")).join("");
}

export function constantTimeHexEqual(left: string, right: string) {
    if (left.length !== right.length) return false;
    let difference = 0;
    for (let index = 0; index < left.length; index += 1) {
        difference |= left.charCodeAt(index) ^ right.charCodeAt(index);
    }
    return difference === 0;
}

export async function verifyPreviewSystemAdminPassword(email: string, currentPassword: string) {
    if (!currentPassword) return false;
    const admin = readDemoAccounts().find((item) =>
        item.email.toLowerCase() === email.trim().toLowerCase()
        && item.role === "ROLE_SYSTEM_ADMIN"
        && item.status === "ACTIVE");
    if (!admin) return false;
    return constantTimeHexEqual(admin.passwordHash, await hashDemoPassword(currentPassword));
}

export function mergeDemoStaffAccounts(current: StaffAccount[]) {
    const staffRoles = new Set(["ROLE_EMPLOYEE", "ROLE_TEAM_LEAD", "ROLE_HR_ADMIN", "ROLE_MANAGER",
        "ROLE_RECEPTIONIST", "ROLE_SECURITY"]);
    const persisted = readDemoAccounts().filter((account) => staffRoles.has(account.role));
    const persistedEmails = new Set(persisted.map((account) => account.email.toLowerCase()));
    return [
        ...current.filter((account) => !persistedEmails.has(account.email.toLowerCase())),
        ...persisted.map((account): StaffAccount => ({
            userId: account.id,
            employeeId: account.employeeId,
            fullName: account.fullName,
            email: account.email,
            roles: [account.role],
            enabled: account.status === "ACTIVE",
            forcePasswordChange: Boolean(account.forcePasswordChange),
            status: account.status,
            grantedPermissions: [],
            deniedPermissions: [],
            effectivePermissions: fallbackRoles.find((definition) =>
                definition.role === account.role)?.defaultPermissions ?? [],
        })),
    ];
}

