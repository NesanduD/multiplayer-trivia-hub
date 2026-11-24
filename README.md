# Multiplayer Trivia Hub

Java NIO and multithreaded multiplayer trivia game.

This repository currently contains **Member 1**: the TCP server that accepts clients, reads commands, and sends replies. Rooms, questions, UDP discovery, and the idle-client monitor are the other members. They plug into the interfaces in `src/main/java/trivia`.

## What Member 1 does

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

UDP, broadcast, and multicast are Member 4. Certificates and TLS are not part of this server.

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

Member 5 reads `session.getLastActiveTime()`, which is updated on every successful read and on `PING`.

## Run the tests

Java 17 and Maven are required.

```bash
mvn test
```

The tests check partial lines (`compact`), `\r\n` stripping, a 4 KB line, a line that is too long, `PING` / `PONG`, a send from another thread, client close, and `stop()`.
