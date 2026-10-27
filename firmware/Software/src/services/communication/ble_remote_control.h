#ifndef OSSM_BLE_REMOTE_CONTROL_H
#define OSSM_BLE_REMOTE_CONTROL_H

#include <atomic>
#include <cstdint>

namespace ble_remote_control {

inline constexpr uint32_t kWatchdogMs = 250;

class Lease {
   public:
    bool claim(uint16_t connectionHandle, bool authenticated, uint32_t nowMs) {
        if (!authenticated || active_.load()) return false;
        owner_.store(connectionHandle);
        lastHeartbeatMs_.store(nowMs);
        active_.store(true);
        return true;
    }

    bool heartbeat(uint16_t connectionHandle, uint32_t nowMs) {
        if (!isOwner(connectionHandle)) {
            clear();
            return false;
        }
        lastHeartbeatMs_.store(nowMs);
        return true;
    }

    bool release(uint16_t connectionHandle) {
        if (!isOwner(connectionHandle)) return false;
        clear();
        return true;
    }

    bool disconnected(uint16_t connectionHandle) {
        if (!isOwner(connectionHandle)) return false;
        clear();
        return true;
    }

    bool expired(uint32_t nowMs) {
        if (!active_.load() ||
            static_cast<uint32_t>(nowMs - lastHeartbeatMs_.load()) <
                kWatchdogMs) {
            return false;
        }
        clear();
        return true;
    }

    bool active() const { return active_.load(); }

    bool isOwner(uint16_t connectionHandle) const {
        return active_.load() && owner_.load() == connectionHandle;
    }

    void clear() {
        active_.store(false);
        owner_.store(0);
        lastHeartbeatMs_.store(0);
    }

   private:
    std::atomic<bool> active_{false};
    std::atomic<uint16_t> owner_{0};
    std::atomic<uint32_t> lastHeartbeatMs_{0};
};

}

extern ble_remote_control::Lease bleRemoteControlLease;

#endif
