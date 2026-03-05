package com.example.myapplication.webrtc

import android.util.Log
import com.example.myapplication.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.*

/**
 * Mediasoup SFU modunda çalışan WebRTC peer.
 * İki ayrı PeerConnection kullanır:
 *  - sendPC: Yerel medyayı sunucuya göndermek için
 *  - recvPC: Uzak medyayı sunucudan almak için
 */
class WebRtcPeer(
    private val factory: PeerConnectionFactory,
    private val localStream: MediaStream,
    private val pcConstraints: MediaConstraints,
    private val listener: RtcListener,
    private val signalingHandler: SignalingHandler
) {

    companion object {
        private const val TAG = "WebRtcPeer"
    }

    var sendPC: PeerConnection? = null
        private set
    var recvPC: PeerConnection? = null
        private set

    private var routerRtpCapabilities: JSONObject? = null
    private var sendTransportId: String? = null
    private var recvTransportId: String? = null
    private var hasConnectedSend = false
    private var hasConnectedRecv = false

    private val rtcConfig: PeerConnection.RTCConfiguration by lazy {
        PeerConnection.RTCConfiguration(mutableListOf()).apply {
            iceServers.add(
                PeerConnection.IceServer
                    .builder("stun:stun.l.google.com:19302")
                    .createIceServer()
            )
            if (BuildConfig.TURN_URI.isNotEmpty()) {
                iceServers.add(
                    PeerConnection.IceServer.builder(BuildConfig.TURN_URI)
                        .setUsername(BuildConfig.TURN_USERNAME)
                        .setPassword(BuildConfig.TURN_PASSWORD)
                        .createIceServer()
                )
            }
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            iceCandidatePoolSize = 0
        }
    }

    init {
        setupSignalingCallbacks()
    }

    private fun setupSignalingCallbacks() {
        signalingHandler.onRtpCapabilities = { rtpCapabilities ->
            routerRtpCapabilities = rtpCapabilities
            startMediasoupFlow()
        }

        signalingHandler.onNewProducer = { producerId, producerSocketId, kind ->
            Log.d(TAG, "Yeni producer bildirildi: $kind from $producerSocketId")
            consumeProducer(producerId, kind)
        }

        signalingHandler.onProducerClosed = { producerId ->
            Log.d(TAG, "Producer kapatıldı: $producerId")
            listener.onRemoveRemoteStream()
        }
    }

    /**
     * Mediasoup SFU akışını başlatan ana fonksiyon.
     * Sıra:
     * 1. Send transport oluştur
     * 2. Send transport'u bağla ve produce yap (kendi medyamızı gönder)
     * 3. Recv transport oluştur
     * 4. Mevcut producer'ları al ve consume et (karşı medyayı al)
     */
    private fun startMediasoupFlow() {
        Log.d(TAG, "Mediasoup akışı başlıyor...")

        // 1. Send Transport oluştur
        signalingHandler.requestCreateTransport("send") { sendParams ->
            sendTransportId = sendParams.getString("id")
            val sendIceParams = sendParams.getJSONObject("iceParameters")
            val sendIceCandidates = sendParams.getJSONArray("iceCandidates")
            val sendDtlsParams = sendParams.getJSONObject("dtlsParameters")

            // Send PeerConnection oluştur
            createSendPeerConnection(sendIceParams, sendIceCandidates, sendDtlsParams)

            // 2. Recv Transport oluştur
            signalingHandler.requestCreateTransport("recv") { recvParams ->
                recvTransportId = recvParams.getString("id")

                // 3. Mevcut producer'ları al ve consume et
                signalingHandler.getExistingProducers { producers ->
                    for (i in 0 until producers.length()) {
                        val producer = producers.getJSONObject(i)
                        consumeProducer(
                            producer.getString("producerId"),
                            producer.getString("kind")
                        )
                    }
                }
            }
        }
    }

    // ═══════════════════════════
    //  SEND PeerConnection
    // ═══════════════════════════

    private fun createSendPeerConnection(
        serverIceParams: JSONObject,
        serverIceCandidates: JSONArray,
        serverDtlsParams: JSONObject
    ) {
        sendPC = factory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                // SFU modunda ICE candidate'ler transport üzerinden yönetilir
                // Server tarafı bunu yapıyor, burada bir şey yapmamıza gerek yok
            }

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                Log.d(TAG, "Send ICE state: $state")
                when (state) {
                    PeerConnection.IceConnectionState.CONNECTED,
                    PeerConnection.IceConnectionState.COMPLETED -> {
                        listener.onStatusChanged("CONNECTED")
                        listener.onPeersConnectionStatusChange(true)
                    }
                    PeerConnection.IceConnectionState.FAILED -> {
                        listener.onStatusChanged("FAILED")
                        listener.onPeersConnectionStatusChange(false)
                    }
                    else -> {}
                }
            }

            override fun onSignalingChange(state: PeerConnection.SignalingState) {}
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}
            override fun onIceCandidatesRemoved(candidates: Array<IceCandidate>) {}
            override fun onAddStream(stream: MediaStream) {}
            override fun onRemoveStream(stream: MediaStream) {}
            override fun onRenegotiationNeeded() {}
            override fun onDataChannel(dataChannel: DataChannel) {}
        }) ?: throw IllegalStateException("Send PeerConnection oluşturulamadı")

        // Yerel medya track'lerini ekle
        localStream.audioTracks.firstOrNull()?.let {
            sendPC!!.addTrack(it, listOf("ARDAMS"))
        }
        localStream.videoTracks.firstOrNull()?.let {
            sendPC!!.addTrack(it, listOf("ARDAMS"))
        }

        // Offer oluştur ve sunucuya gönder
        sendPC!!.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) {
                sendPC!!.setLocalDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        // DTLS parametrelerini sunucuya gönder
                        val dtlsParams = extractDtlsFromSDP(sdp.description)
                        signalingHandler.connectTransport("send", dtlsParams) {
                            hasConnectedSend = true
                            Log.d(TAG, "Send transport bağlandı, produce yapılıyor...")

                            // Her track için produce çağır
                            produceLocalTracks()
                        }

                        // Sunucunun SDP'si ile remote description ayarla
                        val serverSDP = buildServerSDP(
                            serverIceParams,
                            serverIceCandidates,
                            serverDtlsParams,
                            sdp.description
                        )
                        val remoteSdp = SessionDescription(SessionDescription.Type.ANSWER, serverSDP)
                        sendPC!!.setRemoteDescription(object : SdpObserver {
                            override fun onSetSuccess() {
                                Log.d(TAG, "Send remote SDP ayarlandı")
                            }
                            override fun onSetFailure(error: String) {
                                Log.e(TAG, "Send remote SDP ayarlanamadı: $error")
                            }
                            override fun onCreateSuccess(sdp: SessionDescription) {}
                            override fun onCreateFailure(error: String) {}
                        }, remoteSdp)
                    }

                    override fun onSetFailure(error: String) {
                        Log.e(TAG, "Send local SDP ayarlanamadı: $error")
                    }
                    override fun onCreateSuccess(sdp: SessionDescription) {}
                    override fun onCreateFailure(error: String) {}
                }, sdp)
            }

            override fun onCreateFailure(error: String) {
                Log.e(TAG, "Send offer oluşturulamadı: $error")
            }
            override fun onSetSuccess() {}
            override fun onSetFailure(error: String) {}
        }, pcConstraints)
    }

    private fun produceLocalTracks() {
        // Audio produce
        localStream.audioTracks.firstOrNull()?.let { audioTrack ->
            val audioRtpParams = extractRtpParametersFromSender("audio")
            if (audioRtpParams != null) {
                signalingHandler.produce("audio", audioRtpParams) { producerId ->
                    Log.d(TAG, "Audio producer ID: $producerId")
                }
            }
        }

        // Video produce
        localStream.videoTracks.firstOrNull()?.let { videoTrack ->
            val videoRtpParams = extractRtpParametersFromSender("video")
            if (videoRtpParams != null) {
                signalingHandler.produce("video", videoRtpParams) { producerId ->
                    Log.d(TAG, "Video producer ID: $producerId")
                }
            }
        }
    }

    // ═══════════════════════════
    //  CONSUME (Karşı tarafın medyasını al)
    // ═══════════════════════════

    private fun consumeProducer(producerId: String, kind: String) {
        val capabilities = routerRtpCapabilities ?: return

        signalingHandler.consume(producerId, capabilities) { consumerData ->
            val consumerId = consumerData.getString("consumerId")
            val rtpParams = consumerData.getJSONObject("rtpParameters")
            val consumerKind = consumerData.getString("kind")

            Log.d(TAG, "Consumer alındı: $consumerKind / $consumerId")

            // Recv PeerConnection yoksa oluştur
            if (recvPC == null) {
                createRecvPeerConnection()
            }

            // Consumer'ı resume et (sunucu tarafında paused olarak oluşturulur)
            signalingHandler.resumeConsumer(consumerId)

            // Bu noktada remote stream onAddStream callback'i ile gelecek
            // Mediasoup server tarafında consumer oluşturulduğunda
            // stream otomatik olarak PeerConnection'a eklenir
        }
    }

    private fun createRecvPeerConnection() {
        recvPC = factory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {}

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                Log.d(TAG, "Recv ICE state: $state")
            }

            override fun onAddStream(stream: MediaStream) {
                Log.d(TAG, "Remote stream alındı (recv)")
                listener.onAddRemoteStream(stream)
            }

            override fun onRemoveStream(stream: MediaStream) {
                listener.onRemoveRemoteStream()
            }

            override fun onSignalingChange(state: PeerConnection.SignalingState) {}
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}
            override fun onIceCandidatesRemoved(candidates: Array<IceCandidate>) {}
            override fun onRenegotiationNeeded() {}
            override fun onDataChannel(dataChannel: DataChannel) {}
        }) ?: throw IllegalStateException("Recv PeerConnection oluşturulamadı")
    }

    // ═══════════════════════════
    //  YARDIMCI FONKSİYONLAR
    // ═══════════════════════════

    private fun extractDtlsFromSDP(sdp: String): JSONObject {
        val dtls = JSONObject()
        val fingerprint = JSONObject()

        // Fingerprint'i ayıkla
        val fingerprintRegex = Regex("a=fingerprint:(\\S+)\\s+(\\S+)")
        val match = fingerprintRegex.find(sdp)
        if (match != null) {
            fingerprint.put("algorithm", match.groupValues[1])
            fingerprint.put("value", match.groupValues[2])
        }

        dtls.put("fingerprints", JSONArray().put(fingerprint))

        // Role ayıkla
        val setupRegex = Regex("a=setup:(\\S+)")
        val setupMatch = setupRegex.find(sdp)
        val role = when (setupMatch?.groupValues?.get(1)) {
            "active" -> "client"
            "passive" -> "server"
            "actpass" -> "auto"
            else -> "auto"
        }
        dtls.put("role", role)

        return dtls
    }

    private fun extractRtpParametersFromSender(kind: String): JSONObject? {
        val senders = sendPC?.senders ?: return null
        val sender = senders.find { it.track()?.kind() == kind } ?: return null
        val params = sender.parameters ?: return null

        val rtpParams = JSONObject()

        // Codecs
        val codecs = JSONArray()
        params.codecs.forEach { codec ->
            val codecJson = JSONObject().apply {
                put("mimeType", codec.name)
                put("clockRate", codec.clockRate)
                put("payloadType", codec.payloadType)
                if (codec.numChannels != null) {
                    put("channels", codec.numChannels)
                }
                val parameters = JSONObject()
                codec.parameters.forEach { (key, value) ->
                    parameters.put(key, value)
                }
                put("parameters", parameters)
            }
            codecs.put(codecJson)
        }
        rtpParams.put("codecs", codecs)

        // Encodings
        val encodings = JSONArray()
        params.encodings.forEach { encoding ->
            val encJson = JSONObject().apply {
                if (encoding.ssrc != null) {
                    put("ssrc", encoding.ssrc)
                }
            }
            encodings.put(encJson)
        }
        rtpParams.put("encodings", encodings)

        // Header extensions
        val headerExtensions = JSONArray()
        params.headerExtensions.forEach { ext ->
            val extJson = JSONObject().apply {
                put("uri", ext.uri)
                put("id", ext.id)
            }
            headerExtensions.put(extJson)
        }
        rtpParams.put("headerExtensions", headerExtensions)

        return rtpParams
    }

    private fun buildServerSDP(
        iceParams: JSONObject,
        iceCandidates: JSONArray,
        dtlsParams: JSONObject,
        localSdp: String
    ): String {
        // Bu basitleştirilmiş bir SDP oluşturucusudur
        // Mediasoup server tarafından gelen parametrelerle uyumlu bir SDP oluşturur
        val sb = StringBuilder()
        sb.appendLine("v=0")
        sb.appendLine("o=- ${System.currentTimeMillis()} 2 IN IP4 127.0.0.1")
        sb.appendLine("s=-")
        sb.appendLine("t=0 0")

        val ufrag = iceParams.getString("usernameFragment")
        val pwd = iceParams.getString("password")

        // Local SDP'den m= satırlarını kopyala ve sunucu parametreleriyle değiştir
        val lines = localSdp.split("\n")
        var inMedia = false
        for (line in lines) {
            if (line.startsWith("m=")) {
                inMedia = true
                sb.appendLine(line.trim())
                sb.appendLine("c=IN IP4 0.0.0.0")
                sb.appendLine("a=ice-ufrag:$ufrag")
                sb.appendLine("a=ice-pwd:$pwd")

                // DTLS fingerprints
                val fingerprints = dtlsParams.optJSONArray("fingerprints")
                if (fingerprints != null) {
                    for (i in 0 until fingerprints.length()) {
                        val fp = fingerprints.getJSONObject(i)
                        sb.appendLine("a=fingerprint:${fp.getString("algorithm")} ${fp.getString("value")}")
                    }
                }
                sb.appendLine("a=setup:active")

                // ICE candidates
                for (i in 0 until iceCandidates.length()) {
                    val candidate = iceCandidates.getJSONObject(i)
                    sb.appendLine("a=candidate:${candidate.getString("foundation")} ${candidate.getInt("component")} ${candidate.getString("protocol")} ${candidate.getInt("priority")} ${candidate.getString("ip")} ${candidate.getInt("port")} typ ${candidate.getString("type")}")
                }
            } else if (inMedia && (line.startsWith("a=") && !line.startsWith("a=ice-") && !line.startsWith("a=fingerprint") && !line.startsWith("a=setup") && !line.startsWith("a=candidate"))) {
                sb.appendLine(line.trim())
            }
        }

        return sb.toString()
    }

    fun dispose() {
        try { sendPC?.close() } catch (e: Exception) { Log.e(TAG, "Send PC close error", e) }
        try { recvPC?.close() } catch (e: Exception) { Log.e(TAG, "Recv PC close error", e) }
        sendPC?.dispose()
        recvPC?.dispose()
    }
}