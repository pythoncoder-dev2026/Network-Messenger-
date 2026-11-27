# Network messenger v1.0.0 (`com.network.messenger`) — Multi-Hop & Bridge Test Guide

This guide verifies end-to-end encrypted multi-hop offline relaying across a 3-phone chain (**A — B — C**) plus a 4th **Internet-only phone (D)** bridged into the same mesh network through the Node.js WebSocket relay server.

---

## Prerequisites

1. Build and install the APK (`com.network.messenger`, version `1.0.0`) on 4 Android phones (**A**, **B**, **C**, **D**).
2. Start the Node.js WebSocket relay server on a laptop or cloud host:
   ```bash
   cd relay-server
   npm install
   npm start
   ```
   Verify `http://<HOST_IP>:8080/health` returns `"status": "ok"`.
3. On all 4 phones, verify the **first line** of the Live Log Console reads:
   `Network messenger v1.0.0 (com.network.messenger) | uid=<12-char-id>`
4. Grant runtime permissions (Bluetooth/Nearby, Wi-Fi Nearby/Location, Microphone, Notifications) when prompted, and verify `[PERM] Permission granted` events appear in the log.

---

## Scenario 1: 3-Phone Offline Chain (`A — B — C`, with `B` Relaying)

**Goal:** Prove encrypted multi-hop packet forwarding with `TTL=5`, duplicate dropping, and store-and-forward without Internet.

1. **Disable Wi-Fi & Cellular Data** on **A**, **B**, and **C** (leave Bluetooth ON).
2. Place **A** and **C** far enough apart (or in separate rooms) that they do **not** connect directly, with **B** positioned halfway between them so **B** connects to both **A** and **C**.
3. On **A**, **B**, and **C**, tap the **`BT/BLE`** transport pill in the bottom controls bar.
   - Verify `[XPORT] [BT/BLE] Nearby advertising started (P2P_CLUSTER)` and `Nearby discovery started`.
   - Verify **B** logs `[PEER] [BT/BLE] Peer connected` for both **A** and **C** (`total=2`).
4. On **A**, tap **`New`** → create network `"Field-Mesh"` (with optional password `"alpha123"`).
   - Verify `[NET] Network created: "Field-Mesh" ... role=OWNER keyVer=1`.
   - A QR code and `NMSG1:...` share code appear.
5. On **C**, tap **`Join`** → scan **A**'s QR code (or paste the share code) and enter password `"alpha123"`.
   - Verify **C** logs `[NET] Network joined` and `[CRYPTO] Symmetric AES-256-GCM group key unlocked`.
   - *(Note: **B** does NOT even need to join `"Field-Mesh"` to relay ciphertext packets! Relaying works blindly on routing metadata + Ed25519 signature verification.)*
6. On **A**, type a text message and tap **Send**:
   - **A** logs: `[MSG] Sent TEXT id=... net=... size=...B keyVer=1 links=1 [sent]` (notice plaintext is **never** logged).
   - **B** logs: `[MSG] Relayed TEXT id=... hops=1 ttl=4 links=1 [relayed]`.
   - **C** logs: `[MSG] Received TEXT id=... hops=1 via=BT/BLE [delivered]` and sends an encrypted `DELIVERY_ACK` back through **B**.
   - **A** logs: `[MSG] Delivered ACK for id=... hops=1 [delivered]`.
7. **Hold `PTT`** on **A** for 2 seconds and release:
   - Verify **A** logs `[VOICE] PTT recording started ... codec=opus-16k (16 kbps)` (or fallback codec on older devices).
   - Verify **B** relays the encrypted voice packets (`hops=1`) and **C** logs `[VOICE] Received VOICE_...` and plays audio automatically (when `Auto` is ON).

---

## Scenario 2: Bridging a 4th Internet-Only Phone (`D`) Into the Same Network

**Goal:** Prove cross-transport bridging (`BT/BLE` ↔ `Internet WebSocket`) using **B** (or **C**) as a dual-transport bridge node.

