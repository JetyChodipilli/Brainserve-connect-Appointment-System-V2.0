import { apiRequest } from '../../../lib/api-client';
import type { DiagnosticPackage, DiagnosticPreview } from '../types';

const base = '/support/diagnostics';
const read = (signal?: AbortSignal) => ({ signal, cache: 'no-store' as RequestCache });
export const supportApi = {
    preview: (hours: number, signal?: AbortSignal) => apiRequest<DiagnosticPreview>(`${base}/preview?hours=${hours}`, read(signal)),
    packages: (signal?: AbortSignal) => apiRequest<DiagnosticPackage[]>(base, read(signal)),
    generate: (hours: number, signal?: AbortSignal) => apiRequest<DiagnosticPackage>(base, { method: 'POST', body: JSON.stringify({ requestId: crypto.randomUUID(), hours }), signal }, false),
    download: (id: string, signal?: AbortSignal) => apiRequest<DiagnosticPreview>(`${base}/${encodeURIComponent(id)}/download`, read(signal))
};
