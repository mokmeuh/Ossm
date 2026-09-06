import { isSessionId, type SessionRequest } from "./protocol"
import { SessionBroker, type Env } from "./session-broker"

export { SessionBroker }

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url)
    if (url.pathname === "/api/sessions" && request.method === "POST") return createSession(request, env, url)
    const websocketMatch = /^\/api\/session\/([A-Za-z0-9_-]{8,64})\/ws$/.exec(url.pathname)
    const websocketSessionId = websocketMatch?.[1]
    if (websocketSessionId !== undefined) return env.SESSIONS.get(env.SESSIONS.idFromName(websocketSessionId)).fetch(new Request(new URL("/ws", url), request))
    const sessionMatch = /^\/s\/([A-Za-z0-9_-]{8,64})$/.exec(url.pathname)
    if (sessionMatch !== null) return env.ASSETS.fetch(new Request(new URL("/index.html", url), request))
    return env.ASSETS.fetch(request)
  },
} satisfies ExportedHandler<Env>

async function createSession(request: Request, env: Env, url: URL): Promise<Response> {
  const body = await request.json<unknown>()
  const requestedId = sessionId(body)
  const id = requestedId ?? crypto.randomUUID().replaceAll("-", "")
  const ttl = Number.parseInt(env.SESSION_TTL_SECONDS, 10)
  const expiresAt = Date.now() + (Number.isFinite(ttl) ? ttl : 3600) * 1000
  const secret = toBase64Url(crypto.getRandomValues(new Uint8Array(32)))
  const brokerId = env.SESSIONS.idFromName(id)
  await env.SESSIONS.get(brokerId).fetch("https://session/create", { method: "POST", body: JSON.stringify({ secret, expiresAt }) })
  const result: SessionRequest = { id, secret }
  return Response.json({ ...result, url: `${url.origin}/s/${id}#k=${secret}`, expiresAt })
}

function authorized(request: Request, token: string): boolean { return request.headers.get("Authorization") === `Bearer ${token}` }
function sessionId(value: unknown): string | undefined {
  if (typeof value !== "object" || value === null) return undefined
  const candidate = value as { readonly sessionId?: unknown }
  return typeof candidate.sessionId === "string" && isSessionId(candidate.sessionId) ? candidate.sessionId : undefined
}
function toBase64Url(bytes: Uint8Array): string {
  let binary = ""
  for (const byte of bytes) binary += String.fromCharCode(byte)
  return btoa(binary).replaceAll("+", "-").replaceAll("/", "_").replaceAll("=", "")
}
