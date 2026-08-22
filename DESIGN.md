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

- STOP remains the dominant protected 110dp action and must never be covered by the pattern bubble.
- Pause/play and settings use matching 80dp circular footprints.
- Save is a small square action on the right side of the control header because it saves control settings.
- Sensation is one scalar slider from 0% to 100%, with neutral labeling and matching +/- controls.
- Live mode uses a continuous-preview, release-to-commit position slider. UI callbacks expose positions only; transport cadence and actuator speed remain outside the UI layer.
- Pattern selection uses a fixed small opener on the left side of the control title. Opening it replaces the visible control surface with one picker overlay; the picker uses a vertical list and closes after selection or dismissal. The opener is not draggable.
- Safety settings expose persisted depth-limit sliders and explicit microphone targets for speed, depth, and sensation within the existing dialog.

## Compact Active Layout

- The active control surface never owns vertical scrolling. Pattern pickers and dialogs may scroll internally, but the STOP rail and active control card remain fixed in the available window.
- Portrait uses a compact 110dp top action rail: settings and pause/play retain at least 56dp effective targets while STOP remains the dominant protected 110dp action. The active control card consumes the remaining height budget and reduces decorative spacing before hiding safety or status information.
- Landscape uses a narrow 112dp side action rail with STOP, settings, and pause/play stacked beside one flexible control card. STOP stays fully visible, above all overlays, and is never covered by the pattern picker.
- The Moto G acceptance bounds are the real app content area after system bars and bottom navigation. All active Live information, including readiness feedback, value, fine range, and endpoint labels, must fit without a main-surface swipe in both orientations.
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

- Live does not use the centered scalar `+/-10` and `+/-5` levels. It owns a dedicated continuous-drag state model with a 56dp effective target and the same no-jump pointer-down guarantee.
- While horizontal movement continues, Live never zooms or re-ranges. A cumulative 8dp displacement from the stationary anchor counts as movement, updates the preview linearly, and restarts the stationary timer.
- The first 2000ms continuously stationary always activates `10-90`. Each further uninterrupted 2000ms interval reduces the current half-span by 10 points and recenters on the finger's current mapped value: half-span `30`, then `20`, then `10`, then the safe minimum `5`.
- For example, after `10-90`, moving to 40 and holding produces `10-70`; the next level centers the then-current value with half-span 20. Near global endpoints, the window shifts inside `0..100` to preserve its intended span.
- Narrowing retains the current target and resets the relative grab origin at the current finger position. Subsequent horizontal displacement maps linearly across the current range span, so no stationary transition or resumed movement creates an absolute-position jump.
- Resumed movement restarts the 2000ms timer but retains the narrowed range. Each newly entered range emits one `TextHandleMove` haptic and displays its exact low/high labels.
- Successful release commits exactly once. Release, pointer cancellation, disabled-state transition, disposal, or orientation change returns the visual range to `0-100`; cancellation never commits or ghost-sends.

## Accessibility Constraints

- Slider semantics expose the current value, active precision level/range, enabled state, and a progress action. The semantics bounds cover the full 56dp interaction region, not only the visual track.
- Toggle and checkbox rows expose a minimum 56dp effective height and one coherent label/state target; descriptive copy must not create a second ambiguous click target.
- Compact layouts must preserve readable 11sp minimum labels, single-line pattern names with ellipsis, sufficient contrast from existing tokens, and logical TalkBack traversal with STOP before the active control card.
- Precision feedback is not color-only. Text and one haptic per entered level communicate the state; cancellation and release reset are announced through the returned global-range description.

## Accepted Debt

- Deterministic Compose tests and preview captures cover layout contracts in this task, but physical thumb reach, system-bar insets, font scaling above 1.0, TalkBack traversal, and rendered Moto G portrait/landscape screenshots remain device-level acceptance for task 23.
- Range sliders in safety dialogs keep their existing Material gesture behavior in this slice. The progressive primitive applies to scalar sliders; dual-thumb range editing requires a separate ambiguity and safety design before migration.

## Accessibility And Safety

- Pattern names stay on one line with ellipsis; the full label remains available to accessibility services.
- Connection status is not shown in the Control header. Safety, calibration, and error messages remain visible.
- Swipe navigation between app sections is disabled in the main pager; bottom navigation remains the explicit route control.
