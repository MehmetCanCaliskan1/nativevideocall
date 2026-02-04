import Foundation
import WebRTC
import AVFoundation

// Protokolü sadeleştirdik: Sadece bağlantı ve sinyal durumları kaldı.
protocol WebRTCClientDelegate: AnyObject {
    func didGenerateCandidate(iceCandidate: RTCIceCandidate)
    func didIceConnectionStateChanged(iceConnectionState: RTCIceConnectionState)
    func didConnectWebRTC()
    func didDisconnectWebRTC()
    func onPeersConnectionStatusChange(connected: Bool)
}

class WebRTCClient: NSObject, RTCPeerConnectionDelegate, RTCVideoViewDelegate {
    
    // MARK: - Properties
    private var peerConnectionFactory: RTCPeerConnectionFactory!
    private var peerConnection: RTCPeerConnection?
    private var videoCapturer: RTCVideoCapturer!
    private var localVideoTrack: RTCVideoTrack!
    private var localAudioTrack: RTCAudioTrack!
    private var localRenderView: RTCMTLVideoView?
    private var localView: UIView!
    private var remoteRenderView: RTCMTLVideoView?
    private var remoteView: UIView!
    private var remoteStream: RTCMediaStream?
    
    private var channels: (video: Bool, audio: Bool) = (false, false)
    private var useFrontCamera = true
    private var videoSource: RTCVideoSource?
    private var isSwitchingCamera = false
    
    weak var delegate: WebRTCClientDelegate?
    public private(set) var isConnected: Bool = false
    
    // MARK: - Init & Deinit
    override init() {
        super.init()
        print("WebRTC Client initialize")
    }
    
    deinit {
        print("WebRTC Client Deinit")
        self.peerConnectionFactory = nil
        self.peerConnection = nil
    }
    
    // MARK: - View Accessors
    func localVideoView() -> UIView {
        return localView
    }
    
    func remoteVideoView() -> UIView {
        return remoteView
    }
    
    // MARK: - Setup
    func setup(videoTrack: Bool, audioTrack: Bool, customFrameCapturer: Bool) {
        print("WebRTC Client Setup")
        self.channels.video = videoTrack
        self.channels.audio = audioTrack
        
        let videoEncoderFactory = RTCDefaultVideoEncoderFactory()
        let videoDecoderFactory = RTCDefaultVideoDecoderFactory()
        
        self.peerConnectionFactory = RTCPeerConnectionFactory(
            encoderFactory: videoEncoderFactory,
            decoderFactory: videoDecoderFactory
        )
        
        setupView()
        setupLocalTracks()
        
        if self.channels.video {
            startCaptureLocalVideo(
                cameraPositon: .front,
                videoWidth: 640,
                videoHeight: 640 * 16 / 9,
                videoFps: 30
            )
            self.localVideoTrack?.add(self.localRenderView!)
        }
    }
    
    func setupLocalViewFrame(frame: CGRect) {
        localView.frame = frame
        localRenderView?.frame = localView.frame
    }
    
    func setupRemoteViewFrame(frame: CGRect) {
        remoteView.frame = frame
        remoteRenderView?.frame = remoteView.frame
    }
    
    // MARK: - Connect / Disconnect
    func connect(onSuccess: @escaping (RTCSessionDescription) -> Void) {
        self.peerConnection = setupPeerConnection()
        self.peerConnection!.delegate = self
        
        if self.channels.video {
            self.peerConnection!.add(localVideoTrack, streamIds: ["stream0"])
        }
        if self.channels.audio {
            self.peerConnection!.add(localAudioTrack, streamIds: ["stream0"])
        }
        
        makeOffer(onSuccess: onSuccess)
    }
    
    func disconnect() {
        if self.peerConnection != nil {
            self.peerConnection!.close()
        }
    }
    
    // MARK: - Signaling Handling
    func receiveOffer(offerSDP: RTCSessionDescription, onCreateAnswer: @escaping (RTCSessionDescription) -> Void) {
        if self.peerConnection == nil {
            print("Offer received, creating peer connection")
            self.peerConnection = setupPeerConnection()
            self.peerConnection!.delegate = self
            
            if self.channels.video {
                self.peerConnection!.add(localVideoTrack, streamIds: ["stream-0"])
            }
            if self.channels.audio {
                self.peerConnection!.add(localAudioTrack, streamIds: ["stream-0"])
            }
        }
        
        self.peerConnection!.setRemoteDescription(offerSDP) { (err) in
            if let error = err {
                print("Failed to set remote offer SDP: \(error)")
                return
            }
            self.makeAnswer(onCreateAnswer: onCreateAnswer)
        }
    }
    
    func receiveAnswer(answerSDP: RTCSessionDescription) {
        self.peerConnection!.setRemoteDescription(answerSDP) { (err) in
            if let error = err {
                print("Failed to set remote answer SDP: \(error)")
                return
            }
        }
    }
    
    func receiveCandidate(candidate: RTCIceCandidate) {
        self.peerConnection?.add(candidate) { err in
            if let error = err {
                print("Failed to set ice candidate: \(error.localizedDescription)")
            }
        }
    }
    
