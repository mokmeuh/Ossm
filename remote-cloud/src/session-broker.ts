import { BrokerCore, type Peer } from "./broker-core"
import { parseInboundMessage, type BrokerEvent, type Role } from "./protocol"

export interface Env {
  readonly ADMIN_TOKEN: string
  readonly SESSIONS: DurableObjectNamespace
  readonly ASSETS: Fetcher
  readonly SESSION_TTL_SECONDS: string
}

// cameraSecret is optional: sessions stored before it existed simply have no camera-only key.
type SessionRecord = { readonly secret: string; readonly expiresAt: number; readonly cameraSecret?: string }

export class SessionBroker implements DurableObject {
  private readonly core = new BrokerCore()
  private readonly state: DurableObjectState
  private session: SessionRecord | undefined

  constructor(state: DurableObjectState) { this.state = state }

  async fetch(request: Request): Promise<Response> {
    const url = new URL(request.url)
    if (url.pathname === "/create" && request.method === "POST") return this.create(request)
    if (url.pathname === "/session") return this.manage(request)
    if (url.pathname === "/ws") return this.openSocket(request)
    if (url.pathname === "/status") return this.status(request)
    return new Response("Not found", { status: 404 })
  }

  private async create(request: Request): Promise<Response> {
    const body = await request.json<unknown>()
    if (!isSessionRecord(body)) return json({ error: "invalid session" }, 400)
    if (await this.getSession() !== undefined) return json({ error: "already_created" }, 409)
    this.session = body
    await this.state.storage.put("session", body)
    return json({ ok: true })
  }

  private async manage(request: Request): Promise<Response> {
    const session = await this.getSession()
    if (session === undefined || !hasSessionCapability(request, session.secret)) return json({ error: "not_found" }, 404)
    if (request.method === "POST") {
      const body = await request.json<unknown>()
      if (!isRenewal(body)) return json({ error: "invalid renewal" }, 400)
      const renewed = { ...session, expiresAt: body.expiresAt }
      this.session = renewed
      await this.state.storage.put("session", renewed)
      return json({ expiresAt: renewed.expiresAt })
    }
    if (request.method === "DELETE") {
      this.session = undefined
      await this.state.storage.delete("session")
      revokeSessionPeers(this.core)
      return new Response(null, { status: 204 })
    }
    return new Response("Method not allowed", { status: 405, headers: { Allow: "POST, DELETE" } })
  }

  // Public, non-sensitive liveness probe: reveals only active/expired, never the secret or participants.
  private async status(request: Request): Promise<Response> {
    if (request.method !== "GET" && request.method !== "HEAD") return new Response("Method not allowed", { status: 405, headers: { Allow: "GET, HEAD" } })
    const session = await this.getSession()
    const active = session !== undefined && Date.now() < session.expiresAt
    return Response.json({ status: active ? "active" : "expired" }, { status: active ? 200 : 410, headers: { "Cache-Control": "no-store" } })
  }

  private async openSocket(request: Request): Promise<Response> {
    if (request.headers.get("Upgrade") !== "websocket") return new Response("Expected WebSocket", { status: 426 })
    const session = await this.getSession()
    if (session === undefined || Date.now() >= session.expiresAt) return json({ error: "expired" }, 410)
    const pair = new WebSocketPair()
    const client = pair[0]
    const server = pair[1]
    if (client === undefined || server === undefined) return new Response("WebSocket setup failed", { status: 500 })
    server.accept()
    const peerId = crypto.randomUUID()
    let peer: Peer | undefined
    server.addEventListener("message", (event) => {
      const message = parseSocketMessage(event.data)
      if (message === null) return send(server, { type: "error", code: "invalid_message" })
      if (peer === undefined) {
        if (message.type !== "hello" || !isAdmitted(session, message.secret, message.role)) return send(server, { type: "error", code: "forbidden" })
        peer = { id: peerId, role: message.role, send: (payload) => send(server, payload), close: (code, reason) => server.close(code, reason), lastHeartbeat: Date.now(), lastSequence: -1 }
        const joined = this.core.join(peer)
        if (joined.kind === "rejected") { send(server, joined.event); server.close(1008, "role unavailable"); return }
        this.state.waitUntil(this.scheduleExpiry())
        send(server, { type: "ready", participantId: peerId, role: peer.role, expiresAt: session.expiresAt })
        return
      }
      // A peer the core already removed (e.g. heartbeat timeout) must reconnect, not linger silently.
      if (!this.core.peers.has(peer.id)) { server.close(4000, "lease_lost"); return }
      this.core.handle(peer.id, message)
    })
    const disconnect = (): void => { if (peer !== undefined) this.core.remove(peer.id) }
    server.addEventListener("close", disconnect)
    server.addEventListener("error", disconnect)
    this.scheduleExpiry()
    return new Response(null, { status: 101, webSocket: client })
  }

