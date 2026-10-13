import { constants, openSync, readSync, fstatSync, closeSync, writeFileSync } from 'node:fs';
import { evaluateRelease } from './lib/release-acceptance.mjs';

// Records supplied attestations only; original evidence authentication remains a reviewer responsibility.
function readJson(file, limit) {
  let handle;
  try {
    handle = openSync(file, constants.O_RDONLY | constants.O_NONBLOCK);
    const stat = fstatSync(handle);
    if (!stat.isFile() || stat.size > limit) throw new Error();
    const buffer = Buffer.alloc(limit + 1); let count = 0;
    while (count < buffer.length) {
      const size = readSync(handle, buffer, count, buffer.length - count, count);
      if (size === 0) break; count += size;
    }
    if (count > limit) throw new Error();
    return JSON.parse(buffer.subarray(0, count).toString('utf8'));
  } catch { throw new Error('Provide valid bounded JSON in regular acceptance input files'); }
  finally { if (handle !== undefined) closeSync(handle); }
}

try {
  const values = new Map(), args = process.argv.slice(2);
  for (let i = 0; i < args.length; i += 2) {
    if (!['--record', '--gate-file', '--output', '--release-sha'].includes(args[i]) || !args[i + 1] || values.has(args[i])) {
      throw new Error('Use --record FILE --gate-file FILE --output NEW_FILE --release-sha SHA');
    }
    values.set(args[i], args[i + 1]);
  }
  if (values.size !== 4 || !/^[a-f0-9]{40}$/.test(values.get('--release-sha'))) {
    throw new Error('All four arguments and an immutable release SHA are required');
  }
  const record = readJson(values.get('--record'), 256 * 1024), releaseSha = values.get('--release-sha');
  evaluateRelease(record, { expectedReleaseSha: releaseSha });
  if (record.decision !== 'PENDING') throw new Error('An approved record is immutable; prepare a new candidate packet instead');
  const gate = readJson(values.get('--gate-file'), 8 * 1024);
  const index = record.gates.findIndex(value => value.id === gate?.id);
  if (index < 0) throw new Error('Use an existing acceptance gate ID');
  record.gates[index] = gate;
  evaluateRelease(record, { expectedReleaseSha: releaseSha });
  const output = JSON.stringify(record, null, 2) + '\n';
  if (Buffer.byteLength(output) > 256 * 1024) throw new Error('Updated record exceeds the acceptance byte limit');
  try { writeFileSync(values.get('--output'), output, { flag: 'wx', mode: 0o600 }); }
  catch { throw new Error('A new writable output file is required; existing records are never overwritten'); }
  console.log('RELEASE_GATE_EVIDENCE_RECORDED');
  console.log('Recorded release approval: pending');
  console.log('Reviewers must authenticate original evidence and supply actual approval separately.');
} catch (problem) {
  console.error(problem instanceof Error ? problem.message : 'Acceptance evidence recording rejected');
  process.exitCode = 1;
}
