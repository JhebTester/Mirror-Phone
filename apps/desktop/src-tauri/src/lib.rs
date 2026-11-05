use base64::{engine::general_purpose::STANDARD as B64, Engine as _};
use mdns_sd::{ServiceDaemon, ServiceInfo};
use once_cell::sync::OnceCell;
use rand::Rng;
use serde::Serialize;
use std::collections::{BTreeMap, HashMap};
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::Arc;
use std::time::{Duration, Instant};
use tauri::{AppHandle, Emitter};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpListener, UdpSocket};
use tokio::sync::{mpsc, Mutex};

// ---- Protocolo MPH4: Control por TCP + Video Zero-Buffer por UDP ----
//
// 1) Handshake TCP (16 bytes):
//      magic  : 4 bytes = b"MPH4" (UDP + PLI) o b"MPH2" (legacy TCP)
//      pin    : 6 bytes ASCII
//      width  : 2 bytes BE u16
//      height : 2 bytes BE u16
//      fps    : 2 bytes BE u16
//    Respuesta TCP del servidor:
//      1 byte : 0x00 = OK, 0x01 = PIN inválido
//
// 2) Canal de control TCP (post-ACK en MPH4):
//    - Phone -> Desktop: paquetes framing (kind: u8, pts_us: u64 BE, len: u32 BE, data)
//        kind = 0x00 -> CSD (SPS+PPS) fiable
//        kind = 0x13 -> BYE (cierre limpio)
//    - Desktop -> Phone: comandos de 1 byte
//        0x01 -> PLI (Picture Loss Indication: solicita IDR inmediato)
//
// 3) Canal de video UDP (mismo puerto 7878):
//    Cabecera de 20 bytes por datagrama (máx ~1380 bytes total):
//      0..2   : b"MP" (0x4D, 0x50)
//      2      : kind (0 = CSD, 1 = Keyframe/IDR, 2 = Delta)
//      3      : flags (reservado)
//      4..8   : frame_id (u32 BE)
//      8..10  : frag_idx (u16 BE)
//      10..12 : frag_count (u16 BE)
//      12..20 : pts_us (u64 BE)
//      20..   : fragment payload (Annex-B)

const SERVICE_TYPE: &str = "_mirrorphone._tcp.local.";
const PROTO_VERSION: &str = "4";
const CMD_PLI: u8 = 0x01;

#[derive(Serialize, Clone)]
struct HandshakePayload {
    width: u16,
    height: u16,
    fps: u16,
    peer: String,
}

#[derive(Serialize, Clone)]
struct PacketPayload {
    kind: u8,
    pts_us: u64,
    b64: String,
}

#[derive(Serialize, Clone)]
struct StatusPayload {
    state: String,
    message: String,
}

#[derive(Serialize, Clone)]
struct PairingInfo {
    host: String,
    port: u16,
    pin: String,
    name: String,
    qr_payload: String,
}

struct ServerState {
    running: Mutex<bool>,
    pin: String,
    hostname: String,
    port: u16,
    /// Canal para enviar comandos de control (como CMD_PLI) por TCP al teléfono activo.
    control_tx: Mutex<Option<mpsc::UnboundedSender<u8>>>,
    /// Contador incremental de sesión para reiniciar el estado UDP en cada nueva conexión.
    session_epoch: AtomicU64,
    /// Indica si hay un cliente autenticado actualmente activo.
    client_connected: AtomicBool,
}

static STATE: OnceCell<Arc<ServerState>> = OnceCell::new();

fn generate_pin() -> String {
    const ALPHABET: &[u8] = b"ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    let mut rng = rand::thread_rng();
    (0..6)
        .map(|_| ALPHABET[rng.gen_range(0..ALPHABET.len())] as char)
        .collect()
}

fn detect_hostname() -> String {
    hostname::get()
        .ok()
        .and_then(|s| s.into_string().ok())
        .unwrap_or_else(|| "Desktop".into())
}

