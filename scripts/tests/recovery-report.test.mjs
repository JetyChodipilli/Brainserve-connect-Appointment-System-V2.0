import { test } from 'node:test';
import assert from 'node:assert/strict';
import { recoveryReport } from '../write-recovery-report.mjs';
test('recovery timings measure the outage through readiness without claiming a customer RPO', () => {
  const report = recoveryReport('a'.repeat(40), 'b'.repeat(40), [0, 1000, 1100, 1200, 2200, 4200, 5100, 5200, 6200, 6300, 7300]);
  assert.equal(report.backupSeconds, 1); assert.equal(report.restoreSeconds, 2); assert.equal(report.rehearsalRecoverySeconds, 4); assert.equal(report.rollbackSeconds, 1);
  assert.equal(report.customerRpoSeconds, null); assert.equal(report.customerRtoAccepted, false);
  assert.throws(() => recoveryReport('a'.repeat(40), 'a'.repeat(40), []), /Distinct/);
  assert.throws(() => recoveryReport('a'.repeat(40), 'b'.repeat(40), [2, 1, ...Array(9).fill(3)]), /Ordered/);
});