  async alarm(): Promise<void> {
    const session = await this.getSession()
    if (session === undefined || Date.now() >= session.expiresAt) {
      this.session = undefined
      await this.state.storage.delete("session")
      revokeSessionPeers(this.core, { type: "error", code: "expired" }, "expired")
      return
    }
    this.core.expire(Date.now())
    await this.scheduleExpiry()
  }
  private async getSession(): Promise<SessionRecord | undefined> { return this.session ?? await this.state.storage.get<SessionRecord>("session") }
  private async scheduleExpiry(): Promise<void> { if (this.core.peers.size > 0) await this.state.storage.setAlarm(Date.now() + 500) }
}

export function revokeSessionPeers(
  core: BrokerCore,
  error: Extract<BrokerEvent, { readonly type: "error" }> = { type: "error", code: "forbidden" },
  closeReason = "revoked",
): void {
  core.clearLimits()
  for (const peer of [...core.peers.values()]) {
    peer.send(error)
    core.remove(peer.id, "replaced")
    peer.close?.(1008, closeReason)
  }
}

function isSessionRecord(value: unknown): value is SessionRecord {
  if (typeof value !== "object" || value === null) return false
  const candidate = value as { readonly secret?: unknown; readonly expiresAt?: unknown; readonly cameraSecret?: unknown }
  if (candidate.cameraSecret !== undefined && (typeof candidate.cameraSecret !== "string" || candidate.cameraSecret.length < 24 || candidate.cameraSecret === candidate.secret)) return false
  return typeof candidate.secret === "string" && candidate.secret.length >= 24 && typeof candidate.expiresAt === "number"
}

/** The main secret admits any role; the camera-only secret admits the camera role and nothing else. */
export function isAdmitted(session: SessionRecord, secret: string, role: Role): boolean {
  if (secureEqual(secret, session.secret)) return true
  return role === "camera" && session.cameraSecret !== undefined && secureEqual(secret, session.cameraSecret)
}

function isRenewal(value: unknown): value is { readonly expiresAt: number } {
  if (typeof value !== "object" || value === null) return false
  const candidate = value as { readonly expiresAt?: unknown }
  return typeof candidate.expiresAt === "number" && Number.isFinite(candidate.expiresAt) && candidate.expiresAt > Date.now()
}

export function hasSessionCapability(request: Request, secret: string): boolean {
  const authorization = request.headers.get("Authorization")
  return authorization !== null && authorization.startsWith("Bearer ") && secureEqual(authorization.substring("Bearer ".length), secret)
}

function parseSocketMessage(value: unknown): ReturnType<typeof parseInboundMessage> {
  if (typeof value !== "string") return null
  try { return parseInboundMessage(JSON.parse(value) as unknown) } catch { return null }
}

function secureEqual(left: string, right: string): boolean {
  if (left.length !== right.length) return false
  let result = 0
  for (let index = 0; index < left.length; index += 1) result |= left.charCodeAt(index) ^ right.charCodeAt(index)
  return result === 0
}

function send(socket: WebSocket, event: BrokerEvent): void { socket.send(JSON.stringify(event)) }
function json(body: object, status = 200): Response { return Response.json(body, { status }) }
