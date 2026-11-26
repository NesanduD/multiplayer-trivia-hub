# Multiplayer Trivia Game Hub (V2)

Java networking project demonstrating TCP sockets, UDP multicast discovery, Java NIO, multithreading, HTTP/REST, and a browser WebSocket client — aligned with the UoM Network Programming module.

**V2 slice (this release):** externalized config, ordered worker dispatch off the NIO selector, application heartbeats with idle cleanup, and secure token-based session recovery.

## Modules

| Module | Role |
|--------|------|
| `trivia-shared` | Protocol codec, models, DTOs (`RESUME`, session tokens) |
| `trivia-server` | NIO TCP, WebSocket bridge, UDP beacon, rooms/engine, HTTP UI/stats, heartbeat + session registry |
| `trivia-client` | Optional Swing/TCP client with heartbeat + `resume()` |

## Ports (defaults)

- **Web UI + HTTP API:** `8080` → [http://localhost:8080/](http://localhost:8080/)
- **WebSocket game channel:** `5001`
- **TCP game (NIO):** `5000`
- **UDP multicast discovery:** `239.255.77.77:4445`

Override via `trivia-hub.properties`, env vars (`TRIVIA_TCP_PORT`, …), or CLI:  
`java -jar … [tcpPort] [httpPort] [serverName] [wsPort]`

## Build & run

```bash
mvn clean package
java -jar trivia-server/target/trivia-server-1.0.0-SNAPSHOT.jar
```

Open **http://localhost:8080/** — enter a name → **Play now** → create/join → **Start**.

### V2 behaviours to demo

1. **Heartbeat:** clients send `PING`; idle sockets beyond `heartbeat.timeoutMs` are soft-disconnected.
2. **Session recovery:** `WELCOME` includes a one-time `sessionToken` (server stores SHA-256 only). After a drop, browser auto-`RESUME`s; Swing client can call `resume(host, port)`.
3. **Worker dispatch:** selector/WS threads only enqueue; `SessionDispatcher` runs handlers in per-connection FIFO order.

Config defaults live in `trivia-server/src/main/resources/trivia-hub.properties`.

## Tests

```bash
mvn test
```

Covers protocol codec, scoreboard concurrency, smoke TCP+HTTP, dispatcher ordering, heartbeat timeout, resume, and name-only anti-impersonation.

## Team ownership

| Member | Focus | Key packages |
|--------|-------|--------------|
| 01 | TCP client + framing + reconnect | `trivia.client.net`, `RESUME` |
| 02 | UDP multicast discovery | `trivia.server.discovery` |
| 03 | Rooms, sessions, concurrency | `trivia.server.room`, `trivia.server.session` |
| 04 | NIO + dispatch | `trivia.server.nio`, `trivia.server.dispatch` |
| 05 | HTTP/REST + web UI | `trivia.server.http`, `web/` |

See [docs/DEMO.md](docs/DEMO.md) and [docs/V2-ARCHITECTURE.md](docs/V2-ARCHITECTURE.md).

## Not in this slice (planned later)

PostgreSQL persistence, TLS/HTTPS/WSS, network simulation mode.
