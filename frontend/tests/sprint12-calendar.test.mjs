import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { stripTypeScriptTypes } from 'node:module';

function runtime(file, dependencies, result) {
    const source = readFileSync(new URL(file, import.meta.url), 'utf8');
    const javascript = stripTypeScriptTypes(source.replace(/^import .*;$/gm, ''), { mode: 'transform' }).replaceAll('export ', '');
    return new Function(...Object.keys(dependencies), `${javascript}; return ${result};`)(...Object.values(dependencies));
}
const calls = [];
const request = (...arguments_) => { calls.push(arguments_); return Promise.resolve({}); };
const blob = new Blob(['BEGIN:VCALENDAR\r\nEND:VCALENDAR\r\n'], { type: 'text/calendar' });
const download = (...arguments_) => { calls.push(arguments_); return Promise.resolve(blob); };
const api = runtime('../features/integrations/api/integrations-api.ts', { apiRequest: request, requestSpringPage: request, apiDownload: download }, 'integrationsApi');
const authorizationUrl = runtime('../features/integrations/api/google-authorization-url.ts', {}, 'googleAuthorizationUrl');

test('Google metadata and reconciliation reads are authenticated transport reads with cancellation and no cache', async () => {
    calls.length = 0;
    const { signal } = new AbortController();
    await api.googleConfig(signal); await api.googleConsents(signal); await api.googleConnection('connection/a', signal); await api.reconciliation('connection/a', signal);
    assert.deepEqual(calls.map(call => call[0]), ['/integrations/google-calendar/config', '/integrations/google-calendar/consents', '/integrations/google-calendar/connections/connection%2Fa', '/integrations/connections/connection%2Fa/reconciliation']);
    for (const call of calls) { assert.equal(call[1].signal, signal); assert.equal(call[1].cache, 'no-store'); }
});

test('consent completion, reconsent, recovery, revocation and reconcile writes never automatically replay', async () => {
    calls.length = 0;
    const { signal } = new AbortController();
    const initial = { requestId: crypto.randomUUID(), label: 'Office calendar' };
    const renewal = { requestId: crypto.randomUUID(), label: 'Office calendar', connectionId: 'connection/a', expectedVersion: 9 };
    await api.startGoogleConsent(initial, signal); await api.startGoogleConsent(renewal, signal); await api.completeGoogleConsent('consent/a', signal);
    await api.recoverGoogleCalendar('connection/a', 10, 'existing@group.calendar.google.com', signal); await api.retryGoogleRevocation('connection/a', 11, signal);
    await api.reconcile('connection/a', 12, signal); await api.reconcile('connection/a', 13, signal);
    for (const call of calls) { assert.equal(call[2], false); assert.equal(call[1].method, 'POST'); assert.equal(call[1].signal, signal); }
    assert.deepEqual(JSON.parse(calls[0][1].body), initial); assert.deepEqual(JSON.parse(calls[1][1].body), renewal);
    assert.equal(calls[2][0], '/integrations/google-calendar/consents/consent%2Fa/complete'); assert.deepEqual(JSON.parse(calls[2][1].body), {});
    assert.deepEqual(JSON.parse(calls[3][1].body), { expectedVersion: 10, calendarId: 'existing@group.calendar.google.com' });
    assert.deepEqual(JSON.parse(calls[4][1].body), { expectedVersion: 11 });
    assert.match(calls[4][0], /connection%2Fa\/revocation\/retry$/);
    const jobs = calls.slice(5).map(call => JSON.parse(call[1].body));
    assert.deepEqual(jobs.map(job => job.expectedVersion), [12, 13]); assert.notEqual(jobs[0].requestId, jobs[1].requestId);
    for (const job of jobs) { assert.deepEqual(Object.keys(job).sort(), ['expectedVersion', 'requestId']); assert.match(job.requestId, /^[a-f0-9-]{36}$/); }
});

test('calendar fallback uses the session-aware binary download helper and returns a Blob', async () => {
    calls.length = 0; const { signal } = new AbortController();
    assert.equal(await api.calendarFile(signal), blob);
    assert.deepEqual(calls, [['/integrations/google-calendar/calendar.ics', signal]]);
});

test('only the configured backend authorize ticket route can be navigated', () => {
    const base = 'https://api.example.invalid/api/v1';
    const valid = `${base}/integrations/google-calendar/authorize?ticket=abcdefghijklmnopqrstuvwxyz012345`;
    assert.equal(authorizationUrl(valid, base), valid);
    assert.equal(authorizationUrl(valid, `${base}/`), valid);
    assert.equal(authorizationUrl(valid, `${base}///`), valid);
    const unsafe = ['javascript:alert(1)', 'data:text/html,hello', 'file:///authorize', valid.replace('api.example.invalid', 'evil.example.invalid'), valid.replace('https:', 'http:'), valid.replace('/authorize?', '/callback?'), valid.replace('/authorize?', '/authorize/extra?'), valid.replace('https://', 'https://admin:secret@'), `${valid}#secret`, `${valid}&ticket=abcdefghijklmnop`, `${valid}&redirect=https://evil.example.invalid`, valid.replace('abcdefghijklmnopqrstuvwxyz012345', 'short'), valid.replace('abcdefghijklmnopqrstuvwxyz012345', '%3Cscript%3E'), '/api/v1/integrations/google-calendar/authorize?ticket=abcdefghijklmnopqrstuvwxyz012345'];
    unsafe.forEach(value => assert.throws(() => authorizationUrl(value, base), undefined, value));
    assert.throws(() => authorizationUrl(valid, 'javascript:unsafe'));
    const local = 'http://localhost:8080/api/v1/integrations/google-calendar/authorize?ticket=abcdefghijklmnopqrstuvwxyz012345';
    assert.equal(authorizationUrl(local, 'http://localhost:8080/api/v1'), local);
});

test('same-origin relative API bases resolve against the browser origin and enforce its route boundary', () => {
    const origin = 'https://app.example.invalid';
    const valid = `${origin}/api/v1/integrations/google-calendar/authorize?ticket=abcdefghijklmnopqrstuvwxyz012345`;
    assert.equal(authorizationUrl(valid, '/api/v1', origin), valid);
    assert.equal(authorizationUrl(valid, '/api/v1///', origin), valid);
    assert.throws(() => authorizationUrl(valid.replace('/api/v1/', '/api/v10/'), '/api/v1', origin));
    assert.throws(() => authorizationUrl(valid.replace('app.example.invalid', 'evil.example.invalid'), '/api/v1', origin));
    assert.throws(() => authorizationUrl(valid, '', origin));
    assert.throws(() => authorizationUrl(valid, '   ', origin));
    assert.throws(() => authorizationUrl(valid, '/api/v1?redirect=evil', origin));
    assert.throws(() => authorizationUrl(valid, '/api/v1#fragment', origin));
    assert.throws(() => authorizationUrl(valid, '/api/v1'));
});
