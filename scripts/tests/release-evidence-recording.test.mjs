import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync, writeFileSync, statSync, existsSync, symlinkSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { releaseGates, evaluateRelease } from '../lib/release-acceptance.mjs';

const cli = fileURLToPath(new URL('../record-release-evidence.mjs', import.meta.url));
const verifier = fileURLToPath(new URL('../verify-release-acceptance.mjs', import.meta.url));
const releaseSha = 'a'.repeat(40);
const template = () => ({ ...JSON.parse(readFileSync(new URL('../../docs/release/acceptance.example.json', import.meta.url), 'utf8')), releaseSha });
const proof = () => ({ reference: 'secured-packet/synthetic-reviewed-result', releaseSha, reviewedBy: 'Synthetic reviewer', reviewedAt: '2026-10-09T10:30:00Z' });
function fixture(body) {
  const folder = mkdtempSync(join(tmpdir(), 'brainserve-record-evidence-'));
  const record = join(folder, 'record.json'), gate = join(folder, 'gate.json'), output = join(folder, 'updated.json');
  writeFileSync(record, JSON.stringify(template()));
  const args = ['--record', record, '--gate-file', gate, '--output', output, '--release-sha', releaseSha];
  const run = (values = args) => spawnSync(process.execPath, [cli, ...values], { encoding: 'utf8', timeout: 5000, cwd: tmpdir() });
  try { body({ folder, record, gate, output, args, run }); } finally { rmSync(folder, { recursive: true, force: true }); }
}

test('every existing gate records supplied candidate evidence without editing unrelated acceptance or its input', () => fixture(({ folder, record, gate, args, run }) => {
  const before = readFileSync(record, 'utf8');
  for (const id of releaseGates) {
    const output = join(folder, `${id}.json`), update = { id, status: 'PASSED', evidence: proof(), reason: null };
    writeFileSync(gate, JSON.stringify(update));
    const result = run(args.map(value => value === join(folder, 'updated.json') ? output : value));
    assert.equal(result.status, 0, result.stderr); assert.match(result.stdout, /RELEASE_GATE_EVIDENCE_RECORDED/);
    const value = JSON.parse(readFileSync(output, 'utf8')), expected = template(); expected.gates[releaseGates.indexOf(id)] = update;
    assert.deepEqual(value, expected); assert.equal(evaluateRelease(value, { expectedReleaseSha: releaseSha }).accepted, false);
    assert.equal(readFileSync(record, 'utf8'), before);
    if (process.platform !== 'win32') assert.equal(statSync(output).mode & 0o777, 0o600);
    assert.equal(result.stdout.includes(proof().reference), false); assert.equal(result.stdout.includes(proof().reviewedBy), false);
    const check = spawnSync(process.execPath, [verifier, output, '--release-sha', releaseSha], { encoding: 'utf8', timeout: 5000 });
    assert.equal(check.status, 0, check.stderr);
  }
}));

test('failed outcomes remain pending and optional exclusions require an explicitly disabled feature', () => fixture(({ record, gate, output, run }) => {
  writeFileSync(gate, JSON.stringify({ id: 'pilot-one', status: 'FAILED', evidence: proof(), reason: 'Synthetic failed role workflow' }));
  assert.equal(run().status, 0);
  const failed = JSON.parse(readFileSync(output, 'utf8')); assert.equal(evaluateRelease(failed).accepted, false);
  assert.equal(failed.gates.find(g => g.id === 'pilot-one').status, 'FAILED'); rmSync(output);
  writeFileSync(gate, JSON.stringify({ id: 'google-live', status: 'NOT_APPLICABLE', evidence: proof(), reason: 'Synthetic verified disabled configuration' }));
  assert.equal(run().status, 1); assert.equal(existsSync(output), false);
  const value = template(); value.optionalFeatures.googleCalendar = false; writeFileSync(record, JSON.stringify(value));
  assert.equal(run().status, 0);
  assert.equal(JSON.parse(readFileSync(output, 'utf8')).gates.find(g => g.id === 'google-live').status, 'NOT_APPLICABLE');
}));

