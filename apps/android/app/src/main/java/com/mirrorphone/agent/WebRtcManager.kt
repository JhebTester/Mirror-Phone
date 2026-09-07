package com.mirrorphone.agent

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.util.Log
import org.webrtc.*
import java.util.concurrent.Executors

/**
 * Encapsula todo el pipeline WebRTC del agente Android:
 *  - Inicializa PeerConnectionFactory con codecs H.264 HW.
 *  - Captura pantalla vía ScreenCapturerAndroid (MediaProjection).
 *  - Publica un VideoTrack en un RTCPeerConnection.
 *  - Actúa como offerer: crea la SDP offer, la envía por el signaling y
 *    procesa la answer + los ICE candidates entrantes.
 *
 * El signaling (SDP + ICE) se transporta por la conexión TCP existente en
 * formato JSON, delegado al [SignalingTransport].
 */
class WebRtcManager(
    private val ctx: Context,
    private val screenCaptureIntent: Intent,
    private val width: Int,
    private val height: Int,
    private val fps: Int,
    private val transport: SignalingTransport,
) {

    companion object { const val TAG = "WebRtcManager" }

    interface SignalingTransport {
        fun sendOffer(sdp: String)
        fun sendIce(candidateJson: String)
        fun onConnectionState(state: PeerConnection.PeerConnectionState)
        fun onError(msg: String)
    }

    private val executor = Executors.newSingleThreadExecutor()
    private var eglBase: EglBase? = null
    private var factory: PeerConnectionFactory? = null
    private var peer: PeerConnection? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var capturer: ScreenCapturerAndroid? = null
    private var surfaceHelper: SurfaceTextureHelper? = null

    fun start() {
        executor.execute { startInternal() }
    }

    fun onAnswer(sdp: String) {
        executor.execute {
            val desc = SessionDescription(SessionDescription.Type.ANSWER, sdp)
            peer?.setRemoteDescription(SimpleSdpObserver("setRemote(answer)"), desc)
        }
    }

    fun onRemoteIce(candidateJson: String) {
        executor.execute {
            try {
                val obj = org.json.JSONObject(candidateJson)
                val candidate = IceCandidate(
                    obj.optString("sdpMid", "0"),
                    obj.optInt("sdpMLineIndex", 0),
                    obj.optString("candidate", "")
                )
                peer?.addIceCandidate(candidate)
            } catch (e: Exception) {
                Log.e(TAG, "bad ICE", e)
            }
        }
    }

    fun stop() {
        executor.execute {
            try { capturer?.stopCapture() } catch (_: Exception) {}
            try { capturer?.dispose() } catch (_: Exception) {}
            try { surfaceHelper?.dispose() } catch (_: Exception) {}
            try { videoTrack?.dispose() } catch (_: Exception) {}
            try { videoSource?.dispose() } catch (_: Exception) {}
            try { peer?.close() } catch (_: Exception) {}
            try { factory?.dispose() } catch (_: Exception) {}
            try { eglBase?.release() } catch (_: Exception) {}
            capturer = null; surfaceHelper = null; videoTrack = null
            videoSource = null; peer = null; factory = null; eglBase = null
        }
        executor.shutdown()
    }

    // -------------------------------------------------------------- internal

    private fun startInternal() {
        val opts = PeerConnectionFactory.InitializationOptions
            .builder(ctx)
            .setEnableInternalTracer(false)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(opts)

        eglBase = EglBase.create()
        val eglCtx = eglBase!!.eglBaseContext

        // Encoders/decoders HW cuando estén disponibles (H.264 baseline soportado por
        // WKWebView macOS). Usamos DefaultVideoEncoderFactory con fallback SW.
        val encoderFactory = DefaultVideoEncoderFactory(eglCtx, true, true)
        val decoderFactory = DefaultVideoDecoderFactory(eglCtx)

        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()

        val rtcConfig = PeerConnection.RTCConfiguration(emptyList()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            // Solo LAN: candidatos host directos, sin STUN/TURN
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            keyType = PeerConnection.KeyType.ECDSA
            iceTransportsType = PeerConnection.IceTransportsType.ALL
        }

        val pcObserver = object : PeerConnection.Observer {
            override fun onSignalingChange(s: PeerConnection.SignalingState) {
                Log.i(TAG, "signaling=$s")
            }
            override fun onIceConnectionChange(s: PeerConnection.IceConnectionState) {
                Log.i(TAG, "ice=$s")
            }
            override fun onIceConnectionReceivingChange(b: Boolean) {}
            override fun onIceGatheringChange(s: PeerConnection.IceGatheringState) {
                Log.i(TAG, "iceGathering=$s")
            }
            override fun onIceCandidate(c: IceCandidate) {
                val json = org.json.JSONObject().apply {
                    put("candidate", c.sdp)
                    put("sdpMid", c.sdpMid)
                    put("sdpMLineIndex", c.sdpMLineIndex)
                }
                transport.sendIce(json.toString())
            }
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
            override fun onAddStream(stream: MediaStream) {}
            override fun onRemoveStream(stream: MediaStream) {}
            override fun onDataChannel(dc: DataChannel) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {}
            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
                Log.i(TAG, "connection=$newState")
                transport.onConnectionState(newState)
            }
        }

        peer = factory!!.createPeerConnection(rtcConfig, pcObserver)
            ?: return transport.onError("createPeerConnection returned null")

        // Screen capture → VideoSource → VideoTrack
        capturer = ScreenCapturerAndroid(screenCaptureIntent, object : MediaProjection.Callback() {
            override fun onStop() { Log.i(TAG, "MediaProjection stopped") }
        }).also { cap ->
            surfaceHelper = SurfaceTextureHelper.create("ScreenCaptureThread", eglCtx)
            videoSource = factory!!.createVideoSource(cap.isScreencast)
            cap.initialize(surfaceHelper, ctx, videoSource!!.capturerObserver)
            cap.startCapture(width, height, fps)
        }

        videoTrack = factory!!.createVideoTrack("screen0", videoSource).apply { setEnabled(true) }

        val streamId = "mirror-phone-stream"
        val sender = peer!!.addTrack(videoTrack, listOf(streamId))

        // Tuning fino del sender para LAN: bitrate alto, framerate objetivo,
        // priorizar framerate sobre resolución (queremos suavidad de UI).
        try {
            val params = sender.parameters
            params.degradationPreference =
                RtpParameters.DegradationPreference.MAINTAIN_RESOLUTION
            if (params.encodings.isNotEmpty()) {
                val enc = params.encodings[0]
                enc.maxBitrateBps = 12_000_000    // 12 Mbps techo (LAN sobra)
                enc.minBitrateBps = 4_000_000     // no bajar de 4 Mbps → menos re-scaling
                enc.maxFramerate = fps
                enc.networkPriority = 4           // HIGH
                enc.bitratePriority = 4.0
            }
            sender.parameters = params
            Log.i(TAG, "sender tuned: max=12Mbps min=4Mbps fps=$fps MAINTAIN_RESOLUTION")
        } catch (e: Exception) {
            Log.w(TAG, "sender tune failed: ${e.message}")
        }

        // Crear offer y publicarla
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
        }
        peer!!.createOffer(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription) {
                var sdp = preferH264(desc.description)
                sdp = boostBitrateHints(sdp, startKbps = 8000, minKbps = 4000, maxKbps = 12000)
                val patched = SessionDescription(desc.type, sdp)
                peer!!.setLocalDescription(SimpleSdpObserver("setLocal(offer)"), patched)
                transport.sendOffer(patched.description)
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(err: String) { transport.onError("createOffer: $err") }
            override fun onSetFailure(err: String) { transport.onError("setLocal(offer): $err") }
        }, constraints)
    }

    /** Inyecta x-google-{start,min,max}-bitrate en los fmtp de H264 para arrancar
     *  con bitrate alto (evita el ramp-up lento de GCC en los primeros segundos). */
    private fun boostBitrateHints(sdp: String, startKbps: Int, minKbps: Int, maxKbps: Int): String {
        try {
            val lines = sdp.split("\r\n").toMutableList()
            val h264Pts = mutableSetOf<String>()
            val rtpmap = Regex("^a=rtpmap:(\\d+) (H264|h264)/.*$")
            for (l in lines) rtpmap.matchEntire(l)?.let { h264Pts.add(it.groupValues[1]) }
            if (h264Pts.isEmpty()) return sdp
            val fmtpRe = Regex("^a=fmtp:(\\d+) (.*)$")
            for (i in lines.indices) {
                val m = fmtpRe.matchEntire(lines[i]) ?: continue
                val pt = m.groupValues[1]
                if (pt !in h264Pts) continue
                val extras =
                    "x-google-start-bitrate=$startKbps;x-google-min-bitrate=$minKbps;x-google-max-bitrate=$maxKbps"
                lines[i] = "a=fmtp:$pt ${m.groupValues[2]};$extras"
            }
            return lines.joinToString("\r\n")
        } catch (_: Exception) {
            return sdp
        }
    }

    /** Reordena la lista de payload types de la línea m=video para preferir H.264
     *  (WKWebView macOS lo decodea con VideoToolbox HW). */
    private fun preferH264(sdp: String): String {
        try {
            val lines = sdp.split("\r\n").toMutableList()
            val mIdx = lines.indexOfFirst { it.startsWith("m=video") }
            if (mIdx < 0) return sdp

            // Encuentra los pt (payload types) de H264
            val h264Pts = mutableListOf<String>()
            val rtpmapRegex = Regex("^a=rtpmap:(\\d+) (H264|h264)/.*$")
            for (l in lines) {
                val m = rtpmapRegex.matchEntire(l) ?: continue
                h264Pts.add(m.groupValues[1])
            }
            if (h264Pts.isEmpty()) return sdp

            // Recompón m=video: mantén 3 primeras tokens, luego h264Pts, luego resto
            val tokens = lines[mIdx].split(" ").toMutableList()
            if (tokens.size <= 3) return sdp
            val head = tokens.take(3)
            val payloads = tokens.drop(3).toMutableList()
            val ordered = h264Pts + payloads.filter { it !in h264Pts }
            lines[mIdx] = (head + ordered).joinToString(" ")
            return lines.joinToString("\r\n")
        } catch (_: Exception) {
            return sdp
        }
    }

    private class SimpleSdpObserver(private val tag: String) : SdpObserver {
        override fun onCreateSuccess(desc: SessionDescription) {}
        override fun onSetSuccess() { Log.i(TAG, "$tag OK") }
        override fun onCreateFailure(err: String) { Log.e(TAG, "$tag create: $err") }
        override fun onSetFailure(err: String) { Log.e(TAG, "$tag set: $err") }
    }
}
