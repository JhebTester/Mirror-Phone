// Mirror Phone — Desktop Receiver (MPH4: UDP Zero-Buffer + Instant PLI + WebCodecs)
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
const ctx =
  canvas.getContext("2d", { alpha: false, desynchronized: true }) ||
  canvas.getContext("2d");

let decoder = null;
let lastCsdHex = "";
let bytesInWindow = 0;
let framesInWindow = 0;
let lastPliMs = 0;

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

function requestKeyframeThrottled() {
  const now = performance.now();
  if (now - lastPliMs < 80) return;
  lastPliMs = now;
  invoke("request_keyframe").catch(() => {});
}

function codecStringFromSPS(spsBytes) {
  const p = spsBytes[1].toString(16).padStart(2, "0");
  const c = spsBytes[2].toString(16).padStart(2, "0");
  const l = spsBytes[3].toString(16).padStart(2, "0");
  return `avc1.${p}${c}${l}`.toUpperCase();
}

function buildAvcCDescription(sps, pps) {
  const total = 5 + 1 + 2 + sps.length + 1 + 2 + pps.length;
  const out = new Uint8Array(total);
  let o = 0;
  out[o++] = 0x01;
  out[o++] = sps[1];
  out[o++] = sps[2];
  out[o++] = sps[3];
  out[o++] = 0xff;
  out[o++] = 0xe1;
  out[o++] = (sps.length >> 8) & 0xff;
  out[o++] = sps.length & 0xff;
  out.set(sps, o);
  o += sps.length;
  out[o++] = 0x01;
  out[o++] = (pps.length >> 8) & 0xff;
  out[o++] = pps.length & 0xff;
  out.set(pps, o);
  return out;
}

function annexBtoAvcc(annexB) {
  const nals = [];
  let total = 0;
  for (const nal of iterateAnnexB(annexB)) {
    const t = nal[0] & 0x1f;
    // Ignorar SPS(7)/PPS(8) embebidos cuando el decoder usa avcC description
    if (t === 7 || t === 8) continue;
    nals.push(nal);
    total += 4 + nal.length;
  }
  const out = new Uint8Array(total);
  let o = 0;
  for (const nal of nals) {
    out[o++] = (nal.length >>> 24) & 0xff;
    out[o++] = (nal.length >>> 16) & 0xff;
    out[o++] = (nal.length >>> 8) & 0xff;
    out[o++] = nal.length & 0xff;
    out.set(nal, o);
    o += nal.length;
  }
  return out;
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

function toHex(u8) {
  let s = "";
  for (let i = 0; i < u8.length; i++) {
    s += u8[i].toString(16).padStart(2, "0");
  }
  return s;
}

async function ensureDecoder(csdBytes) {
  let sps = null,
    pps = null;
  for (const nal of iterateAnnexB(csdBytes)) {
    if (!nal.length) continue;
    const t = nal[0] & 0x1f;
    if (t === 7) sps = nal;
    else if (t === 8) pps = nal;
  }
  if (!sps || !pps) return;

  const sig = `${toHex(sps)}:${toHex(pps)}`;
  if (decoder && decoder.state === "configured" && lastCsdHex === sig) {
    return;
  }
  lastCsdHex = sig;

  const detected = codecStringFromSPS(sps);
  const description = buildAvcCDescription(sps, pps);

  const candidates = [
    detected,
    "avc1.42E01E",
    "avc1.42E01F",
    "avc1.4D401E",
    "avc1.4D401F",
    "avc1.640028",
  ];
  const seen = new Set();
  const uniq = candidates.filter((c) => (seen.has(c) ? false : (seen.add(c), true)));

  let chosen = null;
  let chosenConfig = null;
  for (const codec of uniq) {
    for (const withDesc of [true, false]) {
      const cfg = { codec, optimizeForLatency: true };
      if (withDesc) cfg.description = description;
      try {
        const res = await VideoDecoder.isConfigSupported(cfg);
        if (res.supported) {
          chosen = codec;
          chosenConfig = cfg;
          break;
        }
      } catch {}
    }
    if (chosen) break;
  }

  if (!chosen) {
    setStatus("error", `Codec no soportado (${detected})`);
    return;
  }

  if (decoder) {
    try {
      decoder.close();
    } catch {}
  }
  decoder = new VideoDecoder({
    output: (frame) => {
      if (
        canvas.width !== frame.displayWidth ||
        canvas.height !== frame.displayHeight
      ) {
        canvas.width = frame.displayWidth;
        canvas.height = frame.displayHeight;
        metaRes.textContent = `${frame.displayWidth} × ${frame.displayHeight}`;
      }
      ctx.drawImage(frame, 0, 0, canvas.width, canvas.height);
      frame.close();
      framesInWindow++;
      pairing.classList.add("hidden");
    },
    error: (e) => {
      console.error("decoder error", e);
      lastCsdHex = "";
      requestKeyframeThrottled();
    },
  });

  try {
    decoder.configure(chosenConfig);
    decoder._avccMode = !!chosenConfig.description;
    requestKeyframeThrottled();
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

  // Si el keyframe trae SPS+PPS embebidos y aún no hay decoder, configúralo al vuelo
  if (kind === 1 && (!decoder || decoder.state !== "configured")) {
    await ensureDecoder(bytes);
  }
  if (!decoder || decoder.state !== "configured") {
    requestKeyframeThrottled();
    return;
  }

  // Si la cola de decodificación acumula más de 2 cuadros, pedimos IDR para saltar al presente
  if (decoder.decodeQueueSize > 3 && kind === 2) {
    requestKeyframeThrottled();
    return;
  }

  const payload = decoder._avccMode ? annexBtoAvcc(bytes) : bytes;
  if (!payload.length) return;

  try {
    decoder.decode(
      new EncodedVideoChunk({
        type: kind === 1 ? "key" : "delta",
        timestamp: Number(pts_us),
        data: payload,
      })
    );
  } catch (e) {
    console.warn("decode error, requesting PLI:", e);
    requestKeyframeThrottled();
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
      lastCsdHex = "";
      if (decoder) {
        try {
          decoder.close();
        } catch {}
        decoder = null;
      }
    }
  });

  await listen("mirror://handshake", (e) => {
    const hs = e.payload;
    lastCsdHex = "";
    metaRes.textContent = `${hs.width} × ${hs.height}`;
    setStatus("connected", `Conectado ${hs.peer}`);
  });

  await listen("mirror://packet", (e) => onPacket(e.payload));
})();
