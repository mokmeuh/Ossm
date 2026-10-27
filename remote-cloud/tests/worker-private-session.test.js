import { describe, expect, it, vi } from "vitest"
import { BrokerCore as sourceBrokerCore } from "../src/broker-core.ts"
import { BrokerCore as javascriptBrokerCore } from "../src/broker-core.js"
import { parseInboundMessage as parseSourceMessage } from "../src/protocol.ts"
import { parseInboundMessage as parseJavascriptMessage } from "../src/protocol.js"
import { SessionBroker as sourceSessionBroker } from "../src/session-broker.ts"
import { SessionBroker as javascriptSessionBroker } from "../src/session-broker.js"
import worker from "../src/worker.js"
import sourceWorker from "../src/worker.ts"

function fakePeer(id, role) {
  const events = []
  return { peer: { id, role, send: (event) => events.push(event), lastHeartbeat: 0, lastSequence: -1 }, events }
}

describe.each([
  ["TypeScript source", sourceBrokerCore, parseSourceMessage],
  ["JavaScript counterpart", javascriptBrokerCore, parseJavascriptMessage],
])("Broker diagnostics via %s", (_, BrokerCore, parseMessage) => {
  it("relays the allowlisted host diagnostic only to the active controller", () => {
    const broker = new BrokerCore()
    const host = fakePeer("host", "host")
    const controller = fakePeer("controller", "controller")
    const camera = fakePeer("camera", "camera")
    broker.join(host.peer)
    broker.join(controller.peer)
    broker.join(camera.peer)
    const diagnostic = parseMessage({ type: "diagnostic", severity: "warning", code: "firmware_remote_auth_required" })

    expect(diagnostic).not.toBeNull()
    if (diagnostic !== null) broker.handle("host", diagnostic)

    expect(controller.events).toContainEqual({ type: "diagnostic", severity: "warning", code: "firmware_remote_auth_required" })
    expect(host.events).not.toContainEqual({ type: "diagnostic", severity: "warning", code: "firmware_remote_auth_required" })
    expect(camera.events).not.toContainEqual({ type: "diagnostic", severity: "warning", code: "firmware_remote_auth_required" })
  })

  it("rejects unknown diagnostic codes and extra data", () => {
    const unknownCode = parseMessage({ type: "diagnostic", severity: "warning", code: "arbitrary" })
    const extraField = parseMessage({ type: "diagnostic", severity: "warning", code: "firmware_remote_auth_required", message: "raw log" })

    expect(unknownCode).toBeNull()
    expect(extraField).toBeNull()
  })

  it("includes participant identifiers in broker presence", () => {
    const broker = new BrokerCore()
    const host = fakePeer("host-id", "host")
    const controller = fakePeer("controller-id", "controller")
    const camera = fakePeer("camera-id", "camera")

    broker.join(host.peer)
    broker.join(controller.peer)
    broker.join(camera.peer)

    expect(controller.events).toContainEqual({
      type: "presence",
      hostOnline: true,
      controllerOnline: true,
      cameras: 1,
      hostId: "host-id",
      controllerId: "controller-id",
      cameraIds: ["camera-id"],
    })
  })
})

class FakeSocket {
  listeners = new Map()
  sent = []

  closed = false
  accept() {}
  close() { this.closed = true }
  send(value) { this.sent.push(JSON.parse(value)) }
  addEventListener(type, listener) {
    const listeners = this.listeners.get(type) ?? []
    listeners.push(listener)
    this.listeners.set(type, listeners)
  }
  receive(value) {
    for (const listener of this.listeners.get("message") ?? []) listener({ data: JSON.stringify(value) })
  }
}

class FakeWebSocketPair {
  static latest

  constructor() {
    this.client = new FakeSocket()
    this.server = new FakeSocket()
    this[0] = this.client
    this[1] = this.server
    FakeWebSocketPair.latest = this
  }
}

class FakeResponse {
  constructor(_body, init = {}) {
    this.status = init.status ?? 200
    this.webSocket = init.webSocket
  }
  static json(_body, init = {}) { return new FakeResponse(null, init) }
}

