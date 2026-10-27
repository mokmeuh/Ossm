# OSSM Remote Cloud Design

## 0. Source contract

This document records the implemented browser client contract in `static/index.html`,
`static/app.css`, and `static/app.js`. It is descriptive rather than a proposal. The
current web patch is `WEB v2.0.13`; the browser version is independent from the Android
APK version.

The page is a semantic French-language control surface. Its first DOM surface is the
session shell and waiting media placeholder, not a splash screen. The shell contains the
session/status header, remote controls, remote-camera stage, media invitation, participant
hint, and a disclosure-based technical report. The browser source is the contract for this
surface; checked-in JavaScript counterparts and the Worker remain separate concerns.

## 1. Direction

The implemented direction is quiet remote instrumentation: semantic light surfaces by
default, a system dark mapping, aqua operational accents, explicit degraded/lost status,
and one vivid red emergency STOP. Panels use the existing glass treatment and the camera
stage provides media presence without covering the controller's safety action.

The visual system is deliberately operational rather than decorative. No imagery or
branded loading screen is required for the page to become useful, and no native Android
camera or motor capability is implied by this browser contract.

## 2. Tokens

All names and values below are implemented in `static/app.css`.

- Grid and typography: `--space:4px`, `--space-2:calc(var(--space) * 2)`,
  `--font-body:ui-rounded, "Avenir Next", "Segoe UI", sans-serif`, and
  `--font-mono:ui-monospace, SFMono-Regular, Menlo, Consolas, monospace`. Phone body
  text uses `--font-size-body:1.0625rem` (17px); desktop body is `1rem` from the
  `min-width:700px` rule; secondary copy is `--font-size-secondary:.9375rem` (15px);
  telemetry is `--font-size-telemetry:.875rem` (14px); labels are `.75rem` (12px).
  Body line height is `--line-height-body:1.5`.
- Geometry and focus: `--radius-panel:20px`, `--radius-control:14px`,
  `--radius-compact:10px`, `--radius-live:16px`, `--focus-ring-width:3px`,
  `--focus-ring-offset:3px`, and `--debug-hit-target:44px`.
- Motion primitives: `--duration-fast:140ms`, `--duration-state:180ms`,
  `--duration-crossfade:180ms`, and `--ease-standard:cubic-bezier(.2, .8, .2, 1)`.
  The JavaScript transition marker is interruptible and expires after 220ms. Control
  hover/pressed feedback may transition its own background, border, colour, shadow, or
  press transform; the mode/video state marker is an opacity-only crossfade.
- Light semantic palette (the default): `--ink:#edf7f8`, `--surface:#ffffff`,
  `--surface-raised:#f7fbfc`, `--glass:rgba(255, 255, 255, .86)`,
  `--line:#57747d`, `--aqua:#075b62`, `--aqua-deep:#0a747d`,
  `--warn:#a62238`, `--text:#162831`, `--muted:#355661`,
  `--disabled-text:#49656d`, `--disabled-line:#6f888f`, and
  `--disabled-surface:#e6eef0`.
- System dark semantic palette (`@media (prefers-color-scheme: dark)`):
  `--ink:#061018`, `--surface:#0d1d28`, `--surface-raised:#122a35`,
  `--glass:rgba(17, 39, 53, .84)`, `--line:#5c838d`, `--aqua:#7ce7db`,
  `--aqua-deep:#52c8c1`, `--warn:#ff8791`, `--text:#ecf8fa`,
  `--muted:#bed6db`, `--disabled-text:#c1d3d6`, `--disabled-line:#668b95`,
  and `--disabled-surface:#1b303a`.
- Controls and safety use the implemented semantic families: `--control-border`,
  `--control-surface`, `--control-hover`, `--control-pressed`, `--control-disabled`,
  `--input-track`, `--input-thumb`, `--video-placeholder`, `--stop-start`,
  `--stop-end`, `--stop-line`, `--stop-text`, `--stop-subtext`, and `--stop-shadow`.
  Disabled controls are opaque and retain their border/text boundary; disabled STOP uses
  the dashed `--disabled-line` border and `--disabled-surface`.
- Diagnostics reuse the same grid: `--debug-type-family:var(--font-mono)`,
  `--debug-type-label:600 var(--font-size-telemetry-label) var(--debug-type-family)`,
  `--debug-type-value:500 var(--font-size-telemetry) / 1.5 var(--debug-type-family)`,
  `--debug-inline-gap:calc(var(--space) * 2)`, `--debug-section-gap:calc(var(--space) * 3)`,
  `--debug-panel-inset:calc(var(--space) * 3)`, `--debug-cell-inset:calc(var(--space) * 2)`,
  `--debug-log-indent:calc(var(--space) * 6)`, `--debug-border-width:1px`,
  `--debug-cell-radius:10px`, and `--debug-console-rail-width:2px`.

