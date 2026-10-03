"use client";
import { type ChangeEvent, useEffect, useEffectEvent, useRef, useState } from "react";
import { ArrowLeft, ArrowRight, Download, RefreshCw, Upload } from "lucide-react";
import { ApiError, isBackendConfigured } from "../../lib/api-client";
import type { DuplicatePolicy, ImportJob, ImportKind, ImportOptions } from "./types";
import { setupImportsApi } from "./api/setup-imports-api";
import { downloadCsv, executable, executionKey, importLabels, importPageSize, readImportFile, validateImportJob, validateImportOptions } from "./import-model";
import { useOperationScope, useSessionRevision } from "./use-operation-scope";
import styles from "./setup-imports.module.css";

export function BulkImportEntry({ accountScope, kinds, label = "Import CSV" }: { accountScope: string; kinds?: ImportKind[]; label?: string }) {
    const [open, setOpen] = useState(false), revision = useSessionRevision();
    return <div className={styles.entry}><button type="button" className={styles.button} aria-expanded={open} onClick={() => setOpen((value) => !value)}><Upload size={17} aria-hidden="true" />{open ? "Close imports" : label}</button>
        {open && <BulkImportPanel key={`${accountScope}:${revision}`} accountScope={accountScope} kinds={kinds} />}</div>;
}

export function BulkImports({ accountScope }: { accountScope: string }) {
    const revision = useSessionRevision();
    return <BulkImportPanel key={`${accountScope}:${revision}`} accountScope={accountScope} />;
}

