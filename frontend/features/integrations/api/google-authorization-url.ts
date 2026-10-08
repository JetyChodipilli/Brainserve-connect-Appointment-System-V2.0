// Only the configured API's browser-binding ticket route may open a consent tab.
export function googleAuthorizationUrl(value: string, configuredBase = process.env.NEXT_PUBLIC_API_BASE_URL ?? '', browserOrigin = typeof window === 'undefined' ? '' : window.location.origin): string {
    if (!configuredBase.trim()) throw new Error('Google Calendar is not connected to a configured service.');
    // api-client supports both absolute backend URLs and a same-origin /api/v1 base.
    const api = new URL(configuredBase.trim(), browserOrigin || undefined), url = new URL(value);
    const ticket = url.searchParams.get('ticket');
    if (!['https:', 'http:'].includes(api.protocol) || api.username || api.password || api.search || api.hash
        || url.protocol !== api.protocol || url.origin !== api.origin
        || url.username || url.password || url.hash
        || url.pathname !== `${api.pathname.replace(/\/+$/, '')}/integrations/google-calendar/authorize`
        || [...url.searchParams.keys()].length !== 1 || !ticket || !/^[A-Za-z0-9_-]{16,512}$/.test(ticket)) {
        throw new Error('The consent address could not be verified. Reload connections before starting again.');
    }
    return url.href;
}
