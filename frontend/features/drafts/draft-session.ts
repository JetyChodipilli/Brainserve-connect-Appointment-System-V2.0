export type DraftForm = "TASK_CREATE" | "TASK_UPDATE" | "VISIT_INTAKE" | "COMPANY_PROFILE";
export type DraftFields = Record<string, string>;
export type DraftReceipt = { submissionKey: string; submittedAt: string; result: { formType: DraftForm; recordId?: string; referenceNumber?: string; saved?: boolean } };
export type OwnedDraft = { formType: DraftForm; contextKey: string; schemaVersion: number; revision: number; fields: DraftFields; submissionKey: string; updatedAt: string; expiresAt: string; receipt: DraftReceipt | null };
export type DraftPhase = "loading" | "ready" | "unsaved" | "saving" | "saved" | "pending" | "offline" | "conflict" | "denied" | "submitting" | "unknown" | "submitted";
export type DraftState = { phase: DraftPhase; candidate: OwnedDraft | null; saved: OwnedDraft | null; receipt: DraftReceipt | null; message: string };
export type DraftTransport = { read(): Promise<OwnedDraft | null>; save(revision: number, fields: DraftFields): Promise<OwnedDraft>; discard(revision: number): Promise<void>; submit(draft: OwnedDraft, finalFields?: DraftFields): Promise<DraftReceipt> };
const same = (a: DraftFields, b: DraftFields) => JSON.stringify(Object.entries(a).sort()) === JSON.stringify(Object.entries(b).sort());
const initial = (): DraftState => ({ phase: "loading", candidate: null, saved: null, receipt: null, message: "" });
const denied = (reason: unknown) => [401, 403, 404].includes(Number((reason as { status?: number })?.status));
const conflict = (reason: unknown) => Number((reason as { status?: number })?.status) === 409;

/** Memory-only coordinator. Persistence and authority belong to the server; every write uses the observed revision. */
export class DraftSession {
    state = initial();
    fields: DraftFields = {};
    private listeners = new Set<() => void>();
    private generation = 0;
    private savePromise: Promise<OwnedDraft | null> | null = null;
    constructor(private transport: DraftTransport, readonly identityKey = "") {}
    subscribe = (listener: () => void) => { this.listeners.add(listener); return () => { this.listeners.delete(listener); }; };
    getSnapshot = () => this.state;
    private change(values: Partial<DraftState>) { this.state = { ...this.state, ...values }; this.listeners.forEach(listener => listener()); }
    invalidate() { this.generation++; this.fields = {}; this.savePromise = null; this.change({ ...initial(), phase: "denied", message: "Your account or access changed. Reopen this form with your current account." }); }
    dispose() { this.generation++; this.fields = {}; this.listeners.clear(); }
    async load() {
        const generation = this.generation; this.change({ phase: "loading", candidate: null, message: "" });
        try {
            const draft = await this.transport.read(); if (generation !== this.generation) return;
            this.change(draft?.receipt ? { phase: "submitted", saved: draft, receipt: draft.receipt, candidate: null } : draft ? { phase: "pending", candidate: draft, saved: draft, receipt: null } : { phase: "ready", candidate: null, saved: null, receipt: null });
        } catch (reason) { if (generation === this.generation) this.failure(reason); }
    }
    setFields(fields: DraftFields) {
        if (["denied", "submitted", "submitting", "unknown"].includes(this.state.phase)) return;
        this.fields = { ...fields };
        if (["ready", "saved", "unsaved"].includes(this.state.phase)) this.change({ phase: this.state.saved && same(this.fields, this.state.saved.fields) ? "saved" : "unsaved" });
    }
    restore(): DraftFields | null {
        const candidate = this.state.candidate; if (!candidate || this.state.phase !== "pending") return null;
        this.fields = { ...candidate.fields }; this.change({ candidate: null, saved: candidate, phase: "saved", message: "Draft restored. Review current eligibility and selections before submitting." }); return { ...this.fields };
    }
    async discard() {
        const generation = this.generation, saved = this.state.candidate ?? this.state.saved;
        if (!saved) { this.change({ phase: "unsaved", candidate: null }); return; }
        this.change({ phase: "saving", message: "" });
        try { await this.transport.discard(saved.revision); if (generation === this.generation) this.change({ phase: "unsaved", candidate: null, saved: null, receipt: null, message: "Saved draft discarded. Your open form remains in memory." }); }
        catch (reason) { if (generation === this.generation) this.failure(reason); }
    }
    private failure(reason: unknown, submission = false) {
        if (denied(reason)) { this.invalidate(); return; }
        if (conflict(reason)) { this.change({ phase: "conflict", message: "This draft changed in another tab. Your open form is kept. Reload the saved draft, then explicitly restore or discard it." }); return; }
        const status = Number((reason as { status?: number })?.status);
        if (status >= 400 && status < 500) { this.change({ phase: "unsaved", message: reason instanceof Error ? reason.message : "Review the required form fields before submitting." }); return; }
        this.change({ phase: submission ? "unknown" : "offline", message: submission ? "The submission response was lost. Keep this form open and check the submission before editing or trying again." : "Draft is not saved. Your changes stay only in this open form while the service is unavailable." });
    }
    async save(): Promise<OwnedDraft | null> {
        if (this.savePromise) { await this.savePromise; return this.save(); }
        if (["loading", "pending", "conflict", "denied", "submitting", "unknown", "submitted"].includes(this.state.phase)) return null;
        if (this.state.saved && same(this.fields, this.state.saved.fields)) { this.change({ phase: "saved" }); return this.state.saved; }
        const generation = this.generation, fields = { ...this.fields }, revision = this.state.saved?.revision ?? 0;
        this.change({ phase: "saving", message: "" });
        const operation = (async () => {
            try { const saved = await this.transport.save(revision, fields); if (generation !== this.generation) return null;
                this.change({ saved, candidate: null, phase: same(this.fields, fields) ? "saved" : "unsaved" }); return saved;
            } catch (reason) { if (generation === this.generation) this.failure(reason); return null; }
        })();
        this.savePromise = operation; const result = await operation; if (this.savePromise === operation) this.savePromise = null; return result;
    }
    async submit(finalFields?: DraftFields): Promise<DraftReceipt | null> {
        if (this.state.phase === "submitted") return this.state.receipt;
        if (this.state.phase === "unknown") return this.retrySubmission(finalFields);
        let draft = await this.save(); if (!draft) return null;
        if (!same(this.fields, draft.fields)) { draft = await this.save(); if (!draft) return null; }
        return this.send(draft, finalFields);
    }
    async retrySubmission(finalFields?: DraftFields) { const saved = this.state.saved; return saved ? this.send(saved, finalFields) : null; }
    private async send(saved: OwnedDraft, finalFields?: DraftFields) {
        const generation = this.generation; this.change({ phase: "submitting", message: "" });
        try { const receipt = await this.transport.submit(saved, finalFields); if (generation !== this.generation) return null;
            this.fields = {}; this.change({ phase: "submitted", receipt, candidate: null, message: "Submission confirmed." }); return receipt;
        } catch (reason) { if (generation === this.generation) this.failure(reason, true); return null; }
    }
}