test('wrong candidate, malformed declarations, invalid CLI arguments and approval edits create no output', () => fixture(({ record, gate, output, args, run }) => {
  const good = { id: 'pilot-one', status: 'PASSED', evidence: proof(), reason: null };
  for (const change of [g => { g.id = 'unknown'; }, g => { g.evidence.releaseSha = 'b'.repeat(40); }, g => { g.evidence.reviewedBy = ''; }, g => { g.status = 'APPROVED'; }, g => { g.extra = true; }, g => { g.evidence.reviewedAt = '2099-01-01T00:00:00Z'; }]) {
    const value = structuredClone(good); change(value); writeFileSync(gate, JSON.stringify(value));
    assert.equal(run().status, 1); assert.equal(existsSync(output), false);
  }
  writeFileSync(gate, JSON.stringify(good));
  for (const values of [[], args.slice(0, 6), [...args, '--approve', 'true'], [...args, '--output', output], args.map(v => v === releaseSha ? 'b'.repeat(40) : v)]) {
    assert.equal(run(values).status, 1); assert.equal(existsSync(output), false);
  }
  const approved = template(); Object.assign(approved, { decision: 'APPROVED', approvedBy: 'Synthetic approver', approvedAt: '2026-10-09T10:45:00Z', operationsOwner: 'Synthetic operations', supportOwner: 'Synthetic support', supportContact: 'support@example.invalid', optionalFeatures: { googleCalendar: true, slackArrival: true, kioskIntake: true } });
  approved.gates = releaseGates.map(id => ({ id, status: 'PASSED', evidence: proof(), reason: null }));
  assert.equal(evaluateRelease(approved, { expectedReleaseSha: releaseSha }).accepted, true);
  writeFileSync(record, JSON.stringify(approved)); const result = run(); assert.equal(result.status, 1);
  assert.match(result.stderr, /approved record is immutable/); assert.equal(existsSync(output), false);
}));

test('existing records and output symlinks are never overwritten', () => fixture(({ folder, record, gate, output, run }) => {
  writeFileSync(gate, JSON.stringify({ id: 'pilot-one', status: 'PASSED', evidence: proof(), reason: null }));
  const before = readFileSync(record, 'utf8'); writeFileSync(output, 'existing operator evidence');
  assert.equal(run().status, 1); assert.equal(readFileSync(output, 'utf8'), 'existing operator evidence'); rmSync(output);
  if (process.platform !== 'win32') {
    symlinkSync(record, output); assert.equal(run().status, 1); assert.equal(readFileSync(record, 'utf8'), before);
  }
}));

test('bounded regular inputs reject malformed JSON, directories and oversized files without leaking contents', () => fixture(({ folder, record, gate, output, args, run }) => {
  const good = JSON.stringify({ id: 'pilot-one', status: 'PASSED', evidence: proof(), reason: null });
  writeFileSync(gate, good);
  for (const contents of ['{private-synthetic-invalid', ' '.repeat(256 * 1024 + 1)]) {
    writeFileSync(record, contents); const result = run(); assert.equal(result.status, 1); assert.equal(existsSync(output), false);
    assert.equal(result.stderr.includes('private-synthetic-invalid'), false);
  }
  writeFileSync(record, JSON.stringify(template()));
  for (const contents of ['{private-synthetic-invalid', ' '.repeat(8 * 1024 + 1), 'null']) {
    writeFileSync(gate, contents); assert.equal(run().status, 1); assert.equal(existsSync(output), false);
  }
  writeFileSync(gate, good);
  assert.equal(run(args.map(v => v === gate ? folder : v)).status, 1); assert.equal(existsSync(output), false);
  const large = template();
  large.pilotFeedback = Array.from({ length: 100 }, (_, i) => ({ id: `SIZE-${i}`, priority: 'LOW', summary: '測'.repeat(500), status: 'OPEN', owner: 'o'.repeat(180), followUpBy: null,
    evidence: { ...proof(), reference: 'r'.repeat(500), reviewedBy: 'v'.repeat(120) } }));
  evaluateRelease(large, { expectedReleaseSha: releaseSha });
  const compact = JSON.stringify(large); assert.ok(Buffer.byteLength(compact) <= 256 * 1024);
  assert.ok(Buffer.byteLength(JSON.stringify(large, null, 2) + '\n') > 256 * 1024);
  writeFileSync(record, compact); const result = run(); assert.equal(result.status, 1);
  assert.match(result.stderr, /Updated record exceeds the acceptance byte limit/);
  assert.equal(existsSync(output), false); assert.equal(readFileSync(record, 'utf8'), compact);
}));
