(() => {
  const $ = (id) => document.getElementById(id);
  const els = {
    playerName: $("playerName"),
    connectBtn: $("connectBtn"),
    disconnectBtn: $("disconnectBtn"),
    statusPill: $("statusPill"),
    rttPill: $("rttPill"),
    serverInfo: $("serverInfo"),
    roomList: $("roomList"),
    roomName: $("roomName"),
    joinCode: $("joinCode"),
    joinCodeBtn: $("joinCodeBtn"),
    createRoomBtn: $("createRoomBtn"),
    refreshRoomsBtn: $("refreshRoomsBtn"),
    refreshPlayersBtn: $("refreshPlayersBtn"),
    playerList: $("playerList"),
    statsBtn: $("statsBtn"),
    statsBox: $("statsBox"),
    historyBox: $("historyBox"),
    lobbyView: $("lobbyView"),
    gameView: $("gameView"),
    roomTitle: $("roomTitle"),
    roomStatus: $("roomStatus"),
    roomCodeLabel: $("roomCodeLabel"),
    playerChips: $("playerChips"),
    timer: $("timer"),
    timerBar: $("timerBar"),
    startBtn: $("startBtn"),
    leaveBtn: $("leaveBtn"),
    copyInviteBtn: $("copyInviteBtn"),
    questionText: $("questionText"),
    questionMeta: $("questionMeta"),
    options: $("options"),
    submitBtn: $("submitBtn"),
    answerFeedback: $("answerFeedback"),
    scoreList: $("scoreList"),
    chatLog: $("chatLog"),
    chatInput: $("chatInput"),
    chatSendBtn: $("chatSendBtn"),
    conceptGrid: $("conceptGrid"),
    protocolLog: $("protocolLog"),
    serviceMap: $("serviceMap"),
    pingBtn: $("pingBtn"),
    refreshLabBtn: $("refreshLabBtn"),
    heroStatus: $("heroStatus"),
    heroMeta: $("heroMeta"),
    liveBadge: $("liveBadge"),
    steps: $("steps"),
  };

  let ws = null;
  let wsUrl = `ws://${location.hostname}:5001`;
  let serverInfo = null;
  let playerId = null;
  let playerName = null;
  let currentRoomId = null;
  let currentQIndex = -1;
  let selectedOption = null;
  let endsAt = null;
  let questionStartedAt = null;
  let timerHandle = null;
  let pingHandle = null;
  let roomRefreshHandle = null;
  const protocolLines = [];

  function toast(text, bad = false) {
    const host = $("toastHost");
    const el = document.createElement("div");
    el.className = "toast" + (bad ? " bad" : "");
    el.textContent = text;
    host.appendChild(el);
    setTimeout(() => el.remove(), 3500);
  }

  function setStatus(text, kind = "muted") {
    els.statusPill.textContent = text;
    els.statusPill.className = `pill ${kind}`;
  }

  function setStep(n) {
    [...els.steps.children].forEach((li) => {
      const step = Number(li.dataset.step);
      li.classList.toggle("active", step === n);
      li.classList.toggle("done", step < n);
    });
  }

  function setLobbyActions(enabled) {
    els.createRoomBtn.disabled = !enabled;
    els.joinCodeBtn.disabled = !enabled;
    els.refreshRoomsBtn.disabled = !enabled;
  }

  function colorFor(name) {
    let hash = 0;
    for (let i = 0; i < name.length; i++) hash = (hash * 31 + name.charCodeAt(i)) >>> 0;
    return `hsl(${hash % 360} 70% 62%)`;
  }

  function avatar(name) {
    const span = document.createElement("span");
    span.className = "avatar";
    span.style.background = colorFor(name || "?");
    span.textContent = (name || "?").trim().charAt(0).toUpperCase();
    return span;
  }

  function logProtocol(direction, msg) {
    const line = `${new Date().toLocaleTimeString()} ${direction} ${msg.type}` +
      (msg.roomId ? ` room=${msg.roomId}` : "") +
      (msg.text ? ` “${String(msg.text).slice(0, 40)}”` : "");
    protocolLines.unshift(line);
    while (protocolLines.length > 40) protocolLines.pop();
    if (els.protocolLog) els.protocolLog.textContent = protocolLines.join("\n");
  }

  function send(msg) {
    if (!ws || ws.readyState !== WebSocket.OPEN) {
      toast("Connect first", true);
      return false;
    }
    logProtocol("→", msg);
    ws.send(JSON.stringify(msg));
    return true;
  }

  async function loadServerInfo() {
    try {
      const res = await fetch("/api/server-info");
      serverInfo = await res.json();
      wsUrl = serverInfo.wsUrl || wsUrl;
      const uptimeMin = Math.floor((serverInfo.uptimeMs || 0) / 60000);
      els.serverInfo.textContent =
        `${serverInfo.serverName} @ ${serverInfo.host} · WS ${serverInfo.wsPort} · TCP ${serverInfo.tcpPort} · HTTP ${serverInfo.httpPort} · up ${uptimeMin}m`;
      els.heroStatus.textContent = "Server online";
      els.heroMeta.textContent = `UDP ${serverInfo.multicastGroup}:${serverInfo.multicastPort} · ${serverInfo.players} players · ${serverInfo.rooms} rooms`;
      els.liveBadge.classList.add("online");
      renderServiceMap();
    } catch (err) {
      els.serverInfo.textContent = "Server unreachable — start the Java server first.";
      els.heroStatus.textContent = "Server offline";
      els.liveBadge.classList.remove("online");
    }
  }

  function renderServiceMap() {
    if (!serverInfo || !els.serviceMap) return;
    const rows = [
      ["HTTP web UI + REST", `http://${location.host}/`, "Request/response JSON & pages"],
      ["WebSocket live game", serverInfo.wsUrl, "Browser duplex channel"],
      ["TCP NIO game", `${serverInfo.host}:${serverInfo.tcpPort}`, "Native Socket / NIO clients"],
      ["UDP multicast beacon", `${serverInfo.multicastGroup}:${serverInfo.multicastPort}`, "LAN discovery (best-effort)"],
    ];
    els.serviceMap.innerHTML = "";
    rows.forEach(([title, addr, note]) => {
      const li = document.createElement("li");
      li.innerHTML = `<strong>${title}</strong><br/><code>${addr}</code><br/><span class="muted small">${note}</span>`;
      els.serviceMap.appendChild(li);
    });
  }

  async function loadLab() {
    try {
      const res = await fetch("/api/network-lab");
      const data = await res.json();
      els.conceptGrid.innerHTML = "";
      (data.concepts || []).forEach((c) => {
        const card = document.createElement("article");
        card.className = "concept";
        card.innerHTML = `
          <div class="lecture">${c.lecture}</div>
          <h3>${c.name}</h3>
          <p>${c.meaning}</p>
          <p><strong>In this app:</strong> ${c.evidence}</p>`;
        els.conceptGrid.appendChild(card);
      });
    } catch (_) {
      els.conceptGrid.innerHTML = `<p class="muted">Could not load /api/network-lab</p>`;
    }
  }

  async function loadStats() {
    try {
      const [boardRes, matchRes] = await Promise.all([fetch("/api/leaderboard"), fetch("/api/matches")]);
      const board = await boardRes.json();
      const matches = await matchRes.json();
      els.statsBox.textContent = board.length
        ? board.map((p, i) => `${i + 1}. ${p.playerName} — ${p.totalScore} pts · ${p.matchesPlayed} matches · ${p.wins} wins`).join("\n")
        : "No matches recorded yet.";
      els.historyBox.textContent = matches.length
        ? matches.slice(0, 12).map((m) => {
            const scores = (m.scores || []).map((s) => `${s.playerName}:${s.score}`).join(", ");
            return `${m.roomName} · ${new Date(m.endedAt).toLocaleString()}\n  ${scores}`;
          }).join("\n\n")
        : "No match history yet.";
    } catch (err) {
      els.statsBox.textContent = "Failed: " + err.message;
      els.historyBox.textContent = "Failed: " + err.message;
    }
  }

  async function loadPlayers() {
    try {
      const res = await fetch("/api/players");
      const players = await res.json();
      els.playerList.innerHTML = "";
      if (!players.length) {
        const li = document.createElement("li");
        li.textContent = "Nobody connected yet.";
        els.playerList.appendChild(li);
        return;
      }
      players.forEach((p) => {
        const li = document.createElement("li");
        const wrap = document.createElement("div");
        wrap.className = "row";
        wrap.appendChild(avatar(p.playerName));
        const text = document.createElement("span");
        text.textContent = p.roomId
          ? `${p.playerName} · in room ${p.roomId}`
          : `${p.playerName} · lobby (can join a room)`;
        wrap.appendChild(text);
        li.appendChild(wrap);
        els.playerList.appendChild(li);
      });
    } catch (_) {
      // ignore
    }
  }

  function refreshRooms() {
    if (ws && ws.readyState === WebSocket.OPEN && !currentRoomId) {
      send({ type: "LIST_ROOMS" });
    } else {
      loadRoomsHttp();
    }
  }

  async function loadRoomsHttp() {
    try {
      const res = await fetch("/api/rooms");
      const rooms = await res.json();
      // Only paint HTTP rooms when not already inside a room UI
      if (!currentRoomId) {
        renderRooms(rooms, !!(ws && ws.readyState === WebSocket.OPEN));
      }
    } catch (_) {
      // ignore
    }
  }

  function connect() {
    const name = els.playerName.value.trim() || "Player";
    playerName = name;
    setStatus("Connecting…", "muted");
    setStep(2);
    ws = new WebSocket(wsUrl);
    ws.onopen = () => {
      setStatus("Connected", "ok");
      els.connectBtn.disabled = true;
      els.disconnectBtn.disabled = false;
      setLobbyActions(true);
      els.rttPill.classList.remove("hidden");
      send({ type: "HELLO", playerName: name, clientId: crypto.randomUUID() });
      startPingLoop();
      startRoomRefresh();
      toast(`Connected as ${name}. Create a room or join a live one.`);
    };
    ws.onmessage = (ev) => {
      try {
        const msg = JSON.parse(ev.data);
        logProtocol("←", msg);
        onMessage(msg);
      } catch (err) {
        console.error(err);
      }
    };
    ws.onclose = () => {
      stopPingLoop();
      stopRoomRefresh();
      setStatus("Disconnected", "muted");
      els.connectBtn.disabled = false;
      els.disconnectBtn.disabled = true;
      setLobbyActions(false);
      els.rttPill.classList.add("hidden");
      playerId = null;
      showLobby();
      setStep(1);
      toast("Disconnected from game server");
    };
    ws.onerror = () => {
      setStatus("Connection error", "bad");
      toast("WebSocket failed — is port 5001 open?", true);
    };
  }

  function disconnect() {
    if (ws) ws.close();
  }

  function startPingLoop() {
    stopPingLoop();
    const beat = () => send({ type: "PING", serverTime: Date.now() });
    beat();
    pingHandle = setInterval(beat, 5000);
  }

  function stopPingLoop() {
    if (pingHandle) clearInterval(pingHandle);
    pingHandle = null;
  }

  function startRoomRefresh() {
    stopRoomRefresh();
    roomRefreshHandle = setInterval(() => {
      if (!currentRoomId) refreshRooms();
      loadPlayers();
    }, 3000);
  }

  function stopRoomRefresh() {
    if (roomRefreshHandle) clearInterval(roomRefreshHandle);
    roomRefreshHandle = null;
  }

  function onMessage(msg) {
    switch (msg.type) {
      case "WELCOME":
        playerId = msg.playerId;
        playerName = msg.playerName || playerName;
        setStatus(`Online as ${playerName}`, "ok");
        setStep(3);
        loadPlayers();
        refreshRooms();
        break;
      case "PONG": {
        const sent = msg.extra && msg.extra.clientTime ? Number(msg.extra.clientTime) : null;
        if (sent) {
          els.rttPill.textContent = `RTT ${Math.max(0, Date.now() - sent)} ms`;
          els.rttPill.className = "pill ok";
        }
        break;
      }
      case "ROOM_LIST":
        if (!currentRoomId) renderRooms(msg.rooms || [], true);
        break;
      case "ROOM_CREATED":
        currentRoomId = msg.roomId;
        els.chatLog.innerHTML = "";
        toast(`Room created. Code: ${msg.roomId} — share it with friends`);
        break;
      case "ROOM_STATE":
        currentRoomId = msg.roomId;
        showGame(msg);
        setStep(4);
        break;
      case "QUESTION":
        showQuestion(msg);
        break;
      case "ANSWER_RESULT":
        els.answerFeedback.textContent = msg.correct ? `Correct! +${msg.points}` : "Not this time";
        els.answerFeedback.className = "feedback " + (msg.correct ? "ok" : "bad");
        renderScores(msg.scores || []);
        break;
      case "SCORE_UPDATE":
      case "GAME_OVER":
        renderScores(msg.scores || []);
        if (msg.type === "GAME_OVER") {
          els.answerFeedback.textContent = "Match finished — check Stats & History.";
          els.answerFeedback.className = "feedback ok";
          els.submitBtn.disabled = true;
          els.startBtn.disabled = false;
          els.questionMeta.textContent = "Round complete — you can chat or start again";
          loadStats();
          toast("Game over");
        }
        break;
      case "CHAT":
        appendChat(msg.from, msg.text, msg.from === "System");
        break;
      case "PLAYER_LEFT":
        appendChat("System", `${msg.from || "A player"} left (${msg.reason})`, true);
        break;
      case "ERROR":
        toast(msg.error || "Server error", true);
        break;
      default:
        break;
    }
  }

  function appendChat(from, text, system = false) {
    const mine = !system && from && playerName && from === playerName;
    const line = document.createElement("div");
    line.className = "chat-line " + (system ? "system" : mine ? "mine" : "theirs");

    const bubble = document.createElement("div");
    bubble.className = "chat-bubble";

    if (!system) {
      const who = document.createElement("span");
      who.className = "who";
      who.textContent = from || "Unknown";
      bubble.appendChild(who);
    }

    const body = document.createElement("div");
    body.className = "msg-text";
    body.textContent = system
      ? ((from ? from + ": " : "") + (text || ""))
      : (text || "");
    bubble.appendChild(body);

    line.appendChild(bubble);
    els.chatLog.appendChild(line);
    els.chatLog.scrollTop = els.chatLog.scrollHeight;
  }

  function renderRooms(rooms, canJoin) {
    els.roomList.innerHTML = "";
    if (!rooms.length) {
      const li = document.createElement("li");
      li.textContent = "No live rooms yet. Create one, then friends refresh/join.";
      els.roomList.appendChild(li);
      return;
    }
    rooms.forEach((room) => {
      const li = document.createElement("li");
      const card = document.createElement("div");
      card.className = "room-card";
      const title = document.createElement("strong");
      title.textContent = room.roomName || "Room";
      const meta = document.createElement("div");
      meta.className = "meta";
      const names = (room.playerNames || []).join(", ") || "empty";
      meta.textContent = `${room.players}/${room.maxPlayers} players · ${room.status} · code ${room.roomId} · ${names}`;
      card.appendChild(title);
      card.appendChild(meta);

      if (canJoin) {
        const btn = document.createElement("button");
        const joinable = room.joinable !== false && room.status === "WAITING";
        btn.className = joinable ? "primary" : "ghost";
        btn.textContent = joinable ? "Join this room" : (room.status === "PLAYING" ? "In progress (wait)" : "Cannot join");
        btn.disabled = !joinable;
        btn.onclick = () => {
          els.joinCode.value = room.roomId;
          send({ type: "JOIN_ROOM", roomId: room.roomId });
          toast(`Joining ${room.roomName}…`);
        };
        card.appendChild(btn);
      }
      li.appendChild(card);
      els.roomList.appendChild(li);
    });
  }

  function showLobby() {
    els.gameView.classList.add("hidden");
    els.lobbyView.classList.remove("hidden");
    currentRoomId = null;
    clearInterval(timerHandle);
    refreshRooms();
  }

  function showGame(state) {
    els.lobbyView.classList.add("hidden");
    els.gameView.classList.remove("hidden");
    els.roomTitle.textContent = state.roomName || "Room";
    els.roomCodeLabel.textContent = state.roomId || currentRoomId || "—";
    const count = (state.players || []).length;
    els.roomStatus.textContent = `Status: ${state.status} · ${count} player(s) in this room`;
    els.startBtn.disabled = state.status !== "WAITING";
    els.playerChips.innerHTML = "";
    (state.players || []).forEach((name) => {
      const chip = document.createElement("span");
      chip.className = "chip";
      chip.appendChild(avatar(name));
      chip.appendChild(document.createTextNode(name));
      els.playerChips.appendChild(chip);
    });
  }

  function showQuestion(msg) {
    currentQIndex = msg.qIndex;
    selectedOption = null;
    endsAt = msg.endsAt;
    questionStartedAt = Date.now();
    const total = Math.max(1, (endsAt || Date.now()) - questionStartedAt);
    els.questionMeta.textContent = `Question ${msg.qIndex + 1} · answer before the timer ends`;
    els.questionText.textContent = msg.text;
    els.answerFeedback.textContent = "";
    els.answerFeedback.className = "feedback";
    els.submitBtn.disabled = false;
    els.startBtn.disabled = true;
    els.options.innerHTML = "";
    (msg.options || []).forEach((opt, i) => {
      const btn = document.createElement("button");
      btn.className = "option";
      btn.textContent = `${String.fromCharCode(65 + i)}. ${opt}`;
      btn.onclick = () => {
        selectedOption = i;
        [...els.options.children].forEach((c) => c.classList.remove("selected"));
        btn.classList.add("selected");
      };
      els.options.appendChild(btn);
    });
    clearInterval(timerHandle);
    timerHandle = setInterval(() => updateTimer(total), 100);
    updateTimer(total);
  }

  function updateTimer(totalMs) {
    if (!endsAt) {
      els.timer.textContent = "Time: —";
      els.timerBar.style.width = "100%";
      return;
    }
    const remaining = Math.max(0, endsAt - Date.now());
    els.timer.textContent = `Time: ${(remaining / 1000).toFixed(1)}s`;
    els.timerBar.style.width = Math.max(0, Math.min(100, (remaining / totalMs) * 100)) + "%";
    if (remaining === 0) els.submitBtn.disabled = true;
  }

  function renderScores(scores) {
    els.scoreList.innerHTML = "";
    scores.forEach((s, idx) => {
      const li = document.createElement("li");
      const wrap = document.createElement("div");
      wrap.className = "row";
      wrap.appendChild(avatar(s.playerName));
      wrap.appendChild(document.createTextNode(`${idx + 1}. ${s.playerName}: ${s.score}`));
      li.appendChild(wrap);
      els.scoreList.appendChild(li);
    });
  }

  function joinByCode() {
    const code = (els.joinCode.value || "").trim();
    if (!code) {
      toast("Paste a room code first (example: r-ab12cd34)", true);
      return;
    }
    send({ type: "JOIN_ROOM", roomId: code });
  }

  document.querySelectorAll(".tab").forEach((tab) => {
    tab.addEventListener("click", () => {
      document.querySelectorAll(".tab").forEach((t) => t.classList.remove("active"));
      document.querySelectorAll(".tab-panel").forEach((p) => p.classList.remove("active"));
      tab.classList.add("active");
      $("tab-" + tab.dataset.tab).classList.add("active");
      if (tab.dataset.tab === "lab") loadLab();
      if (tab.dataset.tab === "stats") loadStats();
    });
  });

  els.connectBtn.onclick = connect;
  els.disconnectBtn.onclick = disconnect;
  els.createRoomBtn.onclick = () =>
    send({ type: "CREATE_ROOM", roomName: els.roomName.value.trim() || "Trivia Room", maxPlayers: 4 });
  els.joinCodeBtn.onclick = joinByCode;
  els.joinCode.addEventListener("keydown", (e) => {
    if (e.key === "Enter") joinByCode();
  });
  els.refreshRoomsBtn.onclick = refreshRooms;
  els.refreshPlayersBtn.onclick = loadPlayers;
  els.statsBtn.onclick = loadStats;
  els.refreshLabBtn.onclick = () => { loadLab(); loadServerInfo(); };
  els.pingBtn.onclick = () => {
    if (!ws || ws.readyState !== WebSocket.OPEN) {
      toast("Connect first to measure RTT", true);
      return;
    }
    send({ type: "PING", serverTime: Date.now() });
  };
  els.startBtn.onclick = () => send({ type: "START_GAME", roomId: currentRoomId });
  els.leaveBtn.onclick = () => {
    send({ type: "LEAVE_ROOM" });
    els.chatLog.innerHTML = "";
    showLobby();
    setStep(3);
  };
  els.copyInviteBtn.onclick = async () => {
    if (!currentRoomId) return;
    try {
      await navigator.clipboard.writeText(currentRoomId);
      toast("Room code copied — friends paste it under Join with room code");
    } catch (_) {
      toast("Room code: " + currentRoomId);
    }
  };
  els.submitBtn.onclick = () => {
    if (selectedOption == null) {
      toast("Select an option first", true);
      return;
    }
    send({ type: "ANSWER", roomId: currentRoomId, qIndex: currentQIndex, optionIndex: selectedOption });
    els.submitBtn.disabled = true;
  };
  els.chatSendBtn.onclick = () => {
    const text = els.chatInput.value.trim();
    if (!text) {
      toast("Type a chat message first", true);
      return;
    }
    if (!currentRoomId) {
      toast("Join a room before chatting", true);
      return;
    }
    if (send({ type: "CHAT", text })) {
      els.chatInput.value = "";
    }
  };
  els.chatInput.addEventListener("keydown", (e) => {
    if (e.key === "Enter") els.chatSendBtn.click();
  });
  els.playerName.addEventListener("keydown", (e) => {
    if (e.key === "Enter") els.connectBtn.click();
  });

  setLobbyActions(false);
  loadServerInfo();
  loadStats();
  loadLab();
  loadPlayers();
  loadRoomsHttp();
  setInterval(() => {
    loadServerInfo();
    loadPlayers();
  }, 8000);
})();
