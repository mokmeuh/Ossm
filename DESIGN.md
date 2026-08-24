# OSSM Control Design Contract

## Scope

This contract covers the Android Compose control surface only. Preserve the existing dark glass visual language and avoid a broad redesign of Scan, Profiles, Diagnostics, Funscript, or Video Sync.

## Tokens

- Background: vertical `OssmBackground` to `OssmSecondary` gradient.
- Cards: `GlassCard` glass fill with `OssmGlass` and `OssmGlassBorder`.
- Primary controls: `OssmPrimary` and `OssmPrimaryLight`.
- Accent controls: `OssmAccent`.
- Warning/state controls: `OssmWarning`, `OssmConnected`, and `OssmError`.
- Text: `OssmOnSurface`, bold uppercase labels at 11-12sp, body copy at 12-14sp.

## Control Primitives

- Outside Live, STOP remains the dominant protected 110dp action and must never be covered by the pattern bubble. Live exposes no Stop or Pause action because finger release is its stop mechanism.
- Pause/play retains its 80dp circular footprint outside Live. Settings uses the existing glass/border tokens in a 40dp square target inside the active control-card header.
- The control-card header renders only the active mode name. Its opener, weighted title, settings, and optional Save action occupy one non-overlapping row; Save remains absent in Live.
- Sensation is one scalar slider from 0% to 100%, with neutral labeling and matching +/- controls.
- Live mode uses a large vertical real-time control surface. The finger position is the target while contact is active; release or cancellation sends Stop only and never commits a final target. UI callbacks expose positions only; transport cadence and actuator speed remain outside the UI layer.
- The settings gear exposes Precision for the active pattern. Overrides are keyed independently by pattern, default on outside Live, and default off for Live so changing Live never changes Simple Stroke or another mode. In Live only, the same dialog also exposes the existing invert-direction switch; the Live card never renders a standalone invert row.
- Pattern selection uses a fixed small opener on the left side of the control title. Opening it replaces the visible control surface with one picker overlay; the picker uses a vertical list and closes after selection or dismissal. The opener is not draggable.
- Safety settings expose persisted depth-limit sliders and explicit microphone targets for speed, depth, and sensation within the existing dialog.

## Compact Active Layout

- The active control surface never owns vertical scrolling. Pattern pickers and dialogs may scroll internally, but the STOP rail and active control card remain fixed in the available window.
- Portrait uses a compact 110dp top action rail outside Live: pause/play retains at least a 56dp effective target while STOP remains the dominant protected 110dp action. Live removes this rail entirely so the active control card uses the available height.
- Landscape uses a narrow 112dp side action rail with STOP and pause/play outside Live. Live removes this rail entirely; otherwise STOP stays fully visible, above all overlays, and is never covered by the pattern picker.
- The Moto G acceptance bounds are the real app content area after system bars and bottom navigation. The Live card takes the flexible remaining height while retaining comfortable outer margins and leaving the header, settings gear, and bottom navigation visible. All active Live information, including readiness feedback, value, fine range, and endpoint labels, must fit without a main-surface swipe in both orientations.
- Compact cards use the existing `OssmBackground`/`OssmSecondary`, `GlassCard`, border, and accent tokens. The redesign does not introduce a second material language or generic component system.

## Precision Scalar Slider

- `PrecisionScalarSlider` is the reusable gesture and state primitive for scalar setting controls only. Its effective horizontal interaction region is at least 56dp high while its visual track remains 6dp and its thumb remains visually compact.
- Pointer down anywhere in the enlarged region preserves the current value. Horizontal movement is relative to the pointer-down value; a tap or vertical-only movement never maps to an absolute track position.
- Movement up to 8dp from the last stationary anchor is treated as normal finger jitter. Movement beyond 8dp updates the value and restarts the current level's 1500ms stationary clock.
- Global state maps the full track to `0..100`. Each completed 1500ms stationary interval activates exactly one centered level around the finger's current mapped value: `+/-30`, then `+/-20`, then `+/-10`, then `+/-5` percentage points.
- In a precision level, horizontal displacement is relative to the value captured when that level begins and is scaled across that level's displayed range. Meaningful movement retains the reached level/range while dragging, makes the new mapped value the center candidate for the next level, and restarts the 1500ms timer.
- Near 0 or 100, a centered window shifts inside the global bounds to preserve as much of its intended span as possible. Visible feedback names the active level and exact integer range; French and English resources carry equivalent status and accessibility wording.
- Entering each precision level emits one `TextHandleMove` haptic. Recomposition or stationary timer ticks do not repeat haptics.
- Successful pointer release commits exactly once and immediately returns visual feedback to `0..100`. Pointer cancellation, disabled-state transition, or disposal cancels without commit or ghost callback. A fresh gesture after orientation change starts from the latest external value.
- Existing +/- controls remain available and preserve their guarded incremental/ramp behavior; their effective target is at least 56dp.

