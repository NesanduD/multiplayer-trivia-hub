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
    welcomeView: $("welcomeView"),
    lobbyView: $("lobbyView"),
    gameView: $("gameView"),
    roomTitle: $("roomTitle"),
    roomStatus: $("roomStatus"),
    roomCodeLabel: $("roomCodeLabel"),
    playerChips: $("playerChips"),
    timer: $("timer"),
    timerText: $("timerText"),
    timerArc: $("timerArc"),
    timerBar: $("timerBar"),
    startBtn: $("startBtn"),
    leaveBtn: $("leaveBtn"),
    copyInviteBtn: $("copyInviteBtn"),
    questionText: $("questionText"),
    questionMeta: $("questionMeta"),
    options: $("options"),
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
    fxHost: $("fxHost"),
  };

  const RING = 97.4;
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
  let questionDuration = 15000;
  let timerHandle = null;
  let pingHandle = null;
  let roomRefreshHandle = null;
  const protocolLines = [];

  function toast(text, bad = false) {
    const el = document.createElement("div");
    el.className = "toast" + (bad ? " bad" : "");
    el.textContent = text;
    $("toastHost").appendChild(el);
    setTimeout(() => el.remove(), 2800);
  }

  function setStatus(text, kind = "offline") {
    els.statusPill.textContent = text;
    els.statusPill.className = "status-pill " + kind;
  }

  function setStep(n) {
    if (!els.steps) return;
    [...els.steps.children].forEach((li) => {
      const step = Number(li.dataset.step);
      li.classList.toggle("active", step === n);
    });
  }

  function setLobbyActions(enabled) {
    els.createRoomBtn.disabled = !enabled;
    els.joinCodeBtn.disabled = !enabled;
    els.refreshRoomsBtn.disabled = !enabled;
    els.disconnectBtn.disabled = !enabled;
  }

  function showWelcome() {
    els.welcomeView.classList.remove("hidden");
    els.lobbyView.classList.add("hidden");
    els.gameView.classList.add("hidden");
  }

  function showLobbyScreen() {
    els.welcomeView.classList.add("hidden");
    els.lobbyView.classList.remove("hidden");
    els.gameView.classList.add("hidden");
    currentRoomId = null;
    clearInterval(timerHandle);
    refreshRooms();
  }

  function showGameScreen() {
    els.welcomeView.classList.add("hidden");
    els.lobbyView.classList.add("hidden");
    els.gameView.classList.remove("hidden");
  }

  function colorFor(name) {
    let hash = 0;
    for (let i = 0; i < name.length; i++) hash = (hash * 31 + name.charCodeAt(i)) >>> 0;
    return `hsl(${hash % 360} 72% 62%)`;
  }

  function avatar(name) {
    const span = document.createElement("span");
    span.className = "avatar";
    span.style.background = colorFor(name || "?");
    span.textContent = (name || "?").trim().charAt(0).toUpperCase();
    return span;
  }

  function celebrate() {
    const colors = ["#2fe39a", "#ffc857", "#7dffc7", "#5ec8ff", "#ff6b7a"];
    for (let i = 0; i < 28; i++) {
      const bit = document.createElement("span");
      bit.className = "confetti";
      bit.style.left = Math.random() * 100 + "vw";
      bit.style.background = colors[i % colors.length];
      bit.style.animationDelay = (Math.random() * 0.25) + "s";
      els.fxHost.appendChild(bit);
      setTimeout(() => bit.remove(), 1200);
    }
  }

  function logProtocol(direction, msg) {
    if (!els.protocolLog) return;
    const line = `${new Date().toLocaleTimeString()} ${direction} ${msg.type}` +
      (msg.roomId ? ` room=${msg.roomId}` : "");
    protocolLines.unshift(line);
    while (protocolLines.length > 40) protocolLines.pop();
    els.protocolLog.textContent = protocolLines.join("\n");
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
      if (els.serverInfo) {
        els.serverInfo.textContent = `${serverInfo.serverName} ready`;
      }
      if (els.heroStatus) els.heroStatus.textContent = "Server online";
      if (els.heroMeta) {
        els.heroMeta.textContent = `${serverInfo.players} players · ${serverInfo.rooms} rooms`;
      }
      renderServiceMap();
    } catch (_) {
      if (els.heroStatus) els.heroStatus.textContent = "Server offline";
    }
  }

  function renderServiceMap() {
    if (!serverInfo || !els.serviceMap) return;
    const rows = [
      ["Web UI", `http://${location.host}/`],
      ["WebSocket", serverInfo.wsUrl],
      ["TCP NIO", `${serverInfo.host}:${serverInfo.tcpPort}`],
      ["UDP beacon", `${serverInfo.multicastGroup}:${serverInfo.multicastPort}`],
    ];
    els.serviceMap.innerHTML = "";
    rows.forEach(([title, addr]) => {
      const li = document.createElement("li");
      li.innerHTML = `<strong>${title}</strong><br/><span class="muted">${addr}</span>`;
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
        card.innerHTML = `<div class="lecture">${c.lecture}</div><h3>${c.name}</h3><p>${c.meaning}</p><p>${c.evidence}</p>`;
        els.conceptGrid.appendChild(card);
      });
    } catch (_) {
      els.conceptGrid.innerHTML = `<p class="muted">Lab unavailable</p>`;
    }
  }

  async function loadStats() {
    try {
      const [boardRes, matchRes] = await Promise.all([fetch("/api/leaderboard"), fetch("/api/matches")]);
      const board = await boardRes.json();
      const matches = await matchRes.json();
      els.statsBox.textContent = board.length
        ? board.map((p, i) => `${i + 1}. ${p.playerName} — ${p.totalScore} pts`).join("\n")
        : "Play a match to fill the board.";
      els.historyBox.textContent = matches.length
        ? matches.slice(0, 10).map((m) => {
            const scores = (m.scores || []).map((s) => `${s.playerName}:${s.score}`).join(", ");
            return `${m.roomName}\n  ${scores}`;
          }).join("\n\n")
        : "No matches yet.";
    } catch (err) {
      els.statsBox.textContent = err.message;
    }
  }

  async function loadPlayers() {
    try {
      const res = await fetch("/api/players");
      const players = await res.json();
      els.playerList.innerHTML = "";
      if (!players.length) {
        const li = document.createElement("li");
        li.textContent = "No one else is here yet.";
        els.playerList.appendChild(li);
        return;
      }
      players.forEach((p) => {
        const li = document.createElement("li");
        li.appendChild(avatar(p.playerName));
        const text = document.createElement("span");
        text.textContent = p.roomId ? `${p.playerName} · in a room` : `${p.playerName} · lobby`;
        li.appendChild(text);
        els.playerList.appendChild(li);
      });
    } catch (_) {}
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
      if (!currentRoomId) renderRooms(rooms, !!(ws && ws.readyState === WebSocket.OPEN));
    } catch (_) {}
  }

  function connect() {
    const name = els.playerName.value.trim() || "Player";
    playerName = name;
    setStatus("Connecting…", "subtle");
    setStep(2);
    ws = new WebSocket(wsUrl);
    ws.onopen = () => {
      setStatus("Online", "ok");
      els.connectBtn.disabled = true;
      setLobbyActions(true);
      els.rttPill.classList.remove("hidden");
      send({ type: "HELLO", playerName: name, clientId: crypto.randomUUID() });
      startPingLoop();
      startRoomRefresh();
      showLobbyScreen();
      toast(`Welcome, ${name}!`);
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
      setStatus("Offline", "offline");
      els.connectBtn.disabled = false;
      setLobbyActions(false);
      els.rttPill.classList.add("hidden");
      playerId = null;
      currentRoomId = null;
      showWelcome();
      setStep(1);
    };
    ws.onerror = () => toast("Could not connect. Is the server running?", true);
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
        setStatus(playerName, "ok");
        setStep(3);
        loadPlayers();
        refreshRooms();
        break;
      case "PONG": {
        const sent = msg.extra && msg.extra.clientTime ? Number(msg.extra.clientTime) : null;
        if (sent) {
          els.rttPill.textContent = `${Math.max(0, Date.now() - sent)} ms`;
        }
        break;
      }
      case "ROOM_LIST":
        if (!currentRoomId) renderRooms(msg.rooms || [], true);
        break;
      case "ROOM_CREATED":
        currentRoomId = msg.roomId;
        els.chatLog.innerHTML = "";
        toast("Room ready — share the code");
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
        handleAnswerResult(msg);
        break;
      case "SCORE_UPDATE":
        renderScores(msg.scores || []);
        break;
      case "GAME_OVER":
        renderScores(msg.scores || []);
        els.answerFeedback.textContent = "Round over!";
        els.answerFeedback.className = "feedback ok";
        lockOptions();
        els.startBtn.disabled = false;
        els.questionMeta.textContent = "Nice game — start again anytime";
        celebrate();
        loadStats();
        toast("Game over");
        break;
      case "CHAT":
        appendChat(msg.from, msg.text, msg.from === "System");
        break;
      case "PLAYER_LEFT":
        appendChat("System", `${msg.from || "Someone"} left`, true);
        break;
      case "ERROR":
        toast(msg.error || "Something went wrong", true);
        break;
      default:
        break;
    }
  }

  function handleAnswerResult(msg) {
    els.answerFeedback.textContent = msg.correct ? `Nice! +${msg.points}` : "Not quite";
    els.answerFeedback.className = "feedback " + (msg.correct ? "ok" : "bad");
    [...els.options.children].forEach((btn, i) => {
      if (msg.correct && selectedOption === i) btn.classList.add("correct");
      if (!msg.correct && selectedOption === i) btn.classList.add("wrong");
    });
    if (msg.correct) celebrate();
    renderScores(msg.scores || []);
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
    body.textContent = system ? `${from}: ${text || ""}` : (text || "");
    bubble.appendChild(body);
    line.appendChild(bubble);
    els.chatLog.appendChild(line);
    els.chatLog.scrollTop = els.chatLog.scrollHeight;
  }

  function renderRooms(rooms, canJoin) {
    els.roomList.innerHTML = "";
    if (!rooms.length) {
      const li = document.createElement("li");
      li.className = "room-card";
      li.textContent = "No live rooms yet. Create one!";
      els.roomList.appendChild(li);
      return;
    }
    rooms.forEach((room) => {
      const li = document.createElement("li");
      li.className = "room-card";
      const title = document.createElement("strong");
      title.textContent = room.roomName || "Room";
      const meta = document.createElement("div");
      meta.className = "meta";
      const names = (room.playerNames || []).join(", ") || "waiting for players";
      meta.textContent = `${room.players}/${room.maxPlayers} · ${names}`;
      li.appendChild(title);
      li.appendChild(meta);
      if (canJoin) {
        const joinable = room.joinable !== false && room.status === "WAITING";
        const btn = document.createElement("button");
        btn.className = joinable ? "btn btn-primary btn-sm" : "btn btn-ghost btn-sm";
        btn.textContent = joinable ? "Join" : "In progress";
        btn.disabled = !joinable;
        btn.onclick = () => {
          els.joinCode.value = room.roomId;
          send({ type: "JOIN_ROOM", roomId: room.roomId });
        };
        li.appendChild(btn);
      }
      els.roomList.appendChild(li);
    });
  }

  function showGame(state) {
    showGameScreen();
    els.roomTitle.textContent = state.roomName || "Room";
    els.roomCodeLabel.textContent = state.roomId || currentRoomId || "—";
    const count = (state.players || []).length;
    els.roomStatus.textContent =
      state.status === "WAITING" ? `Waiting · ${count} player${count === 1 ? "" : "s"}` : `Playing · ${count} player${count === 1 ? "" : "s"}`;
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

  function lockOptions() {
    [...els.options.children].forEach((btn) => {
      btn.disabled = true;
    });
  }

  function submitAnswer(optionIndex) {
    if (selectedOption != null || optionIndex == null) return;
    selectedOption = optionIndex;
    const chosen = els.options.children[optionIndex];
    if (chosen) chosen.classList.add("selected");
    lockOptions();
    els.answerFeedback.textContent = "Answer locked";
    els.answerFeedback.className = "feedback";
    send({ type: "ANSWER", roomId: currentRoomId, qIndex: currentQIndex, optionIndex });
  }

  function showQuestion(msg) {
    currentQIndex = msg.qIndex;
    selectedOption = null;
    endsAt = msg.endsAt;
    questionStartedAt = Date.now();
    questionDuration = Math.max(1000, (endsAt || Date.now()) - questionStartedAt);
    els.questionMeta.textContent = `Question ${msg.qIndex + 1}`;
    els.questionText.textContent = msg.text;
    els.questionText.classList.remove("animate-in");
    void els.questionText.offsetWidth;
    els.questionText.classList.add("animate-in");
    els.answerFeedback.textContent = "Tap an answer to lock it in";
    els.answerFeedback.className = "feedback";
    els.startBtn.disabled = true;
    els.options.innerHTML = "";
    (msg.options || []).forEach((opt, i) => {
      const btn = document.createElement("button");
      btn.className = "option";
      btn.textContent = `${String.fromCharCode(65 + i)}. ${opt}`;
      btn.style.animationDelay = `${i * 0.05}s`;
      btn.classList.add("animate-in");
      btn.onclick = () => submitAnswer(i);
      els.options.appendChild(btn);
    });
    clearInterval(timerHandle);
    timerHandle = setInterval(updateTimer, 100);
    updateTimer();
  }

  function updateTimer() {
    if (!endsAt) {
      els.timerText.textContent = "—";
      els.timerBar.style.width = "100%";
      els.timerArc.style.strokeDashoffset = "0";
      return;
    }
    const remaining = Math.max(0, endsAt - Date.now());
    const secs = (remaining / 1000).toFixed(0);
    els.timerText.textContent = secs;
    const pct = Math.max(0, Math.min(1, remaining / questionDuration));
    els.timerBar.style.width = pct * 100 + "%";
    els.timerArc.style.strokeDashoffset = String((1 - pct) * RING);
    const urgent = remaining <= 5000;
    els.timer.classList.toggle("urgent", urgent);
    els.timerBar.classList.toggle("urgent", urgent);
    if (remaining === 0) lockOptions();
  }

  function renderScores(scores) {
    els.scoreList.innerHTML = "";
    scores.forEach((s, idx) => {
      const li = document.createElement("li");
      li.appendChild(avatar(s.playerName));
      li.appendChild(document.createTextNode(` ${idx + 1}. ${s.playerName} · ${s.score}`));
      els.scoreList.appendChild(li);
    });
  }

  function joinByCode() {
    const code = (els.joinCode.value || "").trim();
    if (!code) {
      toast("Enter a room code", true);
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
  els.joinCode.addEventListener("keydown", (e) => { if (e.key === "Enter") joinByCode(); });
  els.refreshRoomsBtn.onclick = refreshRooms;
  els.refreshPlayersBtn.onclick = loadPlayers;
  els.statsBtn.onclick = loadStats;
  els.refreshLabBtn.onclick = () => { loadLab(); loadServerInfo(); };
  els.pingBtn.onclick = () => {
    if (!ws || ws.readyState !== WebSocket.OPEN) {
      toast("Go to Play and connect first", true);
      return;
    }
    send({ type: "PING", serverTime: Date.now() });
  };
  els.startBtn.onclick = () => send({ type: "START_GAME", roomId: currentRoomId });
  els.leaveBtn.onclick = () => {
    send({ type: "LEAVE_ROOM" });
    els.chatLog.innerHTML = "";
    showLobbyScreen();
    setStep(3);
  };
  els.copyInviteBtn.onclick = async () => {
    if (!currentRoomId) return;
    try {
      await navigator.clipboard.writeText(currentRoomId);
      toast("Code copied");
    } catch (_) {
      toast(currentRoomId);
    }
  };
  els.chatSendBtn.onclick = () => {
    const text = els.chatInput.value.trim();
    if (!text) return;
    if (!currentRoomId) {
      toast("Join a room to chat", true);
      return;
    }
    if (send({ type: "CHAT", text })) els.chatInput.value = "";
  };
  els.chatInput.addEventListener("keydown", (e) => {
    if (e.key === "Enter") els.chatSendBtn.click();
  });
  els.playerName.addEventListener("keydown", (e) => {
    if (e.key === "Enter") els.connectBtn.click();
  });

  setLobbyActions(false);
  showWelcome();
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
