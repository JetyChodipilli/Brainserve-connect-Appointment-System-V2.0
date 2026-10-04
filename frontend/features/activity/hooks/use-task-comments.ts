'use client';
import { useCallback, useEffect, useRef, useState } from 'react';
import { ApiError, isBackendConfigured } from '../../../lib/api-client';
import { activityApi } from '../api/activity-api';
import { activityPageSize, emptyCommentDraft, validateCommentDraft } from '../activity-model';
import type { CommentDraft, Discussion, TaskComment } from '../types';

export function useTaskComments(taskId: string) {
    const [discussion, setDiscussion] = useState<Discussion | null>(null), [draft, setDraft] = useState<CommentDraft>(emptyCommentDraft);
    const [editing, setEditing] = useState<TaskComment | null>(null), [error, setError] = useState(''), [saved, setSaved] = useState('');
    const [busy, setBusy] = useState(false), [conflict, setConflict] = useState(false), [revision, setRevision] = useState(0);
    const alive = useRef(false), blocked = useRef(false), serial = useRef(0), abort = useRef<AbortController | null>(null), data = useRef<Discussion | null>(null);
    const request = useRef<{ fingerprint: string; id: string } | null>(null), editRef = useRef<TaskComment | null>(null);
    const clear = useCallback(() => { data.current = null; editRef.current = null; request.current = null; setDiscussion(null); setDraft(emptyCommentDraft()); setEditing(null); setSaved(''); setConflict(false); }, []);
    const accept = (value: Discussion) => { data.current = value; setDiscussion(value); };
    const begin = () => { const token = ++serial.current; abort.current?.abort(); const controller = new AbortController(); abort.current = controller; setBusy(true); setError(''); return { token, controller }; };
    const valid = (token: number) => alive.current && !blocked.current && token === serial.current;
    const failed = (cause: unknown) => { if (cause instanceof ApiError && [401, 403, 404].includes(cause.status)) clear(); setConflict(cause instanceof ApiError && cause.status === 409); setError(cause instanceof Error ? cause.message : 'The comment was not confirmed. Reload and retry explicitly.'); };
    const reload = useCallback(async (more = false) => {
        if (!alive.current || blocked.current) return;
        const { token, controller } = begin(), page = more ? (data.current?.page ?? -1) + 1 : 0;
        try {
            if (!isBackendConfigured) { clear(); setError('Comments are available in the connected workspace.'); return; }
            const value = await activityApi.comments(taskId, page, controller.signal);
            if (!valid(token)) return;
            if (!value || !Array.isArray(value.comments) || !Array.isArray(value.participants) || !Array.isArray(value.evidence) || value.page !== page || value.size !== activityPageSize || typeof value.canComment !== 'boolean') throw new Error('Comments are unavailable. Reload this worksheet.');
            const combined = more ? [...(data.current?.comments ?? []), ...value.comments] : value.comments;
            const comments = [...new Map(combined.map(comment => [comment.id, comment])).values()];
            accept({ ...value, comments }); setConflict(false);
            if (editRef.current) { const latest = comments.find(comment => comment.id === editRef.current!.id); if (latest?.canEdit) { editRef.current = latest; setEditing(latest); } else { editRef.current = null; setEditing(null); } }
        } catch (cause) { if (valid(token)) { data.current = null; setDiscussion(null); failed(cause); } }
        finally { if (valid(token)) setBusy(false); }
        // The lifecycle owns request serials; draft contents remain memory-only across a reload.
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [taskId, clear]);
    const cancelRequests = useCallback(() => { serial.current++; abort.current?.abort(); }, []);
    useEffect(() => {
        alive.current = true; blocked.current = false;
        const timer = setTimeout(() => { clear(); void reload(); }, 0);
        const changed = () => { cancelRequests(); blocked.current = true; clear(); setBusy(false); setError('Session changed. Reopen this worksheet in the current workspace.'); };
        window.addEventListener('brainserve:auth-session-changed', changed);
        window.addEventListener('brainserve:auth-session-expired', changed);
        return () => { clearTimeout(timer); alive.current = false; cancelRequests(); window.removeEventListener('brainserve:auth-session-changed', changed); window.removeEventListener('brainserve:auth-session-expired', changed); };
    }, [clear, reload, cancelRequests]);
    const mutate = async (operation: (signal: AbortSignal) => Promise<TaskComment>, resetDraft: boolean) => {
        if (!data.current?.canComment || busy || conflict || blocked.current) return;
        const { token, controller } = begin(); setSaved('');
        try {
            const value = await operation(controller.signal); if (!valid(token)) return;
            accept({ ...data.current!, comments: [...data.current!.comments.filter(comment => comment.id !== value.id), value].sort((a, b) => Date.parse(a.createdAt) - Date.parse(b.createdAt) || (a.id < b.id ? -1 : 1)) });
            if (resetDraft) { setDraft(emptyCommentDraft()); setEditing(null); editRef.current = null; request.current = null; }
            setSaved(value.deletedAt ? 'Comment removed. Its revisions and business approvals are retained.' : 'Comment saved. Notifications are requested after commit.'); setRevision(current => current + 1);
        } catch (cause) { if (valid(token)) failed(cause); }
        finally { if (valid(token)) setBusy(false); }
    };
    const submit = async () => {
        const problem = validateCommentDraft(draft); if (problem) { setError(problem); return; }
        const normalized = { body: draft.body.trim(), mentionUserIds: [...draft.mentionUserIds].sort(), evidenceIds: [...draft.evidenceIds].sort() };
        if (editRef.current) { const current = editRef.current; await mutate(signal => activityApi.edit(taskId, current.id, current.version, normalized, signal), true); }
        else {
            const fingerprint = JSON.stringify(normalized);
            if (request.current?.fingerprint !== fingerprint) request.current = { fingerprint, id: crypto.randomUUID() };
            const id = request.current.id;
            await mutate(signal => activityApi.create(taskId, id, normalized, signal), true);
        }
    };
    const edit = (comment: TaskComment) => { if (!comment.canEdit || busy) return; editRef.current = comment; setEditing(comment); request.current = null; setDraft({ body: comment.body ?? '', mentionUserIds: comment.mentions.map(item => item.id), evidenceIds: comment.attachments.map(item => item.id) }); setSaved(''); setError(''); };
    const cancelEdit = () => { editRef.current = null; setEditing(null); setDraft(emptyCommentDraft()); request.current = null; setConflict(false); setError(''); };
    const remove = (comment: TaskComment) => mutate(signal => activityApi.remove(taskId, comment.id, comment.version, signal), false);
    const download = async (comment: TaskComment, evidenceId: string) => {
        if (busy || blocked.current) return null;
        const { token, controller } = begin();
        try { const value = await activityApi.download(taskId, comment.id, evidenceId, controller.signal); return valid(token) ? value : null; }
        catch (cause) { if (valid(token)) failed(cause); return null; }
        finally { if (valid(token)) setBusy(false); }
    };
    return { discussion, draft, setDraft, editing, error, saved, busy, conflict, revision, reload, submit, edit, cancelEdit, remove, download };
}
