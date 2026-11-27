# Network messenger — WebSocket Relay Server

Zero-knowledge Internet relay for **Network messenger v1.0.0 (`com.network.messenger`)**.

## Run locally

```bash
cd relay-server
npm install
npm start
```

The server listens on `ws://0.0.0.0:8080` by default (override with `PORT` and `HOST` environment variables).

- **Android Emulator:** use `ws://10.0.2.2:8080` in the app's Settings → Internet Relay WebSocket URL.
- **Physical Android phones on LAN:** use `ws://<YOUR_COMPUTER_LAN_IP>:8080`.
- **Health check:** `GET http://<HOST>:8080/health`.

## Security Notes
- The relay server never possesses network symmetric keys or user private keys.
- All text, voice, and key exchange payloads are encrypted end-to-end (AES-256-GCM / X25519+HKDF) and signed (Ed25519) before leaving the phone.
- For public Internet deployments, terminate TLS with a reverse proxy (Caddy/Nginx) and connect via `wss://`.