describe.each([
  ["TypeScript source", sourceSessionBroker],
  ["JavaScript counterpart", javascriptSessionBroker],
])("Session heartbeat expiry via %s", (_, SessionBroker) => {
  it("expires a lone host after its heartbeat alarm is scheduled", async () => {
    const now = 1_000_000
    const scheduledAlarms = []
    const backgroundTasks = []
    const state = {
      storage: {
        get: async () => undefined,
        put: async () => {},
        delete: async () => true,
        setAlarm: async (when) => { scheduledAlarms.push(when) },
      },
      waitUntil: (promise) => backgroundTasks.push(promise),
    }
    const broker = new SessionBroker(state)
    const secret = "a".repeat(32)
    const clock = vi.spyOn(Date, "now").mockReturnValue(now)
    vi.stubGlobal("Response", FakeResponse)
    vi.stubGlobal("WebSocketPair", FakeWebSocketPair)

    try {
      await broker.fetch(new Request("https://session/create", {
        method: "POST",
        body: JSON.stringify({ secret, expiresAt: now + 3_600_000 }),
      }))
      await broker.fetch(new Request("https://session/ws", { headers: { Upgrade: "websocket" } }))
      const server = FakeWebSocketPair.latest.server
      server.receive({ type: "hello", role: "host", secret })
      await Promise.all(backgroundTasks)

      expect(scheduledAlarms).toEqual([now + 500])

      clock.mockReturnValue(now + 1_501)
      await broker.alarm()

      expect(server.sent).toContainEqual({ type: "lease_lost", participantId: expect.any(String), reason: "heartbeat_timeout" })
      // The expired peer's socket is closed so the client reconnects instead of lingering as a zombie.
      expect(server.closed).toBe(true)
    } finally {
      vi.restoreAllMocks()
      vi.unstubAllGlobals()
    }
  })

  it("closes active peers and clears the session when its TTL expires", async () => {
    const now = 1_000_000
    const scheduledAlarms = []
    let deleteCount = 0
    const backgroundTasks = []
    const state = {
      storage: {
        get: async () => undefined,
        put: async () => {},
        delete: async () => { deleteCount += 1; return true },
        setAlarm: async (when) => { scheduledAlarms.push(when) },
      },
      waitUntil: (promise) => backgroundTasks.push(promise),
    }
    const broker = new SessionBroker(state)
    const secret = "b".repeat(32)
    const clock = vi.spyOn(Date, "now").mockReturnValue(now)
    vi.stubGlobal("Response", FakeResponse)
    vi.stubGlobal("WebSocketPair", FakeWebSocketPair)

    try {
      await broker.fetch(new Request("https://session/create", {
        method: "POST",
        body: JSON.stringify({ secret, expiresAt: now + 1_000 }),
      }))
      await broker.fetch(new Request("https://session/ws", { headers: { Upgrade: "websocket" } }))
      const server = FakeWebSocketPair.latest.server
      server.receive({ type: "hello", role: "host", secret })
      await Promise.all(backgroundTasks)

      clock.mockReturnValue(now + 1_000)
      await broker.alarm()

      expect(deleteCount).toBe(1)
      expect(server.sent).toContainEqual({ type: "error", code: "expired" })
      expect(server.sent).toContainEqual({ type: "lease_lost", participantId: expect.any(String), reason: "replaced" })
      expect(scheduledAlarms).toEqual([now + 500])
    } finally {
      vi.restoreAllMocks()
      vi.unstubAllGlobals()
    }
  })
})

