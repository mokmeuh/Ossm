import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";

const root = resolve(import.meta.dirname, "..");
const html = readFileSync(resolve(root, "static/index.html"), "utf8");
const app = readFileSync(resolve(root, "static/app.js"), "utf8");
const css = readFileSync(resolve(root, "static/app.css"), "utf8");
const packageJson = JSON.parse(readFileSync(resolve(root, "package.json"), "utf8"));

describe("static remote client", () => {
  it("keeps the web version synchronized and exposes one safe Stop action", () => {
    expect(packageJson.version).toBe("2.0.13");
    expect(html).toContain('lang="fr"');
    expect(html).toContain('content="Client web OSSM Remote v2.0.13 pour session de contrôle sécurisée"');
    expect(html).toContain("WEB v2.0.13");
    expect((html.match(/>STOP</g) ?? []).length).toBe(1);
    expect(html).not.toContain(">SPLIT<");
    expect(html).not.toContain(">FULL<");
    expect(html).toContain('id="pause-video"');
    expect(html).toContain('Pause vidéo');
    expect(html).toContain('id="stop-video"');
    expect(html).toContain('Fermer la vidéo');
    expect(html).toContain('id="stop-sharing"');
    expect(html).toContain('Arrêter le partage');
    expect(html).not.toContain("RELEASE");
    expect(html).not.toContain("EMERGENCY STOP");
    expect(html).not.toContain("Retraction");
  });

  it("leaves camera invitations to the phone owner", () => {
    expect(html).not.toContain('id="camera-invite"');
    expect(html).not.toContain("copy-camera-invite");
    expect(html).not.toMatch(/INVITATION CAMÉRA|COPIER L’INVITATION/);
    expect(app).not.toContain("cameraInvite");
    expect(app).not.toContain('searchParams.set("role", "camera")');
  });

  it("offers every confirmed phone pattern key as a native radio chip", () => {
    const keys = [...html.matchAll(/<input type="radio" name="pattern" value="([A-Za-z]+)"/g)].map(match => match[1]);
    expect(keys).toEqual(["simpleStroke", "teasingPounding", "roboStroke", "halfNHalf", "deeper", "stopNGo", "insist", "streamingLive", "progressive", "autoRandom", "natural"]);
    expect(html).toContain('<fieldset class="mode-picker" id="pattern">');
    expect(html).not.toContain("<select");
    expect((html.match(/ checked /g) ?? []).length).toBe(1);
  });

  it("makes Live a selected mode with one upper half-circle pad as its only control", () => {
    expect(html).toContain('id="live-touch-panel" hidden');
    expect(html).toContain('id="standard-controls"');
    expect(html).toContain('id="parameter-controls"');
    expect(html).not.toContain('id="live"');
    expect(html).not.toContain("live-range");
    expect(html).toMatch(/<div class="live-pad" id="live-pad" role="slider" tabindex="0" aria-label="Position directe" aria-valuemin="0" aria-valuemax="100" aria-valuenow="0" aria-valuetext="0 %, rentré"/);
    expect(html).toContain("Touchez : ce point = 0 · montez = plus profond");
    // No fixed centre: an anchor placed by the first touch, upper half-circles 25/50/75/100, the
    // anchor line and a greyed "reste à 0" band below it. No full reach circle any more.
    expect(html).toContain('<span class="live-pad-anchor"></span>');
    expect(html).toContain('<span class="live-pad-baseline"></span>');
    expect(html).toContain('<span class="live-pad-floor-label">reste à 0</span>');
    for (const step of ["25", "50", "75", "100"]) expect(html).toContain(`<span class="live-pad-arc live-pad-arc--${step}"><span class="live-pad-arc-label">${step}</span></span>`);
    expect(html).not.toContain("live-pad-range");
    expect(html).not.toContain("éloignez-vous");
    expect(html).not.toContain("live-pad-center");
    expect(html).not.toContain("live-pad-rings");
    expect(app).not.toContain("LIVE_PAD_RADIUS");
    expect((html.match(/role="slider"/g) ?? []).length).toBe(1);
    expect(app).toContain('const liveMode = patternKey() === "streamingLive"');
    expect(app).toContain("const LIVE_THROTTLE_MS = 40;");
    expect(css).toContain(".live-touch-panel");
    // The zone fills the column and the viewport height above the fixed STOP dock.
    expect(css).toMatch(/\.live-pad \{[^}]*inline-size: 100%;[^}]*block-size: max\(300px, calc\(100dvh - var\(--dock-stop-height\)[^}]*touch-action:none; transition:none!important;/);
    expect(css).not.toMatch(/\.live-pad \{[^}]*aspect-ratio/);
    // The release glide is not animated under reduced motion.
    const reduced = css.slice(css.indexOf("@media (prefers-reduced-motion: reduce)"));
    expect(reduced).toContain('.live-pad[data-state="released"] .live-pad-finger');
    expect(reduced).toContain('.live-pad[data-state="released"] .live-pad-origin');
    expect(reduced).toContain('.live-pad[data-state="released"] .live-pad-floor');
    expect(reduced).toContain("transition: none;");
  });

  it("pins STOP in the bottom thumb dock with full-width sliders and readable values", () => {
    expect(html).toMatch(/<div class="thumb-dock" id="thumb-dock">[\s\S]*id="standard-controls"[\s\S]*id="emergency-stop"[\s\S]*<\/div>\s*<\/section>/);
    for (const id of ["speed", "sensation", "minimum", "maximum"]) {
      expect(html).toContain(`<label for="${id}">`);
      expect(html).toContain(`<output id="${id}-value" for="${id}">`);
      // The limit badge lives in the label row, so it never shifts the slider under the finger.
      expect(html).toMatch(new RegExp(`<div class="slider-head"><label for="${id}">[^<]+</label><span class="limit-badge" id="${id}-limit" hidden></span><output id="${id}-value"`));
    }
    expect(html).toMatch(/<div class="centered-range" id="sensation-track" data-neutral="in"><input id="sensation" type="range" min="0" max="100" value="50" aria-valuetext="neutre" aria-describedby="sensation-legend" \/><span class="neutral-mark" aria-hidden="true"><\/span><\/div><p class="sensation-legend" id="sensation-legend">/);
    expect(css).toContain(".neutral-mark");
    expect(css).toMatch(/\.centered-range\[data-neutral="out"\] \.neutral-mark \{ display: none; \}/);
    expect(css).toMatch(/\.thumb-dock \{[^}]*position: fixed/);
    expect(css).toContain("@media (prefers-color-scheme: dark)");
    expect(css).toContain("@media (prefers-reduced-motion: reduce)");
  });

  it("keeps controller media receive-only, multi-camera and camera capture explicit", () => {
    expect(app).toContain('const isCamera = role === "camera"');
    expect(app).toContain("join.hidden = !isCamera");
    expect(app).toContain("if (!isCamera) return;");
    expect(app).toContain("navigator.mediaDevices?.getUserMedia");
    expect(app).toContain("join.addEventListener(\"click\", () => startMedia(true).catch(reportMediaFailure))");
    expect(app).toContain("if (isCamera && !localStream)");
    expect(app).toContain("const mediaLinks = new Map()");
    expect(app).toContain("function syncCameraPeers(cameraIds)");
    expect(app).toContain("function closeMediaLink(peerId");
    expect(app).toContain("function setAudioCamera(peerId)");
    expect(app).toContain('event.type === "lease_lost"');
    expect(app).toContain("La caméra peut rejoindre à nouveau.");
    expect(app).toContain('element.setAttribute("playsinline", "")');
    expect(app).toContain("function showLocalPreview(stream)");
    expect(app).toContain("function stopRemoteVideo()");
    expect(app).toContain("function stopCameraSharing()");
    expect(app).toContain("captureGeneration");
    expect(app).toContain("captureAttempt");
    expect(app).toContain("explicitCameraStop");
    expect(app).toContain('element.dataset.uiTransition = "crossfade"');
    expect(app).toContain("formatDiagnosticTime");
    expect(app).toContain("function fullscreenFocusableElements()");
    expect(app).toContain('event.key === "Tab"');
    const mediaStopBody = app.slice(app.indexOf("function stopRemoteVideo()"), app.indexOf("function requestRemoteVideo()"));
    expect(mediaStopBody).not.toContain('type:"command"');
    expect(mediaStopBody).not.toContain('type:"emergency_stop"');
    const cameraStopBody = app.slice(app.indexOf("function stopCameraSharing()"), app.indexOf("function stopRemoteVideo()"));
    expect(cameraStopBody).not.toContain('type:"command"');
    expect(cameraStopBody).not.toContain('type:"emergency_stop"');
    expect(app).toContain('document.body.classList.toggle("video-fullscreen"');
    expect(app).toContain("Promise.resolve(tile.video.play()).then");
    expect(app).toContain('"playback_blocked"');
    expect(app).toContain('"media_unsupported"');
    expect(app).toContain("Ce navigateur ne peut pas fournir de média caméra : ");
  });

  it("uses no blocking dialogs or popups, so the camera page runs inside an Android WebView", () => {
    for (const forbidden of ["alert(", "confirm(", "prompt(", "window.open(", "requestFullscreen(", "Notification"]) expect(app).not.toContain(forbidden);
  });
});
