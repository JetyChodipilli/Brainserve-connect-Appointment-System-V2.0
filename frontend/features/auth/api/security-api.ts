import { apiRequest, setAuthTokens } from "../../../lib/api-client";

export type AuthTokens = { accessToken: string; refreshToken: string; forcePasswordChange: boolean;
    mfaRequired?: boolean; mfaEnrolled?: boolean };
export type SecurityState = { mfaRequired: boolean; mfaEnrolled: boolean; mfaVerified: boolean;
    mfaVerifiedAt: string | null; stepUpRequired: boolean; stepUpExpiresAt: string | null;
    recoveryCodesRemaining: number; currentSessionId: string; privilegedRoles: string[] };
export type DeviceSession = { familyId: string; createdAt: string; lastSeenAt: string; expiresAt: string;
    mfaVerifiedAt: string | null; current: boolean };

export const securityApi = {
    status: () => apiRequest<SecurityState>("/auth/security", { cache: "no-store" }),
    sessions: (page = 0) => apiRequest<{ sessions: DeviceSession[]; limit: number; page: number; hasMore: boolean }>(`/auth/sessions?page=${page}`, { cache: "no-store" }),
    enroll: () => apiRequest<{ secret: string; otpauthUri: string }>("/auth/mfa/enrollment", { method: "POST" }),
    async verify(code: string, enrollment = false) {
        const result = await apiRequest<{ tokens: AuthTokens; recoveryCodes: string[] }>(
            enrollment ? "/auth/mfa/enrollment/confirm" : "/auth/mfa/verify",
            { method: "POST", body: JSON.stringify({ code: code.trim() }) }, false);
        setAuthTokens(result.tokens.accessToken, result.tokens.refreshToken);
        return result;
    },
    revoke: (familyId: string) => apiRequest<void>(`/auth/sessions/${encodeURIComponent(familyId)}`, { method: "DELETE" }),
    logoutAll: () => apiRequest<void>("/auth/logout-all", { method: "POST" }),
};
