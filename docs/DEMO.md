# Practical Theory Demonstrations (Viva)

## Member 01 — TCP Sockets (`Lecture 02`)

1. Start server and client; click **Connect**.
2. Open Wireshark on loopback / LAN filter `tcp.port == 5000`.
3. Show three-way handshake (SYN / SYN-ACK / ACK).
4. Create a room and send chat — ordered framed JSON over the stream.
5. Click **Disconnect** / close client — show FIN and server log `PLAYER_LEFT` / disconnect.
6. Force-kill a client process mid-game — server removes the player without crashing.

**Explain:** connection-oriented reliable byte stream; `Socket` / streams; lifecycle and exceptions.

## Member 02 — UDP + Multicast + Addressing

1. Start server — beacon logs `UDP discovery beacon started -> 239.255.77.77:4445`.
2. Start client — server appears in **Discovered Servers** without typing an IP.
3. Stop the server — entry ages out after ~6s (best-effort; missed datagrams are OK).
4. Show `DiscoveryMessage` JSON fields: host, tcpPort, players, rooms.
5. Contrast with TCP: no handshake, no delivery guarantee.

**Explain:** `DatagramSocket` / `MulticastSocket`, Class D addresses, ports, `InetAddress`.

## Member 03 — Multithreading & Concurrency

1. Create two rooms with different clients; start both games.
2. Point to logs: `room-worker` / `question-timer` thread names.
3. Run `ScoreBoardTest` — concurrent increments remain correct via `synchronized`.
4. Let a question timer expire unanswered — `Future` timeout advances the round.

**Explain:** thread pools, race conditions, `ExecutorService`, `Callable`/`Future`.

## Member 04 — Java NIO

1. Connect several clients while watching a single selector loop in `NioGameServer`.
2. Show `NioFrameBuffer` / `ProtocolCodec.drainFrames` using `flip()` + `compact()`.
3. Explain: one thread monitors many `SocketChannel`s vs one-thread-per-client blocking I/O.

**Explain:** Channels, Buffers, Selectors, non-blocking accept/read/write.

## Member 05 — HTTP & Web Services

1. Finish a match (or POST a sample match).
2. Browser or curl:

```bash
curl http://localhost:8080/api/leaderboard
curl http://localhost:8080/api/matches
curl http://localhost:8080/api/matches/<matchId>
```

3. Open client **Stats** panel — `HttpClient` GET with JSON parsing.
4. Request unknown match id → HTTP 404 JSON error.

**Explain:** REST resource URIs, HTTP methods/status codes, JSON, why live play uses TCP and history uses HTTP.
