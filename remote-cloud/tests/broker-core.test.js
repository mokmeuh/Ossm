import { describe, expect, it } from "vitest";
import { BrokerCore } from "../src/broker-core";
function fakePeer(id, role) {
    const events = [];
    return { peer: { id, role, send: (event) => events.push(event), lastHeartbeat: 0, lastSequence: -1 }, events };
}
describe("BrokerCore", () => {
    it("permits one host and controller, and four video-only cameras", () => {
        const broker = new BrokerCore();
        expect(broker.join(fakePeer("host", "host").peer).kind).toBe("joined");
        expect(broker.join(fakePeer("controller", "controller").peer).kind).toBe("joined");
        for (const id of ["one", "two", "three", "four"])
            expect(broker.join(fakePeer(id, "camera").peer).kind).toBe("joined");
        expect(broker.join(fakePeer("five", "camera").peer)).toMatchObject({ kind: "rejected", event: { code: "capacity" } });
    });
    it("relays only monotonic controller commands to the host", () => {
        const broker = new BrokerCore();
        const host = fakePeer("host", "host");
        const controller = fakePeer("controller", "controller");
        broker.join(host.peer);
        broker.join(controller.peer);
        broker.handle("controller", { type: "command", seq: 1, command: "go:menu" });
        broker.handle("controller", { type: "command", seq: 1, command: "go:menu" });
        expect(host.events).toContainEqual({ type: "command", seq: 1, command: "go:menu", from: "controller" });
        expect(controller.events).toContainEqual({ type: "error", code: "invalid_sequence" });
    });
    it("broadcasts emergency stop and expires a missed heartbeat", () => {
        const broker = new BrokerCore();
        const host = fakePeer("host", "host");
        const controller = fakePeer("controller", "controller");
        broker.join(host.peer);
        broker.join(controller.peer);
        broker.handle("controller", { type: "emergency_stop", seq: 3 });
        broker.expire(1_501);
        expect(host.events).toContainEqual({ type: "emergency_stop", seq: 3, from: "controller" });
        expect(controller.events).toContainEqual({ type: "lease_lost", participantId: "controller", reason: "heartbeat_timeout" });
    });
});
