import { readdir, readFile } from "node:fs/promises";
import { spawn } from "node:child_process";
import path from "node:path";
import { decodePng } from "./png.mjs";

export async function analyzeFrameDirectory(framesDir, options) {
  const entries = (await readdir(framesDir, { withFileTypes: true }))
    .filter((entry) => entry.isFile() && entry.name.toLowerCase().endsWith(".png"))
    .map((entry) => entry.name)
    .sort();
  if (entries.length < 2) {
    throw new Error("Need at least two PNG frames to analyze motion.");
  }

  const frames = [];
  for (let index = 0; index < entries.length; index += 1) {
    const name = entries[index];
    const filePath = path.join(framesDir, name);
    const png = decodePng(await readFile(filePath));
    frames.push({
      name,
      path: filePath,
      timeMs: index * options.sampleIntervalMs,
      gray: png.gray,
      width: png.width,
      height: png.height,
    });
  }
  return analyzeFrames(frames, options);
}

export async function analyzeVideoFile(videoPath, outputDir, options) {
  const { mkdir } = await import("node:fs/promises");
  await mkdir(outputDir, { recursive: true });
  const fps = Math.max(1, Math.min(10, Math.round(1000 / options.sampleIntervalMs)));
  await runProcess("ffmpeg", [
    "-hide_banner",
    "-loglevel",
    "error",
    "-y",
    "-i",
    videoPath,
    "-vf",
    `fps=${fps}`,
    "-frames:v",
    String(options.maxFrames ?? 120),
    "frame-%04d.png",
  ], outputDir);
  return analyzeFrameDirectory(outputDir, options);
}

async function runProcess(command, args, cwd) {
  return new Promise((resolve, reject) => {
    const child = spawn(command, args, {
      cwd,
      stdio: ["ignore", "ignore", "pipe"],
      windowsHide: true,
    });
    const stderr = [];
    child.stderr.on("data", (chunk) => stderr.push(chunk));
    child.on("error", reject);
    child.on("close", (code) => {
      if (code === 0) {
        resolve();
        return;
      }
      reject(new Error(Buffer.concat(stderr).toString("utf8").trim() || `${command} exited with code ${code}`));
    });
  });
}

function analyzeFrames(frames, options) {
  const sampleStride = options.sampleStride ?? 4;
  const pixelDiffThreshold = options.pixelDiffThreshold ?? 14;
  const smoothingWindow = options.smoothingWindow ?? 3;
  const minActionGapMs = options.minActionGapMs ?? 140;
  const minActionAmplitude = options.minActionAmplitude ?? 8;

  const raw = [];
  const times = [];
  let prev = frames[0].gray;

  for (let index = 1; index < frames.length; index += 1) {
    const frame = frames[index];
    const motion = measureMotion(prev, frame.gray, frame.width, frame.height, sampleStride, pixelDiffThreshold);
    raw.push(motion.centroidY);
    times.push(frame.timeMs);
    prev = frame.gray;
  }

  const filled = fillGaps(raw);
  const smoothed = movingAverage(filled, smoothingWindow);
  const normalized = normalize(smoothed);
  const markers = extremaToMarkers(times, normalized, minActionGapMs, minActionAmplitude);
  const peak = markers.reduce((best, item) => (item.signal > best.signal ? item : best), markers[0]);
  const average = averageValue(normalized);
  const motionRange = normalized.length > 0 ? Math.max(...normalized) - Math.min(...normalized) : 0;

  return {
    frameCount: frames.length,
    frames: frames.map((frame) => ({
      name: frame.name,
      path: frame.path,
      timeMs: frame.timeMs,
    })),
    sampleStride,
    hasMotion: motionRange >= 5,
    peakTimeMs: peak?.timeMs ?? 0,
    peakSignal: peak?.signal ?? 0,
    dominantBand: classifyBand(average),
    motionRange,
    samples: normalized,
    markers,
  };
}

function measureMotion(previous, current, width, height, stride, threshold) {
  let sumWeight = 0;
  let sumWeightedY = 0;
  for (let y = 0; y < height; y += stride) {
    for (let x = 0; x < width; x += stride) {
      const index = y * width + x;
      const diff = Math.abs(current[index] - previous[index]);
      if (diff > threshold) {
        sumWeight += diff;
        sumWeightedY += diff * y;
      }
    }
  }
  return {
    centroidY: sumWeight > 0 ? sumWeightedY / sumWeight / Math.max(1, height - 1) : Number.NaN,
    energy: sumWeight,
  };
}

function fillGaps(values) {
  const output = new Array(values.length);
  let last = values.find((value) => Number.isFinite(value)) ?? 0.5;
  for (let index = 0; index < values.length; index += 1) {
    const value = values[index];
    if (Number.isFinite(value)) {
      output[index] = value;
      last = value;
    } else {
      output[index] = last;
    }
  }
  return output;
}

function movingAverage(values, windowSize) {
  if (windowSize <= 1) {
    return values.slice();
  }
  const output = new Array(values.length);
  const half = Math.floor(windowSize / 2);
  for (let index = 0; index < values.length; index += 1) {
    let sum = 0;
    let count = 0;
    for (let offset = -half; offset <= half; offset += 1) {
      const sampleIndex = index + offset;
      if (sampleIndex >= 0 && sampleIndex < values.length) {
        sum += values[sampleIndex];
        count += 1;
      }
    }
    output[index] = sum / count;
  }
  return output;
}

function normalize(values) {
  if (values.length === 0) {
    return [];
  }
  const sorted = values.slice().sort((left, right) => left - right);
  const low = sorted[Math.max(0, Math.min(sorted.length - 1, Math.floor(sorted.length * 0.05)))];
  const high = sorted[Math.max(0, Math.min(sorted.length - 1, Math.floor(sorted.length * 0.95)))];
  const range = high - low;
  return values.map((value) => {
    const normalized = range > 1e-6 ? (value - low) / range : 0.5;
    return Math.max(0, Math.min(100, Math.round(normalized * 100)));
  });
}

function extremaToMarkers(times, values, minActionGapMs, minActionAmplitude) {
  if (values.length === 0) {
    return [];
  }
  const markers = [{
    timeMs: 0,
    signal: values[0],
    kind: "start",
  }];

  let lastIndex = 0;
  for (let index = 1; index < values.length - 1; index += 1) {
    const prev = values[index - 1];
    const current = values[index];
    const next = values[index + 1];
    const isPeak = current >= prev && current > next;
    const isTrough = current <= prev && current < next;
    if (!isPeak && !isTrough) {
      continue;
    }
    const gap = times[index] - times[lastIndex];
    const amplitude = Math.abs(current - values[lastIndex]);
    if (gap >= minActionGapMs && amplitude >= minActionAmplitude) {
      markers.push({
        timeMs: times[index],
        signal: current,
        kind: isPeak ? "peak" : "trough",
      });
      lastIndex = index;
    }
  }

  const lastIndexInSequence = values.length - 1;
  if (times[lastIndexInSequence] > times[lastIndex]) {
    markers.push({
      timeMs: times[lastIndexInSequence],
      signal: values[lastIndexInSequence],
      kind: "end",
    });
  }
  return markers;
}

function averageValue(values) {
  if (values.length === 0) {
    return 50;
  }
  return values.reduce((sum, value) => sum + value, 0) / values.length;
}

function classifyBand(value) {
  if (value < 33) {
    return "top";
  }
  if (value < 66) {
    return "middle";
  }
  return "bottom";
}
