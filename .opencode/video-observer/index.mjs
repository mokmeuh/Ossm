import { mkdir, writeFile } from "node:fs/promises";
import { spawn } from "node:child_process";
import path from "node:path";
import { tool } from "@opencode-ai/plugin/tool";
import { analyzeFrameDirectory } from "./motion.mjs";

const DEFAULT_CAPTURE_ROOT = "captures";

class VideoObserverError extends Error {
  constructor(message, cause) {
    super(message);
    this.name = "VideoObserverError";
    if (cause instanceof Error) {
      this.cause = cause;
    }
  }
}

export const OssmVideoObserverPlugin = async ({ worktree }) => {
  const captureRoot = path.resolve(worktree, DEFAULT_CAPTURE_ROOT, "ossm-video");

  return {
    tool: {
      ossm_video_probe: tool({
        description: "Capture a short live ADB screen sequence and analyze the visible motion.",
        args: {
          adbPath: tool.schema.string().default("adb").describe("ADB executable path or name"),
          serial: tool.schema.string().optional().describe("ADB serial; default picks the first connected device"),
          durationMs: tool.schema.number().int().min(1000).max(30000).default(4000).describe("Capture duration in milliseconds"),
          intervalMs: tool.schema.number().int().min(100).max(5000).default(500).describe("Delay between captures in milliseconds"),
          sampleStride: tool.schema.number().int().min(1).max(16).default(4).describe("Pixel stride for motion analysis"),
          label: tool.schema.string().min(1).max(80).default("live").describe("Capture label used in the output directory"),
        },
        async execute(args, context) {
          const runDir = await captureSequence(captureRoot, args, context.abort);
          const summary = await analyzeFrameDirectory(runDir, {
            sampleStride: args.sampleStride,
            sampleIntervalMs: args.intervalMs,
          });
          await writeReport(runDir, summary);
          return {
            title: "OSSM live video probe",
            output: buildProbeOutput(runDir, summary),
            metadata: {
              captureDir: runDir,
              frameCount: summary.frameCount,
              hasMotion: summary.hasMotion,
              peakTimeMs: summary.peakTimeMs,
              dominantBand: summary.dominantBand,
            },
          };
        },
      }),
      ossm_video_analyze_frames: tool({
        description: "Analyze a directory of PNG frames as a video sequence.",
        args: {
          framesDir: tool.schema.string().min(1).describe("Directory containing PNG frames"),
          sampleStride: tool.schema.number().int().min(1).max(16).default(4).describe("Pixel stride for motion analysis"),
          sampleIntervalMs: tool.schema.number().int().min(1).max(10000).default(500).describe("Time delta between frames in milliseconds"),
        },
        async execute(args) {
          const framesDir = path.resolve(args.framesDir);
          const summary = await analyzeFrameDirectory(framesDir, {
            sampleStride: args.sampleStride,
            sampleIntervalMs: args.sampleIntervalMs,
          });
          return {
            title: "OSSM frame analysis",
            output: buildAnalysisOutput(framesDir, summary),
            metadata: {
              frameCount: summary.frameCount,
              hasMotion: summary.hasMotion,
              peakTimeMs: summary.peakTimeMs,
              dominantBand: summary.dominantBand,
            },
          };
        },
      }),
    },
  };
};

export default OssmVideoObserverPlugin;

async function captureSequence(captureRoot, args, abortSignal) {
  const serial = await resolveSerial(args.adbPath, args.serial, abortSignal);
  const runDir = path.join(captureRoot, buildRunName(args.label));
  await mkdir(runDir, { recursive: true });

  const frameCount = Math.max(2, Math.floor(args.durationMs / args.intervalMs) + 1);
  const frames = [];
  const startedAt = Date.now();

  for (let index = 0; index < frameCount; index += 1) {
    abortIfRequested(abortSignal);
    const frameName = `frame-${String(index).padStart(4, "0")}.png`;
    const framePath = path.join(runDir, frameName);
    const png = await captureFrame(args.adbPath, serial, abortSignal);
    await writeFile(framePath, png);
    frames.push({
      frameName,
      framePath,
      capturedAtMs: Date.now() - startedAt,
    });
    if (index < frameCount - 1) {
      await sleep(args.intervalMs, abortSignal);
    }
  }

  const manifest = {
    serial,
    adbPath: args.adbPath,
    durationMs: args.durationMs,
    intervalMs: args.intervalMs,
    frameCount: frames.length,
    frames,
    capturedAt: new Date().toISOString(),
  };
  await writeFile(path.join(runDir, "manifest.json"), `${JSON.stringify(manifest, null, 2)}\n`);
  return runDir;
}

