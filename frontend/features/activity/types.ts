import type { WorkEvidence } from '../workboard/types/workboard';
export type ActivityActor = { id: string | null; name: string | null; role: string | null; snapshotRecorded: boolean };
export type ActivityEvent = { id: string; occurredAt: string; eventType: string; title: string; actor: ActivityActor; cycle: number | null; evidenceVersion: number | null; departmentId: string | null; correlationId: string | null; deliveryStatus: 'REQUESTED' | 'QUEUED' | 'DELIVERED' | 'SENT' | 'FAILED' | null; note: string | null };
export type ActivityPage = { events: ActivityEvent[]; page: number; size: number; hasMore: boolean };
export type Participant = { id: string; name: string };
export type TaskComment = { id: string; author: ActivityActor; body: string | null; mentions: Participant[]; attachments: WorkEvidence[]; version: number; createdAt: string; editedAt: string | null; deletedAt: string | null; canEdit: boolean };
export type Discussion = { comments: TaskComment[]; page: number; size: number; hasMore: boolean; canComment: boolean; participants: Participant[]; evidence: WorkEvidence[] };
export type CommentDraft = { body: string; mentionUserIds: string[]; evidenceIds: string[] };
