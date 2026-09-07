package com.mirrorphone.agent

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import org.webrtc.PeerConnection

/**
 * Foreground service — Mirror Phone Agent (WebRTC edition):
 *  1. Conecta TCP al desktop y hace handshake MPH3 (con PIN).
 *  2. Después del ACK, el TCP se convierte en canal de signaling (SDP + ICE).
 *  3. Delega WebRTC a [WebRtcManager]; el video fluye P2P por UDP.
 *
 * Signaling frame:
 *   1 byte  kind: 0x10 offer | 0x11 answer | 0x12 ice | 0x13 bye
 *   4 bytes BE u32 len
 *   len bytes UTF-8 JSON
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

        private const val KIND_OFFER: Byte = 0x10
        private const val KIND_ANSWER: Byte = 0x11
        private const val KIND_ICE: Byte = 0x12
        private const val KIND_BYE: Byte = 0x13

        private const val TARGET_FPS = 60
    }

    private var socket: Socket? = null
    private var dis: DataInputStream? = null
    private var dos: DataOutputStream? = null
    private var readerThread: Thread? = null
    private var webrtc: WebRtcManager? = null
    private val stopping = AtomicBoolean(false)
    @Volatile private var running = false

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
                if (data == null || code != Activity.RESULT_OK) { stopSelf(); return START_NOT_STICKY }
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
        val (srcW, srcH) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            val b = wm.currentWindowMetrics.bounds
            b.width() to b.height()
        } else {
            val m = DisplayMetrics()
            @Suppress("DEPRECATION")
            (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(m)
            m.widthPixels to m.heightPixels
        }
        val maxSide = 1280
        val scale = minOf(1f, maxSide.toFloat() / maxOf(srcW, srcH).toFloat())
        val w = (((srcW * scale).toInt() + 15) / 16) * 16
        val h = (((srcH * scale).toInt() + 15) / 16) * 16
        Log.i(TAG, "screen ${srcW}x${srcH} -> WebRTC ${w}x${h} @ ${TARGET_FPS}fps -> $host:$port")

        running = true

        // Reader/handshake thread: conecta TCP, autentica, arranca WebRTC, lee signaling
        readerThread = thread(name = "mph-signaling", isDaemon = true) {
            try {
                val s = Socket()
                s.connect(InetSocketAddress(host, port), 5000)
                s.tcpNoDelay = true
                s.setSoLinger(true, 1)
                socket = s
                dos = DataOutputStream(s.getOutputStream().buffered(16 * 1024))
                dis = DataInputStream(BufferedInputStream(s.getInputStream(), 16 * 1024))

                // Handshake MPH3 (mismo layout que MPH2 pero cambia magic)
                if (pin.length != 6) {
                    Log.e(TAG, "PIN requerido para MPH3 (recibido: '$pin')")
                    updateNotification("PIN inválido")
                    fullStopAndExit(); return@thread
                }
                dos!!.write("MPH3".toByteArray(Charsets.US_ASCII))
                val pinBytes = pin.uppercase().padEnd(6, ' ').substring(0, 6)
                    .toByteArray(Charsets.US_ASCII)
                dos!!.write(pinBytes)
                dos!!.writeShort(w)
                dos!!.writeShort(h)
                dos!!.writeShort(TARGET_FPS)
                dos!!.flush()
                val ack = dis!!.read()
                if (ack != 0) {
                    Log.e(TAG, "Servidor rechazó el PIN (ack=$ack)")
                    updateNotification("PIN inválido — detenido")
                    fullStopAndExit(); return@thread
                }
                Log.i(TAG, "Handshake MPH3 OK, iniciando WebRTC…")
                updateNotification("Negociando WebRTC…")

                // Iniciar WebRTC (offerer)
                val transport = object : WebRtcManager.SignalingTransport {
                    override fun sendOffer(sdp: String) {
                        val json = org.json.JSONObject().put("sdp", sdp).toString()
                        writeFrame(KIND_OFFER, json.toByteArray(Charsets.UTF_8))
                    }
                    override fun sendIce(candidateJson: String) {
                        writeFrame(KIND_ICE, candidateJson.toByteArray(Charsets.UTF_8))
                    }
                    override fun onConnectionState(state: PeerConnection.PeerConnectionState) {
                        when (state) {
                            PeerConnection.PeerConnectionState.CONNECTED ->
                                updateNotification("Espejando (WebRTC conectado)")
                            PeerConnection.PeerConnectionState.FAILED,
                            PeerConnection.PeerConnectionState.DISCONNECTED -> {
                                updateNotification("Conexión perdida — detenido")
                                fullStopAndExit()
                            }
                            else -> {}
                        }
                    }
                    override fun onError(msg: String) {
                        Log.e(TAG, "WebRTC error: $msg")
                        updateNotification("Error WebRTC: $msg")
                    }
                }
                webrtc = WebRtcManager(
                    applicationContext,
                    data,
                    w, h, TARGET_FPS,
                    transport
                ).also { it.start() }

                // Loop de lectura de signaling desde el desktop
                while (running) {
                    val kind = dis!!.read()
                    if (kind < 0) break // EOF
                    val len = dis!!.readInt()
                    if (len < 0 || len > 8 * 1024 * 1024) {
                        Log.e(TAG, "bad signaling size $len")
                        break
                    }
                    val payload = ByteArray(len)
                    dis!!.readFully(payload)
                    val text = String(payload, Charsets.UTF_8)
                    when (kind.toByte()) {
                        KIND_ANSWER -> {
                            val sdp = org.json.JSONObject(text).getString("sdp")
                            webrtc?.onAnswer(sdp)
                        }
                        KIND_ICE -> {
                            webrtc?.onRemoteIce(text)
                        }
                        KIND_BYE -> {
                            Log.i(TAG, "BYE recibido")
                            break
                        }
                        else -> Log.w(TAG, "kind desconocido: $kind")
                    }
                }
                Log.i(TAG, "reader loop terminó")
                if (running) fullStopAndExit()
            } catch (e: Exception) {
                if (running) {
                    Log.e(TAG, "reader error: ${e.message}", e)
                    updateNotification("Conexión perdida — detenido")
                    fullStopAndExit()
                }
            }
        }
    }

    private fun writeFrame(kind: Byte, payload: ByteArray) {
        try {
            val out = dos ?: return
            synchronized(out) {
                out.writeByte(kind.toInt())
                out.writeInt(payload.size)
                out.write(payload)
                out.flush()
            }
        } catch (e: Exception) {
            Log.e(TAG, "writeFrame failed: ${e.message}")
        }
    }

    // ------------------------------------------------------------------ stop

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
        try { webrtc?.stop() } catch (_: Exception) {}
        webrtc = null
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        try { readerThread?.interrupt() } catch (_: Exception) {}
        dis = null
        dos = null
    }
}