function BulkImportPanel({ accountScope, kinds }: { accountScope: string; kinds?: ImportKind[] }) {
    const [options, setOptions] = useState<ImportOptions | null>(null), [kind, setKind] = useState<ImportKind | "">("");
    const [policy, setPolicy] = useState<DuplicatePolicy>("SKIP"), [csv, setCsv] = useState("");
    const [filename, setFilename] = useState(""), [fileRows, setFileRows] = useState(0), [fileError, setFileError] = useState("");
    const [job, setJob] = useState<ImportJob | null>(null), [busy, setBusy] = useState(isBackendConfigured ? "access" : "");
    const [error, setError] = useState(""), [message, setMessage] = useState(""), [confirmed, setConfirmed] = useState(false);
    const [page, setPage] = useState(0), [recoverId, setRecoverId] = useState(""), [now, setNow] = useState(() => Date.now());
    const scope = useOperationScope();
    const errorSummary = useRef<HTMLDivElement>(null), fileInput = useRef<HTMLInputElement>(null);
    const storageKey = `brainserve.connect.import-recovery:${accountScope}`;
    const allowedKinds = options?.allowedKinds.filter((item) => !kinds || kinds.includes(item)) ?? [];
    const clearPreview = () => { scope.invalidate(); setJob(null); setConfirmed(false); setPage(0); setError(""); setMessage(""); setBusy(""); };
    const clearFile = (resetControl = true) => { setCsv(""); setFilename(""); setFileRows(0); setFileError(""); if (resetControl && fileInput.current) fileInput.current.value = ""; };
    const showError = (reason: unknown, fallback: string) => {
        if (reason instanceof ApiError && [401, 403].includes(reason.status)) { setOptions(null); setJob(null); setKind(""); clearFile(); setConfirmed(false); }
        setError(reason instanceof Error ? reason.message : fallback);
    };
    const remember = (next: ImportJob) => {
        setJob(next); setRecoverId(next.id);
        // Only the opaque job ID is stored. Source CSV and row records stay in memory.
        try { window.sessionStorage.setItem(storageKey, next.id); } catch { /* Recovery also works with the displayed ID. */ }
    };
    const fetchOptions = () => {
        if (!isBackendConfigured) return Promise.resolve();
        const request = scope.request();
        return setupImportsApi.options(request.signal)
            .then((result) => { const next = validateImportOptions(result); if (request.current()) { setOptions(next); setKind(next.allowedKinds.find((item) => !kinds || kinds.includes(item)) ?? ""); setPolicy(next.duplicatePolicies.includes("SKIP") ? "SKIP" : "FAIL"); } })
            .catch((reason: unknown) => { if (request.current()) showError(reason, "Import access could not be loaded."); })
            .finally(() => { if (request.current()) setBusy(""); request.finish(); });
    };
    const initialize = useEffectEvent(() => fetchOptions().then(() => {
        try { setRecoverId(window.sessionStorage.getItem(storageKey) ?? ""); } catch { /* Optional browser recovery pointer. */ }
    }));
    useEffect(() => {
        if (isBackendConfigured) void initialize();
    }, []);
    const loadOptions = async () => { clearPreview(); clearFile(); setOptions(null); setKind(""); setBusy("access"); await fetchOptions(); };
    useEffect(() => { if (error || fileError) errorSummary.current?.focus(); }, [error, fileError]);
    useEffect(() => { const timer = window.setInterval(() => setNow(Date.now()), 1_000); return () => window.clearInterval(timer); }, []);

    const refreshJob = async (id: string, automatic = false) => {
        if (!options || !id.trim()) { if (!automatic) setError("Enter the job ID shown after previewing an import."); return; }
        if (!automatic) scope.invalidate();
        const request = scope.request(); if (!automatic) { setBusy("status"); setError(""); }
        try {
            const next = validateImportJob(await setupImportsApi.job(id.trim(), request.signal), id.trim());
            if (!allowedKinds.includes(next.kind)) throw new Error("This job type is outside your current import access or this screen. Open it from the correct import screen after refreshing access.");
            if (request.current()) { remember(next); setKind(next.kind); setPolicy(next.duplicatePolicy); if (!automatic) { setConfirmed(false); setPage(0); setMessage("Current durable job status loaded. Review its results before continuing."); } }
        } catch (reason) { if (request.current()) showError(reason, "Job status could not be loaded. Keep its ID and retry."); }
        finally { if (request.current() && !automatic) setBusy(""); request.finish(); }
    };
    const pollJob = useEffectEvent((id: string) => { void refreshJob(id, true); });
    useEffect(() => {
        if (!job || !["QUEUED", "RUNNING"].includes(job.status) || busy || error) return;
        const timer = window.setTimeout(() => pollJob(job.id), 2_500);
        return () => window.clearTimeout(timer);
    }, [job, busy, error]);

    const selectFile = async (event: ChangeEvent<HTMLInputElement>) => {
        const file = event.target.files?.[0]; clearPreview(); clearFile(false);
        if (!file || !options) return;
        const request = scope.request(); setBusy("file");
        try { const result = await readImportFile(file, options); if (request.current()) { setCsv(result.csv); setFilename(file.name); setFileRows(result.rows); } }
        catch (reason) { if (request.current()) setFileError(reason instanceof Error ? reason.message : "The CSV could not be read."); }
        finally { if (request.current()) setBusy(""); request.finish(); }
    };
    const template = async () => {
        if (!kind || !options) return;
        const request = scope.request(); setBusy("template"); setError("");
        try { const result = await setupImportsApi.template(kind, request.signal); if (result.kind !== kind) throw new Error("The template did not match the selected import type."); if (request.current()) { downloadCsv(result.filename, result.csv); setMessage(`Template downloaded. Use its exact columns: ${result.columns.join(", ")}.`); } }
        catch (reason) { if (request.current()) showError(reason, "The template could not be downloaded."); }
        finally { if (request.current()) setBusy(""); request.finish(); }
    };
    const preview = async () => {
        if (!kind || !options || !csv || busy) return;
        clearPreview(); const request = scope.request(); setBusy("preview");
        try {
            const next = validateImportJob(await setupImportsApi.preview(kind, policy, csv, request.signal), undefined, kind);
            if (next.duplicatePolicy !== policy || next.status !== "PREVIEW") throw new Error("The preview did not match the selected duplicate policy.");
            if (request.current()) { remember(next); setMessage("Preview saved. No rows have been created. Review every reported error before confirming."); }
        } catch (reason) { if (request.current()) showError(reason, "The import could not be previewed."); }
        finally { if (request.current()) setBusy(""); request.finish(); }
    };
    const execute = async () => {
        if (!job || busy || !options || !allowedKinds.includes(job.kind)) return;
        const retry = ["QUEUED", "RUNNING"].includes(job.status);
        if (!retry && !executable(job, confirmed, Date.now())) return;
        scope.invalidate();
        const currentJob = job, request = scope.request(); setBusy("execute"); setError(""); setMessage("");
        try {
            const next = validateImportJob(await setupImportsApi.execute(currentJob.id, currentJob.checksum, executionKey(currentJob.id), request.signal), currentJob.id, currentJob.kind);
            if (next.checksum !== currentJob.checksum) throw new Error("The job checksum changed. Reload its status before retrying.");
            if (request.current()) { remember(next); setConfirmed(false); setMessage(next.status === "COMPLETED" ? "Import finished. Review the durable outcome for each row." : "Execution accepted. The current status below will update as rows finish."); }
        } catch (reason) {
            if (request.current()) { showError(reason, "The execution response was lost."); setMessage("The service may have saved rows even when its response is lost. Reload this job’s status or retry the same job; completed rows are not created again."); }
        } finally { if (request.current()) setBusy(""); request.finish(); }
    };
    const errors = async () => {
        if (!job) return;
        const request = scope.request(); setBusy("errors"); setError("");
        try { const result = await setupImportsApi.errors(job.id, request.signal); if (request.current()) { downloadCsv(result.filename, result.csv); setMessage("Error CSV downloaded with spreadsheet formula cells neutralized by the service."); } }
        catch (reason) { if (request.current()) showError(reason, "Error rows could not be downloaded."); }
        finally { if (request.current()) setBusy(""); request.finish(); }
    };
    const pageCount = job ? Math.max(1, Math.ceil(job.rows.length / importPageSize)) : 1;
    const expired = job?.status === "EXPIRED" || (job?.status === "PREVIEW" && Date.parse(job.expiresAt) <= now);
    const unprocessed = job?.rows.filter((row) => row.status === "VALID").length ?? 0;
    return <section className={styles.feature} aria-label="Safe CSV imports"><article className={styles.panel}>
        <header className={styles.heading}><div><span className={styles.eyebrow}>CREATE RECORDS SAFELY</span><h2>Review, then import</h2><p>Use a verified template. Existing records are skipped or reported as errors; imports never overwrite them.</p></div>
            <button className={styles.button} type="button" disabled={Boolean(busy) || !isBackendConfigured} onClick={() => void loadOptions()}><RefreshCw size={16} aria-hidden="true" />Refresh import access</button></header>
        {!isBackendConfigured && <p className={styles.notice} role="status">Demo preview only. Synthetic demo data is separate from your company. CSV preview, execution and job recovery require the secure backend.</p>}
        {(error || fileError) && <div className={styles.error} role="alert" ref={errorSummary} tabIndex={-1}><h3>Import needs attention</h3>{error && <p>{error}</p>}{fileError && <a href="#bulk-import-file" onClick={(event) => { event.preventDefault(); fileInput.current?.focus(); }}>{fileError}</a>}</div>}
        {busy && <p role="status">{busy === "execute" ? "Processing this job. Keep its ID to check the result if this response is lost." : busy === "preview" ? "Validating rows; no records are being created…" : "Loading import information…"}</p>}
        {options && allowedKinds.length === 0 && <p className={styles.notice} role="status">No CSV import types are available in this screen under your current permissions. Existing workflows remain available.</p>}
        {options && allowedKinds.length > 0 && <><div className={styles.fields}>
            <label className={styles.field}>Import type<select id="bulk-import-kind" value={kind} onChange={(event) => { clearPreview(); clearFile(); setKind(event.target.value as ImportKind); }}>{allowedKinds.map((item) => <option key={item} value={item}>{importLabels[item]}</option>)}</select></label>
            <label className={styles.field}>Existing record policy<select value={policy} onChange={(event) => { clearPreview(); setPolicy(event.target.value as DuplicatePolicy); }}>{options.duplicatePolicies.map((item) => <option key={item} value={item}>{item === "SKIP" ? "Skip existing records" : "Report existing records as errors"}</option>)}</select></label>
            <div className={styles.wide}><button className={styles.button} type="button" disabled={Boolean(busy)} onClick={() => void template()}><Download size={16} aria-hidden="true" />Download {kind ? importLabels[kind].toLowerCase() : "CSV"} template</button></div>
            <label className={`${styles.field} ${styles.wide}`}>CSV UTF-8 file<input ref={fileInput} id="bulk-import-file" type="file" accept=".csv,text/csv" onChange={(event) => void selectFile(event)} aria-invalid={Boolean(fileError)} aria-describedby="bulk-import-file-help" />
                <small id="bulk-import-file-help" className={fileError ? styles.inlineError : styles.note}>{fileError || `Maximum ${Math.min(options.maxRows, 1_000)} data rows and 2 MiB. Quoted commas and multiline cells are supported.`}</small></label>
        </div>
        {filename && <p className={styles.note}>{filename} · {fileRows} data rows ready for validation.</p>}
        <p className={styles.note}>{kind === "EMPLOYEES" ? "Employee imports create profiles only. Accounts, passwords and invitations use the existing governed flow." : kind === "VISITORS" ? "Each visitor row creates a pending visit. Normal routing, approvals and check-in are still required. Dates must be ISO 8601 instants from the template." : "Department rows create new codes. Leadership assignments still use the governed organization flow."}</p>
        {kind === "EMPLOYEES" && <p className={styles.note}>Use the active department code and a joining date in YYYY-MM-DD format. Each official email must be unique; imported profiles remain subject to your department scope.</p>}
        {kind === "VISITORS" && <details className={styles.notice}><summary>Visitor CSV fields and visit types</summary><p>Use a real host employee ID and active department code. For slotStart and slotEnd, include the timezone: for example <code>2026-10-10T09:00:00+05:30</code>. Local times without an offset are invalid. The visit must follow current appointment lead time, duration and booking rules.</p><p>Supported type codes: <code>HR_VISIT</code>, <code>CEO_VISIT</code>, <code>INTERVIEW</code>, <code>VENDOR_VISIT</code>, <code>CLIENT_MEETING</code>, <code>SERVICE_VISIT</code>, <code>DELIVERY</code>, <code>OTHER</code>, <code>EMERGENCY</code>, <code>EMPLOYEE_VISIT</code>. Keep optional visitorCompany and requestedEmployeeId cells empty when they do not apply.</p></details>}
        <button type="button" className={`${styles.button} ${styles.primary}`} disabled={!csv || Boolean(busy)} onClick={() => void preview()}><Upload size={16} aria-hidden="true" />Validate &amp; preview CSV</button>
        {job && <section aria-label="Import job results" className={styles.recovery}><h3>{importLabels[job.kind]} · {job.status === "PREVIEW" ? "Preview" : job.status.replaceAll("_", " ")}</h3>
            <p className={styles.metadata}>Job ID: <strong>{job.id}</strong> · Preview expires {new Date(job.expiresAt).toLocaleString("en-IN")}</p>
            <dl className={styles.counts}><div><dt>Total rows</dt><dd>{job.totalRows}</dd></div><div><dt>Created</dt><dd>{job.applied}</dd></div><div><dt>Skipped</dt><dd>{job.skipped}</dd></div><div><dt>Errors</dt><dd>{job.failed}</dd></div></dl>
            <p role="status">{unprocessed} rows {job.status === "PREVIEW" ? "ready for creation" : "remaining"}. {expired && "This preview expired. Validate the source CSV again to create a new preview."}</p>
            <table className={styles.table}><caption>Import row outcomes · {importPageSize} rows per page</caption><thead><tr><th scope="col">Row</th><th scope="col">Outcome</th><th scope="col">Values and errors</th></tr></thead><tbody>{job.rows.slice(page * importPageSize, (page + 1) * importPageSize).map((row) => <tr key={row.rowNumber}><td data-label="CSV row">{row.rowNumber}</td><td data-label="Outcome"><span className={styles.status}>{row.status}</span></td><td><dl>{Object.entries(row.values).map(([name, value]) => <div key={name}><dt>{name}</dt><dd>{value || "—"}</dd></div>)}</dl>{row.errors.length > 0 && <ul className={styles.inlineError}>{row.errors.map((reason, index) => <li key={index}>{reason}</li>)}</ul>}{row.recordId && <p className={styles.metadata}>Created record: {row.recordId}</p>}</td></tr>)}</tbody></table>
            <nav className={styles.pagination} aria-label="Import result pages"><button className={styles.button} type="button" disabled={page === 0} onClick={() => setPage((value) => value - 1)}><ArrowLeft size={15} aria-hidden="true" />Previous rows</button><span aria-live="polite">Page {page + 1} of {pageCount}</span><button className={styles.button} type="button" disabled={page + 1 >= pageCount} onClick={() => setPage((value) => value + 1)}>Next rows<ArrowRight size={15} aria-hidden="true" /></button></nav>
            <p className={styles.notice}>{job.rollbackNotice}</p>
            {job.status === "PREVIEW" && !expired && <label className={styles.check}><input type="checkbox" checked={confirmed} onChange={(event) => setConfirmed(event.target.checked)} disabled={Boolean(busy)} /><span>I reviewed this exact preview and its errors. Create the {unprocessed} valid rows using the selected duplicate policy. Downstream effects cannot be undone by deleting this job.</span></label>}
            <div className={styles.actions}>{job.status === "PREVIEW" && <button className={`${styles.button} ${styles.primary}`} type="button" disabled={Boolean(busy) || !executable(job, confirmed, now)} onClick={() => void execute()}>Confirm &amp; create valid rows</button>}
                {["QUEUED", "RUNNING"].includes(job.status) && <button className={`${styles.button} ${styles.primary}`} type="button" disabled={Boolean(busy)} onClick={() => void execute()}>Safely resume this job</button>}
                <button className={styles.button} type="button" disabled={Boolean(busy)} onClick={() => void refreshJob(job.id)}><RefreshCw size={16} aria-hidden="true" />Reload job status</button>
                {job.failed > 0 && <button className={styles.button} type="button" disabled={Boolean(busy)} onClick={() => void errors()}><Download size={16} aria-hidden="true" />Download error CSV</button>}</div>
        </section>}
        <form className={styles.recovery} onSubmit={(event) => { event.preventDefault(); clearPreview(); clearFile(); void refreshJob(recoverId); }}><h3>Recover an existing job</h3><p className={styles.note}>Use its job ID after a lost response or page reload. Only your own currently authorized jobs can be reopened.</p>
            <label className={styles.field}>Recovery job ID<input value={recoverId} onChange={(event) => setRecoverId(event.target.value)} maxLength={100} placeholder="Job ID from your preview" /></label>
            <div className={styles.actions}><button className={styles.button} type="submit" disabled={Boolean(busy) || !recoverId.trim()}>Load existing job</button></div></form>
        </>}
        {message && <p role="status">{message}</p>}
    </article></section>;
}
