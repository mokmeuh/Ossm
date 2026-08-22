import assert from "node:assert/strict";
import { mkdtemp, writeFile, rm, readFile } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { OssmVideoObserverPlugin } from "./index.mjs";
import { decodePng } from "./png.mjs";
import { analyzeFrameDirectory, analyzeVideoFile } from "./motion.mjs";

const ONE_BY_ONE_PNG = Buffer.from(
  "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO5nYFEAAAAASUVORK5CYII=",
  "base64",
);

await run();

async function run() {
  const plugin = await OssmVideoObserverPlugin({ worktree: process.cwd() });
  assert.ok(plugin.tool.ossm_video_probe);
  assert.ok(plugin.tool.ossm_video_analyze_frames);

  const decoded = decodePng(ONE_BY_ONE_PNG);
  assert.equal(decoded.width, 1);
  assert.equal(decoded.height, 1);
  assert.equal(decoded.gray.length, 1);

  const tempDir = await mkdtemp(path.join(os.tmpdir(), "ossm-video-observer-"));
  try {
    const sourceA = path.resolve(process.cwd(), "ossm_diag_trial.png");
    const sourceB = path.resolve(process.cwd(), "ossm_live_fix.png");
    await writeFile(path.join(tempDir, "frame-0000.png"), await readFile(sourceA));
    await writeFile(path.join(tempDir, "frame-0001.png"), await readFile(sourceB));

    const summary = await analyzeFrameDirectory(tempDir, {
      sampleStride: 8,
      sampleIntervalMs: 250,
    });

    assert.equal(summary.frameCount, 2);
    assert.ok(summary.samples.length >= 1);
    assert.ok(["top", "middle", "bottom"].includes(summary.dominantBand));

    // Given: the recorded task-3 webcam sequence; When: ffmpeg extracts sampled frames.
    const videoOutput = path.join(tempDir, "video-frames");
    const videoSummary = await analyzeVideoFile(
      path.join(process.cwd(), ".omo/evidence/start-work/resistance-adaptive-intensity/a1/task-3/execution-20260821-usb-camera/hp-webcam-3100-20260821T201104Z-mjpeg-640x480-30fps.mp4"),
      videoOutput,
      { sampleStride: 8, sampleIntervalMs: 1000, maxFrames: 12 },
    );

    // Then: the process starts and produces a multi-frame temporal summary.
    assert.ok(videoSummary.frameCount >= 2);
    assert.ok(videoSummary.samples.length >= 1);
  } finally {
    await rm(tempDir, { recursive: true, force: true });
  }
}
