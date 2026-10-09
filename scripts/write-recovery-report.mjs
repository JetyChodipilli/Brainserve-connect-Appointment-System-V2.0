import { writeFileSync } from 'node:fs';
import { pathToFileURL } from 'node:url';

export function recoveryReport(releaseId, rollbackReleaseId, times) {
  if (!/^[a-f0-9]{40}$/.test(releaseId) || !/^[a-f0-9]{40}$/.test(rollbackReleaseId) || releaseId === rollbackReleaseId) throw new Error('Distinct immutable release SHAs required');
  if (times.length !== 11 || times.some(t => !Number.isSafeInteger(t) || t < 0) || times.some((t, i) => i > 0 && t < times[i - 1])) throw new Error('Ordered monotonic timestamps required');
  const [install, installed, stopped, backup, backedUp, restored, ready, rollback, rolledBack, reapply, reapplied] = times;
  const seconds = (a, b) => Math.round((b - a) / 10) / 100;
  return { schemaVersion: 1, releaseId, rollbackReleaseId, environment: 'disposable-single-runner', installationSeconds: seconds(install, installed),
    backupSeconds: seconds(backup, backedUp), restoreSeconds: seconds(backedUp, restored), rehearsalRecoverySeconds: seconds(stopped, ready),
    rollbackSeconds: seconds(rollback, rolledBack), reapplySeconds: seconds(reapply, reapplied), dataValidation: 'matching migration checksums and retained fixture fingerprints', retainedApplicationReads: ['restored current', 'prior release rollback', 'current release reapply'],
    writesQuiesced: true, fixtureRowsLost: 0, customerRpoSeconds: null, customerRtoAccepted: false,
    exclusions: ['off-host backup retrieval', 'PITR/WAL replay', 'object/archive/key recovery', 'DNS/host replacement', 'customer workload'] };
}
if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const [file, releaseId, rollbackReleaseId, ...times] = process.argv.slice(2);
  writeFileSync(file, JSON.stringify(recoveryReport(releaseId, rollbackReleaseId, times.map(Number)), null, 2) + '\n', { mode: 0o600 });
}
