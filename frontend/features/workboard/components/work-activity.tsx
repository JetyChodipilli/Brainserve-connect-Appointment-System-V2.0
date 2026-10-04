'use client';
import { useState } from 'react';
import { ActivityTimeline } from '../../activity/components/activity-timeline';
import { useTaskComments } from '../../activity/hooks/use-task-comments';
import type { TaskComment } from '../../activity/types';
import styles from '../../activity/activity.module.css';

export function WorkActivity({ taskId, readOnly = false }: { taskId: string; readOnly?: boolean }) { return <TaskActivity key={taskId} taskId={taskId} readOnly={readOnly} />; }
function TaskActivity({ taskId, readOnly }: { taskId: string; readOnly: boolean }) {
    const comments = useTaskComments(taskId), [confirmRemove, setConfirmRemove] = useState<string | null>(null);
    const values = comments.discussion;
    const toggle = (kind: 'mentionUserIds' | 'evidenceIds', id: string, checked: boolean) => comments.setDraft(previous => ({ ...previous, [kind]: checked ? [...previous[kind], id] : previous[kind].filter(value => value !== id) }));
    const download = async (comment: TaskComment, evidenceId: string, filename: string) => { const blob = await comments.download(comment, evidenceId); if (!blob) return; const url = URL.createObjectURL(blob), anchor = document.createElement('a'); anchor.href = url; anchor.download = filename.replace(/[\\/\x00-\x1f]/g, '_'); anchor.click(); setTimeout(() => URL.revokeObjectURL(url), 1000); };
    return <><ActivityTimeline kind="task" recordId={taskId} refreshKey={comments.revision} />
        <section className={styles.section} aria-label="Worksheet comments">
            <div className={styles.heading}><h3>Participant comments</h3><button type="button" className="button button-secondary" disabled={comments.busy} onClick={() => void comments.reload()}>Reload comments</button></div>
            <p className={styles.muted}>Discussion supports delivery. Approval and rework decisions remain separate records.</p>
            {comments.error && <div className="login-error" role="alert">{comments.error}{comments.conflict && <p>Your unsaved text is retained. Reload comments, review the current version, then explicitly save again.</p>}</div>}
            {comments.busy && <p role="status">Checking current participant access…</p>}{comments.saved && <p role="status">{comments.saved}</p>}
            <ol className={styles.comments}>{values?.comments.map(comment => <li key={comment.id} data-comment-id={comment.id}>
                <div className={styles.heading}><strong>{comment.author.name ?? 'Recorded participant'}</strong><time dateTime={comment.createdAt}>{new Date(comment.createdAt).toLocaleString('en-IN')}</time></div>
                <small>{comment.author.role?.replace(/^ROLE_/, '').replaceAll('_', ' ')}{comment.editedAt && ' · Edited'} · Revision {comment.version}</small>
                {comment.deletedAt ? <p className={styles.muted}>Comment removed. Original revisions are retained.</p> : <><p className={styles.body}>{comment.body}</p>{comment.mentions.length > 0 && <p className={styles.muted}>Mentioned: {comment.mentions.map(item => item.name).join(', ')}</p>}
                    <div className={styles.attachments}>{comment.attachments.map(evidence => <button key={evidence.id} type="button" className="button button-secondary" disabled={comments.busy} onClick={() => void download(comment, evidence.id, evidence.filename)}>Download {evidence.filename}</button>)}</div>
                    {!readOnly && comment.canEdit && <div className="work-task-actions"><button type="button" className="button button-secondary" disabled={comments.busy || comments.conflict} onClick={() => comments.edit(comment)}>Edit comment</button><button type="button" className="button button-secondary" disabled={comments.busy || comments.conflict} onClick={() => setConfirmRemove(comment.id)}>Remove comment</button></div>}
                    {confirmRemove === comment.id && !readOnly && <div><p>Remove this comment? Its retained revisions and business approvals stay available to authorized auditing.</p><button type="button" className="button button-secondary" disabled={comments.busy || comments.conflict} onClick={() => void comments.remove(comment).then(() => setConfirmRemove(null))}>Confirm removal</button><button type="button" className="button button-secondary" onClick={() => setConfirmRemove(null)}>Keep comment</button></div>}
                </>}
            </li>)}</ol>
            {values?.comments.length === 0 && <p>No participant comments yet.</p>}{values?.hasMore && <button className="button button-secondary" type="button" disabled={comments.busy} onClick={() => void comments.reload(true)}>Load more comments</button>}
            {!readOnly && values?.canComment && <form className={styles.form} onSubmit={event => { event.preventDefault(); void comments.submit(); }}>
                <label>{comments.editing ? 'Edit comment text' : 'Comment text'}<textarea maxLength={4000} required value={comments.draft.body} disabled={comments.busy} onChange={event => comments.setDraft(previous => ({ ...previous, body: event.target.value }))} /></label>
                <small>{comments.draft.body.length}/4000 · Plain text</small>
                <details><summary>Mention participants ({comments.draft.mentionUserIds.length}/20)</summary><div className={styles.options}>{values.participants.map(participant => <label key={participant.id}><input type="checkbox" checked={comments.draft.mentionUserIds.includes(participant.id)} disabled={comments.busy || !comments.draft.mentionUserIds.includes(participant.id) && comments.draft.mentionUserIds.length >= 20} onChange={event => toggle('mentionUserIds', participant.id, event.target.checked)} />{participant.name}</label>)}</div></details>
                <details><summary>Link private evidence ({comments.draft.evidenceIds.length}/5)</summary><p className={styles.muted}>Upload and scan files in Planning & evidence, then link them here.</p><div className={styles.options}>{[...new Map([...values.evidence, ...(comments.editing?.attachments ?? [])].map(item => [item.id, item])).values()].map(evidence => <label key={evidence.id}><input type="checkbox" checked={comments.draft.evidenceIds.includes(evidence.id)} disabled={comments.busy || !comments.draft.evidenceIds.includes(evidence.id) && comments.draft.evidenceIds.length >= 5} onChange={event => toggle('evidenceIds', evidence.id, event.target.checked)} />{evidence.filename}</label>)}{values.evidence.length === 0 && !comments.editing?.attachments.length && <p>No scanned evidence is linked to this worksheet.</p>}</div></details>
                <div className="work-task-actions"><button type="submit" className="button button-primary" disabled={comments.busy || comments.conflict || !comments.draft.body.trim()}>{comments.editing ? 'Save edited comment' : 'Post comment'}</button>{comments.editing && <button type="button" className="button button-secondary" disabled={comments.busy} onClick={comments.cancelEdit}>Cancel comment edit</button>}</div>
            </form>}
            {values && (readOnly || !values.canComment) && <p className={styles.muted}>Your current account can read this discussion.</p>}
        </section></>;
}
