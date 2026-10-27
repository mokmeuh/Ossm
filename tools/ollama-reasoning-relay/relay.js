#!/usr/bin/env node
'use strict';
// OSSM reasoning relay: sits between the OSSM app and the local Ollama.
// Listens ONLY on 127.0.0.1 and the PC's Tailscale IPv4 (port 11435), never 0.0.0.0.
// Load requests pass through; plan requests are streamed with thinking so the reasoning
// is written live to logs/raisonnement.log, then ONE stream:false-style answer goes back.
// It never opens a window: Voir-Raisonnement-Qwen3.cmd tails the log whenever wanted.

const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');
const { execFile } = require('node:child_process');
const lib = require('./lib');

const DEFAULT_PORT = 11435;
const UPSTREAM = { host: '127.0.0.1', port: 11434 };
const TIMEOUT_MS = 300_000;
const MAX_BODY_BYTES = 1_048_576;
const PASSTHROUGH_GET = new Set(['/api/tags', '/api/ps', '/api/version']);

function createJournal(logDir) {
  fs.mkdirSync(logDir, { recursive: true });
  const file = path.join(logDir, 'raisonnement.log');
  try {
    if (fs.statSync(file).size > 5 * 1024 * 1024) fs.renameSync(file, file + '.old');
  } catch (_) { /* absent */ }
  const write = (text) => { try { fs.appendFileSync(file, text, 'utf8'); } catch (_) { /* ignore */ } };
  return {
    file,
    raw: write,
    line: (text) => write(`[${lib.stamp()}] ${text}\n`),
  };
}

function readBody(req) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let size = 0;
    req.on('data', (c) => {
      size += c.length;
      if (size > MAX_BODY_BYTES) { reject(new Error('too large')); req.destroy(); return; }
      chunks.push(c);
    });
    req.on('end', () => resolve(Buffer.concat(chunks)));
    req.on('error', reject);
  });
}

function sendJson(res, status, obj) {
  if (res.headersSent || res.writableEnded || res.destroyed) return;
  const data = Buffer.from(JSON.stringify(obj), 'utf8');
  res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Content-Length': data.length });
  res.end(data);
}

function sendRaw(res, status, contentType, data) {
  if (res.headersSent || res.writableEnded || res.destroyed) return;
  res.writeHead(status, { 'Content-Type': contentType || 'application/json', 'Content-Length': data.length });
  res.end(data);
}

/** Starts an upstream request; aborts it on timeout (300 s) or when the app disconnects. */
function upstreamRequest(upstream, res, method, urlPath, body, onResponse, onError, timeoutMs) {
  const headers = body ? { 'Content-Type': 'application/json', 'Content-Length': body.length } : {};
  const up = http.request({ host: upstream.host, port: upstream.port, method, path: urlPath, headers }, onResponse);
  up.setTimeout(timeoutMs || TIMEOUT_MS, () => up.destroy(new Error('timeout')));
  up.on('error', onError);
  res.on('close', () => { if (!res.writableEnded) up.destroy(new Error('client closed')); });
  if (body) up.write(body);
  up.end();
  return up;
}

function failureText(error) {
  const msg = error && error.message;
  if (msg === 'timeout') return 'délai dépassé (300 s)';
  if (msg === 'client closed') return "annulé par l'app";
  return 'Ollama injoignable';
}

function createRelay({ upstream = UPSTREAM, journal, timeoutMs = TIMEOUT_MS }) {
  return http.createServer(async (req, res) => {
    const urlPath = (req.url || '').split('?')[0];
    try {
      if (req.method === 'GET' && PASSTHROUGH_GET.has(urlPath)) {
        upstreamRequest(upstream, res, 'GET', urlPath, null, (up) => {
          res.writeHead(up.statusCode || 502, { 'Content-Type': up.headers['content-type'] || 'application/json' });
          up.pipe(res);
        }, () => sendJson(res, 502, { error: 'Ollama injoignable' }), timeoutMs);
        return;
      }
      if (req.method !== 'POST' || urlPath !== '/api/generate') {
        sendJson(res, 404, { error: 'not found' });
        return;
      }
      let raw;
      try { raw = await readBody(req); } catch (_) { sendJson(res, 413, { error: 'requete trop grande' }); return; }
      let body;
      try { body = JSON.parse(raw.toString('utf8')); } catch (_) { sendJson(res, 400, { error: 'JSON invalide' }); return; }
      if (!body || typeof body !== 'object' || Array.isArray(body)) { sendJson(res, 400, { error: 'JSON invalide' }); return; }
      if (lib.isLoadRequest(body)) handleLoad(upstream, journal, res, raw, body, timeoutMs);
      else handlePlan(upstream, journal, res, body, timeoutMs);
    } catch (_) {
      sendJson(res, 500, { error: 'erreur du relais' });
    }
  });
}

function handleLoad(upstream, journal, res, raw, body, timeoutMs) {
  const model = typeof body.model === 'string' ? body.model : '?';
  const started = Date.now();
  journal.line(`Chargement de ${model} demandé par l'app…`);
  upstreamRequest(upstream, res, 'POST', '/api/generate', raw, (up) => {
    const chunks = [];
    up.on('data', (c) => chunks.push(c));
    up.on('end', () => {
      const seconds = Math.round((Date.now() - started) / 1000);
      const status = up.statusCode || 502;
      if (status >= 200 && status < 300) journal.line(`Modèle chargé (${seconds} s)`);
      else journal.line(`Échec du chargement : HTTP ${status} (${seconds} s)`);
      sendRaw(res, status, up.headers['content-type'], Buffer.concat(chunks));
    });
    up.on('error', () => {});
  }, (error) => {
    journal.line(`Échec du chargement : ${failureText(error)}`);
    sendJson(res, error && error.message === 'timeout' ? 504 : 502, { error: failureText(error) });
  }, timeoutMs);
}

