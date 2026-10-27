'use strict';
const test = require('node:test');
const assert = require('node:assert');
const http = require('node:http');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const lib = require('../lib');
const { createRelay, createJournal, listen } = require('../relay');

test('bind: loopback + Tailscale only, never a wildcard', () => {
  assert.deepStrictEqual(lib.computeBindHosts('100.65.88.40'), ['127.0.0.1', '100.65.88.40']);
  assert.deepStrictEqual(lib.computeBindHosts(null), ['127.0.0.1']);
  assert.deepStrictEqual(lib.computeBindHosts('0.0.0.0'), ['127.0.0.1']);
  assert.deepStrictEqual(lib.computeBindHosts('192.168.1.5'), ['127.0.0.1']);
  assert.deepStrictEqual(lib.computeBindHosts('100.128.0.1'), ['127.0.0.1']);
  assert.strictEqual(lib.parseTailscaleIpOutput('100.65.88.40\r\n'), '100.65.88.40');
  assert.strictEqual(lib.parseTailscaleIpOutput('erreur'), null);
});

test('load vs plan detection and forced stream/think', () => {
  assert.ok(lib.isLoadRequest({ model: 'qwen3:8b', prompt: '', keep_alive: '15m', stream: false }));
  assert.ok(lib.isLoadRequest({ model: 'qwen3:8b' }));
  assert.ok(!lib.isLoadRequest({ model: 'qwen3:8b', prompt: 'plan' }));
  const up = lib.buildPlanUpstreamBody({ model: 'qwen3:8b', prompt: 'p', stream: false, think: false, format: { type: 'object' }, options: { num_predict: 256, num_ctx: 2048, temperature: 0.2 } });
  assert.strictEqual(up.stream, true);
  assert.strictEqual(up.think, true);
  assert.deepStrictEqual(up.format, { type: 'object' });
  assert.strictEqual(up.options.num_predict, lib.MIN_THINK_PREDICT);
  assert.strictEqual(up.options.num_ctx, lib.MIN_THINK_CTX);
  assert.strictEqual(up.options.temperature, 0.2);
  const withPacing = lib.buildPlanUpstreamBody({ model: 'qwen3:8b', prompt: 'p', ossm_pacing: { reserve_s: 42, last_latency_s: 31, threshold_s: 72 } });
  assert.ok(!('ossm_pacing' in withPacing));
  assert.strictEqual(lib.describePacing({ ossm_pacing: { reserve_s: 42, last_latency_s: 31, threshold_s: 72 } }),
    'Avance IA : 42 s de gestes en réserve, seuil 72 s, dernière latence 31 s');
  assert.strictEqual(lib.describePacing({}), null);
});

const STREAM = [
  { model: 'qwen3:8b', created_at: 't1', response: '', thinking: 'Je ', done: false },
  { model: 'qwen3:8b', created_at: 't2', response: '', thinking: 'réfléchis.', done: false },
  { model: 'qwen3:8b', created_at: 't3', response: '{"schemaVersion":1,', done: false },
  { model: 'qwen3:8b', created_at: 't4', response: '"phrases":[]}', done: false },
  { model: 'qwen3:8b', created_at: 't5', response: '', done: true, done_reason: 'stop', total_duration: 5, eval_count: 9 },
];

test('stream -> single stream:false answer', () => {
  const splitter = new lib.NdjsonSplitter();
  const agg = new lib.StreamAggregator();
  const text = STREAM.map((c) => JSON.stringify(c)).join('\n') + '\n';
  // Split in awkward pieces to exercise buffering.
  for (let i = 0; i < text.length; i += 7) splitter.push(text.slice(i, i + 7)).forEach((c) => agg.push(c));
  splitter.end().forEach((c) => agg.push(c));
  const { status, body } = agg.result();
  assert.strictEqual(status, 200);
  assert.strictEqual(body.model, 'qwen3:8b');
  assert.strictEqual(body.created_at, 't5');
  assert.strictEqual(body.response, '{"schemaVersion":1,"phrases":[]}');
  assert.strictEqual(body.thinking, 'Je réfléchis.');
  assert.strictEqual(body.done, true);
  assert.strictEqual(body.done_reason, 'stop');
  assert.strictEqual(body.eval_count, 9);

  const noThink = new lib.StreamAggregator({ keepThinking: false });
  STREAM.forEach((c) => noThink.push(c));
  assert.ok(!('thinking' in noThink.result().body));

  const err = new lib.StreamAggregator();
  err.push({ error: 'boom' });
  assert.strictEqual(err.result().status, 500);
  const partial = new lib.StreamAggregator();
  partial.push(STREAM[0]);
  assert.strictEqual(partial.result().status, 502);
});