#[tauri::command]
async fn get_local_ip() -> Result<String, String> {
    local_ip_address::local_ip()
        .map(|ip| ip.to_string())
        .map_err(|e| e.to_string())
}

#[tauri::command]
async fn get_pairing_info() -> Result<PairingInfo, String> {
    let state = STATE.get().ok_or("state uninitialized")?.clone();
    let host = local_ip_address::local_ip()
        .map(|ip| ip.to_string())
        .map_err(|e| e.to_string())?;
    let qr = serde_json::json!({
        "v": PROTO_VERSION,
        "host": host,
        "port": state.port,
        "pin": state.pin,
        "name": state.hostname,
    })
    .to_string();
    Ok(PairingInfo {
        host,
        port: state.port,
        pin: state.pin.clone(),
        name: state.hostname.clone(),
        qr_payload: qr,
    })
}

/// Solicita un Keyframe (IDR) inmediato al teléfono conectado.
#[tauri::command]
async fn request_keyframe() -> Result<(), String> {
    trigger_pli().await;
    Ok(())
}

async fn trigger_pli() {
    if let Some(state) = STATE.get() {
        let guard = state.control_tx.lock().await;
        if let Some(tx) = guard.as_ref() {
            let _ = tx.send(CMD_PLI);
        }
    }
}

fn emit_packet(app: &AppHandle, kind: u8, pts_us: u64, data: &[u8]) {
    let _ = app.emit(
        "mirror://packet",
        PacketPayload {
            kind,
            pts_us,
            b64: B64.encode(data),
        },
    );
}

fn emit_status(app: &AppHandle, state: &str, message: &str) {
    let _ = app.emit(
        "mirror://status",
        StatusPayload {
            state: state.into(),
            message: message.into(),
        },
    );
}

struct FrameAssembly {
    kind: u8,
    pts_us: u64,
    frag_count: u16,
    received: u16,
    chunks: Vec<Option<Vec<u8>>>,
}

impl FrameAssembly {
    fn new(kind: u8, pts_us: u64, frag_count: u16) ->Self {
        Self {
            kind,
            pts_us,
            frag_count,
            received: 0,
            chunks: vec![None; frag_count as usize],
        }
    }

    fn insert(&mut self, idx: u16, data: Vec<u8>) -> bool {
        let i = idx as usize;
        if i >= self.chunks.len() {
            return false;
        }
        if self.chunks[i].is_none() {
            self.chunks[i] = Some(data);
            self.received += 1;
        }
        self.received == self.frag_count
    }

    fn into_payload(self) -> Vec<u8> {
        let total: usize = self
            .chunks
            .iter()
            .filter_map(|c| c.as_ref().map(|v| v.len()))
            .sum();
        let mut out = Vec::with_capacity(total);
        for c in self.chunks.into_iter().flatten() {
            out.extend_from_slice(&c);
        }
        out
    }
}

