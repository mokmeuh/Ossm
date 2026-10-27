import { SessionBroker } from "./session-broker";
export { SessionBroker };
export default {
    async fetch(request, env) {
        const url = new URL(request.url);
        if (url.pathname === "/api/sessions" && request.method === "POST")
            return createSession(request, env, url);
        const sessionMatch = /^\/api\/session\/([A-Za-z0-9_-]{8,64})$/.exec(url.pathname);
        const sessionId = sessionMatch?.[1];
        if (sessionId !== undefined && (request.method === "POST" || request.method === "DELETE"))
            return manageSession(request, env, sessionId);
        const statusMatch = /^\/api\/session\/([A-Za-z0-9_-]{8,64})\/status$/.exec(url.pathname);
        const statusSessionId = statusMatch?.[1];
        if (statusSessionId !== undefined && (request.method === "GET" || request.method === "HEAD"))
            return env.SESSIONS.get(env.SESSIONS.idFromName(statusSessionId)).fetch(new Request("https://session/status", { method: request.method }));
        const websocketMatch = /^\/api\/session\/([A-Za-z0-9_-]{8,64})\/ws$/.exec(url.pathname);
        const websocketSessionId = websocketMatch?.[1];
        if (websocketSessionId !== undefined)
            return env.SESSIONS.get(env.SESSIONS.idFromName(websocketSessionId)).fetch(new Request(new URL("/ws", url), request));
        const shareMatch = /^\/s\/([A-Za-z0-9_-]{8,64})$/.exec(url.pathname);
        if (shareMatch !== null)
            return env.ASSETS.fetch(new Request(new URL("/index.html", url), request));
        return env.ASSETS.fetch(request);
    },
};
async function createSession(request, env, url) {
    await request.text();
    const id = crypto.randomUUID().replaceAll("-", "");
    const expiresAt = nextExpiry(env);
    const secret = toBase64Url(crypto.getRandomValues(new Uint8Array(32)));
    // A separate camera-only key: it can join the session as a camera and nothing else.
    const cameraSecret = toBase64Url(crypto.getRandomValues(new Uint8Array(32)));
    const brokerId = env.SESSIONS.idFromName(id);
    const brokerResponse = await env.SESSIONS.get(brokerId).fetch("https://session/create", { method: "POST", body: JSON.stringify({ secret, cameraSecret, expiresAt }) });
    if (!brokerResponse.ok)
        return brokerResponse;
    // `cameraUrl` is additive: older hosts read only `url`. Keys stay in the fragment, never in a request.
    return Response.json({ url: `${url.origin}/s/${id}#k=${secret}`, cameraUrl: `${url.origin}/s/${id}?role=camera#k=${cameraSecret}`, expiresAt });
}
function manageSession(request, env, sessionId) {
    const authorization = request.headers.get("Authorization");
    const headers = authorization === null ? new Headers() : new Headers({ Authorization: authorization });
    const init = request.method === "POST"
        ? { method: "POST", headers, body: JSON.stringify({ expiresAt: nextExpiry(env) }) }
        : { method: "DELETE", headers };
    return env.SESSIONS.get(env.SESSIONS.idFromName(sessionId)).fetch(new Request("https://session/session", init));
}
function nextExpiry(env) {
    const ttl = Number.parseInt(env.SESSION_TTL_SECONDS, 10);
    return Date.now() + (Number.isFinite(ttl) && ttl > 0 ? ttl : 3600) * 1000;
}
function toBase64Url(bytes) {
    let binary = "";
    for (const byte of bytes)
        binary += String.fromCharCode(byte);
    return btoa(binary).replaceAll("+", "-").replaceAll("/", "_").replaceAll("=", "");
}
