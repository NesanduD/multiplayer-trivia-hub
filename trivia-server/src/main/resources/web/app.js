(() => {
  const els = {
    playerName: document.getElementById("playerName"),
    connectBtn: document.getElementById("connectBtn"),
    disconnectBtn: document.getElementById("disconnectBtn"),
    statusPill: document.getElementById("statusPill"),
    serverInfo: document.getElementById("serverInfo"),
    roomList: document.getElementById("roomList"),
    roomName: document.getElementById("roomName"),
    createRoomBtn: document.getElementById("createRoomBtn"),
    refreshRoomsBtn: document.getElementById("refreshRoomsBtn"),
    statsBtn: document.getElementById("statsBtn"),
    statsBox: document.getElementById("statsBox"),
    lobbyView: document.getElementById("lobbyView"),
    gameView: document.getElementById("gameView"),
    roomTitle: document.getElementById("roomTitle"),
    roomStatus: document.getElementById("roomStatus"),
    timer: document.getElementById("timer"),
    startBtn: document.getElementById("startBtn"),
    leaveBtn: document.getElementById("leaveBtn"),
    questionText: document.getElementById("questionText"),
    options: document.getElementById("options"),
    submitBtn: document.getElementById("submitBtn"),
    answerFeedback: document.getElementById("answerFeedback"),
    scoreList: document.getElementById("scoreList"),
    chatLog: document.getElementById("chatLog"),
    chatInput: document.getElementById("chatInput"),
    chatSendBtn: document.getElementById("chatSendBtn"),
  };

  let ws = null;
  let wsUrl = `ws://${location.hostname}:5001`;
  let playerId = null;
  let currentRoomId = null;
  let currentQIndex = -1;
  let selectedOption = null;
  let endsAt = null;
  let timerHandle = null;

  function setStatus(text, kind = "muted") {
    els.statusPill.textContent = text;
    els.statusPill.className = `pill ${kind}`;
  }

  function send(msg) {
    if (!ws || ws.readyState !== WebSocket.OPEN) return;
    ws.send(JSON.stringify(msg));
  }

  async function loadServerInfo() {
    try {
      const res = await fetch("/api/server-info");
      const info = await res.json();
      wsUrl = info.wsUrl || wsUrl;
      els.serverInfo.textContent =
        `${info.serverName} · WebSocket ${info.wsPort} · TCP ${info.tcpPort} · players ${info.players}`;
    } catch (err) {
      els.serverInfo.textContent = "Could not load /api/server-info — is the server running?";
    }
  }

  async function loadStats() {
    try {
      const res = await fetch("/api/leaderboard");
      const board = await res.json();
      if (!board.length) {
        els.statsBox.textContent = "No matches recorded yet.";
        return;
      }
      els.statsBox.textContent = board
        .map((p, i) => `${i + 1}. ${p.playerName} — ${p.totalScore} pts (${p.matchesPlayed} matches, ${p.wins} wins)`)
        .join("\n");
    } catch (err) {
      els.statsBox.textContent = "Failed to load leaderboard: " + err.message;
    }
  }

  function connect() {
    const name = els.playerName.value.trim() || "Player";
    setStatus("Connecting…", "muted");
    ws = new WebSocket(wsUrl);
    ws.onopen = () => {
      setStatus("Connected", "ok");
      els.connectBtn.disabled = true;
      els.disconnectBtn.disabled = false;
      els.createRoomBtn.disabled = false;
      els.refreshRoomsBtn.disabled = false;
      send({ type: "HELLO", playerName: name, clientId: crypto.randomUUID() });
    };
    ws.onmessage = (ev) => {
      try {
        onMessage(JSON.parse(ev.data));
      } catch (err) {
        console.error(err);
      }
    };
    ws.onclose = () => {
      setStatus("Disconnected", "muted");
      els.connectBtn.disabled = false;
      els.disconnectBtn.disabled = true;
      els.createRoomBtn.disabled = true;
      els.refreshRoomsBtn.disabled = true;
      showLobby();
    };
    ws.onerror = () => setStatus("Connection error", "bad");
  }

  function disconnect() {
    if (ws) ws.close();
  }

  function onMessage(msg) {
    switch (msg.type) {
      case "WELCOME":
        playerId = msg.playerId;
        setStatus(`Online as ${msg.playerName}`, "ok");
        break;
      case "ROOM_LIST":
        renderRooms(msg.rooms || []);
        break;
      case "ROOM_CREATED":
        currentRoomId = msg.roomId;
        break;
      case "ROOM_STATE":
        currentRoomId = msg.roomId;
        showGame(msg);
        break;
      case "QUESTION":
        showQuestion(msg);
        break;
      case "ANSWER_RESULT":
        els.answerFeedback.textContent = msg.correct
          ? `Correct! +${msg.points}`
          : "Incorrect / already answered";
        renderScores(msg.scores || []);
        break;
      case "SCORE_UPDATE":
      case "GAME_OVER":
        renderScores(msg.scores || []);
        if (msg.type === "GAME_OVER") {
          els.answerFeedback.textContent = "Game over — check HTTP stats for history.";
          els.submitBtn.disabled = true;
          els.startBtn.disabled = false;
          loadStats();
        }
        break;
      case "CHAT":
        els.chatLog.textContent += `${msg.from}: ${msg.text}\n`;
        els.chatLog.scrollTop = els.chatLog.scrollHeight;
        break;
      case "PLAYER_LEFT":
        els.chatLog.textContent += `* ${msg.playerId} left (${msg.reason})\n`;
        break;
      case "ERROR":
        alert(msg.error || "Server error");
        break;
      default:
        break;
    }
  }

  function renderRooms(rooms) {
    els.roomList.innerHTML = "";
    if (!rooms.length) {
      const li = document.createElement("li");
      li.textContent = "No rooms yet — create one.";
      els.roomList.appendChild(li);
      return;
    }
    rooms.forEach((room) => {
      const li = document.createElement("li");
      const btn = document.createElement("button");
      btn.textContent = `${room.roomName} (${room.players}/${room.maxPlayers}) [${room.status}]`;
      btn.onclick = () => send({ type: "JOIN_ROOM", roomId: room.roomId });
      li.appendChild(btn);
      els.roomList.appendChild(li);
    });
  }

  function showLobby() {
    els.gameView.classList.add("hidden");
    els.lobbyView.classList.remove("hidden");
    currentRoomId = null;
    clearInterval(timerHandle);
  }

  function showGame(state) {
    els.lobbyView.classList.add("hidden");
    els.gameView.classList.remove("hidden");
    els.roomTitle.textContent = state.roomName || "Room";
    els.roomStatus.textContent = `Status: ${state.status} · Players: ${(state.players || []).join(", ")}`;
    els.startBtn.disabled = state.status !== "WAITING";
  }

  function showQuestion(msg) {
    currentQIndex = msg.qIndex;
    selectedOption = null;
    endsAt = msg.endsAt;
    els.questionText.textContent = `Q${msg.qIndex + 1}: ${msg.text}`;
    els.answerFeedback.textContent = "";
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
    timerHandle = setInterval(updateTimer, 200);
    updateTimer();
  }

  function updateTimer() {
    if (!endsAt) {
      els.timer.textContent = "Time: —";
      return;
    }
    const remaining = Math.max(0, endsAt - Date.now());
    els.timer.textContent = `Time: ${(remaining / 1000).toFixed(1)}s`;
    if (remaining === 0) els.submitBtn.disabled = true;
  }

  function renderScores(scores) {
    els.scoreList.innerHTML = "";
    scores.forEach((s) => {
      const li = document.createElement("li");
      li.textContent = `${s.playerName}: ${s.score}`;
      els.scoreList.appendChild(li);
    });
  }

  els.connectBtn.onclick = connect;
  els.disconnectBtn.onclick = disconnect;
  els.createRoomBtn.onclick = () =>
    send({ type: "CREATE_ROOM", roomName: els.roomName.value.trim() || "Trivia Room", maxPlayers: 4 });
  els.refreshRoomsBtn.onclick = () => send({ type: "LIST_ROOMS" });
  els.statsBtn.onclick = loadStats;
  els.startBtn.onclick = () => send({ type: "START_GAME", roomId: currentRoomId });
  els.leaveBtn.onclick = () => {
    send({ type: "LEAVE_ROOM" });
    showLobby();
    send({ type: "LIST_ROOMS" });
  };
  els.submitBtn.onclick = () => {
    if (selectedOption == null) {
      alert("Select an option first");
      return;
    }
    send({ type: "ANSWER", roomId: currentRoomId, qIndex: currentQIndex, optionIndex: selectedOption });
    els.submitBtn.disabled = true;
  };
  els.chatSendBtn.onclick = () => {
    const text = els.chatInput.value.trim();
    if (!text) return;
    send({ type: "CHAT", text });
    els.chatInput.value = "";
  };
  els.chatInput.addEventListener("keydown", (e) => {
    if (e.key === "Enter") els.chatSendBtn.click();
  });

  loadServerInfo();
  loadStats();
})();