async fn run_udp_receiver(app: AppHandle, udp: UdpSocket) {
    let mut buf = vec![0u8; 65536];
    let mut assemblies: BTreeMap<u32, FrameAssembly> = BTreeMap::new();
    let mut last_delivered: Option<u32> = None;
    let mut waiting_for_keyframe = true;
    let mut last_pli_at = Instant::now() - Duration::from_secs(1);
    let mut observed_epoch: u64 = 0;

    loop {
        let (len, _peer) = match udp.recv_from(&mut buf).await {
            Ok(v) => v,
            Err(_) => continue,
        };
        if len < 20 || &buf[0..2] != b"MP" {
            continue;
        }

        // Si empezó una nueva conexión TCP, reseteamos el estado del ensamblador UDP.
        if let Some(state) = STATE.get() {
            if !state.client_connected.load(Ordering::Relaxed) {
                continue;
            }
            let ep = state.session_epoch.load(Ordering::Relaxed);
            if ep != observed_epoch {
                observed_epoch = ep;
                assemblies.clear();
                last_delivered = None;
                waiting_for_keyframe = true;
            }
        }

        let kind = buf[2];
        let frame_id = u32::from_be_bytes([buf[4], buf[5], buf[6], buf[7]]);
        let frag_idx = u16::from_be_bytes([buf[8], buf[9]]);
        let frag_count = u16::from_be_bytes([buf[10], buf[11]]);
        let pts_us = u64::from_be_bytes([
            buf[12], buf[13], buf[14], buf[15], buf[16], buf[17], buf[18], buf[19],
        ]);

        if frag_count == 0 || frag_idx >= frag_count || frag_count > 512 {
            continue;
        }
        let slice = &buf[20..len];

        // CSD (SPS+PPS) cabe en 1 paquete y se entrega siempre de inmediato
        if kind == 0 && frag_count == 1 {
            emit_packet(&app, 0, pts_us, slice);
            continue;
        }

        // Ignorar paquetes de frames ya entregados o demasiado viejos
        if let Some(last) = last_delivered {
            if frame_id <= last && last.wrapping_sub(frame_id) < 10_000 {
                continue;
            }
        }

        // Fast-path: frame en 1 solo datagrama (la mayoría de los P-frames)
        let completed = if frag_count == 1 {
            Some((kind, pts_us, slice.to_vec()))
        } else {
            let entry = assemblies
                .entry(frame_id)
                .or_insert_with(|| FrameAssembly::new(kind, pts_us, frag_count));
            if entry.insert(frag_idx, slice.to_vec()) {
                let done = assemblies.remove(&frame_id).unwrap();
                Some((done.kind, done.pts_us, done.into_payload()))
            } else {
                None
            }
        };

        // Limpiar frames incompletos anteriores si ya avanzó la secuencia
        let stale_keys: Vec<u32> = assemblies
            .keys()
            .copied()
            .filter(|&k| frame_id > k && (frame_id - k) >= 2)
            .collect();
        if !stale_keys.is_empty() {
            for k in stale_keys {
                assemblies.remove(&k);
            }
            // Se perdió al menos un fragmento de un frame previo
            waiting_for_keyframe = true;
            if last_pli_at.elapsed() >= Duration::from_millis(50) {
                last_pli_at = Instant::now();
                trigger_pli().await;
            }
        }

        let Some((pkt_kind, pkt_pts, payload)) = completed else {
            continue;
        };

        // Descartar cualquier ensamblaje previo más viejo que el frame recién completado
        let older_incomplete: Vec<u32> = assemblies
            .keys()
            .copied()
            .filter(|&k| k < frame_id)
            .collect();
        let had_older_incomplete = !older_incomplete.is_empty();
        for k in older_incomplete {
            assemblies.remove(&k);
        }

        if pkt_kind == 1 {
            // Keyframe (IDR): recupera el estado inmediatamente
            waiting_for_keyframe = false;
            last_delivered = Some(frame_id);
            emit_packet(&app, 1, pkt_pts, &payload);
        } else if pkt_kind == 2 {
            // Delta (P-frame): entregar SOLO si no hay huecos desde el último frame entregado
            let in_order = !had_older_incomplete
                && match last_delivered {
                    Some(prev) => frame_id == prev.wrapping_add(1),
                    None => false,
                };
            if !waiting_for_keyframe && in_order {
                last_delivered = Some(frame_id);
                emit_packet(&app, 2, pkt_pts, &payload);
            } else {
                waiting_for_keyframe = true;
                if last_pli_at.elapsed() >= Duration::from_millis(50) {
                    last_pli_at = Instant::now();
                    trigger_pli().await;
                }
            }
        }
    }
}

