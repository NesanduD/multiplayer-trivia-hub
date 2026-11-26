# Multiplayer Trivia Game Hub

Java networking project demonstrating TCP sockets, UDP multicast discovery, Java NIO, multithreading, HTTP/REST, and a browser WebSocket client — aligned with the UoM Network Programming module.

## Modules

| Module | Role |
|--------|------|
| `trivia-shared` | Protocol codec, models, DTOs |
| `trivia-server` | NIO TCP server, WebSocket web bridge, UDP beacon, game engine, HTTP web UI + stats |
| `trivia-client` | Optional Swing/TCP client (legacy desktop demo) |

## Ports

- **Web UI + HTTP API:** `8080` → open [http://localhost:8080/](http://localhost:8080/)
- **WebSocket game channel:** `5001`
- **TCP game (NIO):** `5000` (optional native clients)
- **UDP multicast discovery:** `239.255.77.77:4445`

## Build

```bash
mvn clean package
```

## Run (web app)

```bash
java -jar trivia-server/target/trivia-server-1.0.0-SNAPSHOT.jar
```

Then open **http://localhost:8080/** in your browser.

1. Enter a name → **Connect**
2. **Create Room** (or join an existing one)
3. **Start Game** → answer timed questions, chat, view live scores
4. Use the **Leaderboard (HTTP)** panel for REST stats

Optional args: `<tcpPort> <httpPort> <serverName> <wsPort>`

## Optional desktop client

```bash
java -jar trivia-client/target/trivia-client-1.0.0-SNAPSHOT.jar
```

## Team ownership

| Member | Focus | Key packages |
|--------|-------|--------------|
| 01 | TCP client + framing | `trivia.client.net`, `trivia.shared.protocol` |
| 02 | UDP multicast discovery | `trivia.server.discovery`, `trivia.client.discovery` |
| 03 | Multithreaded rooms/engine | `trivia.server.room`, `trivia.server.game` |
| 04 | Java NIO server | `trivia.server.nio` |
| 05 | HTTP REST + web UI | `trivia.server.http`, `web/` |

WebSocket bridge (`trivia.server.ws`) lets browsers use the same game protocol as TCP clients.

See [docs/DEMO.md](docs/DEMO.md) for viva demonstration scripts.
