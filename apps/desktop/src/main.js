const { invoke } = window.__TAURI__.core;
const { listen } = window.__TAURI__.event;

const statusEl = document.getElementById("status");
const canvas = document.getElementById("screen");
const pairing = document.getElementById("pairing");
const qrEl = document.getElementById("qr");
const pinEl = document.getElementById("pin");
const pairHost = document.getElementById("pair-host");
const pairPort = document.getElementById("pair-port");
const pairName = document.getElementById("pair-name");
const metaRes = document.getElementById("meta-res");
const metaFps = document.getElementById("meta-fps");
const metaKbps = document.getElementById("meta-kbps");
const ctx = canvas.getContext("2d");

let decoder = null;
let bytesInWindow = 0;
let framesInWindow = 0;

function setStatus(state, message) {
  statusEl.className = `status ${state}`;
  statusEl.textContent = message;
}

function renderQR(payload) {
  // qrcode-generator: type=0 (auto), 'M' error correction
  const qr = window.qrcode(0, "M");
  qr.addData(payload);
  qr.make();
  // createImgTag(cellSize, margin)
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
    // Reintenta cuando llegue la IP (setup async)
    setTimeout(loadPairing, 1000);
  }
}

function codecStringFromSPS(spsBytes) {
  const p = spsBytes[1].toString(16).padStart(2, "0");
  const c = spsBytes[2].toString(16).padStart(2, "0");
  const l = spsBytes[3].toString(16).padStart(2, "0");
  return `avc1.${p}${c}${l}`.toUpperCase();
}

function* iterateAnnexB(buf) {
  let i = 0;
  const len = buf.length;
  let start = -1;
  while (i < len - 3) {
    const isSC3 = buf[i] === 0 && buf[i + 1] === 0 && buf[i + 2] === 1;
    const isSC4 =
      buf[i] === 0 && buf[i + 1] === 0 && buf[i + 2] === 0 && buf[i + 3] === 1;
    if (isSC3 || isSC4) {
      const scLen = isSC4 ? 4 : 3;
      if (start >= 0) yield buf.subarray(start, i);
      start = i + scLen;
      i += scLen;
    } else {
      i++;
    }
  }
  if (start >= 0) yield buf.subarray(start, len);
}

function b64ToBytes(b64) {
  const bin = atob(b64);
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}

async function ensureDecoder(csdBytes) {
  let sps = null;
  for (const nal of iterateAnnexB(csdBytes)) {
    if (nal.length && (nal[0] & 0x1f) === 7) sps = nal;
  }
  if (!sps) return;
  const codec = codecStringFromSPS(sps);

  if (decoder) {
    try { decoder.close(); } catch {}
  }
  decoder = new VideoDecoder({
    output: (frame) => {
      if (canvas.width !== frame.displayWidth || canvas.height !== frame.displayHeight) {
        canvas.width = frame.displayWidth;
        canvas.height = frame.displayHeight;
      }
      ctx.drawImage(frame, 0, 0, canvas.width, canvas.height);
      frame.close();
      framesInWindow++;
      pairing.classList.add("hidden");
    },
    error: (e) => {
      console.error("decoder error", e);
      setStatus("error", `Decoder: ${e.message}`);
    },
  });

  try {
    decoder.configure({ codec, optimizeForLatency: true });
    console.log("VideoDecoder configurado:", codec);
  } catch (e) {
    console.error("configure failed", e);
    setStatus("error", `configure: ${e.message}`);
  }
}

async function onPacket({ kind, pts_us, b64 }) {
  const bytes = b64ToBytes(b64);
  bytesInWindow += bytes.length;

  if (kind === 0) {
    await ensureDecoder(bytes);
    return;
  }
  if (!decoder || decoder.state !== "configured") return;

  const chunk = new EncodedVideoChunk({
    type: kind === 1 ? "key" : "delta",
    timestamp: Number(pts_us),
    data: bytes,
  });
  try {
    decoder.decode(chunk);
  } catch (e) {
    console.error("decode failed", e);
  }
}

setInterval(() => {
  const kbps = ((bytesInWindow * 8) / 1000).toFixed(0);
  metaKbps.textContent = `${kbps} kbps`;
  metaFps.textContent = `${framesInWindow} fps`;
  bytesInWindow = 0;
  framesInWindow = 0;
}, 1000);

(async () => {
  if (!("VideoDecoder" in window)) {
    setStatus("error", "WebCodecs no soportado en este WebView");
    return;
  }
  setStatus("listening", "Esperando teléfono…");
  await loadPairing();

  await listen("mirror://status", (e) => {
    const { state, message } = e.payload;
    setStatus(state, message);
    if (state === "disconnected") {
      pairing.classList.remove("hidden");
      if (decoder) { try { decoder.close(); } catch {} decoder = null; }
    }
  });

  await listen("mirror://handshake", (e) => {
    const hs = e.payload;
    metaRes.textContent = `${hs.width} x ${hs.height}`;
    setStatus("connected", `Conectado ${hs.peer}`);
  });

  await listen("mirror://packet", (e) => onPacket(e.payload));
})();
