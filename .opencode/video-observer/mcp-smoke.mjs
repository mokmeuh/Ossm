import assert from "node:assert/strict";
import path from "node:path";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StdioClientTransport } from "@modelcontextprotocol/sdk/client/stdio.js";

const cwd = process.cwd();
const transport = new StdioClientTransport({
  command: process.execPath,
  args: [path.join(cwd, ".opencode/video-observer/mcp-server.mjs")],
  cwd,
  env: {
    ...process.env,
    OSSM_VIDEO_ROOT: cwd,
  },
  stderr: "pipe",
});

const client = new Client({ name: "ossm-video-smoke", version: "0.1.0" });

await client.connect(transport);
const tools = await client.listTools();
const status = await client.callTool({ name: "ossm_video_status", arguments: {} });
await client.close();

assert.ok(tools.tools.some((tool) => tool.name === "ossm_webcam_probe"));
assert.ok(status.content[0].text.includes("ffmpeg="));
