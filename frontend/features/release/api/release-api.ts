import { apiRequest } from '../../../lib/api-client';
import type { ReleaseProfile, ReleaseSnapshot } from '../types';

export const releaseApi = {
    read: (signal: AbortSignal) => apiRequest<ReleaseSnapshot>('/release-profile', { signal, cache: 'no-store' }),
    save: (expectedVersion: number, profile: ReleaseProfile, signal: AbortSignal) => apiRequest<ReleaseSnapshot>('/release-profile', {
        method: 'PUT', body: JSON.stringify({ expectedVersion, profile }), signal,
    }, false),
    kioskConfig: (signal: AbortSignal) => apiRequest<{ enabled: boolean }>('/admin/kiosks/config', { signal, cache: 'no-store' }),
};
