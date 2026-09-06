import { describe, expect, it } from "vitest"
import worker from "../src/worker.js"
import sourceWorker from "../src/worker.ts"

function createEnv() {
  const created = []
  const env = {
    ADMIN_TOKEN: "unused-admin-token",
    SESSION_TTL_SECONDS: "3600",
    SESSIONS: {
      idFromName: (id) => id,
      get: () => ({
        fetch: async (request) => {
          created.push(request)
          return Response.json({ ok: true })
        },
      }),
    },
    ASSETS: { fetch: async () => new Response("asset", { status: 404 }) },
  }
  return { env, created }
}

describe("POST /api/sessions", () => {
  it("creates a fragment-key private link without an account credential", async () => {
    const { env, created } = createEnv()
    const request = new Request("https://remote.example/api/sessions", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: "{}",
    })

    const response = await worker.fetch(request, env)
    const body = await response.json()

    expect(response.status).toBe(200)
    expect(request.headers.has("authorization")).toBe(false)
    expect(body.url).toMatch(/^https:\/\/remote\.example\/s\/[A-Za-z0-9_-]{8,64}#k=[A-Za-z0-9_-]{24,}$/)
    expect(created).toHaveLength(1)
    expect(created[0]).toBe("https://session/create")
  })
})

describe.each([["TypeScript source", sourceWorker], ["JavaScript counterpart", worker]])("WebSocket relay via %s", (_, candidate) => {
  it("forwards the external session endpoint to the broker websocket route", async () => {
    const forwarded = []
    const env = {
      ADMIN_TOKEN: "unused-admin-token",
      SESSION_TTL_SECONDS: "3600",
      SESSIONS: {
        idFromName: (id) => id,
        get: () => ({ fetch: async (request) => { forwarded.push(request); return new Response("ok") } }),
      },
      ASSETS: { fetch: async () => new Response("asset", { status: 404 }) },
    }

    await candidate.fetch(new Request("https://remote.example/api/session/abcdefgh/ws", { headers: { Upgrade: "websocket" } }), env)

    expect(forwarded).toHaveLength(1)
    expect(new URL(forwarded[0].url).pathname).toBe("/ws")
  })
})
