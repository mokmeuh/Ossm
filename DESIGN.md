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
- Live mode uses a release-to-move position slider. Dragging previews locally; release sends one stream command.
- Pattern selection uses a fixed small opener on the left side of the control title. Opening it replaces the visible control surface with one picker overlay; the picker uses a vertical list and closes after selection or dismissal. The opener is not draggable.
- Safety settings expose persisted depth-limit sliders and explicit microphone targets for speed, depth, and sensation within the existing dialog.

## Accessibility And Safety

- Pattern names stay on one line with ellipsis; the full label remains available to accessibility services.
- Connection status is not shown in the Control header. Safety, calibration, and error messages remain visible.
- Swipe navigation between app sections is disabled in the main pager; bottom navigation remains the explicit route control.
