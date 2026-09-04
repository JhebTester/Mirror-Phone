package com.mirrorphone.agent

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import java.io.DataOutputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Foreground service:
 *  - Captura de pantalla vía MediaProjection.
 *  - Encoding H.264 con MediaCodec (hardware).
 *  - Envío de paquetes al desktop por TCP.
 *
 * Protocolo (ver src-tauri/src/lib.rs):
 *   Handshake v1: b"MPH1" + width(u16 BE) + height(u16 BE) + fps(u16 BE)
 *   Handshake v2: b"MPH2" + pin(6 ASCII) + width(u16) + height(u16) + fps(u16)
 *                 servidor responde 1 byte: 0x00 OK, 0x01 PIN inválido
 *   Paquete:   kind(u8) + pts_us(u64 BE) + len(u32 BE) + payload (Annex-B)
 *     kind = 0 -> CSD (SPS+PPS), 1 -> keyframe, 2 -> delta
 */
class CaptureService : Service() {

    companion object {
        const val TAG = "CaptureService"
        const val ACTION_START = "start"
        const val ACTION_STOP = "stop"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val EXTRA_PIN = "pin"

        private const val CHANNEL_ID = "mirror_phone_capture"
        private const val NOTIF_ID = 101
        private const val REQ_STOP = 1
        private const val REQ_OPEN = 2
        private const val TARGET_FPS = 30
        private const val BITRATE = 4_000_000 // 4 Mbps
        private const val IFRAME_INTERVAL_SEC = 2
    }

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var encoder: MediaCodec? = null
    private var inputSurface: Surface? = null

