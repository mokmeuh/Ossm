# OSSM video observer

OpenCode plugin and MCP server that capture short visual sequences and turn them into frame-based video probes.

## Tools

- `ossm_video_probe`
- `ossm_video_analyze_frames`

## MCP tools

- `ossm_video_status`: reports `ffmpeg`, ADB, and WSL `/dev/video*` availability.
- `ossm_adb_screen_probe`: captures Android screen frames over ADB and summarizes visible motion.
- `ossm_webcam_probe`: captures frames from a WSL video device such as `/dev/video0`.
- `ossm_video_analyze_file`: extracts frames from a local video file with `ffmpeg` and analyzes motion.

## Output

Each probe writes a run directory under `captures/ossm-video/` with:

- `manifest.json`
- `report.json`
- `report.md`
- `frame-0000.png`, `frame-0001.png`, ...

## Intended use

Ask OpenCode to probe the screen after a change, then inspect the captured frame sequence and motion summary.

For the webcam path, Windows must first attach the USB camera to WSL with `usbipd`. After that, `ossm_video_status` should show `/dev/video0` and `ossm_webcam_probe` can capture from it.
