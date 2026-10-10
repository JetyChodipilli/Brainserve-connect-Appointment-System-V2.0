import { constants, openSync, readSync, fstatSync, closeSync } from 'node:fs';
import { evaluateRelease } from './lib/release-acceptance.mjs';

const [file, ...args] = process.argv.slice(2);
let requireAccepted = false, expectedReleaseSha;
try {
  if (!file) throw new Error('Provide a release record path');
  for (let i = 0; i < args.length; i++) {
    if (args[i] === '--require-accepted') requireAccepted = true;
    else if (args[i] === '--release-sha' && args[i + 1]) expectedReleaseSha = args[++i];
    else throw new Error('Unsupported release checker argument');
  }
  if (requireAccepted && !expectedReleaseSha) throw new Error('Approval checks require the installed release SHA');
  const handle = openSync(file, constants.O_RDONLY | constants.O_NONBLOCK);
  let bytes;
  try {
    const stat = fstatSync(handle);
    if (!stat.isFile() || stat.size > 256 * 1024) throw new Error('Release record must be a regular file within 256 KiB');
    const buffer = Buffer.alloc(256 * 1024 + 1); let count = 0;
    while (count < buffer.length) {
      const size = readSync(handle, buffer, count, buffer.length - count, count);
      if (size === 0) break; count += size;
    }
    if (count > 256 * 1024) throw new Error('Release record exceeded the byte limit');
    bytes = buffer.subarray(0, count);
  } finally { closeSync(handle); }
  let record; try { record = JSON.parse(bytes.toString('utf8')); } catch { throw new Error('Release record must be valid JSON'); }
  const result = evaluateRelease(record, { expectedReleaseSha });
  console.log('RELEASE_RECORD_VALID');
  console.log(`Recorded release approval: ${result.accepted ? 'accepted' : 'pending'}`);
  if (result.pending.length) console.log(`Missing acceptance: ${result.pending.join(', ')}`);
  console.log('This checker validates recorded attestations; reviewers authenticate evidence and approve the release.');
  if (requireAccepted && !result.accepted) process.exitCode = 1;
} catch (problem) {
  console.error(problem instanceof Error ? problem.message : 'Release record rejected'); process.exitCode = 1;
}
