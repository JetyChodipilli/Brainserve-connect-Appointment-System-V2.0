import { test } from 'node:test';
import assert from 'node:assert/strict';
import { METRICS_MAX_BYTES, readMonitoringText } from '../verify-monitoring-staging.mjs';

test('route histograms larger than one MiB fit the bounded metrics budget', async () => {
  const text = '# http_server_requests_seconds_bucket\n' + 'x'.repeat(1024 * 1024);
  assert.equal(await readMonitoringText(new Response(text), METRICS_MAX_BYTES), text);
});
test('oversized responses cancel the stream and release its reader', async () => {
  let cancelled = false;
  const response = new Response(new ReadableStream({
    start(controller) { controller.enqueue(new Uint8Array(1025)); },
    cancel() { cancelled = true; },
  }));
  await assert.rejects(() => readMonitoringText(response, 1024), /exceeded 1024 byte limit/);
  assert.equal(cancelled, true); assert.equal(response.body.locked, false);
});
test('monitoring limits count UTF-8 bytes rather than JavaScript characters', async () => {
  await assert.rejects(() => readMonitoringText(new Response('€'), 2), /byte limit/);
});
test('truncated monitoring responses fail instead of accepting partial metrics', async () => {
  const response = new Response(new ReadableStream({ start(controller) { controller.error(new Error('Truncated stream')); } }));
  await assert.rejects(() => readMonitoringText(response, METRICS_MAX_BYTES), /Truncated stream/);
  assert.equal(response.body.locked, false);
});
