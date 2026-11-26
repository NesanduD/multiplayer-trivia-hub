# Multiplayer Trivia Game Hub

Java networking project demonstrating TCP sockets, UDP multicast discovery, Java NIO, multithreading, and HTTP/REST — aligned with the UoM Network Programming module.

## Modules

| Module | Role |
|--------|------|
| `trivia-shared` | Protocol codec, models, DTOs |
| `trivia-server` | NIO TCP server, UDP beacon, game engine, HTTP stats |
| `trivia-client` | Swing UI, TCP client, multicast discovery, HttpClient |

## Ports

- **TCP game (NIO):** `5000`
- **UDP multicast discovery:** `239.255.77.77:4445`
- **HTTP stats:** `8080`

## Build

```bash
mvn clean package
```

## Run server

```bash
java -jar trivia-server/target/trivia-server-1.0.0-SNAPSHOT.jar
# optional args: <tcpPort> <httpPort> <serverName>
```

## Run client

```bash
java -jar trivia-client/target/trivia-client-1.0.0-SNAPSHOT.jar
```

1. Wait for the server to appear under **Discovered Servers**, or type `127.0.0.1` / `5000`.
2. Enter a player name → **Connect**.
3. **Create Room** or **Join Room** → **Start Game**.
4. Answer timed questions, use chat, then open **Stats** for HTTP leaderboard/history.

## Team ownership

| Member | Focus | Key packages |
|--------|-------|--------------|
| 01 | TCP client + framing | `trivia.client.net`, `trivia.shared.protocol` |
| 02 | UDP multicast discovery | `trivia.server.discovery`, `trivia.client.discovery` |
| 03 | Multithreaded rooms/engine | `trivia.server.room`, `trivia.server.game` |
| 04 | Java NIO server | `trivia.server.nio` |
| 05 | HTTP REST stats | `trivia.server.http`, `trivia.client.http` |

See [docs/DEMO.md](docs/DEMO.md) for viva demonstration scripts.
