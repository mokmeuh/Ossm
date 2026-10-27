# Firmware BLE Remote-Control Lease

The existing BLE command characteristic (`522b443a-4f53-534d-1000-420badbabe69`)
keeps its legacy text commands. Firmware supporting the remote-control safety
boundary additionally accepts these text commands:

- `remote:claim`: starts an explicit lease only for a bonded and encrypted BLE
  connection. The device replies `ok:remote:claimed`.
- `remote:heartbeat`: must be written by the lease owner at least every 250 ms.
  A heartbeat from any other connection invalidates the lease and force-stops.
- `remote:release`: ends the owner's lease and force-stops.

While a lease is active, only its owning BLE connection may send motion
commands or Live Smooth targets. Legacy BLE control remains unchanged when no
lease is active. USB ownership remains authoritative and prevents a BLE lease
from being claimed.

Lease timeout, lease-owner disconnect, an invalid heartbeat, and release all
immediately stop motion, disable motor outputs, exit to the menu, and clear the
homed state. A physical homing cycle is therefore required before subsequent
movement. This is firmware behavior only; it has not been validated on hardware.

The existing state JSON adds `remoteControlSafe` (protocol capability),
`watchdogMs` (250), `homed`, and `transport` (`ble-local` or `ble-lease`) so a
client can determine whether the safety contract is available and active.