## 3. Layout and scrolling

The page is mobile-first (390px reference); desktop is a secondary adaptation. Below 700px the
`.shell` is one column with a 16px gutter (safe-area aware) and 12px gaps, in this order: the
top bar (wordmark, `WEB vX.Y.Z`, status chip), a compact session title, the compact
`#phone-limits` strip, the video stage, the controls, the MÉDIA card, the participant hint and
the folded technical report. There is no horizontal page scroll at 390px.

The controller's primary actions live in a fixed bottom `.thumb-dock` inside `#remote-controls`:
a row with `POSITION INITIALE`, `PAUSE`, `REPRENDRE` (52px) above the single full-width `STOP`
(76px, large label). The dock is always visible; the shell reserves bottom padding for it (less
in Direct, where the action row is hidden). `#remote-controls` drops the glass `backdrop-filter`
so the fixed dock is positioned against the viewport. At 700px and above the shell (max 1120px)
becomes two columns: controls on the left spanning rows 4–6, video and MÉDIA card on the right,
and the dock lays the action row and STOP side by side.

Mode selection is a native `<fieldset id="pattern">` of radio "chips" (44px minimum targets),
grouped as "Motifs machine" and "Modes de l’application"; the checked chip is filled and carries
a ✓ glyph, so selection is not colour-only. Each slider is a full-width row with its label, a
large tabular `output` value (`#speed-value`, `#sensation-value`, `#minimum-value`,
`#maximum-value`) and its `limit-badge`. The badge sits in the label row, between the label and the
value, so its appearance or disappearance (limits received, socket closed) never shifts a slider
under the finger. Every slider range leaves vertical pans to the page (`touch-action: pan-y`).

Sensation is bipolar: the slider keeps its native 0..100 value (sent as `set_sensation` 0..1)
with 50 (0.5) as neutral, but is presented centred. `#sensation-value` shows −100…0…+100
(`(value − 50) × 2`, 0 = neutral, U+2212 minus), the native track is restyled inside
`#sensation-track` (`.centered-range`) with a visible neutral marker (`.neutral-mark`, hidden
when neutral lies outside the bounded range) and a fill from neutral to the thumb, both
positioned by `--neutral-pos` / `--fill-a` / `--fill-b`. `#sensation-legend` under the slider
depends on the selected mode, from the StrokeEngine documentation in
`firmware/Software/lib/StrokeEngine/src/pattern.h`: Teasing Pounding and Half’n’Half "← retrait
rapide · avancée rapide →", Robo Stroke "← accélération douce · mouvement robotique →", Deeper
"← rampe courte · rampe longue →", Stop’n’Go "← pauses courtes · pauses longues →", Insist "←
vers l’arrière · vers l’avant →", Simple Stroke "Sans effet en Simple Stroke" (documented as
having no effect), and the application modes the generic "← vers −100 · vers +100 →". The
input carries `aria-valuetext` ("neutre", "retrait rapide 40", or "−40"/"+40" for the generic
modes) and is described by its badge and legend.

The video stage shows a compact placeholder ("AUCUNE CAMÉRA CONNECTÉE") until a view exists.
On the controller page the MÉDIA card is derived from the real state on every presence, track and
close: views present → "Caméra connectée" / "N caméras connectées"; a camera listed by the broker
presence but not yet streaming → "Caméra présente" / "Une caméra a rejoint la session; en attente de
son flux." (placeholder "CONNEXION DE LA CAMÉRA…"); only when no camera participant has joined →
"Aucune caméra connectée pour le moment : aucun appareil caméra n’a rejoint la session." The camera
page never uses that wording: its placeholder is "APERÇU CAMÉRA EN ATTENTE", or "CAMÉRA DE CET
APPAREIL INDISPONIBLE" after a failed capture.
Camera views are tiles in `#video-grid`: on phones the primary view is full width (16:9) and the
other views are tappable thumbnails underneath (tap = show it large); on desktop the views sit
side by side. Each tile has a `Caméra N` label and its own `Activer le son` / `Plein écran`
controls. A single view goes fullscreen at a time (`.video-stage.full` + `data-focus`); the
controller STOP is re-parented above it with safe-area insets, while the camera role never
receives that motor-control surface. `Pause vidéo` and `Fermer la vidéo` act on all views.

Diagnostics use native `<details>/<summary>`. The recent-event list flows with the
disclosure and has `overflow:visible`; the page/disclosure is the sole vertical scroll
owner, so there is no clipped nested diagnostics scrollbar.

