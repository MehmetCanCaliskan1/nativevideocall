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
        socket.on(Socket.EVENT_DISCONNECT, onDisconnect)
    }

    /*  SOCKET EVENTS  */

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
        if (targetSocketId == null) return

        val payload = JSONObject().apply {
            put("to", targetSocketId)
            put("offer", JSONObject().apply {
                put("type", sdp.type.canonicalForm())
                put("sdp", sdp.description)
            })
        }

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
        socket.disconnect()
    }
}
