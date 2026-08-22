import assert from "node:assert/strict";
import path from "node:path";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StdioClientTransport } from "@modelcontextprotocol/sdk/client/stdio.js";

const cwd = process.cwd();
const client = new Client({ name: "ossm-video-adb-smoke", version: "0.1.0" });
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

await client.connect(transport);
const result = await client.callTool({
  name: "ossm_adb_screen_probe",
  arguments: {
    durationMs: 900,
    intervalMs: 300,
    label: "mcp-adb-smoke",
  },
});
await client.close();

const text = result.content[0].text;
assert.ok(text.includes("captureDir="), text);
assert.ok(text.includes("frames="), text);
console.log(text);
