import { apiRequest, isBackendConfigured } from "../../../lib/api-client";
import type { DuplicatePolicy, ImportJob, ImportKind, ImportOptions, SetupState } from "../types";

const live = () => { if (!isBackendConfigured) throw new Error("Demo preview only. Connect the secure backend to use company setup and imports."); };
export const setupImportsApi = {
    setup(signal?: AbortSignal) { live(); return apiRequest<SetupState>("/company-setup", { cache: "no-store", signal }); },
    progress(expectedRevision: number, stepId: string, signal?: AbortSignal) { live(); return apiRequest<SetupState>("/company-setup/progress", {
        method: "PUT", body: JSON.stringify({ expectedRevision, stepId }), signal }); },
    complete(expectedRevision: number, signal?: AbortSignal) { live(); return apiRequest<SetupState>("/company-setup/complete", {
        method: "POST", body: JSON.stringify({ expectedRevision }), signal }); },
    options(signal?: AbortSignal) { live(); return apiRequest<ImportOptions>("/bulk-imports/options", { cache: "no-store", signal }); },
    template(kind: ImportKind, signal?: AbortSignal) { live(); return apiRequest<{ kind: ImportKind; filename: string; csv: string; columns: string[] }>(
        `/bulk-imports/templates/${kind}`, { cache: "no-store", signal }); },
    preview(kind: ImportKind, duplicatePolicy: DuplicatePolicy, csv: string, signal?: AbortSignal) { live(); return apiRequest<ImportJob>("/bulk-imports/preview", {
        method: "POST", body: JSON.stringify({ kind, duplicatePolicy, csv }), signal }); },
    execute(id: string, checksum: string, idempotencyKey: string, signal?: AbortSignal) { live(); return apiRequest<ImportJob>(
        `/bulk-imports/${encodeURIComponent(id)}/execute`, { method: "POST", body: JSON.stringify({ checksum, idempotencyKey }), signal }); },
    job(id: string, signal?: AbortSignal) { live(); return apiRequest<ImportJob>(`/bulk-imports/${encodeURIComponent(id)}`, { cache: "no-store", signal }); },
    errors(id: string, signal?: AbortSignal) { live(); return apiRequest<{ filename: string; csv: string }>(
        `/bulk-imports/${encodeURIComponent(id)}/errors`, { cache: "no-store", signal }); },
};
