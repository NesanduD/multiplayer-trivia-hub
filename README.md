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

---

# NIO TCP Server (Member 1 write-up)

Java NIO and multithreaded multiplayer trivia game.

This section covers the TCP server that accepts clients, reads commands, and sends replies. Rooms, questions, UDP discovery, and the idle-client monitor are the other members. They plug into the interfaces in `src/main/java/trivia`.

## What the NIO server does

`GameServer` listens on port **5000**. One thread accepts every client and reads every socket. Each connection is a `ClientSession`.

Clients speak in single lines that end with `\n`. A trailing `\r` is removed, so a telnet-style `\r\n` still works. `PING` is answered at once with `PONG`. Every other line (`JOIN`, `ANSWER`, `CHAT`, `LIST`, `LEAVE`) is handed to the room thread pool.

A reply can be queued from any thread with `session.send(...)`. The selector thread is the only thread that writes to the socket.

## Theories used

The code stays inside the course lectures.

- **NIO.** One selector thread, not one thread per socket. `ServerSocketChannel` is non-blocking and registered for `OP_ACCEPT`. Each client is a non-blocking `SocketChannel` registered for `OP_READ`, and for `OP_WRITE` only while a send is unfinished. Bytes move through a `ByteBuffer`: `flip` before reading what the channel wrote, `compact` to keep an unfinished line, `clear` when the buffer can be reused. This is the lecture echo server, plus line framing.
- **TCP/IP.** The server listens, the client connects, both sides exchange bytes, then both close. Port 5000 names this service. Each client is identified by its IP address and source port, even though every client uses destination port 5000. TCP delivers an ordered byte stream, so the application cuts that stream into lines the way `readLine()` does. Closing the socket frees the buffers and finishes connection termination. An `IOException` on one client closes that client only.
- **Flow control.** If `channel.write` cannot finish a buffer, the rest stays in that buffer and the selector waits for `OP_WRITE`. The selector thread does not spin in `while (buffer.hasRemaining())`, because that would block every other client.
- **Multithreading.** A long task on the only server thread blocks every client. `PING` stays on the selector thread because it is instant. Other commands are `executor.submit` on a fixed pool. `playerId` and `roomId` are read and written in `synchronized` methods so two threads cannot tear the pair. The outbound queue is a `ConcurrentLinkedQueue` so the selector thread and a room thread cannot corrupt it. Close uses `AtomicBoolean.compareAndSet`, so the selector and the idle monitor cannot clean up the same session twice.
- **Client-server and addressing.** This is a client-server TCP service. A client reaches it with the server's IP address and port 5000.
- **Secure network programming.** After `JOIN`, the server stores who this socket is. Later commands are authorized from that stored player and room. A name typed in `ANSWER` or `CHAT` is not identity. The inbound buffer is a fixed 4 KB, so one client cannot make the server allocate without bound.
- **Text lines.** Same idea as an HTTP request line and as `BufferedReader.readLine()`: one command is one line, and a trailing `\r` is stripped. This class is not an HTTP server and does not parse JSON.

UDP, broadcast, and multicast are handled by the discovery member. Certificates and TLS are not part of this server.

## How the other members connect

```java
new GameServer(5000, sessionRegistry, commandExecutor, roomHandler, SessionCleanup::closeQuietly)
```

- `sessionRegistry` implements `Sessions`. The map key is the client's remote address (IP + source port).
- `commandExecutor` is a fixed thread pool created in `ServerMain`.
- `roomHandler` implements `InboundHandler`. It must use `session.getPlayerId()` and `session.getRoomId()`, then call `session.send(...)` for replies such as `ERROR:Unauthorized room action\n`.
- `SessionCleanup.closeQuietly` is the `SessionDisposer`. It must call `session.tryMarkClosed()` and return if another thread already won. The channel may already be closed.

`server.start()` blocks on the selector thread. The JVM shutdown hook calls `server.stop()`, which closes the listening socket and unblocks `select()`.

After a successful `JOIN`, the room code calls:

```java
session.bindIdentity(playerId, roomId);
```

The idle-client monitor reads `session.getLastActiveTime()`, which is updated on every successful read and on `PING`.

## Run the NIO tests

Java 17 and Maven are required.

```bash
mvn test
```

The tests check partial lines (`compact`), `\r\n` stripping, a 4 KB line, a line that is too long, `PING` / `PONG`, a send from another thread, client close, and `stop()`.