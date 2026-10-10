import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, mkdtempSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { evaluateRelease, releaseGates } from '../lib/release-acceptance.mjs';

const now = Date.parse('2026-10-09T11:00:00Z'), releaseSha = 'a'.repeat(40);
const template = () => JSON.parse(readFileSync(new URL('../../docs/release/acceptance.example.json', import.meta.url), 'utf8'));
const proof = () => ({ reference: 'secured-packet/synthetic-signoff', releaseSha, reviewedBy: 'Synthetic reviewer', reviewedAt: '2026-10-09T10:30:00Z' });
function approved() {
  return { ...template(), releaseSha, decision: 'APPROVED', approvedBy: 'Synthetic release owner', approvedAt: '2026-10-09T10:45:00Z',
    operationsOwner: 'Synthetic operator', supportOwner: 'Synthetic support', supportContact: 'support@example.invalid',
    optionalFeatures: { googleCalendar: true, slackArrival: true, kioskIntake: true },
    gates: releaseGates.map(id => ({ id, status: 'PASSED', evidence: proof(), reason: null })) };
}
test('the supplied handoff template is valid and honestly incomplete', () => {
  const result = evaluateRelease(template(), { now }); assert.equal(result.accepted, false);
  for (const id of releaseGates) assert.ok(result.pending.includes(id));
});
test('a fully populated positive control passes only against its candidate SHA', () => {
  assert.equal(evaluateRelease(approved(), { now, expectedReleaseSha: releaseSha }).accepted, true);
  assert.throws(() => evaluateRelease(approved(), { now, expectedReleaseSha: 'b'.repeat(40) }));
});
test('an approval marker cannot promote pending gates or missing owners', () => {
  for (const damage of [r => { r.gates[0].status = 'PENDING'; }, r => { r.operationsOwner = null; }, r => { r.approvedAt = null; }, r => { r.approvedAt = '2026-10-09T10:00:00Z'; }, r => { r.optionalFeatures.kioskIntake = null; }]) {
    const value = approved(); damage(value); assert.throws(() => evaluateRelease(value, { now }));
  }
});
test('missing, duplicated and unknown gates fail rather than silently shrinking acceptance', () => {
  for (const damage of [r => { r.gates.pop(); }, r => { r.gates[1].id = r.gates[0].id; }, r => { r.gates[0].id = 'made-up-gate'; }, r => { r.ready = true; }]) {
    const value = approved(); damage(value); assert.throws(() => evaluateRelease(value, { now }));
  }
});
test('evidence must belong to the candidate and have a reviewer and plausible review timestamp', () => {
  for (const damage of [r => { r.gates[0].evidence = null; }, r => { r.gates[0].evidence.releaseSha = 'b'.repeat(40); }, r => { r.gates[0].evidence.reviewedBy = ''; }, r => { r.gates[0].evidence.reviewedAt = '2026-10-10T00:00:00Z'; }, r => { r.gates[0].evidence.reference = 'https://token@example.invalid/report?token=secret'; }]) {
    const value = approved(); damage(value); assert.throws(() => evaluateRelease(value, { now }));
  }
});
test('only explicitly disabled optional features can use a reviewed exclusion', () => {
  const value = approved(), gate = value.gates.find(g => g.id === 'google-live');
  gate.status = 'NOT_APPLICABLE'; gate.reason = 'Calendar remains manual and the provider is disabled';
  assert.throws(() => evaluateRelease(value, { now }));
  value.optionalFeatures.googleCalendar = false; assert.equal(evaluateRelease(value, { now }).accepted, true);
  gate.id = 'ci'; value.gates[0].id = 'google-live'; assert.throws(() => evaluateRelease(value, { now }));
});
test('feedback requires disposition evidence; critical and high items cannot be accepted as open risk', () => {
  const value = approved();
  value.pilotFeedback = [{ id: 'PILOT-001', priority: 'HIGH', summary: 'Synthetic failed approval recovery', status: 'OPEN', owner: 'Synthetic owner', followUpBy: null, evidence: null }];
  assert.throws(() => evaluateRelease(value, { now }));
  const item = value.pilotFeedback[0]; item.status = 'FIXED'; item.evidence = proof(); assert.equal(evaluateRelease(value, { now }).accepted, true);
  item.status = 'ACCEPTED'; item.followUpBy = '2026-11-01'; assert.throws(() => evaluateRelease(value, { now }));
  item.priority = 'LOW'; assert.equal(evaluateRelease(value, { now }).accepted, true);
  item.followUpBy = '2026-02-31'; assert.throws(() => evaluateRelease(value, { now }));
  item.followUpBy = '2026-10-01'; assert.throws(() => evaluateRelease(value, { now }));
});
test('the CLI rejects the real pending template as an accepted release and requires an installed SHA', () => {
  const folder = mkdtempSync(join(tmpdir(), 'brainserve-release-'));
  try {
    const file = join(folder, 'record.json'); writeFileSync(file, JSON.stringify({ ...template(), releaseSha }));
    const cli = fileURLToPath(new URL('../verify-release-acceptance.mjs', import.meta.url));
    const run = args => spawnSync(process.execPath, [cli, file, ...args], { encoding: 'utf8', timeout: 5000 });
    const valid = run([]); assert.equal(valid.status, 0); assert.match(valid.stdout, /RELEASE_RECORD_VALID/);
    assert.equal(run(['--require-accepted']).status, 1);
    assert.equal(run(['--require-accepted', '--release-sha', releaseSha]).status, 1);
    writeFileSync(file, '{broken'); assert.equal(run([]).status, 1);
    writeFileSync(file, ' '.repeat(256 * 1024 + 1)); assert.equal(run([]).status, 1);
  } finally { rmSync(folder, { recursive: true, force: true }); }
});
