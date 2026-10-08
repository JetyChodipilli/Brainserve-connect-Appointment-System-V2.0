export type IntegrationProvider = 'SIMULATOR_CALENDAR' | 'SIMULATOR_MESSAGING' | 'GOOGLE_CALENDAR';
export type TestScenario = 'SUCCESS' | 'OUTAGE' | 'RATE_LIMITED' | 'REAUTH_REQUIRED' | 'PERMANENT_FAILURE';
export type Connection = {
    id: string; provider: IntegrationProvider; kind: string; label: string; ownerId: string;
    minimumScopes: string[]; status: string; credentialVersion: number; credentialExpiresAt: string;
    version: number; lastCheckedAt: string | null; lastResultCode: string | null; createdAt: string; updatedAt: string;
};
export type Delivery = {
    id: string; connectionId: string; businessEventId: string; eventType: string; resourceId: string; businessRevision: number; status: string;
    attempts: number; totalAttempts: number; manualRetries: number; nextAttemptAt: string | null; lastResultCode: string | null; version: number; createdAt: string; deliveredAt: string | null;
};
export type DeliveryAttempt = { id: string; deliveryId: string; attemptNumber: number; credentialVersion: number; outcome: string; startedAt: string; completedAt: string | null };
export type CreateConnection = { requestId: string; provider: IntegrationProvider; label: string; credential: string; credentialExpiresAt: string };
export type GoogleCalendarConfig = { configured: boolean; scope: string; usesDedicatedCalendar: true };
export type GoogleConsent = { id: string; connectionId: string | null; status: string; expiresAt: string; lastResultCode: string | null };
export type GoogleConsentStart = Omit<GoogleConsent, 'lastResultCode'> & { authorizationUrl: string };
export type StartGoogleConsent = { requestId: string; label: string; connectionId?: string; expectedVersion?: number };
export type GoogleConnectionMetadata = { provisioningStatus: string; revocationStatus: string; lastResultCode: string | null };
export type CalendarReconciliation = { id: string; status: string; processed: number; createdAt: string; completedAt: string | null };
