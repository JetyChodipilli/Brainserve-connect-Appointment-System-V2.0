import { apiRequest } from '../../../lib/api-client';
import type { NotificationPreference, ApprovalPolicy, ApprovalQueue, ApprovalQueueItem } from '../types/notification-policy';

const privateRead = (signal?: AbortSignal) => ({ signal, cache: 'no-store' as RequestCache });
export const notificationPolicyApi = {
    preferences: (signal?: AbortSignal) => apiRequest<NotificationPreference>('/notification-preferences', privateRead(signal)),
    savePreferences: (value: NotificationPreference, signal?: AbortSignal) => apiRequest<NotificationPreference>('/notification-preferences', { method: 'PUT', body: JSON.stringify(value), signal }, false),
    queue: (overdue: boolean, page: number, signal?: AbortSignal) => apiRequest<ApprovalQueue>(`/approval-policies/queue?overdue=${overdue}&page=${page}`, privateRead(signal)),
    candidates: (stage: string, signal?: AbortSignal) => apiRequest<{ userId: string; name: string }[]>(`/approval-policies/stages/${encodeURIComponent(stage)}/candidates`, privateRead(signal)),
    delegate: (stage: string, delegateId: string, expiresAt: string, reason: string, signal?: AbortSignal) => apiRequest(`/approval-policies/stages/${encodeURIComponent(stage)}/delegations`, { method: 'POST', body: JSON.stringify({ delegateId, expiresAt, reason }), signal }, false),
    revoke: (id: string, signal?: AbortSignal) => apiRequest<void>(`/approval-policies/delegations/${encodeURIComponent(id)}`, { method: 'DELETE', signal }, false),
    policies: (signal?: AbortSignal) => apiRequest<ApprovalPolicy[]>('/approval-policies', privateRead(signal)),
    savePolicy: (value: ApprovalPolicy, signal?: AbortSignal) => apiRequest<ApprovalPolicy[]>('/approval-policies', { method: 'POST', body: JSON.stringify(value), signal }, false),
    decide: (item: ApprovalQueueItem, approved: boolean, remarks: string, signal?: AbortSignal) => apiRequest<void>(item.kind === 'WORK'
        ? `/work-insights/review-queue/${encodeURIComponent(item.id)}/decision`
        : `/appointments/${encodeURIComponent(item.resourceId)}/review-queue-decision`, {
        method: 'POST', body: JSON.stringify({ stageId: item.id, expectedVersion: item.resourceVersion, approved, remarks }), signal
    }, false)
};
