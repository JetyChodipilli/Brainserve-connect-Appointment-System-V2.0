"use client";

import { ApiError, brainServeApi, type CompensationRecord, type EmployeeDocument, isBackendConfigured } from "../../../lib/api";
import { officeToday } from "../../../lib/appointments";
import { useModalDialog } from "../../../shared/hooks/use-modal-dialog";
import { type Employee, type Role } from "../../../shared/types/workspace";
import { rethrow } from "../../../shared/utils/errors";
import { BriefcaseBusiness, CheckCircle2, FileText, LockKeyhole, Plus, Trash2, X } from "lucide-react";
import { type FormEvent, useCallback, useEffect, useState } from "react";

export function EmployeeServicePanel({ employee, role, onClose }: {
    employee: Employee; role: Role; onClose: () => void;
}) {
    const employeeId = employee.uuid;
    const [current, setCurrent] = useState<CompensationRecord | null>(null);
    const [history, setHistory] = useState<CompensationRecord[]>([]);
    const [documents, setDocuments] = useState<EmployeeDocument[]>([]);
    const [busy, setBusy] = useState("");
    const [error, setError] = useState("");
    const [message, setMessage] = useState("");
    const canWrite = role === "HR Admin";
    const refresh = useCallback(async () => {
        if (!isBackendConfigured || !employeeId) return;
        setBusy("load"); setError("");
        try {
            const [currentResult, historyResult, documentResult] = await Promise.all([
                brainServeApi.currentCompensation(employeeId).catch((reason) => {
                    if (reason instanceof ApiError && reason.status === 404) return null;
                    rethrow(reason);
                }),
                brainServeApi.compensationHistory(employeeId),
                brainServeApi.employeeDocuments(employeeId),
            ]);
            setCurrent(currentResult);
            setHistory(historyResult);
            setDocuments(documentResult);
        } catch (reason) {
            setError(reason instanceof Error ? reason.message : "The employee record could not be loaded.");
        } finally { setBusy(""); }
    }, [employeeId]);
    useEffect(() => {
        const timer = window.setTimeout(() => void refresh(), 0);
        return () => window.clearTimeout(timer);
    }, [refresh]);
    useModalDialog(onClose);
    const money = (value: number, currency = current?.currency ?? "INR") =>
        new Intl.NumberFormat("en-IN", { style: "currency", currency, maximumFractionDigits: 2 }).format(value);
    const createCompensation = async (event: FormEvent<HTMLFormElement>) => {
        event.preventDefault();
        if (!employeeId) return;
        const data = new FormData(event.currentTarget);
        const amount = (key: string) => Number(data.get(key) || 0);
        setBusy("compensation"); setError(""); setMessage("");
        try {
            const created = await brainServeApi.createCompensation(employeeId, {
                components: {
                    basicSalary: amount("basicSalary"), hra: amount("hra"),
                    transportAllowance: amount("transportAllowance"), medicalAllowance: amount("medicalAllowance"),
                    specialAllowance: amount("specialAllowance"), otherAllowance: amount("otherAllowance"),
                    providentFundDeduction: amount("providentFundDeduction"),
                    professionalTax: amount("professionalTax"), incomeTaxEstimate: amount("incomeTaxEstimate"),
                    otherDeductions: amount("otherDeductions"),
                },
                currency: String(data.get("currency") || "INR"),
                effectiveFrom: String(data.get("effectiveFrom")),
                effectiveTo: String(data.get("effectiveTo") || "") || null,
            });
            setCurrent(created);
            setHistory((items) => [created, ...items.filter((item) => item.id !== created.id)]);
            setMessage("Compensation package saved with backend-calculated totals.");
            event.currentTarget.reset();
        } catch (reason) {
            setError(reason instanceof Error ? reason.message : "Compensation could not be saved.");
        } finally { setBusy(""); }
    };
    const uploadDocument = async (event: FormEvent<HTMLFormElement>) => {
        event.preventDefault();
        if (!employeeId) return;
        const form = event.currentTarget;
        const data = new FormData(form);
        const file = data.get("file");
        if (!(file instanceof File) || file.size === 0) return;
        setBusy("document"); setError(""); setMessage("");
        try {
            const created = await brainServeApi.uploadEmployeeDocument(employeeId,
                String(data.get("category")) as EmployeeDocument["category"], file);
            setDocuments((items) => [created, ...items]);
            setMessage("Document scanned and stored in the private employee record.");
            form.reset();
        } catch (reason) {
            setError(reason instanceof Error ? reason.message : "The document could not be uploaded.");
        } finally { setBusy(""); }
    };
    const downloadDocument = async (item: EmployeeDocument) => {
        setBusy(item.id); setError("");
        try {
            const access = await brainServeApi.employeeDocumentDownload(item.id);
            window.location.assign(access.url);
        } catch (reason) {
            setError(reason instanceof Error ? reason.message : "The secure download link could not be created.");
        } finally { setBusy(""); }
    };
    const deleteDocument = async (item: EmployeeDocument) => {
        setBusy(item.id); setError(""); setMessage("");
        try {
            await brainServeApi.deleteEmployeeDocument(item.id);
            setDocuments((items) => items.filter((document) => document.id !== item.id));
            setMessage(`${item.filename} was removed from the active document record.`);
        } catch (reason) {
            setError(reason instanceof Error ? reason.message : "The document could not be deleted.");
        } finally { setBusy(""); }
    };
    return <div className="modal-backdrop" role="presentation">
        <section className="modal employee-service-modal glass-panel" role="dialog" aria-modal="true"
                 aria-labelledby="employee-service-title">
            <header><div><span>EMPLOYEE SERVICE RECORD</span><h2 id="employee-service-title">{employee.name}</h2>
                <p>{employee.id} · {employee.department} · {employee.role}</p></div>
                <button type="button" className="icon-button" onClick={onClose} aria-label="Close employee record"><X size={18} /></button>
            </header>
            {!isBackendConfigured && <div className="governance-connection-note"><LockKeyhole size={18} />
                <span><strong>Backend connection required</strong><small>Compensation and private documents are never stored in browser Preview data.</small></span></div>}
            {isBackendConfigured && !employeeId && <div className="login-error">Refresh the employee directory to load this record’s database ID.</div>}
            {isBackendConfigured && employeeId && <>
                <section className="employee-record-section">
                    <div className="panel-heading"><div><span>COMPENSATION</span><h3>Current package</h3></div>
                        <BriefcaseBusiness size={20} /></div>
                    {current ? <div className="compensation-summary">
                        <span><small>Gross monthly</small><strong>{money(current.grossSalary, current.currency)}</strong></span>
                        <span><small>Net monthly</small><strong>{money(current.netSalary, current.currency)}</strong></span>
                        <span><small>Annual CTC</small><strong>{money(current.annualCtc, current.currency)}</strong></span>
                        <span><small>Effective</small><strong>{current.effectiveFrom}{current.effectiveTo ? ` – ${current.effectiveTo}` : " onward"}</strong></span>
                    </div> : <div className="empty-state compact-empty"><BriefcaseBusiness size={24} />
                        <strong>No active compensation package</strong><small>HR can add the first effective-dated package below.</small></div>}
                    {history.length > 0 && <div className="compensation-history">{history.map((item) =>
                        <div key={item.id}><span><strong>{money(item.netSalary, item.currency)} net</strong>
              <small>{item.effectiveFrom}{item.effectiveTo ? ` – ${item.effectiveTo}` : " · current"}</small></span>
                            <span><strong>{money(item.annualCtc, item.currency)}</strong><small>Annual CTC</small></span></div>)}</div>}
                    {canWrite && <form className="compensation-form" onSubmit={createCompensation}>
                        <div className="modal-form-grid">
                            <label>Basic salary<input name="basicSalary" type="number" min="0" step="0.01" required /></label>
                            <label>HRA<input name="hra" type="number" min="0" step="0.01" required /></label>
                            <label>Transport allowance<input name="transportAllowance" type="number" min="0" step="0.01" defaultValue="0" required /></label>
                            <label>Medical allowance<input name="medicalAllowance" type="number" min="0" step="0.01" defaultValue="0" required /></label>
                            <label>Special allowance<input name="specialAllowance" type="number" min="0" step="0.01" defaultValue="0" required /></label>
                            <label>Other allowance<input name="otherAllowance" type="number" min="0" step="0.01" defaultValue="0" required /></label>
                            <label>PF deduction<input name="providentFundDeduction" type="number" min="0" step="0.01" defaultValue="0" required /></label>
                            <label>Professional tax<input name="professionalTax" type="number" min="0" step="0.01" defaultValue="0" required /></label>
                            <label>Income-tax estimate<input name="incomeTaxEstimate" type="number" min="0" step="0.01" defaultValue="0" required /></label>
                            <label>Other deductions<input name="otherDeductions" type="number" min="0" step="0.01" defaultValue="0" required /></label>
                            <label>Currency<select name="currency" defaultValue="INR"><option value="INR">INR</option><option value="USD">USD</option></select></label>
                            <label>Effective from<input name="effectiveFrom" type="date" defaultValue={officeToday()} required /></label>
                            <label>Effective to<input name="effectiveTo" type="date" min={officeToday()} /></label>
                        </div>
                        <button className="button button-primary" disabled={Boolean(busy)}><Plus size={15} />
                            {busy === "compensation" ? "Saving…" : "Save compensation"}</button>
                    </form>}
                </section>
                <section className="employee-record-section">
                    <div className="panel-heading"><div><span>PRIVATE DOCUMENTS</span><h3>Employment record</h3></div><FileText size={20} /></div>
                    {canWrite && <form className="document-upload-form" onSubmit={uploadDocument}>
                        <label>Category<select name="category" defaultValue="EMPLOYMENT"><option value="EMPLOYMENT">Employment</option>
                            <option value="IDENTITY">Identity</option><option value="PHOTO">Photo</option><option value="OTHER">Other</option></select></label>
                        <label>File<input name="file" type="file" accept=".pdf,image/jpeg,image/png" required /></label>
                        <button className="button button-secondary" disabled={Boolean(busy)}><Plus size={15} />
                            {busy === "document" ? "Scanning…" : "Upload securely"}</button>
                    </form>}
                    <div className="employee-document-list">{documents.map((item) => <div key={item.id}>
                        <FileText size={18} /><span><strong>{item.filename}</strong><small>{item.category} · {(item.sizeBytes / 1024).toFixed(1)} KB · {item.status}</small></span>
                        <button type="button" className="button button-quiet" disabled={busy === item.id}
                                onClick={() => void downloadDocument(item)}>Download</button>
                        {canWrite && <button type="button" className="icon-button reject" disabled={busy === item.id}
                                             onClick={() => void deleteDocument(item)} aria-label={`Delete ${item.filename}`}><Trash2 size={15} /></button>}
                    </div>)}{documents.length === 0 && <div className="empty-state compact-empty"><FileText size={24} />
                        <strong>No private documents</strong><small>Files appear here after secure scanning.</small></div>}</div>
                </section>
            </>}
            {message && <div className="success-banner"><CheckCircle2 size={17} />{message}</div>}
            {error && <div className="login-error" role="alert">{error}</div>}
            <div className="modal-actions"><button type="button" className="button button-secondary" onClick={onClose}>Close</button></div>
        </section>
    </div>;
}

