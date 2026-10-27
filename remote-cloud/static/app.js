const status = document.querySelector("#status");
const title = document.querySelector("#session-title");
const copy = document.querySelector("#session-copy");
const hint = document.querySelector("#participant-hint");
const stage = document.querySelector("#video-stage");
const videoGrid = document.querySelector("#video-grid");
const mediaTools = document.querySelector("#media-tools");
const pauseVideoButton = document.querySelector("#pause-video");
const stopVideoButton = document.querySelector("#stop-video");
const stopButton = document.querySelector("#emergency-stop");
const stopButtonAnchor = document.createComment("stop-button-anchor");
const videoPlaceholder = document.querySelector("#video-placeholder");
const videoPlaceholderLabel = document.querySelector("#video-placeholder-label");
const join = document.querySelector("#join-call");
const flipCameraButton = document.querySelector("#flip-camera");
const controls = document.querySelector("#remote-controls");
const standardControls = document.querySelector("#standard-controls");
const parameterControls = document.querySelector("#parameter-controls");
const liveTouchPanel = document.querySelector("#live-touch-panel");
const livePad = document.querySelector("#live-pad");
const liveValue = document.querySelector("#live-value");
// The mode picker is a fieldset of native radio chips; its "change" bubbles from the chosen radio.
const pattern = document.querySelector("#pattern");
const patternInputs = [...pattern.querySelectorAll('input[name="pattern"]')];
const modeTitle = document.querySelector("#mode-title");
const mediaTitle = document.querySelector("#media-title");
const mediaCopy = document.querySelector("#media-copy");
const cameraSharingControls = document.querySelector("#camera-sharing-controls");
const stopSharingButton = document.querySelector("#stop-sharing");
const phoneLimitsPanel = document.querySelector("#phone-limits");
const phoneLimitsValues = document.querySelector("#phone-limits-values");
const phoneLimitsNote = document.querySelector("#phone-limits-note");
// Every slider is bounded by an envelope set on the phone. Sensation is bipolar (50 = neutral):
// the phone publishes a band [sensationMin, sensationMax] around neutral; a legacy frame with
// sensationMax alone keeps the old ceiling [0, sensationMax]; a 4-key frame leaves it unbounded.
const limitInputs = [
  { input:document.querySelector("#speed"), badge:document.querySelector("#speed-limit"), output:document.querySelector("#speed-value"), name:"Vitesse", kind:"max", range:limits => limits && [0, limits.speedMax] },
  { input:document.querySelector("#sensation"), badge:document.querySelector("#sensation-limit"), output:document.querySelector("#sensation-value"), name:"Sensation", kind:"band", range:sensationBand },
  { input:document.querySelector("#minimum"), badge:document.querySelector("#minimum-limit"), output:document.querySelector("#minimum-value"), name:"Minimum", kind:"range", range:limits => limits && [limits.depthMin, limits.depthMax] },
  { input:document.querySelector("#maximum"), badge:document.querySelector("#maximum-limit"), output:document.querySelector("#maximum-value"), name:"Profondeur", kind:"range", range:limits => limits && [limits.depthMin, limits.depthMax] },
];
const speedInput = limitInputs[0].input;
const sensationInput = limitInputs[1].input;
const minimumInput = limitInputs[2].input;
const maximumInput = limitInputs[3].input;
const sensationTrack = document.querySelector("#sensation-track");
const sensationLegend = document.querySelector("#sensation-legend");
const SENSATION_NEUTRAL = 50;
// Effect of each side of neutral, as documented by StrokeEngine (firmware lib/StrokeEngine/src/pattern.h):
// [toward 0 (negative sensation), toward 100 (positive sensation)]. Other modes get a generic legend.
const SENSATION_DIRECTIONS = {
  teasingPounding:["retrait rapide", "avancée rapide"],
  halfNHalf:["retrait rapide", "avancée rapide"],
  roboStroke:["accélération douce", "mouvement robotique"],
  deeper:["rampe courte", "rampe longue"],
  stopNGo:["pauses courtes", "pauses longues"],
  insist:["vers l’arrière", "vers l’avant"],
};
const debug = {
  summary:document.querySelector("#debug-summary"),
  browser:document.querySelector("#debug-browser"),
  origin:document.querySelector("#debug-origin"),
  session:document.querySelector("#debug-session"),
  role:document.querySelector("#debug-role"),
  host:document.querySelector("#debug-host"),
  lease:document.querySelector("#debug-lease"),
  presence:document.querySelector("#debug-presence"),
  websocket:document.querySelector("#debug-websocket"),
  lastEvent:document.querySelector("#debug-last-event"),
  appEvent:document.querySelector("#debug-app-event"),
  log:document.querySelector("#debug-log"),
  copyButton:document.querySelector("#copy-debug-report"),
  copyStatus:document.querySelector("#debug-copy-status"),
};

const recentDiagnostics = [];
const MAX_RECENT_DIAGNOSTICS = 20;
const DEBUG_SOURCE_LABELS = Object.freeze({
  browser:"navigateur",
  broker:"relais",
  media:"média",
  "host app":"téléphone",
});
const APP_DIAGNOSTIC_MESSAGES = Object.freeze({
  firmware_remote_auth_required:"Le téléphone signale un échec d’autorisation distante. Ce code n’identifie pas la cause.",
  remote_lease_invalid:"Le téléphone a refusé le bail distant : rouvrez la session distante depuis l’application, puis rechargez ce lien si besoin.",
  remote_session_not_started:"La session distante n’est pas démarrée sur le téléphone : démarrez-la dans l’application OSSM.",
  remote_machine_not_ready:"Le téléphone attend que la machine soit prête (connexion, homing).",
  remote_sequence_stale:"Le téléphone a ignoré une commande périmée (ordre de séquence) : renvoyez le réglage.",
  remote_live_position_rejected:"Le téléphone a refusé la position Direct : vérifiez les maximums et le mode sur le téléphone.",
});
const BROKER_ERROR_MESSAGES = Object.freeze({
  forbidden:"Le relais a refusé l’accès ou le rôle du participant.",
  invalid_sequence:"Le relais a refusé une commande dans le mauvais ordre.",
  capacity:"La limite de participants est atteinte.",
  expired:"La session est expirée.",
  invalid_message:"Le relais a refusé un message mal formé.",
});
// Only these DOMException names are shown or exported; error messages are never surfaced.
const MEDIA_ERROR_MESSAGES = Object.freeze({
  NotAllowedError:"permission refusée par le navigateur/WebView",
  NotReadableError:"caméra occupée par une autre application",
  NotFoundError:"aucune caméra ou aucun micro trouvé par le navigateur",
  OverconstrainedError:"réglages caméra non pris en charge par cet appareil",
  SecurityError:"accès média bloqué par la sécurité du navigateur",
  AbortError:"ouverture de la caméra interrompue",
});
const LEASE_LOST_MESSAGES = Object.freeze({
  heartbeat_timeout:"Le délai de présence du participant est dépassé.",
  disconnected:"Le participant est déconnecté.",
  replaced:"La connexion du participant a été remplacée.",
});

const sessionId = location.pathname.split("/")[2];
const secret = new URLSearchParams(location.hash.slice(1)).get("k");
const role = new URLSearchParams(location.search).get("role") === "camera" ? "camera" : "controller";
const isCamera = role === "camera";
const MAX_CAMERAS = 4;
let sequence = 0;
let socket;
let heartbeat;
let localParticipantId;
let localStream;
let cameraFacing = "environment";
let hostOnline = false;
let controllerOnline = false;
let cameraCount = 0;
let presenceKnown = false;
let controllerLeaseActive = false;
let controllerLeaseLost = false;
let controlAccess = false;
// Incremented whenever motor control access is lost. A slider "change" is only sent when the
// user moved that slider (an "input" event) during the current access epoch, so nothing dragged
// before a socket, lease or phone loss is ever replayed once control comes back.
let controlEpoch = 0;
const sliderInteractionEpoch = new WeakMap();
let phoneLimits;
let admitted = false;
let reconnectTimer;
let reconnectAttempts = 0;
let sessionEnded = false;
let rejectionBeforeReady;
let liveGesture;
let liveInteractionActive = false;
let videoViewingStopped = false;
let videoPaused = false;
let captureAttempt;
let captureGeneration = 0;
let explicitCameraStop = false;
// Redacted media state for the camera UI and the exported report (names and counts only).
const mediaState = { captureMode:"not started", lastErrorName:undefined, audioErrorName:undefined, flipResult:undefined, devices:undefined };
let fullscreenFocusReturn;
// Media: one RTCPeerConnection per remote participant. The controller keeps one link per camera
// (up to the broker's 4); a camera keeps a single link to the controller.
const mediaLinks = new Map();
// Controller-side camera views: one tile per camera participant, keyed by participant id.
const cameraTiles = new Map();
let knownCameraIds = [];
let controllerPeerId;
let primaryCameraId;
let audioCameraId;
let fullscreenTileId;
let localTile;
const RECONNECT_BASE_MS = 1000;
const RECONNECT_MAX_MS = 15000;
const MAX_RECONNECT_ATTEMPTS = 30;
const SESSION_ENDED_MESSAGE = "Session expirée ou introuvable — demandez un nouveau lien au téléphone";
const transitionTimers = new WeakMap();

stopButton.before(stopButtonAnchor);

document.body.dataset.role = role;
controls.hidden = isCamera;
phoneLimitsPanel.hidden = isCamera;
join.hidden = !isCamera;
cameraSharingControls.hidden = !isCamera;
flipCameraButton.hidden = true;
stage.classList.toggle("camera-preview", isCamera);
stage.setAttribute("aria-label", isCamera ? "Aperçu caméra" : "Flux caméra distant");
mediaTools.hidden = true;
pauseVideoButton.hidden = true;
stopVideoButton.hidden = true;
stopSharingButton.hidden = true;
if (isCamera) resetCameraIdleCopy();
renderVideoStage();

function setStatus(text, tone = "") {
  status.textContent = text;
  status.className = `status-chip ${tone}`;
}

function resetCameraIdleCopy() {
  mediaTitle.textContent = "Caméra et voix désactivées";
  mediaCopy.textContent = "Démarrez seulement lorsque vous êtes prêt à autoriser la caméra et le microphone de cet appareil.";
}

// A control-access epoch also ends any in-flight slider gesture. Mode changes use the same
// boundary so a delayed trailing frame from the previous mode cannot cross into the next one.
function invalidateControlEpoch() {
  controlEpoch += 1;
  cancelSliderStreams();
}

// STOP only needs an admitted controller on an open WebSocket: the broker relays
// it without requiring a host or a fresh lease, so it must outlive other controls.
function stopAvailable() {
  return role === "controller" && admitted && socket?.readyState === WebSocket.OPEN;
}

function setControlsEnabled(enabled) {
  if (controlAccess && !enabled) invalidateControlEpoch();
  controlAccess = enabled;
  if (!enabled) cancelLiveTouch("access-lost");
  if (role === "controller") {
    controls.querySelectorAll("button, input, select").forEach(element => { element.disabled = !enabled; });
    pattern.disabled = !enabled;
    stopButton.disabled = !stopAvailable();
    syncSpeedAvailability();
  }
  syncModePresentation();
}

function syncSpeedAvailability() {
  if (role === "controller") speedInput.disabled = !controlAccess || phoneLimits?.speedMax === 0;
}

