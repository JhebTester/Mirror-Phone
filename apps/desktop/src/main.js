const { invoke } = window.__TAURI__.core;
const { listen } = window.__TAURI__.event;

const statusEl = document.getElementById("status");
const ipEl = document.getElementById("ip");
const canvas = document.getElementById("screen");
const placeholder = document.getElementById("placeholder");
const metaRes = document.getElementById("meta-res");
const metaFps = document.getElementById("meta-fps");
const metaKbps = document.getElementById("meta-kbps");
const ctx = canvas.getContext("2d");

let decoder = null;
let handshake = null;
let bytesInWindow = 0;
let framesInWindow = 0;
let avcConfig = null;

function setStatus(state, message) {
  statusEl.className = `status ${state}`;
  statusEl.textContent = message;
}

async function initIP() {
  try {
    const ip = await invoke("get_local_ip");
    ipEl.textContent = ip;
  } catch (e) {
    ipEl.textContent = "?";
  }
}

// Parse the profile/level from an SPS NAL to build a codec string like "avc1.420029"
function codecStringFromSPS(spsBytes) {
  const p = spsBytes[1].toString(16).padStart(2, "0").toLowerCase();
  const c = spsBytes[2].toString(16).padStart(2, "0").toLowerCase();
  const l = spsBytes[3].toString(16).padStart(2, "0").toLowerCase();
  return `avc1.${p}${c}${l}`;
}

// Given an Annex-B buffer, iterate over NAL units (yields Uint8Array slices without start codes)
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
  if (start >= 0 && start < len) yield buf.subarray(start, len);
}

function extractSpsPps(csdBytes) {
  let sps = null;
  let pps = null;
  for (const nal of iterateAnnexB(csdBytes)) {
    if (!nal.length) continue;
    const type = nal[0] & 0x1f;
    if (type === 7 && !sps) sps = nal;
    if (type === 8 && !pps) pps = nal;
  }
  return { sps, pps };
}

// Build AVCDecoderConfigurationRecord (avcC) as required by W3C WebCodecs
function makeAvcCDecoderConfig(sps, pps) {
  if (!sps || !pps) return null;
  const len = 1 + 3 + 1 + 1 + 2 + sps.length + 1 + 2 + pps.length;
  const out = new Uint8Array(len);
  let offset = 0;
  out[offset++] = 1; // configurationVersion
  out[offset++] = sps[1]; // AVCProfileIndication
  out[offset++] = sps[2]; // profile_compatibility
  out[offset++] = sps[3]; // AVCLevelIndication
  out[offset++] = 0xff; // lengthSizeMinusOne = 3 (4-byte length prefix)
  out[offset++] = 0xe1; // numOfSequenceParameterSets = 1
  out[offset++] = (sps.length >> 8) & 0xff;
  out[offset++] = sps.length & 0xff;
  out.set(sps, offset);
  offset += sps.length;
  out[offset++] = 1; // numOfPictureParameterSets = 1
  out[offset++] = (pps.length >> 8) & 0xff;
  out[offset++] = pps.length & 0xff;
  out.set(pps, offset);
  return out;
}

// Convert Annex-B formatted buffer into AVCC 4-byte length prefixed NALUs
function annexBToAVCC(buf) {
  const nals = [];
  let totalLen = 0;
  for (const nal of iterateAnnexB(buf)) {
    if (nal.length > 0) {
      const type = nal[0] & 0x1f;
      // Skip in-band SPS/PPS when configured with avcC description
      if (type !== 7 && type !== 8) {
        nals.push(nal);
        totalLen += 4 + nal.length;
      }
    }
  }
  if (totalLen === 0) return null;
  const out = new Uint8Array(totalLen);
  let offset = 0;
  for (const nal of nals) {
    const len = nal.length;
    out[offset++] = (len >> 24) & 0xff;
    out[offset++] = (len >> 16) & 0xff;
    out[offset++] = (len >> 8) & 0xff;
    out[offset++] = len & 0xff;
    out.set(nal, offset);
    offset += len;
  }
  return out;
}

function b64ToBytes(b64) {
  const bin = atob(b64);
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}

async function ensureDecoder(csdBytes) {
  const { sps, pps } = extractSpsPps(csdBytes);
  if (!sps) {
    console.warn("CSD sin SPS válido", csdBytes);
    return;
  }
  const codec = codecStringFromSPS(sps);
  const avcC = makeAvcCDecoderConfig(sps, pps);
  avcConfig = avcC;

  console.log("Configurando VideoDecoder:", codec, "avcC bytes:", avcC ? avcC.length : 0);

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
      placeholder.classList.add("hidden");
    },
    error: (e) => {
      console.error("VideoDecoder error:", e);
      setStatus("error", `Decoder: ${e.message}`);
    },
  });

  const config = {
    codec: codec,
    optimizeForLatency: true,
  };
  if (avcC) {
    config.description = avcC;
  }

  try {
    decoder.configure(config);
    console.log("VideoDecoder configurado con éxito:", config);
  } catch (e) {
    console.error("decoder.configure error:", e);
    setStatus("error", `configure: ${e.message}`);
  }
}

async function onPacket({ kind, pts_us, b64 }) {
  const bytes = b64ToBytes(b64);
  bytesInWindow += bytes.length;

  if (kind === 0) {
    // CSD (SPS + PPS)
    await ensureDecoder(bytes);
    return;
  }
  if (!decoder || decoder.state !== "configured") return;

  // Convertir a formato AVCC que espera WebKit con description
  let chunkData = avcConfig ? annexBToAVCC(bytes) : bytes;
  if (!chunkData) return;

  const chunk = new EncodedVideoChunk({
    type: kind === 1 ? "key" : "delta",
    timestamp: Number(pts_us),
    data: chunkData,
  });
  try {
    decoder.decode(chunk);
  } catch (e) {
    console.error("decode error:", e);
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

  await initIP();
  setStatus("listening", "Escuchando en :7878");

  await listen("mirror://status", (e) => {
    const { state, message } = e.payload;
    setStatus(state, message);
    if (state === "disconnected") {
      placeholder.classList.remove("hidden");
      if (decoder) { try { decoder.close(); } catch {} decoder = null; }
    }
  });

  await listen("mirror://handshake", (e) => {
    handshake = e.payload;
    metaRes.textContent = `${handshake.width} x ${handshake.height}`;
    setStatus("connected", `Conectado ${handshake.peer}`);
  });

  await listen("mirror://packet", (e) => onPacket(e.payload));
})();
