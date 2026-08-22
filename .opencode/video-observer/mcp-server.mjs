#!/usr/bin/env node
import { mkdir, readdir, writeFile } from "node:fs/promises";
import { spawn } from "node:child_process";
import path from "node:path";
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { z } from "zod";
import { analyzeFrameDirectory, analyzeVideoFile } from "./motion.mjs";

const ROOT = process.env.OSSM_VIDEO_ROOT || process.cwd();
const CAPTURE_ROOT = path.resolve(ROOT, "captures", "ossm-video");

const server = new McpServer({
  name: "ossm-video",
  version: "0.1.0",
});

server.registerTool(
  "ossm_video_status",
  {
    title: "OSSM video observer status",
    description: "Report ffmpeg, ADB, and WSL video device availability.",
    inputSchema: {},
  },
  async () => {
    const videoDevices = await listVideoDevices();
    const ffmpeg = await commandStatus("ffmpeg", ["-version"]);
    const adb = await commandStatus("adb", ["devices", "-l"]);

    return textResult([
      `root=${ROOT}`,
      `captureRoot=${CAPTURE_ROOT}`,
      `ffmpeg=${ffmpeg.ok ? "ok" : "missing"}`,
      `adb=${adb.ok ? "ok" : "missing"}`,
      `videoDevices=${videoDevices.length ? videoDevices.join(", ") : "none"}`,
    ].join("\n"));
  },
);

server.registerTool(
  "ossm_video_analyze_file",
  {
    title: "Analyze local video file",
    description: "Extract frames from a local video file with ffmpeg and summarize visible motion.",
    inputSchema: {
      videoPath: z.string().min(1),
      intervalMs: z.number().positive().default(500),
      sampleStride: z.number().int().positive().default(8),
      maxFrames: z.number().int().positive().default(120),
      label: z.string().default("video-file"),
    },
  },
  async (args) => {
    try {
      const runDir = path.join(CAPTURE_ROOT, runName(args.label));
      const summary = await analyzeVideoFile(path.resolve(ROOT, args.videoPath), runDir, {
        sampleIntervalMs: args.intervalMs,
        sampleStride: args.sampleStride,
        maxFrames: args.maxFrames,
      });
      await writeReport(runDir, summary);
      return textResult(summaryText(runDir, summary));
    } catch (error) {
      return toolError(error);
    }
  },
);

server.registerTool(
  "ossm_webcam_probe",
  {
    title: "Probe WSL webcam video",
    description: "Capture webcam frames from a WSL /dev/video device and summarize visible motion.",
    inputSchema: {
      device: z.string().default("/dev/video0"),
      durationMs: z.number().positive().default(3000),
      intervalMs: z.number().positive().default(500),
      sampleStride: z.number().int().positive().default(8),
      label: z.string().default("webcam"),
    },
  },
  async (args) => {
    try {
      const runDir = path.join(CAPTURE_ROOT, runName(args.label));
      await captureWebcamFrames(runDir, {
        device: args.device,
        durationMs: args.durationMs,
        intervalMs: args.intervalMs,
      });
      const summary = await analyzeFrameDirectory(runDir, {
        sampleIntervalMs: args.intervalMs,
        sampleStride: args.sampleStride,
      });
      await writeReport(runDir, summary);
      return textResult(summaryText(runDir, summary));
    } catch (error) {
      return toolError(error);
    }
  },
);

server.registerTool(
  "ossm_adb_screen_probe",
  {
    title: "Probe Android screen video",
    description: "Capture Android screen frames over ADB and summarize visible motion.",
    inputSchema: {
      adbPath: z.string().default("adb"),
      serial: z.string().optional(),
      durationMs: z.number().positive().default(3000),
      intervalMs: z.number().positive().default(500),
      sampleStride: z.number().int().positive().default(8),
      label: z.string().default("adb"),
    },
  },
  async (args) => {
    try {
      const runDir = path.join(CAPTURE_ROOT, runName(args.label));
      await captureAdbFrames(runDir, {
        adbPath: args.adbPath,
        serial: args.serial,
        durationMs: args.durationMs,
        intervalMs: args.intervalMs,
      });
      const summary = await analyzeFrameDirectory(runDir, {
        sampleIntervalMs: args.intervalMs,
        sampleStride: args.sampleStride,
      });
      await writeReport(runDir, summary);
      return textResult(summaryText(runDir, summary));
    } catch (error) {
      return toolError(error);
    }
  },
);

