// Checks recorded attestations only. Evidence authentication and customer approval remain human responsibilities.
export const releaseGates = ['ci', 'compatibility', 'second-install', 'setup-timing', 'pilot-one', 'pilot-two', 'capacity-soak', 'manual-accessibility', 'full-recovery', 'alert-routing', 'support-handoff', 'offboarding', 'google-live', 'slack-live', 'kiosk-hardware'];
const optional = { 'google-live': 'googleCalendar', 'slack-live': 'slackArrival', 'kiosk-hardware': 'kioskIntake' };
const insist = (condition, message) => { if (!condition) throw new Error(message); };
const sha = value => typeof value === 'string' && /^[a-f0-9]{40}$/.test(value);
function shape(value, keys, label) {
  insist(value && typeof value === 'object' && !Array.isArray(value), `${label} must be an object`);
  const actual = Object.keys(value).sort(), expected = [...keys].sort();
  insist(actual.length === expected.length && actual.every((key, i) => key === expected[i]), `${label} has missing or unsupported fields`);
}
function text(value, label, nullable = false, max = 180) {
  if (nullable && value === null) return;
  insist(typeof value === 'string' && value.trim().length > 0 && value.length <= max && !/[\p{Cc}\p{Cf}]/u.test(value), `${label} must be bounded, nonempty text`);
}
function instant(value, now, label) {
  text(value, label);
  const time = Date.parse(value);
  insist(Number.isFinite(time) && (new Date(time).toISOString() === value || new Date(time).toISOString().replace('.000Z', 'Z') === value), `${label} must be a UTC ISO timestamp`);
  insist(time <= now + 300_000, `${label} is in the future`);
}
function evidence(value, releaseSha, now) {
  shape(value, ['reference', 'releaseSha', 'reviewedBy', 'reviewedAt'], 'Evidence');
  text(value.reference, 'Evidence reference', false, 500); text(value.reviewedBy, 'Evidence reviewer');
  insist(sha(releaseSha) && value.releaseSha === releaseSha, 'Evidence must identify the candidate release SHA');
  instant(value.reviewedAt, now, 'Evidence review time');
  if (value.reference.includes(':')) {
    let url; try { url = new URL(value.reference); } catch { throw new Error('Evidence reference is invalid'); }
    insist(url.protocol === 'https:' && !url.username && !url.password && !url.search && !url.hash, 'Use an HTTPS evidence reference without credentials or temporary URL parameters');
  } else {
    insist(!value.reference.startsWith('/') && !value.reference.includes('\\') && !value.reference.split('/').includes('..'), 'Use a stable evidence packet reference');
  }
}

