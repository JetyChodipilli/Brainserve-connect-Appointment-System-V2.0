import { DEMO_RECOVERY_REQUESTS_KEY } from "./storage-keys";
import { type DemoRecoveryRequest } from "./types";

export const previewOtpIsValid = (otp: string) => {
    void otp;
    return false;
};

export function readDemoRecoveryRequests(): DemoRecoveryRequest[] {
    if (typeof window === "undefined") return [];
    try {
        const value = JSON.parse(window.localStorage.getItem(DEMO_RECOVERY_REQUESTS_KEY) ?? "[]");
        return Array.isArray(value) ? value : [];
    } catch { return []; }
}

export function writeDemoRecoveryRequests(items: DemoRecoveryRequest[]) {
    if (typeof window !== "undefined") {
        window.localStorage.setItem(DEMO_RECOVERY_REQUESTS_KEY, JSON.stringify(items));
        window.dispatchEvent(new CustomEvent("brainserve:demo-recovery-updated"));
    }
}

export function newDemoRecoveryCode() {
    const alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    const segment = () => Array.from(crypto.getRandomValues(new Uint8Array(4)),
        (value) => alphabet[value % alphabet.length]).join("");
    return `BSR-${segment()}-${segment()}-${segment()}`;
}

