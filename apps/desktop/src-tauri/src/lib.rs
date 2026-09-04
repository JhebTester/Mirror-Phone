use base64::Engine;
use mdns_sd::{ServiceDaemon, ServiceInfo};
use once_cell::sync::OnceCell;
use rand::Rng;
use serde::Serialize;
use std::collections::HashMap;
use std::sync::Arc;
use tauri::{AppHandle, Emitter};
use tokio::io::AsyncReadExt;
use tokio::net::TcpListener;
use tokio::sync::Mutex;

// ---- Protocolo phone -> desktop ----
//
// Handshake v1 (legacy, sin PIN, 10 bytes):
//   magic  : 4 bytes = b"MPH1"
//   width  : 2 bytes BE u16
//   height : 2 bytes BE u16
//   fps    : 2 bytes BE u16
//
// Handshake v2 (con PIN, 16 bytes):
//   magic  : 4 bytes = b"MPH2"
//   pin    : 6 bytes ASCII
//   width  : 2 bytes BE u16
//   height : 2 bytes BE u16
//   fps    : 2 bytes BE u16
//
// Respuesta del servidor tras validar (solo v2):
//   1 byte: 0x00 = OK, 0x01 = PIN inválido
//
// Loop de paquetes (idéntico en ambas versiones):
//   kind   : 1 byte  (0 = CSD/SPS+PPS, 1 = keyframe, 2 = delta)
//   pts_us : 8 bytes BE u64
//   len    : 4 bytes BE u32
//   data   : len bytes en formato Annex-B

const SERVICE_TYPE: &str = "_mirrorphone._tcp.local.";
const PROTO_VERSION: &str = "2";

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
        .unwrap_or_else(|| "Mac".into())
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
    emit_status(&app, "listening", &format!("Escuchando en {}", addr));

    let app_handle = app.clone();
    tauri::async_runtime::spawn(async move {
        loop {
            match listener.accept().await {
                Ok((mut socket, peer)) => {
                    let app_h = app_handle.clone();
                    let peer_str = peer.to_string();
                    tauri::async_runtime::spawn(async move {
                        match handle_client(&app_h, &mut socket, peer_str.clone()).await {
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

fn emit_status(app: &AppHandle, state: &str, message: &str) {
    let _ = app.emit(
        "mirror://status",
        StatusPayload {
            state: state.into(),
            message: message.into(),
        },
    );
}

async fn handle_client(
    app: &AppHandle,
    socket: &mut tokio::net::TcpStream,
    peer: String,
) -> std::io::Result<()> {
    use tokio::io::AsyncWriteExt;

    socket.set_nodelay(true).ok();

    let mut magic = [0u8; 4];
    socket.read_exact(&mut magic).await?;

    let (width, height, fps);
    if &magic == b"MPH2" {
        let mut buf = [0u8; 12];
        socket.read_exact(&mut buf).await?;
        let received_pin: String = std::str::from_utf8(&buf[0..6])
            .map(|s| s.trim().to_string())
            .unwrap_or_default();
        let expected_pin = STATE.get().map(|s| s.pin.clone()).unwrap_or_default();
        if received_pin != expected_pin {
            let _ = socket.write_all(&[0x01u8]).await;
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
        socket.write_all(&[0x00u8]).await?;
        width = u16::from_be_bytes([buf[6], buf[7]]);
        height = u16::from_be_bytes([buf[8], buf[9]]);
        fps = u16::from_be_bytes([buf[10], buf[11]]);
    } else if &magic == b"MPH1" {
        let mut buf = [0u8; 6];
        socket.read_exact(&mut buf).await?;
        width = u16::from_be_bytes([buf[0], buf[1]]);
        height = u16::from_be_bytes([buf[2], buf[3]]);
        fps = u16::from_be_bytes([buf[4], buf[5]]);
        emit_status(app, "warning", "Cliente legacy MPH1 (sin PIN)");
    } else {
        return Err(std::io::Error::new(
            std::io::ErrorKind::InvalidData,
            "bad magic",
        ));
    }

    emit_status(
        app,
        "connected",
        &format!("Teléfono conectado desde {}", peer),
    );
    let _ = app.emit(
        "mirror://handshake",
        HandshakePayload {
            width,
            height,
            fps,
            peer,
        },
    );

    let engine = base64::engine::general_purpose::STANDARD;
    loop {
        let mut hdr = [0u8; 13];
        socket.read_exact(&mut hdr).await?;
        let kind = hdr[0];
        let pts_us = u64::from_be_bytes([
            hdr[1], hdr[2], hdr[3], hdr[4], hdr[5], hdr[6], hdr[7], hdr[8],
        ]);
        let len = u32::from_be_bytes([hdr[9], hdr[10], hdr[11], hdr[12]]) as usize;
        if len == 0 || len > 8 * 1024 * 1024 {
            return Err(std::io::Error::new(
                std::io::ErrorKind::InvalidData,
                format!("bad packet size {}", len),
            ));
        }
        let mut buf = vec![0u8; len];
        socket.read_exact(&mut buf).await?;
        let b64 = engine.encode(&buf);
        let _ = app.emit(
            "mirror://packet",
            PacketPayload { kind, pts_us, b64 },
        );
    }
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
    }));

    tauri::Builder::default()
        .plugin(tauri_plugin_opener::init())
        .invoke_handler(tauri::generate_handler![
            get_local_ip,
            get_pairing_info
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

#[tauri::command]
#[allow(dead_code)]
async fn restart_server(app: AppHandle, port: u16) -> Result<(), String> {
    start_server(app, port).await
}
