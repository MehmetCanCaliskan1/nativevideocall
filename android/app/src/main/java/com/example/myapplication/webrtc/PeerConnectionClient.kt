package com.example.myapplication.webrtc

import android.content.Context
import android.util.Log
import io.socket.client.IO
import io.socket.client.Socket
import org.webrtc.*
import java.net.URISyntaxException
import org.json.JSONObject
import org.json.JSONException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraMetadata
import android.os.Build
class PeerConnectionClient(
    private val context: Context,
    private val roomId: String,
    private val rtcListener: RtcListener,
    host: String,
    private val rootEglBase: EglBase
) {
    fun sendJoinDecision(requesterId: String, requesterName: String, approved: Boolean) {
        val decisionData = JSONObject()

        decisionData.put("decision", if (approved) "approve" else "reject")

        decisionData.put("requesterId", requesterId)

        decisionData.put("requesterName", requesterName)

        socket.emit("handle-join-request", decisionData)
    }
    fun toggleFlash(isEnable: Boolean): Boolean {
        Log.d(TAG, "toggleFlash triggered. Requested: $isEnable")

        val cameraVideoCapturer = videoCapturer as? CameraVideoCapturer
        if (cameraVideoCapturer == null) {
            Log.e(TAG, "Error: videoCapturer is null or not a CameraVideoCapturer")
            return false
        }

        try {
            // Find "currentSession" field in the hierarchy
            var currentClass: Class<*>? = videoCapturer!!.javaClass
            var sessionField: java.lang.reflect.Field? = null

            while (currentClass != null) {
                try {
                    sessionField = currentClass.getDeclaredField("currentSession")
                    break
                } catch (e: NoSuchFieldException) {
                    currentClass = currentClass.superclass
                }
            }

            if (sessionField == null) {
                Log.e(TAG, "Error: 'currentSession' field not found in capturer")
                return false
            }

            sessionField.isAccessible = true
            val currentSession = sessionField.get(videoCapturer) ?: return false

            val sessionClass = currentSession.javaClass
            val fields = sessionClass.declaredFields
            fields.forEach { it.isAccessible = true }

            // Handle Camera1 or Camera2 sessions
            if (sessionClass.name.contains("Camera1Session")) {
                val cameraField = fields.find { it.type.name.contains("android.hardware.Camera") }
                if (cameraField != null) {
                    val camera = cameraField.get(currentSession) as android.hardware.Camera
                    val params = camera.parameters
                    val supportedFlashModes = params.supportedFlashModes
                    if (supportedFlashModes != null && supportedFlashModes.contains(android.hardware.Camera.Parameters.FLASH_MODE_TORCH)) {
                        params.flashMode = if (isEnable) android.hardware.Camera.Parameters.FLASH_MODE_TORCH else android.hardware.Camera.Parameters.FLASH_MODE_OFF
                        camera.parameters = params
                        return true
                    } else {
                        Log.w(TAG, "Flash mode TORCH not supported on this camera")
                    }
                }
            } else if (sessionClass.name.contains("Camera2Session")) {
                val sessionField = fields.find { it.type == android.hardware.camera2.CameraCaptureSession::class.java }
                if (sessionField == null) {
                    Log.e(TAG, "Camera2Session: captureSession field not found")
                    return false
                }
                
                val captureSession = sessionField.get(currentSession) as android.hardware.camera2.CameraCaptureSession
                var builder: android.hardware.camera2.CaptureRequest.Builder? = null
                var callback: android.hardware.camera2.CameraCaptureSession.CaptureCallback? = null

                // 1. Try to get existing builder field
                val builderField = fields.find { it.type == android.hardware.camera2.CaptureRequest.Builder::class.java }
                    ?: fields.find { it.name == "captureRequestBuilder" || it.name == "captureRequest" || it.name == "builder" }
                
                if (builderField != null) {
                    builder = builderField.get(currentSession) as? android.hardware.camera2.CaptureRequest.Builder
                    val callbackField = fields.find { it.type.name.contains("CaptureCallback") }
                    callback = callbackField?.get(currentSession) as? android.hardware.camera2.CameraCaptureSession.CaptureCallback
                }

                // 2. Fallback: Create a new builder if it's not stored as a field (common in some WebRTC versions)
                if (builder == null) {
                    val cameraDeviceField = fields.find { it.type == android.hardware.camera2.CameraDevice::class.java }
                    val surfaceField = fields.find { it.type == android.view.Surface::class.java }
                    
                    if (cameraDeviceField != null && surfaceField != null) {
                        val cameraDevice = cameraDeviceField.get(currentSession) as android.hardware.camera2.CameraDevice
                        val surface = surfaceField.get(currentSession) as android.view.Surface
                        builder = cameraDevice.createCaptureRequest(android.hardware.camera2.CameraDevice.TEMPLATE_RECORD)
                        builder.addTarget(surface)
                        
                        // Try to restore original FPS settings from captureFormat to avoid framerate drops
                        try {
                            val formatField = fields.find { it.name == "captureFormat" }
                            val fpsUnitFactorField = fields.find { it.name == "fpsUnitFactor" }
                            if (formatField != null && fpsUnitFactorField != null) {
                                val format = formatField.get(currentSession)
                                val fpsUnitFactor = fpsUnitFactorField.get(currentSession) as Int
                                val framerate = format!!.javaClass.getDeclaredField("framerate").let { 
                                    it.isAccessible = true
                                    it.get(format) 
                                }
                                val minFps = framerate!!.javaClass.getDeclaredField("min").let {
                                    it.isAccessible = true
                                    it.get(framerate)
                                } as Int
                                val maxFps = framerate.javaClass.getDeclaredField("max").let {
                                    it.isAccessible = true
                                    it.get(framerate)
                                } as Int
                                builder.set(android.hardware.camera2.CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, 
                                    android.util.Range(minFps / fpsUnitFactor, maxFps / fpsUnitFactor))
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to restore FPS settings: ${e.message}")
                        }
                    }
                }

                if (builder != null) {
                    builder.set(android.hardware.camera2.CaptureRequest.FLASH_MODE,
                        if (isEnable) android.hardware.camera2.CameraMetadata.FLASH_MODE_TORCH
                        else android.hardware.camera2.CameraMetadata.FLASH_MODE_OFF)

                    captureSession.setRepeatingRequest(builder.build(), callback, null)
                    return true
                } else {
                    Log.e(TAG, "Could not find or create a CaptureRequest.Builder")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "toggleFlash reflection error", e)
        }

        return false
    }  companion object {
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

    var useFrontCamera = true
        private set

    init {
        initWebRtc()
        initSignaling(host)
    }

    // initialize

    private fun initWebRtc() {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .createInitializationOptions()
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
            Log.e(TAG, "Socket URL Error: $host", e)
            throw RuntimeException(e)
        }

        signalingHandler = SignalingHandler(
            socket = socket,
            roomId = roomId,
            onPeerCreated = { createPeer() },
            getPeer = { peer },
            listener = rtcListener
        )

        signalingHandler.setupListeners()

        // 1. Socket bağlantısını kontrol et
        socket.on(Socket.EVENT_CONNECT) {
            Log.d(TAG, "Socket: CONNECTED to server")
        }

        socket.on(Socket.EVENT_CONNECT_ERROR) { args ->
            Log.e(TAG, "Socket: CONNECTION ERROR: ${args[0]}")
        }

        // 2. join-request dinleyicisi
        socket.on("join-request") { args ->
            Log.d(TAG, "EVENT: join-request received. Data: ${args.getOrNull(0)}")

            if (args.isNotEmpty()) {
                try {
                    val data = args[0] as JSONObject
                    val requesterId = data.optString("socketId", data.optString("id"))

                    val requesterName = data.optString("username", data.optString("name", "Misafir"))

                    Log.d(TAG, "Parsing Success -> ID: $requesterId, Name: $requesterName")

                    rtcListener.onJoinRequest(requesterId, requesterName)

                } catch (e: Exception) {
                    Log.e(TAG, "JSON Parsing Error in join-request", e)
                }
            } else {
                Log.e(TAG, "join-request received but args is empty!")
            }
        }

        socket.on("join-rejected") {
            Log.d(TAG, "Giriş isteği host tarafından reddedildi.")
            // Activity'e haber ver
            rtcListener.onJoinRejected("Host isteğinizi reddetti.")
        }
        socket.on("user-disconnected") {
            // sunucudan sinyal gelir gelmez çalışır
            rtcListener.onRemoveRemoteStream()
        }


        socket.connect()
    }

    //  PUBLIC API

    fun start() {
        startCameraCapture()
    }

    fun switchCamera() {
        (videoCapturer as? CameraVideoCapturer)?.switchCamera(object : CameraVideoCapturer.CameraSwitchHandler {
            override fun onCameraSwitchDone(isFront: Boolean) {
                useFrontCamera = isFront
                Log.d(TAG, "Camera switch done. isFront: $isFront")
            }

            override fun onCameraSwitchError(error: String?) {
                Log.e(TAG, "Camera switch error: $error")
            }
        })
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

    /*  PEER  */

    private fun createPeer(): WebRtcPeer {
        peer = WebRtcPeer(
            factory = factory!!,
            localStream = localStream!!,
            pcConstraints = pcConstraints,
            listener = rtcListener,
            signalingHandler = signalingHandler
        )
        return peer!!
    }

    //  CAMERA

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

        rtcListener.onAddLocalStream(localStream!!)
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

private fun VideoCapturer.stopCaptureSafely() {
    try {
        stopCapture()
    } catch (e: Exception) {
        Log.e("PeerConnectionClient", "stopCapture error", e)
    }
}



