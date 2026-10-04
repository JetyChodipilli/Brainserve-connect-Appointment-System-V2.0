import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import ts from 'typescript';
const filename = new URL('../features/drafts/draft-session.ts', import.meta.url);
const code = ts.transpileModule(readFileSync(filename, 'utf8'), { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS } }).outputText;
const loaded = { exports: {} }; new Function('module', 'exports', 'require', code)(loaded, loaded.exports, createRequire(import.meta.url));
const { DraftSession } = loaded.exports;
const error = status => Object.assign(new Error(`HTTP ${status}`), { status });
const fixture = () => {
    let saved = null, writes = 0, submissions = 0, effects = 0, unavailable = false, loseSubmit = false, denied = false;
    const transport = {
        async read() { if (denied) throw error(403); if (unavailable) throw new Error('offline'); return saved && structuredClone(saved); },
        async save(revision, fields) { if (denied) throw error(403); if (unavailable) throw new Error('offline'); if (revision !== (saved?.revision ?? 0) || saved?.receipt) throw error(409);
            writes++; saved = { formType: 'TASK_CREATE', contextKey: 'new', schemaVersion: 1, revision: revision + 1, fields: { ...fields }, submissionKey: saved?.submissionKey ?? 'owned-submission-key', updatedAt: '2026-10-04T00:00:00Z', expiresAt: '2026-10-11T00:00:00Z', receipt: null }; return structuredClone(saved); },
        async discard(revision) { if (denied) throw error(403); if (unavailable) throw new Error('offline'); if (revision !== saved?.revision) throw error(409); saved = null; },
        async submit(draft) { submissions++; if (denied) throw error(403); if (unavailable) throw new Error('offline'); if (draft.submissionKey !== saved?.submissionKey) throw error(409);
            if (!saved.receipt) { effects++; saved.receipt = { submissionKey: saved.submissionKey, submittedAt: '2026-10-04T01:00:00Z', result: { formType: 'TASK_CREATE', recordId: 'real-task' } }; }
            if (loseSubmit) { loseSubmit = false; throw new Error('response lost'); } return saved.receipt; },
    };
    return { transport, session: () => new DraftSession(transport), get saved() { return saved; }, get counts() { return { writes, submissions, effects }; }, set unavailable(value) { unavailable = value; }, set denied(value) { denied = value; }, set loseSubmit(value) { loseSubmit = value; } };
};
test('autosave uses observed revision; restore is explicit and does not replay business writes', async () => {
    const f = fixture(), first = f.session(); await first.load(); first.setFields({ title: 'Real first draft', description: 'Still editing' }); await first.save(); assert.equal(first.state.phase, 'saved'); assert.equal(f.saved.revision, 1);
    const next = f.session(); next.setFields({ title: 'Open form' }); await next.load(); assert.equal(next.state.phase, 'pending'); assert.equal(next.fields.title, 'Open form'); assert.equal(await next.save(), null); assert.deepEqual(f.counts, { writes: 1, submissions: 0, effects: 0 });
    assert.equal(next.restore().title, 'Real first draft'); next.setFields({ title: 'Reviewed draft' }); await next.save(); assert.equal(f.saved.revision, 2); assert.equal(f.counts.submissions, 0);
});
test('two tabs cannot overwrite and conflict preserves local form until explicit choice', async () => {
    const f = fixture(), first = f.session(), second = f.session(); await Promise.all([first.load(), second.load()]); first.setFields({ note: 'Tab A' }); second.setFields({ note: 'Tab B retained' });
    await first.save(); await second.save(); assert.equal(second.state.phase, 'conflict'); assert.equal(second.fields.note, 'Tab B retained'); assert.equal(f.saved.fields.note, 'Tab A');
    await second.load(); assert.equal(second.state.phase, 'pending'); assert.equal(second.fields.note, 'Tab B retained'); await second.discard(); await second.save(); assert.equal(f.saved.fields.note, 'Tab B retained');
});
test('offline retains only open memory and requires reconnect before autosave', async () => {
    const f = fixture(), form = f.session(); await form.load(); form.setFields({ note: 'Memory only' }); f.unavailable = true; await form.save(); assert.equal(form.state.phase, 'offline'); assert.equal(f.saved, null); assert.match(form.state.message, /only in this open form/);
    form.setFields({ note: 'Continued offline editing' }); assert.equal(form.state.phase, 'offline'); assert.equal(form.fields.note, 'Continued offline editing'); assert.equal(f.counts.writes, 0);
    f.unavailable = false; await form.load(); await form.save(); assert.equal(f.saved.fields.note, 'Continued offline editing');
});
test('lost successful submit is idempotent and does not auto-replay when reconnecting', async () => {
    const f = fixture(), form = f.session(); await form.load(); form.setFields({ title: 'Explicit submit' }); f.loseSubmit = true; assert.equal(await form.submit(), null); assert.equal(form.state.phase, 'unknown'); assert.deepEqual(f.counts, { writes: 1, submissions: 1, effects: 1 });
    form.setFields({ title: 'Cannot change uncertain submitted payload' }); assert.equal(form.fields.title, 'Explicit submit'); await form.load(); assert.equal(form.state.phase, 'submitted'); assert.equal(form.state.receipt.result.recordId, 'real-task'); assert.equal(f.counts.submissions, 1);
    assert.equal((await form.submit()).result.recordId, 'real-task'); assert.equal(f.counts.effects, 1);
});
test('account changes invalidate late reads and erase prior fields and receipts', async () => {
    let resolve; const form = new DraftSession({ read: () => new Promise(done => { resolve = done; }) }); form.setFields({ note: 'Former account private note' }); const pending = form.load(); form.invalidate(); resolve({ fields: { note: 'Late former owner response' }, receipt: null }); await pending;
    assert.equal(form.state.phase, 'denied'); assert.deepEqual(form.fields, {}); assert.equal(form.state.candidate, null); assert.equal(form.state.saved, null);
});
test('permission revocation erases restored content and prevents submission', async () => {
    const f = fixture(), form = f.session(); await form.load(); form.setFields({ note: 'Private draft' }); await form.save(); f.denied = true; await form.load(); assert.equal(form.state.phase, 'denied'); assert.deepEqual(form.fields, {}); assert.equal(await form.submit(), null); assert.equal(f.counts.effects, 0);
});
test('edits while a draft write is in flight remain unsaved until a newer revision is written', async () => {
    const f = fixture(); let release; const transport = { ...f.transport, save: (revision, fields) => new Promise(resolve => { release = async () => resolve(await f.transport.save(revision, fields)); }) };
    const form = new DraftSession(transport); await form.load(); form.setFields({ note: 'First edit' }); const save = form.save(); form.setFields({ note: 'Second edit' }); await release(); await save; assert.equal(form.state.phase, 'unsaved'); assert.equal(f.saved.fields.note, 'First edit');
    const newer = form.save(); await release(); await newer; assert.equal(f.saved.fields.note, 'Second edit'); assert.equal(form.state.phase, 'saved');
});
