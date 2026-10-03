import type { ImportJob, ImportKind, ImportOptions, SetupState } from "./types";

export const importLabels: Record<ImportKind, string> = { DEPARTMENTS: "Departments", EMPLOYEES: "Employee profiles", VISITORS: "Pending visits" };
export const importLimits = { maxBytes: 2_097_152, maxRows: 1_000 };
export const importPageSize = 20;

// Count logical records, including quoted multiline cells. The server still validates all fields.
export function csvRowCount(csv: string): number {
    const source = csv.replace(/^\uFEFF/, "");
    if (!source.trim()) throw new Error("Choose a CSV with a header and at least one data row.");
    if (/[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F]/.test(source)) throw new Error("The CSV contains unsupported control characters.");
    let quoted = false, afterQuote = false, fieldStart = true, rows = 0, hasContent = false;
    for (let index = 0; index < source.length; index++) {
        const char = source[index];
        if (quoted) {
            if (char === '"') { if (source[index + 1] === '"') index++; else { quoted = false; afterQuote = true; } }
            hasContent = true; continue;
        }
        if (char === '"') { if (!fieldStart || afterQuote) throw new Error("Malformed CSV: quote a complete field and escape quotes with two quotes."); quoted = true; fieldStart = false; hasContent = true; }
        else if (char === ",") { fieldStart = true; afterQuote = false; hasContent = true; }
        else if (char === "\r" || char === "\n") { if (char === "\r" && source[index + 1] === "\n") index++; if (hasContent) rows++; hasContent = false; fieldStart = true; afterQuote = false; }
        else { if (afterQuote) throw new Error("Malformed CSV: unexpected text after a closing quote."); fieldStart = false; hasContent = true; }
    }
    if (quoted) throw new Error("Malformed CSV: a quoted field is not closed.");
    if (hasContent) rows++;
    return Math.max(0, rows - 1);
}

export async function readImportFile(file: File, options: Pick<ImportOptions, "maxRows" | "maxBytes">): Promise<{ csv: string; rows: number }> {
    const maxBytes = Math.min(importLimits.maxBytes, options.maxBytes), maxRows = Math.min(importLimits.maxRows, options.maxRows);
    if (file.size > maxBytes) throw new Error(`The file exceeds ${maxBytes.toLocaleString("en-IN")} bytes (2 MiB maximum).`);
    if (!/\.csv$/i.test(file.name)) throw new Error("Choose a .csv file saved as UTF-8.");
    let csv: string;
    try { csv = new TextDecoder("utf-8", { fatal: true }).decode(await file.arrayBuffer()); }
    catch { throw new Error("The file is not valid UTF-8. Save it as CSV UTF-8 and try again."); }
    const rows = csvRowCount(csv);
    if (rows < 1 || rows > maxRows) throw new Error(`The CSV must contain 1 to ${maxRows} data rows; found ${rows}.`);
    return { csv, rows };
}

export function executionKey(id: string) { return `import-${id}`; }
export function executable(job: ImportJob, confirmed: boolean, now: number) {
    return confirmed && job.status === "PREVIEW" && Date.parse(job.expiresAt) > now && job.rows.some((row) => row.status === "VALID");
}
export function validateImportOptions(value: ImportOptions) {
    if (!value || !Array.isArray(value.allowedKinds) || value.allowedKinds.some((kind) => !(kind in importLabels))
        || !Number.isInteger(value.maxRows) || value.maxRows < 1 || !Number.isInteger(value.maxBytes) || value.maxBytes < 1
        || !Array.isArray(value.duplicatePolicies) || value.duplicatePolicies.length === 0 || value.duplicatePolicies.some((policy) => !["SKIP", "FAIL"].includes(policy)))
        throw new Error("Import capabilities could not be verified. Refresh access before continuing.");
    return value;
}
export function validateImportJob(job: ImportJob, expectedId?: string, expectedKind?: ImportKind) {
    if (!job || typeof job.id !== "string" || !job.id || (expectedId && job.id !== expectedId) || (expectedKind && job.kind !== expectedKind)
        || !(job.kind in importLabels) || !["PREVIEW", "QUEUED", "RUNNING", "COMPLETED", "EXPIRED"].includes(job.status)
        || !["SKIP", "FAIL"].includes(job.duplicatePolicy) || typeof job.checksum !== "string" || !job.checksum || typeof job.rollbackNotice !== "string" || !Number.isFinite(Date.parse(job.expiresAt))
        || !Array.isArray(job.rows) || job.rows.length > importLimits.maxRows || job.totalRows !== job.rows.length
        || [job.applied, job.skipped, job.failed].some((count) => !Number.isInteger(count) || count < 0 || count > job.totalRows)
        || job.applied + job.skipped + job.failed > job.totalRows
        || new Set(job.rows.map((row) => row?.rowNumber)).size !== job.rows.length
        || job.rows.some((row) => !row || !Number.isInteger(row.rowNumber) || row.rowNumber < 2 || !["VALID", "APPLIED", "SKIPPED", "FAILED"].includes(row.status)
            || (row.recordId !== null && typeof row.recordId !== "string") || !Array.isArray(row.errors) || row.errors.some((error) => typeof error !== "string") || !row.values || typeof row.values !== "object" || Array.isArray(row.values)
            || Object.values(row.values).some((value) => typeof value !== "string")))
        throw new Error("Import results did not match the requested job. Reload its status before continuing.");
    return job;
}
export function validateSetupState(state: SetupState) {
    if (!state || !Number.isInteger(state.revision) || state.revision < 0 || !state.officeZone || !state.policyVersion
        || !["IN_PROGRESS", "COMPLETE"].includes(state.status) || !Array.isArray(state.steps)
        || !["company", "departments", "roles", "policy", "notifications", "privacy", "review"].every((id) => state.steps.some((step) => step.id === id))
        || !state.steps.some((step) => step.id === state.currentStep) || state.steps.some((step) => typeof step.title !== "string" || typeof step.complete !== "boolean"
            || !Array.isArray(step.issues) || step.issues.some((issue) => typeof issue !== "string") || !Array.isArray(step.settingKeys) || step.settingKeys.some((key) => typeof key !== "string")))
        throw new Error("Company setup readiness could not be verified. Refresh its current state.");
    return state;
}

export function downloadCsv(filename: string, csv: string) {
    const safeName = filename.replace(/[^a-zA-Z0-9_.-]/g, "_").slice(0, 100) || "import.csv";
    const url = URL.createObjectURL(new Blob([csv], { type: "text/csv;charset=utf-8" }));
    const anchor = document.createElement("a"); anchor.href = url; anchor.download = safeName; anchor.click();
    window.setTimeout(() => URL.revokeObjectURL(url), 1_000);
}
