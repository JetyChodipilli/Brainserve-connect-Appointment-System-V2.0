import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { evaluateRelease } from './lib/release-acceptance.mjs';

// Preparation records declared CI results; it never supplies customer consent or authenticates sign-offs.
try {
  const values = new Map(), args = process.argv.slice(2);
  for (let i = 0; i < args.length; i += 2) {
    if (!['--release-sha', '--output', '--ci-run-url'].includes(args[i]) || !args[i + 1] || values.has(args[i])) {
      throw new Error('Use --release-sha SHA --output NEW_DIRECTORY and optionally --ci-run-url URL');
    }
    values.set(args[i], args[i + 1]);
  }
  const releaseSha = values.get('--release-sha'), output = values.get('--output'), ciRunUrl = values.get('--ci-run-url') ?? null;
  if (!/^[a-f0-9]{40}$/.test(releaseSha ?? '') || !output) throw new Error('An immutable release SHA and new output directory are required');
  let requiredJobs = null;
  if (ciRunUrl !== null) {
    let url;
    try { url = new URL(ciRunUrl); } catch { throw new Error('CI evidence requires a stable GitHub Actions run URL'); }
    if (url.protocol !== 'https:' || url.hostname !== 'github.com' || url.port || url.username || url.password || url.search || url.hash
      || !/^\/[A-Za-z0-9_.-]+\/[A-Za-z0-9_.-]+\/actions\/runs\/[1-9][0-9]*$/.test(url.pathname)) {
      throw new Error('CI evidence requires a stable GitHub Actions run URL');
    }
    const input = process.env.BRAINSERVE_CI_RESULTS;
    if (!input || Buffer.byteLength(input) > 16 * 1024) throw new Error('Provide bounded predecessor CI results');
    let results;
    try { results = JSON.parse(input); } catch { throw new Error('CI results must be valid JSON'); }
    const jobs = ['frontend', 'backend', 'staging'];
    if (!results || typeof results !== 'object' || Array.isArray(results) || Object.keys(results).length !== jobs.length
      || !jobs.every(job => Object.hasOwn(results, job) && results[job]?.result === 'success')) {
      throw new Error('All three required predecessor CI jobs must succeed');
    }
    requiredJobs = Object.fromEntries(jobs.map(job => [job, 'success']));
  }
  const record = JSON.parse(readFileSync(new URL('../docs/release/acceptance.example.json', import.meta.url), 'utf8'));
  record.releaseSha = releaseSha;
  const generatedAt = new Date().toISOString();
  if (requiredJobs !== null) {
    const gate = record.gates.find(value => value.id === 'ci');
    gate.status = 'PASSED';
    gate.evidence = { reference: ciRunUrl, releaseSha, reviewedBy: 'GitHub Actions predecessor job results', reviewedAt: generatedAt };
  }
  const state = evaluateRelease(record, { expectedReleaseSha: releaseSha });
  if (state.accepted || record.decision !== 'PENDING') throw new Error('Preparation must retain a pending release decision');
  const runbook = readFileSync(new URL('../docs/release/ACCEPTANCE_RUN.md', import.meta.url), 'utf8');
  const review = readFileSync(new URL('../docs/release/ENGINEERING_REVIEW.md', import.meta.url), 'utf8');
  const folder = resolve(output);
  try { mkdirSync(folder, { mode: 0o700 }); } catch { throw new Error('Provide a new, writable packet directory; existing packets are never overwritten'); }
  const files = {
    'acceptance.json': JSON.stringify(record, null, 2) + '\n',
    'engineering-evidence.json': JSON.stringify({ schemaVersion: 1, releaseSha, generatedAt, ciRunUrl, requiredJobs,
      reviewRecord: 'ENGINEERING_REVIEW.md', customerApproval: 'PENDING', evidenceAuthentication: 'Reviewers must authenticate references against the installed candidate' }, null, 2) + '\n',
    'README.md': `# V2.0 acceptance packet\n\nCandidate: \`${releaseSha}\`\n\nCI: ${ciRunUrl ?? 'Not supplied; the CI gate remains pending.'}\n\n${runbook}`,
    'ENGINEERING_REVIEW.md': review,
  };
  try {
    for (const [name, content] of Object.entries(files)) writeFileSync(join(folder, name), content, { flag: 'wx', mode: 0o600 });
  } catch { throw new Error('Packet creation failed; keep any created files private and inspect them before preparing another packet'); }
  console.log('RELEASE_ACCEPTANCE_PACKET_PREPARED');
  console.log('Recorded release approval: pending');
  console.log(`Missing acceptance: ${state.pending.join(', ')}`);
} catch (problem) {
  console.error(problem instanceof Error ? problem.message : 'Release preparation rejected');
  process.exitCode = 1;
}