    @Volatile private var socket: Socket? = null
    @Volatile private var out: DataOutputStream? = null
    private val sendQueue = LinkedBlockingQueue<Packet>()
    @Volatile private var running = false
    private val stopping = AtomicBoolean(false)
    private var senderThread: Thread? = null
    private var drainThread: Thread? = null

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.i(TAG, "MediaProjection stopped by system")
            fullStopAndExit()
        }
    }

    private data class Packet(val kind: Byte, val ptsUs: Long, val data: ByteArray)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val code = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val data = if (Build.VERSION.SDK_INT >= 33)
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_RESULT_DATA)
                val host = intent.getStringExtra(EXTRA_HOST) ?: "127.0.0.1"
                val port = intent.getIntExtra(EXTRA_PORT, 7878)
                val pin = intent.getStringExtra(EXTRA_PIN) ?: ""
                if (data == null) { stopSelf(); return START_NOT_STICKY }

                startForegroundWithType("Iniciando conexión a $host:$port…")
                startMirror(code, data, host, port, pin)
            }
            ACTION_STOP -> {
                Log.i(TAG, "ACTION_STOP recibido")
                fullStopAndExit()
            }
        }
        return START_NOT_STICKY
    }

    /** Si el usuario cierra la app deslizándola de recientes, detenemos el servicio.
     *  (El sistema llama a onTaskRemoved cuando se remueve la tarea principal.) */
    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.i(TAG, "onTaskRemoved: app cerrada, deteniendo mirror")
        fullStopAndExit()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        // Doble seguro por si el sistema mata el servicio sin pasar por ACTION_STOP
        stopMirror()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- notifs

    private fun startForegroundWithType(text: String) {
        val n = buildNotification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun updateNotification(text: String) {
        try {
            val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            mgr.notify(NOTIF_ID, buildNotification(text))
        } catch (_: Exception) {}
    }

    private fun buildNotification(text: String): Notification {
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26 && mgr.getNotificationChannel(CHANNEL_ID) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Mirror Phone", NotificationManager.IMPORTANCE_LOW).apply {
                    setShowBadge(false)
                }
            )
        }

        val flagImmutable =
            if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0

        // Botón "Detener": envía ACTION_STOP al servicio
        val stopIntent = Intent(this, CaptureService::class.java).apply { action = ACTION_STOP }
        val stopPi = PendingIntent.getService(
            this, REQ_STOP, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or flagImmutable
        )

        // Tap sobre la notificación: abre MainActivity
        val openPi = PendingIntent.getActivity(
            this, REQ_OPEN,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
            },
            PendingIntent.FLAG_UPDATE_CURRENT or flagImmutable
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Mirror Phone activo")
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setColor(0xFF14B8A6.toInt())
            .setColorized(true)
            .setOngoing(true)
            .setContentIntent(openPi)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Detener",
                stopPi
            )
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    // ---------------------------------------------------------------- start

    private fun startMirror(code: Int, data: Intent, host: String, port: Int, pin: String) {
        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = mgr.getMediaProjection(code, data)
        if (projection == null) {
            Log.e(TAG, "Failed to obtain MediaProjection")
            fullStopAndExit()
            return
        }
        projection!!.registerCallback(projectionCallback, null)

        val (srcW, srcH, dpi) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            val bounds = windowManager.currentWindowMetrics.bounds
            val density = resources.displayMetrics.densityDpi
            Triple(bounds.width(), bounds.height(), density)
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
            Triple(metrics.widthPixels, metrics.heightPixels, metrics.densityDpi)
        }

        val maxSide = 1280
        val scale = minOf(1f, maxSide.toFloat() / maxOf(srcW, srcH).toFloat())
        val w = (((srcW * scale).toInt() + 15) / 16) * 16
        val h = (((srcH * scale).toInt() + 15) / 16) * 16

        Log.i(TAG, "capture ${srcW}x${srcH} -> ${w}x${h} @ ${TARGET_FPS}fps -> $host:$port")

        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
            setInteger(MediaFormat.KEY_FRAME_RATE, TARGET_FPS)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, IFRAME_INTERVAL_SEC)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
            // Baseline profile Level 4.1 para compatibilidad universal con WebCodecs
            if (Build.VERSION.SDK_INT >= 21) {
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
                setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel41)
            }
        }

        encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = createInputSurface()
            start()
        }

        virtualDisplay = projection!!.createVirtualDisplay(
            "MirrorPhone",
            w, h, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            inputSurface, null, null
        )

        running = true

        // Sender: conexión TCP + handshake + envío de paquetes (v0.1.0 — sin drops)
        senderThread = thread(name = "sender", isDaemon = true) {
            try {
                Log.i(TAG, "Connecting to $host:$port...")
                val s = Socket()
                s.connect(InetSocketAddress(host, port), 5000)
                s.tcpNoDelay = true
                // Al cerrar, no esperar más de 1s por buffers pendientes
                s.setSoLinger(true, 1)
                socket = s
                val os: OutputStream = s.getOutputStream()
                val ins = s.getInputStream()
                val dos = DataOutputStream(os.buffered(64 * 1024))
                out = dos

                if (pin.length == 6) {
                    dos.write(byteArrayOf('M'.code.toByte(), 'P'.code.toByte(), 'H'.code.toByte(), '2'.code.toByte()))
                    val pinBytes = pin.uppercase().padEnd(6, ' ')
                        .substring(0, 6).toByteArray(Charsets.US_ASCII)
                    dos.write(pinBytes)
                    dos.writeShort(w)
                    dos.writeShort(h)
                    dos.writeShort(TARGET_FPS)
                    dos.flush()
                    val ack = ins.read()
                    if (ack != 0) {
                        Log.e(TAG, "Servidor rechazó el PIN (ack=$ack)")
                        runOnMainSafe { updateNotification("PIN inválido. Detenido.") }
                        fullStopAndExit()
                        return@thread
                    }
                    Log.i(TAG, "Handshake v2 OK a $host:$port ($w x $h) con PIN")
                } else {
                    dos.write(byteArrayOf('M'.code.toByte(), 'P'.code.toByte(), 'H'.code.toByte(), '1'.code.toByte()))
                    dos.writeShort(w)
                    dos.writeShort(h)
                    dos.writeShort(TARGET_FPS)
                    dos.flush()
                    Log.i(TAG, "Handshake v1 (legacy) a $host:$port ($w x $h)")
                }

                updateNotification("Espejando a $host:$port")

                // Bucle de envío con poll para poder salir rápido cuando running=false
                while (running) {
                    val pkt = sendQueue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                    dos.writeByte(pkt.kind.toInt())
                    dos.writeLong(pkt.ptsUs)
                    dos.writeInt(pkt.data.size)
                    dos.write(pkt.data)
                    dos.flush()
                }
                Log.i(TAG, "Sender loop terminó (running=false)")
            } catch (e: InterruptedException) {
                Log.i(TAG, "Sender interrumpido")
            } catch (e: Exception) {
                if (running) {
                    Log.e(TAG, "Sender connection error to $host:$port: ${e.message}", e)
                    runOnMainSafe { updateNotification("Conexión perdida. Detenido.") }
                    fullStopAndExit()
                } else {
                    Log.i(TAG, "Sender terminó durante shutdown: ${e.message}")
                }
            }
        }

        // Drain: leer buffers del encoder y encolar (v0.1.0 — cola ilimitada, sin drops)
        drainThread = thread(name = "drain", isDaemon = true) {
            val info = MediaCodec.BufferInfo()
            var csd: ByteArray? = null
            while (running) {
                val idx = try {
                    encoder?.dequeueOutputBuffer(info, 10_000) ?: break
                } catch (e: Exception) {
                    if (running) Log.e(TAG, "dequeue error", e)
                    break
                }
                if (idx < 0) continue
                val buf: ByteBuffer = try {
                    encoder?.getOutputBuffer(idx) ?: continue
                } catch (_: Exception) { continue }
                buf.position(info.offset)
                buf.limit(info.offset + info.size)
                val bytes = ByteArray(info.size).also { buf.get(it) }
                try { encoder?.releaseOutputBuffer(idx, false) } catch (_: Exception) {}

                val isCfg = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                val isKey = (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                if (isCfg) {
                    csd = bytes
                    sendQueue.offer(Packet(0, info.presentationTimeUs, bytes))
                } else {
                    if (isKey && csd != null) {
                        sendQueue.offer(Packet(0, info.presentationTimeUs, csd!!))
                    }
                    sendQueue.offer(Packet(if (isKey) 1 else 2, info.presentationTimeUs, bytes))
                }
                if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
            }
            Log.i(TAG, "Drain loop terminó")
        }
    }

    /** Pide al encoder un sync frame en el próximo cuadro. Útil tras drops
     *  para recuperar rápido sin esperar al I-frame periódico. */
    private fun requestSyncFrame() {
        try {
            val params = android.os.Bundle()
            params.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            encoder?.setParameters(params)
        } catch (_: Exception) {}
    }

    // ---------------------------------------------------------------- stop

    /** Detiene TODO y sale del foreground. Reentrante-seguro. */
    private fun fullStopAndExit() {
        if (!stopping.compareAndSet(false, true)) return
        stopMirror()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (_: Exception) {}
        stopSelf()
    }

    private fun stopMirror() {
        running = false
        // 1. Cierra socket primero — desbloquea cualquier write bloqueante
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        out = null

        // 2. Poison + interrupt para desbloquear take()/poll()
        try { sendQueue.clear() } catch (_: Exception) {}
        try { senderThread?.interrupt() } catch (_: Exception) {}
        try { drainThread?.interrupt() } catch (_: Exception) {}

        // 3. Encoder y virtual display
        try { encoder?.stop() } catch (_: Exception) {}
        try { encoder?.release() } catch (_: Exception) {}
        encoder = null
        try { virtualDisplay?.release() } catch (_: Exception) {}
        virtualDisplay = null
        try { inputSurface?.release() } catch (_: Exception) {}
        inputSurface = null

        // 4. Proyección (después del display para que no dispare callback re-entrante)
        try { projection?.unregisterCallback(projectionCallback) } catch (_: Exception) {}
        try { projection?.stop() } catch (_: Exception) {}
        projection = null
    }

    private fun runOnMainSafe(block: () -> Unit) {
        try { android.os.Handler(mainLooper).post(block) } catch (_: Exception) {}
    }
}
