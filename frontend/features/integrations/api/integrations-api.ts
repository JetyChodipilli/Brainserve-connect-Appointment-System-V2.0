import { apiRequest, requestSpringPage } from '../../../lib/api-client';
import type { Connection, CreateConnection, Delivery, DeliveryAttempt, TestScenario } from '../types';

const read = (signal?: AbortSignal) => ({ signal, cache: 'no-store' as RequestCache });
const write = <T>(path: string, body: unknown, signal?: AbortSignal) => apiRequest<T>(path, { method: 'POST', body: JSON.stringify(body), signal }, false);
const base = '/integrations';
export const integrationsApi = {
    connections: (signal?: AbortSignal) => apiRequest<Connection[]>(`${base}/connections`, read(signal)),
    create: (value: CreateConnection, signal?: AbortSignal) => write<Connection>(`${base}/connections`, value, signal),
    reconnect: (id: string, expectedVersion: number, credential: string, credentialExpiresAt: string, signal?: AbortSignal) => write<Connection>(`${base}/connections/${encodeURIComponent(id)}/reconnect`, { expectedVersion, credential, credentialExpiresAt }, signal),
    revoke: (id: string, expectedVersion: number, signal?: AbortSignal) => write<Connection>(`${base}/connections/${encodeURIComponent(id)}/revoke`, { expectedVersion }, signal),
    test: (id: string, expectedVersion: number, scenario: TestScenario, signal?: AbortSignal) => write<unknown>(`${base}/connections/${encodeURIComponent(id)}/test`, { requestId: crypto.randomUUID(), expectedVersion, scenario }, signal),
    deliveries: (id: string, page: number, signal?: AbortSignal) => requestSpringPage<Delivery>(`${base}/connections/${encodeURIComponent(id)}/deliveries?page=${page}&size=20`, read(signal)),
    retry: (id: string, expectedVersion: number, signal?: AbortSignal) => write<Delivery>(`${base}/deliveries/${encodeURIComponent(id)}/retry`, { requestId: crypto.randomUUID(), expectedVersion }, signal),
    attempts: (id: string, signal?: AbortSignal) => apiRequest<DeliveryAttempt[]>(`${base}/deliveries/${encodeURIComponent(id)}/attempts`, read(signal))
};
