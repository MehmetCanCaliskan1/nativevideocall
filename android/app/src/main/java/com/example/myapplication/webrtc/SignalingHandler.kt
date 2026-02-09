package com.example.myapplication.webrtc

import android.util.Log
import io.socket.client.Socket
import io.socket.emitter.Emitter
import org.json.JSONObject
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription

class SignalingHandler(
    private val socket: Socket,
    private val roomId: String,
    private val onPeerCreated: () -> WebRtcPeer,
    private val getPeer: () -> WebRtcPeer?
) {

    companion object {
        private const val TAG = "SignalingHandler"
    }

    // Karşı tarafın socket id'si
    private var targetSocketId: String? = null

    fun setupListeners() {
        socket.on(Socket.EVENT_CONNECT, onConnect)
        socket.on("webrtc-offer", onOffer)
        socket.on("webrtc-answer", onAnswer)
        socket.on("webrtc-ice", onIceCandidate)
        socket.on("room-joined", onRoomJoined)
        socket.on(Socket.EVENT_DISCONNECT, onDisconnect)
    }

    /*  SOCKET EVENTS  */
    private val onRoomJoined = Emitter.Listener { args ->
        val data = args[0] as JSONObject
        // Sunucudan gelen veriyi ayrıştır
        val status = data.optString("status")
        val isHost = data.optBoolean("isHost")

        Log.d(TAG, "Odaya katılındı: Status=$status, Host=$isHost")

        // Eğer onaylandıysak ve Host biz DEĞİLSEK (yani misafirsek), aramayı biz başlatmalıyız.
        if (status == "approved" && !isHost) {
            Log.d(TAG, "Misafir girişi onaylandı, Offer oluşturuluyor...")

            // Peer (WebRTC motoru) yoksa oluştur, varsa getir
            val peer = getPeer() ?: onPeerCreated()

            // --- SİHİRLİ DOKUNUŞ BURASI ---
            peer.createOffer()
        }
    }
    private val onConnect = Emitter.Listener {
        Log.d(TAG, "Socket connected")

        val obj = JSONObject().apply {
            put("roomId", roomId)
            put("username", "Android User")
        }

        socket.emit("join-room", obj)
    }


    private val onDisconnect = Emitter.Listener {
        Log.d(TAG, "Socket disconnected")
    }

    private val onOffer = Emitter.Listener { args ->
        val data = args[0] as JSONObject

        targetSocketId = data.getString("from")
        val offer = data.getJSONObject("offer")

        val peer = getPeer() ?: onPeerCreated()

        val sdp = SessionDescription(
            SessionDescription.Type.OFFER,
            offer.getString("sdp")
        )

        peer.setRemoteDescription(sdp)
        peer.createAnswer()
    }

    private val onAnswer = Emitter.Listener { args ->
        val data = args[0] as JSONObject

        targetSocketId = data.getString("from")
        val answer = data.getJSONObject("answer")

        val sdp = SessionDescription(
            SessionDescription.Type.ANSWER,
            answer.getString("sdp")
        )

        getPeer()?.setRemoteDescription(sdp)
    }

    private val onIceCandidate = Emitter.Listener { args ->
        val data = args[0] as JSONObject
        val candidateObj = data.getJSONObject("candidate")

        val candidate = IceCandidate(
            candidateObj.getString("sdpMid"),
            candidateObj.getInt("sdpMLineIndex"),
            candidateObj.getString("candidate")
        )

        getPeer()?.addIceCandidate(candidate)
    }

    fun sendOffer(sdp: SessionDescription) {
        val payload = JSONObject()

        // 1. Eğer hedef ID belliyse ekle, değilse boş bırak (Sunucu broadcast yapar)
        if (targetSocketId != null) {
            payload.put("to", targetSocketId)
        }

        // 2. Offer objesini oluştur
        val offerJson = JSONObject().apply {
            put("type", sdp.type.canonicalForm())
            put("sdp", sdp.description)
        }

        // 3. Ana payload'a offer'ı ekle
        payload.put("offer", offerJson)

        // 4. Gönder
        Log.d("Signaling", "Offer gönderiliyor. Hedef: ${targetSocketId ?: "TÜM ODA"}")
        socket.emit("webrtc-offer", payload)
    }

    fun sendAnswer(sdp: SessionDescription) {
        if (targetSocketId == null) return

        val payload = JSONObject().apply {
            put("to", targetSocketId)
            put("answer", JSONObject().apply {
                put("type", sdp.type.canonicalForm())
                put("sdp", sdp.description)
            })
        }

        socket.emit("webrtc-answer", payload)
    }

    fun sendIceCandidate(candidate: IceCandidate) {
        if (targetSocketId == null) return

        val payload = JSONObject().apply {
            put("to", targetSocketId)
            put("candidate", JSONObject().apply {
                put("sdpMid", candidate.sdpMid)
                put("sdpMLineIndex", candidate.sdpMLineIndex)
                put("candidate", candidate.sdp)
            })
        }

        socket.emit("webrtc-ice", payload)
    }

    fun disconnect() {
        socket.off(Socket.EVENT_CONNECT, onConnect)
        socket.off("webrtc-offer", onOffer)
        socket.off("webrtc-answer", onAnswer)
        socket.off("webrtc-ice", onIceCandidate)
        socket.off(Socket.EVENT_DISCONNECT, onDisconnect)
        socket.off("room-joined", onRoomJoined)
        socket.disconnect()
    }
}