## 4. Roles, controls, and state transitions

The URL query selects `role=camera`; every other value is the controller role. Camera
invitations belong to the phone owner: the controller page no longer shows any invitation or
copy-link control. `POST /api/sessions` returns `{url, cameraUrl, expiresAt}`; `cameraUrl` is
`${origin}/s/${id}?role=camera#k=${cameraSecret}` with a separate camera-only key (the field is
additive; older hosts read only `url`). The broker admits the camera key for the `camera` role
only (`controller`/`host` are `forbidden`); the main key keeps every role. Renewal and revocation
accept only the main key, and revocation closes cameras too. Sessions stored without a camera key
keep working with the main key only.

The camera role hides controller commands and the maxima strip, and asks for camera and
microphone permission only after `DÉMARRER CAMÉRA + VOIX`. Capture follows a fallback chain:
`{video:{facingMode:{ideal:"environment"}}, audio:true}` → `{video:true, audio:true}` →
`{video:true, audio:false}` (a `NotAllowedError` on a voice request skips straight to video only,
since a WebView may grant only the camera; a `SecurityError` stops the chain). Video without voice
says "Vidéo sans voix : micro indisponible (Name)". A failure shows the DOMException name and a
short French cause in the camera UI and the log, never the raw message: `NotAllowedError`
"permission refusée par le navigateur/WebView" (status CAMÉRA BLOQUÉE), `NotReadableError`
"caméra occupée par une autre application" (status CAMÉRA OCCUPÉE), `NotFoundError`,
`OverconstrainedError`, `SecurityError`, `AbortError`, otherwise `UnknownError`.
`enumerateDevices` is never consulted before `getUserMedia` (lists are empty or generic before
permission). `Changer de caméra` stops the current video track before opening the other camera
(Android cannot open both at once), tries `facingMode:{exact}`, then `{ideal}` (rejected if it
returns the same device), then the other `videoinput` by `deviceId`; the new track replaces the
old one on every link via `replaceTrack`. On failure the previous camera is reopened and the
page says why; a single-camera device says "Cet appareil n’a qu’une caméra". It then shows a
muted, `playsinline` local preview tile ("Votre caméra · aperçu local") and offers only to the
current controller. Cancellation increments a capture generation, stops late tracks, and
requires an explicit restart. The page uses no `alert`/`confirm`/`prompt`/`window.open`/
Fullscreen API, so it can run inside an Android WebView (the embedding app must grant the WebView
camera/microphone permission request).

The controller keeps one `RTCPeerConnection` per camera participant listed by the broker (at
most 4), keyed by participant id: offers, answers and candidates are routed by `from`/`to`;
signals from participants not in the presence list are ignored. A camera that leaves closes
only its own link and view; the others keep playing. Views start muted; audio comes from one view
at a time (enabling one mutes the others).

