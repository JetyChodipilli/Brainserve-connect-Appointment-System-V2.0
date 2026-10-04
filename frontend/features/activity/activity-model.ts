import type { ActivityEvent, ActivityPage, CommentDraft } from './types';
export const activityPageSize = 50;
export function deliveryLabel(status: ActivityEvent['deliveryStatus']): string | null {
    switch (status) {
        case 'REQUESTED': return 'Notification requested';
        case 'QUEUED': return 'Queued · delivery unconfirmed';
        case 'DELIVERED': return 'Delivered to internal inbox';
        case 'SENT': return 'Email sent · receipt unconfirmed';
        case 'FAILED': return 'Delivery failed';
        default: return null;
    }
}
export function orderedActivity(previous: ActivityEvent[], next: ActivityEvent[]): ActivityEvent[] {
    const retained = new Map(previous.map(event => [event.id, event]));
    next.forEach(event => retained.set(event.id, event));
    return [...retained.values()].sort((a, b) => Date.parse(a.occurredAt) - Date.parse(b.occurredAt) || (a.id < b.id ? -1 : a.id > b.id ? 1 : 0));
}
export function validateActivityPage(value: ActivityPage, page: number): ActivityPage {
    if (!value || !Array.isArray(value.events) || value.page !== page || value.size !== activityPageSize || typeof value.hasMore !== 'boolean' || value.events.some(event => !event || typeof event.id !== 'string' || !event.id || typeof event.title !== 'string' || !Number.isFinite(Date.parse(event.occurredAt)) || !event.actor)) throw new Error('Activity response is unavailable. Reload this record.');
    return value;
}
export function validateCommentDraft(draft: CommentDraft): string | null {
    if (!draft.body.trim() || draft.body.trim().length > 4000 || /[\x00-\x08\x0b\x0c\x0e-\x1f]/.test(draft.body)) return 'Enter a plain-text comment of 1–4000 characters.';
    if (draft.mentionUserIds.length > 20 || new Set(draft.mentionUserIds).size !== draft.mentionUserIds.length || draft.evidenceIds.length > 5 || new Set(draft.evidenceIds).size !== draft.evidenceIds.length) return 'Choose up to 20 participants and five existing evidence files.';
    return null;
}
export const emptyCommentDraft = (): CommentDraft => ({ body: '', mentionUserIds: [], evidenceIds: [] });
