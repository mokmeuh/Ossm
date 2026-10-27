import { describe, expect, it } from "vitest"
import { BrokerCore, type Peer } from "../src/broker-core"
import { parseInboundMessage, type BrokerEvent, type Role } from "../src/protocol"
import { hasSessionCapability, revokeSessionPeers } from "../src/session-broker"

function fakePeer(id: string, role: Role): { readonly peer: Peer; readonly events: BrokerEvent[] } {
  const events: BrokerEvent[] = []
  return { peer: { id, role, send: (event) => events.push(event), lastHeartbeat: 0, lastSequence: -1 }, events }
}

describe("BrokerCore", () => {
  it("accepts only the fixed remote-auth diagnostic event", () => {
    const diagnostic = { type: "diagnostic", severity: "warning", code: "firmware_remote_auth_required" }

    expect(parseInboundMessage(diagnostic)).toEqual(diagnostic)
    expect(parseInboundMessage({ ...diagnostic, code: "arbitrary" })).toBeNull()
    expect(parseInboundMessage({ ...diagnostic, message: "raw application log" })).toBeNull()
  })

  it("relays host diagnostics only to the active controller", () => {
    const broker = new BrokerCore()
    const host = fakePeer("host", "host")
    const controller = fakePeer("controller", "controller")
    const camera = fakePeer("camera", "camera")
    broker.join(host.peer); broker.join(controller.peer); broker.join(camera.peer)
    const diagnostic = parseInboundMessage({ type: "diagnostic", severity: "warning", code: "firmware_remote_auth_required" })

    if (diagnostic !== null) broker.handle("host", diagnostic)

    expect(controller.events).toContainEqual({ type: "diagnostic", severity: "warning", code: "firmware_remote_auth_required" })
    expect(host.events).not.toContainEqual({ type: "diagnostic", severity: "warning", code: "firmware_remote_auth_required" })
    expect(camera.events).not.toContainEqual({ type: "diagnostic", severity: "warning", code: "firmware_remote_auth_required" })
  })

  it("rejects diagnostics from controller and camera roles", () => {
    const broker = new BrokerCore()
    const host = fakePeer("host", "host")
    const controller = fakePeer("controller", "controller")
    const camera = fakePeer("camera", "camera")
    broker.join(host.peer); broker.join(controller.peer); broker.join(camera.peer)
    const diagnostic = parseInboundMessage({ type: "diagnostic", severity: "warning", code: "firmware_remote_auth_required" })

    if (diagnostic !== null) {
      broker.handle("controller", diagnostic)
      broker.handle("camera", diagnostic)
    }

    expect(controller.events).toContainEqual({ type: "error", code: "forbidden" })
    expect(camera.events).toContainEqual({ type: "error", code: "forbidden" })
    expect(host.events).not.toContainEqual({ type: "diagnostic", severity: "warning", code: "firmware_remote_auth_required" })
  })

  it("requires the exact bearer capability for session renewal or revocation", () => {
    const secret = "abcdefghijklmnopqrstuvwxyz012345"

    expect(hasSessionCapability(new Request("https://session/session", { headers: { Authorization: `Bearer ${secret}` } }), secret)).toBe(true)
    expect(hasSessionCapability(new Request("https://session/session", { headers: { Authorization: "Bearer wrong" } }), secret)).toBe(false)
    expect(hasSessionCapability(new Request("https://session/session"), secret)).toBe(false)
  })

  it("permits one host and controller, and four video-only cameras", () => {
    const broker = new BrokerCore()
    expect(broker.join(fakePeer("host", "host").peer).kind).toBe("joined")
    expect(broker.join(fakePeer("controller", "controller").peer).kind).toBe("joined")
    for (const id of ["one", "two", "three", "four"]) expect(broker.join(fakePeer(id, "camera").peer).kind).toBe("joined")
    expect(broker.join(fakePeer("five", "camera").peer)).toMatchObject({ kind: "rejected", event: { code: "capacity" } })
  })

  it("relays only monotonic controller commands to the host", () => {
    const broker = new BrokerCore()
    const host = fakePeer("host", "host")
    const controller = fakePeer("controller", "controller")
    broker.join(host.peer); broker.join(controller.peer)
    broker.handle("controller", { type: "command", seq: 1, command: "go:menu" })
    broker.handle("controller", { type: "command", seq: 1, command: "go:menu" })
    expect(host.events).toContainEqual({ type: "command", seq: 1, command: "go:menu", from: "controller" })
    expect(controller.events).toContainEqual({ type: "error", code: "invalid_sequence" })
  })

  it("relays media signaling only between distinct compatible roles", () => {
    const broker = new BrokerCore()
    const controller = fakePeer("controller", "controller")
    const firstCamera = fakePeer("camera-one", "camera")
    const secondCamera = fakePeer("camera-two", "camera")
    broker.join(controller.peer); broker.join(firstCamera.peer); broker.join(secondCamera.peer)

    broker.handle("controller", { type: "signal", to: "camera-one", data: "offer" })
    broker.handle("camera-one", { type: "signal", to: "camera-two", data: "offer" })

    expect(firstCamera.events).toContainEqual({ type: "signal", from: "controller", data: "offer" })
    expect(secondCamera.events).not.toContainEqual({ type: "signal", from: "camera-one", data: "offer" })
    expect(firstCamera.events).toContainEqual({ type: "error", code: "forbidden" })
  })

  it("broadcasts emergency stop and expires a missed heartbeat", () => {
    const broker = new BrokerCore()
    const host = fakePeer("host", "host")
    const controller = fakePeer("controller", "controller")
    broker.join(host.peer); broker.join(controller.peer)
    broker.handle("controller", { type: "emergency_stop", seq: 3 })
    host.peer.lastHeartbeat = 1_501
    broker.expire(1_501)
    expect(host.events).toContainEqual({ type: "emergency_stop", seq: 3, from: "controller" })
    expect(host.events).toContainEqual({ type: "lease_lost", participantId: "controller", reason: "heartbeat_timeout" })
    expect(controller.events).toContainEqual({ type: "lease_lost", participantId: "controller", reason: "heartbeat_timeout" })
  })

  it("removes active peers before closing their sockets during session revocation", () => {
    const broker = new BrokerCore()
    const controller = fakePeer("controller", "controller")
    const cameraEvents: BrokerEvent[] = []
    let cameraClosed = false
    const camera: Peer = {
      id: "camera",
      role: "camera",
      send: (event) => {
        if (cameraClosed) throw new Error("sent to a closed camera socket")
        cameraEvents.push(event)
      },
      close: () => { cameraClosed = true },
      lastHeartbeat: 0,
      lastSequence: -1,
    }
    broker.join(controller.peer)
    broker.join(camera)

    expect(() => revokeSessionPeers(broker)).not.toThrow()
    expect(broker.peers.size).toBe(0)
    expect(cameraEvents).toContainEqual({ type: "error", code: "forbidden" })
    expect(cameraEvents).toContainEqual({ type: "lease_lost", participantId: "camera", reason: "replaced" })
  })
})
