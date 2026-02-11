package com.example.myapplication

import android.Manifest
import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.graphics.Point
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.Window
import android.view.WindowManager.LayoutParams
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.myapplication.webrtc.PeerConnectionClient
import com.example.myapplication.webrtc.RtcListener
import com.google.android.material.bottomsheet.BottomSheetDialog
import org.webrtc.DataChannel
import org.webrtc.EglBase
import org.webrtc.MediaStream
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import androidx.core.graphics.toColorInt
import java.lang.ref.WeakReference
import androidx.appcompat.app.AlertDialog
class RoomActivity : AppCompatActivity(), RtcListener {

    companion object {
        private val TAG = RoomActivity::class.java.canonicalName
        private val RequiredPermissions = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )

        @JvmStatic
        var mediaProjectionPermissionResultData: Intent? = null

        // WeakReference to avoid memory leak while allowing ScreenCaptureService access
        @JvmStatic
        var peerConnectionClientRef: WeakReference<PeerConnectionClient>? = null
    }

    private lateinit var mSocketAddress: String
    private lateinit var roleBadge: TextView
    private lateinit var roomId: String
    private val permissionChecker = PermissionChecker()
    private var peerConnectionClient: PeerConnectionClient? = null

    private lateinit var localView: SurfaceViewRenderer
    private lateinit var remoteView: SurfaceViewRenderer
    private lateinit var eglBase: EglBase

    private var videoEnabled = true
    private var audioEnabled = true
    private var isAppInForeground = true
    private var needsCameraRestart = false


    private var dX = 0f
    private var dY = 0f
    private var lastAction = 0
    private lateinit var participantNameText: TextView

    // Messaging
    private var messageBottomSheet: BottomSheetDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(R.layout.activity_call)
        roleBadge = findViewById(R.id.local_role_badge)
        // Use modern alternatives for deprecated flags
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    or LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }

        window.addFlags(LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Hide the action bar
        supportActionBar?.hide()

        // Set status bar color
        window.apply {
            addFlags(LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                @Suppress("DEPRECATION")
                statusBarColor = "#373f3d".toColorInt()
            }
            // Note: On API 35+, status bar is automatically transparent and cannot be changed
        }

        setContentView(R.layout.activity_call)

        mSocketAddress = getString(R.string.serverAddress)

        localView = findViewById(R.id.local_view)
        remoteView = findViewById(R.id.remote_view)

        eglBase = EglBase.create()

        localView.apply {
            init(eglBase.eglBaseContext, null)
            setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
            setMirror(false)
            setZOrderMediaOverlay(true)
            setEnableHardwareScaler(true)
        }

        remoteView.apply {
            init(eglBase.eglBaseContext, null)
            setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
            setMirror(false)
            setEnableHardwareScaler(true)
        }

        roomId = intent.getStringExtra(HomeActivity.EXTRA_MESSAGE) ?: ""

        // Initialize TextViews
        val roomIdText = findViewById<TextView>(R.id.room_id)
        participantNameText = findViewById(R.id.participant_name)

        // Set room ID
        roomIdText.text = "Room: $roomId"

        checkPermissions()
        init()
    }

    private fun checkPermissions() {
        permissionChecker.verifyPermissions(
            this,
            RequiredPermissions,
            object : PermissionChecker.VerifyPermissionsCallback {
                override fun onPermissionAllGranted() {}

                override fun onPermissionDeny(permissions: Array<String>) {
                    Toast.makeText(
                        this@RoomActivity,
                        "Please grant required permissions.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        )
    }

    private fun init() {
        peerConnectionClient = PeerConnectionClient(
            this, roomId, this, mSocketAddress, eglBase,
        )
        peerConnectionClientRef = WeakReference(peerConnectionClient)

        if (PermissionChecker.hasPermissions(this, RequiredPermissions)) {
            peerConnectionClient?.start()
        }

        // Setup draggable local view
        val localViewContainer = findViewById<FrameLayout>(R.id.local_view_container)

        // Set initial position to top-right corner to avoid jumping when first frame arrives
        val initialParams = localViewContainer.layoutParams as FrameLayout.LayoutParams
        initialParams.width = (resources.displayMetrics.density * 112).toInt()
        initialParams.height = (resources.displayMetrics.density * 160).toInt()
        initialParams.rightMargin = (resources.displayMetrics.density * 16).toInt()
        initialParams.topMargin = (resources.displayMetrics.density * 80).toInt()
        initialParams.gravity = Gravity.TOP or Gravity.END
        localViewContainer.layoutParams = initialParams

        setupDraggableView(localViewContainer)

        // Switch camera
        val switchCamera = findViewById<ImageButton>(R.id.switch_camera)
        switchCamera.setOnClickListener {
            // Flip animation on local view container
            val flipAnimator = ObjectAnimator.ofFloat(localViewContainer, "rotationY", 0f, 180f)
            flipAnimator.duration = 600
            flipAnimator.start()

            // Switch camera at halfway point of animation
            localView.postDelayed({
                peerConnectionClient?.switchCamera()
            }, 300)
        }

        // Toggle video
        val btnVideo = findViewById<ImageButton>(R.id.btn_video)
        btnVideo.setOnClickListener {
            peerConnectionClient?.toggleVideo(!videoEnabled)
            videoEnabled = !videoEnabled
            btnVideo.setImageResource(
                if (videoEnabled) R.drawable.video_fill else R.drawable.video_slash_fill
            )
            localViewContainer.visibility = if (videoEnabled) View.VISIBLE else View.GONE
        }

        // Toggle audio
        val btnMute = findViewById<ImageButton>(R.id.btn_mute)
        val localMuteIndicator = findViewById<ImageView>(R.id.local_mute_indicator)

        btnMute.setOnClickListener {
            peerConnectionClient?.toggleAudio(!audioEnabled)
            audioEnabled = !audioEnabled
            btnMute.setImageResource(
                if (audioEnabled) R.drawable.mic_fill else R.drawable.mic_slash_fill
            )
            // Show/hide mute indicator on local video
            localMuteIndicator.visibility = if (audioEnabled) View.GONE else View.VISIBLE
        }


        val hangUp = findViewById<ImageButton>(R.id.hang_up)
        hangUp.setOnClickListener {
            peerConnectionClient?.onDestroy()
            peerConnectionClient = null

            messageBottomSheet?.dismiss()

            val intent = Intent(this, HomeActivity::class.java)

            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)

            startActivity(intent)
            finish()
        }

        localView.addFrameListener({ bitmap ->
            // this will give exact size which remote peer will see
            Log.d(TAG, "localView size: ${bitmap.width} ${bitmap.height}")

            val newWidth = (resources.displayMetrics.density * 100).toInt()
            // Calculate aspect ratio using float division to preserve accuracy
            val localAspectRatio = bitmap.height.toFloat() / bitmap.width.toFloat()
            val newHeight = (newWidth * localAspectRatio).toInt()

            val localViewContainer = findViewById<FrameLayout>(R.id.local_view_container)
            val params = localViewContainer.layoutParams as FrameLayout.LayoutParams
            params.width = newWidth
            params.height = newHeight
            params.rightMargin = (resources.displayMetrics.density * 16).toInt()
            params.topMargin = (resources.displayMetrics.density * 80).toInt()
            // Position at top-right corner of the restricted area (below top bar)
            params.gravity = Gravity.TOP or Gravity.END

            runOnUiThread {
                localViewContainer.layoutParams = params
            }
        }, 1F)

        remoteView.addFrameListener({ bitmap ->
            // this will give exact size which remote peer will see
            Log.d(TAG, "remoteView size: ${bitmap.width} ${bitmap.height}")

            val screenWidth = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                windowManager.currentWindowMetrics.bounds.width()
            } else {
                @Suppress("DEPRECATION")
                val displaySize = Point()
                @Suppress("DEPRECATION")
                windowManager.defaultDisplay.getSize(displaySize)
                displaySize.x
            }

            // Calculate the remote view height based on aspect ratio
            // Width = screen width, height calculated from aspect ratio
            val remoteAspectRatio = bitmap.height.toFloat() / bitmap.width.toFloat()
            val newRemoteHeight = (screenWidth * remoteAspectRatio).toInt()

            // Update the layout parameters for remoteView
            val remoteParams = remoteView.layoutParams as FrameLayout.LayoutParams
            remoteParams.width = FrameLayout.LayoutParams.MATCH_PARENT
            remoteParams.height = newRemoteHeight
            remoteParams.gravity = Gravity.CENTER

            runOnUiThread {
                remoteView.layoutParams = remoteParams
            }
        }, 1F)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupDraggableView(view: FrameLayout) {
        view.setOnTouchListener { v, event ->
            // Get the top bar and bottom controls to constrain the dragging area
            val topBar = findViewById<View>(R.id.top_bar)
            val bottomControls = findViewById<View>(R.id.bottom_controls_container)

            // Calculate available area
            val topBarBottom = topBar.height
            val bottomControlsTop = bottomControls.top

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dX = v.x - event.rawX
                    dY = v.y - event.rawY
                    lastAction = MotionEvent.ACTION_DOWN
                }

                MotionEvent.ACTION_MOVE -> {
                    var newX = event.rawX + dX
                    var newY = event.rawY + dY

                    // Get screen width and view dimensions
                    val screenWidth = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        windowManager.currentWindowMetrics.bounds.width()
                    } else {
                        @Suppress("DEPRECATION")
                        val displaySize = Point()
                        @Suppress("DEPRECATION")
                        windowManager.defaultDisplay.getSize(displaySize)
                        displaySize.x
                    }

                    // Constrain X position
                    if (newX < 0) newX = 0f
                    if (newX + v.width > screenWidth) newX = (screenWidth - v.width).toFloat()

                    // Constrain Y position to area between top bar and bottom controls
                    if (newY < topBarBottom) newY = topBarBottom.toFloat()
                    if (newY + v.height > bottomControlsTop) newY = (bottomControlsTop - v.height).toFloat()

                    v.x = newX
                    v.y = newY
                    lastAction = MotionEvent.ACTION_MOVE
                }

                MotionEvent.ACTION_UP -> {
                    if (lastAction == MotionEvent.ACTION_MOVE) {
                        // Snap to nearest edge (left or right)
                        val screenWidth2 = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            windowManager.currentWindowMetrics.bounds.width()
                        } else {
                            @Suppress("DEPRECATION")
                            val displaySize2 = Point()
                            @Suppress("DEPRECATION")
                            windowManager.defaultDisplay.getSize(displaySize2)
                            displaySize2.x
                        }

                        val currentX = v.x
                        val centerX = currentX + v.width / 2f

                        // Determine which edge is closer
                        val margin = (16 * resources.displayMetrics.density).toInt()
                        val targetX = if (centerX < screenWidth2 / 2f) {
                            // Snap to left edge
                            margin.toFloat()
                        } else {
                            // Snap to right edge
                            (screenWidth2 - v.width - margin).toFloat()
                        }

                        // Animate to the target position
                        v.animate()
                            .x(targetX)
                            .setDuration(200)
                            .start()
                    }
                    v.performClick()
                }

                else -> return@setOnTouchListener false
            }
            true
        }
    }


    override fun onPause() {
        super.onPause()
        isAppInForeground = false
        Log.d(TAG, "onPause - app going to background")
    }

    override fun onResume() {
        super.onResume()
        isAppInForeground = true
        Log.d(TAG, "onResume - app in foreground")

        // If camera restart was deferred because app was in background, do it now
        if (needsCameraRestart) {
            Log.d(TAG, "Restarting camera after returning to foreground")
            needsCameraRestart = false
            peerConnectionClient?.start()
        }
    }

    override fun onDestroy() {
        println("CallActivity onDestroy $peerConnectionClient")
        peerConnectionClient?.let {
            println("CallActivity onDestroy")
            it.onDestroy()
        }
        peerConnectionClientRef?.clear()
        peerConnectionClientRef = null

        messageBottomSheet?.dismiss()
        messageBottomSheet = null

        localView.release()
        remoteView.release()
        eglBase.release()

        super.onDestroy()
    }

    override fun onStatusChanged(newStatus: String) {
        runOnUiThread {
            Toast.makeText(this@RoomActivity, newStatus, Toast.LENGTH_SHORT).show()

            val roomIdText = findViewById<TextView>(R.id.room_id)

            if (newStatus == "CONNECTED") {
                roomIdText.text = "Room: $roomId (Bağlandı)"
                roomIdText.setTextColor(Color.GREEN)
            } else if (newStatus == "CONNECTING") {
                roomIdText.text = "Room: $roomId (Bağlanıyor...)"
                roomIdText.setTextColor(Color.YELLOW)
            } else {
                roomIdText.text = "Room: $roomId ($newStatus)"
                roomIdText.setTextColor(Color.RED)
            }
        }
    }


    override fun onAddLocalStream(localStream: MediaStream) {
        Log.d(TAG, "onAddLocalStream")

        val videoTrack = localStream.videoTracks[0]
        videoTrack.setEnabled(true)
        localStream.videoTracks[0].addSink(localView)
    }

    override fun onRemoveLocalStream(localStream: MediaStream) {
        Log.d(TAG, "onRemoveLocalStream")
        if (localStream.videoTracks.isNotEmpty()) {
            localStream.videoTracks[0].removeSink(localView)
        }
    }

    override fun onAddRemoteStream(remoteStream: MediaStream) {
        Log.d(TAG, "onAddRemoteStream ${remoteStream.id} ${remoteStream.videoTracks.size} ${remoteStream.audioTracks.size}")

        if (remoteStream.videoTracks.isNotEmpty()) {
            val remoteVideoTrack = remoteStream.videoTracks[0]
            remoteVideoTrack.addSink(remoteView)
        }
    }

    override fun onRemoveRemoteStream() {
        Log.d(TAG, "onRemoveRemoteStream")
        runOnUiThread {
            remoteView.clearImage()

            // Revert local view container to original position (top-right)
            val localViewContainer = findViewById<FrameLayout>(R.id.local_view_container)
            val params = localViewContainer.layoutParams as FrameLayout.LayoutParams
            params.width = (resources.displayMetrics.density * 112).toInt()
            params.height = (resources.displayMetrics.density * 160).toInt()
            params.rightMargin = (resources.displayMetrics.density * 16).toInt()
            params.topMargin = (resources.displayMetrics.density * 80).toInt()
            params.gravity = Gravity.TOP or Gravity.END

            localViewContainer.layoutParams = params
        }
    }

    override fun onPeersConnectionStatusChange(success: Boolean) {
        Log.d(TAG, "onPeersConnectionStatusChange: Success=$success")

    }


    override fun onJoinRequest(participantId: String, participantName: String?) {
        runOnUiThread {
            val nameToShow = participantName ?: "Gizli Misafir"

            if (isFinishing) return@runOnUiThread

            AlertDialog.Builder(this)
                .setTitle("Odaya Giriş İsteği")
                .setMessage("$nameToShow katılmak istiyor. Onaylıyor musunuz?")
                .setCancelable(false)
                .setPositiveButton("Kabul Et") { dialog, _ ->
                    // 1. Sunucuya kabul kararını bildir
                    peerConnectionClient?.sendJoinDecision(participantId, nameToShow, true)                    // 2. ARAYÜZÜ GÜNCELLE (Burası eklendi)
                    // Bağlantı kurulana kadar "Connecting" (Sarı) durumuna geçiriyoruz.
                    onStatusChanged("CONNECTING")

                    Toast.makeText(this, "$nameToShow kabul edildi, bağlanılıyor...", Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                }
                .setNegativeButton("Reddet") { dialog, _ ->
                    peerConnectionClient?.sendJoinDecision(participantId, requesterName =participantId,approved = false)
                    Toast.makeText(this, "$nameToShow reddedildi", Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                }
                .setIcon(android.R.drawable.ic_dialog_info)
                .show()
        }
    }
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        permissionChecker.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }


    override fun onJoinRejected(reason: String) {
        runOnUiThread {
            Toast.makeText(this, "Odaya giriş talebiniz reddedildi.", Toast.LENGTH_LONG).show()

            val intent = Intent(this, HomeActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)

            finish()
        }
    }

    override fun onRoleDataReceived(isHost: Boolean) {
        runOnUiThread {
            if (isHost) {
                roleBadge.text = "HOST"
                roleBadge.setBackgroundColor(Color.parseColor("#AAFF0000")) // Kırmızımsı
            } else {
                roleBadge.text = "GUEST"
                roleBadge.setBackgroundColor(Color.parseColor("#AA0000FF")) // Mavimsi
            }
        }
    }
}
