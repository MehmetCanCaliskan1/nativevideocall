package com.example.myapplication.webrtc

import android.util.Log
import io.socket.client.Socket
import io.socket.emitter.Emitter
import org.json.JSONObject
import org.json.JSONArray
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription

class SignalingHandler(
    private val socket: Socket,
    private val roomId: String,
    private val onPeerCreated: () -> WebRtcPeer,
    private val getPeer: () -> WebRtcPeer?,
    private val listener: RtcListener
) {

    companion object {
        private const val TAG = "SignalingHandler"
    }

    var remoteTargetId: String? = null

    // Mediasoup signaling callbacks
    var onRtpCapabilities: ((JSONObject) -> Unit)? = null
    var onTransportCreated: ((String, JSONObject) -> Unit)? = null // direction, params
    var onNewProducer: ((String, String, String) -> Unit)? = null // producerId, socketId, kind
    var onProducerClosed: ((String) -> Unit)? = null // producerId

    fun setupListeners() {
        socket.on(Socket.EVENT_CONNECT, onConnect)
        socket.on("room-joined", onRoomJoined)
        socket.on("new-producer", onNewProducerEvent)
        socket.on("producer-closed", onProducerClosedEvent)
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
        try {
            val data = args[0] as JSONObject
            val isHost = data.optBoolean("isHost", false)
            val status = data.optString("status", "")

            Log.d(TAG, "Odaya katılındı! Status: $status, Host mu: $isHost")
            listener.onRoleDataReceived(isHost)

            if (status == "approved") {
                val users = data.optJSONArray("users")
                if (users != null) {
                    for (i in 0 until users.length()) {
                        val user = users.getJSONObject(i)
                        val socketId = user.getString("socketId")
                        if (socketId != socket.id()) {
                            remoteTargetId = socketId
                            break
                        }
                    }
                }

                // Mediasoup akışını başlat
                listener.onStatusChanged("CONNECTING")
                requestRtpCapabilities()
            }
        } catch (e: Exception) {
            Log.e(TAG, "onRoomJoined işlenirken hata oluştu", e)
        }
    }

    private val onNewProducerEvent = Emitter.Listener { args ->
        try {
            val data = args[0] as JSONObject
            val producerId = data.getString("producerId")
            val producerSocketId = data.getString("producerSocketId")
            val kind = data.getString("kind")

            Log.d(TAG, "Yeni producer bildirimi: $kind from $producerSocketId")
            onNewProducer?.invoke(producerId, producerSocketId, kind)
        } catch (e: Exception) {
            Log.e(TAG, "new-producer işlenirken hata", e)
        }
    }

    private val onProducerClosedEvent = Emitter.Listener { args ->
        try {
            val data = args[0] as JSONObject
            val producerId = data.getString("producerId")
            Log.d(TAG, "Producer kapatıldı: $producerId")
            onProducerClosed?.invoke(producerId)
        } catch (e: Exception) {
            Log.e(TAG, "producer-closed işlenirken hata", e)
        }
    }

    private val onDisconnect = Emitter.Listener {
        Log.d(TAG, "Socket disconnected")
    }

    // ═══════════════════════════
    //  MEDIASOUP SİNYALLEŞME
    // ═══════════════════════════

    fun requestRtpCapabilities() {
        Log.d(TAG, "RTP yetenekleri isteniyor...")
        socket.emit("get-rtp-capabilities", arrayOf()) { args ->
            try {
                val data = args[0] as JSONObject
                if (data.has("error")) {
                    Log.e(TAG, "RTP capabilities hatası: ${data.getString("error")}")
                    return@emit
                }
                val rtpCapabilities = data.getJSONObject("rtpCapabilities")
                Log.d(TAG, "RTP yetenekleri alındı")
                onRtpCapabilities?.invoke(rtpCapabilities)
            } catch (e: Exception) {
                Log.e(TAG, "RTP capabilities ayrıştırma hatası", e)
            }
        }
    }

    fun requestCreateTransport(direction: String, callback: (JSONObject) -> Unit) {
        Log.d(TAG, "Transport oluşturma isteniyor: $direction")
        val payload = JSONObject().apply {
            put("roomId", roomId)
            put("direction", direction)
        }

        socket.emit("create-transport", arrayOf(payload)) { args ->
            try {
                val data = args[0] as JSONObject
                if (data.has("error")) {
                    Log.e(TAG, "Create transport hatası: ${data.getString("error")}")
                    return@emit
                }
                Log.d(TAG, "Transport oluşturuldu: $direction / ${data.getString("id")}")
                callback(data)
            } catch (e: Exception) {
                Log.e(TAG, "Create transport ayrıştırma hatası", e)
            }
        }
    }

    fun connectTransport(direction: String, dtlsParameters: JSONObject, callback: () -> Unit) {
        Log.d(TAG, "Transport bağlanıyor: $direction")
        val payload = JSONObject().apply {
            put("roomId", roomId)
            put("direction", direction)
            put("dtlsParameters", dtlsParameters)
        }

        socket.emit("connect-transport", arrayOf(payload)) { args ->
            try {
                val data = args[0] as JSONObject
                if (data.has("error")) {
                    Log.e(TAG, "Connect transport hatası: ${data.getString("error")}")
                    return@emit
                }
                Log.d(TAG, "Transport bağlandı: $direction")
                callback()
            } catch (e: Exception) {
                Log.e(TAG, "Connect transport ayrıştırma hatası", e)
            }
        }
    }

    fun produce(kind: String, rtpParameters: JSONObject, callback: (String) -> Unit) {
        Log.d(TAG, "Produce isteniyor: $kind")
        val payload = JSONObject().apply {
            put("roomId", roomId)
            put("kind", kind)
            put("rtpParameters", rtpParameters)
        }

        socket.emit("produce", arrayOf(payload)) { args ->
            try {
                val data = args[0] as JSONObject
                if (data.has("error")) {
                    Log.e(TAG, "Produce hatası: ${data.getString("error")}")
                    return@emit
                }
                val producerId = data.getString("producerId")
                Log.d(TAG, "Producer oluşturuldu: $kind / $producerId")
                callback(producerId)
            } catch (e: Exception) {
                Log.e(TAG, "Produce ayrıştırma hatası", e)
            }
        }
    }

    fun consume(producerId: String, rtpCapabilities: JSONObject, callback: (JSONObject) -> Unit) {
        Log.d(TAG, "Consume isteniyor: $producerId")
        val payload = JSONObject().apply {
            put("roomId", roomId)
            put("producerId", producerId)
            put("rtpCapabilities", rtpCapabilities)
        }

        socket.emit("consume", arrayOf(payload)) { args ->
            try {
                val data = args[0] as JSONObject
                if (data.has("error")) {
                    Log.e(TAG, "Consume hatası: ${data.getString("error")}")
                    return@emit
                }
                Log.d(TAG, "Consumer oluşturuldu: ${data.getString("kind")} / ${data.getString("consumerId")}")
                callback(data)
            } catch (e: Exception) {
                Log.e(TAG, "Consume ayrıştırma hatası", e)
            }
        }
    }

    fun resumeConsumer(consumerId: String) {
        Log.d(TAG, "Consumer resume ediliyor: $consumerId")
        val payload = JSONObject().apply {
            put("roomId", roomId)
            put("consumerId", consumerId)
        }

        socket.emit("consumer-resume", arrayOf(payload)) { args ->
            try {
                val data = args[0] as JSONObject
                if (data.has("error")) {
                    Log.e(TAG, "Consumer resume hatası: ${data.getString("error")}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Consumer resume ayrıştırma hatası", e)
            }
        }
    }

    fun getExistingProducers(callback: (JSONArray) -> Unit) {
        Log.d(TAG, "Mevcut producer'lar isteniyor...")
        val payload = JSONObject().apply {
            put("roomId", roomId)
        }

        socket.emit("get-producers", arrayOf(payload)) { args ->
            try {
                val data = args[0] as JSONObject
                if (data.has("error")) {
                    Log.e(TAG, "Get producers hatası: ${data.getString("error")}")
                    return@emit
                }
                val producers = data.getJSONArray("producers")
                Log.d(TAG, "Mevcut producer sayısı: ${producers.length()}")
                callback(producers)
            } catch (e: Exception) {
                Log.e(TAG, "Get producers ayrıştırma hatası", e)
            }
        }
    }

    fun disconnect() {
        socket.disconnect()
    }
}