    // MARK: - Private Setup Methods
    private func setupPeerConnection() -> RTCPeerConnection {
        let rtcConf = RTCConfiguration()
        rtcConf.iceServers = [RTCIceServer(urlStrings: ["stun:stun.l.google.com:19302"])]
        
        let mediaConstraints = RTCMediaConstraints(mandatoryConstraints: nil, optionalConstraints: nil)
        
        let pc = self.peerConnectionFactory.peerConnection(with: rtcConf, constraints: mediaConstraints, delegate: nil)
        return pc!
    }
    
    private func setupView() {
        // Local View
        localRenderView = RTCMTLVideoView()
        localRenderView!.delegate = self
        localRenderView!.videoContentMode = .scaleAspectFill // Tam ekran dolgusu
        localView = UIView()
        localView.addSubview(localRenderView!)
        
        // Remote View
        remoteRenderView = RTCMTLVideoView()
        remoteRenderView?.delegate = self
        remoteRenderView!.videoContentMode = .scaleAspectFill // Tam ekran dolgusu
        remoteView = UIView()
        remoteView.addSubview(remoteRenderView!)
    }
    
    private func setupLocalTracks() {
        if self.channels.video {
            self.localVideoTrack = createVideoTrack()
        }
        if self.channels.audio {
            self.localAudioTrack = createAudioTrack()
        }
    }
    
    private func createAudioTrack() -> RTCAudioTrack {
        let audioConstrains = RTCMediaConstraints(mandatoryConstraints: nil, optionalConstraints: nil)
        let audioSource = self.peerConnectionFactory.audioSource(with: audioConstrains)
        let audioTrack = self.peerConnectionFactory.audioTrack(with: audioSource, trackId: "audio0")
        return audioTrack
    }
    
    private func createVideoTrack() -> RTCVideoTrack {
        let source = self.peerConnectionFactory.videoSource()
        self.videoSource = source
        
        #if targetEnvironment(simulator)
            print("Running on simulator: Using File Capturer")
            self.videoCapturer = RTCFileVideoCapturer(delegate: source)
        #else
            print("Running on device: Using Camera Capturer")
            self.videoCapturer = RTCCameraVideoCapturer(delegate: source)
        #endif
        
        return self.peerConnectionFactory.videoTrack(with: source, trackId: "video0")
    }
    
    private func startCaptureLocalVideo(cameraPositon: AVCaptureDevice.Position, videoWidth: Int, videoHeight: Int?, videoFps: Int) {
        if let capturer = self.videoCapturer as? RTCCameraVideoCapturer {
            var targetDevice: AVCaptureDevice?
            var targetFormat: AVCaptureDevice.Format?
            
            let devices = RTCCameraVideoCapturer.captureDevices()
            devices.forEach { (device) in
                if device.position == cameraPositon {
                    targetDevice = device
                }
            }
            
            guard let device = targetDevice else { return }
            
            let formats = RTCCameraVideoCapturer.supportedFormats(for: device)
            formats.forEach { (format) in
                for _ in format.videoSupportedFrameRateRanges {
                    let description = format.formatDescription as CMFormatDescription
                    let dimensions = CMVideoFormatDescriptionGetDimensions(description)
                    
                    if dimensions.width == videoWidth && dimensions.height == videoHeight ?? 0 {
                        targetFormat = format
                    } else if dimensions.width == videoWidth {
                        targetFormat = format
                    }
                }
            }
            
            if let format = targetFormat {
                capturer.startCapture(with: device, format: format, fps: videoFps)
            }
            
        } else if let capturer = self.videoCapturer as? RTCFileVideoCapturer {
            // Simulator Support
            if Bundle.main.path(forResource: "sample", ofType: "mp4") != nil {
                capturer.startCapturing(fromFileNamed: "sample.mp4") { err in
                    print(err as Any)
                }
            } else {
                print("Simulator video file (sample.mp4) not found in bundle.")
            }
        }
    }
    
    // MARK: - Signaling Helpers
    private func makeOffer(onSuccess: @escaping (RTCSessionDescription) -> Void) {
        self.peerConnection?.offer(for: RTCMediaConstraints(mandatoryConstraints: nil, optionalConstraints: nil)) { (sdp, err) in
            if let error = err {
                print("Error making offer: \(error)")
                return
            }
            
            if let offerSDP = sdp {
                self.peerConnection!.setLocalDescription(offerSDP, completionHandler: { (err) in
                    if let error = err {
                        print("Error setting local offer: \(error)")
                        return
                    }
                    onSuccess(offerSDP)
                })
            }
        }
    }
    
    private func makeAnswer(onCreateAnswer: @escaping (RTCSessionDescription) -> Void) {
        self.peerConnection!.answer(for: RTCMediaConstraints(mandatoryConstraints: nil, optionalConstraints: nil)) { (answerSessionDescription, err) in
            if let error = err {
                print("Error making answer: \(error)")
                return
            }
            
            if let answerSDP = answerSessionDescription {
                self.peerConnection!.setLocalDescription(answerSDP, completionHandler: { (err) in
                    if let error = err {
                        print("Error setting local answer: \(error)")
                        return
                    }
                    onCreateAnswer(answerSDP)
                })
            }
        }
    }
    