## Live Position Slider

- Live owns a dedicated vertical control surface with a minimum `420dp x 280dp` portrait footprint and `180dp x 280dp` compact-landscape footprint, then expands to consume the remaining card height. The vertical track is 18dp/16dp and the thumb radius is 28dp/22dp. The full surface is interactive, not only the visual rail.
- Vertical mapping is absolute and direct: the bottom edge is Home/0, the top edge is Fond/100, and pointer-down immediately emits the touched position. Every vertical move beyond the 8px jitter threshold emits its new target while the finger remains down.
- Release and pointer cancellation follow exactly the same path: reset the transient precision range and send Stop. Neither path sends, validates, commits, or replays a final position.
- Live precision narrowing is opt-in and disabled by default. With Precision disabled, stationary time never changes the global `0-100` range; real-time vertical control and release-to-stop behavior remain unchanged.
- While vertical movement continues, Live never zooms or re-ranges. A cumulative 8px displacement from the stationary anchor counts as movement, updates the target immediately, and restarts the stationary timer.
- The first 2000ms continuously stationary always activates `10-90`. Each further uninterrupted 2000ms interval reduces the current half-span by 10 points and recenters on the finger's current mapped value: half-span `30`, then `20`, then `10`, then the safe minimum `5`.
- For example, after `10-90`, moving to 40 and holding produces `10-70`; the next level centers the then-current value with half-span 20. Near global endpoints, the window shifts inside `0..100` to preserve its intended span.
- Narrowing retains the current target and resets the relative grab origin at the current finger position. Subsequent vertical displacement maps linearly across the current range span, so no stationary transition or resumed movement creates an absolute-position jump.
- Resumed movement restarts the 2000ms timer but retains the narrowed range. Each newly entered range emits one `TextHandleMove` haptic and displays its exact low/high labels.
- Release, pointer cancellation, disabled-state transition, disposal, or orientation change returns the visual range to `0-100` and requests Stop without a position callback.

## Accessibility Constraints

- Slider semantics expose the current value, active precision level/range, enabled state, and a progress action. The semantics bounds cover the full 56dp interaction region, not only the visual track.
- Toggle and checkbox rows expose a minimum 56dp effective height and one coherent label/state target; descriptive copy must not create a second ambiguous click target.
- Compact layouts must preserve readable 11sp minimum labels, single-line pattern names with ellipsis, sufficient contrast from existing tokens, and logical TalkBack traversal with STOP before the active control card.
- Precision feedback is not color-only. Text and one haptic per entered level communicate the state; cancellation and release reset are announced through the returned global-range description.

## Accepted Debt

- Deterministic state and layout-contract tests cover this task. Per the user-owned QA boundary, rendered Moto G portrait/landscape appearance, physical thumb reach, system-bar insets, font scaling above 1.0, and TalkBack traversal remain device-level acceptance.
- Range sliders in safety dialogs keep their existing Material gesture behavior in this slice. The progressive primitive applies to scalar sliders; dual-thumb range editing requires a separate ambiguity and safety design before migration.

## Accessibility And Safety

- Pattern names stay on one line with ellipsis; the full label remains available to accessibility services.
- Connection status is not shown in the Control header. Safety, calibration, and error messages remain visible.
- Swipe navigation between app sections is disabled in the main pager; bottom navigation remains the explicit route control.