function handlePlan(upstream, journal, res, body, timeoutMs) {
  const started = Date.now();
  const keepThinking = body.think === true;
  const upstreamBody = Buffer.from(JSON.stringify(lib.buildPlanUpstreamBody(body)), 'utf8');
  journal.raw('\n');
  journal.line(`=== Demande de plan (${typeof body.model === 'string' ? body.model : '?'}) ===`);
  const pacing = lib.describePacing(body);
  if (pacing) journal.line(pacing);
  upstreamRequest(upstream, res, 'POST', '/api/generate', upstreamBody, (up) => {
    const status = up.statusCode || 502;
    if (status < 200 || status >= 300) {
      const chunks = [];
      up.on('data', (c) => chunks.push(c));
      up.on('end', () => {
        journal.line(`Erreur Ollama HTTP ${status}`);
        sendRaw(res, status, up.headers['content-type'], Buffer.concat(chunks));
      });
      up.on('error', () => {});
      return;
    }
    const splitter = new lib.NdjsonSplitter();
    const agg = new lib.StreamAggregator({ keepThinking });
    let section = null;
    const consume = (objs) => {
      for (const chunk of objs) {
        agg.push(chunk);
        if (typeof chunk.thinking === 'string' && chunk.thinking.length > 0) {
          if (section !== 'thinking') { journal.raw('\n'); journal.line('--- RAISONNEMENT ---'); section = 'thinking'; }
          journal.raw(chunk.thinking);
        }
        if (typeof chunk.response === 'string' && chunk.response.length > 0) {
          if (section !== 'plan') { journal.raw('\n'); journal.line('--- PLAN ---'); section = 'plan'; }
          journal.raw(chunk.response);
        }
      }
    };
    let broken = false;
    up.setEncoding('utf8');
    up.on('data', (text) => {
      if (broken) return;
      try { consume(splitter.push(text)); } catch (_) { broken = true; }
    });
    up.on('end', () => {
      if (!broken) { try { consume(splitter.end()); } catch (_) { broken = true; } }
      const result = broken ? { status: 502, body: { error: 'flux Ollama illisible' } } : agg.result();
      const seconds = Math.round((Date.now() - started) / 1000);
      journal.raw('\n');
      if (result.status === 200) journal.line(`=== Plan terminé : latence ${seconds} s (${result.body.done_reason || 'stop'}) ===`);
      else journal.line(`=== Plan en échec (${seconds} s) : ${result.body.error} ===`);
      sendJson(res, result.status, result.body);
    });
    up.on('error', () => {});
  }, (error) => {
    journal.raw('\n');
    journal.line(`=== Plan interrompu : ${failureText(error)} ===`);
    sendJson(res, error && error.message === 'timeout' ? 504 : 502, { error: failureText(error) });
  }, timeoutMs);
}

function discoverTailscaleIp() {
  const candidates = ['tailscale', 'C:\\Program Files\\Tailscale\\tailscale.exe'];
  return new Promise((resolve) => {
    const tryNext = (i) => {
      if (i >= candidates.length) { resolve(null); return; }
      execFile(candidates[i], ['ip', '-4'], { timeout: 10_000, windowsHide: true }, (err, stdout) => {
        const ip = err ? null : lib.parseTailscaleIpOutput(stdout);
        if (ip) resolve(ip); else tryNext(i + 1);
      });
    };
    tryNext(0);
  });
}

function listen(server, host, port) {
  return new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(port, host, () => { server.removeListener('error', reject); resolve(server); });
  });
}

async function main() {
  const port = Number(process.env.OSSM_RELAY_PORT) || DEFAULT_PORT;
  const journal = createJournal(path.join(__dirname, 'logs'));
  const tsIp = await discoverTailscaleIp();
  const hosts = lib.computeBindHosts(tsIp);
  const servers = [];
  for (const host of hosts) {
    const server = createRelay({ journal });
    try {
      await listen(server, host, port);
      servers.push(server);
    } catch (error) {
      console.error(`Impossible d'écouter sur ${host}:${port} (${error.code || error.message})`);
    }
  }
  if (servers.length === 0) { process.exitCode = 1; return; }
  journal.line(`Relais démarré (port ${port})`);
  console.log(`Relais de raisonnement OSSM actif sur le port ${port} (127.0.0.1${tsIp ? ' + Tailscale' : ''}).`);
  if (tsIp) console.log(`Adresse à saisir dans l'app : ${tsIp}:${port}`);
  else console.log('Tailscale introuvable : écoute en local (127.0.0.1) seulement.');
  console.log(`Journal : ${journal.file}`);
  console.log('Fenêtre de raisonnement : Voir-Raisonnement-Qwen3.cmd (quand tu veux).');
  console.log('Ctrl+C pour arrêter.');
  let stopping = false;
  const stop = () => {
    if (stopping) return;
    stopping = true;
    journal.line('Relais arrêté');
    for (const s of servers) {
      s.close();
      if (typeof s.closeAllConnections === 'function') s.closeAllConnections();
    }
    setTimeout(() => process.exit(0), 300).unref();
  };
  process.on('SIGINT', stop);
  process.on('SIGTERM', stop);
  process.on('SIGBREAK', stop);
}

if (require.main === module) main();

module.exports = { createRelay, createJournal, listen, DEFAULT_PORT };
