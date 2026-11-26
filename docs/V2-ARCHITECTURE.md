# Trivia Hub V2 — Architecture (foundation slice)

## What changed vs V1

| Area | V1 | V2 (this slice) |
|------|----|-----------------|
| Config | Hardcoded / CLI only | `trivia-hub.properties` + env + CLI |
| Game logic thread | Often on NIO selector / WS reader | `SessionDispatcher` worker pool, FIFO per connection |
| Liveness | Client PING for RTT only | Server tracks last activity; idle → soft-disconnect |
| Reconnect | New `playerId` every time | Opaque `sessionToken` (hashed server-side) + `RESUME` |
| Persistence | JSON match history | Unchanged (Postgres deferred) |

## Key classes

| Class | Role |
|-------|------|
| `ServerConfig` | Load/merge configuration |
| `SessionDispatcher` | Ordered off-I/O handling |
| `SessionRegistry` / `PlayerSessionRecord` | Logical player + token hash |
| `SessionTokens` | SecureRandom token + SHA-256 |
| `HeartbeatMonitor` | Idle + recover-window sweeper |
| `RoomManager` | Soft disconnect / resume / hard expire |
| `GameRoom` | Soft seat hold + rebind without resetting answers/scores |

## Heartbeat vs TCP

- **TCP** detects clean closes and many resets.
- **Application PING/PONG** detects half-open / zombie clients where the TCP session still looks alive.
- Idle timeout soft-disconnects (seat held for `session.recoverWindowMs`); then hard expire + `PLAYER_LEFT`.

## Protocol (backward compatible)

- Existing `HELLO` still works (mints new player + token).
- `WELCOME` adds `sessionToken` + `extra.recoverWindowMs` / heartbeat hints.
- New `RESUME` with `sessionToken` restores `playerId`, room, scores, current question.
- Name-only reconnect never claims another seat.

## Demo steps

1. Start server; open two browser tabs; play in a room.
2. Kill one tab’s network (DevTools offline) or stop sending PINGs in a test — watch soft-disconnect chat.
3. Re-open / auto-resume — same player and room.
4. Point at logs: `session-dispatch` threads vs selector thread name.
