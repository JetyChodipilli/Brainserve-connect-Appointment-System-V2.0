import { apiRequest } from "../../lib/api-client";
import type { DraftFields, DraftForm, DraftReceipt, DraftTransport, OwnedDraft } from "./draft-session";

export function draftTransport(form: DraftForm, context: string, scope: { request: () => { signal: AbortSignal; current: () => boolean; finish: () => void } }): DraftTransport {
    const path = `/drafts/${form}/${encodeURIComponent(context)}`;
    const run = async <T>(operation: (signal: AbortSignal) => Promise<T>): Promise<T> => { const request = scope.request(); try { const value = await operation(request.signal); if (!request.current()) throw new Error("Your account changed while the draft was loading."); return value; } finally { request.finish(); } };
    return {
        async read() { return (await run(signal => apiRequest<{ draft: OwnedDraft | null }>(path, { signal, cache: "no-store" }))).draft; },
        save(expectedRevision, fields) { return run(signal => apiRequest<OwnedDraft>(path, { method: "PUT", signal, body: JSON.stringify({ schemaVersion: 1, expectedRevision, fields }) }, false)); },
        discard(expectedRevision) { return run(signal => apiRequest<void>(`${path}?expectedRevision=${expectedRevision}`, { method: "DELETE", signal }, false)); },
        submit(draft, finalFields?: DraftFields) { return run(signal => apiRequest<DraftReceipt>(`${path}/submit`, { method: "POST", signal, body: JSON.stringify({ expectedRevision: draft.revision, submissionKey: draft.submissionKey, finalFields }) }, false)); },
    };
}