await server.connect(new StdioServerTransport());

async function captureWebcamFrames(runDir, options) {
  await mkdir(runDir, { recursive: true });
  const count = Math.max(2, Math.floor(options.durationMs / options.intervalMs) + 1);
  for (let index = 0; index < count; index += 1) {
    await runProcess("ffmpeg", [
      "-hide_banner",
      "-loglevel",
      "error",
      "-y",
      "-f",
      "v4l2",
      "-i",
      options.device,
      "-frames:v",
      "1",
      `frame-${String(index).padStart(4, "0")}.png`,
    ], runDir);
    if (index < count - 1) await delay(options.intervalMs);
  }
}

async function captureAdbFrames(runDir, options) {
  await mkdir(runDir, { recursive: true });
  const serial = options.serial ?? await firstAdbSerial(options.adbPath);
  const count = Math.max(2, Math.floor(options.durationMs / options.intervalMs) + 1);
  for (let index = 0; index < count; index += 1) {
    const { stdout } = await runBuffered(options.adbPath, ["-s", serial, "exec-out", "screencap", "-p"], ROOT);
    await writeFile(path.join(runDir, `frame-${String(index).padStart(4, "0")}.png`), stdout);
    if (index < count - 1) await delay(options.intervalMs);
  }
}

async function firstAdbSerial(adbPath) {
  const { stdout } = await runBuffered(adbPath, ["devices"], ROOT);
  const line = stdout.toString("utf8").split(/\r?\n/).find((item) => /\tdevice$/.test(item));
  if (!line) throw new Error("No ADB device in device state.");
  return line.split(/\s+/)[0];
}

async function writeReport(runDir, summary) {
  await writeFile(path.join(runDir, "report.json"), `${JSON.stringify(summary, null, 2)}\n`);
}

function summaryText(runDir, summary) {
  return [
    `captureDir=${runDir}`,
    `frames=${summary.frameCount}`,
    `motion=${summary.hasMotion ? "yes" : "no"}`,
    `peakTimeMs=${summary.peakTimeMs}`,
    `dominantBand=${summary.dominantBand}`,
    `motionRange=${summary.motionRange.toFixed(4)}`,
  ].join("\n");
}

async function listVideoDevices() {
  try {
    return (await readdir("/dev")).filter((name) => /^video\d+$/.test(name)).map((name) => `/dev/${name}`);
  } catch {
    return [];
  }
}

async function commandStatus(command, args) {
  try {
    await runProcess(command, args, ROOT);
    return { ok: true };
  } catch {
    return { ok: false };
  }
}

function textResult(text) {
  return { content: [{ type: "text", text }] };
}

function toolError(error) {
  return {
    isError: true,
    content: [{ type: "text", text: error instanceof Error ? error.message : String(error) }],
  };
}

function runName(label) {
  const safe = String(label).replace(/[^a-z0-9-_]+/gi, "-").replace(/^-+|-+$/g, "").toLowerCase() || "run";
  return `${new Date().toISOString().replace(/[:.]/g, "-")}-${safe}`;
}

function delay(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

async function runProcess(command, args, cwd) {
  await runBuffered(command, args, cwd);
}

async function runBuffered(command, args, cwd) {
  return new Promise((resolve, reject) => {
    const child = spawn(command, args, { cwd, stdio: ["ignore", "pipe", "pipe"], windowsHide: true });
    const stdout = [];
    const stderr = [];
    child.stdout.on("data", (chunk) => stdout.push(chunk));
    child.stderr.on("data", (chunk) => stderr.push(chunk));
    child.on("error", reject);
    child.on("close", (code) => {
      if (code === 0) {
        resolve({ stdout: Buffer.concat(stdout), stderr: Buffer.concat(stderr) });
        return;
      }
      reject(new Error(Buffer.concat(stderr).toString("utf8").trim() || `${command} exited with code ${code}`));
    });
  });
}
