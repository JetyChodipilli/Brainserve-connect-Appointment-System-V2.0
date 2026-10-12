import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync, writeFileSync, statSync, existsSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { evaluateRelease, releaseGates } from '../lib/release-acceptance.mjs';

const cli = fileURLToPath(new URL('../prepare-release-acceptance.mjs', import.meta.url));
const verifier = fileURLToPath(new URL('../verify-release-acceptance.mjs', import.meta.url));
const releaseSha = 'a'.repeat(40), runUrl = 'https://github.com/JetyChodipilli/Brainserve-connect-Appointment-System-V2.0/actions/runs/123';
const successful = { frontend: { result: 'success' }, backend: { result: 'success' }, staging: { result: 'success' } };
const run = (args, results = '') => spawnSync(process.execPath, [cli, ...args], {
  encoding: 'utf8', timeout: 5000, cwd: tmpdir(), env: { ...process.env, BRAINSERVE_CI_RESULTS: results },
});

test('a prepared candidate packet is private, complete and remains unapproved', () => {
  const parent = mkdtempSync(join(tmpdir(), 'brainserve-prepare-'));
  try {
    const output = join(parent, 'packet');
    const result = run(['--release-sha', releaseSha, '--output', output]);
    assert.equal(result.status, 0, result.stderr); assert.match(result.stdout, /RELEASE_ACCEPTANCE_PACKET_PREPARED/);
    const record = JSON.parse(readFileSync(join(output, 'acceptance.json'), 'utf8'));
    const state = evaluateRelease(record, { expectedReleaseSha: releaseSha });
    assert.equal(state.accepted, false); assert.equal(record.decision, 'PENDING');
    assert.equal(record.operationsOwner, null); assert.equal(record.supportOwner, null); assert.equal(record.approvedBy, null);
    for (const id of releaseGates) assert.ok(state.pending.includes(id));
    const evidence = JSON.parse(readFileSync(join(output, 'engineering-evidence.json'), 'utf8'));
    assert.equal(evidence.releaseSha, releaseSha); assert.equal(evidence.requiredJobs, null);
    assert.match(readFileSync(join(output, 'README.md'), 'utf8'), /two distinct pilots/i);
    assert.ok(readFileSync(join(output, 'ENGINEERING_REVIEW.md'), 'utf8').length > 0);
    if (process.platform !== 'win32') {
      assert.equal(statSync(output).mode & 0o777, 0o700);
      for (const name of ['acceptance.json', 'engineering-evidence.json', 'README.md', 'ENGINEERING_REVIEW.md']) {
        assert.equal(statSync(join(output, name)).mode & 0o777, 0o600);
      }
    }
    const check = args => spawnSync(process.execPath, [verifier, join(output, 'acceptance.json'), '--release-sha', releaseSha, ...args], { encoding: 'utf8', timeout: 5000 });
    assert.equal(check([]).status, 0); assert.equal(check(['--require-accepted']).status, 1);
  } finally { rmSync(parent, { recursive: true, force: true }); }
});

test('successful CI input closes only the CI gate, with evidence bound to the candidate', () => {
  const parent = mkdtempSync(join(tmpdir(), 'brainserve-prepare-ci-'));
  try {
    const output = join(parent, 'packet');
    const result = run(['--release-sha', releaseSha, '--output', output, '--ci-run-url', runUrl], JSON.stringify(successful));
    assert.equal(result.status, 0, result.stderr);
    const record = JSON.parse(readFileSync(join(output, 'acceptance.json'), 'utf8'));
    assert.deepEqual(record.gates.filter(gate => gate.status === 'PASSED').map(gate => gate.id), ['ci']);
    assert.equal(record.gates.find(gate => gate.id === 'ci').evidence.releaseSha, releaseSha);
    assert.equal(record.gates.find(gate => gate.id === 'ci').evidence.reference, runUrl);
    assert.equal(evaluateRelease(record, { expectedReleaseSha: releaseSha }).accepted, false);
    assert.equal(record.supportOwner, null); assert.equal(record.approvedAt, null);
    assert.deepEqual(JSON.parse(readFileSync(join(output, 'engineering-evidence.json'), 'utf8')).requiredJobs,
      { frontend: 'success', backend: 'success', staging: 'success' });
  } finally { rmSync(parent, { recursive: true, force: true }); }
});

test('preparation refuses to overwrite an existing operator acceptance packet', () => {
  const parent = mkdtempSync(join(tmpdir(), 'brainserve-prepare-existing-'));
  try {
    const output = join(parent, 'packet'), args = ['--release-sha', releaseSha, '--output', output];
    assert.equal(run(args).status, 0);
    const file = join(output, 'acceptance.json'); writeFileSync(file, 'existing operator record');
    assert.equal(run(args).status, 1); assert.equal(readFileSync(file, 'utf8'), 'existing operator record');
  } finally { rmSync(parent, { recursive: true, force: true }); }
});

test('invalid candidates, arguments and incomplete or failed CI evidence produce no packet', () => {
  const parent = mkdtempSync(join(tmpdir(), 'brainserve-prepare-reject-'));
  try {
    const output = join(parent, 'packet'), base = ['--release-sha', releaseSha, '--output', output];
    const cases = [
      [['--release-sha', 'main', '--output', output], ''],
      [[...base, '--release-sha', releaseSha], ''],
      [[...base, '--approve', 'true'], ''],
      [[...base, '--ci-run-url', 'https://example.invalid/actions/runs/123'], JSON.stringify(successful)],
      [[...base, '--ci-run-url', runUrl], '{'],
      [[...base, '--ci-run-url', runUrl], JSON.stringify({ frontend: { result: 'success' } })],
      [[...base, '--ci-run-url', runUrl], JSON.stringify({ ...successful, backend: { result: 'failure' } })],
    ];
    for (const [args, results] of cases) { assert.equal(run(args, results).status, 1); assert.equal(existsSync(output), false); }
  } finally { rmSync(parent, { recursive: true, force: true }); }
});
