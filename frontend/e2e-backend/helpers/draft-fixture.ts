import { expect, type Route } from "@playwright/test";
import type { DraftFields, DraftForm, DraftReceipt, OwnedDraft } from "../../features/drafts/draft-session";

type Result = { status?: number; json: Record<string, unknown> };

/** The API fixture models revision/receipt transport; real business authorization is covered in PostgreSQL tests. */
export function draftFixture() {
    const drafts = new Map<string, OwnedDraft>();
    const state = { loseNextSubmission: false };
    const handle = async (route: Route, submit: (form: DraftForm, context: string, fields: DraftFields) => Result) => {
        const path = new URL(route.request().url());
        const parts = path.pathname.split("/");
        if (parts[3] !== "drafts") return false;
        const form = parts[4] as DraftForm, context = decodeURIComponent(parts[5]);
        const key = `${form}/${context}`, method = route.request().method(), previous = drafts.get(key);
        const conflict = () => route.fulfill({ status: 409, json: { errorCode: "DRAFT_REVISION_CONFLICT", detail: "The draft changed in another tab." } });
        if (method === "GET") await route.fulfill({ json: { draft: previous ?? null } });
        else if (method === "PUT") {
            const body = route.request().postDataJSON();
            expect(body.schemaVersion).toBe(1);
            if (body.expectedRevision !== (previous?.revision ?? 0) || previous?.receipt) await conflict();
            else {
                const now = new Date().toISOString();
                const draft: OwnedDraft = { formType: form, contextKey: context, schemaVersion: 1, revision: (previous?.revision ?? 0) + 1,
                    fields: body.fields, submissionKey: previous?.submissionKey ?? crypto.randomUUID(), updatedAt: now,
                    expiresAt: new Date(Date.now() + 7 * 86400000).toISOString(), receipt: null };
                drafts.set(key, draft); await route.fulfill({ json: draft });
            }
        } else if (method === "DELETE") { drafts.delete(key); await route.fulfill({ status: 204 }); }
        else if (method === "POST" && parts[6] === "submit") {
            const body = route.request().postDataJSON();
            if (!previous || body.submissionKey !== previous.submissionKey || body.expectedRevision !== previous.revision) await conflict();
            else if (previous.receipt) await route.fulfill({ json: previous.receipt });
            else {
                const result = submit(form, context, previous.fields);
                if (result.status && result.status !== 200) await route.fulfill(result);
                else {
                    const receipt: DraftReceipt = { submissionKey: previous.submissionKey, submittedAt: new Date().toISOString(), result: { formType: form, ...(result.json.id ? { recordId: String(result.json.id) } : {}) } };
                    previous.receipt = receipt; previous.fields = {};
                    if (state.loseNextSubmission) { state.loseNextSubmission = false; await route.abort("failed"); } else await route.fulfill({ json: receipt });
                }
            }
        } else await route.fulfill({ status: 400 });
        return true;
    };
    return Object.assign(handle, { drafts, state });
}
