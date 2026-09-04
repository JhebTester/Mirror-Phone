# Mirror Phone — PoC

Espeja la pantalla de un **Android** hacia una app de escritorio **macOS** (Tauri),
sin cables, por Wi-Fi en LAN. Este repo contiene la PoC:

```
apps/
  desktop/   # Receptor Tauri (Rust + Vanilla JS + WebCodecs)
  android/   # Agente Android (Kotlin + MediaProjection + MediaCodec)
```

## Cómo funciona (PoC)

1. El desktop levanta un servidor TCP en `0.0.0.0:7878`.
2. El teléfono conecta por TCP y envía handshake + stream H.264 en formato Annex-B.
3. El backend Rust reenvía cada paquete al WebView vía evento Tauri.
4. El WebView decodifica con `VideoDecoder` (WebCodecs) y pinta en `<canvas>`.

Protocolo (`apps/desktop/src-tauri/src/lib.rs`):

```
handshake: "MPH1" | width u16 BE | height u16 BE | fps u16 BE
paquete:   kind u8 | pts u64 BE | len u32 BE | payload (Annex-B)
           kind = 0 CSD (SPS+PPS), 1 keyframe, 2 delta
```

## Requisitos

- **macOS** con Xcode CLT, Rust ≥ 1.77, Node ≥ 20.
- **Android Studio Koala** o superior (para compilar el agente).
- Teléfono Android 7.0+ (API 24+) en la **misma red Wi-Fi** que el Mac.

## Correr el receptor (Tauri) en macOS

```bash
cd apps/desktop
npm install
npm run tauri dev
```

La ventana muestra la IP local del Mac (por ejemplo `192.168.1.42`). Anótala.

## Correr el agente Android

1. Abre `apps/android` en Android Studio.
2. Deja que Gradle sincronice y genere el wrapper (`gradlew`) automáticamente.
3. Conecta un teléfono con **depuración USB** habilitada (solo para instalar; el
   streaming va por Wi-Fi).
4. `Run ▶` sobre el módulo `app`.
5. En la app: escribe la IP del Mac + puerto `7878` y pulsa **Iniciar espejo**.
   Android pedirá permiso de captura de pantalla.

## Ajustes rápidos

- Resolución/bitrate: `CaptureService.kt` → `maxSide`, `BITRATE`, `TARGET_FPS`.
- Puerto: constantes `7878` en `lib.rs` y `activity_main.xml`.

## Limitaciones conocidas de esta PoC

- Sin descubrimiento automático — hay que teclear la IP.
- Sin input inverso (solo visualización).
- Sin audio.
- Sin cifrado (TCP plano en LAN de confianza).
- Sin reconexión automática.
- iOS aún no soportado (planeado con `ReplayKit` Broadcast Extension).

Todo esto está en el roadmap. Ver la propuesta principal para las siguientes fases.
