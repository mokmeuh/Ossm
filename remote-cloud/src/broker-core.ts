import { assertNever, type BrokerEvent, type InboundMessage, type Role } from "./protocol"

export interface Peer {
  readonly id: string
  readonly role: Role
  readonly send: (event: BrokerEvent) => void
  readonly close?: (code: number, reason: string) => void
  lastHeartbeat: number
  lastSequence: number
}

export type JoinResult = { readonly kind: "joined" } | { readonly kind: "rejected"; readonly event: BrokerEvent }

export class BrokerCore {
  readonly peers = new Map<string, Peer>()
  private hostId: string | undefined
  private controllerId: string | undefined
  private lastLimits: Extract<BrokerEvent, { readonly type: "limits" }> | undefined

  join(peer: Peer): JoinResult {
    if (peer.role === "host" && this.hostId !== undefined) return { kind: "rejected", event: { type: "error", code: "capacity" } }
    if (peer.role === "controller" && this.controllerId !== undefined) return { kind: "rejected", event: { type: "error", code: "capacity" } }
    if (peer.role === "camera" && this.cameraCount() >= 4) return { kind: "rejected", event: { type: "error", code: "capacity" } }
    this.peers.set(peer.id, peer)
    if (peer.role === "host") this.hostId = peer.id
    if (peer.role === "controller") this.controllerId = peer.id
    this.broadcastPresence()
    if (peer.role === "controller" && this.lastLimits !== undefined) peer.send(this.lastLimits)
    return { kind: "joined" }
  }

  /** Forgets the phone's last published limits; called when the session is revoked or expires. */
  clearLimits(): void { this.lastLimits = undefined }

  remove(peerId: string, reason: "heartbeat_timeout" | "disconnected" | "replaced" = "disconnected"): void {
    const peer = this.peers.get(peerId)
    if (peer === undefined) return
    this.peers.delete(peerId)
    if (peer.role === "host") this.hostId = undefined
    if (peer.role === "controller") this.controllerId = undefined
    for (const recipient of this.peers.values()) recipient.send({ type: "lease_lost", participantId: peer.id, reason })
    peer.send({ type: "lease_lost", participantId: peer.id, reason })
    this.broadcastPresence()
  }

  handle(peerId: string, message: InboundMessage): void {
    const peer = this.peers.get(peerId)
    if (peer === undefined) return
    switch (message.type) {
      case "heartbeat": peer.lastHeartbeat = Date.now(); return
      case "hello": peer.send({ type: "error", code: "forbidden" }); return
      case "command": return this.relayCommand(peer, message)
      case "emergency_stop": return this.relayStop(peer, message)
      case "diagnostic": return this.relayDiagnostic(peer, message)
      case "limits": return this.relayLimits(peer, message)
      case "signal": return this.relaySignal(peer, message)
      default: return assertNever(message)
    }
  }

  /**
   * Removes peers whose heartbeat is late and closes their socket: a removed peer whose socket stayed
   * open was a "zombie" (its heartbeats ignored, absent from presence) that never reconnected.
   */
  expire(now: number): void {
    for (const peer of [...this.peers.values()]) {
      if (now - peer.lastHeartbeat <= 1_500) continue
      this.remove(peer.id, "heartbeat_timeout")
      peer.close?.(4000, "heartbeat_timeout")
    }
  }

  private relayCommand(peer: Peer, message: Extract<InboundMessage, { readonly type: "command" }>): void {
    if (peer.role !== "controller") return peer.send({ type: "error", code: "forbidden" })
    if (message.seq <= peer.lastSequence) return peer.send({ type: "error", code: "invalid_sequence" })
    peer.lastSequence = message.seq
    this.sendHost({ type: "command", seq: message.seq, command: message.command, from: peer.id })
  }

  private relayStop(peer: Peer, message: Extract<InboundMessage, { readonly type: "emergency_stop" }>): void {
    if (peer.role !== "controller" && peer.role !== "host") return peer.send({ type: "error", code: "forbidden" })
    if (message.seq <= peer.lastSequence) return peer.send({ type: "error", code: "invalid_sequence" })
    peer.lastSequence = message.seq
    for (const recipient of this.peers.values()) recipient.send({ type: "emergency_stop", seq: message.seq, from: peer.id })
  }

  private relayDiagnostic(peer: Peer, message: Extract<InboundMessage, { readonly type: "diagnostic" }>): void {
    if (peer.role !== "host") return peer.send({ type: "error", code: "forbidden" })
    if (this.controllerId === undefined) return
    this.peers.get(this.controllerId)?.send({
      type: "diagnostic",
      severity: message.severity,
      code: message.code,
    })
  }

  private relayLimits(peer: Peer, message: Extract<InboundMessage, { readonly type: "limits" }>): void {
    if (peer.role !== "host") return peer.send({ type: "error", code: "forbidden" })
    this.lastLimits = {
      type: "limits", speedMax: message.speedMax, depthMin: message.depthMin, depthMax: message.depthMax,
      ...(message.sensationMin === undefined ? {} : { sensationMin: message.sensationMin }),
      ...(message.sensationMax === undefined ? {} : { sensationMax: message.sensationMax }),
    }
    if (this.controllerId !== undefined) this.peers.get(this.controllerId)?.send(this.lastLimits)
  }

  private relaySignal(peer: Peer, message: Extract<InboundMessage, { readonly type: "signal" }>): void {
    const recipient = this.peers.get(message.to)
    if (recipient === undefined) return peer.send({ type: "error", code: "forbidden" })
    if (!canRelayMediaSignal(peer.role, recipient.role)) return peer.send({ type: "error", code: "forbidden" })
    recipient.send({ type: "signal", from: peer.id, data: message.data })
  }

  private sendHost(event: BrokerEvent): void { this.hostId === undefined ? undefined : this.peers.get(this.hostId)?.send(event) }
  private cameraCount(): number { return [...this.peers.values()].filter((peer) => peer.role === "camera").length }
  private broadcastPresence(): void {
    const event: BrokerEvent = {
      type: "presence",
      hostOnline: this.hostId !== undefined,
      controllerOnline: this.controllerId !== undefined,
      cameras: this.cameraCount(),
      ...(this.hostId === undefined ? {} : { hostId: this.hostId }),
      ...(this.controllerId === undefined ? {} : { controllerId: this.controllerId }),
      cameraIds: [...this.peers.values()].filter((peer) => peer.role === "camera").map((peer) => peer.id),
    }
    for (const peer of this.peers.values()) peer.send(event)
  }
}

function canRelayMediaSignal(sender: Role, recipient: Role): boolean {
  switch (sender) {
    case "host": return recipient === "controller" || recipient === "camera"
    case "controller": return recipient === "host" || recipient === "camera"
    case "camera": return recipient === "host" || recipient === "controller"
  }
}
