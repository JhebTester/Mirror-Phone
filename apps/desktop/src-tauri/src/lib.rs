use base64::Engine;
use once_cell::sync::OnceCell;
use serde::Serialize;
use std::sync::Arc;
use tauri::{AppHandle, Emitter};
use tokio::io::AsyncReadExt;
use tokio::net::TcpListener;
use tokio::sync::Mutex;

// ---- Protocolo phone -> desktop ----
// Handshake (10 bytes):
//   magic  : 4  bytes  = b"MPH1"
//   width  : 2  bytes  BE u16
//   height : 2  bytes  BE u16
//   fps    : 2  bytes  BE u16
// Loop de paquetes:
//   flag   : 1 byte  (0 = CSD/SPS+PPS, 1 = keyframe, 2 = delta)
//   pts_us : 8 bytes BE u64
//   len    : 4 bytes BE u32
//   data   : len bytes en formato Annex-B (con start codes)

#[derive(Serialize, Clone)]
struct HandshakePayload {
    width: u16,
    height: u16,
    fps: u16,
    peer: String,
}

#[derive(Serialize, Clone)]
struct PacketPayload {
    kind: u8,      // 0 csd, 1 key, 2 delta
    pts_us: u64,
    b64: String,
}

#[derive(Serialize, Clone)]
struct StatusPayload {
    state: String,
    message: String,
}

struct ServerState {
    running: Mutex<bool>,
}

static STATE: OnceCell<Arc<ServerState>> = OnceCell::new();

#[tauri::command]
async fn get_local_ip() -> Result<String, String> {
    local_ip_address::local_ip()
        .map(|ip| ip.to_string())
        .map_err(|e| e.to_string())
}

#[tauri::command]
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
                    emit_status(
                        &app_h,
                        "connected",
                        &format!("Teléfono conectado desde {}", peer_str),
                    );

                    tauri::async_runtime::spawn(async move {
                        if let Err(e) = handle_client(&app_h, &mut socket, peer_str.clone()).await {
                            emit_status(&app_h, "disconnected", &format!("Cerrado {}: {}", peer_str, e));
                        } else {
                            emit_status(&app_h, "disconnected", &format!("Cerrado {}", peer_str));
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
    socket.set_nodelay(true).ok();

    // Handshake
    let mut header = [0u8; 10];
    socket.read_exact(&mut header).await?;
    if &header[0..4] != b"MPH1" {
        return Err(std::io::Error::new(
            std::io::ErrorKind::InvalidData,
            "bad magic",
        ));
    }
    let width = u16::from_be_bytes([header[4], header[5]]);
    let height = u16::from_be_bytes([header[6], header[7]]);
    let fps = u16::from_be_bytes([header[8], header[9]]);

    let _ = app.emit(
        "mirror://handshake",
        HandshakePayload {
            width,
            height,
            fps,
            peer,
        },
    );

    // Loop de paquetes
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

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    let _ = STATE.set(Arc::new(ServerState {
        running: Mutex::new(false),
    }));
    tauri::Builder::default()
        .plugin(tauri_plugin_opener::init())
        .invoke_handler(tauri::generate_handler![start_server, get_local_ip])
        .setup(|app| {
            // Auto-start listener en 7878.
            let handle = app.handle().clone();
            tauri::async_runtime::spawn(async move {
                if let Err(e) = start_server(handle.clone(), 7878).await {
                    let _ = handle.emit(
                        "mirror://status",
                        StatusPayload {
                            state: "error".into(),
                            message: format!("start_server: {}", e),
                        },
                    );
                }
            });
            Ok(())
        })
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