function parsePhoneLimits(event) {
  const keys = Object.keys(event);
  const required = ["type", "speedMax", "depthMin", "depthMax"];
  const optional = ["sensationMin", "sensationMax"];
  if (!required.every(key => keys.includes(key)) || !keys.every(key => required.includes(key) || optional.includes(key))) return undefined;
  const hasMin = keys.includes("sensationMin");
  const hasMax = keys.includes("sensationMax");
  const fraction = value => typeof value === "number" && Number.isFinite(value) && value >= 0 && value <= 1;
  if (!fraction(event.speedMax) || !fraction(event.depthMin) || !fraction(event.depthMax) || event.depthMin > event.depthMax) return undefined;
  if (hasMax && !fraction(event.sensationMax)) return undefined;
  if (hasMin && (!hasMax || !fraction(event.sensationMin) || event.sensationMin > event.sensationMax)) return undefined;
  const limits = { speedMax:Math.round(event.speedMax * 100), depthMin:Math.round(event.depthMin * 100), depthMax:Math.round(event.depthMax * 100) };
  if (hasMin) {
    // Round inwards so the browser never offers a value outside the phone's band.
    let low = Math.ceil(event.sensationMin * 100 - 1e-6);
    let high = Math.floor(event.sensationMax * 100 + 1e-6);
    if (low > high) low = high = Math.round((event.sensationMin + event.sensationMax) * 50);
    limits.sensationMin = low;
    limits.sensationMax = high;
  } else if (hasMax) {
    limits.sensationMax = Math.round(event.sensationMax * 100);
  }
  return limits;
}

function sensationBand(limits) {
  if (limits?.sensationMin !== undefined) return [limits.sensationMin, limits.sensationMax];
  if (limits?.sensationMax !== undefined) return [0, limits.sensationMax];
  return undefined;
}

// Slider value 0..100 shown on the bipolar scale −100…0…+100 (0 = neutral).
const signedSensation = value => Math.round((Number(value) - SENSATION_NEUTRAL) * 2);
const formatSigned = value => value === 0 ? "0" : `${value > 0 ? "+" : "−"}${Math.abs(value)}`;

function sensationBandText([low, high]) {
  const a = signedSensation(low);
  const b = signedSensation(high);
  return { intensity:Math.max(Math.abs(a), Math.abs(b)), symmetric:a === -b, low:formatSigned(a), high:formatSigned(b) };
}

function phoneLimitsText(limits) {
  const band = sensationBand(limits);
  const text = band && sensationBandText(band);
  const sensation = text ? ` · Sensation ±${text.intensity} (${text.low}…${text.high})` : "";
  return `Vitesse max ${limits.speedMax} %${sensation} · Profondeur ${limits.depthMin}–${limits.depthMax} %`;
}

function sensationValueText(value) {
  const signed = signedSensation(value);
  if (signed === 0) return "neutre";
  const directions = SENSATION_DIRECTIONS[patternKey()];
  return directions ? `${directions[signed < 0 ? 0 : 1]} ${Math.abs(signed)}` : formatSigned(signed);
}

function sensationLegendText() {
  if (patternKey() === "simpleStroke") return "Sans effet en Simple Stroke";
  const directions = SENSATION_DIRECTIONS[patternKey()];
  return directions ? `← ${directions[0]} · ${directions[1]} →` : "← vers −100 · vers +100 →";
}

// Centered presentation: value label, aria-valuetext, mode legend and the neutral marker/fill
// positions on the (possibly bounded) track. Purely visual: never sends a command.
function renderSensation() {
  const value = Number(sensationInput.value);
  limitInputs[1].output.textContent = formatSigned(signedSensation(value));
  sensationInput.setAttribute("aria-valuetext", sensationValueText(value));
  sensationLegend.textContent = sensationLegendText();
  const low = Number(sensationInput.min);
  const high = Number(sensationInput.max);
  const position = x => high > low ? Math.min(1, Math.max(0, (x - low) / (high - low))) : 0.5;
  const neutral = position(SENSATION_NEUTRAL);
  const current = position(value);
  sensationTrack.dataset.neutral = SENSATION_NEUTRAL >= low && SENSATION_NEUTRAL <= high ? "in" : "out";
  sensationTrack.style?.setProperty("--neutral-pos", String(neutral));
  sensationTrack.style?.setProperty("--fill-a", String(Math.min(neutral, current)));
  sensationTrack.style?.setProperty("--fill-b", String(Math.max(neutral, current)));
}

function renderSliderValue(entry) {
  const { input, output } = entry;
  if (input === sensationInput) return renderSensation();
  if (output) output.textContent = `${Math.round(Number(input.value))} %`;
}

// The phone's sliders set maxima (an envelope); the browser only acts inside it.
// Re-clamping updates displayed values without ever sending a motion command.
function renderPhoneLimits() {
  const known = phoneLimits !== undefined;
  phoneLimitsPanel.dataset.state = !known ? "waiting" : phoneLimits.speedMax === 0 ? "zero" : "set";
  for (const entry of limitInputs) {
    const { input, badge, name, kind, range } = entry;
    const bounds = range(phoneLimits);
    const [low, high] = bounds ?? [0, 100];
    // Rewriting identical bounds on the slider under the finger re-lays its thumb out; only
    // write what changed, widening first so the browser never sanitizes through an empty range.
    if (String(high) !== input.max || String(low) !== input.min) {
      if (high >= Number(input.max)) { input.max = String(high); input.min = String(low); }
      else { input.min = String(low); input.max = String(high); }
    }
    const current = Number(input.value);
    const clamped = Math.min(high, Math.max(low, current));
    if (clamped !== current) input.value = String(clamped);
    const describedBy = kind === "band" ? [sensationLegend.id] : [];
    if (bounds && kind === "band") {
      const band = sensationBandText(bounds);
      badge.textContent = band.symmetric ? `intensité max ±${band.intensity}` : `plage ${band.low}…${band.high}`;
      badge.hidden = false;
      input.setAttribute("aria-label", band.symmetric
        ? `${name}, intensité maximale fixée par le téléphone : ±${band.intensity}`
        : `${name}, plage fixée par le téléphone : ${band.low} à ${band.high}`);
      describedBy.unshift(badge.id);
    } else if (bounds) {
      const isMax = kind === "max";
      badge.textContent = isMax ? `max ${high} %` : `${low}–${high} %`;
      badge.hidden = false;
      input.setAttribute("aria-label", `${name}, ${isMax ? "maximum fixé" : "plage fixée"} par le téléphone : ${isMax ? `${high} %` : `${low} à ${high} %`}`);
      describedBy.unshift(badge.id);
    } else {
      badge.hidden = true;
      badge.textContent = "";
      input.removeAttribute("aria-label");
    }
    if (describedBy.length) input.setAttribute("aria-describedby", describedBy.join(" "));
    else input.removeAttribute("aria-describedby");
    renderSliderValue(entry);
  }
  // Both depth sliders share one bounded range: keep minimum <= maximum (the phone rejects an
  // inverted set_depth envelope). Display only; never sends a command.
  if (Number(minimumInput.value) > Number(maximumInput.value)) {
    maximumInput.value = minimumInput.value;
    renderSliderValue(limitInputs[3]);
  }
  phoneLimitsValues.textContent = known ? phoneLimitsText(phoneLimits) : "Maximums du téléphone : en attente";
  phoneLimitsNote.hidden = !(known && phoneLimits.speedMax === 0);
  phoneLimitsNote.textContent = known && phoneLimits.speedMax === 0 ? "Le téléphone est à 0 % : réglez un maximum sur le téléphone" : "";
  syncSpeedAvailability();
}

function markUiTransition(element) {
  if (!element) return;
  const previousTimer = transitionTimers.get(element);
  if (previousTimer !== undefined) clearTimeout(previousTimer);
  element.dataset.uiTransition = "crossfade";
  const timer = setTimeout(() => {
    if (element.dataset.uiTransition === "crossfade") delete element.dataset.uiTransition;
    transitionTimers.delete(element);
  }, 220);
  transitionTimers.set(element, timer);
}

function selectedPattern() {
  return patternInputs.find(input => input.checked) ?? patternInputs[0];
}

function patternKey() {
  return selectedPattern()?.value ?? "simpleStroke";
}

function syncModePresentation({ transition = false } = {}) {
  const liveMode = patternKey() === "streamingLive";
  modeTitle.textContent = selectedPattern()?.dataset.label ?? patternKey();
  standardControls.hidden = liveMode;
  parameterControls.hidden = liveMode;
  liveTouchPanel.hidden = !liveMode;
  const padEnabled = controlAccess && liveMode;
  livePad.setAttribute("aria-disabled", String(!padEnabled));
  livePad.tabIndex = padEnabled ? 0 : -1;
  controls.classList.toggle("live-mode", liveMode);
  if (!liveMode) cancelLiveTouch("mode-exit");
  renderSensation();
  if (transition) markUiTransition(controls);
}