function request(port, method, urlPath, body) {
  return new Promise((resolve, reject) => {
    const data = body ? Buffer.from(JSON.stringify(body)) : null;
    const req = http.request({ host: '127.0.0.1', port, method, path: urlPath, headers: data ? { 'Content-Type': 'application/json', 'Content-Length': data.length } : {} }, (res) => {
      let text = '';
      res.setEncoding('utf8');
      res.on('data', (c) => { text += c; });
      res.on('end', () => resolve({ status: res.statusCode, json: JSON.parse(text) }));
    });
    req.on('error', reject);
    if (data) req.write(data);
    req.end();
  });
}

test('relay end-to-end against a fake Ollama, bound to loopback', async () => {
  const seen = [];
  const fake = http.createServer((req, res) => {
    let raw = '';
    req.on('data', (c) => { raw += c; });
    req.on('end', () => {
      if (req.url === '/api/tags') { res.end(JSON.stringify({ models: [{ name: 'qwen3:8b' }] })); return; }
      const body = JSON.parse(raw);
      seen.push(body);
      if (body.prompt === '') { res.end(JSON.stringify({ model: 'qwen3:8b', created_at: 'x', response: '', done: true, done_reason: 'load' })); return; }
      res.setHeader('Content-Type', 'application/x-ndjson');
      STREAM.forEach((c) => res.write(JSON.stringify(c) + '\n'));
      res.end();
    });
  });
  await listen(fake, '127.0.0.1', 0);
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'relay-'));
  const journal = createJournal(dir);
  const relay = createRelay({ upstream: { host: '127.0.0.1', port: fake.address().port }, journal });
  await listen(relay, '127.0.0.1', 0);
  try {
    assert.strictEqual(relay.address().address, '127.0.0.1');
    const port = relay.address().port;
    const tags = await request(port, 'GET', '/api/tags');
    assert.strictEqual(tags.json.models[0].name, 'qwen3:8b');
    const load = await request(port, 'POST', '/api/generate', { model: 'qwen3:8b', prompt: '', keep_alive: '15m', stream: false });
    assert.strictEqual(load.status, 200);
    assert.strictEqual(seen[0].stream, false); // passed through unchanged
    const plan = await request(port, 'POST', '/api/generate', { model: 'qwen3:8b', prompt: 'p', stream: false, think: true, ossm_pacing: { reserve_s: 42, threshold_s: 70 } });
    assert.strictEqual(plan.status, 200);
    assert.strictEqual(seen[1].stream, true);
    assert.strictEqual(seen[1].think, true);
    assert.ok(!('ossm_pacing' in seen[1]));
    assert.strictEqual(plan.json.response, '{"schemaVersion":1,"phrases":[]}');
    assert.strictEqual(plan.json.done, true);
    const other = await request(port, 'POST', '/api/pull', { name: 'x' });
    assert.strictEqual(other.status, 404);
    const log = fs.readFileSync(journal.file, 'utf8');
    assert.match(log, /Chargement de qwen3:8b demandé par l'app/);
    assert.match(log, /Modèle chargé/);
    assert.match(log, /Avance IA : 42 s de gestes en réserve/);
    assert.match(log, /Plan terminé : latence/);
    assert.match(log, /--- RAISONNEMENT ---[\s\S]*Je réfléchis\./);
    assert.match(log, /--- PLAN ---[\s\S]*schemaVersion/);
    assert.doesNotMatch(log, /127\.0\.0\.1/);
  } finally {
    relay.close();
    fake.close();
  }
});
