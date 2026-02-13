package com.example.myapplication.webrtc

import android.util.Log
import com.example.myapplication.BuildConfig
import org.webrtc.*

class WebRtcPeer(
    factory: PeerConnectionFactory,
    localStream: MediaStream,
    private val pcConstraints: MediaConstraints,
    private val listener: RtcListener,
    private val signalingHandler: SignalingHandler
) : SdpObserver, PeerConnection.Observer {

    val peerConnection: PeerConnection
    private val pendingIceCandidates = mutableListOf<IceCandidate>()

    companion object {
        private const val TAG = "WebRtcPeer"
    }

    init {
        val rtcConfig = PeerConnection.RTCConfiguration(mutableListOf()).apply {
            iceServers.add(
                PeerConnection.IceServer
                    .builder("stun:stun.l.google.com:19302")
                    .createIceServer()
            )

           iceServers.add(
                PeerConnection.IceServer.builder(BuildConfig.TURN_URI)
                    .setUsername(BuildConfig.TURN_USERNAME)
                    .setPassword(BuildConfig.TURN_PASSWORD)
                    .createIceServer()
            )

            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            iceCandidatePoolSize = 2
        }

        peerConnection = factory.createPeerConnection(rtcConfig, this)
            ?: throw IllegalStateException("PeerConnection creation failed")

        localStream.audioTracks.firstOrNull()
            ?.let { peerConnection.addTrack(it, listOf("ARDAMS")) }

        localStream.videoTracks.firstOrNull()
            ?.let { peerConnection.addTrack(it, listOf("ARDAMS")) }

        listener.onStatusChanged("CONNECTING")
    }





    fun createOffer() {
        peerConnection.createOffer(this, pcConstraints)
    }

    fun createAnswer() {
        peerConnection.createAnswer(this, pcConstraints)
    }

    fun setRemoteDescription(sdp: SessionDescription) {
        peerConnection.setRemoteDescription(this, sdp)
    }

    fun addIceCandidate(candidate: IceCandidate) {
        if (peerConnection.remoteDescription != null) {
            peerConnection.addIceCandidate(candidate)
        } else {
            pendingIceCandidates.add(candidate)
        }
    }

    fun addTrack(track: MediaStreamTrack) {
        peerConnection.addTrack(track, listOf("ARDAMS"))
    }

    fun removeTrack(sender: RtpSender) {
        peerConnection.removeTrack(sender)
    }

    fun getSenders(): List<RtpSender> = peerConnection.senders

    fun dispose() {
        // Önce close sonra dispose
        try {
            peerConnection.close()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to close peer connection", e)
        }
        peerConnection.dispose()
    }

    override fun onCreateSuccess(sdp: SessionDescription) {
        peerConnection.setLocalDescription(this, sdp)

        when (sdp.type) {
            SessionDescription.Type.OFFER ->
                signalingHandler.sendOffer(sdp)

            SessionDescription.Type.ANSWER ->
                signalingHandler.sendAnswer(sdp)

            else -> Unit
        }
    }

    override fun onSetSuccess() {
        if (peerConnection.remoteDescription != null && pendingIceCandidates.isNotEmpty()) {
            pendingIceCandidates.forEach { peerConnection.addIceCandidate(it) }
            pendingIceCandidates.clear()
        }
    }

    override fun onCreateFailure(error: String) {
        Log.e(TAG, "SDP create failure: $error")
    }

    override fun onSetFailure(error: String) {
        Log.e(TAG, "SDP set failure: $error")
    }

    override fun onSignalingChange(state: PeerConnection.SignalingState) {}

    override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
        Log.d(TAG, "onIceConnectionChange: $state")

        when (state) {
            // HEM CONNECTED HEM DE COMPLETED DURUMLARINI KONTROL ETME KISMI
            PeerConnection.IceConnectionState.CONNECTED,
            PeerConnection.IceConnectionState.COMPLETED -> {
                listener.onStatusChanged("CONNECTED")
                listener.onPeersConnectionStatusChange(true)
            }

            PeerConnection.IceConnectionState.DISCONNECTED -> {
                listener.onStatusChanged("DISCONNECTED")
                listener.onPeersConnectionStatusChange(false)
            }

            PeerConnection.IceConnectionState.FAILED,
            PeerConnection.IceConnectionState.CLOSED -> {
                listener.onStatusChanged("FAILED")
                listener.onRemoveRemoteStream()
                listener.onPeersConnectionStatusChange(false)
                dispose() // Bağlantı koptuysa temizle
            }

            else -> Unit
        }
    }

    override fun onIceCandidate(candidate: IceCandidate) {
        signalingHandler.sendIceCandidate(candidate)
    }

    override fun onIceCandidatesRemoved(candidates: Array<IceCandidate>) {
        peerConnection.removeIceCandidates(candidates)
    }

    override fun onAddStream(stream: MediaStream) {
        listener.onAddRemoteStream(stream)
    }

    override fun onRemoveStream(stream: MediaStream) {
        listener.onRemoveRemoteStream()
    }

    override fun onIceConnectionReceivingChange(receiving: Boolean) {}
    override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}
    override fun onRenegotiationNeeded() {}
    override fun onDataChannel(dataChannel: DataChannel) {}
}