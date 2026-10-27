import { z } from "zod"

export const ROLES = ["host", "controller", "camera"] as const
export type Role = (typeof ROLES)[number]

const roleSchema = z.enum(ROLES)
const sequenceSchema = z.number().int().nonnegative()
const sessionIdSchema = z.string().regex(/^[A-Za-z0-9_-]{8,64}$/)
export const DIAGNOSTIC_CODES = [
  "firmware_remote_auth_required",
  "remote_lease_invalid",
  "remote_session_not_started",
  "remote_machine_not_ready",
  "remote_sequence_stale",
  "remote_live_position_rejected",
] as const
export type DiagnosticCode = (typeof DIAGNOSTIC_CODES)[number]

const diagnosticSchema = z.object({
  type: z.literal("diagnostic"), severity: z.literal("warning"), code: z.enum(DIAGNOSTIC_CODES),
}).strict()
const fractionSchema = z.number().min(0).max(1)
// Sensation is bipolar (0.5 = neutral). The phone publishes the allowed band as sensationMin and
// sensationMax together (newer APKs); sensationMax alone is the legacy 2.3.14/2.3.15 ceiling
// [0, sensationMax] and stays valid; a 4-key frame from an older APK stays valid too. sensationMin
// alone, an inverted band and any other key are rejected.
const limitsSchema = z.object({
  type: z.literal("limits"), speedMax: fractionSchema, depthMin: fractionSchema, depthMax: fractionSchema,
  sensationMin: fractionSchema.optional(), sensationMax: fractionSchema.optional(),
}).strict()
  .refine((limits) => limits.depthMin <= limits.depthMax)
  .refine((limits) => limits.sensationMin === undefined || (limits.sensationMax !== undefined && limits.sensationMin <= limits.sensationMax))
const signalSchema = z.object({
  type: z.literal("signal"), to: z.string().min(1).max(128), data: z.string().max(32_768),
}).strict()

export const inboundMessageSchema = z.discriminatedUnion("type", [
  z.object({ type: z.literal("hello"), role: roleSchema, secret: z.string().min(24).max(256) }).strict(),
  z.object({ type: z.literal("heartbeat") }).strict(),
  z.object({ type: z.literal("command"), seq: sequenceSchema, command: z.string().min(1).max(512) }).strict(),
  z.object({ type: z.literal("emergency_stop"), seq: sequenceSchema }).strict(),
  diagnosticSchema,
  limitsSchema,
  signalSchema,
])

export type InboundMessage = z.infer<typeof inboundMessageSchema>
export type BrokerEvent =
  | { readonly type: "ready"; readonly participantId: string; readonly role: Role; readonly expiresAt: number }
  | { readonly type: "presence"; readonly hostOnline: boolean; readonly controllerOnline: boolean; readonly cameras: number; readonly hostId?: string; readonly controllerId?: string; readonly cameraIds: readonly string[] }
  | { readonly type: "lease_lost"; readonly participantId: string; readonly reason: "heartbeat_timeout" | "disconnected" | "replaced" }
  | { readonly type: "command"; readonly seq: number; readonly command: string; readonly from: string }
  | { readonly type: "emergency_stop"; readonly seq: number; readonly from: string }
  | { readonly type: "diagnostic"; readonly severity: "warning"; readonly code: DiagnosticCode }
  | { readonly type: "limits"; readonly speedMax: number; readonly depthMin: number; readonly depthMax: number; readonly sensationMin?: number; readonly sensationMax?: number }
  | { readonly type: "signal"; readonly from: string; readonly data: string }
  | { readonly type: "error"; readonly code: "forbidden" | "invalid_sequence" | "capacity" | "expired" | "invalid_message" }

export type SessionRequest = { readonly id: string; readonly secret: string }

export function parseInboundMessage(value: unknown): InboundMessage | null {
  const parsed = inboundMessageSchema.safeParse(value)
  return parsed.success ? parsed.data : null
}

export function isSessionId(value: string): boolean {
  return sessionIdSchema.safeParse(value).success
}

export function assertNever(value: never): never {
  throw new TypeError(`Unexpected protocol variant: ${JSON.stringify(value)}`)
}
