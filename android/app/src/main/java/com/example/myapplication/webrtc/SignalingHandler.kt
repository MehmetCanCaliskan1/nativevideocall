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
    var remoteTargetId: String? = null

    fun setupListeners() {
        socket.on(Socket.EVENT_CONNECT, onConnect)
        socket.on("webrtc-offer", onOffer)
        socket.on("webrtc-answer", onAnswer)
        socket.on("webrtc-ice", onIceCandidate)
        socket.on("room-joined", onRoomJoined)
        socket.on(Socket.EVENT_DISCONNECT, onDisconnect)
    }

    private val onConnect = Emitter.Listener {
        Log.d(TAG, "Socket connected")
        val obj = JSONObject().apply {
            put("roomId", roomId)
            put("username", "Android User")
        }
        socket.emit("join-room", obj)
    }

    private val onRoomJoined = Emitter.Listener { args ->
        val data = args[0] as JSONObject
        val status = data.optString("status")
        val isHost = data.optBoolean("isHost")
        val users = data.optJSONArray("users")

        Log.d(TAG, "Odaya katılındı: Status=$status, Host=$isHost")

        // Eğer biz MİSAFİR isek, Host'un ID'sini bulup kaydetmeliyiz.
        if (status == "approved" && !isHost && users != null) {
            for (i in 0 until users.length()) {
                val user = users.getJSONObject(i)
                if (user.optBoolean("isHost")) {
                    remoteTargetId = user.getString("socketId")
                    Log.d(TAG, "Host ID bulundu ve kaydedildi: $remoteTargetId")
                    break
                }
            }

            // Misafir kabul edildiğinde offer'ı başlatır
            Log.d(TAG, "Misafir girişi onaylandı, Offer oluşturuluyor...")
            val peer = getPeer() ?: onPeerCreated()
            peer.createOffer()
        }
    }

    private val onDisconnect = Emitter.Listener {
        Log.d(TAG, "Socket disconnected")
    }

    // --- OFFER ALMA (Backendden gelen: { from: "...", offer: {...} }) ---
    private val onOffer = Emitter.Listener { args ->
        Log.d(TAG, "Webrtc Offer Alındı")
        try {
            val data = args[0] as JSONObject
            if (data.has("from")) {
                remoteTargetId = data.getString("from")
                Log.d(TAG, "Offer kimden geldi: $remoteTargetId")
            }

            val offerJson = data.getJSONObject("offer")
            val sdpString = offerJson.getString("sdp")

            val peer = getPeer() ?: onPeerCreated()
            val sdp = SessionDescription(SessionDescription.Type.OFFER, sdpString)

            peer.setRemoteDescription(sdp)
            peer.createAnswer()
        } catch (e: Exception) {
            Log.e(TAG, "Offer işlenirken hata", e)
        }
    }

    // ANSWER ALMA
    private val onAnswer = Emitter.Listener { args ->
        Log.d(TAG, "Webrtc Answer Alındı")
        try {
            val data = args[0] as JSONObject
            val answerJson = data.getJSONObject("answer")
            val sdpString = answerJson.getString("sdp")

            val sdp = SessionDescription(SessionDescription.Type.ANSWER, sdpString)
            getPeer()?.setRemoteDescription(sdp)
        } catch (e: Exception) {
            Log.e(TAG, "Answer işlenirken hata", e)
        }
    }

    // ICE CANDIDATE
    private val onIceCandidate = Emitter.Listener { args ->
        try {
            val data = args[0] as JSONObject
            val candidateJson = data.getJSONObject("candidate")

            val candidate = IceCandidate(
                candidateJson.getString("sdpMid"),
                candidateJson.getInt("sdpMLineIndex"),
                candidateJson.getString("candidate")
            )

            getPeer()?.addIceCandidate(candidate)
        } catch (e: Exception) {
            Log.e(TAG, "ICE Candidate işlenirken hata", e)
        }
    }


    fun sendOffer(sdp: SessionDescription) {
        if (remoteTargetId == null) {
            Log.e(TAG, "HATA: Hedef ID (remoteTargetId) null! Offer gönderilemiyor.")
            return
        }
        val payload = JSONObject()
        val offerJson = JSONObject()

        offerJson.put("type", "offer")
        offerJson.put("sdp", sdp.description)

        payload.put("to", remoteTargetId)
        payload.put("offer", offerJson)

        Log.d(TAG, "Offer GÖNDERİLİYOR -> Hedef: $remoteTargetId")
        socket.emit("webrtc-offer", payload)
    }

    fun sendAnswer(sdp: SessionDescription) {
        if (remoteTargetId == null) return

        // Backend yapısı: { to: "targetId", answer: { type: "answer", sdp: "..." } }
        val payload = JSONObject()
        val answerJson = JSONObject()

        answerJson.put("type", "answer")
        answerJson.put("sdp", sdp.description)

        payload.put("to", remoteTargetId)
        payload.put("answer", answerJson)

        Log.d(TAG, "Answer GÖNDERİLİYOR -> Hedef: $remoteTargetId")
        socket.emit("webrtc-answer", payload)
    }

    fun sendIceCandidate(candidate: IceCandidate) {
        if (remoteTargetId == null) return
        val payload = JSONObject()
        val candidateJson = JSONObject()

        candidateJson.put("sdpMid", candidate.sdpMid)
        candidateJson.put("sdpMLineIndex", candidate.sdpMLineIndex)
        candidateJson.put("candidate", candidate.sdp)

        payload.put("to", remoteTargetId)
        payload.put("candidate", candidateJson)

        socket.emit("webrtc-ice", payload)
    }

    fun disconnect() {
        socket.disconnect()
    }
}