The controller exposes the command contract: the modes `simpleStroke`, `teasingPounding`,
`roboStroke`, `halfNHalf`, `deeper`, `stopNGo`, `insist`, `streamingLive` (Direct),
`progressive`, `autoRandom` (sent as `select_pattern` with that exact key), `POSITION INITIALE`,
`PAUSE`, `REPRENDRE`, `Vitesse`, `Sensation`, `Minimum`, and `Profondeur`. Selecting `Direct`
hides the action row and parameter sliders and shows one free touch zone (`#live-pad`), the only
Live control. The zone takes the full column width and the viewport height above the fixed STOP
dock (`max(300px, 100dvh − STOP − 176px − safe area)`, about 380 × 590 px on a 390 × 844 phone),
so STOP stays visible. There is no fixed centre: the first point touched, anywhere, becomes the
anchor and is position 0 (retracted). Only the half-plane above the anchor's horizontal line
counts (an upward half-circle): while the finger is above that line, the position is its radial
distance from the anchor (sideways motion counts too) divided by the distance from the anchor to
the farthest TOP corner of the zone (computed at `pointerdown`, at least 48 px when the anchor is
at the very top), clamped and rounded to an integer 0..100; that top corner is exactly 100. On or
below the anchor line the position is 0 and never becomes positive again from there (no mirror).
The visuals are an anchor marker where the finger landed, upper half-circles centred on the anchor
at 25/50/75/100 (labelled; the 100 one has the farthest-top-corner radius, so it may run partly
off the zone), the anchor's horizontal line, a greyed hatched band under it labelled "reste à 0",
a reach half-disc, a ray from the anchor to the finger (collapsed below the line, where the finger
marker is dimmed, `data-zone="below"`), a large `%` value and the legend "Touchez : ce point = 0 ·
montez = plus profond"; an idle prompt sits in the empty zone. `pointerdown` sends 0 (the
anchor) at once (the phone ramps from the machine's current position, no teleport);
`pointermove` streams the position in real time for fast back-and-forth strokes, leading +
trailing throttled to one frame per 40 ms, deduplicated, only for the gesture of the current
control-access epoch. A successful pointer `pointerup` or keyboard arrow `keyup` drops any
pending frame and sends the only normal-release frame, `end_live_touch`; the phone then owns the
slow return to 0 and the page never sends a position after release (one log entry summarises
the frames). `pointercancel`, `lostpointercapture`, pad blur, window blur, document hiding, mode
exit and access/session loss drop pending frames and issue a best-effort regular `stop` instead;
they never send `end_live_touch` and never initiate the phone-owned return. On release the
finger, ray and half-disc glide back to the anchor in 650 ms and the anchor group and grey band then fade out
(`data-state="released"`; instant under `prefers-reduced-motion`). Each new touch takes a new
anchor. A second finger is ignored. The pad is `role="slider"` (`aria-valuenow`, `aria-valuetext` "N %"
/ "0 %, rentré", `aria-disabled` and `tabindex=-1` without access); arrow keys step ±5
(Up/Right +, Down/Left −, from 0, anchored at the bottom middle of the zone) through the same throttle and the arrow `keyup` uses the normal
release path. The single `STOP` cancels local Live state and emits only the emergency-stop frame;
it remains the only motor emergency action and is never preceded by `end_live_touch`. A held finger
cannot resume after access loss without a new `pointerdown`. `STOP` stays
enabled for an admitted controller as long as the WebSocket is open, even when the phone or lease
is absent, and is disabled before `ready` and after close. Mode and button commands are sent
only while control access is held. STOP also invalidates the scalar control-access epoch and drops
pending slider frames before its emergency frame; a fresh slider `input` starts a new epoch and
remains usable.

Mode changes and fullscreen changes use the shared `data-ui-transition="crossfade"`
marker. JavaScript clears and replaces the previous timer, and CSS animates only opacity
for that bounded marker. Under `prefers-reduced-motion: reduce`, the pulse remains
static, press transforms are removed, and the bounded opacity crossfade is retained with
a linear timing function; the live pad itself uses `transition:none!important` and its marker
follows the finger 1:1 (only the release glide is animated, and not under reduced motion).

Remote video starts muted and only enables audio through a visible per-view `Activer le son`
control. `Pause vidéo`/`Lire la vidéo` and `Fermer la vidéo`/`Reprendre la vidéo` are
media-only controls; closing or reopening the video never sends a motor STOP (reopening sends an
`offer_request` to each listed camera). Fullscreen traps Tab within the focused view's controls
and STOP, and Escape returns focus to that view's fullscreen control.

The phone's own sliders set maxima (an envelope); every browser slider is bounded by the
phone. The host sends a strict `{type:"limits", speedMax, depthMin, depthMax}` with optional
`sensationMin` and `sensationMax` (all fractions 0..1, `depthMin <= depthMax`). Sensation keys
are either both present (`sensationMin <= sensationMax`: the band the phone allows around
neutral, `[0.5 − d, 0.5 + d]` for a phone intensity `d = |s − 0.5|`), or `sensationMax` alone
(legacy APK 2.3.14/2.3.15 frame, still accepted and treated as the old ceiling
`[0, sensationMax]`), or both absent (older 4-key frame). `sensationMin` alone, an inverted band
or any other key is `invalid_message`. The broker relays it host to controller only, replays
the last one exactly as published to a returning controller and forgets it on revocation or
expiry. `#phone-limits` shows "Maximums du téléphone : en attente" until the first message,
then "Vitesse max X % · Profondeur a–b %", with "· Sensation ±D (a…b)" inserted on the signed
−100…+100 scale when a sensation bound is known (D is the larger magnitude of the two ends).
The speed slider is bounded to `0..speedMax`, the sensation slider to
`sensationMin..sensationMax` (rounded inwards to whole percent; `0..sensationMax` for the legacy
frame; unbounded `0..100` when absent), and both depth sliders to `depthMin..depthMax`
(`min`/`max` attributes, a `limit-badge` marker under each bounded slider — "intensité max ±D"
for a symmetric sensation band, "plage a…b" otherwise — and an accessible name). A change re-clamps displayed values without emitting any
motion command. Sliders are real time: while the user drags (`input`), each slider sends its
value at once and then at most one frame per 80 ms (leading + trailing throttle, one pending value
per slider); a successful release (`change` or `pointerup`) cancels the pending frame and sends the
final value if it differs from the last one sent. A browser `pointercancel` cancels the slider
gesture and drops its pending trailing value without sending a final command. The two
depth sliders cannot cross: moving one past the other pushes it along (the phone rejects an
inverted `set_depth`, so the envelope is always sent ordered), and a re-clamp keeps
`minimum <= maximum`. A `limits` frame rewrites `min`/`max` only when they change (widening first),
so a relayed identical frame never disturbs the slider under the finger. Nothing leaves without control access or without a
user `input` on that slider in the current control-access epoch; losing the socket, lease or phone
starts a new epoch and cancels pending frames; a mode change uses the same epoch boundary, so no
slider value from the previous mode can be sent after a delayed trailing callback or stale
`change`/`pointerup`. No slider value is ever re-sent when control returns, and reconnection,
`ready`, presence or a replayed `limits` never emits a motion command by itself. Each frame takes
the next monotonic `seq`. Streamed frames are summarised as one log entry per release. `speedMax = 0` shows "Le
téléphone est à 0 % : réglez un maximum sur le téléphone" and disables the speed slider;
STOP is never affected. The diagnostic frame also accepts `remote_lease_invalid`,
`remote_session_not_started`, `remote_machine_not_ready`, `remote_sequence_stale` and
`remote_live_position_rejected`, each shown in plain French in the phone diagnostic area.

A closed WebSocket reconnects automatically (1 s doubling to a 15 s cap, at most 30
attempts, `RECONNEXION` chip); motor controls stay disabled until the new socket reports
`ready` and presence. A socket that closes before `ready` is diagnosed with the keyless
`GET /api/session/:id/status` probe (`410` = expired); an expired or unknown session shows
"Session expirée ou introuvable — demandez un nouveau lien au téléphone" and stops retrying.
While the browser camera permission prompt is open the camera role shows
`AUTORISATION CAMÉRA` instead of the idle "Caméra et voix désactivées" copy.

Implemented status/error branches include missing session key, WebSocket connection and
offline, waiting for the phone, active controller, lost controller lease, camera ready,
camera permission denied, unsupported media, blocked playback, connected media, and broker
errors. Each branch updates the status chip, explanatory copy, or media surface in place;
no routine alert/modal is required.

## 5. Accessibility and diagnostics

Native buttons, select, ranges, video, and `<details>/<summary>` remain the interaction
primitives. `:focus-visible` applies the shared 3px aqua ring to buttons, links, summary,
select, and range inputs. Controls retain 44px minimum targets, visible text labels, and
shape/weight treatments for `aria-pressed="true"`; colour is not the only pressed or
disabled cue. On fine pointers, the wordmark has an aqua underline on hover and active
press (`text-decoration-thickness:2px`, `text-underline-offset:3px`), while native range
thumbs acknowledge hover and press with a 4px `--pulse-ring` halo in both WebKit and
Mozilla selectors. The Direct pad uses semantic `grab`/`grabbing` cursors while active;
disabled ranges use `cursor:not-allowed`, the disabled accent, and remain opaque. Safe-area
padding protects the fullscreen STOP on compact screens.

The technical report exposes browser, origin, redacted session/key presence, role, phone
presence, controller lease, participant presence, WebSocket state, last event, app
diagnostic, recent events, and a copy/download action. Session/key values, commands,
media, IP addresses, and device identifiers are omitted from the safe export; only the
origin hostname is retained. The export also has a `media` block: `mediaDevicesPresent`,
`getUserMediaPresent`, `secureContext`, the camera `captureMode`, the last capture/voice
DOMException names, the camera-switch result, and `enumerateDevices` counts (`videoinput`,
`audioinput`, `afterCaptureAttempt`) without labels or device ids. Visible diagnostic timestamps are formatted for `fr-CA`,
while the export retains machine-readable timestamps.

## 6. Accepted boundaries

The media path is browser camera role (one or more, up to 4) -> Worker/Durable Object
signaling -> browser controller WebRTC, one peer connection per camera. The Android host participates in control/signaling and has no native
camera producer in this web surface. This document therefore makes no claim about native
Android WebRTC, physical camera hardware, NAT traversal, a production Worker, deployment,
or a physical motor.

The control channel remains independent from media signaling. Session authorization,
controller leasing, sequence monotonicity, and the 60-minute session/heartbeat policy
remain broker contracts; this design document does not replace their source tests or
deployment evidence. A peer removed for a late heartbeat now has its socket closed (code 4000), and
a frame from a peer the broker already removed closes its socket too, so the client reconnects
instead of lingering unseen in presence.