function browserFamily() {
  const agent = navigator.userAgent;
  if (/; wv\)/.test(agent)) return "Android WebView";
  if (/Edg\//.test(agent)) return "Edge";
  if (/Firefox\//.test(agent)) return "Firefox";
  if (/Chrome\//.test(agent)) return "Chrome";
  if (/Safari\//.test(agent)) return "Safari";
  return "Autre navigateur";
}

function safeOriginHostname() {
  const hostname = location.hostname.toLowerCase();
  if (hostname === "localhost" || hostname.includes(":") || /^\d{1,3}(?:\.\d{1,3}){3}$/.test(hostname)) return "hôte local de test";
  return hostname.endsWith(".workers.dev") ? hostname : "origine locale ou personnalisée";
}

function websocketState() {
  if (!socket) return "non ouvert";
  return ["connexion", "ouvert", "fermeture", "fermé"][socket.readyState] ?? "inconnu";
}

function debugSummaryText(controlsReady) {
  if (!sessionId || !secret) return "Lien de session incomplet";
  if (sessionEnded) return "Session expirée ou introuvable";
  if (reconnectTimer !== undefined) return "Reconnexion en cours";
  if (controllerLeaseLost) return "Bail du contrôleur perdu";
  if (isCamera) return localStream ? "Caméra prête" : socket?.readyState === WebSocket.OPEN ? "Caméra connectée" : "Connexion caméra";
  if (hostOnline && controlsReady) return "Téléphone prêt";
  if (presenceKnown && !hostOnline) return "En attente du téléphone";
  return socket?.readyState === WebSocket.OPEN ? "En attente de la session" : "Connexion";
}

function renderDebugState() {
  const controlsReady = hostOnline && controllerLeaseActive && !controllerLeaseLost;
  debug.browser.textContent = `${browserFamily()} · ${window.isSecureContext ? "contexte sécurisé" : "non sécurisé"}`;
  debug.origin.textContent = safeOriginHostname();
  debug.session.textContent = `${sessionId ? "présente (ID masqué)" : "manquante"} · clé ${secret ? "présente (valeur masquée)" : "manquante"}`;
  debug.role.textContent = role === "camera" ? "caméra" : "contrôleur";
  debug.host.textContent = presenceKnown ? `${hostOnline ? "oui" : "non"} · dernière présence` : "inconnu";
  debug.lease.textContent = role !== "controller" ? "sans objet" : controllerLeaseLost ? "perdu" : controllerLeaseActive ? "actif" : "en attente";
  debug.presence.textContent = presenceKnown ? `contrôleur ${controllerOnline ? "oui" : "non"} · caméras ${cameraCount}` : "inconnue";
  debug.websocket.textContent = websocketState();
  debug.summary.textContent = debugSummaryText(controlsReady);
}

function addDebugEntry(source, severity, code, message) {
  const entry = { at:new Date().toISOString(), source, severity, code, message };
  recentDiagnostics.push(entry);
  if (recentDiagnostics.length > MAX_RECENT_DIAGNOSTICS) recentDiagnostics.shift();
  const item = document.createElement("li");
  item.textContent = `${formatDiagnosticTime(entry.at)} · ${DEBUG_SOURCE_LABELS[source] ?? source} · ${entry.message}`;
  debug.log.append(item);
  while (debug.log.children.length > MAX_RECENT_DIAGNOSTICS) debug.log.firstElementChild?.remove();
  debug.lastEvent.textContent = `${formatDiagnosticTime(entry.at)} · ${DEBUG_SOURCE_LABELS[source] ?? source} · ${entry.message}`;
  renderDebugState();
}

function formatDiagnosticTime(isoTimestamp) {
  const timestamp = new Date(isoTimestamp);
  if (Number.isNaN(timestamp.getTime())) return "heure indisponible";
  try {
    return new Intl.DateTimeFormat("fr-CA", {
      dateStyle:"short",
      timeStyle:"medium",
    }).format(timestamp);
  } catch {
    return timestamp.toLocaleString("fr-CA");
  }
}

function safeDiagnosticsReport() {
  const latestAppEvent = [...recentDiagnostics].reverse().find(entry => entry.source === "host app" && entry.severity === "warning" && Object.prototype.hasOwnProperty.call(APP_DIAGNOSTIC_MESSAGES, entry.code));
  const last = recentDiagnostics[recentDiagnostics.length - 1];
  return {
    schemaVersion:1,
    capturedAt:new Date().toISOString(),
    browser:browserFamily(),
    secureContext:Boolean(window.isSecureContext),
    originHostname:safeOriginHostname(),
    role,
    session:{ present:Boolean(sessionId), id:"redacted", keyPresent:Boolean(secret), key:"redacted" },
    hostPresence:presenceKnown ? hostOnline : "unknown",
    controllerLease:role === "controller" ? controllerLeaseLost ? "lost" : controllerLeaseActive ? "active" : "pending" : "not applicable",
    presence:presenceKnown ? { controllerOnline, cameras:cameraCount } : "unknown",
    cameraViews:isCamera ? "not applicable" : cameraTiles.size,
    media:{
      mediaDevicesPresent:Boolean(navigator.mediaDevices),
      getUserMediaPresent:Boolean(navigator.mediaDevices?.getUserMedia),
      secureContext:Boolean(window.isSecureContext),
      captureMode:isCamera ? mediaState.captureMode : "not applicable",
      lastErrorName:mediaState.lastErrorName ?? "none",
      audioErrorName:mediaState.audioErrorName ?? "none",
      flipResult:mediaState.flipResult ?? "none",
      devices:mediaState.devices ?? "not enumerated",
    },
    websocket:websocketState(),
    phoneLimits:phoneLimits ? { speedMaxPercent:phoneLimits.speedMax, sensationMinPercent:phoneLimits.sensationMin ?? (phoneLimits.sensationMax === undefined ? "unbounded" : 0), sensationMaxPercent:phoneLimits.sensationMax ?? "unbounded", depthMinPercent:phoneLimits.depthMin, depthMaxPercent:phoneLimits.depthMax } : "pending",
    appDebug:latestAppEvent ? { severity:latestAppEvent.severity, code:latestAppEvent.code, message:latestAppEvent.message } : "no host diagnostic received",
    lastEvent:last ? { at:last.at, source:last.source, severity:last.severity, code:last.code, message:last.message } : "none",
    recentEvents:recentDiagnostics.map(({ at, source, severity, code, message }) => ({ at, source, severity, code, message })),
  };
}

async function exportSafeDiagnostics() {
  await refreshDeviceCounts();
  const report = JSON.stringify(safeDiagnosticsReport(), null, 2);
  try {
    if (!navigator.clipboard?.writeText) throw new Error("clipboard unavailable");
    await navigator.clipboard.writeText(report);
    debug.copyStatus.textContent = "Rapport sûr copié.";
  } catch {
    try {
      const downloadUrl = URL.createObjectURL(new Blob([report], { type:"application/json" }));
      const link = document.createElement("a");
      link.href = downloadUrl;
      link.download = "ossm-remote-diagnostics.json";
      link.click();
      setTimeout(() => URL.revokeObjectURL(downloadUrl), 1000);
      debug.copyStatus.textContent = "Presse-papiers indisponible; rapport sûr téléchargé.";
    } catch {
      debug.copyStatus.textContent = "Copie et téléchargement indisponibles dans ce navigateur.";
    }
  }
}

function send(message, { log = true } = {}) {
  if (socket?.readyState === WebSocket.OPEN) {
    socket.send(JSON.stringify(message));
    if (log && (message.type === "command" || message.type === "emergency_stop")) addDebugEntry("browser", "info", "control_frame_sent", "Commande envoyée; contenu masqué.");
    return true;
  }
  if (message.type === "command" || message.type === "emergency_stop") addDebugEntry("browser", "warning", "control_frame_not_sent", "Commande non envoyée car le WebSocket n’est pas ouvert.");
  return false;
}

function command(value, options) {
  return send({ type:"command", seq:++sequence, command:JSON.stringify(value) }, options);
}

// Motion commands only leave on a fresh user action while control access is held.
function motionCommand(value) {
  if (!controlAccess) return;
  command(value);
}

// Real-time sliders: while a finger moves, each slider sends at most one frame per
// SLIDER_THROTTLE_MS (leading + trailing); releasing it flushes the final value when it differs
// from the last one sent. Nothing leaves without control access or without a user "input" in the
// current access epoch, so a value is never replayed after a socket, lease or phone loss.
const SLIDER_THROTTLE_MS = 80;
const sliderStreams = new Map();

function sliderValueCommand(input) {
  if (input === speedInput) return { type:"set_speed", value:+speedInput.value / 100 };
  if (input === sensationInput) return { type:"set_sensation", value:+sensationInput.value / 100 };
  return depthCommand();
}

function sliderMayStream(input) {
  return controlAccess && sliderInteractionEpoch.get(input) === controlEpoch;
}

// Both depth sliders send the same set_depth envelope, so they share one stream (throttle + last value).
const sliderChannel = input => input === speedInput ? "speed" : input === sensationInput ? "sensation" : "depth";

function sliderStream(input) {
  const channel = sliderChannel(input);
  let stream = sliderStreams.get(channel);
  if (!stream || stream.epoch !== controlEpoch) {
    if (stream?.timer !== undefined) clearTimeout(stream.timer);
    stream = { epoch:controlEpoch, lastSent:undefined, timer:undefined, pending:false, frames:0 };
    sliderStreams.set(channel, stream);
  }
  return stream;
}

function emitSliderValue(input, stream) {
  if (!sliderMayStream(input) || stream.epoch !== controlEpoch) return;
  const value = sliderValueCommand(input);
  const key = JSON.stringify(value);
  if (key === stream.lastSent) return;
  if (command(value, { log:false })) {
    stream.lastSent = key;
    stream.frames += 1;
  }
}

function startSliderCooldown(input, stream) {
  stream.timer = setTimeout(() => {
    stream.timer = undefined;
    if (!stream.pending || stream.epoch !== controlEpoch) return;
    stream.pending = false;
    emitSliderValue(stream.input ?? input, stream);
    startSliderCooldown(stream.input ?? input, stream);
  }, SLIDER_THROTTLE_MS);
}

function streamSlider(input) {
  if (!sliderMayStream(input)) return;
  const stream = sliderStream(input);
  if (stream.timer !== undefined) {
    stream.pending = true;
    stream.input = input;
    return;
  }
  emitSliderValue(input, stream);
  startSliderCooldown(input, stream);
}

function releaseSlider(input) {
  const channel = sliderChannel(input);
  const stream = sliderStreams.get(channel);
  if (stream) {
    if (stream.timer !== undefined) clearTimeout(stream.timer);
    stream.timer = undefined;
    stream.pending = false;
  }
  if (sliderMayStream(input)) {
    const current = sliderStream(input);
    emitSliderValue(input, current);
    if (current.frames > 0) addDebugEntry("browser", "info", "slider_frames_sent", `Réglage envoyé en continu (${current.frames} trame${current.frames > 1 ? "s" : ""}); contenu masqué.`);
    current.frames = 0;
  }
  sliderInteractionEpoch.delete(input);
}

// Pointer cancellation is not a successful release: drop the trailing value and let the caller's
// safety path stop motion. A subsequent change/pointerup from the cancelled gesture is ignored.
function cancelSlider(input) {
  const channel = sliderChannel(input);
  const stream = sliderStreams.get(channel);
  if (stream?.timer !== undefined) clearTimeout(stream.timer);
  sliderStreams.delete(channel);
  sliderInteractionEpoch.delete(input);
}

function cancelSliderStreams() {
  for (const stream of sliderStreams.values()) if (stream.timer !== undefined) clearTimeout(stream.timer);
  sliderStreams.clear();
}

function connect() {
  clearTimeout(reconnectTimer);
  reconnectTimer = undefined;
  if (!sessionId || !secret) {
    setStatus("SANS CLÉ", "lost");
    title.textContent = "Clé de session requise";
    copy.textContent = "Utilisez le lien de session complet partagé par l’application OSSM.";
    addDebugEntry("browser", "warning", "session_information_missing", "Le lien de session ne contient pas les informations requises; la clé reste masquée.");
    return;
  }
  const protocol = location.protocol === "https:" ? "wss:" : "ws:";
  const current = new WebSocket(`${protocol}//${location.host}/api/session/${sessionId}/ws`);
  socket = current;
  admitted = false;
  rejectionBeforeReady = undefined;
  addDebugEntry("browser", "info", reconnectAttempts > 0 ? "websocket_reconnecting" : "websocket_connecting", reconnectAttempts > 0 ? "Nouvelle tentative de connexion WebSocket." : "Ouverture de la connexion WebSocket.");
  current.addEventListener("open", () => {
    if (socket !== current) return;
    addDebugEntry("browser", "info", "websocket_open", "WebSocket ouvert.");
    send({ type:"hello", role, secret });
  });
  current.addEventListener("message", ({ data }) => { if (socket === current) receive(data); });
  current.addEventListener("error", () => { if (socket === current) addDebugEntry("browser", "warning", "websocket_error", "Erreur WebSocket; détails omis."); });
  current.addEventListener("close", () => { if (socket === current) handleSocketClosed(current); });
}

function handleSocketClosed(closed) {
  const wasAdmitted = admitted;
  const rejection = rejectionBeforeReady;
  clearInterval(heartbeat);
  admitted = false;
  hostOnline = false;
  controllerOnline = false;
  cameraCount = 0;
  presenceKnown = false;
  controllerLeaseActive = false;
  controllerLeaseLost = false;
  localParticipantId = undefined;
  phoneLimits = undefined;
  renderPhoneLimits();
  setControlsEnabled(false);
  stopMedia();
  addDebugEntry("browser", "warning", "websocket_closed", "WebSocket fermé; les détails sont omis.");
  if (sessionEnded) return;
  if (rejection === "forbidden") {
    sessionEnded = true;
    setStatus("ACCÈS REFUSÉ", "lost");
    title.textContent = "Clé de session refusée";
    copy.textContent = "La clé de ce lien n’est pas acceptée — demandez un nouveau lien au téléphone";
    addDebugEntry("broker", "warning", "session_key_refused", "Le relais a refusé la clé de session; la clé reste masquée.");
    renderDebugState();
    return;
  }
  if (wasAdmitted) {
    scheduleReconnect(rejection);
    return;
  }
  // The socket never became ready: find out why over plain HTTP, without the key.
  setStatus("VÉRIFICATION", "degraded");
  checkSessionStatus().then(state => {
    if (socket !== closed || sessionEnded || reconnectTimer !== undefined) return;
    if (state === "expired") markSessionEnded();
    else scheduleReconnect(rejection);
  });
}

async function checkSessionStatus() {
  try {
    const response = await fetch(`/api/session/${encodeURIComponent(sessionId)}/status`, { method:"GET", cache:"no-store", credentials:"omit" });
    if (response.status === 410) return "expired";
    return response.ok ? "active" : "unknown";
  } catch {
    return "unknown";
  }
}

function markSessionEnded() {
  sessionEnded = true;
  clearTimeout(reconnectTimer);
  reconnectTimer = undefined;
  cancelLiveTouch("session-ended");
  setStatus("SESSION EXPIRÉE", "lost");
  title.textContent = "Session indisponible";
  copy.textContent = SESSION_ENDED_MESSAGE;
  addDebugEntry("broker", "warning", "session_expired", SESSION_ENDED_MESSAGE);
}

function scheduleReconnect(rejection) {
  if (sessionEnded || reconnectTimer !== undefined) return;
  if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
    setStatus("HORS LIGNE", "lost");
    title.textContent = "Connexion perdue";
    copy.textContent = "La reconnexion automatique est interrompue. Rechargez cette page ou demandez un nouveau lien.";
    addDebugEntry("browser", "warning", "websocket_reconnect_stopped", "Reconnexion automatique interrompue après plusieurs tentatives.");
    return;
  }
  const delay = Math.min(RECONNECT_MAX_MS, RECONNECT_BASE_MS * 2 ** reconnectAttempts);
  reconnectAttempts += 1;
  const seconds = Math.ceil(delay / 1000);
  setStatus("RECONNEXION", "degraded");
  title.textContent = "Connexion perdue";
  copy.textContent = rejection === "capacity"
    ? `Ce rôle est déjà occupé par un autre appareil. Nouvelle tentative dans ${seconds} s.`
    : `Nouvelle tentative dans ${seconds} s. Les commandes de mouvement restent bloquées jusqu’au retour du relais.`;
  reconnectTimer = setTimeout(() => {
    reconnectTimer = undefined;
    connect();
  }, delay);
  addDebugEntry("browser", "warning", "websocket_reconnect_scheduled", `Reconnexion automatique prévue dans ${seconds} s (tentative ${reconnectAttempts}).`);
}

function receive(data) {
  let event;
  try { event = JSON.parse(data); } catch { addDebugEntry("broker", "warning", "invalid_event", "Événement illisible ignoré; contenu masqué."); return; }
  if (!event || typeof event !== "object" || Array.isArray(event)) { addDebugEntry("broker", "warning", "invalid_event", "Événement illisible ignoré; contenu masqué."); return; }
  if (event.type === "ready") {
    admitted = true;
    reconnectAttempts = 0;
    controllerLeaseActive = role === "controller";
    controllerLeaseLost = false;
    localParticipantId = typeof event.participantId === "string" ? event.participantId : undefined;
    const controlsReady = role === "controller" && hostOnline && controllerLeaseActive;
    setStatus(isCamera ? "CAMÉRA CONNECTÉE" : controlsReady ? "CONTRÔLE ACTIF" : "EN ATTENTE DU TÉLÉPHONE", controlsReady || isCamera ? "" : "degraded");
    title.textContent = isCamera ? "Participant caméra et voix" : controlsReady ? "Contrôle à distance actif" : "En attente du téléphone";
    if (role === "controller") copy.textContent = controlsReady ? "Le téléphone OSSM est connecté et prêt au contrôle." : "Ouvrez ce lien sur le téléphone OSSM avant d’envoyer une commande.";
    else copy.textContent = "Cet appareil partage seulement sa caméra et sa voix; il ne peut pas commander la machine.";
    setControlsEnabled(controlsReady);
    clearInterval(heartbeat);
    heartbeat = setInterval(() => send({ type:"heartbeat" }), 500);
    addDebugEntry("broker", "info", "ready", "Le relais a accordé l’accès à cette session.");
    return;
  }
  if (event.type === "presence") {
    handlePresence(event);
    return;
  }
  if (event.type === "lease_lost") {
    const selfLeaseLost = typeof event.participantId === "string" && event.participantId === localParticipantId;
    const lostId = typeof event.participantId === "string" ? event.participantId : undefined;
    const remoteMediaLost = lostId !== undefined && (mediaLinks.has(lostId) || cameraTiles.has(lostId) || knownCameraIds.includes(lostId) || (isCamera && lostId === controllerPeerId));
    if (remoteMediaLost) {
      closeMediaLink(lostId);
      if (isCamera && lostId === controllerPeerId) controllerPeerId = undefined;
      addDebugEntry("media", "warning", "camera_peer_lost", isCamera ? "Le contrôleur s’est déconnecté; en attente de son retour." : "Une caméra s’est déconnectée; les autres vues restent actives.");
    }
    if (selfLeaseLost) {
      setControlsEnabled(false);
      controllerLeaseActive = false;
      controllerLeaseLost = true;
      title.textContent = isCamera ? "Présence caméra perdue" : "Bail du contrôleur perdu";
      copy.textContent = isCamera ? "Le relais a retiré cette caméra de la session; reconnexion automatique en cours." : "Le bail du contrôleur est terminé. Rechargez cette page pour rejoindre la session.";
    } else if (role === "controller" && remoteMediaLost) {
      const controlsReady = hostOnline && controllerLeaseActive && !controllerLeaseLost;
      setControlsEnabled(controlsReady);
      setStatus(controlsReady ? "CONTRÔLE ACTIF" : "EN ATTENTE DU TÉLÉPHONE", controlsReady ? "" : "degraded");
      title.textContent = controlsReady ? "Contrôle à distance actif" : "En attente du téléphone";
      copy.textContent = controlsReady
        ? "Le téléphone OSSM est connecté et prêt au contrôle. La caméra peut rejoindre à nouveau."
        : "Ouvrez ce lien sur le téléphone OSSM avant d’envoyer une commande.";
    } else if (isCamera && remoteMediaLost) {
      setStatus("CAMÉRA CONNECTÉE");
    }
    // A camera page is not affected by other participants leaving; only its own lease matters.
    if (selfLeaseLost || (!remoteMediaLost && !isCamera)) setStatus("ACCÈS PERDU", "lost");
    const reason = Object.prototype.hasOwnProperty.call(LEASE_LOST_MESSAGES, event.reason) ? LEASE_LOST_MESSAGES[event.reason] : "L’accès du participant est terminé.";
    addDebugEntry("broker", "warning", "lease_lost", reason);
    return;
  }
  if (event.type === "diagnostic") {
    const exactShape = Object.keys(event).length === 3 && ["type", "severity", "code"].every(key => Object.prototype.hasOwnProperty.call(event, key));
    if (!exactShape || event.severity !== "warning" || !Object.prototype.hasOwnProperty.call(APP_DIAGNOSTIC_MESSAGES, event.code)) {
      addDebugEntry("host app", "warning", "diagnostic_ignored", "Diagnostic du téléphone non reconnu et ignoré.");
      return;
    }
    const message = APP_DIAGNOSTIC_MESSAGES[event.code];
    debug.appEvent.textContent = `avertissement · ${event.code} · ${message}`;
    addDebugEntry("host app", "warning", event.code, message);
    return;
  }
  if (event.type === "limits") {
    if (role !== "controller") return;
    const next = parsePhoneLimits(event);
    if (!next) {
      addDebugEntry("broker", "warning", "limits_ignored", "Maximums du téléphone illisibles; ignorés.");
      return;
    }
    phoneLimits = next;
    renderPhoneLimits();
    addDebugEntry("broker", "info", "limits_updated", `Maximums du téléphone reçus · ${phoneLimitsText(next).toLowerCase()}.`);
    return;
  }
  if (event.type === "signal") {
    addDebugEntry("media", "info", "media_signal_received", "Signal média reçu; contenu masqué.");
    handleSignal(event).catch(reportMediaFailure);
    return;
  }
  if (event.type === "error") {
    if (event.code === "expired") {
      markSessionEnded();
      return;
    }
    if (!admitted && typeof event.code === "string") rejectionBeforeReady = event.code;
    const knownError = Object.prototype.hasOwnProperty.call(BROKER_ERROR_MESSAGES, event.code);
    const code = knownError ? event.code : "broker_error";
    const message = knownError ? BROKER_ERROR_MESSAGES[event.code] : "Le relais a signalé une erreur; les détails non reconnus sont masqués.";
    setStatus(knownError ? event.code.toUpperCase() : "ERREUR DU RELAIS", "degraded");
    addDebugEntry("broker", "warning", code, message);
  }
}

function handlePresence(event) {
  presenceKnown = true;
  hostOnline = Boolean(event.hostOnline);
  controllerOnline = Boolean(event.controllerOnline);
  const count = Number(event.cameras);
  cameraCount = Number.isInteger(count) ? Math.max(0, Math.min(MAX_CAMERAS, count)) : 0;
  const cameraIds = Array.isArray(event.cameraIds) ? event.cameraIds.filter(value => typeof value === "string").slice(0, MAX_CAMERAS) : [];
  if (isCamera) setControllerPeer(typeof event.controllerId === "string" ? event.controllerId : undefined);
  else syncCameraPeers(cameraIds);
  if (!isCamera) renderVideoStage();
  hint.textContent = `Téléphone : ${hostOnline ? "oui" : "non"} · Contrôleur : ${controllerOnline ? "oui" : "non"} · Caméras : ${cameraCount}`;
  if (role === "controller") {
    const controlsReady = hostOnline && controllerLeaseActive && !controllerLeaseLost;
    setControlsEnabled(controlsReady);
    setStatus(controllerLeaseLost ? "ACCÈS PERDU" : controlsReady ? "CONTRÔLE ACTIF" : "EN ATTENTE DU TÉLÉPHONE", controllerLeaseLost ? "lost" : controlsReady ? "" : "degraded");
    title.textContent = controllerLeaseLost ? "Bail du contrôleur perdu" : controlsReady ? "Contrôle à distance actif" : "En attente du téléphone";
    copy.textContent = controllerLeaseLost ? "Le bail du contrôleur est terminé. Rechargez cette page pour rejoindre la session." : controlsReady ? "Le téléphone OSSM est connecté et prêt au contrôle." : "Ouvrez ce lien sur le téléphone OSSM avant d’envoyer une commande.";
  }
  if (isCamera && localStream && controllerPeerId && !explicitCameraStop) startMedia(false).catch(reportMediaFailure);
  addDebugEntry("broker", "info", "presence_updated", `Présence · téléphone ${hostOnline ? "oui" : "non"} · contrôleur ${controllerOnline ? "oui" : "non"} · caméras ${cameraCount}.`);
}

// Camera role: a new controller participant id means a fresh link (the old one is closed).
function setControllerPeer(nextPeerId) {
  if (nextPeerId === controllerPeerId) return;
  if (controllerPeerId) closeMediaLink(controllerPeerId);
  controllerPeerId = nextPeerId;
}

// Controller role: keep one link and one view per camera still present; close only the ones that left.
function syncCameraPeers(cameraIds) {
  knownCameraIds = cameraIds;
  for (const peerId of [...new Set([...mediaLinks.keys(), ...cameraTiles.keys()])]) {
    if (!cameraIds.includes(peerId)) closeMediaLink(peerId);
  }
  renderVideoStage();
}

/* ---------- Media views ---------- */

function cameraLabel(number) {
  return `Caméra ${number}`;
}

function nextCameraNumber() {
  const used = new Set([...cameraTiles.values()].map(tile => tile.number));
  for (let number = 1; number <= MAX_CAMERAS; number += 1) if (!used.has(number)) return number;
  return cameraTiles.size + 1;
}

function createTile(key, label, { local = false } = {}) {
  const figure = document.createElement("figure");
  figure.className = local ? "camera-tile local-preview" : "camera-tile";
  figure.dataset.peer = key;
  const element = document.createElement("video");
  element.autoplay = true;
  element.muted = true;
  element.playsInline = true;
  element.setAttribute("autoplay", "");
  element.setAttribute("muted", "");
  element.setAttribute("playsinline", "");
  element.setAttribute("aria-label", local ? "Aperçu local de votre caméra" : `Vidéo en direct · ${label}`);
  const caption = document.createElement("figcaption");
  caption.className = "tile-label";
  caption.textContent = label;
  const tools = document.createElement("div");
  tools.className = "tile-tools";
  const playButton = tileButton("LIRE", `Lire la vidéo · ${label}`);
  playButton.hidden = true;
  const audioButton = tileButton("Activer le son", `Activer le son · ${label}`);
  audioButton.setAttribute("aria-pressed", "false");
  audioButton.hidden = local;
  const fullButton = tileButton("Plein écran", `Plein écran · ${label}`);
  fullButton.setAttribute("aria-pressed", "false");
  const swapButton = document.createElement("button");
  swapButton.type = "button";
  swapButton.className = "tile-swap";
  swapButton.setAttribute("aria-label", `Afficher ${label} en grand`);
  swapButton.hidden = true;
  tools.append(playButton);
  tools.append(audioButton);
  tools.append(fullButton);
  figure.append(element);
  figure.append(swapButton);
  figure.append(caption);
  figure.append(tools);
  const tile = { key, label, figure, video:element, caption, playButton, audioButton, fullButton, swapButton, local };
  playButton.addEventListener("click", () => playTile(tile));
  element.addEventListener("click", () => { if (playButton.hidden === false) playTile(tile); });
  audioButton.addEventListener("click", () => setAudioCamera(audioCameraId === key ? undefined : key));
  fullButton.addEventListener("click", () => toggleTileFullscreen(key));
  swapButton.addEventListener("click", () => setPrimaryCamera(key));
  videoGrid.append(figure);
  return tile;
}

function tileButton(text, label) {
  const button = document.createElement("button");
  button.type = "button";
  button.className = "icon-button media-button";
  button.textContent = text;
  button.setAttribute("aria-label", label);
  return button;
}

function ensureCameraTile(peerId) {
  let tile = cameraTiles.get(peerId);
  if (tile) return tile;
  const number = nextCameraNumber();
  tile = createTile(peerId, cameraLabel(number));
  tile.number = number;
  cameraTiles.set(peerId, tile);
  if (!primaryCameraId || !cameraTiles.has(primaryCameraId)) primaryCameraId = peerId;
  return tile;
}

function removeCameraTile(peerId) {
  const tile = cameraTiles.get(peerId);
  if (!tile) return;
  if (fullscreenTileId === peerId) exitFullscreen();
  tile.video.srcObject = null;
  tile.figure.remove();
  cameraTiles.delete(peerId);
  if (audioCameraId === peerId) audioCameraId = undefined;
  if (primaryCameraId === peerId) primaryCameraId = cameraTiles.keys().next().value;
}

function playTile(tile) {
  if (!tile.video.srcObject) return;
  tile.video.muted = tile.local || audioCameraId !== tile.key;
  tile.playButton.disabled = true;
  Promise.resolve(tile.video.play()).then(() => {
    tile.playButton.hidden = true;
    tile.playButton.disabled = false;
    if (!isCamera) setStatus("MÉDIA CONNECTÉ", "on");
    renderVideoStage();
  }).catch(() => {
    tile.playButton.hidden = false;
    tile.playButton.disabled = false;
    if (!isCamera) setStatus("TOUCHEZ POUR LIRE", "degraded");
    addDebugEntry("media", "warning", "playback_blocked", "Le navigateur a bloqué la lecture; touchez la vidéo pour continuer.");
    renderVideoStage();
  });
}

// Audio from one view at a time: choosing a view mutes every other one.
function setAudioCamera(peerId) {
  audioCameraId = peerId && cameraTiles.has(peerId) ? peerId : undefined;
  for (const tile of cameraTiles.values()) {
    const on = tile.key === audioCameraId;
    tile.video.muted = !on;
    if (on && tile.video.paused !== false) playTile(tile);
  }
  renderVideoStage();
}

function setPrimaryCamera(peerId) {
  if (!cameraTiles.has(peerId)) return;
  primaryCameraId = peerId;
  renderVideoStage();
  markUiTransition(stage);
}

function attachRemoteStream(peerId, stream) {
  if (isCamera || videoViewingStopped) return;
  const tile = ensureCameraTile(peerId);
  if (tile.video.srcObject !== stream) tile.video.srcObject = stream;
  tile.video.muted = audioCameraId !== peerId;
  renderVideoStage();
  if (!videoPaused) playTile(tile);
}

function renderVideoStage() {
  const tiles = isCamera ? (localTile ? [localTile] : []) : [...cameraTiles.values()];
  stage.dataset.cameras = String(tiles.length);
  videoPlaceholder.hidden = tiles.length > 0;
  if (!isCamera) {
    if (primaryCameraId && !cameraTiles.has(primaryCameraId)) primaryCameraId = cameraTiles.keys().next().value;
    videoPlaceholderLabel.textContent = videoViewingStopped ? "VIDÉO FERMÉE" : Math.max(knownCameraIds.length, cameraCount) > 0 ? "CONNEXION DE LA CAMÉRA…" : "AUCUNE CAMÉRA CONNECTÉE";
    for (const tile of cameraTiles.values()) {
      const primary = tile.key === primaryCameraId;
      tile.figure.classList.toggle("primary", primary);
      tile.figure.classList.toggle("thumb", !primary);
      tile.swapButton.hidden = primary;
      const audioOn = tile.key === audioCameraId;
      tile.audioButton.textContent = audioOn ? "Couper le son" : "Activer le son";
      tile.audioButton.setAttribute("aria-label", `${audioOn ? "Couper le son" : "Activer le son"} · ${tile.label}`);
      tile.audioButton.setAttribute("aria-pressed", String(audioOn));
      const full = fullscreenTileId === tile.key;
      tile.fullButton.textContent = full ? "Quitter" : "Plein écran";
      tile.fullButton.setAttribute("aria-label", `${full ? "Quitter le plein écran" : "Plein écran"} · ${tile.label}`);
      tile.fullButton.setAttribute("aria-pressed", String(full));
      tile.figure.classList.toggle("focused", full);
    }
    if (!videoViewingStopped) renderControllerMediaCard();
  } else {
    videoPlaceholderLabel.textContent = !localTile && mediaState.captureMode === "failed" ? "CAMÉRA DE CET APPAREIL INDISPONIBLE" : "APERÇU CAMÉRA EN ATTENTE";
  }
  syncMediaControls();
}

// Controller page: the card always reflects the real state (views, then broker presence); "no
// camera" is only said when no camera participant has joined the session.
function renderControllerMediaCard() {
  const present = Math.max(knownCameraIds.length, cameraCount);
  if (cameraTiles.size > 0) {
    mediaTitle.textContent = cameraTiles.size > 1 ? `${cameraTiles.size} caméras connectées` : "Caméra connectée";
    mediaCopy.textContent = "Le flux caméra est actif. Touchez la vidéo si la lecture est bloquée.";
  } else if (present > 0) {
    mediaTitle.textContent = present > 1 ? `${present} caméras présentes` : "Caméra présente";
    mediaCopy.textContent = "Une caméra a rejoint la session; en attente de son flux.";
  } else {
    mediaTitle.textContent = "Caméras";
    mediaCopy.textContent = "Aucune caméra connectée pour le moment : aucun appareil caméra n’a rejoint la session.";
  }
}

function syncMediaControls() {
  const hasRemoteStream = !isCamera && cameraTiles.size > 0;
  const canReopen = !isCamera && videoViewingStopped && knownCameraIds.length > 0;
  const hasMediaView = hasRemoteStream || canReopen;
  mediaTools.hidden = !hasMediaView;
  pauseVideoButton.hidden = !hasRemoteStream || videoViewingStopped;
  stopVideoButton.hidden = !hasMediaView;
  pauseVideoButton.textContent = videoPaused ? "Lire la vidéo" : "Pause vidéo";
  pauseVideoButton.setAttribute("aria-label", pauseVideoButton.textContent);
  stopVideoButton.textContent = videoViewingStopped ? "Reprendre la vidéo" : "Fermer la vidéo";
  stopVideoButton.setAttribute("aria-label", stopVideoButton.textContent);
  stopVideoButton.setAttribute("aria-pressed", String(videoViewingStopped));
}

function toggleVideoPlayback() {
  if (isCamera || cameraTiles.size === 0 || videoViewingStopped) return;
  videoPaused = !videoPaused;
  for (const tile of cameraTiles.values()) {
    if (videoPaused) tile.video.pause();
    else playTile(tile);
  }
  syncMediaControls();
}

/* ---------- Fullscreen (one view at a time) ---------- */

function toggleTileFullscreen(key) {
  if (fullscreenTileId === key) exitFullscreen();
  else enterFullscreen(key);
}

function enterFullscreen(key) {
  fullscreenTileId = key;
  stage.dataset.focus = key;
  stage.classList.add("full");
  setVideoFullscreen(true);
  renderVideoStage();
  tileFor(key)?.fullButton.focus({ preventScroll:true });
}

function exitFullscreen() {
  if (fullscreenTileId === undefined && !stage.classList.contains("full")) return;
  const key = fullscreenTileId;
  fullscreenTileId = undefined;
  delete stage.dataset.focus;
  stage.classList.remove("full");
  setVideoFullscreen(false);
  renderVideoStage();
  return key;
}

function tileFor(key) {
  return isCamera ? (localTile?.key === key ? localTile : undefined) : cameraTiles.get(key);
}

function setVideoFullscreen(active) {
  const wasActive = document.body.classList.contains("video-fullscreen");
  document.body.classList.toggle("video-fullscreen", active);
  if (active && !wasActive) {
    fullscreenFocusReturn = document.activeElement instanceof HTMLElement ? document.activeElement : undefined;
    if (!isCamera) document.body.append(stopButton);
  } else if (!active && wasActive) {
    if (!isCamera && stopButtonAnchor.parentNode) stopButtonAnchor.after(stopButton);
    const focusTarget = fullscreenFocusReturn;
    fullscreenFocusReturn = undefined;
    if (focusTarget && focusTarget.isConnected && focusTarget !== stopButton && !focusTarget.hidden) focusTarget.focus({ preventScroll:true });
  }
  markUiTransition(stage);
}

function fullscreenFocusableElements() {
  const tile = fullscreenTileId === undefined ? undefined : tileFor(fullscreenTileId);
  const tileControls = tile ? [tile.playButton, tile.audioButton, tile.fullButton] : [];
  return [...tileControls, pauseVideoButton, stopVideoButton, stopButton]
    .filter(element => element && !element.hidden && !element.disabled && element.getClientRects().length > 0);
}

/* ---------- WebRTC links ---------- */

function ensureMediaLink(peerId) {
  const existing = mediaLinks.get(peerId);
  if (existing) return existing;
  if (typeof RTCPeerConnection === "undefined") throw new Error("media unsupported");
  const connection = new RTCPeerConnection({ iceServers:[{ urls:"stun:stun.l.google.com:19302" }] });
  const link = { peerId, connection, remoteDescriptionReady:false, pendingCandidates:[], offering:false };
  mediaLinks.set(peerId, link);
  connection.onicecandidate = event => {
    if (mediaLinks.get(peerId) === link && event.candidate) send({ type:"signal", to:peerId, data:JSON.stringify({ kind:"candidate", candidate:event.candidate }) });
  };
  connection.ontrack = event => {
    if (mediaLinks.get(peerId) !== link) return;
    const stream = event.streams?.[0];
    if (stream) attachRemoteStream(peerId, stream);
  };
  connection.onconnectionstatechange = () => {
    if (mediaLinks.get(peerId) !== link) return;
    if (connection.connectionState === "failed") {
      setStatus("ERREUR MÉDIA", "degraded");
      addDebugEntry("media", "warning", "peer_connection_failed", "Une connexion média a échoué; les autres vues restent actives.");
      closeMediaLink(peerId);
      if (isCamera && localStream && controllerPeerId && !explicitCameraStop) setTimeout(() => startMedia(false).catch(reportMediaFailure), 0);
    }
  };
  return link;
}

// Closes one participant's link (and, on the controller, its view) without touching any other.
function closeMediaLink(peerId, { keepTile = false } = {}) {
  const link = mediaLinks.get(peerId);
  if (link) {
    mediaLinks.delete(peerId);
    link.connection.close();
  }
  if (!isCamera && !keepTile) removeCameraTile(peerId);
  renderVideoStage();
}

function closeAllMediaLinks() {
  for (const peerId of [...mediaLinks.keys()]) closeMediaLink(peerId);
  for (const peerId of [...cameraTiles.keys()]) removeCameraTile(peerId);
}

function addLocalTracks(connection) {
  if (!connection || !localStream) return;
  for (const track of localStream.getTracks()) if (!connection.getSenders().some(sender => sender.track === track)) connection.addTrack(track, localStream);
}

function stopLocalCapture() {
  if (localStream) mediaState.captureMode = "stopped";
  localStream?.getTracks().forEach(track => track.stop());
  localStream = undefined;
  if (localTile) {
    if (fullscreenTileId === localTile.key) exitFullscreen();
    localTile.video.srcObject = null;
    localTile.figure.remove();
    localTile = undefined;
  }
  join.textContent = "DÉMARRER CAMÉRA + VOIX";
  join.disabled = false;
  stopSharingButton.hidden = true;
  flipCameraButton.hidden = true;
  renderVideoStage();
}

function stopMedia() {
  cancelLiveTouch("media-stopped");
  const pendingCapture = captureAttempt;
  if (pendingCapture) pendingCapture.cancelled = true;
  captureAttempt = undefined;
  captureGeneration += 1;
  explicitCameraStop = false;
  videoViewingStopped = false;
  videoPaused = false;
  controllerPeerId = undefined;
  knownCameraIds = [];
  closeAllMediaLinks();
  if (isCamera) {
    stopLocalCapture();
    resetCameraIdleCopy();
  }
  renderVideoStage();
}

function captureConstraints() {
  return { video:{ facingMode:{ ideal:cameraFacing } }, audio:true };
}

function mediaErrorName(error) {
  if (error?.message === "media unsupported") return "MediaUnsupported";
  const name = typeof error?.name === "string" ? error.name : "";
  return Object.prototype.hasOwnProperty.call(MEDIA_ERROR_MESSAGES, name) ? name : "UnknownError";
}

function mediaErrorText(name) {
  if (name === "MediaUnsupported") return window.isSecureContext ? "ce navigateur ne fournit pas la caméra (mediaDevices absent)" : "contexte non sécurisé : HTTPS requis pour la caméra";
  return MEDIA_ERROR_MESSAGES[name] ?? "erreur inconnue";
}

// Counts only (never labels or device ids). Lists are empty or generic before permission, so the
// page never concludes anything from them before getUserMedia has been tried.
async function refreshDeviceCounts() {
  if (!navigator.mediaDevices?.enumerateDevices) return;
  try {
    const devices = await navigator.mediaDevices.enumerateDevices();
    mediaState.devices = {
      videoinput:devices.filter(device => device.kind === "videoinput").length,
      audioinput:devices.filter(device => device.kind === "audioinput").length,
      afterCaptureAttempt:mediaState.captureMode !== "not started",
    };
  } catch {
    mediaState.devices = undefined;
  }
}

// Fallback chain: preferred camera + voice, any camera + voice, then any camera without voice.
// A NotAllowedError on a voice request goes straight to video only (a WebView may grant only the
// camera); a SecurityError stops the chain.
async function acquireCaptureStream(isCurrent) {
  const chain = [
    { constraints:captureConstraints(), mode:"video+audio" },
    { constraints:{ video:true, audio:true }, mode:"video+audio" },
    { constraints:{ video:true, audio:false }, mode:"video only" },
  ];
  let lastError;
  for (let index = 0; index < chain.length; index += 1) {
    const step = chain[index];
    if (index === 1 && lastError?.name === "NotAllowedError") continue;
    try {
      const stream = await navigator.mediaDevices.getUserMedia(step.constraints);
      if (step.mode === "video only") mediaState.audioErrorName = mediaErrorName(lastError);
      mediaState.captureMode = step.mode;
      mediaState.lastErrorName = undefined;
      return stream;
    } catch (error) {
      lastError = error;
      const name = mediaErrorName(error);
      mediaState.lastErrorName = name;
      addDebugEntry("media", "warning", "camera_capture_attempt_failed", `Tentative caméra ${index + 1}/3 refusée : ${name} (${mediaErrorText(name)}).`);
      if (!isCurrent() || name === "SecurityError") break;
    }
  }
  mediaState.captureMode = "failed";
  throw lastError ?? new Error("media unsupported");
}

async function startMedia(requestCapture = false) {
  if (!isCamera) return;
  if (!requestCapture && explicitCameraStop) return;
  if (requestCapture) explicitCameraStop = false;
  if (!localStream) {
    if (!requestCapture) return;
    if (!navigator.mediaDevices?.getUserMedia || typeof RTCPeerConnection === "undefined") throw new Error("media unsupported");
    const attempt = { generation:++captureGeneration, cancelled:false };
    captureAttempt = attempt;
    join.disabled = true;
    stopSharingButton.hidden = false;
    setStatus("AUTORISATION CAMÉRA");
    mediaTitle.textContent = "Autorisation caméra demandée";
    mediaCopy.textContent = "Répondez à l’invite pour autoriser la caméra et le microphone. Rien n’est partagé avant votre accord.";
    addDebugEntry("media", "info", "camera_permission_prompt", "En attente de la réponse à la demande caméra et microphone.");
    mediaState.audioErrorName = undefined;
    const isCurrent = () => captureAttempt === attempt && !attempt.cancelled && attempt.generation === captureGeneration && !explicitCameraStop;
    let stream;
    try {
      stream = await acquireCaptureStream(isCurrent);
    } catch (error) {
      if (captureAttempt !== attempt || attempt.cancelled || attempt.generation !== captureGeneration || explicitCameraStop) return;
      captureAttempt = undefined;
      join.disabled = false;
      stopSharingButton.hidden = true;
      resetCameraIdleCopy();
      throw error;
    }
    if (captureAttempt !== attempt || attempt.cancelled || attempt.generation !== captureGeneration || explicitCameraStop) {
      stream.getTracks().forEach(track => track.stop());
      if (captureAttempt === attempt) captureAttempt = undefined;
      return;
    }
    captureAttempt = undefined;
    localStream = stream;
    showLocalPreview(localStream);
    void refreshDeviceCounts();
  }
  join.textContent = "CAMÉRA PRÊTE";
  join.disabled = true;
  stopSharingButton.hidden = false;
  flipCameraButton.hidden = false;
  if (!controllerPeerId) return;
  await offerTo(controllerPeerId);
}

async function offerTo(peerId) {
  const link = ensureMediaLink(peerId);
  addLocalTracks(link.connection);
  if (link.offering || link.connection.signalingState !== "stable" || link.connection.localDescription) return;
  link.offering = true;
  try {
    const offer = await link.connection.createOffer();
    await link.connection.setLocalDescription(offer);
    if (mediaLinks.get(peerId) === link) send({ type:"signal", to:peerId, data:JSON.stringify({ kind:"offer", description:link.connection.localDescription }) });
  } finally {
    link.offering = false;
  }
}

// Swap front/rear camera in place. Android devices (e.g. S25) cannot open both cameras at once, so
// the current video track is stopped BEFORE the other one is opened; on failure the previous
// camera is reopened so the participant is never left without video. The new track replaces the
// old one on every live link (no renegotiation).
async function openVideoTrack(video) {
  try {
    const stream = await navigator.mediaDevices.getUserMedia({ video, audio:false });
    const [track] = stream.getVideoTracks();
    stream.getTracks().forEach(item => { if (item !== track) item.stop(); });
    return { track };
  } catch (error) {
    return { error };
  }
}

const trackDeviceId = track => track?.getSettings?.().deviceId;

async function openOtherCamera(nextFacing, previousId) {
  let lastError;
  for (const video of [{ facingMode:{ exact:nextFacing } }, { facingMode:{ ideal:nextFacing } }]) {
    const { track, error } = await openVideoTrack(video);
    if (track && (previousId === undefined || trackDeviceId(track) !== previousId)) return { track };
    track?.stop();
    if (error) lastError = error;
  }
  let videoInputs = [];
  try { videoInputs = (await navigator.mediaDevices.enumerateDevices?.() ?? []).filter(device => device.kind === "videoinput"); } catch {}
  for (const device of videoInputs) {
    if (!device.deviceId || device.deviceId === previousId) continue;
    const { track, error } = await openVideoTrack({ deviceId:{ exact:device.deviceId } });
    if (track) return { track };
    lastError = error ?? lastError;
  }
  return { single:videoInputs.length <= 1, error:lastError };
}

async function useVideoTrack(track) {
  localStream.addTrack(track);
  for (const [peerId, link] of mediaLinks) {
    const senders = link.connection.getSenders();
    const sender = senders.find(item => item.track?.kind === "video") ?? senders.find(item => item.track === null);
    if (sender) await sender.replaceTrack(track);
    else { closeMediaLink(peerId); if (peerId === controllerPeerId) await offerTo(peerId); }
  }
  if (localTile) { localTile.video.srcObject = null; localTile.video.srcObject = localStream; playTile(localTile); }
}

async function flipCamera() {
  if (!isCamera || !localStream || !navigator.mediaDevices?.getUserMedia) return;
  const generation = captureGeneration;
  const stillCurrent = () => localStream && generation === captureGeneration && !explicitCameraStop;
  const nextFacing = cameraFacing === "environment" ? "user" : "environment";
  const [previous] = localStream.getVideoTracks();
  const previousId = trackDeviceId(previous);
  flipCameraButton.disabled = true;
  if (previous) { localStream.removeTrack(previous); previous.stop(); }
  try {
    const result = await openOtherCamera(nextFacing, previousId);
    if (!stillCurrent()) { result.track?.stop(); return; }
    if (result.track) {
      await useVideoTrack(result.track);
      cameraFacing = result.track.getSettings?.().facingMode === "user" ? "user" : result.track.getSettings?.().facingMode === "environment" ? "environment" : nextFacing;
      mediaState.flipResult = "switched";
      mediaCopy.textContent = cameraSharingCopy();
      addDebugEntry("media", "info", "camera_switched", "Caméra changée sur cet appareil.");
      return;
    }
    const restore = await openVideoTrack(previousId ? { deviceId:{ exact:previousId } } : { facingMode:{ ideal:cameraFacing } });
    const restored = restore.track ?? (await openVideoTrack({ facingMode:{ ideal:cameraFacing } })).track;
    if (!stillCurrent()) { restored?.stop(); return; }
    const name = mediaErrorName(result.error);
    if (restored) await useVideoTrack(restored);
    mediaState.flipResult = result.single ? "single camera" : restored ? `failed:${name}` : `failed:${name}:not restored`;
    const message = result.single
      ? "Cet appareil n’a qu’une caméra; la caméra actuelle est conservée."
      : restored
        ? `Impossible d’ouvrir l’autre caméra (${name}) : ${mediaErrorText(name)}. Caméra précédente rétablie.`
        : `Impossible d’ouvrir l’autre caméra (${name}) et de rétablir la précédente : ${mediaErrorText(name)}. Arrêtez puis redémarrez le partage.`;
    mediaCopy.textContent = message;
    if (!restored) setStatus("ERREUR MÉDIA", "degraded");
    addDebugEntry("media", "warning", result.single ? "camera_single" : "camera_switch_failed", message);
  } finally {
    flipCameraButton.disabled = false;
  }
}

function stopCameraSharing() {
  if (!isCamera) return;
  explicitCameraStop = true;
  const pendingCapture = captureAttempt;
  if (pendingCapture) pendingCapture.cancelled = true;
  captureAttempt = undefined;
  captureGeneration += 1;
  closeAllMediaLinks();
  stopLocalCapture();
  join.textContent = "REDÉMARRER CAMÉRA + VOIX";
  join.disabled = false;
  stopSharingButton.hidden = true;
  mediaTitle.textContent = "Partage arrêté";
  mediaCopy.textContent = "Le partage est arrêté. Redémarrez-le explicitement lorsque vous êtes prêt.";
  setStatus("PARTAGE ARRÊTÉ", "degraded");
  addDebugEntry("media", "info", "camera_sharing_stopped", "Le partage caméra et voix a été arrêté par le participant caméra.");
}

function stopRemoteVideo() {
  if (isCamera) return;
  videoViewingStopped = true;
  for (const peerId of [...mediaLinks.keys()]) closeMediaLink(peerId);
  for (const peerId of [...cameraTiles.keys()]) removeCameraTile(peerId);
  mediaTitle.textContent = "Vidéo fermée";
  mediaCopy.textContent = "La vidéo est fermée. Le contrôle reste actif; reprenez-la lorsque vous êtes prêt.";
  renderVideoStage();
  addDebugEntry("media", "info", "video_viewing_stopped", "La vidéo a été fermée sans modifier les commandes du téléphone.");
}

function requestRemoteVideo() {
  if (isCamera || !videoViewingStopped) return;
  videoViewingStopped = false;
  videoPaused = false;
  for (const peerId of knownCameraIds) send({ type:"signal", to:peerId, data:JSON.stringify({ kind:"offer_request" }) });
  renderVideoStage();
  addDebugEntry("media", "info", "video_viewing_requested", "Demande explicite de reprise de la vidéo envoyée.");
}

async function handleSignal(event) {
  if (typeof event.from !== "string" || typeof event.data !== "string") return;
  if (!isCamera && videoViewingStopped) return;
  if (isCamera && explicitCameraStop) return;
  const from = event.from;
  let payload;
  try { payload = JSON.parse(event.data); } catch { throw new Error("invalid media signal"); }
  if (!payload || typeof payload !== "object" || typeof payload.kind !== "string") throw new Error("invalid media signal");
  // The controller only negotiates with cameras the broker listed (at most 4); a camera only with its controller.
  if (!isCamera && !knownCameraIds.includes(from)) return;
  if (isCamera && from !== controllerPeerId) return;
  if (payload.kind === "offer_request") {
    if (!isCamera || !localStream || explicitCameraStop) return;
    closeMediaLink(from);
    await offerTo(from);
    return;
  }
  if (payload.kind === "candidate") {
    const link = ensureMediaLink(from);
    if (!link.remoteDescriptionReady) {
      link.pendingCandidates.push(payload.candidate);
      return;
    }
    await link.connection.addIceCandidate(payload.candidate);
    return;
  }
  if (payload.kind === "offer") {
    if (isCamera && !localStream) {
      setStatus("DÉMARREZ LA CAMÉRA", "degraded");
      mediaCopy.textContent = "Démarrez la caméra avant d’accepter une offre média.";
      return;
    }
    const previous = mediaLinks.get(from);
    // A fresh offer from a camera replaces only that camera's previous negotiation; its view stays.
    if (previous && (previous.remoteDescriptionReady || previous.connection.signalingState !== "stable")) closeMediaLink(from, { keepTile:true });
    const link = ensureMediaLink(from);
    addLocalTracks(link.connection);
    await link.connection.setRemoteDescription(payload.description);
    link.remoteDescriptionReady = true;
    await flushCandidates(link);
    const answer = await link.connection.createAnswer();
    await link.connection.setLocalDescription(answer);
    if (mediaLinks.get(from) === link) send({ type:"signal", to:from, data:JSON.stringify({ kind:"answer", description:link.connection.localDescription }) });
    return;
  }
  if (payload.kind === "answer") {
    const link = mediaLinks.get(from);
    if (!link) return;
    await link.connection.setRemoteDescription(payload.description);
    link.remoteDescriptionReady = true;
    await flushCandidates(link);
  }
}

async function flushCandidates(link) {
  while (link.pendingCandidates.length) await link.connection.addIceCandidate(link.pendingCandidates.shift());
}

function cameraSharingCopy() {
  const sharing = controllerPeerId ? "Partage en cours vers le contrôleur." : "En attente de connexion du contrôleur.";
  if (mediaState.captureMode !== "video only") return sharing;
  return `${sharing} Vidéo sans voix : micro indisponible (${mediaState.audioErrorName ?? "UnknownError"}).`;
}

function showLocalPreview(stream) {
  if (!localTile) localTile = createTile("local", "Votre caméra · aperçu local", { local:true });
  localTile.video.srcObject = stream;
  localTile.video.muted = true;
  mediaTitle.textContent = "Caméra prête";
  mediaCopy.textContent = cameraSharingCopy();
  renderVideoStage();
  playTile(localTile);
  setStatus("CAMÉRA PRÊTE");
  addDebugEntry("media", "info", "camera_capture_ready", mediaState.captureMode === "video only"
    ? `La caméra de cet appareil est prête sans micro (${mediaState.audioErrorName ?? "UnknownError"}).`
    : "La caméra et le microphone de cet appareil sont prêts pour le rôle caméra.");
}

function reportMediaFailure(error) {
  const name = mediaErrorName(error);
  const code = name === "MediaUnsupported" ? "media_unsupported" : name === "NotAllowedError" ? "camera_permission_denied" : "media_start_failed";
  mediaState.lastErrorName = name;
  const message = name === "MediaUnsupported"
    ? `Ce navigateur ne peut pas fournir de média caméra : ${mediaErrorText(name)}.`
    : `Caméra de cet appareil indisponible (${name}) : ${mediaErrorText(name)}.`;
  setStatus(name === "NotAllowedError" ? "CAMÉRA BLOQUÉE" : name === "NotReadableError" ? "CAMÉRA OCCUPÉE" : "ERREUR MÉDIA", "degraded");
  mediaCopy.textContent = message;
  addDebugEntry("media", "warning", code, message);
  renderVideoStage();
}

/* ---------- Direct (Live) anchored pad ---------- */

// There is no fixed centre: the first point touched, anywhere in the zone, becomes the anchor and
// is position 0 (retracted). Only the upper half-plane counts: while the finger is above the
// anchor's horizontal line, the position is its radial distance from the anchor (sideways motion
// counts too) divided by the distance from the anchor to the farthest TOP corner of the zone
// (measured at pointerdown, at least LIVE_MIN_REACH px), so that corner is exactly 100. On or below
// the anchor line the position stays 0 and never comes back positive from there. The anchor (0)
// leaves at once, then at most one frame per LIVE_THROTTLE_MS (leading + trailing). A successful
// release cancels any pending frame and sends only end_live_touch: the phone then owns the slow
// return to 0. Cancellation paths send stop instead and never initiate that return.
const LIVE_THROTTLE_MS = 40;
const LIVE_MIN_REACH = 48;
const LIVE_KEY_STEP = 5;
const LIVE_KEY_STEPS = Object.freeze({ ArrowUp:LIVE_KEY_STEP, ArrowRight:LIVE_KEY_STEP, ArrowDown:-LIVE_KEY_STEP, ArrowLeft:-LIVE_KEY_STEP });
const liveStream = { timer:undefined, pending:undefined, lastSent:undefined, frames:0 };

// Anchor in pad coordinates plus the reach radius: distance from the anchor to the farthest top corner.
function liveAnchorGeometry(clientX, clientY) {
  const bounds = livePad.getBoundingClientRect();
  const width = Math.max(1, bounds.width);
  const height = Math.max(1, bounds.height);
  const anchorX = Math.max(0, Math.min(width, clientX - bounds.left));
  const anchorY = Math.max(0, Math.min(height, clientY - bounds.top));
  const radius = Math.max(LIVE_MIN_REACH, Math.hypot(Math.max(anchorX, width - anchorX), anchorY));
  return { left:bounds.left, top:bounds.top, anchorX, anchorY, radius };
}

// dx/dy are relative to the anchor (dy < 0 is above it). On or below the anchor line: 0.
function livePercent(dx, dy, radius) {
  if (!(dy < 0)) return 0;
  return Math.max(0, Math.min(100, Math.round(Math.hypot(dx, dy) / radius * 100)));
}

// Purely visual: value, ARIA state, anchor, upper reach half-disc, ray and finger (held inside the
// reach). Below the anchor line the ray and half-disc collapse and the finger is marked "below".
function renderLivePad(percent, dx = 0, dy = 0, radius = 1) {
  liveValue.textContent = `${percent} %`;
  livePad.setAttribute("aria-valuenow", String(percent));
  livePad.setAttribute("aria-valuetext", percent === 0 ? "0 %, rentré" : `${percent} %`);
  const above = dy < 0;
  const distance = Math.hypot(dx, dy);
  const scale = distance > radius ? radius / distance : 1;
  livePad.dataset.zone = above || distance === 0 ? "above" : "below";
  livePad.style?.setProperty("--fx", `${(dx * scale).toFixed(1)}px`);
  livePad.style?.setProperty("--fy", `${(dy * scale).toFixed(1)}px`);
  livePad.style?.setProperty("--reach", String(percent / 100));
  livePad.style?.setProperty("--ray", `${(above ? distance * scale : 0).toFixed(1)}px`);
  livePad.style?.setProperty("--angle", `${Math.atan2(dy, dx).toFixed(4)}rad`);
}

function placeLiveAnchor(geometry) {
  livePad.style?.setProperty("--ax", `${geometry.anchorX.toFixed(1)}px`);
  livePad.style?.setProperty("--ay", `${geometry.anchorY.toFixed(1)}px`);
  livePad.style?.setProperty("--range", `${(geometry.radius * 2).toFixed(1)}px`);
}

// A position may only leave for the live gesture of the current control-access epoch.
function liveMayStream() {
  return Boolean(liveGesture) && controlAccess && patternKey() === "streamingLive" && liveGesture.epoch === controlEpoch;
}

function emitLivePosition(percent) {
  if (!liveMayStream() || percent === liveStream.lastSent) return;
  if (command({ type:"set_live_position", percent }, { log:false })) {
    liveStream.lastSent = percent;
    liveStream.frames += 1;
  }
}

function startLiveCooldown() {
  liveStream.timer = setTimeout(() => {
    liveStream.timer = undefined;
    const next = liveStream.pending;
    liveStream.pending = undefined;
    if (next === undefined || !liveMayStream()) return;
    emitLivePosition(next);
    startLiveCooldown();
  }, LIVE_THROTTLE_MS);
}

function streamLivePosition(percent) {
  if (!liveMayStream()) return;
  if (liveStream.timer !== undefined) {
    liveStream.pending = percent;
    return;
  }
  emitLivePosition(percent);
  startLiveCooldown();
}

function cancelLiveStream() {
  if (liveStream.timer !== undefined) clearTimeout(liveStream.timer);
  liveStream.timer = undefined;
  liveStream.pending = undefined;
}

function startLiveGesture(source, pointerId, geometry) {
  cancelLiveStream();
  liveStream.lastSent = undefined;
  liveStream.frames = 0;
  liveGesture = { source, pointerId, epoch:controlEpoch, value:0, ...geometry };
  liveInteractionActive = true;
  placeLiveAnchor(geometry);
  livePad.dataset.state = "active";
}

function moveLiveTo(clientX, clientY) {
  const dx = clientX - liveGesture.left - liveGesture.anchorX;
  const dy = clientY - liveGesture.top - liveGesture.anchorY;
  const percent = livePercent(dx, dy, liveGesture.radius);
  renderLivePad(percent, dx, dy, liveGesture.radius);
  streamLivePosition(percent);
}

function beginLiveGesture(event) {
  if (!controlAccess || patternKey() !== "streamingLive" || liveGesture) return;
  if (event.pointerType === "mouse" && event.button !== 0) return;
  event.preventDefault();
  // Wherever the finger lands is the anchor = 0: that 0 leaves at once (the phone ramps there).
  const geometry = liveAnchorGeometry(event.clientX, event.clientY);
  startLiveGesture("pointer", event.pointerId, geometry);
  livePad.setPointerCapture?.(event.pointerId);
  renderLivePad(0, 0, 0, geometry.radius);
  streamLivePosition(0);
}

function moveLiveGesture(event) {
  if (!liveGesture || liveGesture.source !== "pointer" || liveGesture.pointerId !== event.pointerId) return;
  event.preventDefault();
  if (!liveMayStream()) {
    cancelLiveTouch("access-lost");
    return;
  }
  moveLiveTo(event.clientX, event.clientY);
}

function releaseLivePointer(event) {
  if (liveGesture?.source === "pointer" && liveGesture.pointerId !== event.pointerId) return;
  releaseLiveTouch();
}

function cancelLivePointer(event) {
  if (liveGesture?.source === "pointer" && liveGesture.pointerId !== event.pointerId) return;
  cancelLiveTouch("pointer-cancel");
}

function keyLiveGesture(event) {
  const step = LIVE_KEY_STEPS[event.key];
  if (step === undefined) return;
  event.preventDefault();
  if (!controlAccess || patternKey() !== "streamingLive") return;
  if (liveGesture && liveGesture.source !== "key") return;
  if (!liveGesture) {
    // Keyboard has no touch point: the anchor is the bottom middle of the zone and steps go upwards.
    const bounds = livePad.getBoundingClientRect();
    startLiveGesture("key", undefined, liveAnchorGeometry(bounds.left + bounds.width / 2, bounds.top + bounds.height));
  }
  if (!liveMayStream()) {
    cancelLiveTouch("access-lost");
    return;
  }
  liveGesture.value = Math.max(0, Math.min(100, liveGesture.value + step));
  renderLivePad(liveGesture.value, 0, -liveGesture.value / 100 * liveGesture.radius, liveGesture.radius);
  streamLivePosition(liveGesture.value);
}

function releaseLiveKey(event) {
  if (LIVE_KEY_STEPS[event.key] !== undefined && liveGesture?.source === "key") releaseLiveTouch();
}

function resetLiveTouchVisual(gesture) {
  if (!gesture) return;
  livePad.dataset.state = "released";
  renderLivePad(0);
}

// Successful pointer/key release: drop any pending position and ask the phone to own the slow
// return. This is the only path that sends end_live_touch.
function releaseLiveTouch() {
  const gesture = liveGesture;
  liveGesture = undefined;
  cancelLiveStream();
  resetLiveTouchVisual(gesture);
  if (liveInteractionActive) {
    liveInteractionActive = false;
    command({ type:"end_live_touch" }, { log:false });
    addDebugEntry("browser", "info", "live_touch_ended", `Toucher direct terminé (${liveStream.frames} position${liveStream.frames > 1 ? "s" : ""}); contenu masqué.`);
    liveStream.frames = 0;
  }
  if (gesture?.source === "pointer" && livePad.hasPointerCapture?.(gesture.pointerId)) livePad.releasePointerCapture(gesture.pointerId);
}

// Safety cancellation: pending positions are dropped and an admitted/open session is asked to
// stop immediately. Unlike releaseLiveTouch this never sends end_live_touch, so it cannot trigger
// the phone-owned slow return. If the socket is already gone, the phone's lease/session safety
// boundary remains authoritative and this best-effort frame is simply not sent.
function cancelLiveTouch(reason = "cancelled", sendStop = true) {
  const gesture = liveGesture;
  liveGesture = undefined;
  cancelLiveStream();
  resetLiveTouchVisual(gesture);
  if (liveInteractionActive) {
    liveInteractionActive = false;
    if (sendStop) command({ type:"stop" }, { log:false });
    addDebugEntry("browser", "info", "live_touch_cancelled", `Toucher direct annulé (${reason}); contenu masqué.`);
    liveStream.frames = 0;
  }
  if (gesture?.source === "pointer" && livePad.hasPointerCapture?.(gesture.pointerId)) livePad.releasePointerCapture(gesture.pointerId);
}

/* ---------- Wiring ---------- */

// The phone rejects an inverted envelope (minimum > maximum), so the pair is always sent ordered.
const depthCommand = () => {
  const minimum = +minimumInput.value / 100;
  const maximum = +maximumInput.value / 100;
  return { type:"set_depth", minimum:Math.min(minimum, maximum), maximum:Math.max(minimum, maximum) };
};
// Depth sliders cannot cross: the dragged one pushes the other along (both share the phone range).
function keepDepthOrdered(moved) {
  if (Number(minimumInput.value) <= Number(maximumInput.value)) return;
  const other = moved === minimumInput ? maximumInput : minimumInput;
  other.value = moved.value;
  renderSliderValue(limitInputs.find(entry => entry.input === other));
}
for (const button of document.querySelectorAll("[data-command]")) button.addEventListener("click", () => motionCommand(JSON.parse(button.dataset.command)));
document.querySelector("#emergency-stop").addEventListener("click", () => {
  // STOP is the sole emergency action: invalidate every scalar gesture first, then do not
  // prepend a normal Live release or a regular stop. Fresh input starts a new epoch normally.
  invalidateControlEpoch();
  cancelLiveTouch("emergency-stop", false);
  send({ type:"emergency_stop", seq:++sequence });
});
for (const entry of limitInputs) {
  entry.input.addEventListener("input", () => {
    if (entry.input === minimumInput || entry.input === maximumInput) keepDepthOrdered(entry.input);
    renderSliderValue(entry);
    if (!controlAccess) return;
    sliderInteractionEpoch.set(entry.input, controlEpoch);
    streamSlider(entry.input);
  });
  entry.input.addEventListener("change", () => releaseSlider(entry.input));
  entry.input.addEventListener("pointerup", () => releaseSlider(entry.input));
  // A browser-cancelled drag (page pan, palm, lost capture) is not a successful release: drop the
  // trailing frame instead of sending a stale value after the finger is gone.
  entry.input.addEventListener("pointercancel", () => cancelSlider(entry.input));
}
livePad.addEventListener("pointerdown", beginLiveGesture);
livePad.addEventListener("pointermove", moveLiveGesture);
livePad.addEventListener("pointerup", releaseLivePointer);
livePad.addEventListener("pointercancel", cancelLivePointer);
livePad.addEventListener("lostpointercapture", cancelLivePointer);
livePad.addEventListener("keydown", keyLiveGesture);
livePad.addEventListener("keyup", releaseLiveKey);
livePad.addEventListener("blur", () => cancelLiveTouch("pad-blur"));
pattern.addEventListener("change", event => {
  if (event.target?.name !== "pattern") return;
  invalidateControlEpoch();
  cancelLiveTouch("mode-change");
  motionCommand({ type:"select_pattern", key:event.target.value });
  syncModePresentation({ transition:true });
});
window.addEventListener("blur", () => cancelLiveTouch("window-blur"));
window.addEventListener("online", () => {
  if (reconnectTimer === undefined || sessionEnded) return;
  connect();
});
document.addEventListener("visibilitychange", () => { if (document.hidden) cancelLiveTouch("document-hidden"); });
document.addEventListener("keydown", event => {
  if (stage.classList.contains("full") && event.key === "Tab") {
    const focusable = fullscreenFocusableElements();
    if (focusable.length) {
      const currentIndex = focusable.indexOf(document.activeElement);
      const nextIndex = currentIndex < 0
        ? 0
        : (currentIndex + (event.shiftKey ? -1 : 1) + focusable.length) % focusable.length;
      event.preventDefault();
      focusable[nextIndex].focus({ preventScroll:true });
    }
    return;
  }
  if (event.key !== "Escape" || !stage.classList.contains("full")) return;
  const key = exitFullscreen();
  if (key !== undefined) tileFor(key)?.fullButton.focus({ preventScroll:true });
});
pauseVideoButton.addEventListener("click", toggleVideoPlayback);
stopVideoButton.addEventListener("click", () => { if (videoViewingStopped) requestRemoteVideo(); else stopRemoteVideo(); });
debug.copyButton.addEventListener("click", () => { void exportSafeDiagnostics(); });
join.addEventListener("click", () => startMedia(true).catch(reportMediaFailure));
flipCameraButton.addEventListener("click", () => { void flipCamera(); });
stopSharingButton.addEventListener("click", stopCameraSharing);

syncModePresentation();
renderPhoneLimits();
setControlsEnabled(false);
renderDebugState();
addDebugEntry("browser", "info", "page_loaded", "Page chargée.");
connect();
