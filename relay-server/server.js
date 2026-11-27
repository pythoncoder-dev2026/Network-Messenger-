/**
 * Network messenger v1.0.0 (com.network.messenger) — Zero-Knowledge WebSocket Relay Server
 *
 * Forwards signed, end-to-end encrypted MeshPacket frames between connected peers.
 * The server never holds network keys and never inspects decrypted message or voice payloads.
 */
const http = require("http");
const { WebSocketServer } = require("ws");

const PORT = parseInt(process.env.PORT || "8080", 10);
const HOST = process.env.HOST || "0.0.0.0";
const MAX_PAYLOAD_BYTES = 512 * 1024; // 512 KB cap per frame
const RATE_LIMIT_WINDOW_MS = 10_000;
const RATE_LIMIT_MAX_FRAMES = 250;

const clients = new Map(); // ws -> { nodeId, networks: Set<string>, windowStart, count }

const server = http.createServer((req, res) => {
  if (req.url === "/health" && req.method === "GET") {
    res.writeHead(200, { "Content-Type": "application/json" });
    res.end(
      JSON.stringify({
        service: "Network messenger relay",
        package: "com.network.messenger",
        version: "1.0.0",
        status: "ok",
        peersOnline: clients.size,
      })
    );
    return;
  }
  res.writeHead(404);
  res.end("Not found");
});

const wss = new WebSocketServer({
  server,
  maxPayload: MAX_PAYLOAD_BYTES,
});

function broadcastPeerStatus() {
  const statusMsg = JSON.stringify({
    action: "status",
    peersOnline: clients.size,
    ts: Date.now(),
  });
  for (const ws of clients.keys()) {
    if (ws.readyState === ws.OPEN) {
      ws.send(statusMsg);
    }
  }
}

function checkRateLimit(meta) {
  const now = Date.now();
  if (now - meta.windowStart > RATE_LIMIT_WINDOW_MS) {
    meta.windowStart = now;
    meta.count = 0;
  }
  meta.count += 1;
  return meta.count <= RATE_LIMIT_MAX_FRAMES;
}

wss.on("connection", (ws, req) => {
  const remoteAddr = req.socket.remoteAddress || "unknown";
  const meta = {
    nodeId: "anon",
    networks: new Set(),
    windowStart: Date.now(),
    count: 0,
  };
  clients.set(ws, meta);
  console.log(`[CONNECT] ${remoteAddr} | total=${clients.size}`);
  broadcastPeerStatus();

  ws.on("message", (raw) => {
    if (!checkRateLimit(meta)) {
      console.warn(`[RATE_LIMIT] Dropping burst from node=${meta.nodeId.slice(0, 12)}`);
      return;
    }

    let frame;
    try {
      frame = JSON.parse(raw.toString("utf8"));
    } catch {
      return;
    }
    if (!frame || typeof frame.action !== "string") return;

    switch (frame.action) {
      case "register":
      case "subscribe": {
        if (typeof frame.nodeId === "string" && frame.nodeId.length <= 128) {
          meta.nodeId = frame.nodeId;
        }
        if (Array.isArray(frame.networks)) {
          meta.networks = new Set(
            frame.networks.filter((n) => typeof n === "string" && n.length <= 128)
          );
        }
        console.log(
          `[${frame.action.toUpperCase()}] node=${meta.nodeId.slice(0, 12)} nets=${meta.networks.size}`
        );
        broadcastPeerStatus();
        break;
      }

      case "relay": {
        if (typeof frame.payloadBase64 !== "string" || frame.payloadBase64.length === 0) {
          return;
        }
        // Inspect only unencrypted routing metadata (networkId) if present; never log payloads
        let targetNetworkId = null;
        try {
          const envelope = JSON.parse(Buffer.from(frame.payloadBase64, "base64").toString("utf8"));
          if (envelope && typeof envelope.networkId === "string") {
            targetNetworkId = envelope.networkId;
          }
        } catch {
          // Opaque envelope — still forward to peers
        }

        const outbound = JSON.stringify({
          action: "relay",
          payloadBase64: frame.payloadBase64,
          ts: Date.now(),
        });

        let delivered = 0;
        for (const [peerWs, peerMeta] of clients.entries()) {
          if (peerWs === ws || peerWs.readyState !== peerWs.OPEN) continue;
          // Forward if peer subscribed to this network or has no filter yet (for discovery/invites)
          if (
            !targetNetworkId ||
            peerMeta.networks.size === 0 ||
            peerMeta.networks.has(targetNetworkId)
          ) {
            peerWs.send(outbound);
            delivered += 1;
          }
        }
        console.log(
          `[RELAY] from=${meta.nodeId.slice(0, 12)} net=${
            targetNetworkId ? targetNetworkId.slice(0, 12) : "broadcast"
          } bytes=${frame.payloadBase64.length} fanout=${delivered}`
        );
        break;
      }

      default:
        break;
    }
  });

  ws.on("close", () => {
    clients.delete(ws);
    console.log(`[DISCONNECT] node=${meta.nodeId.slice(0, 12)} | total=${clients.size}`);
    broadcastPeerStatus();
  });

  ws.on("error", (err) => {
    console.warn(`[WS_ERROR] node=${meta.nodeId.slice(0, 12)}: ${err.message}`);
  });
});

server.listen(PORT, HOST, () => {
  console.log(
    `Network messenger v1.0.0 WebSocket relay listening on ws://${HOST}:${PORT} (health: /health)`
  );
});
