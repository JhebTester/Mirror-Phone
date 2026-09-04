# Mirror Phone

Espeja la pantalla de un **Android** hacia una app de escritorio **macOS**
(Tauri), sin cables, por Wi-Fi en LAN. Esta versión implementa la **Fase 1**:
descubrimiento automático y emparejamiento por QR + PIN.

```
apps/
  desktop/   # Receptor Tauri (Rust + Vanilla JS + WebCodecs)
  android/   # Agente Android (Kotlin + MediaProjection + MediaCodec)
scripts/
  gen_icon.py  # Generador del ícono (Pillow)
```

## Novedades — Fase 1

- **Descubrimiento mDNS**: el Mac publica `_mirrorphone._tcp.local.` con
  hostname y PIN.
- **QR pairing**: el Mac muestra un QR con `{host, port, pin, name}`. El
  teléfono lo escanea con la cámara.
- **PIN visible** de 6 caracteres (alfabeto sin caracteres ambiguos).
- **Lista de servidores** en el Android descubiertos por NsdManager.
- **Handshake v2 (MPH2)** con validación de PIN.
- **Compatibilidad legacy**: MPH1 sin PIN sigue funcionando.

## Cómo funciona

1. Mac abre TCP en `:7878` y publica mDNS con el PIN de la sesión.
2. Frontend muestra el QR (payload JSON) + PIN en un overlay.
3. Android escanea QR **o** elige un servidor detectado por mDNS **o** entra
   IP+PIN manual.
4. Android envía `MPH2 | pin(6) | w | h | fps` → servidor responde `0x00 OK` o
   `0x01 rechazado`.
5. Streaming H.264 en Annex-B → WebCodecs `VideoDecoder` en el WebView.

## Requisitos

- **macOS** con Rust ≥ 1.77, Node ≥ 20.
- **Android Studio** con JDK 21 (viene incluido) y SDK Platform 34.
- Teléfono Android 7.0+ (API 24+) en la **misma red Wi-Fi** que el Mac.

## Correr el receptor

```bash
cd apps/desktop
npm install
npm run tauri dev
```

Al abrir la ventana verás el QR + el PIN. Espera al teléfono.

## Correr el agente Android

Desde Android Studio, abre `apps/android` y ejecuta el módulo `app`.
También puedes compilar por CLI:

```bash
cd apps/android
export ANDROID_HOME=~/Library/Android/sdk
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:assembleDebug
# APK en app/build/outputs/apk/debug/app-debug.apk
```

En el teléfono:
- **Escanear código QR** — apunta a la pantalla del Mac.
- **Servidores en tu red** — toca uno de la lista.
- **Conexión manual** — IP + puerto + PIN.

## Protocolo

Ver comentarios en `apps/desktop/src-tauri/src/lib.rs`.

## Limitaciones actuales

- Sin input inverso (solo visualización).
- Sin audio.
- Sin cifrado del stream (LAN de confianza).
- Sin reconexión automática.
- iOS aún no soportado.

Roadmap: ver propuesta de fases.
