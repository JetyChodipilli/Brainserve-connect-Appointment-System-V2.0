import { apiDownload, apiRequest, requestSpringPage } from '../../../lib/api-client';
import type { CalendarReconciliation, Connection, CreateConnection, Delivery, DeliveryAttempt, GoogleCalendarConfig, GoogleConnectionMetadata, GoogleConsent, GoogleConsentStart, StartGoogleConsent, TestScenario } from '../types';

const read = (signal?: AbortSignal) => ({ signal, cache: 'no-store' as RequestCache });
const write = <T>(path: string, body: unknown, signal?: AbortSignal) => apiRequest<T>(path, { method: 'POST', body: JSON.stringify(body), signal }, false);
const base = '/integrations';
const google = `${base}/google-calendar`;

export const integrationsApi = {
    connections: (signal?: AbortSignal) => apiRequest<Connection[]>(`${base}/connections`, read(signal)),
    create: (value: CreateConnection, signal?: AbortSignal) => write<Connection>(`${base}/connections`, value, signal),
    reconnect: (id: string, expectedVersion: number, credential: string, credentialExpiresAt: string, signal?: AbortSignal) => write<Connection>(`${base}/connections/${encodeURIComponent(id)}/reconnect`, { expectedVersion, credential, credentialExpiresAt }, signal),
    revoke: (id: string, expectedVersion: number, signal?: AbortSignal) => write<Connection>(`${base}/connections/${encodeURIComponent(id)}/revoke`, { expectedVersion }, signal),
    test: (id: string, expectedVersion: number, scenario: TestScenario, signal?: AbortSignal) => write<unknown>(`${base}/connections/${encodeURIComponent(id)}/test`, { requestId: crypto.randomUUID(), expectedVersion, scenario }, signal),
    deliveries: (id: string, page: number, signal?: AbortSignal) => requestSpringPage<Delivery>(`${base}/connections/${encodeURIComponent(id)}/deliveries?page=${page}&size=20`, read(signal)),
    retry: (id: string, expectedVersion: number, signal?: AbortSignal) => write<Delivery>(`${base}/deliveries/${encodeURIComponent(id)}/retry`, { requestId: crypto.randomUUID(), expectedVersion }, signal),
    attempts: (id: string, signal?: AbortSignal) => apiRequest<DeliveryAttempt[]>(`${base}/deliveries/${encodeURIComponent(id)}/attempts`, read(signal)),
    googleConfig: (signal?: AbortSignal) => apiRequest<GoogleCalendarConfig>(`${google}/config`, read(signal)),
    googleConsents: (signal?: AbortSignal) => apiRequest<GoogleConsent[]>(`${google}/consents`, read(signal)),
    startGoogleConsent: (value: StartGoogleConsent, signal?: AbortSignal) => write<GoogleConsentStart>(`${google}/consents`, value, signal),
    completeGoogleConsent: (id: string, signal?: AbortSignal) => write<Connection>(`${google}/consents/${encodeURIComponent(id)}/complete`, {}, signal),
    googleConnection: (id: string, signal?: AbortSignal) => apiRequest<GoogleConnectionMetadata>(`${google}/connections/${encodeURIComponent(id)}`, read(signal)),
    recoverGoogleCalendar: (id: string, expectedVersion: number, calendarId: string, signal?: AbortSignal) => write<Connection>(`${google}/connections/${encodeURIComponent(id)}/recover`, { expectedVersion, calendarId }, signal),
    retryGoogleRevocation: (id: string, expectedVersion: number, signal?: AbortSignal) => write<GoogleConnectionMetadata>(`${google}/connections/${encodeURIComponent(id)}/revocation/retry`, { expectedVersion }, signal),
    reconcile: (id: string, expectedVersion: number, signal?: AbortSignal) => write<CalendarReconciliation>(`${base}/connections/${encodeURIComponent(id)}/reconcile`, { requestId: crypto.randomUUID(), expectedVersion }, signal),
    reconciliation: (id: string, signal?: AbortSignal) => apiRequest<CalendarReconciliation | null>(`${base}/connections/${encodeURIComponent(id)}/reconciliation`, read(signal)),
    calendarFile: (signal?: AbortSignal) => apiDownload(`${google}/calendar.ics`, signal)
};
