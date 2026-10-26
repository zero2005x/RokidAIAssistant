# ADR 0002: Shared binary protocol, extended through the capability handshake

- **Status:** Accepted
- **Date:** 2026-10-08

## Context

`:phone-app` and `:glasses-app` are installed on two separate devices and updated independently; a newer phone build may connect to an older glasses build or vice versa. Over a single Bluetooth RFCOMM/SPP socket (`BluetoothSppManager` on the phone, `BluetoothSppClient` on the glasses), the two apps exchange both short control/text messages and multi-hundred-kilobyte binary payloads (recorded PCM audio, compressed JPEG photos, and Live video frames).

In addition, hardware transport capabilities vary by build: older setups relied on the proprietary Rokid CXR BLE SDK to trigger and transfer photos, whereas current `:glasses-app` builds capture photos directly via Android `Camera2` and transfer them over SPP. Upcoming features (such as streaming answer deltas) also need to be introduced without breaking older peers.

## Decision

We define a shared protocol in `:common` (`com.example.rokidcommon.protocol`) and evolve it through capability negotiation on connect:

1. **Multiplexed SPP framing:**
   - Text and control messages use newline-delimited JSON (`Message.toJson` / `Message.fromJson`), with embedded binary payloads Base64-encoded (`binaryData`) and message categories partitioned into stable `MessageType` code ranges (`0x00–0x0F` connection, `0x10–0x1F` voice, `0x20–0x2F` AI processing, `0x30–0x3F` display control, `0x40–0x4F` photo transfer, `0x50–0x5F` Live mode, `0xF0–0xFF` system control).
   - Chunked photo transfers use binary packets (`PhotoTransferConstants`: `START`, `DATA`, `END`) with CRC32 verification and `PHOTO_ACK` / `PHOTO_RETRY` flow control over the same stream.
   - Unknown `MessageType` codes resolve to `null` (`MessageType.fromCode`) and are ignored rather than throwing exceptions.
2. **Capability handshake on connect (`DISPLAY_METRICS`, `0x33`):**
   - Immediately upon Bluetooth SPP connection, the phone sends the active `SYSTEM_CONFIG` (`GlassesDisplayConfig`) followed by a `DISPLAY_METRICS` request (`PhoneAIService`).
   - Glasses builds that understand the request reply with `DISPLAY_METRICS` carrying their viewport metrics (`widthPx`, `heightPx`, `density`, `fontScale`) and transport capability flags (such as `"cameraTransport": "spp"` in `GlassesDisplayMetrics`).
   - When `"cameraTransport": "spp"` is advertised, the phone marks `companionCamera = true` and cancels the 3-second delayed CXR BLE SDK initialization; if no capability response arrives within the window, the phone falls back to the legacy path.

## Consequences

- Phone and glasses APKs can be upgraded independently without lockstep version requirements.
- New protocol features (SPP camera capture today, and streaming text deltas in future releases) are gated behind capability flags advertised during the connection exchange, preserving the whole-answer (`AI_RESPONSE_TEXT`) and legacy transport fallbacks for older peers.
- Both apps must treat unknown `MessageType` codes and unrecognized JSON fields as non-fatal no-ops.