async fn start_server(app: AppHandle, port: u16) -> Result<(), String> {
    let state = STATE.get().ok_or("state uninitialized")?.clone();
    {
        let mut running = state.running.lock().await;
        if *running {
            return Err("server already running".into());
        }
        *running = true;
    }
    let addr = format!("0.0.0.0:{}", port);
    let listener = TcpListener::bind(&addr).await.map_err(|e| e.to_string())?;
    let udp = UdpSocket::bind(&addr).await.map_err(|e| e.to_string())?;
    emit_status(&app, "listening", &format!("Escuchando en {} (TCP+UDP)", addr));

    let udp_app = app.clone();
    tauri::async_runtime::spawn(async move {
        run_udp_receiver(udp_app, udp).await;
    });

    let app_handle = app.clone();
    tauri::async_runtime::spawn(async move {
        loop {
            match listener.accept().await {
                Ok((socket, peer)) => {
                    let app_h = app_handle.clone();
                    let peer_str = peer.to_string();
                    tauri::async_runtime::spawn(async move {
                        match handle_client(&app_h, socket, peer_str.clone()).await {
                            Ok(()) => emit_status(
                                &app_h,
                                "disconnected",
                                &format!("Cerrado {}", peer_str),
                            ),
                            Err(e) => emit_status(
                                &app_h,
                                "disconnected",
                                &format!("Cerrado {}: {}", peer_str, e),
                            ),
                        }
                    });
                }
                Err(e) => {
                    emit_status(&app_handle, "error", &format!("accept: {}", e));
                    break;
                }
            }
        }
    });

    Ok(())
}

async fn handle_client(
    app: &AppHandle,
    socket: tokio::net::TcpStream,
    peer: String,
) -> std::io::Result<()> {
    socket.set_nodelay(true).ok();
    let (mut rd, mut wr) = socket.into_split();

    let mut magic = [0u8; 4];
    rd.read_exact(&mut magic).await?;

    if &magic != b"MPH4" && &magic != b"MPH2" {
        return Err(std::io::Error::new(
            std::io::ErrorKind::InvalidData,
            "bad magic",
        ));
    }

    let mut buf = [0u8; 12];
    rd.read_exact(&mut buf).await?;
    let received_pin: String = std::str::from_utf8(&buf[0..6])
        .map(|s| s.trim().to_string())
        .unwrap_or_default();
    let expected_pin = STATE.get().map(|s| s.pin.clone()).unwrap_or_default();
    if received_pin != expected_pin {
        let _ = wr.write_all(&[0x01u8]).await;
        emit_status(
            app,
            "auth_failed",
            &format!("PIN inválido desde {} (recibido: {})", peer, received_pin),
        );
        return Err(std::io::Error::new(
            std::io::ErrorKind::PermissionDenied,
            "bad pin",
        ));
    }
    wr.write_all(&[0x00u8]).await?;

    let width = u16::from_be_bytes([buf[6], buf[7]]);
    let height = u16::from_be_bytes([buf[8], buf[9]]);
    let fps = u16::from_be_bytes([buf[10], buf[11]]);

    let (tx, mut rx) = mpsc::unbounded_channel::<u8>();
    let my_epoch = if let Some(state) = STATE.get() {
        let ep = state.session_epoch.fetch_add(1, Ordering::SeqCst) + 1;
        state.client_connected.store(true, Ordering::SeqCst);
        let mut guard = state.control_tx.lock().await;
        *guard = Some(tx);
        ep
    } else {
        0
    };

    emit_status(
        app,
        "connected",
        &format!("Espejando desde {} (UDP Zero-Buffer)", peer),
    );
    let _ = app.emit(
        "mirror://handshake",
        HandshakePayload {
            width,
            height,
            fps,
            peer: peer.clone(),
        },
    );

    // Tarea que envía comandos de control (CMD_PLI = 0x01) al teléfono por TCP
    let writer_task = tauri::async_runtime::spawn(async move {
        while let Some(cmd) = rx.recv().await {
            if wr.write_all(&[cmd]).await.is_err() {
                break;
            }
        }
    });

    // En MPH4 el socket TCP también recibe CSD (kind=0) fiable, BYE (kind=0x13)
    // o paquetes legacy (en MPH2).
    let read_result: std::io::Result<()> = async {
        loop {
            let mut hdr = [0u8; 13];
            rd.read_exact(&mut hdr).await?;
            let kind = hdr[0];
            if kind == 0x13 {
                // BYE
                return Ok(());
            }
            let pts_us = u64::from_be_bytes([
                hdr[1], hdr[2], hdr[3], hdr[4], hdr[5], hdr[6], hdr[7], hdr[8],
            ]);
            let len = u32::from_be_bytes([hdr[9], hdr[10], hdr[11], hdr[12]]) as usize;
            if len == 0 || len > 8 * 1024 * 1024 {
                return Err(std::io::Error::new(
                    std::io::ErrorKind::InvalidData,
                    format!("invalid packet size {}", len),
                ));
            }
            let mut data = vec![0u8; len];
            rd.read_exact(&mut data).await?;
            emit_packet(app, kind, pts_us, &data);
        }
    }
    .await;

    writer_task.abort();
    if let Some(state) = STATE.get() {
        if state.session_epoch.load(Ordering::SeqCst) == my_epoch {
            state.client_connected.store(false, Ordering::SeqCst);
            let mut guard = state.control_tx.lock().await;
            *guard = None;
        }
    }
    read_result
}

