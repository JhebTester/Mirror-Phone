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

        // Ajustes de encoding
        private const val TARGET_FPS = 30
        private const val BITRATE = 4_000_000 // 4 Mbps
        private const val IFRAME_INTERVAL_SEC = 2
    }

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var encoder: MediaCodec? = null
    private var inputSurface: Surface? = null

    private var socket: Socket? = null
    private var out: DataOutputStream? = null
    private val sendQueue = LinkedBlockingQueue<Packet>()
    @Volatile private var running = false
    private var senderThread: Thread? = null
    private var drainThread: Thread? = null

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.i(TAG, "MediaProjection stopped by system")
            stopMirror()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            stopSelf()
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

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(
                        NOTIF_ID,
                        buildNotification("Iniciando conexión a $host:$port…"),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                    )
                } else {
                    startForeground(NOTIF_ID, buildNotification("Iniciando conexión a $host:$port…"))
                }

                startMirror(code, data, host, port, pin)
            }
            ACTION_STOP -> {
                stopMirror()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } else {
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                }
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopMirror()
        super.onDestroy()
    }

    private fun startMirror(code: Int, data: Intent, host: String, port: Int, pin: String) {
        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = mgr.getMediaProjection(code, data)
        if (projection == null) {
            Log.e(TAG, "Failed to obtain MediaProjection")
            stopSelf()
            return
        }

        // Registrar callback requerido a partir de Android 14 (API 34)
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

        // Escalado para mantener ancho de banda razonable y alineación exacta a 16 px (macrobloques H.264)
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
            // Baseline profile Level 4.1 para compatibilidad universal con WebCodecs en resoluciones HD
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

        // Sender: conexión TCP + handshake + envío de paquetes
        senderThread = thread(name = "sender") {
            try {
                Log.i(TAG, "Connecting to $host:$port...")
                val s = Socket()
                s.connect(InetSocketAddress(host, port), 5000)
                s.tcpNoDelay = true
                socket = s
                val os: OutputStream = s.getOutputStream()
                val ins = s.getInputStream()
                out = DataOutputStream(os.buffered(64 * 1024))

                // Handshake — versión 2 (con PIN) si el pin viene de 6 chars, si no legacy v1
                if (pin.length == 6) {
                    out!!.write(byteArrayOf('M'.code.toByte(), 'P'.code.toByte(), 'H'.code.toByte(), '2'.code.toByte()))
                    val pinBytes = pin.uppercase().padEnd(6, ' ')
                        .substring(0, 6).toByteArray(Charsets.US_ASCII)
                    out!!.write(pinBytes)
                    out!!.writeShort(w)
                    out!!.writeShort(h)
                    out!!.writeShort(TARGET_FPS)
                    out!!.flush()
                    val ack = ins.read()
                    if (ack != 0) {
                        Log.e(TAG, "Servidor rechazó el PIN (ack=$ack)")
                        running = false
                        return@thread
                    }
                    Log.i(TAG, "Handshake v2 OK a $host:$port ($w x $h) con PIN")
                } else {
                    out!!.write(byteArrayOf('M'.code.toByte(), 'P'.code.toByte(), 'H'.code.toByte(), '1'.code.toByte()))
                    out!!.writeShort(w)
                    out!!.writeShort(h)
                    out!!.writeShort(TARGET_FPS)
                    out!!.flush()
                    Log.i(TAG, "Handshake v1 (legacy) a $host:$port ($w x $h)")
                }

                while (running) {
                    val pkt = sendQueue.take()
                    out!!.writeByte(pkt.kind.toInt())
                    out!!.writeLong(pkt.ptsUs)
                    out!!.writeInt(pkt.data.size)
                    out!!.write(pkt.data)
                    out!!.flush()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Sender connection error to $host:$port: ${e.message}", e)
                running = false
            }
        }

        // Drain: leer buffers del encoder y encolar
        drainThread = thread(name = "drain") {
            val info = MediaCodec.BufferInfo()
            var csd: ByteArray? = null
            while (running) {
                val idx = try { encoder!!.dequeueOutputBuffer(info, 10_000) } catch (e: Exception) {
                    Log.e(TAG, "dequeue error", e); break
                }
                if (idx < 0) continue
                val buf: ByteBuffer = encoder!!.getOutputBuffer(idx) ?: continue
                buf.position(info.offset)
                buf.limit(info.offset + info.size)
                val bytes = ByteArray(info.size).also { buf.get(it) }
                encoder!!.releaseOutputBuffer(idx, false)

                val isCfg = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                val isKey = (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                if (isCfg) {
                    csd = bytes
                    sendQueue.offer(Packet(0, info.presentationTimeUs, bytes))
                } else {
                    if (isKey && csd != null) {
                        // Reenvía CSD antes del keyframe (seguridad si el cliente reconecta)
                        sendQueue.offer(Packet(0, info.presentationTimeUs, csd!!))
                    }
                    sendQueue.offer(
                        Packet(if (isKey) 1 else 2, info.presentationTimeUs, bytes)
                    )
                }
                if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
            }
        }
    }

    private fun stopMirror() {
        running = false
        try { sendQueue.clear() } catch (_: Exception) {}
        try { drainThread?.interrupt() } catch (_: Exception) {}
        try { senderThread?.interrupt() } catch (_: Exception) {}
        try { encoder?.stop() } catch (_: Exception) {}
        try { encoder?.release() } catch (_: Exception) {}
        encoder = null
        try { virtualDisplay?.release() } catch (_: Exception) {}
        virtualDisplay = null
        try {
            projection?.unregisterCallback(projectionCallback)
            projection?.stop()
        } catch (_: Exception) {}
        projection = null
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        out = null
    }

    private fun buildNotification(text: String): Notification {
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26 && mgr.getNotificationChannel(CHANNEL_ID) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Mirror Phone", NotificationManager.IMPORTANCE_LOW)
            )
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Mirror Phone activo")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()
    }
}
