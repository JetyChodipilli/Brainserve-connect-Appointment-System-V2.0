import { apiRequest, apiDownload } from '../../../lib/api-client';
import { activityPageSize } from '../activity-model';
import type { ActivityPage, Discussion, TaskComment, CommentDraft } from '../types';
const taskPath = (id: string) => `/work-tasks/${encodeURIComponent(id)}`;
export const activityApi = {
    timeline(kind: 'task' | 'appointment', id: string, page: number, signal?: AbortSignal) {
        const path = kind === 'task' ? taskPath(id) : `/appointments/${encodeURIComponent(id)}`;
        return apiRequest<ActivityPage>(`${path}/activity?page=${page}&size=${activityPageSize}`, { signal, cache: 'no-store' });
    },
    comments(id: string, page: number, signal?: AbortSignal) { return apiRequest<Discussion>(`${taskPath(id)}/comments?page=${page}&size=${activityPageSize}`, { signal, cache: 'no-store' }); },
    create(id: string, clientRequestId: string, draft: CommentDraft, signal?: AbortSignal) { return apiRequest<TaskComment>(`${taskPath(id)}/comments`, { method: 'POST', signal, body: JSON.stringify({ clientRequestId, ...draft }) }, false); },
    edit(id: string, comment: string, expectedVersion: number, draft: CommentDraft, signal?: AbortSignal) { return apiRequest<TaskComment>(`${taskPath(id)}/comments/${encodeURIComponent(comment)}`, { method: 'PUT', signal, body: JSON.stringify({ expectedVersion, ...draft }) }, false); },
    remove(id: string, comment: string, expectedVersion: number, signal?: AbortSignal) { return apiRequest<TaskComment>(`${taskPath(id)}/comments/${encodeURIComponent(comment)}?expectedVersion=${expectedVersion}`, { method: 'DELETE', signal }, false); },
    download(id: string, comment: string, evidence: string, signal?: AbortSignal) { return apiDownload(`${taskPath(id)}/comments/${encodeURIComponent(comment)}/evidence/${encodeURIComponent(evidence)}/download`, signal); },
};