1. Keep **A** offline (Bluetooth/Nearby only).
2. On **B** (the bridge phone) and **D** (an Internet-only phone anywhere else with Bluetooth OFF):
   - Open **Settings (⚙)** → set **Internet Relay WebSocket URL** to `ws://<HOST_IP>:8080` → tap **Save**.
   - Tap the **`WS`** transport pill in the bottom controls bar on both **B** and **D**.
   - Verify both log `[XPORT] [WS] Connected to Internet relay`.
3. Share the `"Field-Mesh"` share code (`NMSG1:...`) with **D** → on **D**, tap **`Join`** and paste the code + password.
4. On **A** (offline, BT/BLE only), send a text message or PTT voice message:
   - **A** sends over `BT/BLE` → **B** receives over `BT/BLE` and automatically bridges/relays it over `WS` to the Node.js relay server → **D** receives over `WS` (`hops=1`, `[delivered]`)!
   - Inspect the Node.js relay console: it prints only `[RELAY] from=... net=... bytes=... fanout=1` — zero plaintext or keys.
5. Reply from **D** (Internet-only):
   - **D** → `WS` → **B** bridges `WS` → `BT/BLE` → **A** receives (`hops=1`, `[delivered]`)!

---

## Scenario 3: Security, Blocking, Key Rotation & Edge Cases

1. **Blocking a User:**
   - Copy **C**'s User ID from **C**'s Settings sheet.
   - On **A**, open **Settings (⚙)** → **Blocked Users** → paste **C**'s User ID → tap **Block**.
   - Verify `[BLOCK] User blocked` and automatic `[CRYPTO] Network key rotated: ... keyVer=2` on networks owned by **A**.
   - Send a text and PTT voice from **C**:
     - Verify **A** logs `[BLOCK] Dropped ... from blocked uid=... (reason=blocked)` — message is neither displayed nor relayed, and **C**'s voice never plays.
     - Verify **D** (still a member) receives the X25519+HKDF `KEY_ROTATE` packet (`keyVer=2`), while **C** cannot decrypt new messages from **A** or **D** (`[CRYPTO] Dropped ... (reason=decrypt_failed)`).
2. **Duplicate Packet Dropping:**
   - Enable both **`BT/BLE`** and **`WIFI`** simultaneously on **A** and **B** on the same Wi-Fi LAN.
   - Send a message: verify the second copy arriving over the alternate transport is dropped with `(reason=duplicate)`.
3. **Store-and-Forward (Network Loss / Offline Peer):**
   - Stop all transports on **C** (or walk out of range). Send a text and hold-to-talk clip from **A**.
   - Verify **A** logs `[MSG] Queued ... (store-and-forward) [queued]` and PTT switches to `CLIP`.
   - Re-enable transport on **C** → verify **A** logs `Store-and-forward flushed ... [sent]` and **C** receives it.
4. **Bluetooth Off:**
   - Turn off system Bluetooth while `BT/BLE` is active → verify `[XPORT] [BT/BLE] Bluetooth turned OFF` (degraded status, no crash).
5. **Permission Denied:**
   - Deny Microphone or Bluetooth permission → tap `PTT` or `BT/BLE` → verify `[PERM] ... permission denied` is logged cleanly without crashing.
6. **Audio Focus:**
   - Play music in another app while receiving an incoming PTT voice message → verify music ducks/pauses during playback and audio focus events are logged under `[VOICE]`.
7. **Low Battery:**
   - Trigger low battery (`adb shell dumpsys battery set level 10`) → verify `[SYS] Low battery detected` and PTT switches from `LIVE` to `CLIP` mode.
8. **Screen-Off Operation:**
   - Lock the screen on **B** (bridge node) while transports are active → verify the persistent `Network messenger active` foreground service notification remains visible and packets continue relaying from **A** to **C** / **D**.