async function resolveSerial(adbPath, explicitSerial, abortSignal) {
  if (explicitSerial) {
    return explicitSerial;
  }
  const { stdout } = await runCommand(adbPath, ["devices", "-l"], abortSignal);
  const devices = stdout
    .toString("utf8")
    .split(/\r?\n/)
    .slice(1)
    .map((line) => line.trim())
    .filter(Boolean)
    .map(parseDeviceLine)
    .filter((device) => device.state === "device");
  if (devices.length === 0) {
    throw new VideoObserverError("No connected ADB device in 'device' state.");
  }
  return devices[0].serial;
}

function parseDeviceLine(line) {
  const [serial, state] = line.split(/\s+/, 3);
  return { serial, state: state ?? "unknown" };
}

async function captureFrame(adbPath, serial, abortSignal) {
  const { stdout } = await runCommand(adbPath, ["-s", serial, "exec-out", "screencap", "-p"], abortSignal);
  if (stdout.length === 0) {
    throw new VideoObserverError("ADB returned an empty screenshot.");
  }
  return stdout;
}

async function writeReport(runDir, summary) {
  await writeFile(path.join(runDir, "report.json"), `${JSON.stringify(summary, null, 2)}\n`);
  await writeFile(path.join(runDir, "report.md"), buildReportMarkdown(runDir, summary));
}

function buildProbeOutput(runDir, summary) {
  return [
    `Captured ${summary.frameCount} frames into ${runDir}.`,
    `Motion: ${summary.hasMotion ? "yes" : "no"}.`,
    `Peak time: ${summary.peakTimeMs} ms.`,
    `Dominant band: ${summary.dominantBand}.`,
    `Report: ${path.join(runDir, "report.md")}`,
  ].join("\n");
}

function buildAnalysisOutput(framesDir, summary) {
  return [
    `Analyzed ${summary.frameCount} frames from ${framesDir}.`,
    `Motion: ${summary.hasMotion ? "yes" : "no"}.`,
    `Peak time: ${summary.peakTimeMs} ms.`,
    `Dominant band: ${summary.dominantBand}.`,
  ].join("\n");
}

function buildReportMarkdown(runDir, summary) {
  const lines = [
    "# OSSM live video probe",
    "",
    `- Frames: ${summary.frameCount}`,
    `- Motion: ${summary.hasMotion ? "yes" : "no"}`,
    `- Peak time: ${summary.peakTimeMs} ms`,
    `- Peak signal: ${summary.peakSignal}`,
    `- Dominant band: ${summary.dominantBand}`,
    "",
    "## Frame sequence",
  ];
  for (const frame of summary.frames) {
    lines.push("", `### ${frame.name}`, `- Time: ${frame.timeMs} ms`, `- Path: ${path.join(runDir, frame.name)}`);
  }
  return `${lines.join("\n")}\n`;
}

function buildRunName(label) {
  const stamp = new Date().toISOString().replace(/[:.]/g, "-");
  const safeLabel = label.replace(/[^a-z0-9-_]+/gi, "-").replace(/^-+|-+$/g, "").toLowerCase() || "run";
  return `${stamp}-${safeLabel}`;
}

function sleep(ms, abortSignal) {
  return new Promise((resolve, reject) => {
    const onAbort = () => {
      clearTimeout(timer);
      abortSignal.removeEventListener("abort", onAbort);
      reject(new VideoObserverError("Capture aborted."));
    };
    const timer = setTimeout(() => {
      abortSignal.removeEventListener("abort", onAbort);
      resolve();
    }, ms);
    abortSignal.addEventListener("abort", onAbort, { once: true });
  });
}

function abortIfRequested(abortSignal) {
  if (abortSignal.aborted) {
    throw new VideoObserverError("Capture aborted.");
  }
}

async function runCommand(command, args, abortSignal) {
  return new Promise((resolve, reject) => {
    const child = spawn(command, args, {
      stdio: ["ignore", "pipe", "pipe"],
      windowsHide: true,
    });
    const stdout = [];
    const stderr = [];
    const onAbort = () => {
      child.kill();
      reject(new VideoObserverError(`${command} aborted.`));
    };
    abortSignal.addEventListener("abort", onAbort, { once: true });
    child.stdout.on("data", (chunk) => stdout.push(chunk));
    child.stderr.on("data", (chunk) => stderr.push(chunk));
    child.on("error", (error) => {
      abortSignal.removeEventListener("abort", onAbort);
      reject(new VideoObserverError(`${command} failed to start.`, error));
    });
    child.on("close", (code) => {
      abortSignal.removeEventListener("abort", onAbort);
      if (code === 0) {
        resolve({ stdout: Buffer.concat(stdout), stderr: Buffer.concat(stderr) });
        return;
      }
      const message = Buffer.concat(stderr).toString("utf8").trim() || `${command} exited with code ${code}`;
      reject(new VideoObserverError(message));
    });
  });
}