    // MARK: - Connection State Handlers
    private func onConnected() {
        self.isConnected = true
        DispatchQueue.main.async {
            self.remoteRenderView?.isHidden = false
            self.delegate?.didConnectWebRTC()
        }
    }
    
    private func onDisConnected() {
        self.isConnected = false
        DispatchQueue.main.async {
            self.peerConnection?.close()
            self.peerConnection = nil
            self.remoteRenderView?.isHidden = true
            self.delegate?.didDisconnectWebRTC()
        }
    }
}

// MARK: - RTCPeerConnectionDelegate
extension WebRTCClient {
    
    func peerConnection(_ peerConnection: RTCPeerConnection, didChange newState: RTCIceConnectionState) {
        switch newState {
        case .connected, .completed:
            if !self.isConnected {
                self.onConnected()
                self.delegate?.onPeersConnectionStatusChange(connected: true)
            }
        default:
            if self.isConnected && (newState == .disconnected || newState == .failed || newState == .closed) {
                self.onDisConnected()
                delegate?.onPeersConnectionStatusChange(connected: false)
            }
        }
        
        DispatchQueue.main.async {
            self.delegate?.didIceConnectionStateChanged(iceConnectionState: newState)
        }
    }
    
    func peerConnection(_ peerConnection: RTCPeerConnection, didGenerate candidate: RTCIceCandidate) {
        self.delegate?.didGenerateCandidate(iceCandidate: candidate)
    }
    
    func peerConnection(_ peerConnection: RTCPeerConnection, didAdd stream: RTCMediaStream) {
        print("Did add remote stream")
        self.remoteStream = stream
        
        if let track = stream.videoTracks.first {
            print("Remote video track found")
            track.add(remoteRenderView!)
        }
        
        if let audioTrack = stream.audioTracks.first {
            print("Remote audio track found")
            audioTrack.source.volume = 10
        }
    }
    
    func peerConnection(_ peerConnection: RTCPeerConnection, didRemove stream: RTCMediaStream) {
        print("Did remove remote stream")
    }
    
    func peerConnectionShouldNegotiate(_ peerConnection: RTCPeerConnection) {}
    func peerConnection(_ peerConnection: RTCPeerConnection, didChange stateChanged: RTCSignalingState) {}
    func peerConnection(_ peerConnection: RTCPeerConnection, didChange newState: RTCIceGatheringState) {}
    func peerConnection(_ peerConnection: RTCPeerConnection, didRemove candidates: [RTCIceCandidate]) {}
    
    // Data Channel (Removed logic but kept empty delegate methods if needed by protocol conformance internally)
    func peerConnection(_ peerConnection: RTCPeerConnection, didOpen dataChannel: RTCDataChannel) {
        print("Data channel opened but ignored (Chat feature removed)")
    }
}

// MARK: - RTCVideoViewDelegate
extension WebRTCClient {
    func videoView(_ videoView: RTCVideoRenderer, didChangeVideoSize size: CGSize) {
        let isLandscape = size.width > size.height
        var renderView: RTCMTLVideoView?
        var parentView: UIView?
        
        if videoView.isEqual(localRenderView) {
            renderView = localRenderView
            parentView = localView
        } else if videoView.isEqual(remoteRenderView!) {
            renderView = remoteRenderView
            parentView = remoteView
        }
        
        guard let _renderView = renderView, let _parentView = parentView else { return }
        
        if isLandscape {
            let ratio = size.height / size.width
            _renderView.frame = CGRect(x: 0, y: 0, width: _parentView.frame.width, height: _parentView.frame.width * ratio)
            _renderView.center.y = _parentView.frame.height / 2
        } else {
            let ratio = size.width / size.height
            _renderView.frame = CGRect(x: 0, y: 0, width: _parentView.frame.height * ratio, height: _parentView.frame.height)
            _renderView.center.x = _parentView.frame.width / 2
        }
    }
}

// MARK: - Public Control Methods
extension WebRTCClient {
    func toggleVideo(enable: Bool) {
        localVideoTrack.isEnabled = enable
    }
    
    func toggleAudio(enable: Bool) {
        localAudioTrack.isEnabled = enable
    }
    
    func switchCamera() {
        guard !isSwitchingCamera else { return }
        guard let capturer = self.videoCapturer as? RTCCameraVideoCapturer else { return }
        
        isSwitchingCamera = true
        capturer.stopCapture { [weak self] in
            guard let self = self else { return }
            self.useFrontCamera.toggle()
            let newPosition: AVCaptureDevice.Position = self.useFrontCamera ? .front : .back
            
            self.startCaptureLocalVideo(
                cameraPositon: newPosition,
                videoWidth: 640,
                videoHeight: 640 * 16 / 9,
                videoFps: 30
            )
            self.isSwitchingCamera = false
        }
    }
}