export function evaluateRelease(record, { now = Date.now(), expectedReleaseSha } = {}) {
  shape(record, ['schemaVersion', 'releaseSha', 'decision', 'approvedBy', 'approvedAt', 'operationsOwner', 'supportOwner', 'supportContact', 'optionalFeatures', 'gates', 'pilotFeedback'], 'Release record');
  insist(record.schemaVersion === 1, 'Unsupported release record version');
  insist(record.releaseSha === null || sha(record.releaseSha), 'Candidate release SHA must be immutable');
  if (expectedReleaseSha !== undefined) insist(sha(expectedReleaseSha) && record.releaseSha === expectedReleaseSha, 'Record does not match the installed candidate release SHA');
  insist(['PENDING', 'APPROVED'].includes(record.decision), 'Unsupported release decision');
  for (const name of ['approvedBy', 'operationsOwner', 'supportOwner', 'supportContact']) text(record[name], name, true);
  if (record.supportContact !== null) insist(/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(record.supportContact), 'Support contact must be an email address');
  if (record.approvedAt !== null) instant(record.approvedAt, now, 'Approval time');
  shape(record.optionalFeatures, ['googleCalendar', 'slackArrival', 'kioskIntake'], 'Optional features');
  for (const flag of Object.values(record.optionalFeatures)) insist(flag === null || typeof flag === 'boolean', 'Optional feature availability must be recorded or unknown');
  insist(Array.isArray(record.gates) && record.gates.length === releaseGates.length, 'Every release gate must be present');
  const seen = new Set(), pending = [];
  for (const gate of record.gates) {
    shape(gate, ['id', 'status', 'evidence', 'reason'], 'Gate');
    insist(releaseGates.includes(gate.id) && !seen.has(gate.id), 'Release gate IDs must be known and unique'); seen.add(gate.id);
    insist(['PENDING', 'PASSED', 'FAILED', 'NOT_APPLICABLE'].includes(gate.status), 'Unsupported gate status');
    text(gate.reason, 'Gate reason', true, 500);
    if (gate.status === 'FAILED') insist(gate.reason !== null, 'A failed gate needs a recorded reason');
    if (gate.status === 'PASSED' || gate.status === 'NOT_APPLICABLE') evidence(gate.evidence, record.releaseSha, now);
    else if (gate.evidence !== null) evidence(gate.evidence, record.releaseSha, now);
    if (gate.status === 'NOT_APPLICABLE') {
      insist(Object.hasOwn(optional, gate.id) && record.optionalFeatures[optional[gate.id]] === false && gate.reason !== null, 'Only a verified disabled optional feature may have an exclusion');
    }
    if (gate.status === 'PENDING' || gate.status === 'FAILED') pending.push(gate.id);
  }
  insist(Array.isArray(record.pilotFeedback) && record.pilotFeedback.length <= 100, 'Pilot feedback must be a bounded register');
  const feedbackIds = new Set();
  for (const item of record.pilotFeedback) {
    shape(item, ['id', 'priority', 'summary', 'status', 'owner', 'followUpBy', 'evidence'], 'Feedback');
    text(item.id, 'Feedback ID', false, 80); text(item.summary, 'Feedback summary', false, 500); text(item.owner, 'Feedback owner');
    insist(!feedbackIds.has(item.id), 'Feedback IDs must be unique'); feedbackIds.add(item.id);
    insist(['CRITICAL', 'HIGH', 'MEDIUM', 'LOW'].includes(item.priority) && ['OPEN', 'FIXED', 'ACCEPTED'].includes(item.status), 'Unsupported feedback disposition');
    if (item.status !== 'OPEN') evidence(item.evidence, record.releaseSha, now);
    else if (item.evidence !== null) evidence(item.evidence, record.releaseSha, now);
    if (item.status === 'ACCEPTED') {
      insist(!['CRITICAL', 'HIGH'].includes(item.priority), 'Critical and high feedback must be fixed and rechecked');
      insist(typeof item.followUpBy === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(item.followUpBy)
        && Number.isFinite(Date.parse(item.followUpBy)) && new Date(item.followUpBy).toISOString().slice(0, 10) === item.followUpBy
        && item.followUpBy >= new Date(now).toISOString().slice(0, 10), 'Accepted feedback needs a current follow-up deadline');
    } else insist(item.followUpBy === null, 'Only accepted feedback has a follow-up deadline');
    if (item.status === 'OPEN') pending.push('pilot-feedback');
  }
  for (const field of ['releaseSha', 'operationsOwner', 'supportOwner', 'supportContact']) if (record[field] === null) pending.push(field);
  if (Object.values(record.optionalFeatures).some(flag => flag === null)) pending.push('optional-feature-verification');
  const approval = record.approvedBy !== null && record.approvedAt !== null;
  if (record.decision === 'APPROVED') {
    insist(pending.length === 0 && approval, 'Release approval is inconsistent with missing acceptance evidence');
    const reviews = [...record.gates, ...record.pilotFeedback].filter(item => item.evidence !== null).map(item => Date.parse(item.evidence.reviewedAt));
    insist(reviews.every(time => time <= Date.parse(record.approvedAt)), 'Release approval cannot precede its evidence reviews');
  } else {
    insist(record.approvedBy === null && record.approvedAt === null, 'A pending decision cannot carry an approval');
    pending.push('release-approval');
  }
  return { accepted: record.decision === 'APPROVED' && pending.length === 0 && approval, pending: [...new Set(pending)] };
}
