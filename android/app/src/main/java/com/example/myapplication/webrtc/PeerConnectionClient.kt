package com.example.myapplication.webrtc

import android.content.Context
import android.hardware.camera2.CameraManager
import android.util.Log
import io.socket.client.IO
import io.socket.client.Socket
import org.webrtc.*
import java.net.URISyntaxException

class PeerConnectionClient(
    private val context: Context,
    private val roomId: String,
    private val listener: RtcListener,
    host: String,
    private val rootEglBase: EglBase
) {

    companion object {
        private const val TAG = "PeerConnectionClient"
    }

    private var factory: PeerConnectionFactory? = null
    private val pcConstraints = MediaConstraints()

    private var localStream: MediaStream? = null
    private var videoCapturer: VideoCapturer? = null
    private var videoSource: VideoSource? = null
    private var audioSource: AudioSource? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null

    private lateinit var socket: Socket
    private lateinit var signalingHandler: SignalingHandler
    private var peer: WebRtcPeer? = null

    private var useFrontCamera = true

    init {
        initWebRtc()
        initSignaling(host)
    }

    /* -------------------- INIT -------------------- */

    private fun initWebRtc() {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions()
        )

        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(
                DefaultVideoEncoderFactory(rootEglBase.eglBaseContext, true, true)
            )
            .setVideoDecoderFactory(
                DefaultVideoDecoderFactory(rootEglBase.eglBaseContext)
            )
            .createPeerConnectionFactory()

        pcConstraints.mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
        pcConstraints.mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
    }

    private fun initSignaling(host: String) {
        try {
            socket = IO.socket(host)
        } catch (e: URISyntaxException) {
            throw RuntimeException(e)
        }

        signalingHandler = SignalingHandler(
            socket = socket,
            roomId = roomId,
            onPeerCreated = { createPeer() },
            getPeer = { peer }
        )

        signalingHandler.setupListeners()
        socket.connect()
    }

    /* -------------------- PUBLIC API -------------------- */

    fun start() {
        startCameraCapture()
    }

    fun switchCamera() {
        (videoCapturer as? CameraVideoCapturer)?.switchCamera(null)
    }

    fun toggleAudio(enable: Boolean) {
        localStream?.audioTracks?.firstOrNull()?.setEnabled(enable)
    }

    fun toggleVideo(enable: Boolean) {
        localStream?.videoTracks?.firstOrNull()?.setEnabled(enable)
    }

    fun onDestroy() {
        signalingHandler.disconnect()

        videoCapturer?.stopCaptureSafely()
        videoCapturer?.dispose()

        surfaceTextureHelper?.dispose()
        videoSource?.dispose()
        audioSource?.dispose()

        peer?.dispose()
        factory?.dispose()

        socket.disconnect()
    }

    /* -------------------- PEER -------------------- */

    private fun createPeer(): WebRtcPeer {
        peer = WebRtcPeer(
            factory = factory!!,
            localStream = localStream!!,
            pcConstraints = pcConstraints,
            listener = listener,
            signalingHandler = signalingHandler
        )
        return peer!!
    }

    /* -------------------- CAMERA -------------------- */

    private fun startCameraCapture() {
        localStream = factory!!.createLocalMediaStream("LOCAL_STREAM")

        videoCapturer = createCameraCapturer()
        videoSource = factory!!.createVideoSource(false)

        surfaceTextureHelper =
            SurfaceTextureHelper.create("CameraThread", rootEglBase.eglBaseContext)

        videoCapturer!!.initialize(
            surfaceTextureHelper,
            context,
            videoSource!!.capturerObserver
        )

        videoCapturer!!.startCapture(1280, 720, 30)

        val videoTrack = factory!!.createVideoTrack("LOCAL_VIDEO", videoSource)
        audioSource = factory!!.createAudioSource(MediaConstraints())
        val audioTrack = factory!!.createAudioTrack("LOCAL_AUDIO", audioSource)

        localStream!!.addTrack(videoTrack)
        localStream!!.addTrack(audioTrack)

        listener.onAddLocalStream(localStream!!)
    }

    private fun createCameraCapturer(): VideoCapturer {
        val enumerator: CameraEnumerator =
            if (Camera2Enumerator.isSupported(context)) {
                Camera2Enumerator(context)
            } else {
                Camera1Enumerator(true)
            }

        for (device in enumerator.deviceNames) {
            if (enumerator.isFrontFacing(device) == useFrontCamera) {
                enumerator.createCapturer(device, null)?.let {
                    return it
                }
            }
        }

        throw RuntimeException("No camera found")
    }

}

/* -------------------- EXT -------------------- */

private fun VideoCapturer.stopCaptureSafely() {
    try {
        stopCapture()
    } catch (e: Exception) {
        Log.e("PeerConnectionClient", "stopCapture error", e)
    }
}
