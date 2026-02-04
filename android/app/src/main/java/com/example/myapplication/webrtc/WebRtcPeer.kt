package com.example.myapplication.webrtc

import android.util.Log
import org.webrtc.*

/**
 * Manages a single WebRTC peer connection including SDP negotiation
 * and ICE candidates.
 */
class WebRtcPeer(
    factory: PeerConnectionFactory,
    localStream: MediaStream,
    private val pcConstraints: MediaConstraints,
    private val listener: RtcListener,
    private val signalingHandler: SignalingHandler
) : SdpObserver, PeerConnection.Observer {

    val peerConnection: PeerConnection

    companion object {
        private const val TAG = "WebRtcPeer"
    }

    init {
        Log.d(TAG, "Creating new peer connection")

        val rtcConfig = PeerConnection.RTCConfiguration(mutableListOf()).apply {
            iceServers.add(
                PeerConnection.IceServer
                    .builder("stun:stun.l.google.com:19302")
                    .createIceServer()
            )
        }

        peerConnection = factory.createPeerConnection(rtcConfig, this)
            ?: throw IllegalStateException("PeerConnection creation failed")

        // Add local tracks
        localStream.audioTracks.firstOrNull()
            ?.let { peerConnection.addTrack(it, listOf("ARDAMS")) }

        localStream.videoTracks.firstOrNull()
            ?.let { peerConnection.addTrack(it, listOf("ARDAMS")) }

        listener.onStatusChanged("CONNECTING")
    }

    /* ================= Public API ================= */

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
        Log.d(TAG, "Disposing peer connection")
        peerConnection.dispose()
    }

    /* ================= SDP Observer ================= */

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

    override fun onSetSuccess() {}
    override fun onCreateFailure(error: String) {
        Log.e(TAG, "SDP create failure: $error")
    }

    override fun onSetFailure(error: String) {
        Log.e(TAG, "SDP set failure: $error")
    }

    /* ================= PeerConnection.Observer ================= */

    override fun onSignalingChange(state: PeerConnection.SignalingState) {}

    override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
        when (state) {
            PeerConnection.IceConnectionState.CONNECTED -> {
                Log.d(TAG, "Peers connected")
                listener.onStatusChanged("CONNECTED")
                listener.onPeersConnectionStatusChange(true)
            }

            PeerConnection.IceConnectionState.DISCONNECTED -> {
                listener.onStatusChanged("DISCONNECTED")
                listener.onRemoveRemoteStream()
                listener.onPeersConnectionStatusChange(false)
                dispose()
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
        Log.d(TAG, "onAddStream ${stream.id}")
        listener.onAddRemoteStream(stream)
    }

    override fun onRemoveStream(stream: MediaStream) {
        Log.d(TAG, "onRemoveStream ${stream.id}")
        listener.onRemoveRemoteStream()
    }

    override fun onIceConnectionReceivingChange(receiving: Boolean) {}
    override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}
    override fun onRenegotiationNeeded() {}

    override fun onDataChannel(dataChannel: DataChannel) {
    }
}
