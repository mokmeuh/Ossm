import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import vm from "node:vm";
import { describe, expect, it, vi } from "vitest";

const appSource = readFileSync(resolve(import.meta.dirname, "../static/app.js"), "utf8");
const SECRET = "SECRETSECRETSECRETSECRETSECRET";
const SESSION_ENDED = "Session expirée ou introuvable — demandez un nouveau lien au téléphone";

class HTMLElementStub {}

class El extends HTMLElementStub {
  constructor(id = "", tagName = "div") {
    super();
    this.tagName = tagName.toUpperCase();
    this.id = id.replace(/^#/, "");
    this.textContent = "";
    this.hidden = false;
    this.disabled = false;
    this.value = "0";
    this.dataset = {};
    this.style = { props: {}, setProperty(name, value) { this.props[name] = String(value); } };
    this.attrs = {};
    this.listeners = {};
    this.children = [];
    this.classes = new Set();
    this.className = "";
    this.options = [];
    this.selectedIndex = 0;
    this.controlChildren = [];
    this.parentNode = {};
    this.classList = {
      toggle: (name, force) => { const on = force ?? !this.classes.has(name); if (on) this.classes.add(name); else this.classes.delete(name); return on; },
      contains: (name) => this.classes.has(name),
      add: (name) => this.classes.add(name),
      remove: (name) => this.classes.delete(name),
    };
  }
  addEventListener(type, listener) { (this.listeners[type] ??= []).push(listener); }
  fire(type, event = {}) { for (const listener of this.listeners[type] ?? []) listener({ preventDefault() {}, target: this, ...event }); }
  setAttribute(name, value) { this.attrs[name] = String(value); }
  getAttribute(name) { return this.attrs[name]; }
  removeAttribute(name) { delete this.attrs[name]; }
  querySelectorAll() { return this.controlChildren; }
  before() {}
  after() {}
  append(child) { child.owner = this; this.children.push(child); }
  remove() { if (this.owner) this.owner.children.splice(this.owner.children.indexOf(this), 1); }
  get firstElementChild() { return this.children[0]; }
  focus() {}
  play() { this.paused = false; return Promise.resolve(); }
  pause() { this.paused = true; }
  getBoundingClientRect() { return { width: 300, height: 200, left: 0, top: 0, right: 300, bottom: 200 }; }
  getClientRects() { return [1]; }
  setPointerCapture() {}
  hasPointerCapture() { return false; }
  releasePointerCapture() {}
}

class FakeWebSocket {
  static OPEN = 1;
  static instances = [];
  constructor(url) {
    this.url = url;
    this.readyState = 0;
    this.sent = [];
    this.listeners = {};
    FakeWebSocket.instances.push(this);
  }
  addEventListener(type, listener) { (this.listeners[type] ??= []).push(listener); }
  emit(type, event = {}) { for (const listener of this.listeners[type] ?? []) listener(event); }
  send(data) { this.sent.push(JSON.parse(data)); }
  open() { this.readyState = 1; this.emit("open"); }
  receive(event) { this.emit("message", { data: JSON.stringify(event) }); }
  drop() { this.readyState = 3; this.emit("close"); }
  commands() { return this.sent.filter(message => message.type === "command").map(message => JSON.parse(message.command)); }
}

const MODE_KEYS = ["simpleStroke", "teasingPounding", "roboStroke", "halfNHalf", "deeper", "stopNGo", "insist", "streamingLive", "progressive", "autoRandom", "natural"];

class FakePeerConnection {
  static instances = [];
  constructor() {
    this.closed = false;
    this.signalingState = "stable";
    this.localDescription = null;
    this.remoteDescription = null;
    this.senders = [];
    this.candidates = [];
    FakePeerConnection.instances.push(this);
  }
  async setRemoteDescription(description) { this.remoteDescription = description; this.signalingState = description.type === "offer" ? "have-remote-offer" : "stable"; }
  async createAnswer() { return { type: "answer", sdp: "fake-answer" }; }
  async createOffer() { return { type: "offer", sdp: "fake-offer" }; }
  async setLocalDescription(description) { this.localDescription = description; this.signalingState = description.type === "offer" ? "have-local-offer" : "stable"; }
  async addIceCandidate(candidate) { this.candidates.push(candidate); }
  getSenders() { return this.senders; }
  addTrack(track) { const sender = { track, replaceTrack: async (next) => { sender.track = next; } }; this.senders.push(sender); }
  close() { this.closed = true; }
}

function boot({ role = "controller", fetchImpl = async () => ({ status: 200, ok: true }), getUserMedia, mediaDevices, clipboard, rtc } = {}) {
  FakeWebSocket.instances = [];
  const els = new Map();
  const el = (selector) => {
    if (!els.has(selector)) els.set(selector, new El(selector));
    return els.get(selector);
  };
  const pattern = el("#pattern");
  const radios = MODE_KEYS.map((key, index) => Object.assign(new El(`radio-${key}`, "input"), { name: "pattern", value: key, checked: index === 0, dataset: { label: key } }));
  pattern.controlChildren = radios;
  el("#live").value = "0";
  el("#speed").value = "0";
  el("#sensation").value = "50";
  el("#minimum").value = "0";
  el("#maximum").value = "100";
  for (const name of ["speed", "sensation", "minimum", "maximum"]) { el(`#${name}`).min = "0"; el(`#${name}`).max = "100"; el(`#${name}-limit`).hidden = true; }
  el("#remote-controls").controlChildren = [pattern, ...radios, el("#speed"), el("#sensation"), el("#minimum"), el("#maximum"), el("#live"), el("#emergency-stop")];
  const timers = new Map();
  let timerId = 0;
  const windowListeners = {};
  const documentListeners = {};
  const document = {
    querySelector: el,
    querySelectorAll: () => [],
    createElement: (tag) => new El("item", tag),
    createComment: () => new El("comment"),
    addEventListener: (type, listener) => { (documentListeners[type] ??= []).push(listener); },
    body: new El("body"),
    hidden: false,
    activeElement: null,
  };
  const win = {
    isSecureContext: true,
    addEventListener: (type, listener) => { (windowListeners[type] ??= []).push(listener); },
  };
  const fetchMock = vi.fn(fetchImpl);
  const search = role === "camera" ? "?role=camera" : "";
  const context = vm.createContext({
    document,
    window: win,
    location: { pathname: "/s/abcdefgh12345678", hash: `#k=${SECRET}`, search, href: `https://remote.example/s/abcdefgh12345678${search}#k=${SECRET}`, protocol: "https:", host: "remote.example", hostname: "remote.example" },
    navigator: { userAgent: "Chrome/1", mediaDevices: mediaDevices ?? (getUserMedia ? { getUserMedia } : undefined), clipboard },
    WebSocket: FakeWebSocket,
    RTCPeerConnection: rtc ?? (getUserMedia || mediaDevices ? class {} : undefined),
    HTMLElement: HTMLElementStub,
    fetch: fetchMock,
    URL, URLSearchParams, Intl, Date, Promise, JSON, Number, Math, WeakMap, Object, Array, Error, Boolean, String, encodeURIComponent,
    Blob: class {},
    setTimeout: (fn, ms) => { timerId += 1; timers.set(timerId, { fn, ms }); return timerId; },
    clearTimeout: (id) => { timers.delete(id); },
    setInterval: () => { timerId += 1; return timerId; },
    clearInterval: () => {},
  });
  vm.runInContext(appSource, context);
  return {
    el,
    radios,
    fetchMock,
    timers,
    windowListeners,
    document,
    documentListeners,
    ws: () => FakeWebSocket.instances[FakeWebSocket.instances.length - 1],
    pendingDelays: () => [...timers.values()].map(timer => timer.ms),
    runTimer: () => { const [id, timer] = [...timers.entries()][0]; timers.delete(id); timer.fn(); },
  };
}

function selectMode(app, key) {
  for (const radio of app.radios) radio.checked = radio.value === key;
  app.el("#pattern").fire("change", { target: app.radios.find(radio => radio.value === key) });
}

// A user drag: browsers fire "input" while moving, then "change" on release.
function drag(app, selector, value) {
  const input = app.el(selector);
  input.value = String(value);
  input.fire("input");
  input.fire("change");
}

const nb = (text) => text.replaceAll(" ", " ");
const limitsEvent = (speedMax, depthMin, depthMax, sensationMax) => ({ type: "limits", speedMax, depthMin, depthMax, ...(sensationMax === undefined ? {} : { sensationMax }) });
const flush = () => new Promise(resolve => setTimeout(resolve, 0));
const presence = (hostOnline) => ({ type: "presence", hostOnline, controllerOnline: true, cameras: 0, cameraIds: [], controllerId: "c1", ...(hostOnline ? { hostId: "h1" } : {}) });
const ready = (role = "controller") => ({ type: "ready", participantId: "c1", role, expiresAt: Date.now() + 60_000 });

function admitController(app, host = true) {
  const socket = app.ws();
  socket.open();
  socket.receive(presence(host));
  socket.receive(ready());
  return socket;
}

describe("static client behavior", () => {
  it("cancels a Live gesture when control access is lost and never replays a position or release return", () => {
    const app = boot();
    const socket = admitController(app);
    const pad = app.el("#live-pad");
    selectMode(app, "streamingLive");
    pad.fire("pointerdown", { pointerId: 1, pointerType: "touch", clientX: 150, clientY: 100, button: 0 });
    pad.fire("pointermove", { pointerId: 1, clientX: 190, clientY: 40 });
    const positionsBefore = socket.commands().filter(command => command.type === "set_live_position").length;
    expect(positionsBefore).toBe(1);

    socket.receive(presence(false));
    expect(socket.commands().at(-1)).toEqual({ type: "stop" });
    expect(socket.commands().some(command => command.type === "end_live_touch")).toBe(false);
    pad.fire("pointermove", { pointerId: 1, clientX: 250, clientY: 40 });
    expect(socket.commands().filter(command => command.type === "set_live_position")).toHaveLength(positionsBefore);

    // Access returns while the finger is still down: no resume without a new pointerdown.
    socket.receive(presence(true));
    const countAfterRestore = socket.commands().length;
    pad.fire("pointermove", { pointerId: 1, clientX: 280, clientY: 40 });
    expect(socket.commands()).toHaveLength(countAfterRestore);
  });

  it("uses end_live_touch only for a successful pointer release", () => {
    const app = boot();
    const socket = admitController(app);
    const pad = app.el("#live-pad");
    selectMode(app, "streamingLive");
    pad.fire("pointerdown", { pointerId: 1, pointerType: "touch", clientX: 150, clientY: 100, button: 0 });
    pad.fire("pointermove", { pointerId: 1, clientX: 220, clientY: 40 });
    pad.fire("pointerup", { pointerId: 1, clientX: 220, clientY: 100 });

    expect(socket.commands().at(-1)).toEqual({ type: "end_live_touch" });
    expect(socket.commands().filter(command => command.type === "stop")).toHaveLength(0);
  });

  it("drops a pending Direct frame on pointer cancellation and stops without release return", () => {
    const app = boot();
    const socket = admitController(app);
    const pad = app.el("#live-pad");
    selectMode(app, "streamingLive");
    pad.fire("pointerdown", { pointerId: 1, pointerType: "touch", clientX: 150, clientY: 100, button: 0 });
    pad.fire("pointermove", { pointerId: 1, clientX: 180, clientY: 40 });
    pad.fire("pointermove", { pointerId: 1, clientX: 220, clientY: 40 });
    pad.fire("pointercancel", { pointerId: 1 });

    const commands = socket.commands();
    expect(commands.filter(command => command.type === "set_live_position")).toHaveLength(1);
    expect(commands.at(-1)).toEqual({ type: "stop" });
    expect(commands.filter(command => command.type === "end_live_touch")).toHaveLength(0);
    expect(app.pendingDelays().filter(ms => ms === 40)).toHaveLength(0);
  });

  it("sends emergency STOP alone when a Live gesture is active", () => {
    const app = boot();
    const socket = admitController(app);
    const pad = app.el("#live-pad");
    selectMode(app, "streamingLive");
    pad.fire("pointerdown", { pointerId: 1, pointerType: "touch", clientX: 150, clientY: 100, button: 0 });
    pad.fire("pointermove", { pointerId: 1, clientX: 220, clientY: 40 });
    app.el("#emergency-stop").fire("click");

    expect(socket.sent.at(-1).type).toBe("emergency_stop");
    expect(socket.commands().filter(command => command.type === "end_live_touch" || command.type === "stop")).toHaveLength(0);
  });

  it("invalidates pending scalar frames on STOP without blocking a fresh drag", () => {
    const app = boot();
    const socket = admitController(app);
    const speed = app.el("#speed");
    speed.value = "30";
    speed.fire("input");
    speed.value = "45";
    speed.fire("input");

    app.el("#emergency-stop").fire("click");
    for (const timer of [...app.timers.values()].filter(timer => timer.ms === 80)) timer.fn();
    speed.fire("pointerup");
    speed.fire("change");

    expect(socket.sent.filter(message => message.type === "emergency_stop")).toHaveLength(1);
    expect(socket.commands()).toEqual([{ type: "set_speed", value: 0.3 }]);

    speed.value = "60";
    speed.fire("input");
    expect(socket.commands()).toEqual([{ type: "set_speed", value: 0.3 }, { type: "set_speed", value: 0.6 }]);
  });

  it("cancels Live before leaving Direct and does not let a stale pointer resume", () => {
    const app = boot();
    const socket = admitController(app);
    const pad = app.el("#live-pad");
    selectMode(app, "streamingLive");
    pad.fire("pointerdown", { pointerId: 1, pointerType: "touch", clientX: 150, clientY: 100, button: 0 });
    pad.fire("pointermove", { pointerId: 1, clientX: 220, clientY: 40 });
    selectMode(app, "teasingPounding");
    const commands = socket.commands();

    expect(commands.at(-2)).toEqual({ type: "stop" });
    expect(commands.at(-1)).toEqual({ type: "select_pattern", key: "teasingPounding" });
    expect(commands.filter(command => command.type === "end_live_touch")).toHaveLength(0);
    pad.fire("pointermove", { pointerId: 1, clientX: 250, clientY: 40 });
    expect(socket.commands()).toEqual(commands);
  });

  it("cancels Live on window blur and document hiding without initiating return", () => {
    const app = boot();
    const socket = admitController(app);
    const pad = app.el("#live-pad");
    selectMode(app, "streamingLive");
    pad.fire("pointerdown", { pointerId: 1, pointerType: "touch", clientX: 150, clientY: 100, button: 0 });
    app.windowListeners.blur[0]();
    expect(socket.commands().at(-1)).toEqual({ type: "stop" });
    expect(socket.commands().some(command => command.type === "end_live_touch")).toBe(false);

    pad.fire("pointerdown", { pointerId: 2, pointerType: "touch", clientX: 150, clientY: 100, button: 0 });
    app.document.hidden = true;
    app.documentListeners.visibilitychange[0]();
    expect(socket.commands().at(-1)).toEqual({ type: "stop" });
    expect(socket.commands().filter(command => command.type === "end_live_touch")).toHaveLength(0);
  });

  it("ignores the Live pad without access", () => {
    const app = boot();
    const socket = admitController(app, false);
    selectMode(app, "streamingLive");
    const pad = app.el("#live-pad");
    expect(pad.getAttribute("aria-disabled")).toBe("true");
    expect(pad.tabIndex).toBe(-1);
    pad.fire("pointerdown", { pointerId: 1, pointerType: "touch", clientX: 230, clientY: 100, button: 0 });
    pad.fire("keydown", { key: "ArrowUp" });
    pad.fire("pointerup", { pointerId: 1 });
    pad.fire("keyup", { key: "ArrowUp" });
    expect(socket.commands()).toHaveLength(0);
  });

  it("keeps STOP available while the WebSocket is open even without host or lease, never before ready", () => {
    const app = boot();
    const stop = app.el("#emergency-stop");
    const pattern = app.el("#pattern");
    expect(stop.disabled).toBe(true);
    const socket = app.ws();
    socket.open();
    socket.receive(presence(false));
    expect(stop.disabled).toBe(true);
    socket.receive(ready());
    expect(pattern.disabled).toBe(true);
    expect(stop.disabled).toBe(false);
    stop.fire("click");
    expect(socket.sent.some(message => message.type === "emergency_stop")).toBe(true);
    socket.receive({ type: "lease_lost", participantId: "c1", reason: "heartbeat_timeout" });
    expect(pattern.disabled).toBe(true);
    expect(stop.disabled).toBe(false);
    socket.drop();
    expect(stop.disabled).toBe(true);
  });

  it("reconnects with bounded backoff and opens no motor control before ready", async () => {
    const app = boot();
    const pattern = app.el("#pattern");
    const stop = app.el("#emergency-stop");
    admitController(app).drop();
    expect(app.el("#status").textContent).toBe("RECONNEXION");
    expect(app.pendingDelays()).toEqual([1000]);
    app.runTimer();
    expect(FakeWebSocket.instances).toHaveLength(2);
    const second = app.ws();
    second.open();
    second.receive(presence(true));
    expect(pattern.disabled).toBe(true);
    expect(stop.disabled).toBe(true);
    second.receive(ready());
    expect(pattern.disabled).toBe(false);
    expect(stop.disabled).toBe(false);

    // The attempt counter resets after ready, then grows to the cap without a ready.
    second.drop();
    expect(app.pendingDelays()).toEqual([1000]);
    const delays = [];
    for (let attempt = 0; attempt < 7; attempt += 1) {
      delays.push(app.pendingDelays()[0]);
      app.runTimer();
      app.ws().drop();
      await flush();
    }
    expect(delays).toEqual([1000, 2000, 4000, 8000, 15000, 15000, 15000]);
    expect(pattern.disabled).toBe(true);
  });

  it("stops reconnecting after a bounded number of attempts", async () => {
    const app = boot();
    admitController(app).drop();
    for (let attempt = 0; attempt < 40 && app.timers.size > 0; attempt += 1) {
      app.runTimer();
      app.ws().drop();
      await flush();
    }
    expect(app.timers.size).toBe(0);
    expect(FakeWebSocket.instances.length).toBeLessThanOrEqual(32);
    expect(app.el("#status").textContent).toBe("HORS LIGNE");
  });

  it("explains an expired session over keyless HTTP instead of a generic WebSocket error", async () => {
    const app = boot({ fetchImpl: async () => ({ status: 410, ok: false }) });
    const socket = app.ws();
    socket.emit("error");
    socket.drop();
    await flush();
    expect(app.fetchMock).toHaveBeenCalledTimes(1);
    const [url, init] = app.fetchMock.mock.calls[0];
    expect(url).toBe("/api/session/abcdefgh12345678/status");
    expect(`${url}${JSON.stringify(init)}`).not.toContain(SECRET);
    expect(`${url}${JSON.stringify(init)}`).not.toContain("k=");
    expect(app.el("#session-copy").textContent).toBe(SESSION_ENDED);
    expect(app.el("#status").textContent).toBe("SESSION EXPIRÉE");
    expect(app.timers.size).toBe(0);
    expect(FakeWebSocket.instances).toHaveLength(1);
    const diagnostics = app.el("#debug-log").children.map(item => item.textContent).join("\n") + app.el("#debug-last-event").textContent;
    expect(diagnostics).not.toContain(SECRET);
    expect(diagnostics).not.toContain("k=");
  });

  it("retries when the status probe says the session is still active or cannot tell", async () => {
    const app = boot({ fetchImpl: async () => ({ status: 200, ok: true }) });
    app.ws().drop();
    await flush();
    expect(app.pendingDelays()).toEqual([1000]);
    expect(app.el("#status").textContent).toBe("RECONNEXION");
    const unknown = boot({ fetchImpl: async () => { throw new Error("offline"); } });
    unknown.ws().drop();
    await flush();
    expect(unknown.pendingDelays()).toEqual([1000]);
  });

  it("handles a broker expiry event and a refused key without retrying", async () => {
    const expired = boot();
    const socket = admitController(expired);
    socket.receive({ type: "error", code: "expired" });
    socket.drop();
    await flush();
    expect(expired.el("#session-copy").textContent).toBe(SESSION_ENDED);
    expect(expired.timers.size).toBe(0);

    const refused = boot();
    const early = refused.ws();
    early.open();
    early.receive({ type: "error", code: "forbidden" });
    early.drop();
    await flush();
    expect(refused.el("#status").textContent).toBe("ACCÈS REFUSÉ");
    expect(refused.timers.size).toBe(0);
    expect(refused.fetchMock).not.toHaveBeenCalled();
  });

  it("names the camera permission prompt state and restores the idle copy on denial", async () => {
    let rejectCapture;
    const app = boot({ role: "camera", getUserMedia: () => new Promise((_, reject) => { rejectCapture = reject; }) });
    const socket = app.ws();
    socket.open();
    socket.receive({ type: "presence", hostOnline: true, controllerOnline: false, cameras: 1, cameraIds: ["c1"] });
    socket.receive(ready("camera"));
    expect(app.el("#status").textContent).toBe("CAMÉRA CONNECTÉE");
    app.el("#join-call").fire("click");
    expect(app.el("#status").textContent).toBe("AUTORISATION CAMÉRA");
    expect(app.el("#media-title").textContent).toBe("Autorisation caméra demandée");
    expect(app.el("#media-title").textContent).not.toContain("désactivées");
    rejectCapture(Object.assign(new Error("denied"), { name: "NotAllowedError" }));
    await flush();
    // The chain then tries video without voice (a WebView may grant only the camera).
    expect(app.el("#status").textContent).toBe("AUTORISATION CAMÉRA");
    rejectCapture(Object.assign(new Error("denied"), { name: "NotAllowedError" }));
    await flush();
    expect(app.el("#status").textContent).toBe("CAMÉRA BLOQUÉE");
    expect(app.el("#media-title").textContent).toBe("Caméra et voix désactivées");
    expect(app.el("#media-copy").textContent).toBe("Caméra de cet appareil indisponible (NotAllowedError) : permission refusée par le navigateur/WebView.");
  });

  it("shows a waiting state before any limits arrive without blocking STOP", () => {
    const app = boot();
    const socket = admitController(app);
    expect(nb(app.el("#phone-limits-values").textContent)).toBe("Maximums du téléphone : en attente");
    expect(app.el("#phone-limits").dataset.state).toBe("waiting");
    expect(app.el("#emergency-stop").disabled).toBe(false);
    expect(app.el("#speed").max).toBe("100");
    expect(app.el("#speed-limit").hidden).toBe(true);
    expect(socket.commands()).toHaveLength(0);
  });

  it("displays the phone maxima, bounds the sliders and clamps values without sending commands", () => {
    const app = boot();
    const socket = admitController(app);
    app.el("#speed").value = "90";
    const sentBefore = socket.sent.length;
    socket.receive(limitsEvent(0.6, 0.2, 0.8));

    expect(nb(app.el("#phone-limits-values").textContent)).toBe("Vitesse max 60 % · Profondeur 20–80 %");
    expect(app.el("#phone-limits").dataset.state).toBe("set");
    expect(app.el("#speed").max).toBe("60");
    expect(app.el("#speed").value).toBe("60");
    expect(app.el("#minimum").min).toBe("20");
    expect(app.el("#minimum").max).toBe("80");
    expect(app.el("#minimum").value).toBe("20");
    expect(app.el("#maximum").min).toBe("20");
    expect(app.el("#maximum").max).toBe("80");
    expect(app.el("#maximum").value).toBe("80");
    expect(app.el("#speed-limit").hidden).toBe(false);
    expect(nb(app.el("#speed-limit").textContent)).toBe("max 60 %");
    expect(nb(app.el("#maximum-limit").textContent)).toBe("20–80 %");
    expect(nb(app.el("#speed").getAttribute("aria-label"))).toBe("Vitesse, maximum fixé par le téléphone : 60 %");
    expect(app.el("#speed").getAttribute("aria-describedby")).toBe("speed-limit");
    expect(app.el("#speed").disabled).toBe(false);
    expect(app.el("#phone-limits-note").hidden).toBe(true);
    expect(socket.sent.slice(sentBefore).filter(message => message.type === "command" || message.type === "emergency_stop")).toHaveLength(0);

    // A live change re-clamps again, still with no automatic motion command.
    socket.receive(limitsEvent(0.3, 0, 0.5));
    expect(app.el("#speed").value).toBe("30");
    expect(app.el("#maximum").value).toBe("50");
    expect(app.el("#minimum").value).toBe("20");
    expect(socket.commands()).toHaveLength(0);
    // Widening the envelope never raises values by itself.
    socket.receive(limitsEvent(1, 0, 1));
    expect(app.el("#speed").value).toBe("30");
    expect(app.el("#speed").max).toBe("100");
    expect(socket.commands()).toHaveLength(0);
  });

  it("explains a 0 % speed maximum and keeps the speed slider unusable while STOP stays available", () => {
    const app = boot();
    const socket = admitController(app);
    socket.receive(limitsEvent(0, 0, 1));
    expect(app.el("#phone-limits").dataset.state).toBe("zero");
    expect(app.el("#phone-limits-note").hidden).toBe(false);
    expect(app.el("#phone-limits-note").textContent).toBe("Le téléphone est à 0 % : réglez un maximum sur le téléphone");
    expect(app.el("#speed").disabled).toBe(true);
    expect(app.el("#speed").max).toBe("0");
    expect(app.el("#emergency-stop").disabled).toBe(false);
    socket.receive(presence(true));
    expect(app.el("#speed").disabled).toBe(true);
    expect(app.el("#pattern").disabled).toBe(false);
    socket.receive(limitsEvent(0.5, 0, 1));
    expect(app.el("#speed").disabled).toBe(false);
    expect(app.el("#phone-limits-note").hidden).toBe(true);
  });

  it("ignores malformed limits and returns to waiting after the socket closes", () => {
    const app = boot();
    const socket = admitController(app);
    socket.receive(limitsEvent(0.5, 0.2, 0.8));
    for (const bad of [limitsEvent(0.5, 0.9, 0.1), limitsEvent("0.5", 0, 1), limitsEvent(1.5, 0, 1), { ...limitsEvent(0.5, 0, 1), extra: true }]) socket.receive(bad);
    expect(nb(app.el("#phone-limits-values").textContent)).toBe("Vitesse max 50 % · Profondeur 20–80 %");
    socket.drop();
    expect(nb(app.el("#phone-limits-values").textContent)).toBe("Maximums du téléphone : en attente");
    expect(app.el("#speed").max).toBe("100");
  });

  it("shows each remote diagnostic in plain French and ignores unknown codes", () => {
    const expected = {
      firmware_remote_auth_required: "échec d’autorisation distante",
      remote_lease_invalid: "refusé le bail distant",
      remote_session_not_started: "session distante n’est pas démarrée",
      remote_machine_not_ready: "Le téléphone attend que la machine soit prête (connexion, homing).",
      remote_sequence_stale: "commande périmée",
      remote_live_position_rejected: "position Direct",
    };
    const app = boot();
    const socket = admitController(app);
    for (const [code, phrase] of Object.entries(expected)) {
      socket.receive({ type: "diagnostic", severity: "warning", code });
      expect(app.el("#debug-app-event").textContent).toContain(code);
      expect(app.el("#debug-app-event").textContent).toContain(phrase);
    }
    socket.receive({ type: "diagnostic", severity: "warning", code: "remote_unknown" });
    expect(app.el("#debug-app-event").textContent).toContain("remote_live_position_rejected");
    expect(app.el("#debug-last-event").textContent).toContain("non reconnu");
  });
});

const bandEvent = (sensationMin, sensationMax, speedMax = 0.6, depthMin = 0.2, depthMax = 0.8) => ({ type: "limits", speedMax, depthMin, depthMax, sensationMin, sensationMax });

describe("phone sensation band (bipolar, 50 = neutral)", () => {
  it("centres the slider on neutral, bounds it to the phone band and re-clamps without sending a command", () => {
    const app = boot();
    const socket = admitController(app);
    const sensation = app.el("#sensation");
    expect(sensation.value).toBe("50");
    socket.receive(bandEvent(0.3, 0.7));
    expect(nb(app.el("#phone-limits-values").textContent)).toBe("Vitesse max 60 % · Sensation ±40 (−40…+40) · Profondeur 20–80 %");
    expect(sensation.min).toBe("30");
    expect(sensation.max).toBe("70");
    expect(sensation.value).toBe("50");
    expect(app.el("#sensation-value").textContent).toBe("0");
    expect(sensation.getAttribute("aria-valuetext")).toBe("neutre");
    expect(app.el("#sensation-limit").hidden).toBe(false);
    expect(app.el("#sensation-limit").textContent).toBe("intensité max ±40");
    expect(sensation.getAttribute("aria-label")).toBe("Sensation, intensité maximale fixée par le téléphone : ±40");
    expect(sensation.getAttribute("aria-describedby")).toBe("sensation-limit sensation-legend");
    expect(app.el("#sensation-track").dataset.neutral).toBe("in");
    expect(socket.commands()).toHaveLength(0);

    selectMode(app, "teasingPounding");
    expect(app.el("#sensation-legend").textContent).toBe("← retrait rapide · avancée rapide →");
    drag(app, "#sensation", 30);
    expect(app.el("#sensation-value").textContent).toBe("−40");
    expect(sensation.getAttribute("aria-valuetext")).toBe("retrait rapide 40");
    drag(app, "#sensation", 65);
    expect(app.el("#sensation-value").textContent).toBe("+30");
    expect(sensation.getAttribute("aria-valuetext")).toBe("avancée rapide 30");
    expect(socket.commands()).toEqual([{ type: "select_pattern", key: "teasingPounding" }, { type: "set_sensation", value: 0.3 }, { type: "set_sensation", value: 0.65 }]);

    // The phone narrows the intensity: the value is re-clamped and shown, but nothing is sent.
    socket.receive(bandEvent(0.4, 0.6));
    expect(sensation.value).toBe("60");
    expect(app.el("#sensation-value").textContent).toBe("+20");
    expect(sensation.getAttribute("aria-valuetext")).toBe("avancée rapide 20");
    expect(app.el("#sensation-limit").textContent).toBe("intensité max ±20");
    // Widening never raises the value by itself.
    socket.receive(bandEvent(0, 1));
    expect(sensation.value).toBe("60");
    expect(app.el("#sensation-limit").textContent).toBe("intensité max ±100");
    expect(socket.commands()).toHaveLength(3);
  });

  it("uses the documented StrokeEngine semantics per mode and a generic legend elsewhere", () => {
    const app = boot();
    const socket = admitController(app);
    socket.receive(bandEvent(0.3, 0.7));
    const sensation = app.el("#sensation");
    expect(app.el("#sensation-legend").textContent).toBe("Sans effet en Simple Stroke");
    drag(app, "#sensation", 40);
    expect(sensation.getAttribute("aria-valuetext")).toBe("−20");
    const expected = {
      teasingPounding: ["← retrait rapide · avancée rapide →", "retrait rapide 20"],
      halfNHalf: ["← retrait rapide · avancée rapide →", "retrait rapide 20"],
      roboStroke: ["← accélération douce · mouvement robotique →", "accélération douce 20"],
      deeper: ["← rampe courte · rampe longue →", "rampe courte 20"],
      stopNGo: ["← pauses courtes · pauses longues →", "pauses courtes 20"],
      insist: ["← vers l’arrière · vers l’avant →", "vers l’arrière 20"],
      progressive: ["← vers −100 · vers +100 →", "−20"],
      autoRandom: ["← vers −100 · vers +100 →", "−20"],
    };
    for (const [key, [legend, valueText]] of Object.entries(expected)) {
      selectMode(app, key);
      expect(app.el("#sensation-legend").textContent).toBe(legend);
      expect(sensation.getAttribute("aria-valuetext")).toBe(valueText);
    }
  });

  it("rounds the band inwards, keeps a collapsed band on neutral and rejects incomplete or inverted bands", () => {
    const app = boot();
    const socket = admitController(app);
    const sensation = app.el("#sensation");
    socket.receive(bandEvent(0.445, 0.555));
    expect(sensation.min).toBe("45");
    expect(sensation.max).toBe("55");
    expect(app.el("#sensation-limit").textContent).toBe("intensité max ±10");
    socket.receive(bandEvent(0.5, 0.5));
    expect(sensation.min).toBe("50");
    expect(sensation.max).toBe("50");
    expect(sensation.value).toBe("50");
    expect(app.el("#sensation-limit").textContent).toBe("intensité max ±0");
    const { sensationMax: _ignored, ...minOnly } = bandEvent(0.3, 0.7);
    for (const bad of [minOnly, bandEvent(0.7, 0.3), bandEvent(-0.1, 0.5), bandEvent(0.3, 1.2), bandEvent("0.3", 0.7), { ...bandEvent(0.3, 0.7), extra: 1 }]) socket.receive(bad);
    expect(nb(app.el("#phone-limits-values").textContent)).toBe("Vitesse max 60 % · Sensation ±0 (0…0) · Profondeur 20–80 %");
    expect(socket.commands()).toHaveLength(0);
    expect(app.el("#emergency-stop").disabled).toBe(false);
  });

  it("keeps the legacy sensationMax-only frame as the old ceiling [0, sensationMax]", () => {
    const app = boot();
    const socket = admitController(app);
    const sensation = app.el("#sensation");
    socket.receive(limitsEvent(0.6, 0.2, 0.8, 0.4));
    expect(nb(app.el("#phone-limits-values").textContent)).toBe("Vitesse max 60 % · Sensation ±100 (−100…−20) · Profondeur 20–80 %");
    expect(sensation.min).toBe("0");
    expect(sensation.max).toBe("40");
    expect(sensation.value).toBe("40");
    expect(app.el("#sensation-value").textContent).toBe("−20");
    expect(app.el("#sensation-limit").textContent).toBe("plage −100…−20");
    expect(sensation.getAttribute("aria-label")).toBe("Sensation, plage fixée par le téléphone : −100 à −20");
    expect(app.el("#sensation-track").dataset.neutral).toBe("out");
    expect(socket.commands()).toHaveLength(0);

    // A 4-key frame (APK 2.3.13) leaves sensation unbounded and never raises the value.
    socket.receive(limitsEvent(0.6, 0.2, 0.8));
    expect(nb(app.el("#phone-limits-values").textContent)).toBe("Vitesse max 60 % · Profondeur 20–80 %");
    expect(sensation.max).toBe("100");
    expect(sensation.value).toBe("40");
    expect(app.el("#sensation-limit").hidden).toBe(true);
    expect(sensation.getAttribute("aria-label")).toBeUndefined();
    expect(sensation.getAttribute("aria-describedby")).toBe("sensation-legend");
    expect(app.el("#sensation-track").dataset.neutral).toBe("in");
    expect(socket.commands()).toHaveLength(0);
  });

  it("keeps sensation unbounded without sensation keys and ignores a malformed sensationMax", () => {
    const app = boot();
    const socket = admitController(app);
    socket.receive(limitsEvent(0.5, 0, 1));
    expect(app.el("#sensation").max).toBe("100");
    expect(app.el("#sensation-limit").hidden).toBe(true);
    for (const bad of [limitsEvent(0.5, 0, 1, 1.2), limitsEvent(0.5, 0, 1, -0.1), limitsEvent(0.5, 0, 1, "0.4"), { ...limitsEvent(0.5, 0, 1, 0.4), extra: 1 }]) socket.receive(bad);
    expect(nb(app.el("#phone-limits-values").textContent)).toBe("Vitesse max 50 % · Profondeur 0–100 %");
    socket.receive(limitsEvent(0.5, 0, 1, 0));
    expect(app.el("#sensation").max).toBe("0");
    expect(app.el("#sensation").value).toBe("0");
    expect(app.el("#emergency-stop").disabled).toBe(false);
  });
});

describe("no automatic motion after a loss", () => {
  it("sends a slider value only for a fresh user drag while control is held", () => {
    const app = boot();
    const socket = admitController(app);
    drag(app, "#speed", 40);
    drag(app, "#sensation", 30);
    expect(socket.commands()).toEqual([{ type: "set_speed", value: 0.4 }, { type: "set_sensation", value: 0.3 }]);
    // A "change" without a preceding user input (programmatic) is never sent.
    app.el("#speed").fire("change");
    expect(socket.commands()).toHaveLength(2);
  });

  it("never re-sends a slider value or mode when the socket comes back", () => {
    const app = boot();
    const first = admitController(app);
    first.receive(limitsEvent(0.8, 0, 1, 0.9));
    const speed = app.el("#speed");
    speed.value = "55";
    speed.fire("input"); // the finger is down when the socket drops
    first.drop();
    app.runTimer();
    const second = app.ws();
    second.open();
    second.receive(presence(true));
    second.receive(ready());
    second.receive(limitsEvent(0.8, 0, 1, 0.9)); // replayed by the broker
    expect(app.el("#pattern").disabled).toBe(false);
    speed.fire("change"); // the stale release arrives after reconnection
    app.el("#sensation").fire("change");
    expect(second.commands()).toHaveLength(0);
    expect(second.sent.filter(message => message.type === "emergency_stop")).toHaveLength(0);
    // A fresh drag after reconnection works normally.
    drag(app, "#speed", 20);
    expect(second.commands()).toEqual([{ type: "set_speed", value: 0.2 }]);
  });

  it("drops an in-flight drag across a phone or lease loss and blocks mode changes without access", () => {
    const app = boot();
    const socket = admitController(app);
    app.el("#maximum").value = "70";
    app.el("#maximum").fire("input");
    expect(socket.commands()).toEqual([{ type: "set_depth", minimum: 0, maximum: 0.7 }]);
    app.el("#maximum").value = "60";
    app.el("#maximum").fire("input"); // throttled: pending trailing value
    socket.receive(presence(false));
    for (const timer of [...app.timers.values()].filter(timer => timer.ms === 80)) timer.fn();
    selectMode(app, "teasingPounding");
    socket.receive(presence(true));
    app.el("#maximum").fire("change");
    expect(socket.commands()).toHaveLength(1);
    socket.receive({ type: "lease_lost", participantId: "c1", reason: "heartbeat_timeout" });
    drag(app, "#speed", 50);
    selectMode(app, "insist");
    expect(socket.commands()).toHaveLength(1);
  });

  it("cancels a pending scalar trailing frame when Direct mode changes", () => {
    const app = boot();
    const socket = admitController(app);
    const speed = app.el("#speed");
    speed.value = "40";
    speed.fire("input");
    speed.value = "60";
    speed.fire("input");
    selectMode(app, "teasingPounding");
    for (const timer of [...app.timers.values()].filter(timer => timer.ms === 80)) timer.fn();
    speed.fire("change");

    expect(socket.commands()).toEqual([
      { type: "set_speed", value: 0.4 },
      { type: "select_pattern", key: "teasingPounding" },
    ]);
  });
});

describe("mode chips", () => {
  it("sends the exact phone pattern key for each chip and shows Direct as the only Live control", () => {
    const app = boot();
    const socket = admitController(app);
    for (const key of MODE_KEYS) selectMode(app, key);
    expect(socket.commands().filter(command => command.type === "select_pattern").map(command => command.key)).toEqual(MODE_KEYS);
    selectMode(app, "streamingLive");
    expect(app.el("#live-touch-panel").hidden).toBe(false);
    expect(app.el("#parameter-controls").hidden).toBe(true);
    expect(app.el("#standard-controls").hidden).toBe(true);
    selectMode(app, "teasingPounding");
    expect(app.el("#live-touch-panel").hidden).toBe(true);
    expect(app.el("#mode-title").textContent).toBe("teasingPounding");
  });
});

describe("multiple camera views", () => {
  const offer = (from) => ({ type: "signal", from, data: JSON.stringify({ kind: "offer", description: { type: "offer", sdp: `offer-${from}` } }) });
  const cameraPresence = (cameraIds) => ({ ...presence(true), cameras: cameraIds.length, cameraIds });
  const tiles = (app) => app.el("#video-grid").children;
  const labelOf = (tile) => tile.children.find(child => child.className === "tile-label").textContent;

  it("keeps one peer connection per camera and closes only the camera that leaves", async () => {
    FakePeerConnection.instances = [];
    const app = boot({ rtc: FakePeerConnection });
    const socket = admitController(app);
    expect(app.el("#video-placeholder").hidden).toBe(false);
    expect(app.el("#video-placeholder-label").textContent).toBe("AUCUNE CAMÉRA CONNECTÉE");
    socket.receive(cameraPresence(["cam-a", "cam-b"]));
    socket.receive(offer("cam-a"));
    socket.receive(offer("cam-b"));
    await flush();
    expect(FakePeerConnection.instances).toHaveLength(2);
    const answers = socket.sent.filter(message => message.type === "signal" && JSON.parse(message.data).kind === "answer");
    expect(answers.map(message => message.to)).toEqual(["cam-a", "cam-b"]);

    const [linkA, linkB] = FakePeerConnection.instances;
    linkA.ontrack({ streams: [{ id: "stream-a" }] });
    linkB.ontrack({ streams: [{ id: "stream-b" }] });
    await flush();
    expect(tiles(app)).toHaveLength(2);
    expect(tiles(app).map(labelOf)).toEqual(["Caméra 1", "Caméra 2"]);
    expect(app.el("#video-stage").dataset.cameras).toBe("2");
    expect(app.el("#video-placeholder").hidden).toBe(true);
    expect(tiles(app)[0].classes.has("primary")).toBe(true);
    expect(tiles(app)[1].classes.has("thumb")).toBe(true);
    // Every view starts muted (autoplay-safe).
    const videoOf = (tile) => tile.children.find(child => child.tagName === "VIDEO");
    expect(tiles(app).map(tile => videoOf(tile).muted)).toEqual([true, true]);

    // Audio from one view at a time.
    const audioOf = (tile) => tile.children.find(child => child.className === "tile-tools").children[1];
    audioOf(tiles(app)[1]).fire("click");
    expect(tiles(app).map(tile => videoOf(tile).muted)).toEqual([true, false]);
    audioOf(tiles(app)[0]).fire("click");
    expect(tiles(app).map(tile => videoOf(tile).muted)).toEqual([false, true]);
    expect(audioOf(tiles(app)[0]).getAttribute("aria-pressed")).toBe("true");

    // Tap the thumbnail to make it the large view.
    tiles(app)[1].children.find(child => child.className === "tile-swap").fire("click");
    expect(tiles(app)[1].classes.has("primary")).toBe(true);

    // Camera A leaves: only its connection closes, camera B keeps playing.
    socket.receive({ type: "lease_lost", participantId: "cam-a", reason: "disconnected" });
    socket.receive(cameraPresence(["cam-b"]));
    expect(linkA.closed).toBe(true);
    expect(linkB.closed).toBe(false);
    expect(tiles(app).map(labelOf)).toEqual(["Caméra 2"]);
    expect(app.el("#status").textContent).toBe("CONTRÔLE ACTIF");
    expect(socket.commands()).toHaveLength(0);
  });

  it("ignores media from participants the broker did not list and caps views at four", async () => {
    FakePeerConnection.instances = [];
    const app = boot({ rtc: FakePeerConnection });
    const socket = admitController(app);
    socket.receive(cameraPresence(["c-1", "c-2", "c-3", "c-4", "c-5"]));
    socket.receive(offer("intruder"));
    socket.receive(offer("c-5"));
    await flush();
    expect(FakePeerConnection.instances).toHaveLength(0);
    for (const id of ["c-1", "c-2", "c-3", "c-4"]) socket.receive(offer(id));
    await flush();
    expect(FakePeerConnection.instances).toHaveLength(4);
  });

  it("closing the video never sends STOP and reopening asks each camera for a new offer", async () => {
    FakePeerConnection.instances = [];
    const app = boot({ rtc: FakePeerConnection });
    const socket = admitController(app);
    socket.receive(cameraPresence(["cam-a", "cam-b"]));
    socket.receive(offer("cam-a"));
    await flush();
    FakePeerConnection.instances[0].ontrack({ streams: [{ id: "a" }] });
    await flush();
    app.el("#stop-video").fire("click");
    expect(FakePeerConnection.instances[0].closed).toBe(true);
    expect(tiles(app)).toHaveLength(0);
    expect(socket.sent.some(message => message.type === "emergency_stop" || message.type === "command")).toBe(false);
    app.el("#stop-video").fire("click");
    const requests = socket.sent.filter(message => message.type === "signal" && JSON.parse(message.data).kind === "offer_request");
    expect(requests.map(message => message.to)).toEqual(["cam-a", "cam-b"]);
  });

  it("shows a muted local preview in the camera role and offers only to the controller", async () => {
    FakePeerConnection.instances = [];
    const track = { kind: "video", stop: vi.fn() };
    const stream = { getTracks: () => [track], getVideoTracks: () => [track] };
    const getUserMedia = vi.fn(async () => stream);
    const app = boot({ role: "camera", getUserMedia, rtc: FakePeerConnection });
    const socket = app.ws();
    socket.open();
    socket.receive({ type: "presence", hostOnline: true, controllerOnline: true, cameras: 1, cameraIds: ["me"], controllerId: "ctrl" });
    socket.receive(ready("camera"));
    app.el("#join-call").fire("click");
    await flush();
    await flush();
    expect(getUserMedia).toHaveBeenCalledTimes(1);
    expect(getUserMedia.mock.calls[0][0]).toMatchObject({ audio: true, video: { facingMode: { ideal: "environment" } } });
    const [preview] = tiles(app);
    const previewVideo = preview.children.find(child => child.tagName === "VIDEO");
    expect(previewVideo.srcObject).toBe(stream);
    expect(previewVideo.muted).toBe(true);
    expect(previewVideo.getAttribute("playsinline")).toBe("");
    expect(app.el("#status").textContent).toBe("CAMÉRA PRÊTE");
    const offers = socket.sent.filter(message => message.type === "signal" && JSON.parse(message.data).kind === "offer");
    expect(offers.map(message => message.to)).toEqual(["ctrl"]);
    expect(socket.sent.some(message => message.type === "command" || message.type === "emergency_stop" || message.type === "limits")).toBe(false);
    expect(app.el("#remote-controls").hidden).toBe(true);
    expect(app.el("#phone-limits").hidden).toBe(true);
  });
});

describe("real-time sliders (throttled while dragging)", () => {
  const runThrottle = (app) => {
    const entry = [...app.timers.entries()].find(([, timer]) => timer.ms === 80);
    if (!entry) return false;
    app.timers.delete(entry[0]);
    entry[1].fn();
    return true;
  };
  const move = (app, selector, value) => { const input = app.el(selector); input.value = String(value); input.fire("input"); };

  it("sends the first value at once, at most one more per 80 ms window, and nothing more on release", () => {
    const app = boot();
    const socket = admitController(app);
    for (const value of [10, 20, 30, 40]) move(app, "#speed", value);
    expect(socket.commands()).toEqual([{ type: "set_speed", value: 0.1 }]);
    expect(app.pendingDelays().filter(ms => ms === 80)).toHaveLength(1);
    expect(runThrottle(app)).toBe(true);
    expect(socket.commands()).toEqual([{ type: "set_speed", value: 0.1 }, { type: "set_speed", value: 0.4 }]);
    // Quiet window: the cooldown ends without sending anything.
    expect(runThrottle(app)).toBe(true);
    expect(runThrottle(app)).toBe(false);
    app.el("#speed").fire("change");
    expect(socket.commands()).toHaveLength(2);
    const seqs = socket.sent.filter(message => message.type === "command").map(message => message.seq);
    expect(seqs).toEqual([...seqs].sort((a, b) => a - b));
    expect(new Set(seqs).size).toBe(seqs.length);
  });

  it("guarantees the final value on release (change or pointerup) when it differs from the last one sent", () => {
    const app = boot();
    const socket = admitController(app);
    move(app, "#sensation", 60);
    move(app, "#sensation", 72);
    app.el("#sensation").fire("pointerup");
    expect(socket.commands()).toEqual([{ type: "set_sensation", value: 0.6 }, { type: "set_sensation", value: 0.72 }]);
    expect(app.pendingDelays().filter(ms => ms === 80)).toHaveLength(0);
    app.el("#sensation").fire("change");
    expect(socket.commands()).toHaveLength(2);
    // Depth sliders stream the full depth envelope.
    move(app, "#minimum", 15);
    move(app, "#maximum", 85);
    app.el("#minimum").fire("change");
    app.el("#maximum").fire("change");
    expect(socket.commands().slice(2)).toEqual([{ type: "set_depth", minimum: 0.15, maximum: 1 }, { type: "set_depth", minimum: 0.15, maximum: 0.85 }]);
  });

  it("drops a pending scalar trailing frame on pointer cancellation", () => {
    const app = boot();
    const socket = admitController(app);
    const speed = app.el("#speed");
    speed.value = "30";
    speed.fire("input");
    speed.value = "45";
    speed.fire("input");
    speed.fire("pointercancel");
    for (const timer of [...app.timers.values()].filter(timer => timer.ms === 80)) timer.fn();

    expect(socket.commands()).toEqual([{ type: "set_speed", value: 0.3 }]);
    expect(app.pendingDelays().filter(ms => ms === 80)).toHaveLength(0);
  });

  it("does not disturb an active scalar drag on an identical replayed limits frame", () => {
    const app = boot();
    const socket = admitController(app);
    const speed = app.el("#speed");
    socket.receive(limitsEvent(0.8, 0, 1));
    speed.value = "55";
    speed.fire("input");
    speed.value = "65";
    speed.fire("input");
    socket.receive(limitsEvent(0.8, 0, 1));
    expect(speed.value).toBe("65");
    expect(runThrottle(app)).toBe(true);
    expect(socket.commands()).toEqual([{ type: "set_speed", value: 0.55 }, { type: "set_speed", value: 0.65 }]);
  });

  it("sends nothing without control access, and the phone's maximums re-clamp without a command", () => {
    const app = boot();
    const socket = admitController(app, false);
    move(app, "#speed", 30);
    app.el("#speed").fire("change");
    expect(socket.commands()).toHaveLength(0);
    socket.receive(presence(true));
    move(app, "#speed", 30);
    expect(socket.commands()).toEqual([{ type: "set_speed", value: 0.3 }]);
    socket.receive(limitsEvent(0.2, 0, 1));
    expect(app.el("#speed").value).toBe("20");
    while (runThrottle(app));
    expect(socket.commands()).toHaveLength(1);
  });

  it("never sends a pending or released value after a loss and reconnection", () => {
    const app = boot();
    const first = admitController(app);
    move(app, "#speed", 30);
    move(app, "#speed", 45); // pending trailing value when the socket drops
    first.drop();
    while (runThrottle(app));
    app.runTimer();
    const second = app.ws();
    second.open();
    second.receive(presence(true));
    second.receive(ready());
    while (runThrottle(app));
    app.el("#speed").fire("change");
    app.el("#speed").fire("pointerup");
    expect(first.commands()).toEqual([{ type: "set_speed", value: 0.3 }]);
    expect(second.commands()).toHaveLength(0);
    // A fresh drag after reconnection streams again, even with the same value as before the loss.
    move(app, "#speed", 45);
    expect(second.commands()).toEqual([{ type: "set_speed", value: 0.45 }]);
  });
});

describe("Direct anchored pad, upper half-circle (v2.0.13)", () => {
  // Pad stub: 300 x 200 at the origin. The first touch is the anchor (0). Only the half-plane above
  // the anchor line counts: radial distance / distance from the anchor to the farthest TOP corner
  // (measured at pointerdown, at least 48 px). On or below the anchor line the value is 0.
  const C = { x: 150, y: 100 }; // centre anchor -> farthest top corner hypot(150, 100) = 180.28 px
  const R = Math.hypot(150, 100);
  // A point at `share` of the way from the centre anchor to the top-left corner (0, 0).
  const up = (share) => [C.x - share * 150, C.y - share * 100];
  const positions = (socket) => socket.commands().filter(command => command.type === "set_live_position").map(command => command.percent);
  const ends = (socket) => socket.commands().filter(command => command.type === "end_live_touch").length;
  const runLive = (app) => {
    const entry = [...app.timers.entries()].find(([, timer]) => timer.ms === 40);
    if (!entry) return false;
    app.timers.delete(entry[0]);
    entry[1].fn();
    return true;
  };
  const liveController = () => {
    const app = boot();
    const socket = admitController(app);
    selectMode(app, "streamingLive");
    return { app, socket, pad: app.el("#live-pad") };
  };
  const down = (pad, x, y, pointerId = 1) => pad.fire("pointerdown", { pointerId, pointerType: "touch", button: 0, clientX: x, clientY: y });
  const move = (pad, x, y, pointerId = 1) => pad.fire("pointermove", { pointerId, clientX: x, clientY: y });

  it("makes the first touched point, anywhere, the anchor and sends 0 at once", () => {
    for (const [x, y] of [[C.x, C.y], [0, 0], [290, 15], [40, 180]]) {
      const { socket, pad } = liveController();
      down(pad, x, y);
      expect(positions(socket)).toEqual([0]);
      expect(pad.getAttribute("aria-valuenow")).toBe("0");
      expect(pad.dataset.state).toBe("active");
      expect(pad.style.props["--ax"]).toBe(`${x.toFixed(1)}px`);
      expect(pad.style.props["--ay"]).toBe(`${y.toFixed(1)}px`);
    }
  });

  it("normalises by the distance from the anchor to the farthest top corner of the zone", () => {
    const geometry = [
      // anchor, distance to its farthest top corner
      [[150, 100], Math.hypot(150, 100)],
      [[60, 50], Math.hypot(240, 50)],
      [[290, 15], Math.hypot(290, 15)],
      [[40, 180], Math.hypot(260, 180)],
      [[200, 199], Math.hypot(200, 199)],
    ];
    for (const [[ax, ay], reach] of geometry) {
      const { app, socket, pad } = liveController();
      down(pad, ax, ay);
      expect(pad.style.props["--range"]).toBe(`${(reach * 2).toFixed(1)}px`);
      // Halfway towards the farthest top corner = 50, that corner itself = 100.
      const cornerX = ax < 150 ? 300 : 0;
      move(pad, ax + (cornerX - ax) / 2, ay / 2);
      expect(app.el("#live-value").textContent).toBe("50 %");
      runLive(app);
      move(pad, cornerX, 0);
      runLive(app);
      expect(positions(socket)).toEqual([0, 50, 100]);
    }
  });

  it("uses the radial distance above the anchor line and keeps 0 on or below it", () => {
    const { app, socket, pad } = liveController();
    down(pad, C.x, C.y);
    const cases = [
      [C.x, C.y - R / 2, 50, "above"],         // straight up
      [C.x + 30, C.y - 40, 28, "above"],       // diagonal above: hypot(30, 40) = 50 -> 27.7
      [C.x - 90, C.y - 60, 60, "above"],       // up and left: hypot(90, 60) = 108.2 -> 60
      [C.x + R / 2, C.y, 0, "below"],          // sideways on the anchor line: 0
      [C.x - 120, C.y, 0, "below"],
      [C.x, C.y + 50, 0, "below"],             // below: 0, never mirrored
      [C.x + 100, C.y + 1, 0, "below"],
      [C.x + 30, C.y - 40, 28, "above"],       // back above: the real distance again
      [0, 0, 100, "above"], [300, 0, 100, "above"], // either top corner is exactly 100
      [C.x + 400, C.y - 400, 100, "above"],    // clamped beyond the reach
      [C.x + 0.3, C.y - 0.3, 0, "above"],
    ];
    const expected = [0];
    for (const [x, y, percent, zone] of cases) {
      move(pad, x, y);
      expect(app.el("#live-value").textContent).toBe(`${percent} %`);
      expect(pad.getAttribute("aria-valuenow")).toBe(String(percent));
      expect(pad.dataset.zone).toBe(zone);
      runLive(app);
      if (percent !== expected.at(-1)) expected.push(percent);
    }
    expect(positions(socket)).toEqual(expected);
    expect(pad.getAttribute("aria-valuetext")).toBe("0 %, rentré");
  });

  it("draws the ray above the anchor line and collapses it below", () => {
    const { pad } = liveController();
    down(pad, C.x, C.y);
    move(pad, C.x + 30, C.y - 40);
    expect(pad.style.props["--ray"]).toBe("50.0px");
    move(pad, C.x + 30, C.y + 40);
    expect(pad.style.props["--ray"]).toBe("0.0px");
    expect(pad.style.props["--reach"]).toBe("0");
  });

  it("keeps a minimum reach of 48 px when the anchor is at the very top", () => {
    const { app, socket, pad } = liveController();
    pad.getBoundingClientRect = () => ({ width: 40, height: 200, left: 0, top: 0, right: 40, bottom: 200 });
    down(pad, 20, 0);
    expect(pad.style.props["--range"]).toBe("96.0px");
    move(pad, 40, 0); // on the anchor line
    expect(app.el("#live-value").textContent).toBe("0 %");
    move(pad, 20, -24); // captured pointer above the zone: 24 / 48
    expect(app.el("#live-value").textContent).toBe("50 %");
    runLive(app);
    move(pad, 20, -100);
    runLive(app);
    expect(positions(socket)).toEqual([0, 50, 100]);
  });

  it("takes a new anchor on every new touch instead of a fixed centre", () => {
    const { app, socket, pad } = liveController();
    down(pad, 300, 200);
    move(pad, 150, 100); // half-way to the far top corner (0, 0)
    runLive(app);
    pad.fire("pointerup", { pointerId: 1 });
    down(pad, 150, 100, 2); // where the previous finger was at 50: now it is 0
    expect(positions(socket)).toEqual([0, 50, 0]);
    expect(pad.style.props["--ax"]).toBe("150.0px");
  });

  it("streams fast back-and-forth: 0 at once, then at most one frame per 40 ms with the latest value", () => {
    const { app, socket, pad } = liveController();
    down(pad, C.x, C.y);
    expect(positions(socket)).toEqual([0]);
    for (const share of [0.2, 0.9, 0.7, 0.5]) move(pad, ...up(share));
    expect(positions(socket)).toEqual([0]);
    expect(app.pendingDelays().filter(ms => ms === 40)).toHaveLength(1);
    expect(runLive(app)).toBe(true);
    expect(positions(socket)).toEqual([0, 50]);
    move(pad, ...up(0.9));
    move(pad, ...up(0.1)); // and back again within the same window
    expect(runLive(app)).toBe(true);
    expect(positions(socket)).toEqual([0, 50, 10]);
    expect(runLive(app)).toBe(true); // quiet window ends silently
    expect(runLive(app)).toBe(false);
    move(pad, ...up(0.25));
    expect(positions(socket)).toEqual([0, 50, 10, 25]); // leading edge again after a quiet window
    move(pad, C.x + 40, C.y + 30); // dropping below the anchor line streams 0, never a mirror
    runLive(app);
    expect(positions(socket)).toEqual([0, 50, 10, 25, 0]);
    const seqs = socket.sent.filter(message => message.type === "command").map(message => message.seq);
    expect(new Set(seqs).size).toBe(seqs.length);
    expect(seqs).toEqual([...seqs].sort((a, b) => a - b));
  });

  it("drops the pending frame on release and sends nothing but end_live_touch afterwards", () => {
    const { app, socket, pad } = liveController();
    down(pad, C.x, C.y);
    move(pad, 0, 0); // pending trailing 100
    pad.fire("pointerup", { pointerId: 1 });
    expect(socket.commands().at(-1)).toEqual({ type: "end_live_touch" });
    expect(app.pendingDelays().filter(ms => ms === 40)).toHaveLength(0);
    while (runLive(app));
    move(pad, C.x + 10, C.y - 10);
    pad.fire("lostpointercapture", { pointerId: 1 });
    pad.fire("pointerup", { pointerId: 1 });
    expect(positions(socket)).toEqual([0]);
    expect(ends(socket)).toBe(1);
    expect(socket.commands().filter(command => command.type === "stop")).toHaveLength(0);
    expect(app.el("#live-value").textContent).toBe("0 %");
    expect(pad.dataset.state).toBe("released");
    expect(pad.style.props["--fx"]).toBe("0.0px");
    expect(pad.style.props["--ray"]).toBe("0.0px");
  });

  it("stops (never end_live_touch) on pointercancel during a fast gesture", () => {
    const { app, socket, pad } = liveController();
    down(pad, 20, 150);
    move(pad, 200, 10);
    pad.fire("pointercancel", { pointerId: 1 });
    expect(positions(socket)).toEqual([0]);
    expect(socket.commands().at(-1)).toEqual({ type: "stop" });
    expect(ends(socket)).toBe(0);
    expect(app.pendingDelays().filter(ms => ms === 40)).toHaveLength(0);
  });

  it("ignores a second finger while the first one drives the pad", () => {
    const { socket, pad } = liveController();
    down(pad, C.x, C.y);
    down(pad, C.x, C.y + 92, 2);
    move(pad, 0, 0, 2);
    pad.fire("pointerup", { pointerId: 2 });
    expect(positions(socket)).toEqual([0]);
    expect(ends(socket)).toBe(0);
    expect(pad.style.props["--ay"]).toBe("100.0px");
    pad.fire("pointerup", { pointerId: 1 });
    expect(ends(socket)).toBe(1);
  });

  it("steps by 5 with the arrow keys from 0 through the same throttle and ends on keyup", () => {
    const { app, socket, pad } = liveController();
    pad.fire("keydown", { key: "ArrowUp" });
    pad.fire("keydown", { key: "ArrowRight" });
    pad.fire("keydown", { key: "ArrowUp" });
    expect(positions(socket)).toEqual([5]);
    expect(pad.getAttribute("aria-valuenow")).toBe("15");
    runLive(app);
    expect(positions(socket)).toEqual([5, 15]);
    pad.fire("keyup", { key: "ArrowUp" });
    expect(socket.commands().at(-1)).toEqual({ type: "end_live_touch" });
    pad.fire("keydown", { key: "ArrowDown" });
    expect(pad.getAttribute("aria-valuenow")).toBe("0");
    pad.fire("keyup", { key: "ArrowDown" });
    pad.fire("keydown", { key: "Tab" });
    expect(positions(socket)).toEqual([5, 15, 0]);
    expect(ends(socket)).toBe(2);
  });

  it("never replays a position across a socket loss and reconnection", () => {
    const { app, socket, pad } = liveController();
    down(pad, C.x, C.y);
    move(pad, 0, 0); // pending
    socket.drop();
    while (runLive(app));
    const reconnect = [...app.timers.entries()].find(([, timer]) => timer.ms === 1000);
    app.timers.delete(reconnect[0]);
    reconnect[1].fn();
    const second = app.ws();
    expect(second).not.toBe(socket);
    second.open();
    second.receive(presence(true));
    second.receive(ready());
    move(pad, C.x + 60, C.y - 60);
    pad.fire("pointerup", { pointerId: 1 });
    while (runLive(app));
    expect(second.commands()).toEqual([]);
    expect(positions(socket)).toEqual([0]);
    down(pad, 10, 10, 3);
    expect(second.commands()).toEqual([{ type: "set_live_position", percent: 0 }]);
  });
});

describe("depth sliders never cross (v2.0.10 audit)", () => {
  const move = (app, selector, value) => { const input = app.el(selector); input.value = String(value); input.fire("input"); };

  it("pushes the other depth slider along so set_depth is never inverted", () => {
    const app = boot();
    const socket = admitController(app);
    move(app, "#maximum", 60);
    app.el("#maximum").fire("change");
    move(app, "#minimum", 80);
    expect(app.el("#maximum").value).toBe("80");
    expect(nb(app.el("#maximum-value").textContent)).toBe("80 %");
    app.el("#minimum").fire("change");
    move(app, "#maximum", 30);
    expect(app.el("#minimum").value).toBe("30");
    app.el("#maximum").fire("change");
    const depth = socket.commands().filter(command => command.type === "set_depth");
    expect(depth).toEqual([
      { type: "set_depth", minimum: 0, maximum: 0.6 },
      { type: "set_depth", minimum: 0.8, maximum: 0.8 },
      { type: "set_depth", minimum: 0.3, maximum: 0.3 },
    ]);
    for (const frame of depth) expect(frame.minimum).toBeLessThanOrEqual(frame.maximum);
  });
});

describe("camera capture robustness", () => {
  const domError = (name) => Object.assign(new Error("hidden detail https://x/#k=SECRET"), { name });
  const fakeTrack = (kind, deviceId, facingMode) => ({ kind, stop: vi.fn(), getSettings: () => ({ deviceId, facingMode }) });
  const fakeStream = (tracks) => {
    const list = [...tracks];
    return { getTracks: () => [...list], getVideoTracks: () => list.filter(track => track.kind === "video"), addTrack: (track) => list.push(track), removeTrack: (track) => list.splice(list.indexOf(track), 1) };
  };
  const startCamera = async (app) => {
    const socket = app.ws();
    socket.open();
    socket.receive({ type: "presence", hostOnline: true, controllerOnline: true, cameras: 1, cameraIds: ["me"], controllerId: "ctrl" });
    socket.receive(ready("camera"));
    app.el("#join-call").fire("click");
    for (let index = 0; index < 6; index += 1) await flush();
    return socket;
  };

  it("falls back to video without voice and says the microphone is unavailable", async () => {
    const calls = [];
    const getUserMedia = vi.fn(async (constraints) => {
      calls.push(constraints);
      if (constraints.audio) throw domError("NotAllowedError");
      return fakeStream([fakeTrack("video", "back", "environment")]);
    });
    const enumerateDevices = vi.fn(async () => [{ kind: "videoinput", deviceId: "back" }, { kind: "audioinput", deviceId: "mic" }]);
    const app = boot({ role: "camera", mediaDevices: { getUserMedia, enumerateDevices }, rtc: FakePeerConnection });
    await startCamera(app);
    expect(calls).toEqual([{ video: { facingMode: { ideal: "environment" } }, audio: true }, { video: true, audio: false }]);
    expect(app.el("#status").textContent).toBe("CAMÉRA PRÊTE");
    expect(app.el("#media-copy").textContent).toContain("Vidéo sans voix : micro indisponible (NotAllowedError).");
  });

  it("names the DOMException in the camera UI and the log, never the raw message, and tries the whole chain", async () => {
    const getUserMedia = vi.fn(async () => { throw domError("NotReadableError"); });
    const app = boot({ role: "camera", mediaDevices: { getUserMedia }, rtc: FakePeerConnection });
    await startCamera(app);
    expect(getUserMedia).toHaveBeenCalledTimes(3);
    expect(getUserMedia.mock.calls.map(([constraints]) => constraints)).toEqual([
      { video: { facingMode: { ideal: "environment" } }, audio: true },
      { video: true, audio: true },
      { video: true, audio: false },
    ]);
    expect(app.el("#status").textContent).toBe("CAMÉRA OCCUPÉE");
    expect(app.el("#media-copy").textContent).toBe("Caméra de cet appareil indisponible (NotReadableError) : caméra occupée par une autre application.");
    expect(app.el("#video-placeholder-label").textContent).toBe("CAMÉRA DE CET APPAREIL INDISPONIBLE");
    const log = app.el("#debug-log").children.map(item => item.textContent).join("\n");
    expect(log).toContain("Tentative caméra 3/3 refusée : NotReadableError");
    expect(log).not.toContain("hidden detail");
    expect(log).not.toContain("#k=");
    expect(log).not.toContain("SECRET");
  });

  it("never enumerates devices before getUserMedia and exports only counts in the safe report", async () => {
    const order = [];
    const getUserMedia = vi.fn(async () => { order.push("getUserMedia"); return fakeStream([fakeTrack("video", "back", "environment"), fakeTrack("audio", "mic")]); });
    const enumerateDevices = vi.fn(async () => { order.push("enumerateDevices"); return [{ kind: "videoinput", deviceId: "back", label: "Back camera" }, { kind: "videoinput", deviceId: "front", label: "Front camera" }, { kind: "audioinput", deviceId: "mic", label: "Mic" }]; });
    let copied;
    const app = boot({ role: "camera", mediaDevices: { getUserMedia, enumerateDevices }, clipboard: { writeText: async (text) => { copied = text; } }, rtc: FakePeerConnection });
    await startCamera(app);
    expect(order[0]).toBe("getUserMedia");
    app.el("#copy-debug-report").fire("click");
    for (let index = 0; index < 4; index += 1) await flush();
    const report = JSON.parse(copied);
    expect(report.media).toEqual({
      mediaDevicesPresent: true, getUserMediaPresent: true, secureContext: true, captureMode: "video+audio",
      lastErrorName: "none", audioErrorName: "none", flipResult: "none",
      devices: { videoinput: 2, audioinput: 1, afterCaptureAttempt: true },
    });
    for (const hidden of ["Back camera", "front", "\"mic\"", "#k=", "SECRETSECRET"]) expect(copied).not.toContain(hidden);
  });

  it("stops the current camera before opening the other one and replaces the track on the live link", async () => {
    const back = fakeTrack("video", "back", "environment");
    const front = fakeTrack("video", "front", "user");
    const calls = [];
    const getUserMedia = vi.fn(async (constraints) => {
      calls.push({ constraints, backStopped: back.stop.mock.calls.length > 0 });
      if (calls.length === 1) return fakeStream([back, fakeTrack("audio", "mic")]);
      return fakeStream([front]);
    });
    FakePeerConnection.instances = [];
    const app = boot({ role: "camera", mediaDevices: { getUserMedia, enumerateDevices: async () => [] }, rtc: FakePeerConnection });
    await startCamera(app);
    const [link] = FakePeerConnection.instances;
    expect(link.senders.map(sender => sender.track)).toContain(back);
    app.el("#flip-camera").fire("click");
    for (let index = 0; index < 6; index += 1) await flush();
    expect(calls[1]).toEqual({ constraints: { video: { facingMode: { exact: "user" } }, audio: false }, backStopped: true });
    expect(link.senders.map(sender => sender.track)).toContain(front);
    expect(link.senders.map(sender => sender.track)).not.toContain(back);
    expect(app.el("#flip-camera").disabled).toBe(false);
  });

  it("reopens the previous camera when the other one cannot be opened, and explains a single-camera device", async () => {
    const back = fakeTrack("video", "back", "environment");
    let restored;
    const getUserMedia = vi.fn(async (constraints) => {
      if (getUserMedia.mock.calls.length === 1) return fakeStream([back]);
      if (constraints.video.deviceId?.exact === "back") { restored = fakeTrack("video", "back", "environment"); return fakeStream([restored]); }
      throw domError("NotReadableError");
    });
    const enumerateDevices = vi.fn(async () => [{ kind: "videoinput", deviceId: "back" }, { kind: "videoinput", deviceId: "front" }]);
    FakePeerConnection.instances = [];
    const app = boot({ role: "camera", mediaDevices: { getUserMedia, enumerateDevices }, rtc: FakePeerConnection });
    await startCamera(app);
    app.el("#flip-camera").fire("click");
    for (let index = 0; index < 8; index += 1) await flush();
    expect(getUserMedia.mock.calls.slice(1).map(([constraints]) => constraints.video)).toEqual([
      { facingMode: { exact: "user" } }, { facingMode: { ideal: "user" } }, { deviceId: { exact: "front" } }, { deviceId: { exact: "back" } },
    ]);
    expect(FakePeerConnection.instances[0].senders.map(sender => sender.track)).toContain(restored);
    expect(app.el("#media-copy").textContent).toBe("Impossible d’ouvrir l’autre caméra (NotReadableError) : caméra occupée par une autre application. Caméra précédente rétablie.");

    // Single camera: "ideal" returns the same device and only one videoinput exists.
    const only = fakeTrack("video", "only", "environment");
    const single = vi.fn(async (constraints) => {
      if (constraints.video.facingMode?.exact) throw domError("OverconstrainedError");
      return fakeStream([fakeTrack("video", "only", "environment")]);
    });
    single.mockImplementationOnce(async () => fakeStream([only]));
    const app2 = boot({ role: "camera", mediaDevices: { getUserMedia: single, enumerateDevices: async () => [{ kind: "videoinput", deviceId: "only" }] }, rtc: FakePeerConnection });
    await startCamera(app2);
    app2.el("#flip-camera").fire("click");
    for (let index = 0; index < 8; index += 1) await flush();
    expect(app2.el("#media-copy").textContent).toBe("Cet appareil n’a qu’une caméra; la caméra actuelle est conservée.");
    expect(app2.el("#status").textContent).toBe("CAMÉRA PRÊTE");
  });
});

describe("controller media card reflects the real camera state", () => {
  const cameraPresence = (cameraIds) => ({ ...presence(true), cameras: cameraIds.length, cameraIds });
  const offer = (from) => ({ type: "signal", from, data: JSON.stringify({ kind: "offer", description: { type: "offer", sdp: "x" } }) });

  it("never says no camera while a camera is present or streaming, and says it only when none has joined", async () => {
    FakePeerConnection.instances = [];
    const app = boot({ rtc: FakePeerConnection });
    const socket = admitController(app);
    const NONE = "Aucune caméra connectée pour le moment : aucun appareil caméra n’a rejoint la session.";
    expect(app.el("#media-copy").textContent).toBe(NONE);
    socket.receive(cameraPresence(["cam-a"]));
    expect(app.el("#media-title").textContent).toBe("Caméra présente");
    expect(app.el("#media-copy").textContent).toBe("Une caméra a rejoint la session; en attente de son flux.");
    socket.receive(offer("cam-a"));
    await flush();
    FakePeerConnection.instances[0].ontrack({ streams: [{ id: "a" }] });
    await flush();
    expect(app.el("#media-title").textContent).toBe("Caméra connectée");
    expect(app.el("#media-copy").textContent).toContain("Le flux caméra est actif");
    // Later presence (host flapping, a second camera joining) keeps the copy consistent.
    socket.receive(cameraPresence(["cam-a", "cam-b"]));
    expect(app.el("#media-copy").textContent).toContain("Le flux caméra est actif");
    socket.receive(cameraPresence(["cam-b"]));
    expect(app.el("#media-title").textContent).toBe("Caméra présente");
    expect(app.el("#media-copy").textContent).not.toContain("Aucune");
    socket.receive(cameraPresence([]));
    expect(app.el("#media-copy").textContent).toBe(NONE);
    expect(app.el("#video-placeholder-label").textContent).toBe("AUCUNE CAMÉRA CONNECTÉE");
  });
});
