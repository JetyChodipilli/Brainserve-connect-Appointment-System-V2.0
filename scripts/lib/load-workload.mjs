import { performance } from 'node:perf_hooks';

export function percentile(values, rank) {
  if (!values.length) return null;
  const sorted = [...values].sort((a, b) => a - b);
  return Math.round(sorted[Math.max(0, Math.ceil(rank * sorted.length) - 1)] * 100) / 100;
}

// Closed-loop, paced requests. Only fixed operation labels and aggregate numbers are retained.
export async function runWorkload({ actors, call, durationSeconds = 30, thinkMs = 1000, maxSamples = 250000 }) {
  if (!actors?.length || actors.length > 50 || new Set(actors.map(a => a.user)).size !== actors.length) throw new Error('Use 1–50 distinct sessions');
  if (!Number.isInteger(durationSeconds) || durationSeconds < 1 || durationSeconds > 3600 || !Number.isInteger(thinkMs) || thinkMs < 100 || thinkMs > 10000) throw new Error('Duration or pacing outside bounds');
  if (!Number.isInteger(maxSamples) || maxSamples < 1 || maxSamples > 250000) throw new Error('Sample bound invalid');
  const groups = new Map(), begin = performance.now(), deadline = begin + durationSeconds * 1000;
  let samples = 0, capped = false;
  await Promise.all(actors.map(async actor => {
    for (let i = 0; performance.now() < deadline && !capped; i++) {
      if (samples >= maxSamples) { capped = true; break; }
      samples++;
      const mutation = actor.role === 'ROLE_TEAM_LEAD' && i % 8 === 7;
      const key = mutation ? 'work-create' : ['dashboard-read', 'workboard-read', 'preferences-read'][i % 3];
      const path = mutation ? '/work-tasks' : ['/dashboard/summary', '/workboard?scope=ALL&page=0&size=20', '/notification-preferences'][i % 3];
      const group = groups.get(key) ?? { kind: mutation ? 'mutation' : 'read', latencies: [], errors: 0, statuses: {} };
      groups.set(key, group);
      const started = performance.now(); let status = 0, invalidResponse = false;
      try {
        const body = mutation ? { employeeId: actor.targetEmployee, title: 'Synthetic load worksheet', description: 'Disposable Sprint 15 load fixture', dueDate: new Date(Date.now() + 2 * 86400000).toISOString().slice(0, 10) } : undefined;
        status = (await call(actor, path, mutation ? 'POST' : 'GET', body)).status;
      } catch (error) {
        invalidResponse = true;
        if (Number.isInteger(error?.status) && error.status >= 100 && error.status <= 599) status = error.status;
        // Retain only a validated numeric status, never exception text or business data.
      }
      group.latencies.push(performance.now() - started); group.statuses[status] = (group.statuses[status] ?? 0) + 1;
      if (status !== 200 || invalidResponse) group.errors++;
      const remaining = deadline - performance.now();
      if (remaining > 0) await new Promise(resolve => setTimeout(resolve, Math.min(thinkMs, remaining)));
    }
  }));
  const endpoints = Object.fromEntries([...groups].map(([key, g]) => [key, { kind: g.kind, requests: g.latencies.length, errors: g.errors, statuses: g.statuses,
    p50Ms: percentile(g.latencies, .5), p95Ms: percentile(g.latencies, .95), p99Ms: percentile(g.latencies, .99), maxMs: Math.round(g.latencies.reduce((max, n) => Math.max(max, n), 0) * 100) / 100 }]));
  const errors = Object.values(endpoints).reduce((sum, g) => sum + g.errors, 0);
  const sufficientSamples = Object.values(endpoints).every(g => g.requests >= 100);
  const measuredTargetsPass = !capped && sufficientSamples && !!endpoints['work-create'] && errors / samples < .01 && Object.values(endpoints).every(g => g.p95Ms <= (g.kind === 'read' ? 500 : 1000) && g.p99Ms <= 2000);
  return { schemaVersion: 1, profile: 'synthetic-closed-loop', durationSeconds, elapsedSeconds: Math.round((performance.now() - begin) / 10) / 100,
    concurrentSessions: actors.length, thinkMs, requests: samples, errors, errorRate: samples ? errors / samples : null,
    complete: !capped, sufficientSamples, measuredTargetsPass, customerCapacityAccepted: false, endpoints };
}
