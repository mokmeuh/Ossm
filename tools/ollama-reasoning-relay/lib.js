'use strict';
// Pure helpers of the OSSM reasoning relay (no I/O): bind hosts, request shaping and
// NDJSON stream -> single non-streamed Ollama answer.

/** True for an IPv4 in the Tailscale CGNAT range 100.64.0.0/10. */
function isTailscaleIpv4(ip) {
  if (typeof ip !== 'string') return false;
  const parts = ip.trim().split('.');
  if (parts.length !== 4) return false;
  const n = parts.map((p) => (/^\d{1,3}$/.test(p) ? Number(p) : -1));
  if (n.some((v) => v < 0 || v > 255)) return false;
  return n[0] === 100 && n[1] >= 64 && n[1] <= 127;
}

/** Loopback always; Tailscale IPv4 only if valid. Never a wildcard address. */
function computeBindHosts(tailscaleIp) {
  const hosts = ['127.0.0.1'];
  const ip = typeof tailscaleIp === 'string' ? tailscaleIp.trim() : '';
  if (isTailscaleIpv4(ip)) hosts.push(ip);
  return hosts.filter((h) => h !== '0.0.0.0' && h !== '::' && h !== '');
}

/** First Tailscale IPv4 found in `tailscale ip -4` output, or null. */
function parseTailscaleIpOutput(text) {
  for (const line of String(text || '').split(/\r?\n/)) {
    if (isTailscaleIpv4(line.trim())) return line.trim();
  }
  return null;
}

/** Ollama treats an empty prompt as "load the model only". */
function isLoadRequest(body) {
  return !body || typeof body.prompt !== 'string' || body.prompt.length === 0;
}

const MIN_THINK_PREDICT = 2048;
const MIN_THINK_CTX = 4096;

/** Plan request sent upstream: streamed, with thinking, room for the reasoning tokens. */
function buildPlanUpstreamBody(body) {
  const options = Object.assign({}, body.options || {});
  if (typeof options.num_predict !== 'number' || (options.num_predict >= 0 && options.num_predict < MIN_THINK_PREDICT)) {
    options.num_predict = MIN_THINK_PREDICT;
  }
  if (typeof options.num_ctx !== 'number' || options.num_ctx < MIN_THINK_CTX) options.num_ctx = MIN_THINK_CTX;
  const out = Object.assign({}, body, { stream: true, think: true, options });
  delete out.ossm_pacing; // app-side pacing info: journal only, never sent to Ollama
  return out;
}

/** Journal line for the app's optional pacing info ({reserve_s, last_latency_s, threshold_s}). */
function describePacing(body) {
  const p = body && body.ossm_pacing;
  if (!p || typeof p !== 'object') return null;
  const num = (v) => (typeof v === 'number' && Number.isFinite(v) ? Math.round(v) : null);
  const reserve = num(p.reserve_s);
  if (reserve === null) return null;
  const latency = num(p.last_latency_s);
  const threshold = num(p.threshold_s);
  return `Avance IA : ${reserve} s de gestes en réserve` +
    (threshold !== null ? `, seuil ${threshold} s` : '') +
    (latency !== null ? `, dernière latence ${latency} s` : '');
}

/** Splits an NDJSON byte stream into parsed objects. */
class NdjsonSplitter {
  constructor() { this.buffer = ''; }
  push(chunk) {
    this.buffer += chunk;
    const out = [];
    let idx;
    while ((idx = this.buffer.indexOf('\n')) >= 0) {
      const line = this.buffer.slice(0, idx).trim();
      this.buffer = this.buffer.slice(idx + 1);
      if (line) out.push(JSON.parse(line));
    }
    return out;
  }
  end() {
    const line = this.buffer.trim();
    this.buffer = '';
    return line ? [JSON.parse(line)] : [];
  }
}

/**
 * Accumulates Ollama /api/generate stream chunks and rebuilds the stream:false answer:
 * final chunk metadata (model, created_at, done, done_reason, durations, counts, context)
 * with the concatenated `response` (and `thinking` when kept).
 */
class StreamAggregator {
  constructor({ keepThinking = true } = {}) {
    this.keepThinking = keepThinking;
    this.response = '';
    this.thinking = '';
    this.final = null;
    this.last = null;
    this.error = null;
  }
  push(chunk) {
    if (!chunk || typeof chunk !== 'object') return;
    if (typeof chunk.error === 'string') { this.error = chunk.error; return; }
    this.last = chunk;
    if (typeof chunk.thinking === 'string') this.thinking += chunk.thinking;
    if (typeof chunk.response === 'string') this.response += chunk.response;
    if (chunk.done === true) this.final = chunk;
  }
  /** {status, body} to send back to the app. */
  result() {
    if (this.error !== null) return { status: 500, body: { error: this.error } };
    if (!this.final) return { status: 502, body: { error: 'flux Ollama incomplet' } };
    const body = {};
    for (const [k, v] of Object.entries(this.final)) {
      if (k === 'response' || k === 'thinking') continue;
      body[k] = v;
    }
    const ordered = { model: body.model, created_at: body.created_at, response: this.response };
    if (this.keepThinking && this.thinking.length > 0) ordered.thinking = this.thinking;
    return { status: 200, body: Object.assign(ordered, body, { done: true }) };
  }
}

/** HH:MM:SS local timestamp. */
function stamp(date = new Date()) {
  const p = (v) => String(v).padStart(2, '0');
  return `${p(date.getHours())}:${p(date.getMinutes())}:${p(date.getSeconds())}`;
}

module.exports = {
  isTailscaleIpv4,
  computeBindHosts,
  parseTailscaleIpOutput,
  isLoadRequest,
  buildPlanUpstreamBody,
  describePacing,
  NdjsonSplitter,
  StreamAggregator,
  stamp,
  MIN_THINK_PREDICT,
  MIN_THINK_CTX,
};
