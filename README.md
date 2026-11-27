# Network messenger

Network messenger is an Android app (Kotlin + Jetpack Compose) that combines a secure messenger with a push-to-talk walkie-talkie. Users can create or join mesh networks and communicate over Bluetooth, Wi-Fi or the Internet, using any one of them or several at once, with end-to-end encryption.

- Package: `com.network.messenger`
- Version: 1.0.0

## Features

- **Mesh networking:** create your own network or join someone else's. Messages hop between nearby phones, so people out of direct range can still reach each other.
- **Multiple transports:** Bluetooth/BLE and Wi-Fi nearby (works offline), local Wi-Fi, and an Internet relay. Phones with more than one transport can bridge them.
- **Text and voice:** send text messages and push-to-talk voice messages (Opus audio).
- **End-to-end encryption:** all messages and voice are encrypted on the device, and packets are signed. Relays and the server only see ciphertext.
- **Blocking and network control:** block users, and network owners can remove members and rotate keys.
- **Built-in log viewer:** a small, toggleable log panel shows connection, routing and security events (metadata only, never message content or keys).
- **Messenger-style UI:** chat list, chat bubbles, voice bubbles and a hold-to-talk button, with light and dark themes.

## Tech stack

Kotlin, Jetpack Compose (Material 3), MVVM, Hilt, Room, Coroutines/Flow, Google Nearby Connections, Opus, and a Node.js WebSocket relay server.

## Status

Early development (v1.0.0). Planned: delivery and read receipts, offline message queue, key verification, SOS and live location, file sharing, disappearing messages, and a 3D UI.

## Security note

Cryptography uses well-known libraries (Tink or libsodium), not custom crypto. This project has not had an independent security audit, so do not rely on it for life-critical or highly sensitive communication yet.

## License

Add your preferred license (for example MIT or Apache-2.0).
