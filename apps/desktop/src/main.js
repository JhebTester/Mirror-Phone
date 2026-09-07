// Mirror Phone — Desktop receiver (WebRTC edition)
//
// El agente Android es el offerer. Nosotros somos el answerer:
//   1) Rust nos entrega mensajes de signaling desde el phone via evento
//      "mirror://signaling" con { kind, text }, donde kind es:
//        0x10 = SDP offer, 0x11 = SDP answer, 0x12 = ICE candidate, 0x13 = bye
//   2) Nosotros respondemos usando el comando `send_signaling(kind, text)`.
//   3) Una vez negociado, el video llega directo por UDP al elemento <video>.

const { invoke } = window.__TAURI__.core;
const { listen } = window.__TAURI__.event;

const statusEl = document.getElementById("status");
const video = document.getElementById("screen");
const pairing = document.getElementById("pairing");
const qrEl = document.getElementById("qr");
const pinEl = document.getElementById("pin");
const pairHost = document.getElementById("pair-host");
const pairPort = document.getElementById("pair-port");
const pairName = document.getElementById("pair-name");
const metaRes = document.getElementById("meta-res");
const metaFps = document.getElementById("meta-fps");
const metaKbps = document.getElementById("meta-kbps");

const KIND_OFFER = 0x10;
const KIND_ANSWER = 0x11;
const KIND_ICE = 0x12;
const KIND_BYE = 0x13;

let pc = null;
let statsTimer = null;

function setStatus(state, message) {
  statusEl.className = `status ${state}`;
  statusEl.textContent = message;
}

function renderQR(payload) {
  const qr = window.qrcode(0, "M");
  qr.addData(payload);
  qr.make();
  qrEl.innerHTML = qr.createImgTag(6, 8);
}

async function loadPairing() {
  try {
    const info = await invoke("get_pairing_info");
    pinEl.textContent = info.pin;
    pairHost.textContent = info.host;
    pairPort.textContent = info.port;
    pairName.textContent = info.name;
    renderQR(info.qr_payload);
  } catch (e) {
    console.warn("get_pairing_info falló:", e);
    setTimeout(loadPairing, 1000);
  }
}

async function sendSignaling(kind, text) {
  try {
    await invoke("send_signaling", { kind, text });
  } catch (e) {
    console.error("send_signaling failed", e);
  }
}

function closePeer() {
  if (statsTimer) { clearInterval(statsTimer); statsTimer = null; }
  if (pc) {
    try { pc.close(); } catch {}
    pc = null;
  }
  try { video.srcObject = null; } catch {}
  pairing.classList.remove("hidden");
}

async function createPeerAnswerer() {
  closePeer();

  // Sin servidores ICE — mismo LAN → candidatos host bastan
  pc = new RTCPeerConnection({ iceServers: [] });

  pc.addEventListener("track", (ev) => {
    console.log("ontrack", ev.track.kind);
    // Reduce el jitter buffer del receptor al mínimo (LAN → latencia ~0).
    // WKWebView Safari 16+ soporta playoutDelayHint en RTCRtpReceiver.
    try {
      if ("playoutDelayHint" in ev.receiver) {
        ev.receiver.playoutDelayHint = 0;
      }
      if ("jitterBufferTarget" in ev.receiver) {
        ev.receiver.jitterBufferTarget = 0;
      }
    } catch (e) { console.warn("playoutDelayHint no soportado:", e); }
    if (ev.streams && ev.streams[0]) {
      video.srcObject = ev.streams[0];
    } else {
      const stream = new MediaStream([ev.track]);
      video.srcObject = stream;
    }
    pairing.classList.add("hidden");
  });

  pc.addEventListener("icecandidate", (ev) => {
    if (ev.candidate) {
      sendSignaling(KIND_ICE, JSON.stringify({
        candidate: ev.candidate.candidate,
        sdpMid: ev.candidate.sdpMid,
        sdpMLineIndex: ev.candidate.sdpMLineIndex,
      }));
    } else {
      // Fin del gathering — no hace falta enviar nada
    }
  });

  pc.addEventListener("connectionstatechange", () => {
    console.log("pc state:", pc?.connectionState);
    if (!pc) return;
    if (pc.connectionState === "connected") {
      setStatus("connected", "Espejando (WebRTC conectado)");
    } else if (pc.connectionState === "failed" || pc.connectionState === "disconnected") {
      setStatus("disconnected", "Conexión perdida");
      closePeer();
    }
  });

  // Video metrics (resolución + fps + bitrate) desde getStats
  statsTimer = setInterval(refreshStats, 1000);
}

let lastBytes = 0;
let lastFramesDecoded = 0;
let lastStatsTs = 0;

async function refreshStats() {
  if (!pc) return;
  try {
    const stats = await pc.getStats();
    let inbound = null;
    for (const s of stats.values()) {
      if (s.type === "inbound-rtp" && s.kind === "video") { inbound = s; break; }
    }
    if (!inbound) return;

    const now = performance.now();
    const dt = lastStatsTs ? (now - lastStatsTs) / 1000 : 1;
    lastStatsTs = now;

    const bytes = inbound.bytesReceived || 0;
    const framesDecoded = inbound.framesDecoded || 0;
    const deltaBytes = Math.max(0, bytes - lastBytes);
    const deltaFrames = Math.max(0, framesDecoded - lastFramesDecoded);
    lastBytes = bytes;
    lastFramesDecoded = framesDecoded;

    const kbps = Math.round((deltaBytes * 8) / 1000 / dt);
    const fps = Math.round(deltaFrames / dt);
    metaKbps.textContent = `${kbps} kbps`;
    metaFps.textContent = `${fps} fps`;
    if (inbound.frameWidth && inbound.frameHeight) {
      metaRes.textContent = `${inbound.frameWidth} × ${inbound.frameHeight}`;
    }
  } catch (e) {
    // stats podría fallar si la conexión aún no está lista
  }
}

async function handleSignaling({ kind, text }) {
  try {
    if (kind === KIND_OFFER) {
      const offer = JSON.parse(text);
      await createPeerAnswerer();
      await pc.setRemoteDescription({ type: "offer", sdp: offer.sdp });
      const answer = await pc.createAnswer();
      await pc.setLocalDescription(answer);
      await sendSignaling(KIND_ANSWER, JSON.stringify({ sdp: answer.sdp }));
      setStatus("connecting", "Negociando…");
    } else if (kind === KIND_ICE) {
      if (!pc) return;
      const ice = JSON.parse(text);
      await pc.addIceCandidate(ice);
    } else if (kind === KIND_BYE) {
      setStatus("disconnected", "Teléfono desconectado");
      closePeer();
    }
  } catch (e) {
    console.error("signaling error", e);
    setStatus("error", `signaling: ${e.message}`);
  }
}

(async () => {
  if (!("RTCPeerConnection" in window)) {
    setStatus("error", "WebRTC no soportado en este WebView");
    return;
  }
  setStatus("listening", "Esperando teléfono…");
  await loadPairing();

  await listen("mirror://status", (ev) => {
    const { state, message } = ev.payload;
    setStatus(state, message);
  });

  await listen("mirror://handshake", (ev) => {
    const { width, height, fps, peer } = ev.payload;
    console.log("handshake", width, height, fps, "from", peer);
    setStatus("connecting", `Negociando con ${peer}`);
  });

  await listen("mirror://signaling", (ev) => {
    handleSignaling(ev.payload);
  });
})();