function createEnv(createResponse = Response.json({ ok: true })) {
  const created = []
  const inits = []
  const env = {
    ADMIN_TOKEN: "unused-admin-token",
    SESSION_TTL_SECONDS: "3600",
    SESSIONS: {
      idFromName: (id) => id,
      get: () => ({
        fetch: async (request, init) => {
          created.push(request)
          inits.push(init)
          return createResponse
        },
      }),
    },
    ASSETS: { fetch: async () => new Response("asset", { status: 404 }) },
  }
  return { env, created, inits }
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

  it("does not let a caller choose the server-generated session identifier", async () => {
    const { env } = createEnv()
    const response = await worker.fetch(new Request("https://remote.example/api/sessions", {
      method: "POST", headers: { "content-type": "application/json" }, body: '{"sessionId":"chosen-by-client"}',
    }), env)

    const body = await response.json()
    expect(body.url).not.toContain("chosen-by-client")
  })

  it.each([["TypeScript source", sourceWorker], ["JavaScript counterpart", worker]])("propagates broker create failure from %s", async (_, candidate) => {
    const { env } = createEnv(new Response("broker unavailable", { status: 503 }))

    const response = await candidate.fetch(new Request("https://remote.example/api/sessions", {
      method: "POST", headers: { "content-type": "application/json" }, body: "{}",
    }), env)

    expect(response.status).toBe(503)
    expect(await response.text()).toBe("broker unavailable")
  })

  it("forwards renewal and revocation only with the bearer capability", async () => {
    const { env, created } = createEnv()
    await worker.fetch(new Request("https://remote.example/api/session/abcdefgh", {
      method: "POST", headers: { Authorization: "Bearer session-capability" }, body: "{}",
    }), env)
    await worker.fetch(new Request("https://remote.example/api/session/abcdefgh", { method: "DELETE" }), env)

    expect(created).toHaveLength(2)
    expect(created[0].url).toBe("https://session/session")
    expect(created[0].headers.get("Authorization")).toBe("Bearer session-capability")
    expect(await created[0].json()).toMatchObject({ expiresAt: expect.any(Number) })
    expect(created[1].headers.has("Authorization")).toBe(false)
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

describe.each([["TypeScript source", sourceWorker], ["JavaScript counterpart", worker]])("GET /api/session/:id/status via %s", (_, candidate) => {
  const statusEnv = (forwarded) => ({
    ADMIN_TOKEN: "unused-admin-token",
    SESSION_TTL_SECONDS: "3600",
    SESSIONS: {
      idFromName: (id) => id,
      get: () => ({ fetch: async (request) => { forwarded.push(request); return new Response("status") } }),
    },
    ASSETS: { fetch: async () => new Response("asset", { status: 404 }) },
  })

  it("forwards only GET and HEAD to the broker status route without credentials", async () => {
    const forwarded = []
    const env = statusEnv(forwarded)
    await candidate.fetch(new Request("https://remote.example/api/session/abcdefgh/status", { headers: { Authorization: "Bearer leaked" } }), env)
    await candidate.fetch(new Request("https://remote.example/api/session/abcdefgh/status", { method: "HEAD" }), env)
    const post = await candidate.fetch(new Request("https://remote.example/api/session/abcdefgh/status", { method: "POST", body: "{}" }), env)

    expect(forwarded).toHaveLength(2)
    expect(forwarded.map((request) => new URL(request.url).pathname)).toEqual(["/status", "/status"])
    expect(forwarded.map((request) => request.method)).toEqual(["GET", "HEAD"])
    expect(forwarded.every((request) => !request.headers.has("Authorization"))).toBe(true)
    expect(post.status).toBe(404)
  })
})

describe.each([
  ["TypeScript source", sourceSessionBroker],
  ["JavaScript counterpart", javascriptSessionBroker],
])("Session status probe via %s", (_, SessionBroker) => {
  it("reports only active or expired and never exposes the session secret", async () => {
    const stored = new Map()
    const state = {
      storage: {
        get: async (key) => stored.get(key),
        put: async (key, value) => { stored.set(key, value) },
        delete: async (key) => stored.delete(key),
        setAlarm: async () => {},
      },
      waitUntil: () => {},
    }
    const broker = new SessionBroker(state)
    const secret = "c".repeat(32)
    const now = 2_000_000
    const clock = vi.spyOn(Date, "now").mockReturnValue(now)

    try {
      const unknown = await broker.fetch(new Request("https://session/status"))
      expect(unknown.status).toBe(410)
      expect(await unknown.json()).toEqual({ status: "expired" })

      await broker.fetch(new Request("https://session/create", { method: "POST", body: JSON.stringify({ secret, expiresAt: now + 1_000 }) }))
      const active = await broker.fetch(new Request("https://session/status"))
      const activeText = await active.text()
      expect(active.status).toBe(200)
      expect(JSON.parse(activeText)).toEqual({ status: "active" })
      expect(activeText).not.toContain(secret)
      expect(active.headers.get("Cache-Control")).toBe("no-store")
      expect((await broker.fetch(new Request("https://session/status", { method: "HEAD" }))).status).toBe(200)
      expect((await broker.fetch(new Request("https://session/status", { method: "POST", body: "{}" }))).status).toBe(405)

      clock.mockReturnValue(now + 1_000)
      const expired = await broker.fetch(new Request("https://session/status"))
      expect(expired.status).toBe(410)
      expect(await expired.json()).toEqual({ status: "expired" })
    } finally {
      vi.restoreAllMocks()
    }
  })
})

describe.each([
  ["TypeScript source", sourceBrokerCore, parseSourceMessage, sourceSessionBroker],
  ["JavaScript counterpart", javascriptBrokerCore, parseJavascriptMessage, javascriptSessionBroker],
])("Phone limits envelope via %s", (_, BrokerCore, parseMessage) => {
  const limits = { type: "limits", speedMax: 0.6, depthMin: 0.2, depthMax: 0.8 }
  const room = () => {
    const broker = new BrokerCore()
    const host = fakePeer("host", "host")
    const controller = fakePeer("controller", "controller")
    const camera = fakePeer("camera", "camera")
    broker.join(host.peer)
    broker.join(controller.peer)
    broker.join(camera.peer)
    return { broker, host, controller, camera }
  }

  it("accepts the 4-key frame and the 5-key frame with an optional bounded sensationMax", () => {
    const withSensation = { ...limits, sensationMax: 0.4 }
    expect(parseMessage(limits)).toEqual(limits)
    expect(Object.keys(parseMessage(limits))).not.toContain("sensationMax")
    expect(parseMessage(withSensation)).toEqual(withSensation)
    expect(parseMessage({ ...limits, sensationMax: 0 })).not.toBeNull()
    expect(parseMessage({ ...limits, sensationMax: 1 })).not.toBeNull()
    expect(parseMessage({ ...limits, sensationMax: -0.01 })).toBeNull()
    expect(parseMessage({ ...limits, sensationMax: 1.01 })).toBeNull()
    expect(parseMessage({ ...limits, sensationMax: "0.4" })).toBeNull()
    expect(parseMessage({ ...limits, sensationMax: Number.NaN })).toBeNull()
    expect(parseMessage({ ...limits, sensationMax: null })).toBeNull()
    expect(parseMessage({ ...withSensation, extra: 1 })).toBeNull()
    expect(parseMessage({ ...limits, sensationMin: 0.1 })).toBeNull()
    expect(parseMessage({ type: "limits", speedMax: 0.5, depthMin: 0, sensationMax: 0.5 })).toBeNull()
  })

  it("accepts the bipolar sensation band only as a complete, ordered sensationMin/sensationMax pair", () => {
    const band = { ...limits, sensationMin: 0.3, sensationMax: 0.7 }
    expect(parseMessage(band)).toEqual(band)
    expect(parseMessage({ ...limits, sensationMin: 0.5, sensationMax: 0.5 })).not.toBeNull()
    expect(parseMessage({ ...limits, sensationMin: 0, sensationMax: 1 })).not.toBeNull()
    // Legacy 2.3.14/2.3.15 ceiling stays accepted on its own.
    expect(parseMessage({ ...limits, sensationMax: 0.4 })).toEqual({ ...limits, sensationMax: 0.4 })
    expect(parseMessage({ ...limits, sensationMin: 0.3 })).toBeNull()
    expect(parseMessage({ ...limits, sensationMin: 0.7, sensationMax: 0.3 })).toBeNull()
    expect(parseMessage({ ...limits, sensationMin: -0.01, sensationMax: 0.5 })).toBeNull()
    expect(parseMessage({ ...limits, sensationMin: 0.5, sensationMax: 1.01 })).toBeNull()
    expect(parseMessage({ ...limits, sensationMin: "0.3", sensationMax: 0.7 })).toBeNull()
    expect(parseMessage({ ...limits, sensationMin: Number.NaN, sensationMax: 0.7 })).toBeNull()
    expect(parseMessage({ ...limits, sensationMin: null, sensationMax: 0.7 })).toBeNull()
    expect(parseMessage({ ...band, extra: 1 })).toBeNull()
    expect(parseMessage({ ...band, depthMin: 0.9 })).toBeNull()
  })

  it("relays and replays the sensation band exactly as published, and drops it when the phone stops sending it", () => {
    const { broker, controller, camera } = room()
    const band = { ...limits, sensationMin: 0.35, sensationMax: 0.65 }
    broker.handle("host", parseMessage(band))
    expect(controller.events).toContainEqual(band)
    expect(camera.events.some((event) => event.type === "limits")).toBe(false)
    broker.remove("controller")
    const returning = fakePeer("controller-2", "controller")
    broker.join(returning.peer)
    expect(returning.events.filter((event) => event.type === "limits")).toEqual([band])

    const legacy = { ...limits, sensationMax: 0.4 }
    broker.handle("host", parseMessage(legacy))
    broker.remove("controller-2")
    const third = fakePeer("controller-3", "controller")
    broker.join(third.peer)
    const replayed = third.events.filter((event) => event.type === "limits")
    expect(replayed).toEqual([legacy])
    expect(Object.keys(replayed[0])).not.toContain("sensationMin")
  })

  it("relays and replays sensationMax exactly as published, and drops it when the phone stops sending it", () => {
    const { broker, controller, camera } = room()
    const withSensation = { ...limits, sensationMax: 0.35 }
    broker.handle("host", parseMessage(withSensation))
    expect(controller.events).toContainEqual(withSensation)
    expect(camera.events.some((event) => event.type === "limits")).toBe(false)
    broker.remove("controller")
    const returning = fakePeer("controller-2", "controller")
    broker.join(returning.peer)
    expect(returning.events.filter((event) => event.type === "limits")).toEqual([withSensation])

    broker.handle("host", parseMessage(limits))
    broker.remove("controller-2")
    const third = fakePeer("controller-3", "controller")
    broker.join(third.peer)
    const replayed = third.events.filter((event) => event.type === "limits")
    expect(replayed).toEqual([limits])
    expect(Object.keys(replayed[0])).not.toContain("sensationMax")
  })

  it("accepts only the strict limits shape", () => {
    expect(parseMessage(limits)).toEqual(limits)
    expect(parseMessage({ ...limits, speedMax: 0, depthMin: 0, depthMax: 0 })).not.toBeNull()
    expect(parseMessage({ ...limits, speedMax: 1, depthMin: 1, depthMax: 1 })).not.toBeNull()
    expect(parseMessage({ ...limits, depthMin: 0.9 })).toBeNull()
    expect(parseMessage({ ...limits, speedMax: -0.01 })).toBeNull()
    expect(parseMessage({ ...limits, speedMax: 1.01 })).toBeNull()
    expect(parseMessage({ ...limits, depthMax: "0.8" })).toBeNull()
    expect(parseMessage({ ...limits, depthMax: Number.NaN })).toBeNull()
    expect(parseMessage({ ...limits, extra: 1 })).toBeNull()
    expect(parseMessage({ type: "limits", speedMax: 0.5, depthMin: 0 })).toBeNull()
  })

  it("relays limits only from the host to the controller and replays the last value", () => {
    const { broker, host, controller, camera } = room()
    broker.handle("host", parseMessage(limits))

    expect(controller.events).toContainEqual(limits)
    expect(camera.events).not.toContainEqual(limits)
    expect(host.events).not.toContainEqual(limits)

    broker.handle("host", parseMessage({ ...limits, speedMax: 0.3 }))
    broker.remove("controller")
    const returning = fakePeer("controller-2", "controller")
    broker.join(returning.peer)
    const replayed = returning.events.filter((event) => event.type === "limits")
    expect(replayed).toEqual([{ type: "limits", speedMax: 0.3, depthMin: 0.2, depthMax: 0.8 }])
    const lateCamera = fakePeer("camera-2", "camera")
    broker.join(lateCamera.peer)
    expect(lateCamera.events.filter((event) => event.type === "limits")).toHaveLength(0)
  })

  it("rejects limits from the controller and camera without storing them", () => {
    const { broker, host, controller, camera } = room()
    broker.handle("controller", parseMessage(limits))
    broker.handle("camera", parseMessage(limits))

    expect(controller.events).toContainEqual({ type: "error", code: "forbidden" })
    expect(camera.events).toContainEqual({ type: "error", code: "forbidden" })
    expect(host.events).not.toContainEqual(limits)
    expect(controller.events).not.toContainEqual(limits)
    broker.remove("controller")
    const returning = fakePeer("controller-2", "controller")
    broker.join(returning.peer)
    expect(returning.events.filter((event) => event.type === "limits")).toHaveLength(0)
  })

  it("forgets limits when the session is cleared", () => {
    const { broker } = room()
    broker.handle("host", parseMessage(limits))
    broker.clearLimits()
    broker.remove("controller")
    const returning = fakePeer("controller-2", "controller")
    broker.join(returning.peer)
    expect(returning.events.filter((event) => event.type === "limits")).toHaveLength(0)
  })

  it("accepts the five remote diagnostic codes from the host to the controller only", () => {
    const codes = ["remote_lease_invalid", "remote_session_not_started", "remote_machine_not_ready", "remote_sequence_stale", "remote_live_position_rejected"]
    for (const code of codes) {
      const { broker, host, controller, camera } = room()
      const diagnostic = parseMessage({ type: "diagnostic", severity: "warning", code })
      expect(diagnostic).toEqual({ type: "diagnostic", severity: "warning", code })
      broker.handle("host", diagnostic)
      broker.handle("camera", diagnostic)
      expect(controller.events).toContainEqual({ type: "diagnostic", severity: "warning", code })
      expect(camera.events).toContainEqual({ type: "error", code: "forbidden" })
      expect(host.events.some((event) => event.type === "diagnostic")).toBe(false)
    }
    expect(parseMessage({ type: "diagnostic", severity: "warning", code: "remote_other" })).toBeNull()
  })
})

describe.each([
  ["TypeScript source", sourceSessionBroker],
  ["JavaScript counterpart", javascriptSessionBroker],
])("Limits over the broker socket via %s", (_, SessionBroker) => {
  it("answers invalid_message for an invalid limits frame and clears limits on revocation", async () => {
    const now = 3_000_000
    const stored = new Map()
    const state = {
      storage: { get: async (key) => stored.get(key), put: async (key, value) => { stored.set(key, value) }, delete: async (key) => stored.delete(key), setAlarm: async () => {} },
      waitUntil: () => {},
    }
    const broker = new SessionBroker(state)
    const secret = "d".repeat(32)
    vi.spyOn(Date, "now").mockReturnValue(now)
    vi.stubGlobal("Response", FakeResponse)
    vi.stubGlobal("WebSocketPair", FakeWebSocketPair)
    try {
      await broker.fetch(new Request("https://session/create", { method: "POST", body: JSON.stringify({ secret, expiresAt: now + 3_600_000 }) }))
      await broker.fetch(new Request("https://session/ws", { headers: { Upgrade: "websocket" } }))
      const hostSocket = FakeWebSocketPair.latest.server
      hostSocket.receive({ type: "hello", role: "host", secret })
      await broker.fetch(new Request("https://session/ws", { headers: { Upgrade: "websocket" } }))
      const controllerSocket = FakeWebSocketPair.latest.server
      controllerSocket.receive({ type: "hello", role: "controller", secret })

      hostSocket.receive({ type: "limits", speedMax: 0.5, depthMin: 0.9, depthMax: 0.1 })
      expect(hostSocket.sent).toContainEqual({ type: "error", code: "invalid_message" })
      expect(controllerSocket.sent.some((event) => event.type === "limits")).toBe(false)

      hostSocket.receive({ type: "limits", speedMax: 0.5, depthMin: 0.1, depthMax: 0.9 })
      expect(controllerSocket.sent).toContainEqual({ type: "limits", speedMax: 0.5, depthMin: 0.1, depthMax: 0.9 })

      hostSocket.receive({ type: "limits", speedMax: 0.5, depthMin: 0.1, depthMax: 0.9, sensationMax: 1.5 })
      expect(hostSocket.sent.filter((event) => event.type === "error" && event.code === "invalid_message")).toHaveLength(2)
      hostSocket.receive({ type: "limits", speedMax: 0.5, depthMin: 0.1, depthMax: 0.9, sensationMax: 0.25 })
      expect(controllerSocket.sent.at(-1)).toEqual({ type: "limits", speedMax: 0.5, depthMin: 0.1, depthMax: 0.9, sensationMax: 0.25 })

      await broker.fetch(new Request("https://session/session", { method: "DELETE", headers: { Authorization: `Bearer ${secret}` } }))
      expect(broker.core.peers.size).toBe(0)
      expect(broker.core.lastLimits).toBeUndefined()
    } finally {
      vi.restoreAllMocks()
      vi.unstubAllGlobals()
    }
  })
})

describe.each([["TypeScript source", sourceWorker], ["JavaScript counterpart", worker]])("Camera-only key at creation via %s", (_, candidate) => {
  it("returns an additive cameraUrl with a distinct key kept in the fragment", async () => {
    const { env, inits } = createEnv()
    const response = await candidate.fetch(new Request("https://remote.example/api/sessions", { method: "POST", body: "{}" }), env)
    const body = await response.json()
    expect(Object.keys(body).sort()).toEqual(["cameraUrl", "expiresAt", "url"])
    const main = /^https:\/\/remote\.example\/s\/([A-Za-z0-9_-]{8,64})#k=([A-Za-z0-9_-]{24,})$/.exec(body.url)
    const camera = /^https:\/\/remote\.example\/s\/([A-Za-z0-9_-]{8,64})\?role=camera#k=([A-Za-z0-9_-]{24,})$/.exec(body.cameraUrl)
    expect(main).not.toBeNull()
    expect(camera).not.toBeNull()
    expect(camera[1]).toBe(main[1])
    expect(camera[2]).not.toBe(main[2])
    const stored = JSON.parse(inits[0].body)
    expect(stored).toEqual({ secret: main[2], cameraSecret: camera[2], expiresAt: body.expiresAt })
  })
})

describe.each([
  ["TypeScript source", sourceSessionBroker],
  ["JavaScript counterpart", javascriptSessionBroker],
])("Camera-only key over the broker socket via %s", (_, SessionBroker) => {
  const secret = "m".repeat(43)
  const cameraSecret = "c".repeat(43)

  async function openBroker(record) {
    const now = 5_000_000
    const stored = new Map()
    const state = {
      storage: { get: async (key) => stored.get(key), put: async (key, value) => { stored.set(key, value) }, delete: async (key) => stored.delete(key), setAlarm: async () => {} },
      waitUntil: () => {},
    }
    const broker = new SessionBroker(state)
    vi.spyOn(Date, "now").mockReturnValue(now)
    vi.stubGlobal("Response", FakeResponse)
    vi.stubGlobal("WebSocketPair", FakeWebSocketPair)
    const created = await broker.fetch(new Request("https://session/create", { method: "POST", body: JSON.stringify({ ...record, expiresAt: now + 3_600_000 }) }))
    const join = async (role, key) => {
      await broker.fetch(new Request("https://session/ws", { headers: { Upgrade: "websocket" } }))
      const socket = FakeWebSocketPair.latest.server
      socket.receive({ type: "hello", role, secret: key })
      return socket
    }
    return { broker, join, created }
  }
  const isReady = (socket) => socket.sent.some((event) => event.type === "ready")
  const forbidden = (socket) => socket.sent.some((event) => event.type === "error" && event.code === "forbidden")

  it("admits the camera key only as a camera, while the main key keeps every role", async () => {
    try {
      const { join } = await openBroker({ secret, cameraSecret })
      const asController = await join("controller", cameraSecret)
      const asHost = await join("host", cameraSecret)
      expect(isReady(asController)).toBe(false)
      expect(forbidden(asController)).toBe(true)
      expect(isReady(asHost)).toBe(false)
      expect(forbidden(asHost)).toBe(true)

      const host = await join("host", secret)
      const controller = await join("controller", secret)
      const camera = await join("camera", cameraSecret)
      const mainCamera = await join("camera", secret)
      for (const socket of [host, controller, camera, mainCamera]) expect(isReady(socket)).toBe(true)

      // The camera stays unable to command, stop, publish limits or diagnostics.
      const before = host.sent.length
      camera.receive({ type: "command", seq: 1, command: "{\"type\":\"set_speed\",\"value\":1}" })
      camera.receive({ type: "emergency_stop", seq: 2 })
      camera.receive({ type: "limits", speedMax: 1, depthMin: 0, depthMax: 1 })
      camera.receive({ type: "diagnostic", severity: "warning", code: "remote_machine_not_ready" })
      expect(camera.sent.filter((event) => event.type === "error" && event.code === "forbidden")).toHaveLength(4)
      expect(host.sent.slice(before).some((event) => ["command", "emergency_stop"].includes(event.type))).toBe(false)
      expect(controller.sent.some((event) => event.type === "limits" || event.type === "diagnostic")).toBe(false)

      // No frame ever echoes either key.
      for (const socket of [asController, asHost, host, controller, camera, mainCamera]) {
        const frames = JSON.stringify(socket.sent)
        expect(frames).not.toContain(secret)
        expect(frames).not.toContain(cameraSecret)
      }
    } finally {
      vi.restoreAllMocks()
      vi.unstubAllGlobals()
    }
  })

  it("never accepts the camera key for renewal or revocation, and revocation closes the cameras", async () => {
    try {
      const { broker, join } = await openBroker({ secret, cameraSecret })
      const camera = await join("camera", cameraSecret)
      expect(isReady(camera)).toBe(true)
      const byCamera = await broker.fetch(new Request("https://session/session", { method: "DELETE", headers: { Authorization: `Bearer ${cameraSecret}` } }))
      expect(byCamera.status).toBe(404)
      const renewByCamera = await broker.fetch(new Request("https://session/session", { method: "POST", headers: { Authorization: `Bearer ${cameraSecret}` }, body: JSON.stringify({ expiresAt: Date.now() + 10_000 }) }))
      expect(renewByCamera.status).toBe(404)
      expect(camera.closed).toBe(false)

      const revoked = await broker.fetch(new Request("https://session/session", { method: "DELETE", headers: { Authorization: `Bearer ${secret}` } }))
      expect(revoked.status).toBe(204)
      expect(camera.closed).toBe(true)
      expect(broker.core.peers.size).toBe(0)
      const status = await broker.fetch(new Request("https://session/status"))
      expect(status.status).toBe(410)
    } finally {
      vi.restoreAllMocks()
      vi.unstubAllGlobals()
    }
  })

  const participantId = (socket) => socket.sent.find((event) => event.type === "ready")?.participantId
  const lastPresence = (socket) => socket.sent.filter((event) => event.type === "presence").at(-1)
  const disconnect = (socket) => { for (const listener of socket.listeners.get("close") ?? []) listener({}) }

  it("lists cameras joined with the camera key in presence, whatever the arrival order, and replays it to a reloaded controller", async () => {
    try {
      const { join } = await openBroker({ secret, cameraSecret })
      const host = await join("host", secret)
      // Camera first (camera-only key), controller afterwards.
      const camera = await join("camera", cameraSecret)
      const cameraId = participantId(camera)
      const controller = await join("controller", secret)
      expect(lastPresence(controller)).toMatchObject({ cameras: 1, cameraIds: [cameraId], controllerId: participantId(controller) })
      expect(lastPresence(camera)).toMatchObject({ controllerId: participantId(controller) })

      const mainKeyCamera = await join("camera", secret)
      expect(lastPresence(controller)).toMatchObject({ cameras: 2, cameraIds: [cameraId, participantId(mainKeyCamera)] })

      // The camera can offer to the controller it was told about.
      camera.receive({ type: "signal", to: participantId(controller), data: "{\"kind\":\"offer\"}" })
      expect(controller.sent).toContainEqual({ type: "signal", from: cameraId, data: "{\"kind\":\"offer\"}" })

      // Controller reload: it leaves, comes back with a new id and gets the full camera list again;
      // each camera learns the new controller id (its cue to send a fresh offer).
      disconnect(controller)
      expect(lastPresence(camera)).not.toHaveProperty("controllerId")
      const reloaded = await join("controller", secret)
      expect(lastPresence(reloaded)).toMatchObject({ cameras: 2, cameraIds: [cameraId, participantId(mainKeyCamera)] })
      expect(lastPresence(camera)).toMatchObject({ controllerId: participantId(reloaded) })
      camera.receive({ type: "signal", to: participantId(reloaded), data: "{\"kind\":\"offer\"}" })
      expect(reloaded.sent).toContainEqual({ type: "signal", from: cameraId, data: "{\"kind\":\"offer\"}" })

      // A camera that leaves is removed from the list sent to the controller.
      disconnect(mainKeyCamera)
      expect(lastPresence(reloaded)).toMatchObject({ cameras: 1, cameraIds: [cameraId] })
      expect(host.sent.some((event) => event.type === "presence")).toBe(true)
    } finally {
      vi.restoreAllMocks()
      vi.unstubAllGlobals()
    }
  })

  it("closes a camera removed for a late heartbeat so it reconnects instead of staying invisible", async () => {
    try {
      const { broker, join } = await openBroker({ secret, cameraSecret })
      const controller = await join("controller", secret)
      const camera = await join("camera", cameraSecret)
      expect(lastPresence(controller)).toMatchObject({ cameras: 1 })
      const late = Date.now() + 1_501
      Date.now.mockReturnValue(late)
      controller.receive({ type: "heartbeat" })
      await broker.alarm()
      expect(camera.sent).toContainEqual({ type: "lease_lost", participantId: participantId(camera), reason: "heartbeat_timeout" })
      expect(camera.closed).toBe(true)
      expect(controller.closed).toBe(false)
      expect(lastPresence(controller)).toMatchObject({ cameras: 0, cameraIds: [] })

      // A frame from a peer the core already dropped closes its socket too.
      const zombie = await join("camera", cameraSecret)
      broker.core.remove(participantId(zombie), "heartbeat_timeout")
      zombie.closed = false
      zombie.receive({ type: "heartbeat" })
      expect(zombie.closed).toBe(true)
    } finally {
      vi.restoreAllMocks()
      vi.unstubAllGlobals()
    }
  })

  it("keeps sessions stored without a camera key working with the main key only", async () => {
    try {
      const { join } = await openBroker({ secret })
      const camera = await join("camera", cameraSecret)
      expect(forbidden(camera)).toBe(true)
      const legacyCamera = await join("camera", secret)
      expect(isReady(legacyCamera)).toBe(true)
    } finally {
      vi.restoreAllMocks()
      vi.unstubAllGlobals()
    }
  })

  it("rejects a session record whose camera key is malformed or equal to the main key", async () => {
    try {
      for (const bad of [{ secret, cameraSecret: "short" }, { secret, cameraSecret: secret }, { secret, cameraSecret: 42 }]) {
        const { created } = await openBroker(bad)
        expect(created.status).toBe(400)
        vi.restoreAllMocks()
      }
    } finally {
      vi.restoreAllMocks()
      vi.unstubAllGlobals()
    }
  })
})