fn start_mdns(
    port: u16,
    pin: &str,
    host_ip: &str,
    host_name: &str,
) -> Result<ServiceDaemon, String> {
    let daemon = ServiceDaemon::new().map_err(|e| e.to_string())?;
    let mut txt = HashMap::new();
    txt.insert("v".to_string(), PROTO_VERSION.to_string());
    txt.insert("name".to_string(), host_name.to_string());
    txt.insert("pin".to_string(), pin.to_string());

    let instance = format!("MirrorPhone-{}", &pin[..4]);
    let full_host = format!("{}.local.", host_name.replace(' ', "-"));

    let info = ServiceInfo::new(
        SERVICE_TYPE,
        &instance,
        &full_host,
        host_ip,
        port,
        Some(txt),
    )
    .map_err(|e| e.to_string())?;

    daemon.register(info).map_err(|e| e.to_string())?;
    Ok(daemon)
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    let pin = generate_pin();
    let hostname = detect_hostname();
    let port: u16 = 7878;

    let _ = STATE.set(Arc::new(ServerState {
        running: Mutex::new(false),
        pin: pin.clone(),
        hostname: hostname.clone(),
        port,
        control_tx: Mutex::new(None),
        session_epoch: AtomicU64::new(1),
        client_connected: AtomicBool::new(false),
    }));

    tauri::Builder::default()
        .plugin(tauri_plugin_opener::init())
        .invoke_handler(tauri::generate_handler![
            get_local_ip,
            get_pairing_info,
            request_keyframe
        ])
        .setup(move |app| {
            let handle = app.handle().clone();
            tauri::async_runtime::spawn(async move {
                if let Err(e) = start_server(handle.clone(), port).await {
                    let _ = handle.emit(
                        "mirror://status",
                        StatusPayload {
                            state: "error".into(),
                            message: format!("start_server: {}", e),
                        },
                    );
                }
            });

            match local_ip_address::local_ip() {
                Ok(ip) => match start_mdns(port, &pin, &ip.to_string(), &hostname) {
                    Ok(daemon) => {
                        std::mem::forget(daemon);
                        let h = app.handle().clone();
                        emit_status(
                            &h,
                            "listening",
                            &format!("mDNS publicado en {}:{}", ip, port),
                        );
                    }
                    Err(e) => {
                        let h = app.handle().clone();
                        emit_status(&h, "warning", &format!("mDNS no disponible: {}", e));
                    }
                },
                Err(e) => {
                    let h = app.handle().clone();
                    emit_status(&h, "warning", &format!("Sin IP local: {}", e));
                }
            }

            Ok(())
        })
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
