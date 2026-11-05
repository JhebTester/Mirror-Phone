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
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * Foreground service — Mirror Phone Agent (MPH4: UDP Zero-Buffer + TCP Control/PLI):
 *  - Handshake y canal de control (PLI / CSD / BYE) por TCP con TCP_NODELAY.
 *  - Video H.264 por UDP directo sin colas intermedias (latencia mínima).
 *  - Recuperación instantánea ante pérdidas Wi-Fi mediante PLI (Picture Loss Indication)
 *    que solicita un Keyframe (IDR) al encoder hardware en <16 ms.
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

        private const val TARGET_FPS = 60
        private const val BITRATE = 5_000_000 // 5 Mbps VBR
        private const val IFRAME_INTERVAL_SEC = 1
        private const val MAX_UDP_PAYLOAD = 1360
        private const val UDP_HEADER_SIZE = 20

        private const val CMD_PLI = 0x01

        @Volatile var isStreaming: Boolean = false
            private set

        @Volatile private var activeInstance: CaptureService? = null
        @Volatile var stateListener: ((Boolean) -> Unit)? = null

        fun stopNow(ctx: Context) {
            isStreaming = false
            stateListener?.invoke(false)
            activeInstance?.fullStopAndExit()
            try {
                ctx.stopService(Intent(ctx, CaptureService::class.java))
            } catch (_: Exception) {}
        }
    }

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var encoder: MediaCodec? = null
    private var inputSurface: Surface? = null

    @Volatile private var tcpSocket: Socket? = null
    @Volatile private var tcpOut: DataOutputStream? = null
    @Volatile private var udpSocket: DatagramSocket? = null
    @Volatile private var running = false
    private val stopping = AtomicBoolean(false)
    private val lastPliMs = AtomicLong(0L)

    private var sessionThread: Thread? = null
    private var drainThread: Thread? = null

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.i(TAG, "MediaProjection stopped by system")
            fullStopAndExit()
        }
    }

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
                if (data == null || code != Activity.RESULT_OK) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                stopping.set(false)
                activeInstance = this
                isStreaming = true
                runOnMainSafe { stateListener?.invoke(true) }
                startForegroundWithType("Conectando a $host:$port…")
                startMirror(code, data, host, port, pin)
            }
            ACTION_STOP -> {
                Log.i(TAG, "ACTION_STOP recibido")
                fullStopAndExit()
            }
        }
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.i(TAG, "onTaskRemoved: app cerrada, deteniendo mirror")
        fullStopAndExit()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        stopMirror()
        super.onDestroy()
    }

    // ----------------------------------------------------------- notificación

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
        val flagImmutable = if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
        val stopIntent = Intent(this, CaptureService::class.java).apply { action = ACTION_STOP }
        val stopPi = PendingIntent.getService(
            this, REQ_STOP, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or flagImmutable
        )
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

    // ----------------------------------------------------------------- start

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
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            val b = wm.currentWindowMetrics.bounds
            Triple(b.width(), b.height(), resources.displayMetrics.densityDpi)
        } else {
            val m = DisplayMetrics()
            @Suppress("DEPRECATION")
            (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(m)
            Triple(m.widthPixels, m.heightPixels, m.densityDpi)
        }

        val maxSide = 1280
        val scale = minOf(1f, maxSide.toFloat() / maxOf(srcW, srcH).toFloat())
        val w = (((srcW * scale).toInt() + 15) / 16) * 16
        val h = (((srcH * scale).toInt() + 15) / 16) * 16

        running = true

        sessionThread = thread(name = "mph-session", isDaemon = true) {
            try {
                // 1) Conectar canal de control TCP y validar PIN
                val s = Socket()
                s.connect(InetSocketAddress(host, port), 5000)
                s.tcpNoDelay = true
                s.setSoLinger(true, 1)
                tcpSocket = s

                val dos = DataOutputStream(s.getOutputStream().buffered(8 * 1024))
                val ins = s.getInputStream()
                tcpOut = dos

                val cleanPin = pin.uppercase().padEnd(6, ' ').substring(0, 6)
                dos.write("MPH4".toByteArray(Charsets.US_ASCII))
                dos.write(cleanPin.toByteArray(Charsets.US_ASCII))
                dos.writeShort(w)
                dos.writeShort(h)
                dos.writeShort(TARGET_FPS)
                dos.flush()

                val ack = ins.read()
                if (ack != 0) {
                    Log.e(TAG, "PIN rechazado por el servidor (ack=$ack)")
                    runOnMainSafe { updateNotification("PIN inválido — detenido") }
                    fullStopAndExit()
                    return@thread
                }

                // 2) Preparar socket UDP de baja latencia
                val targetAddr = InetAddress.getByName(host)
                val udp = DatagramSocket().apply {
                    try { sendBufferSize = 512 * 1024 } catch (_: Exception) {}
                    // IPTOS_LOWDELAY (0x10) | DSCP CS4 (0x80) para priorizar en Wi-Fi WMM
                    try { trafficClass = 0x90 } catch (_: Exception) {}
                }
                udpSocket = udp

                // 3) Iniciar MediaCodec H.264 directo desde VirtualDisplay
                startEncoderAndDrain(w, h, dpi, targetAddr, port)
                runOnMainSafe { updateNotification("Espejando a $host:$port (UDP Zero-Buffer)") }

                // 4) Bucle lector de control TCP: recibe PLI (0x01) desde Desktop
                while (running) {
                    val cmd = ins.read()
                    if (cmd < 0) break // EOF: desktop cerró
                    if (cmd == CMD_PLI) {
                        requestSyncFrameThrottled()
                    }
                }
                if (running) fullStopAndExit()
            } catch (e: Exception) {
                if (running) {
                    Log.e(TAG, "Session error: ${e.message}", e)
                    runOnMainSafe { updateNotification("Conexión perdida — detenido") }
                    fullStopAndExit()
                }
            }
        }
    }

    private fun startEncoderAndDrain(
        w: Int,
        h: Int,
        dpi: Int,
        targetAddr: InetAddress,
        port: Int
    ) {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
            )
            setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
            setInteger(MediaFormat.KEY_FRAME_RATE, TARGET_FPS)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, IFRAME_INTERVAL_SEC)
            setInteger(
                MediaFormat.KEY_BITRATE_MODE,
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
            )
            // Repite el cuadro anterior tras 100 ms de pantalla estática para que el
            // decoder nunca retenga el último frame al cambiar de app o dejar de tocar.
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000L)
            if (Build.VERSION.SDK_INT >= 21) {
                setInteger(
                    MediaFormat.KEY_PROFILE,
                    MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline
                )
                setInteger(
                    MediaFormat.KEY_LEVEL,
                    MediaCodecInfo.CodecProfileLevel.AVCLevel41
                )
            }
            if (Build.VERSION.SDK_INT >= 29) {
                setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
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

        drainThread = thread(name = "mph-drain-udp", isDaemon = true) {
            val info = MediaCodec.BufferInfo()
            val pktBuf = ByteArray(UDP_HEADER_SIZE + MAX_UDP_PAYLOAD)
            var csd: ByteArray? = null
            var frameSeq = 1

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
                } catch (_: Exception) {
                    continue
                }
                buf.position(info.offset)
                buf.limit(info.offset + info.size)
                val bytes = ByteArray(info.size).also { buf.get(it) }
                try {
                    encoder?.releaseOutputBuffer(idx, false)
                } catch (_: Exception) {}

                val isCfg = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                val isKey = (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0

                if (isCfg) {
                    csd = bytes
                    // Enviamos CSD tanto por TCP (100% fiable) como por UDP (inmediato)
                    sendTcpPacket(0, info.presentationTimeUs, bytes)
                    sendUdpFrame(0, 0, info.presentationTimeUs, bytes, pktBuf, targetAddr, port)
                } else {
                    val seq = frameSeq++
                    if (isKey && csd != null) {
                        sendUdpFrame(0, 0, info.presentationTimeUs, csd!!, pktBuf, targetAddr, port)
                    }
                    val kind: Byte = if (isKey) 1 else 2
                    sendUdpFrame(kind, seq, info.presentationTimeUs, bytes, pktBuf, targetAddr, port)
                }

                if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
            }
            Log.i(TAG, "Drain UDP loop terminó")
        }
    }

    private fun sendUdpFrame(
        kind: Byte,
        frameId: Int,
        ptsUs: Long,
        data: ByteArray,
        pktBuf: ByteArray,
        addr: InetAddress,
        port: Int
    ) {
        val udp = udpSocket ?: return
        val total = data.size
        if (total == 0) return
        val fragCount = (total + MAX_UDP_PAYLOAD - 1) / MAX_UDP_PAYLOAD

        var offset = 0
        var fragIdx = 0
        while (offset < total && running) {
            val chunkLen = minOf(MAX_UDP_PAYLOAD, total - offset)

            // Header 20 bytes
            pktBuf[0] = 'M'.code.toByte()
            pktBuf[1] = 'P'.code.toByte()
            pktBuf[2] = kind
            pktBuf[3] = 0
            // frame_id (u32 BE)
            pktBuf[4] = (frameId ushr 24).toByte()
            pktBuf[5] = (frameId ushr 16).toByte()
            pktBuf[6] = (frameId ushr 8).toByte()
            pktBuf[7] = frameId.toByte()
            // frag_idx (u16 BE)
            pktBuf[8] = (fragIdx ushr 8).toByte()
            pktBuf[9] = fragIdx.toByte()
            // frag_count (u16 BE)
            pktBuf[10] = (fragCount ushr 8).toByte()
            pktBuf[11] = fragCount.toByte()
            // pts_us (u64 BE)
            pktBuf[12] = (ptsUs ushr 56).toByte()
            pktBuf[13] = (ptsUs ushr 48).toByte()
            pktBuf[14] = (ptsUs ushr 40).toByte()
            pktBuf[15] = (ptsUs ushr 32).toByte()
            pktBuf[16] = (ptsUs ushr 24).toByte()
            pktBuf[17] = (ptsUs ushr 16).toByte()
            pktBuf[18] = (ptsUs ushr 8).toByte()
            pktBuf[19] = ptsUs.toByte()

            System.arraycopy(data, offset, pktBuf, UDP_HEADER_SIZE, chunkLen)

            try {
                val dp = DatagramPacket(pktBuf, UDP_HEADER_SIZE + chunkLen, addr, port)
                udp.send(dp)
            } catch (_: Exception) {
                return
            }

            offset += chunkLen
            fragIdx++
        }
    }

    private fun sendTcpPacket(kind: Byte, ptsUs: Long, data: ByteArray) {
        val dos = tcpOut ?: return
        try {
            synchronized(dos) {
                dos.writeByte(kind.toInt())
                dos.writeLong(ptsUs)
                dos.writeInt(data.size)
                dos.write(data)
                dos.flush()
            }
        } catch (_: Exception) {}
    }

    private fun requestSyncFrameThrottled() {
        val now = SystemClock.elapsedRealtime()
        val prev = lastPliMs.get()
        if (now - prev < 40) return
        if (!lastPliMs.compareAndSet(prev, now)) return
        try {
            val params = Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            }
            encoder?.setParameters(params)
        } catch (_: Exception) {}
    }

    // ------------------------------------------------------------------ stop

    fun fullStopAndExit() {
        if (!stopping.compareAndSet(false, true)) return
        isStreaming = false
        if (activeInstance === this) activeInstance = null
        runOnMainSafe { stateListener?.invoke(false) }
        stopMirror()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (_: Exception) {}
        try {
            val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            mgr.cancel(NOTIF_ID)
        } catch (_: Exception) {}
        stopSelf()
    }

    private fun stopMirror() {
        running = false
        val u = udpSocket
        val t = tcpSocket
        udpSocket = null
        tcpSocket = null
        tcpOut = null

        val sThread = sessionThread
        val dThread = drainThread
        sessionThread = null
        drainThread = null

        val enc = encoder
        val vd = virtualDisplay
        val surf = inputSurface
        val proj = projection
        encoder = null
        virtualDisplay = null
        inputSurface = null
        projection = null

        // Liberar sockets y codecs fuera del hilo principal para evitar NetworkOnMainThreadException o bloqueos
        thread(name = "mph-cleanup", isDaemon = true) {
            try { u?.close() } catch (_: Exception) {}
            try { t?.close() } catch (_: Exception) {}
            try { sThread?.interrupt() } catch (_: Exception) {}
            try { dThread?.interrupt() } catch (_: Exception) {}
            try { vd?.release() } catch (_: Exception) {}
            try { enc?.stop() } catch (_: Exception) {}
            try { enc?.release() } catch (_: Exception) {}
            try { surf?.release() } catch (_: Exception) {}
            try { proj?.unregisterCallback(projectionCallback) } catch (_: Exception) {}
            try { proj?.stop() } catch (_: Exception) {}
        }
    }

    private fun runOnMainSafe(block: () -> Unit) {
        try {
            android.os.Handler(mainLooper).post(block)
        } catch (_: Exception) {}
    }
}
